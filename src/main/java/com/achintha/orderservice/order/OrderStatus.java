package com.achintha.orderservice.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle (section 6.2). One order per store.
 *
 * <pre>
 * AWAITING_MERCHANT -> AWAITING_CUSTOMER_CONFIRMATION -> (COD) READY_TO_SHIP
 *                                                     -> (bank) AWAITING_PAYMENT -> PAYMENT_SUBMITTED -> READY_TO_SHIP
 * READY_TO_SHIP -> SHIPPED -> COMPLETED
 * </pre>
 *
 * Terminal: {@code COMPLETED} and the nine states without completion.
 */
public enum OrderStatus {
    AWAITING_MERCHANT,
    AWAITING_CUSTOMER_CONFIRMATION,
    AWAITING_PAYMENT,
    PAYMENT_SUBMITTED,
    READY_TO_SHIP,
    SHIPPED,
    COMPLETED,

    CANCELLED_BY_CUSTOMER,
    REJECTED_BY_MERCHANT,
    EXPIRED_MERCHANT,
    DECLINED_BY_CUSTOMER,
    EXPIRED_CUSTOMER,
    EXPIRED_PAYMENT,
    DELIVERY_FAILED,
    CANCELLED_BY_SYSTEM,
    CLOSED_BY_ADMIN;

    /** Not yet confirmed by the customer: counted against the open-order cap, cancelled when a customer is banned. */
    public static final Set<OrderStatus> UNCONFIRMED = EnumSet.of(AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION);

    public static final Set<OrderStatus> OPEN = EnumSet.of(AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION,
            AWAITING_PAYMENT, PAYMENT_SUBMITTED, READY_TO_SHIP, SHIPPED);

    /** Stock is held (not yet committed) in these states. */
    public static final Set<OrderStatus> STOCK_HELD = EnumSet.of(AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION,
            AWAITING_PAYMENT, PAYMENT_SUBMITTED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isTerminal() {
        return !isOpen();
    }

    /** Terminal without completion: reported as {@code OrderCancelled} with this as {@code terminalStatus}. */
    public boolean isCancellation() {
        return isTerminal() && this != COMPLETED && this != DELIVERY_FAILED;
    }
}
