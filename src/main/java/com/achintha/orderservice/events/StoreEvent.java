package com.achintha.orderservice.events;

import java.time.Instant;
import java.util.UUID;

/** The fields order-service reads from {@code store-events} messages. Unknown fields are ignored. */
public record StoreEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID storeId,
        String settingKey) {
}
