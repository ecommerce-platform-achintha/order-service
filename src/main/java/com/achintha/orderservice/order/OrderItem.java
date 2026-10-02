package com.achintha.orderservice.order;

import com.achintha.orderservice.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of an order. Names and prices are snapshots taken from product-service at checkout ({@code listPrice},
 * {@code discountAmount}, {@code unitPrice}); later catalog changes never alter a placed order. The merchant's quote
 * may only lower {@code quantity} (0 removes the line); {@code orderedQuantity} keeps what the customer asked for.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private int position;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "item_public_id", nullable = false, length = 20)
    private String itemPublicId;

    @Column(name = "variant_public_id", nullable = false, length = 20)
    private String variantPublicId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "variant_name", length = 200)
    private String variantName;

    @Column(nullable = false, length = 64)
    private String sku;

    /** JSON object of option name to value (e.g. {"Size":"M"}). */
    @Column(columnDefinition = "text")
    private String attributes;

    @Column(name = "ordered_quantity", nullable = false)
    private int orderedQuantity;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "list_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "cod_allowed", nullable = false)
    private boolean codAllowed;

    public BigDecimal lineTotal() {
        return Money.times(unitPrice, quantity);
    }
}
