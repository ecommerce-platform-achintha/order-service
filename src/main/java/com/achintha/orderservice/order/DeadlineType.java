package com.achintha.orderservice.order;

/** Which timer {@code deadlineAt} belongs to (section 6.4). */
public enum DeadlineType {
    /** {@code AWAITING_MERCHANT}: expires the order ({@code EXPIRED_MERCHANT}, merchant penalty). */
    MERCHANT_RESPONSE,
    /** {@code AWAITING_CUSTOMER_CONFIRMATION}: expires the order ({@code EXPIRED_CUSTOMER}, customer -1). */
    CUSTOMER_CONFIRMATION,
    /** {@code AWAITING_PAYMENT}: expires the order ({@code EXPIRED_PAYMENT}, customer -1). */
    PAYMENT_SUBMISSION,
    /** {@code PAYMENT_SUBMITTED}: flags the order ({@code lateVerification}, merchant penalty), keeps it open. */
    PAYMENT_VERIFICATION,
    /** {@code READY_TO_SHIP}: flags the order ({@code lateShipment}, merchant penalty), keeps it open. */
    SHIP_BY,
    /** {@code SHIPPED}: completes the order 7 days after shipping (D10). */
    AUTO_COMPLETE
}
