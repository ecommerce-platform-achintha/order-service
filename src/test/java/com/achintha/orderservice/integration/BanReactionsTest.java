package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.security.UserStatus;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Reactions to user-events bans (section 3.2, D4, D5) and admin resolution. */
class BanReactionsTest extends IntegrationTest {

    @Test
    void bannedCustomerLosesUnconfirmedOrdersWithoutPenaltyButKeepsConfirmedOnes() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 100);
        String waiting = placeOrder(customer, v, 1, "COD");
        String quoted = placeOrder(customer, v, 1, "COD");
        expect(quote(shop, quoted, v, 1), 200);
        String confirmed = readyCodOrder(customer, v);

        statusChanged(customer, UserStatus.BANNED);

        assertThat(statusOf(waiting)).isEqualTo(OrderStatus.CANCELLED_BY_SYSTEM);
        assertThat(statusOf(quoted)).isEqualTo(OrderStatus.CANCELLED_BY_SYSTEM);
        assertThat(statusOf(confirmed)).isEqualTo(OrderStatus.READY_TO_SHIP);
        assertThat(Remote.releaseCalls(waiting)).isEqualTo(1);
        assertThat(read(lastEvent(waiting, "OrderCancelled"), "$.terminalStatus")).isEqualTo("CANCELLED_BY_SYSTEM");
        assertThat(lastEvent(waiting, "OrderCancelled")).doesNotContain("customerPenalty");

        // Can still view history, cannot buy
        TestUser banned = customer.withTokenVersion(2);
        expect(getAs(banned, "/api/customer/orders/" + confirmed), 200);
        String item = addToCart(banned, v, 1);
        MockHttpServletResponse checkout = checkout(banned, java.util.List.of(item), java.util.Map.of(shop, "COD"),
                "k-" + next());
        assertThat(read(checkout.getContentAsString(), "$.code")).isEqualTo("CUSTOMER_BANNED");
        // An old token is revoked
        assertThat(getAs(customer, "/api/customer/orders").getStatus()).isEqualTo(401);
    }

    @Test
    void bannedMerchantsOpenOrdersNeedAdminResolution() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 100);
        String open = placeOrder(customer, v, 1, "COD");
        String ready = readyCodOrder(customer, v);

        statusChanged(shop.merchant(), UserStatus.BANNED);

        assertThat(order(open).isNeedsAdminResolution()).isTrue();
        assertThat(order(ready).isNeedsAdminResolution()).isTrue();
        assertThat(statusOf(open)).isEqualTo(OrderStatus.AWAITING_MERCHANT);
        assertThat(eventTypes(open)).contains("OrderNeedsAdminResolution");

        TestUser admin = admin();
        String queue = expect(getAs(admin, "/api/admin/orders?needsResolution=true&size=100"), 200)
                .getContentAsString();
        assertThat(queue).contains(open).contains(ready);

        expect(perform(post("/api/admin/orders/" + open + "/resolve"), admin,
                "{\"action\":\"CANCEL\",\"reason\":\"Merchant banned\"}"), 200);
        assertThat(statusOf(open)).isEqualTo(OrderStatus.CLOSED_BY_ADMIN);
        assertThat(Remote.releaseCalls(open)).isEqualTo(1);
        assertThat(order(open).isNeedsAdminResolution()).isFalse();

        expect(perform(post("/api/admin/orders/" + ready + "/resolve"), superAdmin(),
                "{\"action\":\"FORCE_COMPLETE\",\"reason\":\"Delivered, confirmed by phone\"}"), 200);
        assertThat(statusOf(ready)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(read(lastEvent(ready, "OrderCompleted"), "$.resolution")).isEqualTo("FORCE_COMPLETE");

        // The banned merchant (new token after the ban) can read but not act; the old token is revoked
        Variant w = variant(shop, "1.00", 10);
        String another = placeOrder(customer(), w, 1, "COD");
        assertThat(getAs(shop.merchant(), "/api/merchant/orders/" + another).getStatus()).isEqualTo(401);
        Shop banned = shop.as(shop.merchant().withTokenVersion(2));
        expect(getAs(banned.merchant(), "/api/merchant/orders/" + another), 200);
        assertThat(quote(banned, another, w, 1).getStatus()).isEqualTo(403);
    }

    @Test
    void duplicateUserEventsAreIgnored() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 100);
        String order = placeOrder(customer, v, 1, "COD");
        var event = new com.achintha.orderservice.events.UserEvent(UUID.randomUUID(), "UserStatusChanged",
                clock.instant(), customer.id(), customer.publicId(), "ROLE_CUSTOMER", "BANNED", "ACTIVE", null, 2L,
                null);
        assertThat(userEventHandler.handle(event)).isTrue();
        assertThat(userEventHandler.handle(event)).isFalse();
        assertThat(eventTypes(order).stream().filter("OrderCancelled"::equals)).hasSize(1);
    }
}
