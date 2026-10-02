package com.achintha.orderservice.customer;

/** Customer score events (section 6.5); the deltas come from the platform settings. */
public enum ScoreEventType {
    /** {@code score.customer.completed} (+1), on completion (D7). */
    ORDER_COMPLETED,
    /** {@code score.customer.declined} (-1): the customer declined a quote. */
    QUOTE_DECLINED,
    /** {@code score.customer.expired} (-1): the quote expired unanswered. */
    QUOTE_EXPIRED,
    /** {@code score.customer.expired} (-1): the payment was never submitted. */
    PAYMENT_EXPIRED
}
