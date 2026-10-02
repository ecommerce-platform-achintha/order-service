package com.achintha.orderservice.payment;

public enum FlagStatus {
    OPEN,
    /** An admin looked at it; nothing further. */
    REVIEWED,
    /** An admin escalated it to a customer or merchant ban (carried out in user-service). */
    ESCALATED
}
