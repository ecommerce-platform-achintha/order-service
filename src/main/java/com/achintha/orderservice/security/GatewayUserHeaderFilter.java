package com.achintha.orderservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects write requests that did not come through the API Gateway, i.e. that lack the
 * {@value #USER_ID_HEADER} header the Gateway sets after validating the JWT.
 *
 * <p>This is a presence check only, not authentication: the header value is not verified, so any
 * caller that can reach this service directly can forge it. The real guarantee has to come from
 * network isolation (only the Gateway can reach this service).
 *
 * <p>Every method except GET, HEAD and OPTIONS is guarded, regardless of path (today: POST /api/orders and
 * PATCH /api/orders/{id}/cancel). Matching on the method rather than a list of paths means new write endpoints
 * are covered automatically and path tricks (trailing slashes, encoded segments) cannot slip past it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayUserHeaderFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"Authentication required\"}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return READ_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
