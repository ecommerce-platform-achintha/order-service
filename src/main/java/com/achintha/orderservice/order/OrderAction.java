package com.achintha.orderservice.order;

import static com.achintha.orderservice.order.OrderStatus.AWAITING_CUSTOMER_CONFIRMATION;
import static com.achintha.orderservice.order.OrderStatus.AWAITING_MERCHANT;
import static com.achintha.orderservice.order.OrderStatus.AWAITING_PAYMENT;
import static com.achintha.orderservice.order.OrderStatus.PAYMENT_SUBMITTED;
import static com.achintha.orderservice.order.OrderStatus.READY_TO_SHIP;
import static com.achintha.orderservice.order.OrderStatus.SHIPPED;

import com.achintha.orderservice.security.AssistantPermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Every transition of section 6.2: the states it may start from, who may perform it and, for merchant-side actions,
 * the assistant permission it needs. {@link OrderStateMachine} enforces all three.
 */
public enum OrderAction {
    CUSTOMER_CANCEL(Performer.CUSTOMER, null, AWAITING_MERCHANT),
    MERCHANT_REJECT(Performer.MERCHANT, AssistantPermission.ORDER_QUOTE, AWAITING_MERCHANT),
    /** First quote, or a re-quote while the customer has not answered (limited, resets the timer). */
    MERCHANT_QUOTE(Performer.MERCHANT, AssistantPermission.ORDER_QUOTE, AWAITING_MERCHANT,
            AWAITING_CUSTOMER_CONFIRMATION),
    CUSTOMER_CONFIRM(Performer.CUSTOMER, null, AWAITING_CUSTOMER_CONFIRMATION),
    CUSTOMER_DECLINE(Performer.CUSTOMER, null, AWAITING_CUSTOMER_CONFIRMATION),
    PAYMENT_SUBMIT(Performer.CUSTOMER, null, AWAITING_PAYMENT),
    PAYMENT_VERIFY(Performer.MERCHANT, AssistantPermission.PAYMENT_VERIFY, PAYMENT_SUBMITTED),
    PAYMENT_REJECT(Performer.MERCHANT, AssistantPermission.PAYMENT_VERIFY, PAYMENT_SUBMITTED),
    SHIP(Performer.MERCHANT, AssistantPermission.ORDER_SHIP, READY_TO_SHIP),
    MARK_RECEIVED(Performer.CUSTOMER, null, SHIPPED),
    DELIVERY_FAILED(Performer.MERCHANT, AssistantPermission.ORDER_SHIP, SHIPPED),
    EXPIRE_MERCHANT(Performer.SYSTEM, null, AWAITING_MERCHANT),
    EXPIRE_CUSTOMER(Performer.SYSTEM, null, AWAITING_CUSTOMER_CONFIRMATION),
    EXPIRE_PAYMENT(Performer.SYSTEM, null, AWAITING_PAYMENT),
    AUTO_COMPLETE(Performer.SYSTEM, null, SHIPPED),
    /** The customer was banned: unconfirmed orders end without penalty. */
    SYSTEM_CANCEL(Performer.SYSTEM, null, AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION),
    ADMIN_CLOSE(Performer.ADMIN, null, AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION, AWAITING_PAYMENT,
            PAYMENT_SUBMITTED, READY_TO_SHIP, SHIPPED),
    ADMIN_FORCE_COMPLETE(Performer.ADMIN, null, AWAITING_MERCHANT, AWAITING_CUSTOMER_CONFIRMATION, AWAITING_PAYMENT,
            PAYMENT_SUBMITTED, READY_TO_SHIP, SHIPPED);

    /** Who may perform an action. */
    public enum Performer {
        /** The order's own customer. */
        CUSTOMER,
        /** The order's store: the merchant, or an assistant holding {@link #permission()}. */
        MERCHANT,
        /** Admin or super admin. */
        ADMIN,
        /** Scheduler or event handler. */
        SYSTEM
    }

    private final Performer performer;
    private final AssistantPermission permission;
    private final Set<OrderStatus> from;

    OrderAction(Performer performer, AssistantPermission permission, OrderStatus first, OrderStatus... rest) {
        this.performer = performer;
        this.permission = permission;
        this.from = EnumSet.of(first, rest);
    }

    public Performer performer() {
        return performer;
    }

    public AssistantPermission permission() {
        return permission;
    }

    public Set<OrderStatus> from() {
        return from;
    }

    /** The target state; confirmation depends on the payment method. */
    public OrderStatus target(Order order) {
        return switch (this) {
            case CUSTOMER_CANCEL -> OrderStatus.CANCELLED_BY_CUSTOMER;
            case MERCHANT_REJECT -> OrderStatus.REJECTED_BY_MERCHANT;
            case MERCHANT_QUOTE -> AWAITING_CUSTOMER_CONFIRMATION;
            case CUSTOMER_CONFIRM -> order.getPaymentMethod() == PaymentMethod.COD ? READY_TO_SHIP : AWAITING_PAYMENT;
            case CUSTOMER_DECLINE -> OrderStatus.DECLINED_BY_CUSTOMER;
            case PAYMENT_SUBMIT -> PAYMENT_SUBMITTED;
            case PAYMENT_VERIFY -> READY_TO_SHIP;
            case PAYMENT_REJECT -> AWAITING_PAYMENT;
            case SHIP -> SHIPPED;
            case MARK_RECEIVED, AUTO_COMPLETE, ADMIN_FORCE_COMPLETE -> OrderStatus.COMPLETED;
            case DELIVERY_FAILED -> OrderStatus.DELIVERY_FAILED;
            case EXPIRE_MERCHANT -> OrderStatus.EXPIRED_MERCHANT;
            case EXPIRE_CUSTOMER -> OrderStatus.EXPIRED_CUSTOMER;
            case EXPIRE_PAYMENT -> OrderStatus.EXPIRED_PAYMENT;
            case SYSTEM_CANCEL -> OrderStatus.CANCELLED_BY_SYSTEM;
            case ADMIN_CLOSE -> OrderStatus.CLOSED_BY_ADMIN;
        };
    }
}
