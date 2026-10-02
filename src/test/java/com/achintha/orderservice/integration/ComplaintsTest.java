package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.TestUser;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Complaints (section 6.6). */
class ComplaintsTest extends IntegrationTest {

    @Test
    void customerFilesMerchantRespondsOnceAdminUpholds() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 100);
        String early = placeOrder(customer, v, 1, "COD");
        assertThat(code(file(customer, early))).isEqualTo("COMPLAINT_NOT_ALLOWED");

        String order = readyCodOrder(customer, v);
        String created = expect(file(customer, order), 201).getContentAsString();
        String complaint = read(created, "$.publicId");
        assertThat(complaint).startsWith("CMP-");
        assertThat(read(created, "$.text")).startsWith("Wrong colour").doesNotContain("<script>");
        assertThat(code(file(customer, order))).isEqualTo("COMPLAINT_NOT_ALLOWED"); // one open complaint per order

        // Another store cannot see it
        assertThat(getAs(shop().merchant(), "/api/merchant/orders/complaints/" + complaint).getStatus())
                .isEqualTo(404);
        expect(perform(post("/api/merchant/orders/complaints/" + complaint + "/response"), shop.merchant(),
                "{\"response\":\"We will replace it\"}"), 200);
        assertThat(code(perform(post("/api/merchant/orders/complaints/" + complaint + "/response"),
                shop.merchant(), "{\"response\":\"again\"}"))).isEqualTo("COMPLAINT_ALREADY_RESPONDED");

        String decided = expect(perform(post("/api/admin/complaints/" + complaint + "/decision"), admin(),
                "{\"decision\":\"UPHELD\",\"notes\":\"Photos confirm\"}"), 200).getContentAsString();
        assertThat(read(decided, "$.status")).isEqualTo("UPHELD");
        String event = lastEvent(order, "ComplaintDecided");
        assertThat(read(event, "$.decision")).isEqualTo("UPHELD");
        assertThat(read(event, "$.merchantPenalty")).isEqualTo("COMPLAINT_UPHELD");
        assertThat(read(event, "$.complaintPublicId")).isEqualTo(complaint);
    }

    @Test
    void complaintsCloseAfterTheWindow() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 100);
        String order = readyCodOrder(customer, v);
        expect(ship(shop, order), 200);
        expect(perform(post("/api/customer/orders/" + order + "/received"), customer, null), 200);
        clock.advance(Duration.ofDays(31));
        assertThat(code(file(customer, order))).isEqualTo("COMPLAINT_NOT_ALLOWED");
    }

    private MockHttpServletResponse file(TestUser customer, String order) throws Exception {
        return perform(post("/api/customer/complaints"), customer, """
                {"orderPublicId":"%s","type":"WRONG_ITEM","text":"Wrong colour, <script>and alert(1)</script>",
                 "attachmentKeys":["complaints/photo1.jpg"]}""".formatted(order));
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return read(response.getContentAsString(), "$.code");
    }
}
