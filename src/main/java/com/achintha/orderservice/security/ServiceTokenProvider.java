package com.achintha.orderservice.security;

import com.achintha.orderservice.client.UserServiceClient.ServiceTokenResponse;
import com.achintha.orderservice.client.UserServiceGateway;
import com.achintha.orderservice.config.InternalAuthProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * A {@code ROLE_SERVICE} token for calls made without a user context (client credentials against user-service's
 * {@code POST /internal/auth/service-token}). The token is cached and reused until shortly before it expires
 * ({@code security.internal.refresh-before-expiry}). Tokens are never logged.
 */
@Component
public class ServiceTokenProvider {

    private final UserServiceGateway gateway;
    private final InternalAuthProperties properties;
    private final Clock clock;

    private String token;
    private Instant renewAt = Instant.MIN;

    public ServiceTokenProvider(UserServiceGateway gateway, InternalAuthProperties properties, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(renewAt)) {
            ServiceTokenResponse response = gateway.serviceToken(properties.clientId(), properties.clientSecret());
            token = response.accessToken();
            Instant expiresAt = response.expiresAt() != null ? response.expiresAt()
                    : clock.instant().plusSeconds(response.expiresIn());
            renewAt = expiresAt.minus(properties.refreshBeforeExpiry());
        }
        return token;
    }

    /** Drops the cached token (e.g. after a 401), so the next call fetches a new one. */
    public synchronized void invalidate() {
        token = null;
        renewAt = Instant.MIN;
    }
}
