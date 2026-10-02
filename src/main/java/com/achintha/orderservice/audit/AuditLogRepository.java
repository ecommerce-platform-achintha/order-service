package com.achintha.orderservice.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findAllByTargetTypeAndTargetIdOrderByIdAsc(String targetType, String targetId);
}
