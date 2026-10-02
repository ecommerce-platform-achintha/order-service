package com.achintha.orderservice.cart;

import com.achintha.orderservice.common.Validation;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class CartDtos {

    private CartDtos() {
    }

    public record AddItemRequest(
            @NotBlank @Pattern(regexp = Validation.PUBLIC_ID_REGEX, message = Validation.PUBLIC_ID_MESSAGE)
            String variantPublicId,
            @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    public record UpdateItemRequest(@NotNull @Min(1) @Max(100) Integer quantity) {
    }

    /** Why a line cannot be bought right now. */
    public enum LineIssue {
        /** The product, variant or store is not available (inactive, hidden, banned). */
        NOT_AVAILABLE,
        /** Fewer units are available than in the cart. */
        INSUFFICIENT_STOCK,
        /** The variant no longer exists. */
        REMOVED
    }

    /** The cart, re-priced live from product-service on every read, grouped by store. */
    public record CartResponse(List<StoreGroup> stores, int lineCount, BigDecimal itemsTotal) {
    }

    /** {@code subtotal} counts only lines that can be bought now. */
    public record StoreGroup(String storePublicId, String storeName, List<CartLine> lines, BigDecimal subtotal) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CartLine(
            String cartItemPublicId,
            String variantPublicId,
            String itemPublicId,
            String productName,
            String variantName,
            String sku,
            Map<String, String> attributes,
            String imageUrl,
            int quantity,
            BigDecimal listPrice,
            BigDecimal discountAmount,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            Integer availableQuantity,
            boolean available,
            boolean codAllowed,
            LineIssue issue) {
    }
}
