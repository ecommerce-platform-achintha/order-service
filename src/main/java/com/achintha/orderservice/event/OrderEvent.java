package com.achintha.orderservice.event;

import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload published to the order-events topic on every order status change. Items carry only productId and
 * quantity; consumers that need names or prices can fetch the order.
 */
public record OrderEvent(
        OrderEventType eventType,
        UUID orderId,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<Item> items,
        Instant timestamp) {

    public record Item(UUID productId, int quantity) {
    }

    /** Snapshot of the order as it is now; call it where the order's items are still loaded. */
    public static OrderEvent of(OrderEventType type, Order order) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getQuantity()))
                .toList();
        return new OrderEvent(type, order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                items, Instant.now());
    }
}
