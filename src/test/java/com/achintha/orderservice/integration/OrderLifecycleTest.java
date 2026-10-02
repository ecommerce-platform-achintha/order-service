package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.order.DeadlineType;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Every terminal path of the state machine (section 6.2) with a fake clock, the stock effects (release on every
 * terminal state without completion, never after a commit), the customer score deltas, the merchant penalty hints,
 * and the quote rules.
 */
class OrderLifecycleTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private int scoreOf(TestUser customer) {
        return jdbc.queryForObject("select coalesce(sum(delta),0) from customer_score_events where customer_id=?",
                Integer.class, customer.id());
    }

    // ---------------------------------------------------------------------------------------- terminal paths

    @Test
    void merchantTimeoutExpiresTheOrderWithAMerchantPenaltyHint() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "COD");

        clock.advance(Duration.ofHours(23));
        runSchedulerUntilIdle();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.AWAITING_MERCHANT);

        expireDeadline(order);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.EXPIRED_MERCHANT);
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        String event = lastEvent(order, "OrderCancelled");
        assertThat(read(event, "$.terminalStatus")).isEqualTo("EXPIRED_MERCHANT");
        assertThat(read(event, "$.merchantPenalty")).isEqualTo("RESPONSE_TIMEOUT");
        assertThat(scoreOf(customer)).isZero();

        // The merchant can no longer act
        MockHttpServletResponse late = quote(shop, order, v, 1);
        assertThat(late.getStatus()).isEqualTo(409);
    }

    @Test
    void customerDeclineCostsOne() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "COD");
        expect(quote(shop, order, v, 1), 200);

        expect(perform(post("/api/customer/orders/" + order + "/decline"), customer, null), 200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DECLINED_BY_CUSTOMER);
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        assertThat(scoreOf(customer)).isEqualTo(-1);
        assertThat(read(lastEvent(order, "OrderCancelled"), "$.customerPenalty")).isEqualTo("DECLINED");
    }

    @Test
    void customerTimeoutCostsOne() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "BANK_TRANSFER");
        expect(quote(shop, order, v, 1), 200);

        expireDeadline(order);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.EXPIRED_CUSTOMER);
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        assertThat(scoreOf(customer)).isEqualTo(-1);
    }

    @Test
    void paymentTimeoutCostsOne() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "BANK_TRANSFER");
        expect(quote(shop, order, v, 1), 200);
        expect(confirm(customer, order), 200);
        assertThat(order(order).getDeadlineType()).isEqualTo(DeadlineType.PAYMENT_SUBMISSION);

        expireDeadline(order);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.EXPIRED_PAYMENT);
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        assertThat(scoreOf(customer)).isEqualTo(-1);

        // Running the scheduler again changes nothing (idempotent)
        runSchedulerUntilIdle();
        assertThat(scoreOf(customer)).isEqualTo(-1);
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
    }

    @Test
    void merchantRejectionCarriesNoPenaltyForEitherSide() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "COD");

        MockHttpServletResponse bad = perform(post("/api/merchant/orders/" + order + "/reject"), shop.merchant(),
                "{\"reasonCode\":\"NOT_A_REASON\"}");
        assertThat(bad.getStatus()).isEqualTo(400);
        expect(perform(post("/api/merchant/orders/" + order + "/reject"), shop.merchant(),
                "{\"reasonCode\":\"LOW_CUSTOMER_SCORE\",\"note\":\"<b>sorry</b>\"}"), 200);

        Order rejected = order(order);
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED_BY_MERCHANT);
        assertThat(rejected.getReasonCode()).isEqualTo("LOW_CUSTOMER_SCORE");
        assertThat(rejected.getReason()).isEqualTo("sorry");
        assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        assertThat(scoreOf(customer)).isZero();
        String event = lastEvent(order, "OrderCancelled");
        assertThat(read(event, "$.terminalStatus")).isEqualTo("REJECTED_BY_MERCHANT");
        assertThat(event).doesNotContain("merchantPenalty").doesNotContain("customerPenalty");
    }

    @Test
    void customerCancelsFreeOnlyBeforeTheQuote() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String first = placeOrder(customer, v, 1, "COD");
        expect(perform(post("/api/customer/orders/" + first + "/cancel"), customer, null), 200);
        assertThat(statusOf(first)).isEqualTo(OrderStatus.CANCELLED_BY_CUSTOMER);
        assertThat(Remote.releaseCalls(first)).isEqualTo(1);
        assertThat(scoreOf(customer)).isZero();

        String second = placeOrder(customer, v, 1, "COD");
        expect(quote(shop, second, v, 1), 200);
        MockHttpServletResponse tooLate = perform(post("/api/customer/orders/" + second + "/cancel"), customer, null);
        assertThat(tooLate.getStatus()).isEqualTo(409);
        assertThat(read(tooLate.getContentAsString(), "$.code")).isEqualTo("INVALID_ORDER_STATE");
    }

    @Test
    void deliveryFailedAfterShippingCountsACodRefusalWithoutScoreChange() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = readyCodOrder(customer, v);
        expect(ship(shop, order), 200);

        expect(perform(post("/api/merchant/orders/" + order + "/delivery-failed"), shop.merchant(),
                "{\"type\":\"COD_REFUSED\",\"note\":\"Refused at the door\"}"), 200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(Remote.releaseCalls(order)).isZero(); // stock was committed; a merchant stock edit returns it
        assertThat(scoreOf(customer)).isZero();
        assertThat(jdbc.queryForObject("select total_refusals from customer_cod_state where customer_id=?",
                Integer.class, customer.id())).isEqualTo(1);
        assertThat(eventTypes(order)).last().isEqualTo("OrderDeliveryFailed");
    }

    @Test
    void autoCompleteSevenDaysAfterShipping() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = readyCodOrder(customer, v);
        expect(ship(shop, order), 200);
        Order shipped = order(order);
        assertThat(shipped.getDeadlineType()).isEqualTo(DeadlineType.AUTO_COMPLETE);
        assertThat(shipped.getDeadlineAt()).isEqualTo(shipped.getShippedAt().plus(Duration.ofDays(7)));

        clock.advance(Duration.ofDays(6));
        runSchedulerUntilIdle();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);

        expireDeadline(order);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(scoreOf(customer)).isEqualTo(1);
        assertThat(Remote.releaseCalls(order)).isZero();
        assertThat(eventTypes(order)).last().isEqualTo("OrderCompleted");
    }

    // ------------------------------------------------------------------------------------ overdue (stay open)

    @Test
    void missedShipByFlagsTheOrderAndKeepsItOpen() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = readyCodOrder(customer, v);
        assertThat(order(order).getDeadlineAt()).isEqualTo(order(order).getReadyToShipAt().plus(Duration.ofHours(72)));

        expireDeadline(order);
        Order late = order(order);
        assertThat(late.getStatus()).isEqualTo(OrderStatus.READY_TO_SHIP);
        assertThat(late.isLateShipment()).isTrue();
        assertThat(late.getDeadlineAt()).isNull();
        assertThat(read(lastEvent(order, "OrderShipmentOverdue"), "$.merchantPenalty")).isEqualTo("LATE_SHIPMENT");

        // Still shippable
        expect(ship(shop, order), 200);
    }

    @Test
    void missedVerificationFlagsTheOrderAndExtendsTheHold() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "BANK_TRANSFER");
        expect(quote(shop, order, v, 1), 200);
        expect(confirm(customer, order), 200);
        expect(submitPayment(customer, order, shop, "VER" + next()), 200);
        int adjustsBefore = Remote.adjustCalls(order);

        expireDeadline(order);
        Order late = order(order);
        assertThat(late.getStatus()).isEqualTo(OrderStatus.PAYMENT_SUBMITTED);
        assertThat(late.isLateVerification()).isTrue();
        assertThat(Remote.adjustCalls(order)).isEqualTo(adjustsBefore + 1);
        assertThat(read(lastEvent(order, "OrderVerificationOverdue"), "$.merchantPenalty"))
                .isEqualTo("LATE_VERIFICATION");

        expect(perform(post("/api/merchant/orders/" + order + "/payment/verify"), shop.merchant(), null), 200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.READY_TO_SHIP);
    }

    // ---------------------------------------------------------------------------------------------- quotes

    @Test
    void reQuotesAreLimitedAndEachResetsTheTimer() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 3, "COD");
        expect(quote(shop, order, v, 3), 200);

        clock.advance(Duration.ofHours(10));
        expect(quote(shop, order, v, 2), 200); // re-quote 1
        Order requoted = order(order);
        assertThat(requoted.getQuoteRevision()).isEqualTo(2);
        assertThat(requoted.getDeadlineAt()).isEqualTo(requoted.getQuotedAt().plus(Duration.ofHours(24)));
        expect(quote(shop, order, v, 2), 200); // re-quote 2 (limit orders.max-quote-revisions = 2)

        MockHttpServletResponse third = quote(shop, order, v, 1);
        assertThat(third.getStatus()).isEqualTo(409);
        assertThat(read(third.getContentAsString(), "$.code")).isEqualTo("QUOTE_REVISION_LIMIT_REACHED");
        assertThat(read(lastEvent(order, "OrderQuoted"), "$.quoteRevision")).isEqualTo("3");
    }

    @Test
    void aQuoteMayOnlyReduceAndNeedsAStoreCourier() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        Variant other = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 2, "COD");

        assertThat(code(quote(shop, order, v, 3))).isEqualTo("INVALID_QUOTE");
        assertThat(code(quote(shop, order, other, 1))).isEqualTo("INVALID_QUOTE");
        assertThat(code(quote(shop, order, v, 0))).isEqualTo("INVALID_QUOTE");
        assertThat(code(perform(post("/api/merchant/orders/" + order + "/quote"), shop.merchant(), """
                {"lines":[{"variantPublicId":"%s","quantity":1}],"courierCode":"NOPE","courierCharge":100}"""
                .formatted(v.publicId())))).isEqualTo("COURIER_NOT_AVAILABLE");
        // A COD order needs a courier that collects cash
        assertThat(code(perform(post("/api/merchant/orders/" + order + "/quote"), shop.merchant(), """
                {"lines":[{"variantPublicId":"%s","quantity":1}],"courierCode":"%s","courierCharge":100}"""
                .formatted(v.publicId(), Remote.NON_COD_COURIER)))).isEqualTo("COURIER_NOT_AVAILABLE");
        // Courier charge is mandatory
        assertThat(perform(post("/api/merchant/orders/" + order + "/quote"), shop.merchant(), """
                {"lines":[{"variantPublicId":"%s","quantity":1}],"courierCode":"%s"}"""
                .formatted(v.publicId(), Remote.COURIER)).getStatus()).isEqualTo(400);
        // Discount larger than the total
        assertThat(code(perform(post("/api/merchant/orders/" + order + "/quote"), shop.merchant(), """
                {"lines":[{"variantPublicId":"%s","quantity":1}],"courierCode":"%s","courierCharge":10,
                 "quoteDiscount":500}""".formatted(v.publicId(), Remote.COURIER)))).isEqualTo("INVALID_QUOTE");
        assertThat(statusOf(order)).isEqualTo(OrderStatus.AWAITING_MERCHANT);
    }

    @Test
    void anInvalidTrackingNumberIsRefused() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = readyCodOrder(customer, v);
        MockHttpServletResponse bad = perform(post("/api/merchant/orders/" + order + "/ship"), shop.merchant(),
                "{\"courierCode\":\"%s\",\"trackingNumber\":\"XX1\"}".formatted(Remote.COURIER));
        assertThat(code(bad)).isEqualTo("INVALID_TRACKING_NUMBER");
        assertThat(statusOf(order)).isEqualTo(OrderStatus.READY_TO_SHIP);
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return read(response.getContentAsString(), "$.code");
    }
}
