package com.achintha.orderservice.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlaggedPaymentReferenceRepository extends JpaRepository<FlaggedPaymentReference, UUID> {

    boolean existsByPublicId(String publicId);

    Optional<FlaggedPaymentReference> findByPublicId(String publicId);

    Page<FlaggedPaymentReference> findAllByStatus(FlagStatus status, Pageable pageable);
}
