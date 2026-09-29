package com.achintha.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achintha.orderservice.exception.InvalidOrderStateException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

    private static OrderItem item(String unitPrice, int quantity) {
        return new OrderItem(UUID.randomUUID(), "Product", new BigDecimal(unitPrice), quantity);
    }

    private static Order orderIn(OrderStatus status) {
        Order order = new Order(UUID.randomUUID());
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> order.confirm();
            case FAILED -> order.fail();
            case CANCELLED -> order.cancel();
        }
        return order;
    }

    @Nested
    class TotalCalculation {

        @Test
        void newOrderStartsPendingWithZeroTotal() {
            Order order = new Order(UUID.randomUUID());

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getTotalAmount()).isEqualByComparingTo("0");
            assertThat(order.getItems()).isEmpty();
        }

        @Test
        void totalIsSumOfUnitPriceTimesQuantity() {
            Order order = new Order(UUID.randomUUID());
            order.addItem(item("999.99", 2));
            order.addItem(item("25.50", 3));
            order.addItem(item("0.01", 1));

            // 1999.98 + 76.50 + 0.01
            assertThat(order.getTotalAmount()).isEqualByComparingTo("2076.49");
        }

        @Test
        void totalIsRecomputedAsItemsAreAdded() {
            Order order = new Order(UUID.randomUUID());
            order.addItem(item("10.00", 1));
            assertThat(order.getTotalAmount()).isEqualByComparingTo("10.00");

            order.addItem(item("5.25", 4));
            assertThat(order.getTotalAmount()).isEqualByComparingTo("31.00");
        }

        @Test
        void decimalPricesDoNotLosePrecision() {
            // 0.1 * 3 is not exactly 0.3 in floating point; BigDecimal keeps it exact
            assertThat(Order.calculateTotal(List.of(item("0.10", 3)))).isEqualByComparingTo("0.30");
        }

        @Test
        void lineTotalIsUnitPriceTimesQuantity() {
            assertThat(item("19.99", 5).lineTotal()).isEqualByComparingTo("99.95");
        }

        @Test
        void addItemLinksItemToOrder() {
            Order order = new Order(UUID.randomUUID());
            OrderItem item = item("1.00", 1);
            order.addItem(item);

            assertThat(item.getOrder()).isSameAs(order);
        }
    }

    @Nested
    class StateTransitions {

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED"})
        void cancelIsAllowedFromPendingAndConfirmed(OrderStatus from) {
            Order order = orderIn(from);

            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "FAILED"})
        void cancelIsRejectedFromCancelledAndFailed(OrderStatus from) {
            Order order = orderIn(from);

            assertThatThrownBy(order::cancel)
                    .isInstanceOf(InvalidOrderStateException.class)
                    .hasMessageContaining(from + " to CANCELLED");
            assertThat(order.getStatus()).isEqualTo(from);
        }

        @Test
        void pendingCanBeConfirmedOrFailed() {
            Order confirmed = orderIn(OrderStatus.PENDING);
            confirmed.confirm();
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

            Order failed = orderIn(OrderStatus.PENDING);
            failed.fail();
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "CANCELLED", "FAILED"})
        void onlyPendingOrdersCanBeConfirmedOrFailed(OrderStatus from) {
            assertThatThrownBy(orderIn(from)::confirm).isInstanceOf(InvalidOrderStateException.class);
            assertThatThrownBy(orderIn(from)::fail).isInstanceOf(InvalidOrderStateException.class);
        }

        @Test
        void terminalStatusesAllowNoTransitions() {
            for (OrderStatus terminal : List.of(OrderStatus.CANCELLED, OrderStatus.FAILED)) {
                for (OrderStatus target : OrderStatus.values()) {
                    assertThat(terminal.canTransitionTo(target)).as("%s -> %s", terminal, target).isFalse();
                }
            }
        }
    }
}
