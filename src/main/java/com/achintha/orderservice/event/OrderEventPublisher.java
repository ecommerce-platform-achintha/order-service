package com.achintha.orderservice.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends {@link OrderEvent}s to Kafka, keyed by orderId so one order's events share a partition and stay in order.
 *
 * <p>Runs after the order's DB transaction commits (or right away when there is none), so an event is only sent for
 * a change that is actually stored. Postgres is the source of truth and the event is a notification: a publish
 * failure is logged and never fails the order request.
 */
@Slf4j
@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, OrderEvent> kafkaTemplate,
                               @Value("${order-service.kafka.order-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void publish(OrderEvent event) {
        String key = event.orderId().toString();
        try {
            // Returns once the record is buffered; blocks at most spring.kafka.producer max.block.ms if the broker
            // is unreachable. Delivery itself is confirmed (or not) asynchronously.
            kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    logFailure(event, ex);
                } else {
                    log.debug("Published {} for order {} to {}-{}@{}", event.eventType().wireName(), key, topic,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException ex) {
            logFailure(event, ex);
        }
    }

    private void logFailure(OrderEvent event, Throwable ex) {
        log.error("Could not publish {} for order {} (status {}) to topic {}; the order itself is saved",
                event.eventType().wireName(), event.orderId(), event.status(), topic, ex);
    }
}
