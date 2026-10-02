package com.achintha.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Totals and line handling of {@link Order} (no Spring). */
class OrderTest {

    private static OrderItem item(String unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setVariantId(UUID.randomUUID());
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setOrderedQuantity(quantity);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void grandTotalIsItemsPlusCourierPlusChargesMinusDiscount() {
        Order order = new Order();
        order.addItem(item("1500.00", 2));
        order.addItem(item("99.99", 3));
        order.setCourierCharge(new BigDecimal("350"));
        order.getOtherCharges().add(new OrderCharge("Gift wrap", new BigDecimal("100.00")));
        order.setQuoteDiscount(new BigDecimal("50.00"));

        order.recalculateTotals();

        assertThat(order.getItemsTotal()).isEqualByComparingTo("3299.97");
        assertThat(order.getOtherChargesTotal()).isEqualByComparingTo("100.00");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("3699.97");
        assertThat(order.getGrandTotal().scale()).isEqualTo(2);
    }

    @Test
    void removedLinesDoNotCount() {
        Order order = new Order();
        OrderItem kept = item("10.00", 2);
        OrderItem removed = item("500.00", 1);
        order.addItem(kept);
        order.addItem(removed);
        removed.setQuantity(0);

        order.recalculateTotals();

        assertThat(order.activeItems()).containsExactly(kept);
        assertThat(order.getItemsTotal()).isEqualByComparingTo("20.00");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("20.00");
        assertThat(kept.getPosition()).isZero();
        assertThat(removed.getPosition()).isEqualTo(1);
    }

    @Test
    void statusGroupsMatchTheContract() {
        assertThat(OrderStatus.UNCONFIRMED).containsExactlyInAnyOrder(OrderStatus.AWAITING_MERCHANT,
                OrderStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(OrderStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(OrderStatus.SHIPPED.isOpen()).isTrue();
        assertThat(OrderStatus.DELIVERY_FAILED.isCancellation()).isFalse();
        assertThat(OrderStatus.EXPIRED_MERCHANT.isCancellation()).isTrue();
    }

    @Test
    void confirmationTargetDependsOnThePaymentMethod() {
        Order cod = new Order();
        cod.setPaymentMethod(PaymentMethod.COD);
        Order bank = new Order();
        bank.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        assertThat(OrderAction.CUSTOMER_CONFIRM.target(cod)).isEqualTo(OrderStatus.READY_TO_SHIP);
        assertThat(OrderAction.CUSTOMER_CONFIRM.target(bank)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }
}
