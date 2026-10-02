package com.achintha.orderservice.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON payload of every {@code order-events} message, keyed by {@code orderId}. Fields that do not apply to an event
 * are omitted. No PII (no address, phone or account numbers) is published.
 *
 * @param status          the order's status after the change
 * @param previousStatus  the status before it
 * @param terminalStatus  {@code OrderCancelled}: which terminal state (e.g. {@code EXPIRED_MERCHANT})
 * @param reason          reason code or text of a rejection, cancellation, failure or admin resolution
 * @param placedAt        when the order was placed (store-service measures the merchant's response time)
 * @param quoteRevision   {@code OrderQuoted}: 1 for the first quote, higher for re-quotes
 * @param merchantPenalty penalty hint for store-service: {@code RESPONSE_TIMEOUT}, {@code LATE_SHIPMENT},
 *                        {@code LATE_VERIFICATION} or {@code COMPLAINT_UPHELD}
 * @param customerPenalty the customer score change applied here, for information: {@code DECLINED} or
 *                        {@code EXPIRED}
 * @param lines           the order's lines at the time (variant ids and quantities; quantity 0 = removed)
 * @param decision        {@code ComplaintDecided}: {@code UPHELD} or {@code DISMISSED}
 * @param resolution      {@code CLOSED_BY_ADMIN} / admin {@code FORCE_COMPLETE}: the admin action
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEvent(
        UUID eventId,
        OrderEventType eventType,
        Instant occurredAt,
        UUID orderId,
        String orderPublicId,
        String checkoutPublicId,
        UUID storeId,
        String storePublicId,
        UUID customerId,
        String customerPublicId,
        String status,
        String previousStatus,
        String terminalStatus,
        String reason,
        String paymentMethod,
        BigDecimal itemsTotal,
        BigDecimal courierCharge,
        BigDecimal otherChargesTotal,
        BigDecimal quoteDiscount,
        BigDecimal grandTotal,
        Instant placedAt,
        Integer quoteRevision,
        String merchantPenalty,
        String customerPenalty,
        List<Line> lines,
        String courierCode,
        String trackingNumber,
        String deliveryFailureType,
        String complaintPublicId,
        String decision,
        String flaggedReferencePublicId,
        String resolution) {

    /** One order line: variant UUID and publicId, quantity. */
    public record Line(UUID variantId, String variantPublicId, String itemPublicId, int quantity) {
    }
}
