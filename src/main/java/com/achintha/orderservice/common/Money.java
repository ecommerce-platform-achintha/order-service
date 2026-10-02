package com.achintha.orderservice.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** LKR amounts: {@link BigDecimal} with exactly 2 decimals (section 1), never floats. */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private Money() {
    }

    /** Scales to 2 decimals (half-up); {@code null} stays {@code null}. */
    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? ZERO : of(amount);
    }

    public static BigDecimal times(BigDecimal unit, int quantity) {
        return of(unit.multiply(BigDecimal.valueOf(quantity)));
    }

    /** At most 2 decimals given by the client (more is a validation error, not silently rounded). */
    public static boolean hasAtMostTwoDecimals(BigDecimal amount) {
        return amount == null || amount.stripTrailingZeros().scale() <= 2;
    }
}
