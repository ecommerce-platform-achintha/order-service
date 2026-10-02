package com.achintha.orderservice.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer's COD privilege (section 6.5, D8). Each refusal adds to {@code refusalCount}; reaching
 * {@code score.customer.cod-refusal-limit} suspends COD until {@code suspendedUntil} and starts a new count.
 * {@code totalRefusals} is what merchants see. One row per customer, created on first use.
 */
@Entity
@Table(name = "customer_cod_state")
@Getter
@Setter
@NoArgsConstructor
public class CustomerCodState {

    @Id
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_public_id", nullable = false, length = 20)
    private String customerPublicId;

    @Column(name = "refusal_count", nullable = false)
    private int refusalCount;

    @Column(name = "total_refusals", nullable = false)
    private int totalRefusals;

    @Column(name = "suspended_until")
    private Instant suspendedUntil;

    /** The refusal (order) that started the current suspension; an upheld objection to it lifts the suspension. */
    @Column(name = "suspension_order_id")
    private UUID suspensionOrderId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public boolean isSuspended(Instant now) {
        return suspendedUntil != null && now.isBefore(suspendedUntil);
    }
}
