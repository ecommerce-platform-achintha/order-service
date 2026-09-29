package com.achintha.orderservice.event;

import com.fasterxml.jackson.annotation.JsonValue;

/** Value of the {@code eventType} discriminator in every message on the order-events topic. */
public enum OrderEventType {
    ORDER_CREATED("OrderCreated"),
    ORDER_CONFIRMED("OrderConfirmed"),
    ORDER_FAILED("OrderFailed"),
    ORDER_CANCELLED("OrderCancelled");

    private final String wireName;

    OrderEventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
