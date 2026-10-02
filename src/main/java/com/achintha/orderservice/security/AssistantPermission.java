package com.achintha.orderservice.security;

/** Per-assistant permissions (section 3.4). The merchant holds all of them. */
public enum AssistantPermission {
    ORDER_VIEW,
    /** Quote, revise and reject orders. */
    ORDER_QUOTE,
    PAYMENT_VERIFY,
    ORDER_SHIP,
    PRODUCT_EDIT,
    STOCK_EDIT,
    DISCOUNT_MANAGE,
    CUSTOMER_BLOCK,
    REVIEW_REPLY;

    /** Granted-authority name used by Spring Security, e.g. {@code PERM_ORDER_VIEW}. */
    public String authority() {
        return "PERM_" + name();
    }
}
