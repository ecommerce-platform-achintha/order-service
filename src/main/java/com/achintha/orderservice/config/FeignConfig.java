package com.achintha.orderservice.config;

import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    /**
     * Apache HttpClient 5 silently retries some I/O failures on its own, which would multiply the attempts set in
     * {@code resilience4j.retry} (and could resend a non-idempotent inventory decrement). Resilience4j is the only
     * retry layer.
     */
    @Bean
    HttpClientBuilderCustomizer disableHttpClientRetries() {
        return builder -> builder.disableAutomaticRetries();
    }
}
