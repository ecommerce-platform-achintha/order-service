package com.achintha.orderservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validation of the RS256 access tokens issued by user-service (section 3.3): keys come from its JWKS endpoint.
 *
 * @param jwksUri user-service's {@code /.well-known/jwks.json}
 */
@Validated
@ConfigurationProperties("security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank String jwksUri,
        @PositiveOrZero long clockSkewSeconds) {
}
