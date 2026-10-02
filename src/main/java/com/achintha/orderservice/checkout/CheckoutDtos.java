package com.achintha.orderservice.checkout;

import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.order.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    /**
     * The cart lines to buy, the delivery address (one of the customer's own) and a payment method per store. No
     * prices, customer or status: the server takes them from product-service, the JWT and the state machine.
     */
    public record CheckoutRequest(
            @NotEmpty @Size(max = 100)
            List<@NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
                    String> cartItemIds,
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String addressPublicId,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull StorePayment> payments) {
    }

    public record StorePayment(
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String storePublicId,
            @NotNull PaymentMethod method) {
    }

    public enum GroupStatus {
        PLACED,
        FAILED
    }

    /** Outcome for one store: an order, or why that store's lines could not be ordered (they stay in the cart). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupResult(String storePublicId, String storeName, GroupStatus status, String orderPublicId,
                              PaymentMethod paymentMethod, BigDecimal grandTotal, String code, String message) {
    }

    /** {@code checkoutPublicId} is null when no order could be placed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckoutResponse(String checkoutPublicId, List<GroupResult> results) {

        public boolean anyPlaced() {
            return results.stream().anyMatch(r -> r.status() == GroupStatus.PLACED);
        }
    }
}
