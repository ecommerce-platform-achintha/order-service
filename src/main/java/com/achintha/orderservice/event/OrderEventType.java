package com.achintha.orderservice.event;

/** Events on the {@code order-events} topic (section 9). The enum name is the wire {@code eventType}. */
public enum OrderEventType {
    /** One per order created by a checkout ({@code AWAITING_MERCHANT}). */
    OrderPlaced,
    /** The merchant quoted (or re-quoted: {@code quoteRevision} > 1). */
    OrderQuoted,
    /** The customer accepted the quote ({@code READY_TO_SHIP} for COD, {@code AWAITING_PAYMENT} for bank). */
    OrderConfirmed,
    OrderPaymentSubmitted,
    /** The merchant verified the transfer ({@code READY_TO_SHIP}). */
    OrderPaymentVerified,
    /** The merchant rejected the transfer; the customer may resubmit until the payment deadline. */
    OrderPaymentRejected,
    OrderShipped,
    /** {@code COMPLETED}: received, auto-completed, or force-completed by an admin. */
    OrderCompleted,
    /** Every terminal state without completion; {@code terminalStatus} tells which. */
    OrderCancelled,
    OrderDeliveryFailed,
    /** Ship-by deadline missed: the order stays open, merchant penalty hint {@code LATE_SHIPMENT}. */
    OrderShipmentOverdue,
    /** Payment-verification deadline missed: the order stays open, merchant penalty hint {@code LATE_VERIFICATION}. */
    OrderVerificationOverdue,
    /** The merchant was banned with this order still open: an admin must resolve it. */
    OrderNeedsAdminResolution,
    /** A duplicate bank reference was submitted and rejected (D9). */
    PaymentReferenceFlagged,
    /** An admin decided a complaint; {@code UPHELD} carries the merchant penalty hint {@code COMPLAINT_UPHELD}. */
    ComplaintDecided
}
