package com.achintha.orderservice.cart;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    boolean existsByPublicId(String publicId);
}
