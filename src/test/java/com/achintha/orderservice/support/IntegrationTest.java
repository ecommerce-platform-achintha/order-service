package com.achintha.orderservice.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.orderservice.events.UserEvent;
import com.achintha.orderservice.events.UserEventHandler;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.outbox.OutboxMessage;
import com.achintha.orderservice.outbox.OutboxRepository;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.scheduler.OrderDeadlineScheduler;
import com.achintha.orderservice.security.AssistantPermission;
import com.achintha.orderservice.security.Role;
import com.achintha.orderservice.security.SecurityStateCache;
import com.achintha.orderservice.security.UserStatus;
import com.jayway.jsonpath.JsonPath;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the integration tests: the full application against a real PostgreSQL (Testcontainers), an embedded
 * Kafka broker, and WireMock standing in for user-, store- and product-service ({@link Remote}), all shared by the
 * whole run so the Spring context is cached. Schedulers are off; tests call the jobs directly. The clock is a
 * {@link MutableClock}. Tokens are minted with {@link TestKeys}, exactly as user-service would.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.scheduling.enabled=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "security.internal.client-secret=" + IntegrationTest.SERVICE_CLIENT_SECRET,
        // 32 zero bytes: a test-only AES key
        "app.crypto.payment-account-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "logging.level.org.apache.kafka=WARN",
        "logging.level.kafka=WARN",
        "logging.level.state.change.logger=WARN"
})
@AutoConfigureMockMvc
@EmbeddedKafka(topics = {"user-events", "store-events", "order-events", "product-events"}, partitions = 1)
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    public static final String SERVICE_CLIENT_SECRET = "test-order-service-secret-123456";

    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000L));
    private static final PostgreSQLContainer POSTGRES = SharedPostgres.CONTAINER;
    private static final JwtEncoder ENCODER = NimbusJwtEncoder
            .withKeyPair((RSAPublicKey) TestKeys.KEY_PAIR.getPublic(), (RSAPrivateKey) TestKeys.KEY_PAIR.getPrivate())
            .algorithm(SignatureAlgorithm.RS256)
            .jwkPostProcessor(jwk -> jwk.keyID(Remote.KEY_ID))
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.jwks-uri", Remote::jwksUri);
        for (String service : List.of("user-service", "store-service", "product-service")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", Remote::baseUrl);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected TransactionTemplate transactionTemplate;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected SecurityStateCache securityStateCache;
    @Autowired
    protected OrderRepository orderRepository;
    @Autowired
    protected OutboxRepository outboxRepository;
    @Autowired
    protected OrderDeadlineScheduler scheduler;
    @Autowired
    protected UserEventHandler userEventHandler;
    @Autowired
    protected PlatformSettings platformSettings;

    @AfterEach
    void resetClock() {
        clock.reset();
        platformSettings.invalidate();
    }

    // ================================================================================================= users

    protected static long next() {
        return SEQUENCE.incrementAndGet();
    }

    protected static String suffix() {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        long n = next();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            s.append(alphabet.charAt((int) (n % 32)));
            n /= 32;
        }
        return s.toString();
    }

    /** A user known to the security-state cache (as if user-events had been consumed). */
    protected TestUser user(Role role, UserStatus status, UUID storeId, List<String> perms) {
        TestUser user = new TestUser(UUID.randomUUID(), "USR-2610-" + suffix(), role, storeId, perms, 1);
        securityStateCache.apply(user.id(), user.tokenVersion(), status);
        return user;
    }

    protected TestUser customer() {
        return user(Role.ROLE_CUSTOMER, UserStatus.ACTIVE, null, null);
    }

    protected TestUser merchant() {
        return user(Role.ROLE_MERCHANT, UserStatus.ACTIVE, UUID.randomUUID(), null);
    }

    protected TestUser assistant(TestUser merchant, AssistantPermission... permissions) {
        return user(Role.ROLE_ASSISTANT, UserStatus.ACTIVE, merchant.storeId(),
                Arrays.stream(permissions).map(Enum::name).toList());
    }

    protected TestUser admin() {
        return user(Role.ROLE_ADMIN, UserStatus.ACTIVE, null, null);
    }

    protected TestUser superAdmin() {
        return user(Role.ROLE_SUPER_ADMIN, UserStatus.ACTIVE, null, null);
    }

    /** A signed access token with exactly the claims user-service issues (section 3.3). */
    protected String tokenFor(TestUser user) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("user-service")
                .audience(List.of("marketplace"))
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim("pid", user.publicId())
                .claim("role", user.role().name())
                .claim("status", "ACTIVE")
                .claim("tv", user.tokenVersion());
        if (user.storeId() != null) {
            claims.claim("storeId", user.storeId().toString());
        }
        if (user.perms() != null) {
            claims.claim("perms", user.perms());
        }
        return encode(claims.build());
    }

    protected String serviceToken() {
        Instant now = clock.instant();
        return encode(JwtClaimsSet.builder()
                .issuer("user-service")
                .audience(List.of("marketplace"))
                .subject("store-service")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .claim("role", Role.ROLE_SERVICE.name())
                .claim("svc", "store-service")
                .build());
    }

    protected static String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(Remote.KEY_ID).type("JWT").build();
        return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** user-service announces a status change (UserStatusChanged). */
    protected void statusChanged(TestUser user, UserStatus status) {
        userEventHandler.handle(new UserEvent(UUID.randomUUID(), "UserStatusChanged", clock.instant(), user.id(),
                user.publicId(), user.role().name(), status.name(), "ACTIVE", user.storeId(),
                user.tokenVersion() + 1, null));
    }

    // ============================================================================================== fixtures

    /** A store as store-service describes it. */
    protected record Shop(TestUser merchant, UUID storeId, String publicId, String name, String bankAccount) {

        /** The same store, acting as another of its users (an assistant, or the merchant with a newer token). */
        public Shop as(TestUser user) {
            return new Shop(user, storeId, publicId, name, bankAccount);
        }
    }

    /** A variant in the fake catalog. */
    protected record Variant(UUID id, String publicId, String productPublicId, Shop shop) {
    }

    protected Shop shop() {
        return shop(true, true);
    }

    protected Shop shop(boolean visible, boolean codEnabled) {
        TestUser merchant = merchant();
        Shop shop = new Shop(merchant, merchant.storeId(), "STR-2610-" + suffix(), "Store " + next(),
                "BAC-2610-" + suffix());
        Remote.stubStore(shop.storeId(), shop.publicId(), shop.name(), visible, codEnabled, shop.bankAccount());
        return shop;
    }

    protected Variant variant(Shop shop, String price, int available) {
        return variant(shop, price, "0.00", available, true, true);
    }

    protected Variant variant(Shop shop, String listPrice, String discount, int available, boolean codAllowed,
                              boolean purchasable) {
        UUID id = UUID.randomUUID();
        Variant variant = new Variant(id, "VAR-2610-" + suffix(), "ITM-2610-" + suffix(), shop);
        Remote.putVariant(id, quoteItemJson(variant, listPrice, discount, available, codAllowed, purchasable));
        return variant;
    }

    /** Changes what product-service reports for an existing variant. */
    protected void updateVariant(Variant variant, String listPrice, String discount, int available,
                                 boolean codAllowed, boolean purchasable) {
        Remote.putVariant(variant.id(), quoteItemJson(variant, listPrice, discount, available, codAllowed,
                purchasable));
    }

    private static String quoteItemJson(Variant v, String listPrice, String discount, int available,
                                        boolean codAllowed, boolean purchasable) {
        java.math.BigDecimal unit = new java.math.BigDecimal(listPrice).subtract(new java.math.BigDecimal(discount));
        return """
                {"variantId":"%s","variantPublicId":"%s","productId":"%s","productPublicId":"%s","storeId":"%s",
                 "storePublicId":"%s","storeName":"%s","productName":"Product %s","variantName":"Default",
                 "sku":"SKU-%s","attributes":{"Size":"M"},"imageUrl":null,"listPrice":%s,"discountAmount":%s,
                 "unitPrice":%s,"discountPublicId":null,"availableQuantity":%d,"codAllowed":%s,
                 "productActive":%s,"variantActive":true,"storeVisible":true,"purchasable":%s}"""
                .formatted(v.id(), v.publicId(), UUID.nameUUIDFromBytes(v.productPublicId().getBytes()),
                        v.productPublicId(), v.shop().storeId(), v.shop().publicId(), v.shop().name(),
                        v.publicId(), v.publicId(), listPrice, discount, unit.toPlainString(), available, codAllowed,
                        purchasable, purchasable);
    }

    // ================================================================================================= flows

    /** Adds to the cart. @return the cart item's public id */
    protected String addToCart(TestUser customer, Variant variant, int quantity) throws Exception {
        String body = perform(post("/api/customer/cart/items"), customer,
                "{\"variantPublicId\":\"%s\",\"quantity\":%d}".formatted(variant.publicId(), quantity))
                .getContentAsString();
        List<String> ids = JsonPath.read(body,
                "$.stores[*].lines[?(@.variantPublicId == '" + variant.publicId() + "')].cartItemPublicId");
        return ids.getFirst();
    }

    protected MockHttpServletResponse checkout(TestUser customer, List<String> cartItemIds,
                                               Map<Shop, String> methods, String idempotencyKey) throws Exception {
        String payments = methods.entrySet().stream()
                .map(e -> "{\"storePublicId\":\"%s\",\"method\":\"%s\"}".formatted(e.getKey().publicId(),
                        e.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
        String items = cartItemIds.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",", "[", "]"));
        String body = "{\"cartItemIds\":%s,\"addressPublicId\":\"%s\",\"payments\":%s}"
                .formatted(items, Remote.ADDRESS_ID, payments);
        MockHttpServletRequestBuilder request = post("/api/customer/checkout");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return perform(request, customer, body);
    }

    /** Cart + checkout of one variant from one store. @return the order's public id */
    protected String placeOrder(TestUser customer, Variant variant, int quantity, String method) throws Exception {
        String item = addToCart(customer, variant, quantity);
        MockHttpServletResponse response = checkout(customer, List.of(item), Map.of(variant.shop(), method),
                UUID.randomUUID().toString());
        if (response.getStatus() != 201) {
            throw new AssertionError("Checkout failed: " + response.getContentAsString());
        }
        return read(response.getContentAsString(), "$.results[0].orderPublicId");
    }

    /** The merchant quotes keeping every line, with the default courier and a 350.00 courier charge. */
    protected MockHttpServletResponse quote(Shop shop, String order, Variant variant, int quantity)
            throws Exception {
        return perform(post("/api/merchant/orders/" + order + "/quote"), shop.merchant(), """
                {"lines":[{"variantPublicId":"%s","quantity":%d}],"courierCode":"%s","courierCharge":350.00,
                 "otherCharges":[{"label":"Gift wrap","amount":100.00}],"quoteDiscount":50.00}"""
                .formatted(variant.publicId(), quantity, Remote.COURIER));
    }

    protected MockHttpServletResponse confirm(TestUser customer, String order) throws Exception {
        return perform(post("/api/customer/orders/" + order + "/confirm"), customer, null);
    }

    protected MockHttpServletResponse submitPayment(TestUser customer, String order, Shop shop, String reference)
            throws Exception {
        return perform(post("/api/customer/orders/" + order + "/payments")
                        .header("Idempotency-Key", UUID.randomUUID().toString()), customer, """
                {"referenceNumber":"%s","sourceBankCode":"COMB","sourceAccountNumber":"8001234567",
                 "storeBankAccountPublicId":"%s","attachmentKeys":["receipts/r1.jpg"]}"""
                .formatted(reference, shop.bankAccount()));
    }

    protected MockHttpServletResponse ship(Shop shop, String order) throws Exception {
        return perform(post("/api/merchant/orders/" + order + "/ship"), shop.merchant(),
                "{\"courierCode\":\"%s\",\"trackingNumber\":\"DX12345678\"}".formatted(Remote.COURIER));
    }

    /** Places, quotes and confirms a COD order: it is then {@code READY_TO_SHIP}. */
    protected String readyCodOrder(TestUser customer, Variant variant) throws Exception {
        String order = placeOrder(customer, variant, 1, "COD");
        expect(quote(variant.shop(), order, variant, 1), 200);
        expect(confirm(customer, order), 200);
        return order;
    }

    protected Order order(String publicId) {
        return orderRepository.findByPublicId(publicId).orElseThrow();
    }

    protected OrderStatus statusOf(String publicId) {
        return order(publicId).getStatus();
    }

    protected List<String> eventTypes(String orderPublicId) {
        return outboxRepository.findAllByAggregateIdOrderByIdAsc(order(orderPublicId).getId()).stream()
                .map(OutboxMessage::getEventType).toList();
    }

    protected String lastEvent(String orderPublicId, String type) {
        return outboxRepository.findAllByAggregateIdOrderByIdAsc(order(orderPublicId).getId()).stream()
                .filter(m -> m.getEventType().equals(type)).reduce((a, b) -> b).orElseThrow().getPayload();
    }

    /** Moves the clock just past the order's current deadline and runs the scheduler. */
    protected void expireDeadline(String orderPublicId) {
        Instant deadline = order(orderPublicId).getDeadlineAt();
        Duration until = Duration.between(clock.instant(), deadline).plusMinutes(1);
        clock.advance(until);
        runSchedulerUntilIdle();
    }

    /** The database is shared by all tests: drain every due batch, not only the first 100. */
    protected void runSchedulerUntilIdle() {
        while (scheduler.runOnce() > 0) {
            // next batch
        }
    }

    // ================================================================================================== HTTP

    protected MockHttpServletResponse perform(MockHttpServletRequestBuilder request, TestUser user, String body)
            throws Exception {
        if (user != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user));
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    protected MockHttpServletResponse getAs(TestUser user, String path) throws Exception {
        return perform(get(path), user, null);
    }

    protected static MockHttpServletResponse expect(MockHttpServletResponse response, int status) throws Exception {
        if (response.getStatus() != status) {
            throw new AssertionError("Expected " + status + " but got " + response.getStatus() + ": "
                    + response.getContentAsString());
        }
        return response;
    }

    protected static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }

    protected static void ok(MockHttpServletResponse response) throws Exception {
        expect(response, 200);
    }

    protected static org.springframework.test.web.servlet.ResultMatcher isOk() {
        return status().isOk();
    }
}
