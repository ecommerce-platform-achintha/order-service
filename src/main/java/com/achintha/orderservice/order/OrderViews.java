package com.achintha.orderservice.order;

import com.achintha.orderservice.checkout.CheckoutGroup;
import com.achintha.orderservice.checkout.CheckoutGroupRepository;
import com.achintha.orderservice.crypto.AccountNumberCipher;
import com.achintha.orderservice.customer.CodPrivilegeService;
import com.achintha.orderservice.customer.CodPrivilegeService.CodView;
import com.achintha.orderservice.customer.CustomerScoreService;
import com.achintha.orderservice.payment.PaymentSubmissionRepository;
import com.achintha.orderservice.ports.ImageStoragePort;
import com.achintha.orderservice.shipment.ShipmentRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds {@link OrderResponse}s (DTOs only: entities are never serialized). */
@Component
@RequiredArgsConstructor
public class OrderViews {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {
    };

    private final CheckoutGroupRepository checkoutGroups;
    private final ShipmentRepository shipments;
    private final PaymentSubmissionRepository payments;
    private final CustomerScoreService scoreService;
    private final CodPrivilegeService codService;
    private final ImageStoragePort imageStorage;

    public enum Audience {
        CUSTOMER,
        STORE,
        ADMIN
    }

    public OrderResponse detail(Order order, Audience audience) {
        String checkoutPublicId = checkoutGroups.findById(order.getCheckoutGroupId())
                .map(CheckoutGroup::getPublicId).orElse(null);
        OrderResponse.Shipment shipment = shipments.findByOrderId(order.getId())
                .map(s -> new OrderResponse.Shipment(s.getCourierCode(), s.getCourierName(), s.getTrackingNumber(),
                        s.getTrackingUrl(), s.getShippedAt()))
                .orElse(null);
        var paymentViews = payments.findAllByOrderIdOrderBySubmittedAtAsc(order.getId()).stream()
                .map(p -> new OrderResponse.Payment(p.getPublicId(), p.getStatus().name(), p.getReferenceNumber(),
                        p.getSourceBankCode(), AccountNumberCipher.mask(p.getSourceAccountLast4()),
                        p.getDestinationBankCode(), p.getStoreBankAccountPublicId(),
                        p.getAttachmentKeys().stream().map(imageStorage::urlFor).toList(), p.getSubmittedAt(),
                        p.getDecidedAt(), p.getRejectionReason()))
                .toList();
        OrderResponse.CustomerInsight insight = null;
        if (audience != Audience.CUSTOMER) {
            CodView cod = codService.view(order.getCustomerId());
            insight = new OrderResponse.CustomerInsight(scoreService.scoreOf(order.getCustomerId()),
                    cod.totalRefusals(), cod.suspended());
        }
        return new OrderResponse(order.getPublicId(), checkoutPublicId, order.getStorePublicId(),
                order.getStoreName(), audience == Audience.CUSTOMER ? null : order.getCustomerPublicId(),
                order.getStatus(), order.getPaymentMethod(),
                order.getItems().stream().map(OrderViews::line).toList(), amounts(order), order.getDeadlineAt(),
                order.getDeadlineType(), order.getQuoteRevision(), address(order),
                new OrderResponse.Contact(order.getContactName(), order.getContactPhone(), order.getContactEmail()),
                shipment, paymentViews.isEmpty() ? null : paymentViews, order.getReasonCode(), order.getReason(),
                order.getDeliveryFailureType(), flags(order), timeline(order), insight);
    }

    public OrderResponse.Summary summary(Order order, Audience audience) {
        return new OrderResponse.Summary(order.getPublicId(), order.getStorePublicId(), order.getStoreName(),
                audience == Audience.CUSTOMER ? null : order.getCustomerPublicId(), order.getStatus(),
                order.getPaymentMethod(), order.activeItems().size(), order.getGrandTotal(), order.getDeadlineAt(),
                order.getDeadlineType(), flags(order), order.getPlacedAt(), order.getUpdatedAt());
    }

    private static OrderResponse.Line line(OrderItem item) {
        Map<String, String> attributes = item.getAttributes() == null ? Map.of()
                : JSON.readValue(item.getAttributes(), ATTRIBUTES);
        return new OrderResponse.Line(item.getItemPublicId(), item.getVariantPublicId(), item.getName(),
                item.getVariantName(), item.getSku(), attributes, item.getOrderedQuantity(), item.getQuantity(),
                item.getListPrice(), item.getDiscountAmount(), item.getUnitPrice(), item.lineTotal());
    }

    private static OrderResponse.Amounts amounts(Order order) {
        return new OrderResponse.Amounts(order.getItemsTotal(), order.getCourierCode(), order.getCourierCharge(),
                order.getOtherCharges().stream().map(c -> new OrderResponse.Charge(c.getLabel(), c.getAmount()))
                        .toList(),
                order.getOtherChargesTotal(), order.getQuoteDiscount(), order.getGrandTotal());
    }

    private static OrderResponse.Address address(Order order) {
        return new OrderResponse.Address(order.getShipAddressPublicId(), order.getShipRecipientName(),
                order.getShipPhone(), order.getShipLine1(), order.getShipLine2(), order.getShipCity(),
                order.getShipDistrict(), order.getShipPostalCode(), order.getShipCountry());
    }

    private static OrderResponse.Flags flags(Order order) {
        return new OrderResponse.Flags(order.isNeedsAdminResolution(), order.isLateShipment(),
                order.isLateVerification());
    }

    private static OrderResponse.Timeline timeline(Order order) {
        return new OrderResponse.Timeline(order.getPlacedAt(), order.getQuotedAt(), order.getConfirmedAt(),
                order.getPaymentSubmittedAt(), order.getPaymentVerifiedAt(), order.getReadyToShipAt(),
                order.getShippedAt(), order.getCompletedAt(), order.getDeliveryFailedAt(), order.getClosedAt());
    }
}
