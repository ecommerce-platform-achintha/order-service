package com.achintha.orderservice.security;

import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.ServiceUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Runs after the bearer-token filter and rejects revoked user tokens (section 3.3): a token whose {@code tv} is
 * older than the user's current token version (ban, unban, role or permission change, password change, assistant
 * removal), or a token of a user that no longer exists, gets 401 {@code TOKEN_REVOKED}.
 *
 * <p>The version comes from {@link SecurityStateCache} (fed by {@code user-events}); on a miss, or when the token is
 * newer than the cache (an event not consumed yet), user-service is asked. If user-service cannot be reached the
 * request is let through: staleness is then bounded by the 10-minute token lifetime (section 3.3).
 *
 * <p>The resolved state is exposed as the request attribute {@link #STATE_ATTRIBUTE} (e.g. to refuse a banned
 * customer's checkout). Service tokens ({@code ROLE_SERVICE}) carry no user state and are not checked.
 *
 * <p>Not a Spring bean on purpose: it is added to the security chain only (a bean would also be registered as a
 * servlet filter and run twice).
 */
@Slf4j
public class SecurityStateFilter extends OncePerRequestFilter {

    public static final String STATE_ATTRIBUTE = "com.achintha.orderservice.security.SecurityState";

    private final SecurityStateCache cache;
    private final HandlerExceptionResolver resolver;

    public SecurityStateFilter(SecurityStateCache cache, HandlerExceptionResolver resolver) {
        this.cache = cache;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth && isUserToken(jwtAuth.getToken())) {
            ApiException rejection = check(jwtAuth.getToken(), request);
            if (rejection != null) {
                SecurityContextHolder.clearContext();
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
                resolver.resolveException(request, response, null, rejection);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isUserToken(Jwt jwt) {
        return !Role.ROLE_SERVICE.name().equals(jwt.getClaimAsString(TokenClaims.ROLE));
    }

    private ApiException check(Jwt jwt, HttpServletRequest request) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return revoked();
        }
        if (!(jwt.getClaim(TokenClaims.TOKEN_VERSION) instanceof Number tokenVersion)) {
            return revoked();
        }
        try {
            Optional<SecurityState> state = cache.resolve(userId);
            if (state.isPresent() && tokenVersion.longValue() > state.get().tokenVersion()) {
                // The token is newer than what we know: an event is still on its way
                state = cache.refresh(userId);
            }
            if (state.isEmpty() || tokenVersion.longValue() < state.get().tokenVersion()) {
                return revoked();
            }
            request.setAttribute(STATE_ATTRIBUTE, state.get());
            return null;
        } catch (ServiceUnavailableException e) {
            log.warn("Security state of user {} unavailable; accepting the token until it expires", userId);
            return null;
        }
    }

    private static ApiException revoked() {
        return ApiException.unauthorized(ErrorCode.TOKEN_REVOKED, "This token has been revoked; sign in again");
    }
}
