package com.achintha.orderservice.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A store refusing a customer's future orders ({@code CUSTOMER_BLOCK}); checked at checkout. */
@Entity
@Table(name = "store_customer_blocks")
@Getter
@Setter
@NoArgsConstructor
public class StoreCustomerBlock {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_public_id", nullable = false, length = 20)
    private String customerPublicId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "blocked_by", nullable = false, length = 20)
    private String blockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
