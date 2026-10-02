package com.achintha.orderservice.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** How an order was shipped: a courier from the store's list, a validated tracking number and its tracking link. */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "courier_code", nullable = false, length = 32)
    private String courierCode;

    @Column(name = "courier_name", nullable = false, length = 120)
    private String courierName;

    @Column(name = "tracking_number", nullable = false, length = 64)
    private String trackingNumber;

    @Column(name = "tracking_url", nullable = false, length = 600)
    private String trackingUrl;

    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt;

    @Column(name = "shipped_by", nullable = false, length = 20)
    private String shippedBy;
}
