package com.achintha.orderservice.checkout;

import com.achintha.orderservice.cart.Cart;
import com.achintha.orderservice.cart.CartItem;
import com.achintha.orderservice.cart.CartRepository;
import com.achintha.orderservice.checkout.CheckoutDtos.CheckoutRequest;
import com.achintha.orderservice.checkout.CheckoutDtos.CheckoutResponse;
import com.achintha.orderservice.checkout.CheckoutDtos.GroupResult;
import com.achintha.orderservice.checkout.CheckoutDtos.GroupStatus;
import com.achintha.orderservice.checkout.CheckoutDtos.StorePayment;
import com.achintha.orderservice.client.StoreServiceClient.StoreInfo;
import com.achintha.orderservice.client.StoreServiceGateway;
import com.achintha.orderservice.client.UserServiceClient.AddressResponse;
import com.achintha.orderservice.client.UserServiceClient.ProfileResponse;
import com.achintha.orderservice.client.UserServiceGateway;
import com.achintha.orderservice.common.Money;
import com.achintha.orderservice.common.PublicIdGenerator;
import com.achintha.orderservice.customer.CodPrivilegeService;
import com.achintha.orderservice.customer.StoreCustomerBlockRepository;
import com.achintha.orderservice.event.OrderEventPublisher;
import com.achintha.orderservice.event.OrderEventPublisher.Details;
import com.achintha.orderservice.event.OrderEventType;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.InsufficientStockException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.DeadlineType;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderItem;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.order.OrderTimers;
import com.achintha.orderservice.order.PaymentMethod;
import com.achintha.orderservice.order.StockHolds;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.product.ProductDtos.QuoteItem;
import com.achintha.orderservice.product.ProductServiceGateway;
import com.achintha.orderservice.security.AccountGuard;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checkout (section 6.1): the selected cart lines are grouped by store and one order per store is created under a
 * shared {@code CHK-} group. Each store group is placed in its own transaction, so if one store fails (not accepting
 * orders, blocked, COD not allowed, out of stock, ...) the others still succeed; the response reports each group.
 *
 * <p>Per group: preconditions (each with its own error code), prices snapshotted from a live quote, the merchant
 * deadline from {@link OrderTimers}, stock held in product-service until that deadline, purchased lines removed from
 * the cart, {@code OrderPlaced} written to the outbox. If anything fails after the hold was placed, the hold is
 * released again.
 */
@Slf4j
@Service
public class CheckoutService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CartRepository cartRepository;
    private final CheckoutGroupRepository groupRepository;
    private final OrderRepository orderRepository;
    private final StoreCustomerBlockRepository blockRepository;
    private final ProductServiceGateway productService;
    private final StoreServiceGateway storeService;
    private final UserServiceGateway userService;
    private final CodPrivilegeService codService;
    private final AccountGuard accountGuard;
    private final OrderTimers timers;
    private final StockHolds stockHolds;
    private final OrderEventPublisher events;
    private final PlatformSettings settings;
    private final PublicIdGenerator publicIds;
    private final NotificationPort notifications;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CheckoutService(CartRepository cartRepository, CheckoutGroupRepository groupRepository,
                           OrderRepository orderRepository, StoreCustomerBlockRepository blockRepository,
                           ProductServiceGateway productService, StoreServiceGateway storeService,
                           UserServiceGateway userService, CodPrivilegeService codService, AccountGuard accountGuard,
                           OrderTimers timers, StockHolds stockHolds, OrderEventPublisher events,
                           PlatformSettings settings, PublicIdGenerator publicIds, NotificationPort notifications,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.cartRepository = cartRepository;
        this.groupRepository = groupRepository;
        this.orderRepository = orderRepository;
        this.blockRepository = blockRepository;
        this.productService = productService;
        this.storeService = storeService;
        this.userService = userService;
        this.codService = codService;
        this.accountGuard = accountGuard;
        this.timers = timers;
        this.stockHolds = stockHolds;
        this.events = events;
        this.settings = settings;
        this.publicIds = publicIds;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What one store group needs, resolved before any order is written. */
    private record Group(UUID storeId, String storePublicId, String storeName, List<CartItem> lines,
                         PaymentMethod method) {
    }

    public CheckoutResponse checkout(Actor actor, CheckoutRequest request) {
        accountGuard.requireCustomerCanBuy(actor);
        List<CartItem> selected = selectedLines(actor, request.cartItemIds());
        Map<UUID, QuoteItem> quotes = productService.quote(selected.stream().map(CartItem::getVariantId).toList());
        List<Group> groups = group(selected, quotes, request.payments());

        // Same for every group: the customer's own address and contact, read with the customer's token
        AddressResponse address = userService.myAddress(request.addressPublicId(), actor.token());
        ProfileResponse profile = userService.me(actor.token());

        CheckoutGroup checkout = new CheckoutGroup();
        checkout.setId(UUID.randomUUID());
        checkout.setPublicId(publicIds.generateUnique(PublicIdGenerator.CHECKOUT_PREFIX,
                groupRepository::existsByPublicId));
        checkout.setCustomerId(actor.id());
        checkout.setCreatedAt(clock.instant());

        List<GroupResult> results = new ArrayList<>();
        boolean groupSaved = false;
        for (Group group : groups) {
            GroupResult result = placeGroup(actor, checkout, groupSaved, group, quotes, address, profile);
            groupSaved |= result.status() == GroupStatus.PLACED;
            results.add(result);
        }
        return new CheckoutResponse(groupSaved ? checkout.getPublicId() : null, results);
    }

    /** The chosen cart lines (read in a short transaction; only their plain fields are used afterwards). */
    private List<CartItem> selectedLines(Actor actor, List<String> cartItemIds) {
        return transactions.execute(tx -> {
            Cart cart = cartRepository.findByCustomerId(actor.id())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND, "Your cart is empty"));
            List<CartItem> selected = new ArrayList<>();
            for (String id : new LinkedHashSet<>(cartItemIds)) {
                selected.add(cart.findByPublicId(id).orElseThrow(() -> new NotFoundException(
                        ErrorCode.CART_ITEM_NOT_FOUND, "Cart item " + id + " not found")));
            }
            return selected;
        });
    }

    private List<Group> group(List<CartItem> selected, Map<UUID, QuoteItem> quotes, List<StorePayment> payments) {
        Map<String, PaymentMethod> methods = new HashMap<>();
        payments.forEach(p -> methods.put(p.storePublicId(), p.method()));
        Map<UUID, List<CartItem>> byStore = new LinkedHashMap<>();
        selected.forEach(i -> byStore.computeIfAbsent(i.getStoreId(), k -> new ArrayList<>()).add(i));
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<UUID, List<CartItem>> entry : byStore.entrySet()) {
            String storePublicId = null;
            String storeName = null;
            for (CartItem item : entry.getValue()) {
                QuoteItem quote = quotes.get(item.getVariantId());
                if (quote != null && quote.storePublicId() != null) {
                    storePublicId = quote.storePublicId();
                    storeName = quote.storeName();
                    break;
                }
            }
            if (storePublicId == null) {
                StoreInfo store = storeService.store(entry.getKey());
                storePublicId = store.publicId();
                storeName = store.name();
            }
            PaymentMethod method = methods.get(storePublicId);
            if (method == null) {
                throw ApiException.badRequest(ErrorCode.PAYMENT_METHOD_MISSING,
                        "Choose a payment method for store " + storePublicId);
            }
            groups.add(new Group(entry.getKey(), storePublicId, storeName, entry.getValue(), method));
        }
        return groups;
    }

    private GroupResult placeGroup(Actor actor, CheckoutGroup checkout, boolean groupSaved, Group group,
                                   Map<UUID, QuoteItem> quotes, AddressResponse address, ProfileResponse profile) {
        Set<String> heldRefs = new LinkedHashSet<>();
        try {
            Order order = transactions.execute(tx -> {
                StoreInfo store = checkPreconditions(actor, group, quotes);
                if (!groupSaved) {
                    groupRepository.save(checkout);
                }
                Order placed = buildOrder(actor, checkout, group, store, quotes, address, profile);
                orderRepository.saveAndFlush(placed);
                heldRefs.add(placed.getPublicId());
                stockHolds.reserve(placed, placed.getDeadlineAt());
                removeFromCart(actor, group);
                events.publish(placed, OrderEventType.OrderPlaced, Details.NONE, checkout.getPublicId());
                return placed;
            });
            notifications.notifyStore(group.storeId(), "ORDER_PLACED", order.getPublicId());
            return new GroupResult(group.storePublicId(), order.getStoreName(), GroupStatus.PLACED,
                    order.getPublicId(), group.method(), order.getGrandTotal(), null, null);
        } catch (RuntimeException e) {
            heldRefs.forEach(this::compensate);
            ErrorCode code = e instanceof ApiException api ? api.code() : ErrorCode.CHECKOUT_FAILED;
            String message = e instanceof ApiException ? e.getMessage() : "This store's order could not be placed";
            if (!(e instanceof ApiException)) {
                log.error("Checkout group for store {} failed", group.storePublicId(), e);
            }
            return new GroupResult(group.storePublicId(), group.storeName(), GroupStatus.FAILED, null,
                    group.method(), null, code.name(), message);
        }
    }

    /** Every server-side precondition of section 6.1, each with its own error code. */
    private StoreInfo checkPreconditions(Actor actor, Group group, Map<UUID, QuoteItem> quotes) {
        StoreInfo store = storeService.store(group.storeId());
        if (!store.visible() || !store.acceptingOrders()) {
            throw new ConflictException(ErrorCode.STORE_NOT_ACCEPTING_ORDERS,
                    store.name() + " is not accepting orders right now");
        }
        if (blockRepository.existsByStoreIdAndCustomerId(group.storeId(), actor.id())) {
            throw ApiException.forbidden(ErrorCode.CUSTOMER_BLOCKED_BY_STORE,
                    store.name() + " does not accept orders from your account");
        }
        for (CartItem line : group.lines()) {
            QuoteItem quote = quotes.get(line.getVariantId());
            if (quote == null || !quote.purchasable() || !group.storeId().equals(quote.storeId())) {
                throw new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE,
                        "An item from " + store.name() + " is no longer available");
            }
            if (quote.availableQuantity() < line.getQuantity()) {
                throw new InsufficientStockException("Only " + quote.availableQuantity() + " left of "
                        + quote.productName());
            }
        }
        if (group.method() == PaymentMethod.COD) {
            if (!store.codEnabled()) {
                throw new ConflictException(ErrorCode.COD_NOT_AVAILABLE_FOR_STORE,
                        store.name() + " does not offer cash on delivery");
            }
            if (group.lines().stream().anyMatch(l -> !quotes.get(l.getVariantId()).codAllowed())) {
                throw new ConflictException(ErrorCode.COD_NOT_ALLOWED_FOR_PRODUCT,
                        "An item from " + store.name() + " cannot be paid cash on delivery");
            }
            if (codService.isSuspended(actor.id())) {
                throw ApiException.forbidden(ErrorCode.COD_SUSPENDED,
                        "Cash on delivery is suspended for your account");
            }
        }
        int maxOpen = settings.intValue(SettingKeys.MAX_OPEN_UNCONFIRMED_PER_CUSTOMER);
        if (orderRepository.countByCustomerIdAndStatusIn(actor.id(), OrderStatus.UNCONFIRMED) >= maxOpen) {
            throw new ConflictException(ErrorCode.OPEN_ORDER_LIMIT_REACHED,
                    "You have " + maxOpen + " orders waiting for confirmation; wait for them before ordering more");
        }
        return store;
    }

    private Order buildOrder(Actor actor, CheckoutGroup checkout, Group group, StoreInfo store,
                             Map<UUID, QuoteItem> quotes, AddressResponse address, ProfileResponse profile) {
        Instant now = clock.instant();
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setPublicId(publicIds.generateUnique(PublicIdGenerator.ORDER_PREFIX, orderRepository::existsByPublicId));
        order.setCheckoutGroupId(checkout.getId());
        order.setCustomerId(actor.id());
        order.setCustomerPublicId(actor.publicId());
        order.setStoreId(group.storeId());
        order.setStorePublicId(store.publicId());
        order.setStoreName(store.name());
        order.setStatus(OrderStatus.AWAITING_MERCHANT);
        order.setPaymentMethod(group.method());

        String contactName = join(profile.firstName(), profile.lastName());
        order.setShipAddressPublicId(address.publicId());
        order.setShipRecipientName(firstNonBlank(address.recipientName(), contactName, "Customer"));
        order.setShipPhone(firstNonBlank(address.phone(), profile.phone(), "-"));
        order.setShipLine1(address.line1());
        order.setShipLine2(address.line2());
        order.setShipCity(address.city());
        order.setShipDistrict(address.district());
        order.setShipPostalCode(address.postalCode());
        order.setShipCountry(firstNonBlank(address.country(), "Sri Lanka"));
        order.setContactName(firstNonBlank(contactName, "Customer"));
        order.setContactPhone(profile.phone());
        order.setContactEmail(profile.email());

        for (CartItem line : group.lines()) {
            QuoteItem quote = quotes.get(line.getVariantId());
            OrderItem item = new OrderItem();
            item.setId(UUID.randomUUID());
            item.setVariantId(quote.variantId());
            item.setItemPublicId(quote.productPublicId());
            item.setVariantPublicId(quote.variantPublicId());
            item.setName(quote.productName());
            item.setVariantName(quote.variantName());
            item.setSku(quote.sku());
            item.setAttributes(quote.attributes() == null ? null : JSON.writeValueAsString(quote.attributes()));
            item.setOrderedQuantity(line.getQuantity());
            item.setQuantity(line.getQuantity());
            item.setListPrice(Money.of(quote.listPrice()));
            item.setDiscountAmount(Money.of(quote.discountAmount()));
            item.setUnitPrice(Money.of(quote.unitPrice()));
            item.setCodAllowed(quote.codAllowed());
            order.addItem(item);
        }
        order.recalculateTotals();
        order.setPlacedAt(now);
        order.setUpdatedAt(now);
        order.setDeadline(DeadlineType.MERCHANT_RESPONSE, timers.merchantResponse(now));
        return order;
    }

    private void removeFromCart(Actor actor, Group group) {
        Set<UUID> purchased = new LinkedHashSet<>();
        group.lines().forEach(l -> purchased.add(l.getId()));
        Cart cart = cartRepository.findByCustomerId(actor.id()).orElseThrow();
        cart.getItems().removeIf(i -> purchased.contains(i.getId()));
        cart.setUpdatedAt(clock.instant());
        cartRepository.saveAndFlush(cart);
    }

    /** The order was not saved after all: give the held stock back (product-service is idempotent per orderRef). */
    private void compensate(String orderRef) {
        try {
            productService.release(orderRef);
        } catch (RuntimeException e) {
            // product-service's safety net releases the hold after its expiry anyway
            log.warn("Could not release the stock hold of failed order {}: {}", orderRef, e.getMessage());
        }
    }

    private static String join(String first, String last) {
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return joined.isEmpty() ? null : joined;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
