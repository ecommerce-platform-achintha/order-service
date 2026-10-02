package com.achintha.orderservice.complaint;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    boolean existsByPublicId(String publicId);

    Optional<Complaint> findByPublicId(String publicId);

    Optional<Complaint> findByPublicIdAndCustomerId(String publicId, UUID customerId);

    Optional<Complaint> findByPublicIdAndStoreId(String publicId, UUID storeId);

    Page<Complaint> findAllByCustomerId(UUID customerId, Pageable pageable);

    Page<Complaint> findAllByStoreId(UUID storeId, Pageable pageable);

    Page<Complaint> findAllByStatusIn(Collection<ComplaintStatus> statuses, Pageable pageable);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<ComplaintStatus> statuses);
}
