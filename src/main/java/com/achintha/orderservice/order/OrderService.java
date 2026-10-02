package com.achintha.orderservice.order;

import com.achintha.orderservice.client.StoreServiceClient.BankAccount;
import com.achintha.orderservice.client.StoreServiceClient.StoreCourier;
import com.achintha.orderservice.client.StoreServiceGateway;
import com.achintha.orderservice.common.Money;
import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.TextSanitizer;
import com.achintha.orderservice.customer.CodObjection;
import com.achintha.orderservice.customer.CodObjectionRepository;
import com.achintha.orderservice.customer.ObjectionStatus;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.InvalidOrderStateException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.OrderDtos.ChargeLine;
import com.achintha.orderservice.order.OrderDtos.DeliveryFailedRequest;
import com.achintha.orderservice.order.OrderDtos.ObjectionRequest;
import com.achintha.orderservice.order.OrderDtos.QuoteLine;
import com.achintha.orderservice.order.OrderDtos.QuoteRequest;
import com.achintha.orderservice.order.OrderDtos.RejectRequest;
import com.achintha.orderservice.order.OrderDtos.ResolveRequest;
import com.achintha.orderservice.order.OrderDtos.ShipRequest;
import com.achintha.orderservice.order.OrderViews.Audience;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.security.AccountGuard;
import com.achintha.orderservice.security.Actor;
import com.achintha.orderservice.shipment.Shipment;
import com.achintha.orderservice.shipment.ShipmentRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order use cases for customers, stores (merchant and assistants) and admins. Every lookup is scoped by the caller's
 * JWT (customer id or store id); a foreign order is "not found". Status changes are delegated to
 * {@link OrderStateMachine}.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStateMachine stateMachine;
    private final OrderViews views;
    private final StoreServiceGateway storeService;
    private final ShipmentRepository shipmentRepository;
    private final CodObjectionRepository objectionRepository;
    private final AccountGuard accountGuard;
    private final PlatformSettings settings;
    private final NotificationPort notifications;
    private final Clock clock;

    // ============================================================================================ customer

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse.Summary> customerOrders(Actor actor, Collection<OrderStatus> statuses,
                                                              Pageable pageable) {
        Page<Order> page = statuses == null || statuses.isEmpty()
                ? orderRepository.findAllByCustomerId(actor.id(), pageable)
                : orderRepository.findAllByCustomerIdAndStatusIn(actor.id(), statuses, pageable);
        return PageResponse.from(page, o -> views.summary(o, Audience.CUSTOMER));
    }

    @Transactional(readOnly = true)
    public OrderResponse customerOrder(Actor actor, String publicId) {
        return views.detail(customerOwned(actor, publicId), Audience.CUSTOMER);
    }

    /** Free cancellation, only before the merchant quotes (D6). */
    @Transactional
    public OrderResponse cancel(Actor actor, String publicId) {
        Order order = customerOwned(actor, publicId);
        order.setReasonCode("CUSTOMER_CANCELLED");
        stateMachine.apply(order, OrderAction.CUSTOMER_CANCEL, actor);
        return views.detail(order, Audience.CUSTOMER);
    }

    @Transactional
    public OrderResponse confirmQuote(Actor actor, String publicId) {
        accountGuard.requireCustomerCanBuy(actor);
        Order order = customerOwned(actor, publicId);
        stateMachine.apply(order, OrderAction.CUSTOMER_CONFIRM, actor);
        return views.detail(order, Audience.CUSTOMER);
    }

    /** Declining a quote costs the customer {@code score.customer.declined} (D6). */
    @Transactional
    public OrderResponse declineQuote(Actor actor, String publicId) {
        Order order = customerOwned(actor, publicId);
        order.setReasonCode("QUOTE_DECLINED");
        stateMachine.apply(order, OrderAction.CUSTOMER_DECLINE, actor);
        return views.detail(order, Audience.CUSTOMER);
    }

    @Transactional
    public OrderResponse markReceived(Actor actor, String publicId) {
        Order order = customerOwned(actor, publicId);
        stateMachine.apply(order, OrderAction.MARK_RECEIVED, actor);
        return views.detail(order, Audience.CUSTOMER);
    }

    /**
     * The store's active bank accounts, with full numbers, for the order's own customer while a transfer is
     * expected ({@code AWAITING_PAYMENT}, or {@code PAYMENT_SUBMITTED} to check what was paid).
     */
    @Transactional(readOnly = true)
    public List<BankAccountView> bankAccounts(Actor actor, String publicId) {
        Order order = customerOwned(actor, publicId);
        if (order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER
                || (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                && order.getStatus() != OrderStatus.PAYMENT_SUBMITTED)) {
            throw new ConflictException(ErrorCode.INVALID_ORDER_STATE,
                    "Bank details are shown only while a bank transfer is expected");
        }
        return storeService.bankAccounts(order.getStoreId()).stream().map(BankAccountView::from).toList();
    }

    /** A store bank account as shown to the paying customer. */
    public record BankAccountView(String publicId, String bankCode, String bankName, String branch,
                                  String accountName, String accountNumber) {

        static BankAccountView from(BankAccount account) {
            return new BankAccountView(account.publicId(), account.bankCode(), account.bankName(), account.branch(),
                    account.accountName(), account.accountNumber());
        }

        @Override
        public String toString() {
            return "BankAccountView[publicId=" + publicId + "]";
        }
    }

    /** Objection to a COD refusal, within {@code cod.objection-window-days} of the failure (section 13.1). */
    @Transactional
    public OrderResponse objectToCodRefusal(Actor actor, String publicId, ObjectionRequest request) {
        Order order = customerOwned(actor, publicId);
        if (order.getStatus() != OrderStatus.DELIVERY_FAILED || order.getPaymentMethod() != PaymentMethod.COD) {
            throw new ConflictException(ErrorCode.OBJECTION_NOT_ALLOWED,
                    "Only a failed COD delivery can be objected to");
        }
        Instant now = clock.instant();
        int windowDays = settings.intValue(SettingKeys.COD_OBJECTION_WINDOW_DAYS);
        if (now.isAfter(order.getDeliveryFailedAt().plus(Duration.ofDays(windowDays)))) {
            throw new ConflictException(ErrorCode.OBJECTION_NOT_ALLOWED,
                    "Objections are accepted within " + windowDays + " days of the failed delivery");
        }
        if (objectionRepository.existsByOrderId(order.getId())) {
            throw new ConflictException(ErrorCode.OBJECTION_ALREADY_EXISTS, "This order already has an objection");
        }
        CodObjection objection = new CodObjection();
        objection.setId(UUID.randomUUID());
        objection.setOrderId(order.getId());
        objection.setCustomerId(order.getCustomerId());
        objection.setStoreId(order.getStoreId());
        objection.setText(requireText(TextSanitizer.clean(request.text())));
        objection.setStatus(ObjectionStatus.OPEN);
        objection.setCreatedAt(now);
        objectionRepository.save(objection);
        notifications.notifyAdmins("COD_OBJECTION", order.getPublicId());
        return views.detail(order, Audience.CUSTOMER);
    }

    // =========================================================================================== merchant

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse.Summary> storeOrders(Actor actor, Collection<OrderStatus> statuses,
                                                           Pageable pageable) {
        Page<Order> page = statuses == null || statuses.isEmpty()
                ? orderRepository.findAllByStoreId(actor.storeId(), pageable)
                : orderRepository.findAllByStoreIdAndStatusIn(actor.storeId(), statuses, pageable);
        return PageResponse.from(page, o -> views.summary(o, Audience.STORE));
    }

    @Transactional(readOnly = true)
    public OrderResponse storeOrder(Actor actor, String publicId) {
        return views.detail(storeOwned(actor, publicId), Audience.STORE);
    }

    /** Rejection before quoting (out of stock, low score, COD history, ...): no penalty for either side. */
    @Transactional
    public OrderResponse reject(Actor actor, String publicId, RejectRequest request) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOwned(actor, publicId);
        order.setReasonCode(request.reasonCode().name());
        order.setReason(TextSanitizer.cleanLine(request.note()));
        stateMachine.apply(order, OrderAction.MERCHANT_REJECT, actor);
        return views.detail(order, Audience.STORE);
    }

    /**
     * Quote (or re-quote): lines may only be reduced or removed, the courier charge is mandatory, labelled other
     * charges and a quote discount are optional. Starts (or restarts) the customer's confirmation timer and shrinks
     * the stock hold to the quoted quantities.
     */
    @Transactional
    public OrderResponse quote(Actor actor, String publicId, QuoteRequest request) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOwned(actor, publicId);
        if (!OrderAction.MERCHANT_QUOTE.from().contains(order.getStatus())) {
            // Checked again by the state machine; here first, so the store-service call is not wasted
            throw new InvalidOrderStateException(
                    "Order " + order.getPublicId() + " is " + order.getStatus() + ": it cannot be quoted");
        }
        StoreCourier courier = storeCourier(order, request.courierCode());

        Map<String, Integer> wanted = new HashMap<>();
        for (QuoteLine line : request.lines()) {
            if (wanted.put(line.variantPublicId(), line.quantity()) != null) {
                throw ApiException.badRequest(ErrorCode.INVALID_QUOTE, "A variant appears in two lines");
            }
        }
        Map<String, OrderItem> current = new HashMap<>();
        order.getItems().forEach(i -> current.put(i.getVariantPublicId(), i));
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            OrderItem item = current.get(line.getKey());
            if (item == null || item.getQuantity() == 0) {
                throw ApiException.badRequest(ErrorCode.INVALID_QUOTE,
                        "A quote cannot add items: " + line.getKey() + " is not in the order");
            }
            if (line.getValue() > item.getQuantity()) {
                throw ApiException.badRequest(ErrorCode.INVALID_QUOTE,
                        "A quote may only reduce quantities (" + line.getKey() + ")");
            }
        }
        if (wanted.values().stream().noneMatch(q -> q > 0)) {
            throw ApiException.badRequest(ErrorCode.INVALID_QUOTE,
                    "At least one line must remain; reject the order instead");
        }
        for (OrderItem item : order.getItems()) {
            item.setQuantity(wanted.getOrDefault(item.getVariantPublicId(), 0));
        }
        order.setCourierCode(courier.code());
        order.setCourierCharge(Money.of(request.courierCharge()));
        order.getOtherCharges().clear();
        if (request.otherCharges() != null) {
            for (ChargeLine charge : request.otherCharges()) {
                order.getOtherCharges().add(new OrderCharge(requireText(TextSanitizer.cleanLine(charge.label())),
                        Money.of(charge.amount())));
            }
        }
        order.setQuoteDiscount(Money.orZero(request.quoteDiscount()));
        order.recalculateTotals();
        if (order.getGrandTotal().signum() < 0) {
            throw ApiException.badRequest(ErrorCode.INVALID_QUOTE, "The quote discount exceeds the order total");
        }
        stateMachine.apply(order, OrderAction.MERCHANT_QUOTE, actor);
        return views.detail(order, Audience.STORE);
    }

    /**
     * Ships with a courier from the store's list; the tracking number must match the courier's format and the
     * tracking link is built from its template. Starts the auto-complete timer.
     */
    @Transactional
    public OrderResponse ship(Actor actor, String publicId, ShipRequest request) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOwned(actor, publicId);
        if (order.getStatus() != OrderStatus.READY_TO_SHIP) {
            throw new InvalidOrderStateException(
                    "Order " + order.getPublicId() + " is " + order.getStatus() + ": it cannot be shipped");
        }
        StoreCourier courier = storeCourier(order, request.courierCode());
        String trackingNumber = request.trackingNumber().strip();
        if (courier.trackingNumberRegex() != null && !courier.trackingNumberRegex().isBlank()) {
            boolean valid;
            try {
                valid = Pattern.compile(courier.trackingNumberRegex()).matcher(trackingNumber).matches();
            } catch (PatternSyntaxException e) {
                valid = true; // a broken master-data regex must not block shipping
            }
            if (!valid) {
                throw ApiException.badRequest(ErrorCode.INVALID_TRACKING_NUMBER,
                        "The tracking number does not match " + courier.name() + "'s format");
            }
        }
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(order.getId());
        shipment.setCourierCode(courier.code());
        shipment.setCourierName(courier.name());
        shipment.setTrackingNumber(trackingNumber);
        shipment.setTrackingUrl(courier.trackingUrlTemplate().replace("{trackingNumber}",
                URLEncoder.encode(trackingNumber, StandardCharsets.UTF_8)));
        shipment.setShippedAt(clock.instant());
        shipment.setShippedBy(actor.publicId());
        order.setCourierCode(courier.code());
        stateMachine.apply(order, OrderAction.SHIP, actor);
        shipmentRepository.save(shipment);
        return views.detail(order, Audience.STORE);
    }

    /**
     * COD refused or undeliverable. For a COD order this counts as a COD refusal (no score change); the third one
     * suspends the customer's COD for 3 months.
     */
    @Transactional
    public OrderResponse deliveryFailed(Actor actor, String publicId, DeliveryFailedRequest request) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOwned(actor, publicId);
        order.setDeliveryFailureType(request.type());
        order.setReasonCode(request.type().name());
        order.setReason(TextSanitizer.cleanLine(request.note()));
        stateMachine.apply(order, OrderAction.DELIVERY_FAILED, actor);
        return views.detail(order, Audience.STORE);
    }

    // ============================================================================================== admin

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse.Summary> adminOrders(Collection<OrderStatus> statuses, boolean needsResolution,
                                                           Pageable pageable) {
        Collection<OrderStatus> filter = statuses == null || statuses.isEmpty() ? OrderStatus.OPEN : statuses;
        Page<Order> page = needsResolution
                ? orderRepository.findAllByNeedsAdminResolutionTrueAndStatusIn(filter, pageable)
                : statuses == null || statuses.isEmpty()
                ? orderRepository.findAll(pageable)
                : orderRepository.findAllByStatusIn(filter, pageable);
        return PageResponse.from(page, o -> views.summary(o, Audience.ADMIN));
    }

    @Transactional(readOnly = true)
    public OrderResponse adminOrder(String publicId) {
        return views.detail(orderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Order not found")), Audience.ADMIN);
    }

    /** {@code FORCE_COMPLETE} or {@code CANCEL} (closed by admin), with a reason and an audit entry (section 3.2). */
    @Transactional
    public OrderResponse resolve(Actor actor, String publicId, ResolveRequest request) {
        Order order = orderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.setReasonCode("ADMIN_" + request.action().name());
        order.setReason(requireText(TextSanitizer.cleanLine(request.reason())));
        stateMachine.apply(order, request.action() == OrderDtos.Resolution.FORCE_COMPLETE
                ? OrderAction.ADMIN_FORCE_COMPLETE : OrderAction.ADMIN_CLOSE, actor);
        return views.detail(order, Audience.ADMIN);
    }

    // ============================================================================================ helpers

    Order customerOwned(Actor actor, String publicId) {
        return orderRepository.findByPublicIdAndCustomerId(publicId, actor.id())
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    Order storeOwned(Actor actor, String publicId) {
        if (actor.storeId() == null) {
            throw new NotFoundException("Order not found");
        }
        return orderRepository.findByPublicIdAndStoreId(publicId, actor.storeId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    /** One of the store's active couriers; for a COD order it must also support COD. */
    private StoreCourier storeCourier(Order order, String courierCode) {
        StoreCourier courier = storeService.couriers(order.getStoreId()).stream()
                .filter(c -> c.active() && c.code().equals(courierCode))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.COURIER_NOT_AVAILABLE,
                        "Courier " + courierCode + " is not one of the store's couriers"));
        if (order.getPaymentMethod() == PaymentMethod.COD && !courier.codSupported()) {
            throw ApiException.badRequest(ErrorCode.COURIER_NOT_AVAILABLE,
                    "Courier " + courierCode + " does not collect cash on delivery");
        }
        return courier;
    }

    private static String requireText(String cleaned) {
        if (cleaned == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Text must not be empty");
        }
        return cleaned;
    }

    /** Sortable fields of order lists. */
    public static final Map<String, String> SORTABLE = Map.of(
            "placedAt", "placedAt",
            "updatedAt", "updatedAt",
            "grandTotal", "grandTotal",
            "status", "status",
            "deadlineAt", "deadlineAt");
}
