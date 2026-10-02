package com.achintha.orderservice.security;

/** One role per account (section 3.1), as carried in the JWT {@code role} claim. Super admin includes admin powers. */
public enum Role {
    ROLE_CUSTOMER,
    ROLE_MERCHANT,
    ROLE_ASSISTANT,
    ROLE_ADMIN,
    ROLE_SUPER_ADMIN,
    /** Internal service tokens only. */
    ROLE_SERVICE
}
