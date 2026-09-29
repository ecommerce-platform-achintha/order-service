package com.achintha.orderservice.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.product.ProductServiceGateway;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Full order flow against a real PostgreSQL (Testcontainers), with product-service replaced by WireMock. The
 * Feign client still resolves "product-service" through Spring Cloud LoadBalancer; the simple discovery client
 * points that service id at WireMock instead of Eureka.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        // Smaller window and shorter timeouts than production so the resilience paths run quickly
        "resilience4j.circuitbreaker.instances.productService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.productService.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.productService.wait-duration-in-open-state=60s",
        "resilience4j.circuitbreaker.instances.productService.automatic-transition-from-open-to-half-open-enabled=false",
        "resilience4j.retry.instances.productLookup.wait-duration=50ms",
        "resilience4j.retry.instances.inventoryUpdate.wait-duration=50ms",
        "resilience4j.timelimiter.instances.productService.timeout-duration=1s"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @RegisterExtension
    static WireMockExtension productService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void productServiceLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                productService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void resetState() {
        // WireMockExtension resets stubs itself; the breaker is a singleton shared by all tests
        breaker().reset();
        orderRepository.deleteAll();
    }

    @Test
    void createsConfirmedOrderWithPriceSnapshotsThenCancelsIt() throws Exception {
        UUID laptop = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        stubProduct(laptop, "Gaming Laptop", "999.99", 5);
        stubProduct(book, "Laptop Repair Handbook", "25.50", 10);
        stubInventoryUpdate(laptop);
        stubInventoryUpdate(book);

        String body = createOrder("""
                {"userId": "%s", "items": [
                    {"productId": "%s", "quantity": 2},
                    {"productId": "%s", "quantity": 3}
                ]}""".formatted(userId, laptop, book))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.totalAmount").value(2076.48))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].productName").value("Gaming Laptop"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(999.99))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[1].productName").value("Laptop Repair Handbook"))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(body, "$.id");

        // Stock was decremented by exactly the ordered quantities
        productService.verify(1, patchRequestedFor(urlEqualTo("/api/products/" + laptop + "/inventory"))
                .withRequestBody(equalToJson("{\"delta\": -2}")));
        productService.verify(1, patchRequestedFor(urlEqualTo("/api/products/" + book + "/inventory"))
                .withRequestBody(equalToJson("{\"delta\": -3}")));

        // Snapshots survive later price changes in product-service
        stubProduct(laptop, "Gaming Laptop v2", "1299.00", 5);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].productName").value("Gaming Laptop"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(999.99));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/orders").param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId))
                .andExpect(jsonPath("$.content[0].items", hasSize(2)));

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("CANCELLED to CANCELLED")));
    }

    @Test
    void rejectsOrderWhenStockIsInsufficient() throws Exception {
        UUID product = UUID.randomUUID();
        stubProduct(product, "Gaming Laptop", "999.99", 1);

        createOrder(singleItemOrder(product, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("requested 5, available 1")))
                .andExpect(jsonPath("$.path").value("/api/orders"));

        productService.verify(0, patchRequestedFor(anyUrl()));
        assertThat(orderRepository.count()).isZero();
        // A business rejection is not a product-service failure
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void returns503WhenProductServiceIsUnreachable() throws Exception {
        UUID product = UUID.randomUUID();
        productService.stubFor(get(urlEqualTo("/api/products/" + product))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        createOrder(singleItemOrder(product, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(containsString("Product service is unavailable")));

        // Fallback ran after the retry's 2 attempts, both counted by the breaker; nothing was saved
        productService.verify(2, getRequestedFor(urlEqualTo("/api/products/" + product)));
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenFailsFast() throws Exception {
        UUID product = UUID.randomUUID();
        productService.stubFor(get(urlEqualTo("/api/products/" + product))
                .willReturn(aResponse().withStatus(500)));

        // 2 orders x 2 attempts = 4 failed calls = the whole test window at 100% failure
        for (int i = 0; i < 2; i++) {
            createOrder(singleItemOrder(product, 1)).andExpect(status().isServiceUnavailable());
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        productService.verify(4, getRequestedFor(anyUrl()));

        createOrder(singleItemOrder(product, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("circuit breaker is open")));
        // Rejected without calling product-service
        productService.verify(4, getRequestedFor(anyUrl()));
    }

    @Test
    void slowProductServiceTimesOutWith503InsteadOfHanging() throws Exception {
        UUID product = UUID.randomUUID();
        productService.stubFor(get(urlEqualTo("/api/products/" + product))
                .willReturn(productJson(product, "Slow Laptop", "10.00", 5).withFixedDelay(2_500)));

        long start = System.nanoTime();
        createOrder(singleItemOrder(product, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("timed out")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 2 attempts x 1s timeout (+ small retry wait), well under 2 x 2.5s of waiting for the slow responses
        assertThat(elapsedMillis).isLessThan(3_500);
    }

    @Test
    void marksOrderFailedWhenInventoryDecrementFails() throws Exception {
        UUID product = UUID.randomUUID();
        stubProduct(product, "Gaming Laptop", "999.99", 5);
        productService.stubFor(patch(urlEqualTo("/api/products/" + product + "/inventory"))
                .willReturn(aResponse().withStatus(500)));

        String body = createOrder(singleItemOrder(product, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();

        // A 500 may mean the decrement happened, so it is not retried
        productService.verify(1, patchRequestedFor(anyUrl()));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/orders/{id}", (String) JsonPath.read(body, "$.id")))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void returns404ForUnknownProductAndOrder() throws Exception {
        UUID product = UUID.randomUUID();
        productService.stubFor(get(urlEqualTo("/api/products/" + product)).willReturn(aResponse().withStatus(404)));

        createOrder(singleItemOrder(product, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found: " + product));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void rejectsInvalidRequestWith400() throws Exception {
        createOrder("""
                {"items": [{"productId": "%s", "quantity": 0}]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        containsInAnyOrder("userId", "items[0].quantity")));
        productService.verify(0, getRequestedFor(anyUrl()));
    }

    private CircuitBreaker breaker() {
        return circuitBreakerRegistry.circuitBreaker(ProductServiceGateway.CIRCUIT_BREAKER);
    }

    private ResultActions createOrder(String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String singleItemOrder(UUID productId, int quantity) {
        return """
                {"userId": "%s", "items": [{"productId": "%s", "quantity": %d}]}"""
                .formatted(userId, productId, quantity);
    }

    private static void stubProduct(UUID id, String name, String price, int available) {
        productService.stubFor(get(urlEqualTo("/api/products/" + id))
                .willReturn(productJson(id, name, price, available)));
    }

    /** Same shape as product-service's ProductResponse, including fields order-service ignores. */
    private static ResponseDefinitionBuilder productJson(
            UUID id, String name, String price, int available) {
        return okJson("""
                {"id": "%s", "name": "%s", "description": "desc", "price": %s, "sku": "SKU-1",
                 "category": {"id": "%s", "name": "Electronics"},
                 "stock": {"quantityAvailable": %d, "reservedQuantity": 0},
                 "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z"}"""
                .formatted(id, name, price, UUID.randomUUID(), available));
    }

    private static void stubInventoryUpdate(UUID id) {
        productService.stubFor(patch(urlEqualTo("/api/products/" + id + "/inventory"))
                .willReturn(okJson("""
                        {"productId": "%s", "quantityAvailable": 0, "reservedQuantity": 0}""".formatted(id))));
    }
}
