package com.achintha.orderservice.order;

import com.achintha.orderservice.common.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One order: the lines of one store from one checkout (section 6). Status changes go only through
 * {@link OrderStateMachine}; {@code @Version} makes a timer and a user action unable to both win.
 *
 * <p>Address and contact are snapshots taken at checkout. Amounts are LKR with 2 decimals:
 * {@code grandTotal = itemsTotal + courierCharge + otherChargesTotal - quoteDiscount}.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 20)
    private String publicId;

    @Column(name = "checkout_group_id", nullable = false)
    private UUID checkoutGroupId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_public_id", nullable = false, length = 20)
    private String customerPublicId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "store_public_id", nullable = false, length = 20)
    private String storePublicId;

    @Column(name = "store_name", nullable = false, length = 120)
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    // ------------------------------------------------------------------------------ shipping address snapshot

    @Column(name = "ship_address_public_id", nullable = false, length = 20)
    private String shipAddressPublicId;

    @Column(name = "ship_recipient_name", nullable = false, length = 120)
    private String shipRecipientName;

    @Column(name = "ship_phone", nullable = false, length = 20)
    private String shipPhone;

    @Column(name = "ship_line1", nullable = false, length = 200)
    private String shipLine1;

    @Column(name = "ship_line2", length = 200)
    private String shipLine2;

    @Column(name = "ship_city", nullable = false, length = 100)
    private String shipCity;

    @Column(name = "ship_district", length = 100)
    private String shipDistrict;

    @Column(name = "ship_postal_code", length = 20)
    private String shipPostalCode;

    @Column(name = "ship_country", nullable = false, length = 60)
    private String shipCountry;

    // ------------------------------------------------------------------------------------- contact snapshot

    @Column(name = "contact_name", nullable = false, length = 120)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    // ----------------------------------------------------------------------------------------------- amounts

    @Column(name = "items_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemsTotal;

    @Column(name = "courier_code", length = 32)
    private String courierCode;

    @Column(name = "courier_charge", precision = 12, scale = 2)
    private BigDecimal courierCharge;

    @Column(name = "other_charges_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherChargesTotal = Money.ZERO;

    @Column(name = "quote_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal quoteDiscount = Money.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    // ------------------------------------------------------------------------------------------------ timers

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_type", length = 40)
    private DeadlineType deadlineType;

    /** The original payment deadline: a rejected payment may be resubmitted until then. */
    @Column(name = "payment_deadline_at")
    private Instant paymentDeadlineAt;

    /** 0 before the first quote, 1 after it, +1 per re-quote. */
    @Column(name = "quote_revision", nullable = false)
    private int quoteRevision;

    // ------------------------------------------------------------------------------------------------- flags

    @Column(name = "needs_admin_resolution", nullable = false)
    private boolean needsAdminResolution;

    @Column(name = "late_shipment", nullable = false)
    private boolean lateShipment;

    @Column(name = "late_verification", nullable = false)
    private boolean lateVerification;

    /** The stock hold was turned into a real decrement ({@code READY_TO_SHIP}); nothing to release after that. */
    @Column(name = "stock_committed", nullable = false)
    private boolean stockCommitted;

    // ----------------------------------------------------------------------------------------------- outcome

    /** Rejection reason, delivery failure type or admin resolution, as a code. */
    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_failure_type", length = 20)
    private DeliveryFailureType deliveryFailureType;

    // -------------------------------------------------------------------------------------- stage timestamps

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "quoted_at")
    private Instant quotedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "payment_submitted_at")
    private Instant paymentSubmittedAt;

    @Column(name = "payment_verified_at")
    private Instant paymentVerifiedAt;

    @Column(name = "ready_to_ship_at")
    private Instant readyToShipAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "delivery_failed_at")
    private Instant deliveryFailedAt;

    /** When a terminal state other than completion was reached. */
    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private List<OrderItem> items = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_charges", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    private List<OrderCharge> otherCharges = new ArrayList<>();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        item.setPosition(items.size());
        items.add(item);
    }

    /** Lines still part of the order (quantity above 0). */
    public List<OrderItem> activeItems() {
        return items.stream().filter(i -> i.getQuantity() > 0).toList();
    }

    /** Recomputes {@code itemsTotal}, {@code otherChargesTotal} and {@code grandTotal} from the lines and charges. */
    public void recalculateTotals() {
        itemsTotal = activeItems().stream().map(OrderItem::lineTotal).reduce(Money.ZERO, BigDecimal::add);
        otherChargesTotal = otherCharges.stream().map(OrderCharge::getAmount).reduce(Money.ZERO, BigDecimal::add);
        grandTotal = Money.of(itemsTotal.add(Money.orZero(courierCharge)).add(otherChargesTotal)
                .subtract(Money.orZero(quoteDiscount)));
    }

    public void clearDeadline() {
        deadlineAt = null;
        deadlineType = null;
    }

    public void setDeadline(DeadlineType type, Instant at) {
        deadlineType = type;
        deadlineAt = at;
    }
}
