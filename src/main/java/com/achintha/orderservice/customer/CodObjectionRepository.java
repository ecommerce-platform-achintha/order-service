package com.achintha.orderservice.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodObjectionRepository extends JpaRepository<CodObjection, UUID> {

    Optional<CodObjection> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    Page<CodObjection> findAllByStatus(ObjectionStatus status, Pageable pageable);
}
