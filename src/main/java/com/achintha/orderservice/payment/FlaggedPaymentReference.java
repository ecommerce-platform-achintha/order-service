package com.achintha.orderservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A rejected duplicate bank reference (D9, {@code FLG-}): kept separately from submissions, with a pointer to the
 * original usage, for the admin review queue.
 */
@Entity
@Table(name = "flagged_payment_references")
@Getter
@Setter
@NoArgsConstructor
public class FlaggedPaymentReference {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 20)
    private String publicId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_public_id", nullable = false, length = 20)
    private String orderPublicId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_public_id", nullable = false, length = 20)
    private String customerPublicId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "store_public_id", nullable = false, length = 20)
    private String storePublicId;

    @Column(name = "normalized_reference", nullable = false, length = 64)
    private String normalizedReference;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "original_submission_id")
    private UUID originalSubmissionId;

    @Column(name = "original_order_public_id", length = 20)
    private String originalOrderPublicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlagStatus status;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "reviewed_by", length = 20)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;
}
