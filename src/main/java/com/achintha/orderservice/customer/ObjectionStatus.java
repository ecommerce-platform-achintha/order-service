package com.achintha.orderservice.customer;

public enum ObjectionStatus {
    OPEN,
    /** Decided for the customer: the refusal is reversed and any suspension it caused is lifted. */
    UPHELD,
    DISMISSED
}
