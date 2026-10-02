package com.achintha.orderservice.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreCustomerBlockRepository extends JpaRepository<StoreCustomerBlock, UUID> {

    boolean existsByStoreIdAndCustomerId(UUID storeId, UUID customerId);

    Optional<StoreCustomerBlock> findByStoreIdAndCustomerPublicId(UUID storeId, String customerPublicId);

    Page<StoreCustomerBlock> findAllByStoreId(UUID storeId, Pageable pageable);
}
