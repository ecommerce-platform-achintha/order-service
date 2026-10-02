package com.achintha.orderservice.complaint;

import com.achintha.orderservice.common.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ComplaintDtos {

    private ComplaintDtos() {
    }

    public record CreateComplaintRequest(
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String orderPublicId,
            @NotNull ComplaintType type,
            @NotBlank @Size(max = Validation.LONG_TEXT_MAX) String text,
            @Size(max = Validation.MAX_ATTACHMENTS)
            List<@NotBlank @Pattern(regexp = Validation.STORAGE_KEY_REGEX) String> attachmentKeys) {
    }

    public record RespondRequest(@NotBlank @Size(max = Validation.LONG_TEXT_MAX) String response) {
    }

    public enum Decision {
        UPHELD,
        DISMISSED
    }

    public record DecideRequest(
            @NotNull Decision decision,
            @NotBlank @Size(max = Validation.LONG_TEXT_MAX) String notes) {
    }

    public record ComplaintResponse(String publicId, String orderPublicId, String storePublicId,
                                    String customerPublicId, ComplaintType type, String text,
                                    List<String> attachmentUrls, ComplaintStatus status, String merchantResponse,
                                    Instant merchantRespondedAt, String adminNotes, Instant decidedAt,
                                    Instant createdAt) {
    }
}
