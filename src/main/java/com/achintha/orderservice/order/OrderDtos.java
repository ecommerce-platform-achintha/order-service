package com.achintha.orderservice.order;

import com.achintha.orderservice.common.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the order endpoints. None carries a status, price, customer, store or score: those are always
 * server-controlled (section 11).
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** Merchant rejection before quoting; no reason carries a penalty. */
    public record RejectRequest(
            @NotNull RejectionReason reasonCode,
            @Size(max = Validation.REASON_MAX) String note) {
    }

    /**
     * The merchant's quote. {@code lines} lists the lines to keep with their (equal or lower) quantities; a line
     * left out or set to 0 is removed, at least one must remain. The courier charge is mandatory and entered
     * manually; the courier must be one of the store's couriers.
     */
    public record QuoteRequest(
            @NotEmpty @Size(max = 100) List<@Valid @NotNull QuoteLine> lines,
            @NotBlank @Pattern(regexp = Validation.CODE_REGEX) String courierCode,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal courierCharge,
            @Size(max = 10) List<@Valid @NotNull ChargeLine> otherCharges,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal quoteDiscount) {
    }

    public record QuoteLine(
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String variantPublicId,
            @NotNull @Min(0) @Max(10_000) Integer quantity) {
    }

    public record ChargeLine(
            @NotBlank @Size(max = 80) String label,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** Merchant/assistant refusal of a reported transfer; the customer may resubmit until the payment deadline. */
    public record PaymentRejectRequest(@NotBlank @Size(max = Validation.REASON_MAX) String reason) {
    }

    /** The courier must be one of the store's; the tracking number must match the courier's format. */
    public record ShipRequest(
            @NotBlank @Pattern(regexp = Validation.CODE_REGEX) String courierCode,
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$") String trackingNumber) {
    }

    public record DeliveryFailedRequest(
            @NotNull DeliveryFailureType type,
            @Size(max = Validation.REASON_MAX) String note) {
    }

    /** The customer objects to a COD refusal within {@code cod.objection-window-days}. */
    public record ObjectionRequest(@NotBlank @Size(max = Validation.TEXT_MAX) String text) {
    }

    public enum Resolution {
        FORCE_COMPLETE,
        CANCEL
    }

    public record ResolveRequest(
            @NotNull Resolution action,
            @NotBlank @Size(max = Validation.REASON_MAX) String reason) {
    }
}
