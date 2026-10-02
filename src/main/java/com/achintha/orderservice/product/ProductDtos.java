package com.achintha.orderservice.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of product-service's internal quote and reservation APIs (internal APIs may carry UUIDs, section 1). */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record QuoteRequest(List<UUID> variantIds, List<String> variantPublicIds) {
    }

    /** @param missing requested ids / publicIds that do not exist */
    public record QuoteResponse(List<QuoteItem> items, List<String> missing) {
    }

    /**
     * Live data for one variant, priced now.
     *
     * @param unitPrice         {@code listPrice - discountAmount}
     * @param availableQuantity units that can be reserved now (held units excluded)
     * @param purchasable       product active, variant active and store visible
     */
    public record QuoteItem(
            UUID variantId,
            String variantPublicId,
            UUID productId,
            String productPublicId,
            UUID storeId,
            String storePublicId,
            String storeName,
            String productName,
            String variantName,
            String sku,
            Map<String, String> attributes,
            String imageUrl,
            BigDecimal listPrice,
            BigDecimal discountAmount,
            BigDecimal unitPrice,
            String discountPublicId,
            int availableQuantity,
            boolean codAllowed,
            boolean productActive,
            boolean variantActive,
            boolean storeVisible,
            boolean purchasable) {
    }

    public record LineRequest(UUID variantId, String variantPublicId, Integer quantity) {

        public static LineRequest of(UUID variantId, int quantity) {
            return new LineRequest(variantId, null, quantity);
        }
    }

    /** @param orderRef the order's publicId: the idempotency key of the hold */
    public record ReserveRequest(String orderRef, Instant expiresAt, List<LineRequest> lines) {
    }

    /** The lines to keep (only reductions) and, optionally, a new expiry. */
    public record AdjustRequest(List<LineRequest> lines, Instant expiresAt) {
    }

    public record ReservationResponse(String orderRef, String status, Instant expiresAt, List<LineResponse> lines) {
    }

    public record LineResponse(UUID variantId, String variantPublicId, int quantity, int requestedQuantity,
                               String status, String releaseReason) {
    }
}
