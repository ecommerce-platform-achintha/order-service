package com.achintha.orderservice.customer;

import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The COD privilege (D8, section 6.5). A refusal adds to the count without touching the score; the
 * {@code score.customer.cod-refusal-limit}-th refusal (3) suspends COD for {@code score.customer.cod-suspension-months}
 * (3) and starts a new count. An upheld objection reverses its refusal and lifts the suspension it caused.
 */
@Service
public class CodPrivilegeService {

    private final CustomerCodStateRepository repository;
    private final PlatformSettings settings;
    private final Clock clock;
    private final ZoneId zone;

    public CodPrivilegeService(CustomerCodStateRepository repository, PlatformSettings settings, Clock clock,
                               @Value("${app.timezone:Asia/Colombo}") String timezone) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    /** What merchants and admins see. */
    public record CodView(int totalRefusals, int refusalsTowardSuspension, Instant suspendedUntil, boolean suspended) {
    }

    @Transactional(readOnly = true)
    public boolean isSuspended(UUID customerId) {
        return repository.findById(customerId).map(s -> s.isSuspended(clock.instant())).orElse(false);
    }

    @Transactional(readOnly = true)
    public CodView view(UUID customerId) {
        Optional<CustomerCodState> state = repository.findById(customerId);
        Instant now = clock.instant();
        return state.map(s -> new CodView(s.getTotalRefusals(), s.getRefusalCount(),
                        s.isSuspended(now) ? s.getSuspendedUntil() : null, s.isSuspended(now)))
                .orElse(new CodView(0, 0, null, false));
    }

    /** @return {@code true} if this refusal started a suspension */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordRefusal(UUID customerId, String customerPublicId, UUID orderId) {
        CustomerCodState state = lockOrCreate(customerId, customerPublicId);
        Instant now = clock.instant();
        state.setRefusalCount(state.getRefusalCount() + 1);
        state.setTotalRefusals(state.getTotalRefusals() + 1);
        boolean suspends = state.getRefusalCount() >= settings.intValue(SettingKeys.COD_REFUSAL_LIMIT);
        if (suspends) {
            int months = settings.intValue(SettingKeys.COD_SUSPENSION_MONTHS);
            state.setSuspendedUntil(now.atZone(zone).plusMonths(months).toInstant());
            state.setSuspensionOrderId(orderId);
            state.setRefusalCount(0);
        }
        state.setUpdatedAt(now);
        repository.save(state);
        return suspends;
    }

    /** An admin upheld the customer's objection to the refusal of {@code orderId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseRefusal(UUID customerId, String customerPublicId, UUID orderId) {
        CustomerCodState state = lockOrCreate(customerId, customerPublicId);
        state.setTotalRefusals(Math.max(0, state.getTotalRefusals() - 1));
        if (orderId.equals(state.getSuspensionOrderId())) {
            // This refusal started the suspension: lift it and restore the count it reset
            state.setSuspendedUntil(null);
            state.setSuspensionOrderId(null);
            state.setRefusalCount(Math.max(0, settings.intValue(SettingKeys.COD_REFUSAL_LIMIT) - 1));
        } else {
            state.setRefusalCount(Math.max(0, state.getRefusalCount() - 1));
        }
        state.setUpdatedAt(clock.instant());
        repository.save(state);
    }

    private CustomerCodState lockOrCreate(UUID customerId, String customerPublicId) {
        return repository.lockById(customerId).orElseGet(() -> {
            CustomerCodState created = new CustomerCodState();
            created.setCustomerId(customerId);
            created.setCustomerPublicId(customerPublicId);
            created.setUpdatedAt(clock.instant());
            return repository.saveAndFlush(created);
        });
    }
}
