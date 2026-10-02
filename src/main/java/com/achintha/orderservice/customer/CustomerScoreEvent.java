package com.achintha.orderservice.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One change of a customer's score; the score is the sum of the deltas. Unique per (customer, order, type). */
@Entity
@Table(name = "customer_score_events")
@Getter
@Setter
@NoArgsConstructor
public class CustomerScoreEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ScoreEventType eventType;

    @Column(nullable = false)
    private int delta;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
