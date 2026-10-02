package com.achintha.orderservice.customer;

import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer score (section 6.5): completed +1, declined or expired -1 (deltas from the settings). Merchant rejections
 * and COD refusals never change it. Idempotent per (customer, order, type).
 */
@Service
@RequiredArgsConstructor
public class CustomerScoreService {

    private final CustomerScoreEventRepository repository;
    private final PlatformSettings settings;
    private final Clock clock;

    /** @return the applied delta, or 0 if this event was already recorded */
    @Transactional(propagation = Propagation.MANDATORY)
    public int apply(UUID customerId, UUID orderId, ScoreEventType type) {
        if (repository.existsByCustomerIdAndOrderIdAndEventType(customerId, orderId, type)) {
            return 0;
        }
        int delta = settings.intValue(switch (type) {
            case ORDER_COMPLETED -> SettingKeys.CUSTOMER_SCORE_COMPLETED;
            case QUOTE_DECLINED -> SettingKeys.CUSTOMER_SCORE_DECLINED;
            case QUOTE_EXPIRED, PAYMENT_EXPIRED -> SettingKeys.CUSTOMER_SCORE_EXPIRED;
        });
        CustomerScoreEvent event = new CustomerScoreEvent();
        event.setCustomerId(customerId);
        event.setOrderId(orderId);
        event.setEventType(type);
        event.setDelta(delta);
        event.setCreatedAt(clock.instant());
        repository.save(event);
        return delta;
    }

    @Transactional(readOnly = true)
    public int scoreOf(UUID customerId) {
        return repository.scoreOf(customerId);
    }
}
