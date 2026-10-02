package com.achintha.orderservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.TestUser;
import org.junit.jupiter.api.Test;

/**
 * Object-level authorization (BOLA, section 3.4): customer queries are scoped by the JWT subject, store queries by the
 * JWT storeId; a foreign id is 404, never 403. Assistants act only with the route's permission.
 */
class OwnershipTest extends IntegrationTest {

    @Test
    void customerBCannotSeeOrTouchCustomerAsOrder() throws Exception {
        TestUser a = customer();
        TestUser b = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 10);
        String order = placeOrder(a, v, 1, "COD");

        assertThat(getAs(b, "/api/customer/orders/" + order).getStatus()).isEqualTo(404);
        assertThat(perform(post("/api/customer/orders/" + order + "/cancel"), b, null).getStatus()).isEqualTo(404);
        assertThat(getAs(b, "/api/customer/orders").getContentAsString()).doesNotContain(order);
        assertThat(getAs(a, "/api/customer/orders/" + order).getStatus()).isEqualTo(200);
    }

    @Test
    void merchantBCannotSeeOrQuoteMerchantAsOrder() throws Exception {
        TestUser customer = customer();
        Shop shopA = shop();
        Shop shopB = shop();
        Variant v = variant(shopA, "10.00", 10);
        String order = placeOrder(customer, v, 1, "COD");

        assertThat(getAs(shopB.merchant(), "/api/merchant/orders/" + order).getStatus()).isEqualTo(404);
        assertThat(quote(shopB, order, v, 1).getStatus()).isEqualTo(404);
        assertThat(getAs(shopB.merchant(), "/api/merchant/orders").getContentAsString()).doesNotContain(order);
        assertThat(getAs(shopA.merchant(), "/api/merchant/orders/" + order).getStatus()).isEqualTo(200);
    }

    @Test
    void assistantsNeedTheRoutePermission() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 10);
        String order = placeOrder(customer, v, 1, "COD");
        TestUser viewer = assistant(shop.merchant(), AssistantPermission.ORDER_VIEW);
        TestUser quoter = assistant(shop.merchant(), AssistantPermission.ORDER_QUOTE);
        Shop asViewer = shop.as(viewer);
        Shop asQuoter = shop.as(quoter);

        assertThat(getAs(viewer, "/api/merchant/orders/" + order).getStatus()).isEqualTo(200);
        assertThat(quote(asViewer, order, v, 1).getStatus()).isEqualTo(403);
        assertThat(perform(post("/api/merchant/customer-blocks"), viewer,
                "{\"customerPublicId\":\"%s\",\"reason\":\"x\"}".formatted(customer.publicId())).getStatus())
                .isEqualTo(403);
        assertThat(getAs(quoter, "/api/merchant/orders/" + order).getStatus()).isEqualTo(403);
        assertThat(quote(asQuoter, order, v, 1).getStatus()).isEqualTo(200);

        // Another store's assistant with every permission still sees nothing
        TestUser foreign = assistant(shop().merchant(), AssistantPermission.values());
        assertThat(getAs(foreign, "/api/merchant/orders/" + order).getStatus()).isEqualTo(404);
    }

    @Test
    void complaintsAreScopedToo() throws Exception {
        TestUser a = customer();
        TestUser b = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 10);
        String order = readyCodOrder(a, v);
        String complaint = read(expect(perform(post("/api/customer/complaints"), a,
                "{\"orderPublicId\":\"%s\",\"type\":\"NOT_SHIPPED\",\"text\":\"Late\"}".formatted(order)), 201)
                .getContentAsString(), "$.publicId");
        assertThat(getAs(b, "/api/customer/complaints/" + complaint).getStatus()).isEqualTo(404);
        assertThat(perform(post("/api/customer/complaints"), b,
                "{\"orderPublicId\":\"%s\",\"type\":\"OTHER\",\"text\":\"x\"}".formatted(order)).getStatus())
                .isEqualTo(404);
    }
}
