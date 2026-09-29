package com.achintha.orderservice.order;

import java.util.Set;

/**
 * Order lifecycle:
 * <pre>
 * PENDING ──(stock decremented)──▶ CONFIRMED
 *    │  └──(decrement failed)────▶ FAILED
 *    └───────(cancel)──────────▶ CANCELLED ◀──(cancel)── CONFIRMED
 * </pre>
 * CANCELLED and FAILED are terminal.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED;

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<OrderStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, FAILED, CANCELLED);
            case CONFIRMED -> Set.of(CANCELLED);
            case CANCELLED, FAILED -> Set.of();
        };
    }
}
