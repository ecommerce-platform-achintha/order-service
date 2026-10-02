package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.security.UserStatus;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Checkout (section 6.1): grouping per store, every precondition with its own code, idempotency. */
class CheckoutIntegrationTest extends IntegrationTest {

    @Test
    void partialCheckoutAcrossStoresPlacesTheGoodGroupsAndKeepsTheRestInTheCart() throws Exception {
        TestUser customer = customer();
        Shop open = shop();
        Shop closed = shop(false, true);
        Shop noCod = shop(true, false);
        Variant a = variant(open, "100.00", 5);
        Variant b = variant(closed, "200.00", 5);
        Variant c = variant(noCod, "300.00", 5);
        Variant a2 = variant(open, "50.00", 5);
        List<String> items = List.of(addToCart(customer, a, 1), addToCart(customer, b, 1), addToCart(customer, c, 1),
                addToCart(customer, a2, 2));
        Map<Shop, String> methods = new LinkedHashMap<>();
        methods.put(open, "BANK_TRANSFER");
        methods.put(closed, "COD");
        methods.put(noCod, "COD");

        String body = expect(checkout(customer, items, methods, "key-" + next()), 201).getContentAsString();

        assertThat(group(body, open, "status")).isEqualTo("PLACED");
        assertThat(group(body, closed, "code")).isEqualTo("STORE_NOT_ACCEPTING_ORDERS");
        assertThat(group(body, noCod, "code")).isEqualTo("COD_NOT_AVAILABLE_FOR_STORE");
        String order = group(body, open, "orderPublicId");
        assertThat(order(order).getGrandTotal()).isEqualByComparingTo("200.00"); // one order for both lines
        // Only the placed store's lines left the cart
        String cart = expect(getAs(customer, "/api/customer/cart"), 200).getContentAsString();
        assertThat(read(cart, "$.lineCount")).isEqualTo("2");
    }

    @Test
    void checkoutIsIdempotentPerKey() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String item = addToCart(customer, v, 1);
        String key = "idem-" + UUID.randomUUID();

        MockHttpServletResponse first = expect(checkout(customer, List.of(item), Map.of(shop, "COD"), key), 201);
        MockHttpServletResponse replay = expect(checkout(customer, List.of(item), Map.of(shop, "COD"), key), 201);

        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        String orderPublicId = read(first.getContentAsString(), "$.results[0].orderPublicId");
        assertThat(read(replay.getContentAsString(), "$.results[0].orderPublicId")).isEqualTo(orderPublicId);
        assertThat(Remote.reserveCalls(orderPublicId)).isEqualTo(1);
        List<String> orders = JsonPath.read(expect(getAs(customer, "/api/customer/orders"), 200)
                .getContentAsString(), "$.content[*].publicId");
        assertThat(orders).containsExactly(orderPublicId);

        // Same key, different request
        MockHttpServletResponse reused = checkout(customer, List.of(item), Map.of(shop, "BANK_TRANSFER"), key);
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(read(reused.getContentAsString(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // No key
        MockHttpServletResponse missing = checkout(customer, List.of(item), Map.of(shop, "COD"), null);
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(read(missing.getContentAsString(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void openUnconfirmedOrdersAreCapped() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 1000);
        for (int i = 0; i < 10; i++) {
            placeOrder(customer, v, 1, "COD");
        }
        String item = addToCart(customer, v, 1);
        MockHttpServletResponse eleventh = checkout(customer, List.of(item), Map.of(shop, "COD"), "key-" + next());
        assertThat(eleventh.getStatus()).isEqualTo(422);
        assertThat(read(eleventh.getContentAsString(), "$.results[0].code")).isEqualTo("OPEN_ORDER_LIMIT_REACHED");
        assertThat(eleventh.getContentAsString()).doesNotContain("checkoutPublicId");
    }

    @Test
    void blockedCustomerCannotOrderFromThatStore() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 100);
        placeOrder(customer, v, 1, "COD"); // a store can only block its own customers
        expect(perform(post("/api/merchant/customer-blocks"), shop.merchant(),
                "{\"customerPublicId\":\"%s\",\"reason\":\"Abusive\"}".formatted(customer.publicId())), 201);

        String item = addToCart(customer, v, 1);
        MockHttpServletResponse response = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(read(response.getContentAsString(), "$.results[0].code")).isEqualTo("CUSTOMER_BLOCKED_BY_STORE");

        // Another store is unaffected
        Shop other = shop();
        Variant w = variant(other, "10.00", 100);
        assertThat(placeOrder(customer, w, 1, "COD")).startsWith("ORD-");
    }

    @Test
    void codMustBeAllowedForEveryProduct() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant noCod = variant(shop, "10.00", "0.00", 100, false, true);
        String item = addToCart(customer, noCod, 1);
        MockHttpServletResponse response = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(read(response.getContentAsString(), "$.results[0].code")).isEqualTo("COD_NOT_ALLOWED_FOR_PRODUCT");
        // Bank transfer is fine
        assertThat(expect(checkout(customer, List.of(item), Map.of(shop, "BANK_TRANSFER"), "k-" + next()), 201)
                .getContentAsString()).contains("PLACED");
    }

    @Test
    void stockIsCheckedAndAFailedHoldLeavesNothingBehind() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant scarce = variant(shop, "10.00", 1);
        String item = addToCart(customer, scarce, 1);
        updateVariant(scarce, "10.00", "0.00", 0, true, true);
        MockHttpServletResponse soldOut = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(read(soldOut.getContentAsString(), "$.results[0].code")).isEqualTo("INSUFFICIENT_STOCK");

        // product-service itself refuses the hold (someone else took the last unit in between)
        updateVariant(scarce, "10.00", "0.00", 5, true, true);
        Remote.refuseReservationsOf(scarce.id());
        MockHttpServletResponse refused = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(read(refused.getContentAsString(), "$.results[0].code")).isEqualTo("INSUFFICIENT_STOCK");
        List<Object> orders = JsonPath.read(expect(getAs(customer, "/api/customer/orders"), 200)
                .getContentAsString(), "$.content");
        assertThat(orders).isEmpty();
        assertThat(read(expect(getAs(customer, "/api/customer/cart"), 200).getContentAsString(), "$.lineCount"))
                .isEqualTo("1");
    }

    @Test
    void unavailableVariantsCannotBeAddedAndCartReadsAreLive() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant hidden = variant(shop, "10.00", "0.00", 5, true, false);
        MockHttpServletResponse add = perform(post("/api/customer/cart/items"), customer,
                "{\"variantPublicId\":\"%s\",\"quantity\":1}".formatted(hidden.publicId()));
        assertThat(add.getStatus()).isEqualTo(409);
        assertThat(read(add.getContentAsString(), "$.code")).isEqualTo("VARIANT_NOT_AVAILABLE");

        Variant v = variant(shop, "100.00", 5);
        addToCart(customer, v, 2);
        updateVariant(v, "120.00", "20.00", 1, true, true);
        String cart = expect(getAs(customer, "/api/customer/cart"), 200).getContentAsString();
        assertThat(read(cart, "$.stores[0].lines[0].unitPrice")).isEqualTo("100.0");
        assertThat(read(cart, "$.stores[0].lines[0].issue")).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void bannedCustomerCannotCheckOut() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 5);
        String item = addToCart(customer, v, 1);
        securityStateCache.apply(customer.id(), customer.tokenVersion(), UserStatus.BANNED);
        MockHttpServletResponse response = checkout(customer, List.of(item), Map.of(shop, "COD"), "k-" + next());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(read(response.getContentAsString(), "$.code")).isEqualTo("CUSTOMER_BANNED");
    }

    @Test
    void everyStoreNeedsAPaymentMethodAndClientsCannotSendServerFields() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 5);
        String item = addToCart(customer, v, 1);
        MockHttpServletResponse missing = checkout(customer, List.of(item), Map.of(shop(), "COD"), "k-" + next());
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(read(missing.getContentAsString(), "$.code")).isEqualTo("PAYMENT_METHOD_MISSING");

        // A smuggled customerId/status/price is ignored: identity and prices come from the JWT and product-service
        TestUser victim = customer();
        String body = """
                {"cartItemIds":["%s"],"addressPublicId":"%s","payments":[{"storePublicId":"%s","method":"COD"}],
                 "customerId":"%s","userId":"%s","status":"COMPLETED","grandTotal":1.00}"""
                .formatted(item, Remote.ADDRESS_ID, shop.publicId(), victim.id(), victim.id());
        String placed = expect(perform(post("/api/customer/checkout").header("Idempotency-Key", "k-" + next()),
                customer, body), 201).getContentAsString();
        String order = read(placed, "$.results[0].orderPublicId");
        assertThat(order(order).getCustomerId()).isEqualTo(customer.id());
        assertThat(order(order).getStatus()).isEqualTo(OrderStatus.AWAITING_MERCHANT);
        assertThat(order(order).getGrandTotal()).isEqualByComparingTo("10.00");
    }

    private static String group(String body, Shop shop, String field) {
        List<Object> values = JsonPath.read(body,
                "$.results[?(@.storePublicId == '" + shop.publicId() + "')]." + field);
        return values.isEmpty() || values.getFirst() == null ? null : values.getFirst().toString();
    }
}
