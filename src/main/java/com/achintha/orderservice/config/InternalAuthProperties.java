package com.achintha.orderservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * This service's client credentials for user-service's {@code POST /internal/auth/service-token}. The secret comes
 * from the Config Server ({@code SERVICE_CLIENT_SECRET_ORDER}); see {@link InternalClientSecretGuard}.
 *
 * @param refreshBeforeExpiry a cached service token is renewed this long before it expires
 */
@Validated
@ConfigurationProperties("security.internal")
public record InternalAuthProperties(
        @NotBlank String clientId,
        String clientSecret,
        @NotNull Duration refreshBeforeExpiry) {

    @Override
    public String toString() {
        return "InternalAuthProperties[clientId=" + clientId + ", clientSecret=****, refreshBeforeExpiry="
                + refreshBeforeExpiry + "]";
    }
}
