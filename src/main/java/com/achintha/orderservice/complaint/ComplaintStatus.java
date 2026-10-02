package com.achintha.orderservice.complaint;

public enum ComplaintStatus {
    OPEN,
    /** The merchant responded; waiting for an admin decision. */
    UNDER_REVIEW,
    /** Decided for the customer: store-service applies the merchant penalty ({@code ComplaintDecided}). */
    UPHELD,
    DISMISSED
}
