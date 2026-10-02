package com.achintha.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.outbox.OutboxMessage;
import com.achintha.orderservice.outbox.OutboxRelay;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * The transactional outbox end to end (section 9): business code only writes outbox rows; the relay publishes them
 * to {@code order-events} keyed by the order UUID, at least once, and marks them published. Also: a
 * {@code user-events} message on Kafka reaches the consumer, which de-duplicates by {@code eventId}.
 */
class OrderEventsIntegrationTest extends IntegrationTest {

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void relayPublishesOutboxRowsToOrderEventsKeyedByOrderId() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "COD");
        UUID orderId = order(order).getId();
        assertThat(outboxRepository.findAllByAggregateIdOrderByIdAsc(orderId))
                .allSatisfy(m -> assertThat(m.getPublishedAt()).isNull());

        try (Consumer<String, String> consumer = consumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "order-events");
            while (relay.publishPending() > 0) {
                // drain everything pending (other tests' rows too)
            }
            List<ConsumerRecord<String, String>> mine = new ArrayList<>();
            long until = System.currentTimeMillis() + 15_000;
            while (mine.isEmpty() && System.currentTimeMillis() < until) {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2)).forEach(r -> {
                    if (orderId.toString().equals(r.key())) {
                        mine.add(r);
                    }
                });
            }
            assertThat(mine).hasSize(1);
            String payload = mine.getFirst().value();
            assertThat((String) JsonPath.read(payload, "$.eventType")).isEqualTo("OrderPlaced");
            assertThat((String) JsonPath.read(payload, "$.orderPublicId")).isEqualTo(order);
        }
        assertThat(outboxRepository.findAllByAggregateIdOrderByIdAsc(orderId))
                .extracting(OutboxMessage::getPublishedAt).doesNotContainNull();
    }

    @Test
    void aCustomerBanOnUserEventsIsConsumedOnceEvenIfDeliveredTwice() throws Exception {
        TestUser customer = customer();
        Shop shop = shop();
        Variant v = variant(shop, "100.00", 5);
        String order = placeOrder(customer, v, 1, "COD");
        String message = """
                {"eventId":"%s","eventType":"UserStatusChanged","occurredAt":"2026-10-01T00:00:00Z","userId":"%s",
                 "publicId":"%s","role":"ROLE_CUSTOMER","status":"BANNED","previousStatus":"ACTIVE","tokenVersion":2}"""
                .formatted(UUID.randomUUID(), customer.id(), customer.publicId());
        kafkaTemplate.send("user-events", customer.id().toString(), message).get();
        kafkaTemplate.send("user-events", customer.id().toString(), message).get();

        long until = System.currentTimeMillis() + 20_000;
        while (statusOf(order) != OrderStatus.CANCELLED_BY_SYSTEM && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CANCELLED_BY_SYSTEM);
        Thread.sleep(1000); // give the duplicate time to arrive
        assertThat(eventTypes(order).stream().filter("OrderCancelled"::equals)).hasSize(1);
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "order-events-test-" + UUID.randomUUID(),
                false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
