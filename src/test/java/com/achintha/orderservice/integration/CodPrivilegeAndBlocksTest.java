package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.TestUser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** COD privilege (D8), objections (section 13.1) and store blocks (section 6.5). */
class CodPrivilegeAndBlocksTest extends IntegrationTest {

    private String refused(TestUser customer, Shop shop) throws Exception {
        Variant v = variant(shop, "100.00", 100);
        String order = readyCodOrder(customer, v);
        expect(ship(shop, order), 200);
        expect(perform(post("/api/merchant/orders/" + order + "/delivery-failed"), shop.merchant(),
                "{\"type\":\"COD_REFUSED\"}"), 200);
        return order;
    }

    @Test
    void theThirdRefusalSuspendsCodAndAnUpheldObjectionLiftsIt() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        refused(customer, shop);
        refused(customer, shop);
        String third = refused(customer, shop);

        // COD is suspended; bank transfer still works
        Variant v = variant(shop, "100.00", 100);
        String item = addToCart(customer, v, 1);
        MockHttpServletResponse cod = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(read(cod.getContentAsString(), "$.results[0].code")).isEqualTo("COD_SUSPENDED");

        // The merchant sees the history; the admin sees the suspension
        String placedBank = placeOrder(customer, v, 1, "BANK_TRANSFER");
        String detail = expect(getAs(shop.merchant(), "/api/merchant/orders/" + placedBank), 200)
                .getContentAsString();
        assertThat(read(detail, "$.customer.codRefusals")).isEqualTo("3");
        assertThat(read(detail, "$.customer.codSuspended")).isEqualTo("true");
        TestUser admin = admin();
        String score = expect(getAs(admin, "/api/admin/customers/" + customer.publicId() + "/score"), 200)
                .getContentAsString();
        assertThat(read(score, "$.codSuspended")).isEqualTo("true");
        assertThat(read(score, "$.score")).isEqualTo("0"); // refusals never change the score

        // Customer objects to the refusal that caused the suspension; admin upholds
        expect(perform(post("/api/customer/orders/" + third + "/cod-objection"), customer,
                "{\"text\":\"I was home, the courier never came\"}"), 200);
        assertThat(perform(post("/api/customer/orders/" + third + "/cod-objection"), customer,
                "{\"text\":\"again\"}").getStatus()).isEqualTo(409);
        String queue = expect(getAs(admin, "/api/admin/orders/cod-objections?status=OPEN&size=100"), 200)
                .getContentAsString();
        assertThat(queue).contains(third);
        expect(perform(post("/api/admin/orders/" + third + "/cod-objection/decision"), admin,
                "{\"decision\":\"UPHELD\",\"note\":\"Courier confirmed no attempt\"}"), 200);

        String after = expect(getAs(admin, "/api/admin/customers/" + customer.publicId() + "/score"), 200)
                .getContentAsString();
        assertThat(read(after, "$.codSuspended")).isEqualTo("false");
        assertThat(read(after, "$.codRefusals")).isEqualTo("2");
        String item2 = addToCart(customer, v, 1);
        assertThat(expect(checkout(customer, List.of(item2), Map.of(shop, "COD"), "k-" + next()), 201)
                .getContentAsString()).contains("PLACED");
    }

    @Test
    void objectionsCloseAfterTheWindow() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        String order = refused(customer, shop);
        clock.advance(Duration.ofDays(8));
        MockHttpServletResponse late = perform(post("/api/customer/orders/" + order + "/cod-objection"), customer,
                "{\"text\":\"late\"}");
        assertThat(read(late.getContentAsString(), "$.code")).isEqualTo("OBJECTION_NOT_ALLOWED");
    }

    @Test
    void storesBlockOnlyTheirOwnCustomersAndCanUnblock() throws Exception {
        TestUser customer = customer();
        TestUser stranger = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 100);
        placeOrder(customer, v, 1, "COD");

        assertThat(perform(post("/api/merchant/customer-blocks"), shop.merchant(),
                "{\"customerPublicId\":\"%s\",\"reason\":\"x\"}".formatted(stranger.publicId())).getStatus())
                .isEqualTo(404);
        expect(perform(post("/api/merchant/customer-blocks"), shop.merchant(),
                "{\"customerPublicId\":\"%s\",\"reason\":\"Fake orders\"}".formatted(customer.publicId())), 201);
        assertThat(expect(getAs(shop.merchant(), "/api/merchant/customer-blocks"), 200).getContentAsString())
                .contains(customer.publicId());
        // Another store cannot see or lift it
        Shop other = shop();
        assertThat(perform(delete("/api/merchant/customer-blocks/" + customer.publicId()), other.merchant(), null)
                .getStatus()).isEqualTo(404);

        expect(perform(delete("/api/merchant/customer-blocks/" + customer.publicId() + "?reason=resolved"),
                shop.merchant(), null), 204);
        assertThat(placeOrder(customer, v, 1, "COD")).startsWith("ORD-");
    }
}
