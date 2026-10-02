package com.achintha.orderservice.platform;

import com.achintha.orderservice.client.StoreServiceClient.Setting;
import com.achintha.orderservice.client.StoreServiceGateway;
import com.achintha.orderservice.exception.ServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform settings from store-service ({@code GET /internal/settings}), cached for {@code app.platform.settings-ttl}
 * (60 s) and dropped at once on {@code SettingsChanged}. If store-service cannot be reached the last known values are
 * used (logged); with no values at all the caller gets a 503: nothing is invented.
 */
@Slf4j
@Component
public class PlatformSettings {

    private final StoreServiceGateway storeService;
    private final Clock clock;
    private final Duration ttl;

    private volatile Map<String, String> values;
    private volatile Instant loadedAt = Instant.MIN;

    public PlatformSettings(StoreServiceGateway storeService, Clock clock,
                            @Value("${app.platform.settings-ttl:PT60S}") Duration ttl) {
        this.storeService = storeService;
        this.clock = clock;
        this.ttl = ttl;
    }

    public int intValue(String key) {
        String value = values().get(key);
        if (value == null) {
            throw new ServiceUnavailableException("Platform setting " + key + " is not available", null);
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Platform setting " + key + " is not an integer");
        }
    }

    /** Drops the cache ({@code SettingsChanged}): the next read asks store-service. */
    public void invalidate() {
        loadedAt = Instant.MIN;
    }

    private Map<String, String> values() {
        Map<String, String> current = values;
        if (current != null && clock.instant().isBefore(loadedAt.plus(ttl))) {
            return current;
        }
        synchronized (this) {
            if (values != null && clock.instant().isBefore(loadedAt.plus(ttl))) {
                return values;
            }
            try {
                Map<String, String> fresh = new HashMap<>();
                for (Setting setting : storeService.settings()) {
                    fresh.put(setting.key(), setting.value());
                }
                values = Map.copyOf(fresh);
                loadedAt = clock.instant();
                return values;
            } catch (ServiceUnavailableException e) {
                if (values != null) {
                    log.warn("Platform settings could not be refreshed; using the values loaded at {}", loadedAt);
                    return values;
                }
                throw e;
            }
        }
    }
}
