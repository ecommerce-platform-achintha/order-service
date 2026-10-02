package com.achintha.orderservice.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated caller, derived only from the validated JWT (never from request bodies, params or headers).
 *
 * @param storeId     merchant/assistant store from the token; scopes every merchant-side query (BOLA)
 * @param status      the {@code status} claim as issued (a merchant in {@code BAN_GRACE} still sees {@code ACTIVE})
 * @param permissions assistant permissions ({@code perms}); a merchant implicitly holds all
 * @param token       the raw access token, forwarded only on calls made on the user's behalf (section 3.3)
 */
public record Actor(UUID id, String publicId, Role role, UUID storeId, String status, Set<String> permissions,
                    String token) {

    /** Actor id used for scheduler- and event-driven actions in audit rows and events. */
    public static final String SYSTEM = "SYSTEM";

    public static Actor from(Jwt jwt) {
        String storeId = jwt.getClaimAsString(TokenClaims.STORE_ID);
        Role role = Role.valueOf(jwt.getClaimAsString(TokenClaims.ROLE));
        UUID id = role == Role.ROLE_SERVICE ? null : UUID.fromString(jwt.getSubject());
        String publicId = role == Role.ROLE_SERVICE ? jwt.getSubject() : jwt.getClaimAsString(TokenClaims.PUBLIC_ID);
        List<String> perms = jwt.getClaimAsStringList(TokenClaims.PERMISSIONS);
        return new Actor(id, publicId, role, storeId == null ? null : UUID.fromString(storeId),
                jwt.getClaimAsString(TokenClaims.STATUS),
                perms == null ? Set.of() : perms.stream().collect(Collectors.toUnmodifiableSet()),
                jwt.getTokenValue());
    }

    /** The caller of the current request. */
    public static Actor current() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
            return from(auth.getToken());
        }
        throw new IllegalStateException("No authenticated caller");
    }

    public static Actor system() {
        return new Actor(null, SYSTEM, Role.ROLE_SERVICE, null, null, Set.of(), null);
    }

    public boolean isSystem() {
        return SYSTEM.equals(publicId);
    }

    public boolean isCustomer() {
        return role == Role.ROLE_CUSTOMER;
    }

    public boolean isMerchantSide() {
        return role == Role.ROLE_MERCHANT || role == Role.ROLE_ASSISTANT;
    }

    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN || role == Role.ROLE_SUPER_ADMIN;
    }

    /** A merchant holds every permission; an assistant only those in its token. */
    public boolean hasPermission(AssistantPermission permission) {
        return role == Role.ROLE_MERCHANT || (role == Role.ROLE_ASSISTANT && permissions.contains(permission.name()));
    }

    @Override
    public String toString() {
        return "Actor[" + role + " " + publicId + "]";
    }
}
