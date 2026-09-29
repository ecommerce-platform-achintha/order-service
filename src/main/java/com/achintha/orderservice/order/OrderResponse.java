package com.achintha.orderservice.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(UUID id, UUID productId, String productName, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal) {

        static Item from(OrderItem item) {
            return new Item(item.getId(), item.getProductId(), item.getProductName(), item.getUnitPrice(),
                    item.getQuantity(), item.lineTotal());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getItems().stream().map(Item::from).toList(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
