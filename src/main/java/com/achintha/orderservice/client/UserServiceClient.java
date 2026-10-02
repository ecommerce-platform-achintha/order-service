package com.achintha.orderservice.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * user-service, resolved through Eureka by its service id (no hardcoded URL). Call it through
 * {@link UserServiceGateway}, which adds the circuit breaker, retry and timeout.
 *
 * <p>Internal endpoints take a service token. The address and profile reads are made on behalf of the customer and
 * forward the customer's own token (section 3.3), so user-service scopes them to that user.
 */
@FeignClient(name = "user-service", contextId = "userServiceClient")
public interface UserServiceClient {

    @PostMapping("/internal/auth/service-token")
    ServiceTokenResponse serviceToken(@RequestBody ServiceTokenRequest request);

    @GetMapping("/internal/users/{id}/security-state")
    SecurityStateResponse securityState(@PathVariable("id") UUID userId,
                                        @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerServiceToken);

    /** The caller's own address ({@code 404} for someone else's). */
    @GetMapping("/api/users/me/addresses/{publicId}")
    AddressResponse myAddress(@PathVariable("publicId") String addressPublicId,
                              @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerUserToken);

    /** The caller's own profile (contact snapshot). */
    @GetMapping("/api/users/me")
    ProfileResponse me(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearerUserToken);

    record ServiceTokenRequest(String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "ServiceTokenRequest[clientId=" + clientId + ", clientSecret=****]";
        }
    }

    record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {

        @Override
        public String toString() {
            return "ServiceTokenResponse[accessToken=****, expiresAt=" + expiresAt + "]";
        }
    }

    /** What user-service reports for a user: the real status (including {@code BAN_GRACE}) and token version. */
    record SecurityStateResponse(UUID userId, String publicId, String role, String status, String assistantStatus,
                                 long tv, UUID storeId, List<String> perms) {
    }

    record AddressResponse(String publicId, String recipientName, String phone, String line1, String line2,
                           String city, String district, String postalCode, String country) {

        @Override
        public String toString() {
            return "AddressResponse[publicId=" + publicId + "]";
        }
    }

    record ProfileResponse(String publicId, String email, String firstName, String lastName, String phone,
                           String role, String status) {

        @Override
        public String toString() {
            return "ProfileResponse[publicId=" + publicId + ", status=" + status + "]";
        }
    }
}
