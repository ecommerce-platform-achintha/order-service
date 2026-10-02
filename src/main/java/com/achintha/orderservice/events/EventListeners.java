package com.achintha.orderservice.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumers (group {@code order-service}). Payloads are plain JSON; a message that is not valid JSON is logged
 * and skipped (retrying cannot fix it). Handler failures are retried by the container's error handler (see
 * {@code KafkaConsumerConfig}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventListeners {

    private final ObjectMapper objectMapper;
    private final UserEventHandler userEventHandler;
    private final StoreEventHandler storeEventHandler;

    @KafkaListener(id = "order-service-user-events", topics = "${app.kafka.topics.user-events}", idIsGroup = false)
    public void onUserEvent(ConsumerRecord<String, String> record) {
        UserEvent event = parse(record, UserEvent.class);
        if (event != null) {
            userEventHandler.handle(event);
        }
    }

    @KafkaListener(id = "order-service-store-events", topics = "${app.kafka.topics.store-events}", idIsGroup = false)
    public void onStoreEvent(ConsumerRecord<String, String> record) {
        StoreEvent event = parse(record, StoreEvent.class);
        if (event != null) {
            storeEventHandler.handle(event);
        }
    }

    private <T> T parse(ConsumerRecord<String, String> record, Class<T> type) {
        try {
            return objectMapper.readValue(record.value(), type);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Skipping unreadable message on {} (partition {}, offset {})", record.topic(),
                    record.partition(), record.offset());
            return null;
        }
    }
}
