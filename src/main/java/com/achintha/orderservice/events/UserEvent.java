package com.achintha.orderservice.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The fields order-service reads from {@code user-events} messages (published by user-service). Unknown fields are
 * ignored.
 *
 * @param status       the real status, including {@code BAN_GRACE}
 * @param tokenVersion tokens with a lower {@code tv} must be rejected
 */
public record UserEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID userId,
        String publicId,
        String role,
        String status,
        String previousStatus,
        UUID storeId,
        Long tokenVersion,
        String change) {
}
