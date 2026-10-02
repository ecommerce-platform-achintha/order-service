package com.achintha.orderservice.security;

import com.achintha.orderservice.client.UserServiceClient.SecurityStateResponse;
import com.achintha.orderservice.client.UserServiceGateway;
import com.achintha.orderservice.config.SecurityStateProperties;
import com.achintha.orderservice.exception.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code user_security_state} cache of section 3.3: user id to token version and status, in memory (Caffeine).
 *
 * <ul>
 *   <li>Fed by {@code user-events} ({@code UserSecurityChanged}, {@code UserStatusChanged}) through
 *       {@link #apply}; a version never goes backwards, so out-of-order events are harmless.</li>
 *   <li>On a miss, {@link #resolve} asks user-service ({@code GET /internal/users/{id}/security-state}) with a
 *       service token.</li>
 * </ul>
 */
@Component
public class SecurityStateCache {

    private final Cache<UUID, SecurityState> cache;
    private final UserServiceGateway gateway;
    private final ServiceTokenProvider serviceTokens;

    public SecurityStateCache(SecurityStateProperties properties, UserServiceGateway gateway,
                              ServiceTokenProvider serviceTokens) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.maxSize())
                .expireAfterWrite(properties.ttl())
                .build();
        this.gateway = gateway;
        this.serviceTokens = serviceTokens;
    }

    /** The cached state, if any (no remote call). */
    public Optional<SecurityState> cached(UUID userId) {
        return Optional.ofNullable(cache.getIfPresent(userId));
    }

    /**
     * The cached state, or user-service's answer on a miss (then cached).
     *
     * @return empty if user-service does not know the user
     * @throws com.achintha.orderservice.exception.ServiceUnavailableException if user-service cannot be reached
     */
    public Optional<SecurityState> resolve(UUID userId) {
        SecurityState cached = cache.getIfPresent(userId);
        return cached != null ? Optional.of(cached) : refresh(userId);
    }

    /** Asks user-service, replacing whatever is cached. */
    public Optional<SecurityState> refresh(UUID userId) {
        SecurityStateResponse response;
        try {
            response = gateway.securityState(userId, serviceTokens::token, serviceTokens::invalidate);
        } catch (NotFoundException e) {
            cache.invalidate(userId);
            return Optional.empty();
        }
        SecurityState state = new SecurityState(response.tv(), parseStatus(response.status()));
        cache.put(userId, state);
        return Optional.of(state);
    }

    /**
     * Applies a {@code user-events} update. A lower token version than the cached one is ignored; a missing status
     * keeps the cached one.
     */
    public void apply(UUID userId, Long tokenVersion, UserStatus status) {
        cache.asMap().compute(userId, (id, current) -> {
            if (current == null) {
                return tokenVersion == null ? null : new SecurityState(tokenVersion, status);
            }
            long version = tokenVersion == null ? current.tokenVersion()
                    : Math.max(tokenVersion, current.tokenVersion());
            if (tokenVersion != null && tokenVersion < current.tokenVersion()) {
                return current;
            }
            return new SecurityState(version, status != null ? status : current.status());
        });
    }

    public void evict(UUID userId) {
        cache.invalidate(userId);
    }

    private static UserStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
