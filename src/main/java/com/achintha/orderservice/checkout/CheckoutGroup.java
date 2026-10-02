package com.achintha.orderservice.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One checkout ({@code CHK-}): the orders it created, one per store, share this group. */
@Entity
@Table(name = "checkout_groups")
@Getter
@Setter
@NoArgsConstructor
public class CheckoutGroup {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 20)
    private String publicId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
