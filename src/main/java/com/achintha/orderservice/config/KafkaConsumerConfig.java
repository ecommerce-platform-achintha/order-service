package com.achintha.orderservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer error handling: a failing message is retried with exponential back-off (1 s doubling, up to about a
 * minute in total), then logged and skipped so one bad message cannot block the partition. Handlers are idempotent,
 * so a retry after a partial failure is safe.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(16_000L);
        backOff.setMaxElapsedTime(60_000L);
        return new DefaultErrorHandler((record, exception) -> log.error(
                "Giving up on message {}-{}@{} after retries: {}", record.topic(), record.partition(),
                record.offset(), exception.getMessage()), backOff);
    }
}
