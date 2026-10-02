package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.payment.PaymentStatus;
import com.achintha.orderservice.payment.PaymentSubmission;
import com.achintha.orderservice.payment.PaymentSubmissionRepository;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Full happy paths for COD and bank transfer (section 6.2), with the stock hold assertions: reserved at placement,
 * adjusted by the quote and when the deadline moves, committed on READY_TO_SHIP, never released after completion.
 */
class OrderFlowIntegrationTest extends IntegrationTest {

    @Autowired
    private PaymentSubmissionRepository payments;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void codHappyPath() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant lamp = variant(shop, "1500.00", "100.00", 50, true, true);

        // Cart: priced live
        String item = addToCart(customer, lamp, 2);
        String cart = expect(getAs(customer, "/api/customer/cart"), 200).getContentAsString();
        assertThat(read(cart, "$.itemsTotal")).isEqualTo("2800.0");
        assertThat(read(cart, "$.stores[0].storePublicId")).isEqualTo(shop.publicId());

        // Checkout: one order, stock held until the merchant deadline, line leaves the cart
        MockHttpServletResponse checkout = checkout(customer, List.of(item), java.util.Map.of(shop, "COD"),
                "key-" + next());
        String body = expect(checkout, 201).getContentAsString();
        String order = read(body, "$.results[0].orderPublicId");
        assertThat(read(body, "$.checkoutPublicId")).startsWith("CHK-");
        assertThat(order).startsWith("ORD-");
        assertThat(Remote.reserveCalls(order)).isEqualTo(1);
        assertThat(read(expect(getAs(customer, "/api/customer/cart"), 200).getContentAsString(), "$.lineCount"))
                .isEqualTo("0");
        Order placed = order(order);
        assertThat(placed.getStatus()).isEqualTo(OrderStatus.AWAITING_MERCHANT);
        assertThat(placed.getItemsTotal()).isEqualByComparingTo("2800.00");
        transactionTemplate.executeWithoutResult(tx -> {
            var line = order(order).getItems().getFirst();
            assertThat(line.getListPrice()).isEqualByComparingTo("1500.00");
            assertThat(line.getDiscountAmount()).isEqualByComparingTo("100.00");
            assertThat(line.getUnitPrice()).isEqualByComparingTo("1400.00");
        });
        assertThat(placed.getShipCity()).isEqualTo("Colombo");
        assertThat(placed.getDeadlineAt()).isEqualTo(placed.getPlacedAt().plusSeconds(24 * 3600));

        // Merchant sees the customer's score and COD history
        String detail = expect(getAs(shop.merchant(), "/api/merchant/orders/" + order), 200).getContentAsString();
        assertThat(read(detail, "$.customer.score")).isEqualTo("0");
        assertThat(read(detail, "$.customer.codRefusals")).isEqualTo("0");

        // Quote: reduce to 1 unit, courier + other charge - discount
        String quoted = expect(quote(shop, order, lamp, 1), 200).getContentAsString();
        assertThat(read(quoted, "$.status")).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(read(quoted, "$.amounts.grandTotal")).isEqualTo("1800.0"); // 1400 + 350 + 100 - 50
        assertThat(Remote.adjustCalls(order)).isEqualTo(1);
        assertThat(Remote.lastAdjustBody(order)).contains("\"quantity\":1");

        // Customer confirms: COD goes straight to READY_TO_SHIP and the stock is committed
        String confirmed = expect(confirm(customer, order), 200).getContentAsString();
        assertThat(read(confirmed, "$.status")).isEqualTo("READY_TO_SHIP");
        assertThat(Remote.commitCalls(order)).isEqualTo(1);
        assertThat(order(order).getDeadlineType().name()).isEqualTo("SHIP_BY");

        // Ship with a validated tracking number and a templated link
        String shipped = expect(ship(shop, order), 200).getContentAsString();
        assertThat(read(shipped, "$.status")).isEqualTo("SHIPPED");
        assertThat(read(shipped, "$.shipment.trackingUrl")).isEqualTo("https://track.example/domex/DX12345678");

        // Customer marks received: completed, +1, no release
        String completed = expect(perform(post("/api/customer/orders/" + order + "/received"), customer, null), 200)
                .getContentAsString();
        assertThat(read(completed, "$.status")).isEqualTo("COMPLETED");
        assertThat(Remote.releaseCalls(order)).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(delta),0) from customer_score_events where customer_id=?",
                Integer.class, customer.id())).isEqualTo(1);
        assertThat(eventTypes(order)).containsExactly("OrderPlaced", "OrderQuoted", "OrderConfirmed",
                "OrderShipped", "OrderCompleted");
    }

    @Test
    void bankTransferHappyPathWithARejectedFirstPayment() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant kettle = variant(shop, "4200.00", 10);
        String order = placeOrder(customer, kettle, 1, "BANK_TRANSFER");
        expect(quote(shop, order, kettle, 1), 200);

        String confirmed = expect(confirm(customer, order), 200).getContentAsString();
        assertThat(read(confirmed, "$.status")).isEqualTo("AWAITING_PAYMENT");
        assertThat(Remote.commitCalls(order)).isZero();
        assertThat(Remote.adjustCalls(order)).isEqualTo(2); // quote + hold extended to the payment deadline

        // The store's accounts are shown to the paying customer
        String accounts = expect(getAs(customer, "/api/customer/orders/" + order + "/bank-accounts"), 200)
                .getContentAsString();
        assertThat(read(accounts, "$[0].accountNumber")).isEqualTo("0012345678");

        String reference = "TRX " + next();
        String submitted = expect(submitPayment(customer, order, shop, reference), 200).getContentAsString();
        assertThat(read(submitted, "$.status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(read(submitted, "$.payments[0].sourceAccountMasked")).isEqualTo("****4567");
        PaymentSubmission stored = payments.findAllByOrderIdOrderBySubmittedAtAsc(order(order).getId()).getFirst();
        assertThat(stored.getSourceAccountEncrypted()).startsWith("v1:").doesNotContain("8001234567");
        assertThat(stored.getNormalizedReference()).isEqualTo(reference.replace(" ", ""));

        // Merchant rejects: back to AWAITING_PAYMENT until the original deadline
        Order beforeReject = order(order);
        expect(perform(post("/api/merchant/orders/" + order + "/payment/reject"), shop.merchant(),
                "{\"reason\":\"Amount not received\"}"), 200);
        Order rejected = order(order);
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(rejected.getDeadlineAt()).isEqualTo(beforeReject.getPaymentDeadlineAt());

        // The same reference can be resubmitted after a rejection (only successful submissions block it)
        expect(submitPayment(customer, order, shop, reference), 200);
        String verified = expect(perform(post("/api/merchant/orders/" + order + "/payment/verify"), shop.merchant(),
                null), 200).getContentAsString();
        assertThat(read(verified, "$.status")).isEqualTo("READY_TO_SHIP");
        assertThat(Remote.commitCalls(order)).isEqualTo(1);
        assertThat(payments.findAllByOrderIdOrderBySubmittedAtAsc(order(order).getId()))
                .extracting(PaymentSubmission::getStatus)
                .containsExactly(PaymentStatus.REJECTED, PaymentStatus.VERIFIED);

        expect(ship(shop, order), 200);
        expectStatus(order, OrderStatus.SHIPPED);
        assertThat(eventTypes(order)).containsExactly("OrderPlaced", "OrderQuoted", "OrderConfirmed",
                "OrderPaymentSubmitted", "OrderPaymentRejected", "OrderPaymentSubmitted", "OrderPaymentVerified",
                "OrderShipped");
    }

    @Test
    void eventsCarryWhatTheOtherServicesNeed() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "999.50", 5);
        String order = placeOrder(customer, v, 2, "COD");
        String placed = lastEvent(order, "OrderPlaced");
        assertThat(read(placed, "$.eventId")).isNotBlank();
        assertThat(read(placed, "$.occurredAt")).isNotBlank();
        assertThat(read(placed, "$.orderId")).isEqualTo(order(order).getId().toString());
        assertThat(read(placed, "$.orderPublicId")).isEqualTo(order);
        assertThat(read(placed, "$.storeId")).isEqualTo(shop.storeId().toString());
        assertThat(read(placed, "$.storePublicId")).isEqualTo(shop.publicId());
        assertThat(read(placed, "$.customerId")).isEqualTo(customer.id().toString());
        assertThat(read(placed, "$.checkoutPublicId")).startsWith("CHK-");
        assertThat(read(placed, "$.grandTotal")).isEqualTo("1999.0");
        assertThat(read(placed, "$.lines[0].variantId")).isEqualTo(v.id().toString());
        assertThat(read(placed, "$.lines[0].quantity")).isEqualTo("2");
        assertThat(placed).doesNotContain("Galle Road").doesNotContain("+9477");
    }

    private void expectStatus(String order, OrderStatus status) {
        assertThat(statusOf(order)).isEqualTo(status);
    }
}
