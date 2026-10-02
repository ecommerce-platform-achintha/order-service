package com.achintha.orderservice.order;

import com.achintha.orderservice.platform.DeadlineCalculator;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The timers of section 6.4, with their lengths read from the platform settings on every use (never hard-coded):
 * hour-based timers skip holidays ({@link DeadlineCalculator}); auto-complete is in plain days.
 */
@Component
@RequiredArgsConstructor
public class OrderTimers {

    private final DeadlineCalculator calculator;
    private final PlatformSettings settings;

    public Instant merchantResponse(Instant start) {
        return hours(start, SettingKeys.MERCHANT_RESPONSE_HOURS);
    }

    public Instant customerConfirmation(Instant start) {
        return hours(start, SettingKeys.CUSTOMER_CONFIRMATION_HOURS);
    }

    public Instant paymentSubmission(Instant start) {
        return hours(start, SettingKeys.PAYMENT_SUBMISSION_HOURS);
    }

    public Instant paymentVerification(Instant start) {
        return hours(start, SettingKeys.PAYMENT_VERIFICATION_HOURS);
    }

    public Instant shipBy(Instant start) {
        return hours(start, SettingKeys.SHIP_BY_HOURS);
    }

    public Instant autoComplete(Instant start) {
        return calculator.plusDays(start, settings.intValue(SettingKeys.AUTO_COMPLETE_DAYS));
    }

    private Instant hours(Instant start, String key) {
        return calculator.plusHours(start, settings.intValue(key));
    }
}
