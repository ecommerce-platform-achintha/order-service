package com.achintha.orderservice.order;

import com.achintha.orderservice.product.ProductDtos.LineRequest;
import com.achintha.orderservice.product.ProductServiceGateway;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The order's stock hold in product-service, keyed by the order's publicId (section 6.2): reserved at placement,
 * adjusted by the quote and whenever the deadline moves (the hold expires with the deadline), committed on
 * {@code READY_TO_SHIP}, released on every terminal state without completion. All calls are idempotent.
 */
@Component
@RequiredArgsConstructor
public class StockHolds {

    private final ProductServiceGateway productService;

    public void reserve(Order order, Instant expiresAt) {
        productService.reserve(order.getPublicId(), expiresAt, lines(order));
    }

    /** Keeps the current quantities (dropping removed lines) and moves the expiry. */
    public void adjust(Order order, Instant expiresAt) {
        productService.adjust(order.getPublicId(), lines(order), expiresAt);
    }

    public void commit(Order order) {
        productService.commit(order.getPublicId());
        order.setStockCommitted(true);
    }

    /** Releases the hold unless it was committed (sold goods go back to stock only by a merchant stock edit). */
    public void releaseIfHeld(Order order) {
        if (!order.isStockCommitted()) {
            productService.release(order.getPublicId());
        }
    }

    private static List<LineRequest> lines(Order order) {
        return order.activeItems().stream().map(i -> LineRequest.of(i.getVariantId(), i.getQuantity())).toList();
    }
}
