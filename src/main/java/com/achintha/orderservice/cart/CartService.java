package com.achintha.orderservice.cart;

import com.achintha.orderservice.cart.CartDtos.AddItemRequest;
import com.achintha.orderservice.cart.CartDtos.CartLine;
import com.achintha.orderservice.cart.CartDtos.CartResponse;
import com.achintha.orderservice.cart.CartDtos.LineIssue;
import com.achintha.orderservice.cart.CartDtos.StoreGroup;
import com.achintha.orderservice.common.Money;
import com.achintha.orderservice.common.PublicIdGenerator;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.product.ProductDtos.QuoteItem;
import com.achintha.orderservice.product.ProductServiceGateway;
import com.achintha.orderservice.security.Actor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's cart (section 6.1). Only variants and quantities are stored; every read re-prices the lines live
 * from product-service ({@code POST /internal/variants/quote}) and reports availability and stock per line.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductServiceGateway productService;
    private final PublicIdGenerator publicIds;
    private final Clock clock;
    private final int maxLines;
    private final int maxQuantity;

    public CartService(CartRepository cartRepository, CartItemRepository cartItemRepository,
                       ProductServiceGateway productService, PublicIdGenerator publicIds, Clock clock,
                       @Value("${app.orders.max-cart-lines:100}") int maxLines,
                       @Value("${app.orders.max-line-quantity:100}") int maxQuantity) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productService = productService;
        this.publicIds = publicIds;
        this.clock = clock;
        this.maxLines = maxLines;
        this.maxQuantity = maxQuantity;
    }

    @Transactional(readOnly = true)
    public CartResponse get(Actor actor) {
        return cartRepository.findByCustomerId(actor.id()).map(this::view)
                .orElse(new CartResponse(List.of(), 0, Money.ZERO));
    }

    /** Adds a variant (or raises the quantity of its existing line). Only purchasable variants can be added. */
    @Transactional
    public CartResponse add(Actor actor, AddItemRequest request) {
        QuoteItem variant = productService.quoteByPublicId(request.variantPublicId());
        if (!variant.purchasable()) {
            throw new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE, "This item is not available");
        }
        Cart cart = cartFor(actor);
        Instant now = clock.instant();
        CartItem item = cart.findByVariant(variant.variantId()).orElse(null);
        if (item == null) {
            if (cart.getItems().size() >= maxLines) {
                throw new ConflictException(ErrorCode.CART_FULL, "A cart holds at most " + maxLines + " lines");
            }
            item = new CartItem();
            item.setId(UUID.randomUUID());
            item.setPublicId(publicIds.generateUnique(PublicIdGenerator.CART_ITEM_PREFIX,
                    cartItemRepository::existsByPublicId));
            item.setCart(cart);
            item.setVariantId(variant.variantId());
            item.setVariantPublicId(variant.variantPublicId());
            item.setStoreId(variant.storeId());
            item.setAddedAt(now);
            cart.getItems().add(item);
        }
        item.setQuantity(Math.min(maxQuantity, item.getQuantity() + request.quantity()));
        item.setUpdatedAt(now);
        cart.setUpdatedAt(now);
        cartRepository.saveAndFlush(cart);
        return view(cart);
    }

    @Transactional
    public CartResponse update(Actor actor, String cartItemPublicId, int quantity) {
        Cart cart = cartRepository.findByCustomerId(actor.id()).orElseThrow(CartService::itemNotFound);
        CartItem item = cart.findByPublicId(cartItemPublicId).orElseThrow(CartService::itemNotFound);
        item.setQuantity(Math.min(maxQuantity, quantity));
        item.setUpdatedAt(clock.instant());
        cart.setUpdatedAt(clock.instant());
        cartRepository.saveAndFlush(cart);
        return view(cart);
    }

    @Transactional
    public CartResponse remove(Actor actor, String cartItemPublicId) {
        Cart cart = cartRepository.findByCustomerId(actor.id()).orElseThrow(CartService::itemNotFound);
        CartItem item = cart.findByPublicId(cartItemPublicId).orElseThrow(CartService::itemNotFound);
        cart.getItems().remove(item);
        cart.setUpdatedAt(clock.instant());
        cartRepository.saveAndFlush(cart);
        return view(cart);
    }

    @Transactional
    public CartResponse clear(Actor actor) {
        cartRepository.findByCustomerId(actor.id()).ifPresent(cart -> {
            cart.getItems().clear();
            cart.setUpdatedAt(clock.instant());
        });
        return new CartResponse(List.of(), 0, Money.ZERO);
    }

    private Cart cartFor(Actor actor) {
        return cartRepository.findByCustomerId(actor.id()).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setId(UUID.randomUUID());
            cart.setCustomerId(actor.id());
            cart.setCreatedAt(clock.instant());
            cart.setUpdatedAt(clock.instant());
            return cartRepository.save(cart);
        });
    }

    private CartResponse view(Cart cart) {
        Map<UUID, QuoteItem> quotes = productService.quote(
                cart.getItems().stream().map(CartItem::getVariantId).distinct().toList());
        Map<UUID, List<CartLine>> byStore = new LinkedHashMap<>();
        Map<UUID, String[]> storeNames = new LinkedHashMap<>();
        for (CartItem item : cart.getItems()) {
            QuoteItem quote = quotes.get(item.getVariantId());
            byStore.computeIfAbsent(item.getStoreId(), k -> new ArrayList<>()).add(line(item, quote));
            if (quote != null) {
                storeNames.putIfAbsent(item.getStoreId(), new String[]{quote.storePublicId(), quote.storeName()});
            }
        }
        List<StoreGroup> groups = new ArrayList<>();
        BigDecimal total = Money.ZERO;
        for (Map.Entry<UUID, List<CartLine>> entry : byStore.entrySet()) {
            BigDecimal subtotal = entry.getValue().stream().filter(CartLine::available)
                    .map(CartLine::lineTotal).reduce(Money.ZERO, BigDecimal::add);
            String[] store = storeNames.getOrDefault(entry.getKey(), new String[]{null, null});
            groups.add(new StoreGroup(store[0], store[1], entry.getValue(), subtotal));
            total = total.add(subtotal);
        }
        return new CartResponse(groups, cart.getItems().size(), Money.of(total));
    }

    private static CartLine line(CartItem item, QuoteItem quote) {
        if (quote == null) {
            return new CartLine(item.getPublicId(), item.getVariantPublicId(), null, null, null, null, null, null,
                    item.getQuantity(), null, null, null, null, null, false, false, LineIssue.REMOVED);
        }
        LineIssue issue = !quote.purchasable() ? LineIssue.NOT_AVAILABLE
                : quote.availableQuantity() < item.getQuantity() ? LineIssue.INSUFFICIENT_STOCK : null;
        return new CartLine(item.getPublicId(), quote.variantPublicId(), quote.productPublicId(),
                quote.productName(), quote.variantName(), quote.sku(), quote.attributes(), quote.imageUrl(),
                item.getQuantity(), Money.of(quote.listPrice()), Money.of(quote.discountAmount()),
                Money.of(quote.unitPrice()), Money.times(quote.unitPrice(), item.getQuantity()),
                quote.availableQuantity(), issue == null, quote.codAllowed(), issue);
    }

    private static NotFoundException itemNotFound() {
        return new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND, "Cart item not found");
    }
}
