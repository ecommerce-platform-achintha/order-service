package com.achintha.orderservice.outbox;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka in insertion order and marks them published (at-least-once: a crash between the
 * send and the commit re-sends the row, and consumers de-duplicate by {@code eventId}).
 *
 * <p>Each send waits for the broker's acknowledgement. On the first failure the batch stops, so an aggregate's events are
 * never published out of order; the row keeps its attempt count and last error and is retried on the next run.
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate, Clock clock,
                       @Value("${app.outbox.batch-size:100}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:PT1S}")
    @SchedulerLock(name = "order-service.outboxRelay", lockAtMostFor = "PT2M")
    public void scheduledRun() {
        publishPending();
    }

    /** @return the number of messages published */
    public int publishPending() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxMessage> batch = outboxRepository.lockUnpublished(batchSize);
            int count = 0;
            for (OutboxMessage message : batch) {
                if (!send(message)) {
                    break;
                }
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private boolean send(OutboxMessage message) {
        try {
            kafkaTemplate.send(message.getTopic(), message.getMessageKey(), message.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            message.setPublishedAt(clock.instant());
            message.setAttempts(message.getAttempts() + 1);
            message.setLastError(null);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(message, e);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(message, e);
            return false;
        }
    }

    private void recordFailure(OutboxMessage message, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(error.length() > 1000 ? error.substring(0, 1000) : error);
        log.warn("Could not publish outbox event {} ({}) to {} (attempt {}); will retry", message.getEventId(),
                message.getEventType(), message.getTopic(), message.getAttempts());
    }
}
