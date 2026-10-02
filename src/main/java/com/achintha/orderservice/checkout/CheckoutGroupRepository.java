package com.achintha.orderservice.checkout;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutGroupRepository extends JpaRepository<CheckoutGroup, UUID> {

    boolean existsByPublicId(String publicId);
}
