package com.achintha.orderservice.security;

/** Account status owned by user-service (section 3.1), as published on {@code user-events}. */
public enum UserStatus {
    ACTIVE,
    PENDING_APPROVAL,
    REJECTED,
    /** Merchant banned but still in the silent grace period: the store stops accepting orders. */
    BAN_GRACE,
    BANNED
}
