package com.achintha.orderservice.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Size and lifetime of the user security-state cache. Entries are refreshed by {@code user-events}; the TTL only
 * bounds memory and staleness when an event is missed (access tokens live 10 minutes).
 */
@Validated
@ConfigurationProperties("security.state-cache")
public record SecurityStateProperties(@Positive long maxSize, @NotNull Duration ttl) {
}
