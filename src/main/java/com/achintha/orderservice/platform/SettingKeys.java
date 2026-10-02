package com.achintha.orderservice.platform;

import java.util.List;

/**
 * The platform settings (section 7) order-service reads from store-service. Values are never hard-coded here: they
 * come from {@code GET /internal/settings} through {@link PlatformSettings}.
 */
public final class SettingKeys {

    public static final String MERCHANT_RESPONSE_HOURS = "timers.merchant-response-hours";
    public static final String CUSTOMER_CONFIRMATION_HOURS = "timers.customer-confirmation-hours";
    public static final String PAYMENT_SUBMISSION_HOURS = "timers.payment-submission-hours";
    public static final String PAYMENT_VERIFICATION_HOURS = "timers.payment-verification-hours";
    public static final String SHIP_BY_HOURS = "timers.ship-by-hours";
    public static final String AUTO_COMPLETE_DAYS = "timers.auto-complete-days";
    public static final String CUSTOMER_SCORE_COMPLETED = "score.customer.completed";
    public static final String CUSTOMER_SCORE_DECLINED = "score.customer.declined";
    public static final String CUSTOMER_SCORE_EXPIRED = "score.customer.expired";
    public static final String COD_OBJECTION_WINDOW_DAYS = "cod.objection-window-days";
    public static final String COD_REFUSAL_LIMIT = "score.customer.cod-refusal-limit";
    public static final String COD_SUSPENSION_MONTHS = "score.customer.cod-suspension-months";
    public static final String MAX_OPEN_UNCONFIRMED_PER_CUSTOMER = "orders.max-open-unconfirmed-per-customer";
    public static final String MAX_QUOTE_REVISIONS = "orders.max-quote-revisions";
    public static final String COMPLAINTS_WINDOW_DAYS = "complaints.window-days";

    /** Every key this service uses (documented in the README). */
    public static final List<String> ALL = List.of(MERCHANT_RESPONSE_HOURS, CUSTOMER_CONFIRMATION_HOURS,
            PAYMENT_SUBMISSION_HOURS, PAYMENT_VERIFICATION_HOURS, SHIP_BY_HOURS, AUTO_COMPLETE_DAYS,
            CUSTOMER_SCORE_COMPLETED, CUSTOMER_SCORE_DECLINED, CUSTOMER_SCORE_EXPIRED, COD_OBJECTION_WINDOW_DAYS,
            COD_REFUSAL_LIMIT, COD_SUSPENSION_MONTHS, MAX_OPEN_UNCONFIRMED_PER_CUSTOMER, MAX_QUOTE_REVISIONS,
            COMPLAINTS_WINDOW_DAYS);

    private SettingKeys() {
    }
}
