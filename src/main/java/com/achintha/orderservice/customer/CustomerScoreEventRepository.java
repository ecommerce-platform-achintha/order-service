package com.achintha.orderservice.customer;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerScoreEventRepository extends JpaRepository<CustomerScoreEvent, Long> {

    boolean existsByCustomerIdAndOrderIdAndEventType(UUID customerId, UUID orderId, ScoreEventType eventType);

    @Query("select coalesce(sum(e.delta), 0) from CustomerScoreEvent e where e.customerId = :customerId")
    int scoreOf(@Param("customerId") UUID customerId);

    Page<CustomerScoreEvent> findAllByCustomerIdOrderByIdDesc(UUID customerId, Pageable pageable);
}
