package com.achintha.orderservice.integration;

import static com.achintha.orderservice.security.GatewayUserHeaderFilter.USER_ID_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Checks what lands on the order-events topic, using an embedded Kafka broker (no real broker needed), a real
 * PostgreSQL (Testcontainers) and product-service stubbed by WireMock.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(topics = OrderEventsIntegrationTest.TOPIC, partitions = 3)
class OrderEventsIntegrationTest {

    static final String TOPIC = "order-events";

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

    /** X-User-Id the "Gateway" sets on writes; forwarded to product-service on inventory calls. */
    private static final String GATEWAY_USER = "gateway-user-42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, String> consumer;

    private final UUID userId = UUID.randomUUID();
    private final UUID laptop = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);

        stubProduct(laptop, "Gaming Laptop", "999.99");
        stubProduct(book, "Laptop Repair Handbook", "25.50");
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void publishesCreatedThenConfirmedForSuccessfulOrder() throws Exception {
        stubInventoryUpdate(laptop, 200);
        stubInventoryUpdate(book, 200);

        // The client still gets the same synchronous response
        String orderId = createOrder()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(2076.48))
                .andReturn().getResponse().getContentAsString();
        orderId = JsonPath.read(orderId, "$.id");

        List<ConsumerRecord<String, String>> records = awaitEvents(orderId, 2);

        assertEvent(records.get(0), orderId, "OrderCreated", "PENDING");
        assertEvent(records.get(1), orderId, "OrderConfirmed", "CONFIRMED");
        assertSamePartition(records);
    }

    @Test
    void publishesCancelledOnCancelAndNothingForRejectedCancel() throws Exception {
        stubInventoryUpdate(laptop, 200);
        stubInventoryUpdate(book, 200);
        String orderId = JsonPath.read(createOrder().andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(cancelRequest("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // Second cancel is a 409: no status change, so no event
        mockMvc.perform(cancelRequest("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict());

        List<ConsumerRecord<String, String>> records = awaitEvents(orderId, 3);

        assertThat(records).extracting(r -> JsonPath.<String>read(r.value(), "$.eventType"))
                .containsExactly("OrderCreated", "OrderConfirmed", "OrderCancelled");
        assertEvent(records.get(2), orderId, "OrderCancelled", "CANCELLED");
        assertSamePartition(records);
        // Give a stray fourth event (from the rejected cancel) a chance to show up
        assertThat(pollEvents(orderId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void publishesFailedWhenInventoryDecrementFails() throws Exception {
        stubInventoryUpdate(laptop, 200);
        stubInventoryUpdate(book, 500);

        String orderId = createOrder()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        orderId = JsonPath.read(orderId, "$.id");

        List<ConsumerRecord<String, String>> records = awaitEvents(orderId, 2);

        assertEvent(records.get(0), orderId, "OrderCreated", "PENDING");
        assertEvent(records.get(1), orderId, "OrderFailed", "FAILED");
    }

    /** Full payload check: key, discriminator, status, amounts, and items reduced to productId + quantity. */
    private void assertEvent(ConsumerRecord<String, String> record, String orderId, String eventType,
                             String status) {
        assertThat(record.key()).isEqualTo(orderId);
        DocumentContext json = JsonPath.parse(record.value());
        assertThat(json.read("$.eventType", String.class)).isEqualTo(eventType);
        assertThat(json.read("$.orderId", String.class)).isEqualTo(orderId);
        assertThat(json.read("$.userId", String.class)).isEqualTo(userId.toString());
        assertThat(json.read("$.status", String.class)).isEqualTo(status);
        assertThat(json.read("$.totalAmount", Double.class)).isEqualTo(2076.48);
        assertThat(Instant.parse(json.read("$.timestamp", String.class)))
                .isBetween(Instant.now().minusSeconds(60), Instant.now());

        List<Map<String, Object>> items = json.read("$.items");
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item).containsOnlyKeys("productId", "quantity"));
        assertThat(items).extracting(item -> item.get("productId"), item -> item.get("quantity"))
                .containsExactlyInAnyOrder(
                        tuple(laptop.toString(), 2),
                        tuple(book.toString(), 3));
        assertThat(record.headers().lastHeader("__TypeId__")).as("no Java type header").isNull();
    }

    private static void assertSamePartition(List<ConsumerRecord<String, String>> records) {
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());
    }

    /** Events for this order, in topic order, waiting up to 10s for {@code expected} of them. */
    private List<ConsumerRecord<String, String>> awaitEvents(String orderId, int expected) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(10);
        while (found.size() < expected && Instant.now().isBefore(deadline)) {
            found.addAll(pollEvents(orderId, Duration.ofMillis(500)));
        }
        assertThat(found).as("events for order %s", orderId).hasSize(expected);
        return found;
    }

    private List<ConsumerRecord<String, String>> pollEvents(String orderId, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        consumer.poll(timeout).forEach(record -> {
            if (orderId.equals(record.key())) {
                found.add(record);
            }
        });
        return found;
    }

    private ResultActions createOrder() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                .header(USER_ID_HEADER, GATEWAY_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId": "%s", "items": [
                            {"productId": "%s", "quantity": 2},
                            {"productId": "%s", "quantity": 3}
                        ]}""".formatted(userId, laptop, book)));
    }

    private static void stubProduct(UUID id, String name, String price) {
        productService.stubFor(get(urlEqualTo("/api/products/" + id)).willReturn(okJson("""
                {"id": "%s", "name": "%s", "price": %s, "stock": {"quantityAvailable": 10, "reservedQuantity": 0}}"""
                .formatted(id, name, price))));
    }

    private static void stubInventoryUpdate(UUID id, int status) {
        productService.stubFor(patch(urlEqualTo("/api/products/" + id + "/inventory")).withHeader(USER_ID_HEADER, equalTo(GATEWAY_USER))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": \"%s\", \"quantityAvailable\": 0}".formatted(id))));
    }

    /** Writes need the header the Gateway sets after validating the JWT (see GatewayUserHeaderFilter). */
    private static MockHttpServletRequestBuilder cancelRequest(String urlTemplate, Object orderId) {
        return MockMvcRequestBuilders.patch(urlTemplate, orderId).header(USER_ID_HEADER, GATEWAY_USER);
    }
}
