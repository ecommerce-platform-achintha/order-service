package com.achintha.orderservice.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One order as its customer, its store or an admin sees it. {@code customer} (score and COD history) is filled for
 * the store and admins only; the shipping address and contact are shown to the customer, the store and admins.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        String publicId,
        String checkoutPublicId,
        String storePublicId,
        String storeName,
        String customerPublicId,
        OrderStatus status,
        PaymentMethod paymentMethod,
        List<Line> items,
        Amounts amounts,
        Instant deadlineAt,
        DeadlineType deadlineType,
        int quoteRevision,
        Address shippingAddress,
        Contact contact,
        Shipment shipment,
        List<Payment> payments,
        String reasonCode,
        String reason,
        DeliveryFailureType deliveryFailureType,
        Flags flags,
        Timeline timeline,
        CustomerInsight customer) {

    public record Line(String itemPublicId, String variantPublicId, String name, String variantName, String sku,
                       Map<String, String> attributes, int orderedQuantity, int quantity, BigDecimal listPrice,
                       BigDecimal discountAmount, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record Charge(String label, BigDecimal amount) {
    }

    public record Amounts(BigDecimal itemsTotal, String courierCode, BigDecimal courierCharge, List<Charge> otherCharges,
                          BigDecimal otherChargesTotal, BigDecimal quoteDiscount, BigDecimal grandTotal) {
    }

    public record Address(String publicId, String recipientName, String phone, String line1, String line2,
                          String city, String district, String postalCode, String country) {
    }

    public record Contact(String name, String phone, String email) {
    }

    public record Shipment(String courierCode, String courierName, String trackingNumber, String trackingUrl,
                           Instant shippedAt) {
    }

    /** A reported transfer; the depositing account is masked (last 4 digits only). */
    public record Payment(String publicId, String status, String referenceNumber, String sourceBankCode,
                          String sourceAccountMasked, String destinationBankCode, String storeBankAccountPublicId,
                          List<String> attachmentUrls, Instant submittedAt, Instant decidedAt,
                          String rejectionReason) {
    }

    public record Flags(boolean needsAdminResolution, boolean lateShipment, boolean lateVerification) {
    }

    public record Timeline(Instant placedAt, Instant quotedAt, Instant confirmedAt, Instant paymentSubmittedAt,
                           Instant paymentVerifiedAt, Instant readyToShipAt, Instant shippedAt, Instant completedAt,
                           Instant deliveryFailedAt, Instant closedAt) {
    }

    /** What a store may know about the customer before accepting (section 6.5). */
    public record CustomerInsight(int score, int codRefusals, boolean codSuspended) {
    }

    /** List entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String publicId, String storePublicId, String storeName, String customerPublicId,
                          OrderStatus status, PaymentMethod paymentMethod, int itemCount, BigDecimal grandTotal,
                          Instant deadlineAt, DeadlineType deadlineType, Flags flags, Instant placedAt,
                          Instant updatedAt) {
    }
}
