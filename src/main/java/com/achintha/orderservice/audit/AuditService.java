package com.achintha.orderservice.audit;

import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes audit rows for privileged mutations (admin, super admin, merchant owner, assistant, system), in the
 * caller's transaction so the row exists exactly when the change does. Callers pass snapshots with sensitive fields
 * already masked (never a depositor account number, only its last four digits).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_PAYMENT = "PAYMENT";
    public static final String TARGET_FLAGGED_REFERENCE = "FLAGGED_REFERENCE";
    public static final String TARGET_COMPLAINT = "COMPLAINT";
    public static final String TARGET_COD_OBJECTION = "COD_OBJECTION";
    public static final String TARGET_CUSTOMER_BLOCK = "CUSTOMER_BLOCK";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String targetType, String targetId,
                       Map<String, ?> before, Map<String, ?> after, String reason) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setOccurredAt(clock.instant());
        entry.setActorId(actor.id());
        entry.setActorPublicId(actor.publicId() != null ? actor.publicId() : Actor.SYSTEM);
        entry.setActorRole(Actor.SYSTEM.equals(actor.publicId()) ? Actor.SYSTEM : actor.role().name());
        entry.setActorStoreId(actor.storeId());
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setBeforeState(before == null ? null : objectMapper.writeValueAsString(before));
        entry.setAfterState(after == null ? null : objectMapper.writeValueAsString(after));
        entry.setReason(reason);
        repository.save(entry);
    }
}
