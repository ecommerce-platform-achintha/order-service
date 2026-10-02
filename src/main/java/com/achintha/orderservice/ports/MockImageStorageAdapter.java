package com.achintha.orderservice.ports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Accepts every key and returns a placeholder URL; always succeeds. */
@Component
@ConditionalOnProperty(name = "app.ports.image-storage", havingValue = "mock", matchIfMissing = true)
public class MockImageStorageAdapter implements ImageStoragePort {

    private final String baseUrl;

    public MockImageStorageAdapter(@Value("${app.ports.image-base-url:https://images.example.invalid/}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public boolean exists(String key) {
        return true;
    }

    @Override
    public String urlFor(String key) {
        return key == null ? null : baseUrl + key;
    }
}
