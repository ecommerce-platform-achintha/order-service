package com.achintha.orderservice.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Role-by-endpoint authorization matrix (section 11): every endpoint called by every role and anonymously.
 * "Allowed" calls target non-existent ids, so 404 (or 200 for lists) proves the call got past authorization; 401/403
 * prove it did not. Assistants pass {@code /api/merchant/**} routes only with the route's permission.
 */
class AuthorizationMatrixTest extends IntegrationTest {

    enum Who { ANONYMOUS, CUSTOMER, MERCHANT, ASSISTANT, ASSISTANT_NO_PERMS, ADMIN, SUPER_ADMIN, SERVICE }

    private static final String MISSING = "ORD-2610-ZZZZZZ";
    private static Map<Who, String> tokens;

    @BeforeEach
    void fixtures() {
        if (tokens != null) {
            return;
        }
        TestUser merchant = merchant();
        tokens = new EnumMap<>(Who.class);
        tokens.put(Who.CUSTOMER, tokenFor(customer()));
        tokens.put(Who.MERCHANT, tokenFor(merchant));
        tokens.put(Who.ASSISTANT, tokenFor(assistant(merchant, AssistantPermission.values())));
        tokens.put(Who.ASSISTANT_NO_PERMS, tokenFor(assistant(merchant, AssistantPermission.PRODUCT_EDIT)));
        tokens.put(Who.ADMIN, tokenFor(admin()));
        tokens.put(Who.SUPER_ADMIN, tokenFor(superAdmin()));
        tokens.put(Who.SERVICE, serviceToken());
    }

    record Endpoint(HttpMethod method, String path, String body, Map<Who, Integer> allowed) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Arguments> matrix() {
        List<Arguments> arguments = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            for (Who who : Who.values()) {
                int expected = endpoint.allowed().getOrDefault(who, who == Who.ANONYMOUS ? 401 : 403);
                arguments.add(Arguments.of(endpoint, who, expected));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("matrix")
    void endpointAnswersAsExpected(Endpoint endpoint, Who who, int expected) throws Exception {
        MockHttpServletRequestBuilder request = request(endpoint.method(), endpoint.path())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (endpoint.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }
        if (tokens.get(who) != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(who));
        }
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        Assertions.assertThat(response.getStatus())
                .as("%s as %s: %s", endpoint, who, response.getContentAsString())
                .isEqualTo(expected);
    }

    private static Map<Who, Integer> only(int status, Who... who) {
        Map<Who, Integer> map = new EnumMap<>(Who.class);
        for (Who w : who) {
            map.put(w, status);
        }
        return map;
    }

    private static List<Endpoint> endpoints() {
        Map<Who, Integer> customer200 = only(200, Who.CUSTOMER);
        Map<Who, Integer> customer404 = only(404, Who.CUSTOMER);
        Map<Who, Integer> store200 = only(200, Who.MERCHANT, Who.ASSISTANT);
        Map<Who, Integer> store404 = only(404, Who.MERCHANT, Who.ASSISTANT);
        Map<Who, Integer> admin200 = only(200, Who.ADMIN, Who.SUPER_ADMIN);
        Map<Who, Integer> admin404 = only(404, Who.ADMIN, Who.SUPER_ADMIN);
        String o = "/api/customer/orders/" + MISSING;
        String m = "/api/merchant/orders/" + MISSING;
        String a = "/api/admin/orders/" + MISSING;
        String quote = """
                {"lines":[{"variantPublicId":"VAR-2610-ZZZZZZ","quantity":1}],"courierCode":"DOMEX",
                 "courierCharge":100}""";
        String payment = """
                {"referenceNumber":"R1","sourceBankCode":"BOC","sourceAccountNumber":"1234567",
                 "storeBankAccountPublicId":"BAC-2610-ZZZZZZ"}""";
        String checkout = """
                {"cartItemIds":["CRT-2610-ZZZZZZ"],"addressPublicId":"%s",
                 "payments":[{"storePublicId":"STR-2610-ZZZZZZ","method":"COD"}]}""".formatted(Remote.ADDRESS_ID);
        return List.of(
                // Customer
                new Endpoint(HttpMethod.GET, "/api/customer/cart", null, customer200),
                new Endpoint(HttpMethod.POST, "/api/customer/cart/items",
                        "{\"variantPublicId\":\"VAR-2610-ZZZZZZ\",\"quantity\":1}", customer404),
                new Endpoint(HttpMethod.PUT, "/api/customer/cart/items/CRT-2610-ZZZZZZ", "{\"quantity\":1}",
                        customer404),
                new Endpoint(HttpMethod.DELETE, "/api/customer/cart/items/CRT-2610-ZZZZZZ", null, customer404),
                new Endpoint(HttpMethod.DELETE, "/api/customer/cart", null, customer200),
                new Endpoint(HttpMethod.POST, "/api/customer/checkout", checkout, customer404),
                new Endpoint(HttpMethod.GET, "/api/customer/orders", null, customer200),
                new Endpoint(HttpMethod.GET, o, null, customer404),
                new Endpoint(HttpMethod.POST, o + "/cancel", null, customer404),
                new Endpoint(HttpMethod.POST, o + "/confirm", null, customer404),
                new Endpoint(HttpMethod.POST, o + "/decline", null, customer404),
                new Endpoint(HttpMethod.GET, o + "/bank-accounts", null, customer404),
                new Endpoint(HttpMethod.POST, o + "/payments", payment, customer404),
                new Endpoint(HttpMethod.POST, o + "/received", null, customer404),
                new Endpoint(HttpMethod.POST, o + "/cod-objection", "{\"text\":\"x\"}", customer404),
                new Endpoint(HttpMethod.POST, "/api/customer/complaints",
                        "{\"orderPublicId\":\"%s\",\"type\":\"OTHER\",\"text\":\"x\"}".formatted(MISSING),
                        customer404),
                new Endpoint(HttpMethod.GET, "/api/customer/complaints", null, customer200),
                new Endpoint(HttpMethod.GET, "/api/customer/complaints/CMP-2610-ZZZZZZ", null, customer404),
                // Merchant / assistant with the route permission
                new Endpoint(HttpMethod.GET, "/api/merchant/orders", null, store200),
                new Endpoint(HttpMethod.GET, m, null, store404),
                new Endpoint(HttpMethod.POST, m + "/reject", "{\"reasonCode\":\"OTHER\"}", store404),
                new Endpoint(HttpMethod.POST, m + "/quote", quote, store404),
                new Endpoint(HttpMethod.POST, m + "/payment/verify", null, store404),
                new Endpoint(HttpMethod.POST, m + "/payment/reject", "{\"reason\":\"x\"}", store404),
                new Endpoint(HttpMethod.POST, m + "/ship", "{\"courierCode\":\"DOMEX\",\"trackingNumber\":\"DX1\"}",
                        store404),
                new Endpoint(HttpMethod.POST, m + "/delivery-failed", "{\"type\":\"COD_REFUSED\"}", store404),
                new Endpoint(HttpMethod.GET, "/api/merchant/orders/complaints", null, store200),
                new Endpoint(HttpMethod.GET, "/api/merchant/orders/complaints/CMP-2610-ZZZZZZ", null, store404),
                new Endpoint(HttpMethod.POST, "/api/merchant/orders/complaints/CMP-2610-ZZZZZZ/response",
                        "{\"response\":\"x\"}", store404),
                new Endpoint(HttpMethod.GET, "/api/merchant/customer-blocks", null, store200),
                new Endpoint(HttpMethod.POST, "/api/merchant/customer-blocks",
                        "{\"customerPublicId\":\"USR-2610-ZZZZZZ\",\"reason\":\"x\"}", store404),
                new Endpoint(HttpMethod.DELETE, "/api/merchant/customer-blocks/USR-2610-ZZZZZZ", null, store404),
                // Admin
                new Endpoint(HttpMethod.GET, "/api/admin/orders", null, admin200),
                new Endpoint(HttpMethod.GET, a, null, admin404),
                new Endpoint(HttpMethod.POST, a + "/resolve", "{\"action\":\"CANCEL\",\"reason\":\"x\"}", admin404),
                new Endpoint(HttpMethod.GET, "/api/admin/orders/cod-objections", null, admin200),
                new Endpoint(HttpMethod.POST, a + "/cod-objection/decision",
                        "{\"decision\":\"DISMISSED\",\"note\":\"x\"}", admin404),
                new Endpoint(HttpMethod.GET, "/api/admin/complaints", null, admin200),
                new Endpoint(HttpMethod.GET, "/api/admin/complaints/CMP-2610-ZZZZZZ", null, admin404),
                new Endpoint(HttpMethod.POST, "/api/admin/complaints/CMP-2610-ZZZZZZ/decision",
                        "{\"decision\":\"DISMISSED\",\"notes\":\"x\"}", admin404),
                new Endpoint(HttpMethod.GET, "/api/admin/flagged-references", null, admin200),
                new Endpoint(HttpMethod.GET, "/api/admin/flagged-references/FLG-2610-ZZZZZZ", null, admin404),
                new Endpoint(HttpMethod.POST, "/api/admin/flagged-references/FLG-2610-ZZZZZZ/review",
                        "{\"action\":\"REVIEWED\",\"note\":\"x\"}", admin404),
                new Endpoint(HttpMethod.GET, "/api/admin/customers/USR-2610-ZZZZZZ/score", null, admin404),
                // Internal: service tokens only (order-service exposes no internal endpoint yet)
                new Endpoint(HttpMethod.GET, "/internal/anything", null, only(404, Who.SERVICE)),
                // Anything else: denied by default
                new Endpoint(HttpMethod.GET, "/api/orders", null, Map.of()),
                new Endpoint(HttpMethod.POST, "/api/orders", "{\"userId\":\"%s\"}".formatted(UUID.randomUUID()),
                        Map.of()));
    }
}
