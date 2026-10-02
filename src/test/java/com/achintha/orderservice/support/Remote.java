package com.achintha.orderservice.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * One WireMock server standing in for user-, store- and product-service for the whole test run (their paths do not
 * overlap). Serves the JWKS of {@link TestKeys}, a service token, the platform settings (contract defaults), an
 * empty holiday calendar, the customer's profile and address, per-store data, a fake catalog for the quote endpoint,
 * and accepts every reservation call (tests assert on the recorded requests).
 */
public final class Remote {

    public static final String KEY_ID = "test-key";
    public static final WireMockServer SERVER = new WireMockServer(wireMockConfig().dynamicPort());
    public static final String ADDRESS_ID = "ADR-2610-AAAAAA";
    public static final String COURIER = "DOMEX";
    public static final String NON_COD_COURIER = "FASTX";

    private static StubMapping quoteStub;
    private static final Map<UUID, String> CATALOG = new ConcurrentHashMap<>();

    static {
        SERVER.start();
        stubBasics();
    }

    private Remote() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    public static String jwksUri() {
        return SERVER.baseUrl() + "/.well-known/jwks.json";
    }

    private static void stubBasics() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) TestKeys.KEY_PAIR.getPublic())
                .keyID(KEY_ID).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        SERVER.stubFor(get(urlEqualTo("/.well-known/jwks.json")).willReturn(okJson(new JWKSet(key).toString())));
        SERVER.stubFor(post(urlEqualTo("/internal/auth/service-token")).willReturn(okJson("""
                {"accessToken":"service-token","tokenType":"Bearer","expiresIn":300}""")));
        SERVER.stubFor(get(urlEqualTo("/internal/settings")).willReturn(okJson(settingsJson())));
        SERVER.stubFor(get(urlPathEqualTo("/internal/holidays")).willReturn(okJson("[]")));
        SERVER.stubFor(get(urlEqualTo("/api/users/me")).willReturn(okJson("""
                {"publicId":"USR-2610-CCCCCC","email":"customer@example.com","firstName":"Nimal","lastName":"Perera",
                 "phone":"+94771234567","role":"ROLE_CUSTOMER","status":"ACTIVE"}""")));
        SERVER.stubFor(get(urlEqualTo("/api/users/me/addresses/" + ADDRESS_ID)).willReturn(okJson("""
                {"publicId":"%s","recipientName":"Nimal Perera","phone":"+94771234567","line1":"12 Galle Road",
                 "line2":null,"city":"Colombo","district":"Colombo","postalCode":"00300","country":"Sri Lanka"}"""
                .formatted(ADDRESS_ID))));
        // Reservations: accepted and recorded
        String reservation = """
                {"orderRef":"x","status":"HELD","expiresAt":null,"lines":[]}""";
        SERVER.stubFor(post(urlEqualTo("/internal/reservations"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(reservation)));
        SERVER.stubFor(put(urlMatching("/internal/reservations/[A-Z0-9-]+")).willReturn(okJson(reservation)));
        SERVER.stubFor(post(urlMatching("/internal/reservations/[A-Z0-9-]+/(commit|release)"))
                .willReturn(okJson(reservation)));
        refreshCatalog();
    }

    /** Contract section 7 defaults. */
    public static String settingsJson() {
        Map<String, String> settings = Map.ofEntries(
                Map.entry("timers.merchant-response-hours", "24"),
                Map.entry("timers.customer-confirmation-hours", "24"),
                Map.entry("timers.payment-submission-hours", "24"),
                Map.entry("timers.payment-verification-hours", "24"),
                Map.entry("timers.ship-by-hours", "72"),
                Map.entry("timers.auto-complete-days", "7"),
                Map.entry("score.customer.completed", "1"),
                Map.entry("score.customer.declined", "-1"),
                Map.entry("score.customer.expired", "-1"),
                Map.entry("cod.objection-window-days", "7"),
                Map.entry("score.customer.cod-refusal-limit", "3"),
                Map.entry("score.customer.cod-suspension-months", "3"),
                Map.entry("orders.max-open-unconfirmed-per-customer", "10"),
                Map.entry("orders.max-quote-revisions", "2"),
                Map.entry("complaints.window-days", "30"));
        return settings.entrySet().stream()
                .map(e -> "{\"key\":\"%s\",\"value\":\"%s\",\"type\":\"INTEGER\"}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    // -------------------------------------------------------------------------------------------- store-service

    public static void stubStore(UUID storeId, String publicId, String name, boolean visible, boolean codEnabled,
                                 String bankAccountPublicId) {
        SERVER.stubFor(get(urlEqualTo("/internal/stores/" + storeId)).willReturn(okJson("""
                {"id":"%s","publicId":"%s","name":"%s","slug":"s","published":%s,"acceptingOrders":%s,
                 "visible":%s,"codEnabled":%s}""".formatted(storeId, publicId, name, visible, visible, visible,
                codEnabled))));
        SERVER.stubFor(get(urlEqualTo("/internal/stores/" + storeId + "/couriers")).willReturn(okJson("""
                [{"code":"%s","name":"Domex","trackingUrlTemplate":"https://track.example/domex/{trackingNumber}",
                  "trackingNumberRegex":"^DX[0-9]{8}$","codSupported":true,"active":true},
                 {"code":"%s","name":"FastX","trackingUrlTemplate":"https://track.example/fastx/{trackingNumber}",
                  "trackingNumberRegex":null,"codSupported":false,"active":true}]"""
                .formatted(COURIER, NON_COD_COURIER))));
        SERVER.stubFor(get(urlEqualTo("/internal/stores/" + storeId + "/bank-accounts")).willReturn(okJson("""
                [{"id":"%s","publicId":"%s","bankCode":"BOC","bankName":"Bank of Ceylon","branch":"Colombo 03",
                  "accountName":"%s","accountNumber":"0012345678","accountNumberMasked":"****5678"}]"""
                .formatted(UUID.nameUUIDFromBytes(storeId.toString().getBytes()), bankAccountPublicId, name))));
    }

    // ------------------------------------------------------------------------------------------ product-service

    /** Adds or replaces a variant in the fake catalog served by {@code POST /internal/variants/quote}. */
    public static void putVariant(UUID variantId, String json) {
        CATALOG.put(variantId, json);
        refreshCatalog();
    }

    private static synchronized void refreshCatalog() {
        String items = String.join(",", CATALOG.values());
        StubMapping previous = quoteStub;
        quoteStub = SERVER.stubFor(post(urlEqualTo("/internal/variants/quote"))
                .willReturn(okJson("{\"items\":[" + items + "],\"missing\":[]}")));
        if (previous != null) {
            SERVER.removeStub(previous);
        }
    }

    /** product-service refuses to hold any reservation containing this variant (409 INSUFFICIENT_STOCK). */
    public static void refuseReservationsOf(UUID variantId) {
        SERVER.stubFor(post(urlEqualTo("/internal/reservations")).atPriority(1)
                .withRequestBody(matchingJsonPath("$.lines[?(@.variantId == '" + variantId + "')]"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":409,\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"no\"}")));
    }

    // ------------------------------------------------------------------------------------------- verification

    public static int reserveCalls(String orderRef) {
        return count(postRequestedFor(urlEqualTo("/internal/reservations"))
                .withRequestBody(matchingJsonPath("$[?(@.orderRef == '" + orderRef + "')]")));
    }

    public static int adjustCalls(String orderRef) {
        return count(putRequestedFor(urlEqualTo("/internal/reservations/" + orderRef)));
    }

    public static int commitCalls(String orderRef) {
        return count(postRequestedFor(urlEqualTo("/internal/reservations/" + orderRef + "/commit")));
    }

    public static int releaseCalls(String orderRef) {
        return count(postRequestedFor(urlEqualTo("/internal/reservations/" + orderRef + "/release")));
    }

    /** The JSON body of the last adjust call for the order. */
    public static String lastAdjustBody(String orderRef) {
        var requests = SERVER.findAll(putRequestedFor(urlEqualTo("/internal/reservations/" + orderRef)));
        return requests.isEmpty() ? null : requests.getLast().getBodyAsString();
    }

    private static int count(RequestPatternBuilder pattern) {
        return SERVER.countRequestsMatching(pattern.build()).getCount();
    }
}
