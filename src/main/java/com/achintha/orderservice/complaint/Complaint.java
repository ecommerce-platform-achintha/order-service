package com.achintha.orderservice.complaint;

import com.achintha.orderservice.common.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A customer complaint about an order ({@code CMP-}, section 6.6). No money is moved by the platform. */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
public class Complaint {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 20)
    private String publicId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "complaint_type", nullable = false, length = 30)
    private ComplaintType type;

    @Column(nullable = false, length = 4000)
    private String text;

    @Convert(converter = StringListConverter.class)
    @Column(name = "attachment_keys", columnDefinition = "text")
    private List<String> attachmentKeys = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ComplaintStatus status;

    @Column(name = "merchant_response", length = 4000)
    private String merchantResponse;

    @Column(name = "merchant_responded_at")
    private Instant merchantRespondedAt;

    @Column(name = "merchant_responded_by", length = 20)
    private String merchantRespondedBy;

    @Column(name = "admin_notes", length = 4000)
    private String adminNotes;

    @Column(name = "decided_by", length = 20)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
