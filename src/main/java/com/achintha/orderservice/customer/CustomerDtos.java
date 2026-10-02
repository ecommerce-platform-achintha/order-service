package com.achintha.orderservice.customer;

import com.achintha.orderservice.common.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** A store blocks one of its own customers (one who has ordered from it). */
    public record BlockRequest(
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String customerPublicId,
            @NotBlank @Size(max = Validation.REASON_MAX) String reason) {
    }

    public record BlockResponse(String customerPublicId, String reason, String blockedBy, Instant createdAt) {

        static BlockResponse from(StoreCustomerBlock block) {
            return new BlockResponse(block.getCustomerPublicId(), block.getReason(), block.getBlockedBy(),
                    block.getCreatedAt());
        }
    }

    public enum ObjectionDecision {
        UPHELD,
        DISMISSED
    }

    public record ObjectionDecisionRequest(
            @NotNull ObjectionDecision decision,
            @NotBlank @Size(max = 1000) String note) {
    }

    public record ObjectionResponse(String orderPublicId, String customerPublicId, String storePublicId, String text,
                                    ObjectionStatus status, String adminNote, Instant decidedAt, Instant createdAt) {
    }

    /** Admin view of a customer's standing. */
    public record CustomerScoreResponse(String customerPublicId, int score, int codRefusals,
                                        int codRefusalsTowardSuspension, boolean codSuspended,
                                        Instant codSuspendedUntil, List<ScoreEntry> recentEvents) {
    }

    public record ScoreEntry(ScoreEventType type, int delta, Instant createdAt) {
    }
}
