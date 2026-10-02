package com.achintha.orderservice.payment;

import com.achintha.orderservice.common.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A bank-transfer payment the customer reports ({@code PAY-}). The depositing account number is stored only
 * encrypted (AES-GCM) plus its last 4 digits for display. A unique partial index makes the (normalised reference,
 * destination bank) pair usable by one {@code SUBMITTED} or {@code VERIFIED} submission across all stores.
 */
@Entity
@Table(name = "payment_submissions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentSubmission {

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

    @Column(name = "store_bank_account_id", nullable = false)
    private UUID storeBankAccountId;

    @Column(name = "store_bank_account_pid", nullable = false, length = 20)
    private String storeBankAccountPublicId;

    @Column(name = "destination_bank_code", nullable = false, length = 20)
    private String destinationBankCode;

    @Column(name = "reference_number", nullable = false, length = 64)
    private String referenceNumber;

    @Column(name = "normalized_reference", nullable = false, length = 64)
    private String normalizedReference;

    @Column(name = "source_bank_code", nullable = false, length = 20)
    private String sourceBankCode;

    @Column(name = "source_account_encrypted", nullable = false, length = 255)
    private String sourceAccountEncrypted;

    @Column(name = "source_account_last4", nullable = false, length = 4)
    private String sourceAccountLast4;

    @Convert(converter = StringListConverter.class)
    @Column(name = "attachment_keys", columnDefinition = "text")
    private List<String> attachmentKeys = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** publicId of the merchant or assistant who verified or rejected it. */
    @Column(name = "decided_by", length = 20)
    private String decidedBy;
}
