package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.payment.FlagStatus;
import com.achintha.orderservice.payment.FlaggedPaymentReference;
import com.achintha.orderservice.payment.FlaggedPaymentReferenceRepository;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.TestUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** Duplicate bank references (D9, section 6.3): rejected generically, flagged, reviewed by admins. */
class PaymentFlaggingTest extends IntegrationTest {

    @Autowired
    private FlaggedPaymentReferenceRepository flags;

    private String awaitingPayment(TestUser customer, Shop shop) throws Exception {
        Variant v = variant(shop, "500.00", 10);
        String order = placeOrder(customer, v, 1, "BANK_TRANSFER");
        expect(quote(shop, order, v, 1), 200);
        expect(confirm(customer, order), 200);
        return order;
    }

    @Test
    void aReferenceUsedAnywhereBeforeIsRejectedAndFlagged() throws Exception {
        String reference = "REF" + next();
        TestUser honest = customer();
        Shop firstStore = shop();
        String original = awaitingPayment(honest, firstStore);
        expect(submitPayment(honest, original, firstStore, reference), 200);

        // Another customer, another store, same bank (BOC), same reference typed differently
        TestUser other = customer();
        Shop secondStore = shop();
        String copycat = awaitingPayment(other, secondStore);
        String typed = " " + reference.substring(0, 3).toLowerCase() + " " + reference.substring(3) + " ";
        MockHttpServletResponse refused = submitPayment(other, copycat, secondStore, typed);

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(read(refused.getContentAsString(), "$.code")).isEqualTo("PAYMENT_REJECTED");
        assertThat(refused.getContentAsString()).doesNotContain(original); // generic message only
        assertThat(statusOf(copycat)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        FlaggedPaymentReference flag = flags.findAll().stream()
                .filter(f -> f.getOrderPublicId().equals(copycat)).findFirst().orElseThrow();
        assertThat(flag.getPublicId()).startsWith("FLG-");
        assertThat(flag.getNormalizedReference()).isEqualTo(reference);
        assertThat(flag.getBankCode()).isEqualTo("BOC");
        assertThat(flag.getOriginalOrderPublicId()).isEqualTo(original);
        assertThat(flag.getStatus()).isEqualTo(FlagStatus.OPEN);
        assertThat(read(lastEvent(copycat, "PaymentReferenceFlagged"), "$.flaggedReferencePublicId"))
                .isEqualTo(flag.getPublicId());

        // Admin review queue
        TestUser admin = admin();
        String queue = expect(getAs(admin, "/api/admin/flagged-references?status=OPEN&size=100"), 200)
                .getContentAsString();
        List<String> ids = com.jayway.jsonpath.JsonPath.read(queue, "$.content[*].publicId");
        assertThat(ids).contains(flag.getPublicId());
        expect(perform(post("/api/admin/flagged-references/" + flag.getPublicId() + "/review"), admin,
                "{\"action\":\"ESCALATED\",\"note\":\"Same slip used twice\"}"), 200);
        assertThat(flags.findByPublicId(flag.getPublicId()).orElseThrow().getStatus())
                .isEqualTo(FlagStatus.ESCALATED);
        assertThat(perform(post("/api/admin/flagged-references/" + flag.getPublicId() + "/review"), admin,
                "{\"action\":\"REVIEWED\",\"note\":\"again\"}").getStatus()).isEqualTo(409);

        // The honest customer's order is untouched; a fresh reference works for the other one
        assertThat(statusOf(original)).isEqualTo(OrderStatus.PAYMENT_SUBMITTED);
        expect(submitPayment(other, copycat, secondStore, "NEW" + next()), 200);
    }

    @Test
    void paymentSubmissionNeedsAStoreAccountAndAnIdempotencyKey() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        String order = awaitingPayment(customer, shop);
        MockHttpServletResponse noKey = perform(post("/api/customer/orders/" + order + "/payments"), customer, """
                {"referenceNumber":"A1","sourceBankCode":"COMB","sourceAccountNumber":"8001234567",
                 "storeBankAccountPublicId":"%s"}""".formatted(shop.bankAccount()));
        assertThat(read(noKey.getContentAsString(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        Shop otherStore = shop();
        MockHttpServletResponse wrongAccount = submitPayment(customer, order, otherStore, "A" + next());
        assertThat(read(wrongAccount.getContentAsString(), "$.code")).isEqualTo("BANK_ACCOUNT_NOT_AVAILABLE");
    }
}
