package com.achintha.orderservice.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmission, UUID> {

    boolean existsByPublicId(String publicId);

    List<PaymentSubmission> findAllByOrderIdOrderBySubmittedAtAsc(UUID orderId);

    Optional<PaymentSubmission> findFirstByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    /** The earlier submission that already uses this reference with this bank (any store, any customer). */
    Optional<PaymentSubmission> findFirstByNormalizedReferenceAndDestinationBankCodeAndStatusIn(
            String normalizedReference, String destinationBankCode, Collection<PaymentStatus> statuses);
}
