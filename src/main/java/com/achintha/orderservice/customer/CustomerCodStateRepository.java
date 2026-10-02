package com.achintha.orderservice.customer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerCodStateRepository extends JpaRepository<CustomerCodState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CustomerCodState s where s.customerId = :customerId")
    Optional<CustomerCodState> lockById(@Param("customerId") UUID customerId);

    Optional<CustomerCodState> findByCustomerPublicId(String customerPublicId);
}
