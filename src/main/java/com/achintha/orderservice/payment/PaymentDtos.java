package com.achintha.orderservice.payment;

import com.achintha.orderservice.common.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * A bank transfer the customer made (section 6.3): the mandatory reference, the depositing bank and account, the
     * store account that was paid, and receipt attachments (mock storage keys).
     */
    public record PaymentRequest(
            @NotBlank @Pattern(regexp = Validation.REFERENCE_REGEX) String referenceNumber,
            @NotBlank @Pattern(regexp = Validation.CODE_REGEX) String sourceBankCode,
            @NotBlank @Pattern(regexp = Validation.ACCOUNT_NUMBER_REGEX) String sourceAccountNumber,
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String storeBankAccountPublicId,
            @Size(max = Validation.MAX_ATTACHMENTS)
            List<@NotBlank @Pattern(regexp = Validation.STORAGE_KEY_REGEX) String> attachmentKeys) {

        @Override
        public String toString() {
            return "PaymentRequest[storeBankAccountPublicId=" + storeBankAccountPublicId + ", sourceAccountNumber=****]";
        }
    }

    public enum FlagAction {
        /** Looked at; nothing further. */
        REVIEWED,
        /** Escalated to a customer or merchant ban (carried out in user-service by the admin). */
        ESCALATED
    }

    public record FlagReviewRequest(
            @NotNull FlagAction action,
            @NotBlank @Size(max = 1000) String note) {
    }

    public record FlaggedReferenceResponse(String publicId, String orderPublicId, String customerPublicId,
                                           String storePublicId, String reference, String bankCode,
                                           String originalOrderPublicId, FlagStatus status, String reviewNote,
                                           String reviewedBy, Instant reviewedAt, Instant createdAt) {

        static FlaggedReferenceResponse from(FlaggedPaymentReference f) {
            return new FlaggedReferenceResponse(f.getPublicId(), f.getOrderPublicId(), f.getCustomerPublicId(),
                    f.getStorePublicId(), f.getNormalizedReference(), f.getBankCode(),
                    f.getOriginalOrderPublicId(), f.getStatus(), f.getReviewNote(), f.getReviewedBy(),
                    f.getReviewedAt(), f.getCreatedAt());
        }
    }
}
