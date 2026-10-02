package com.achintha.orderservice.security;

/**
 * What this service knows about a user's security state.
 *
 * @param tokenVersion tokens with a lower {@code tv} are rejected
 * @param status       the real status (including {@code BAN_GRACE}); {@code null} if not known yet
 */
public record SecurityState(long tokenVersion, UserStatus status) {
}
