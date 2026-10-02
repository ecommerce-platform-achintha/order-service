package com.achintha.orderservice.security;

import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Account-status rules on top of the token (section 3.2), using the real status from {@link SecurityStateCache}
 * (fed by {@code user-events}, so a ban takes effect without waiting for the token to expire):
 * <ul>
 *   <li>a banned customer can view orders but cannot check out, confirm a quote or pay;</li>
 *   <li>a banned merchant (after the grace period) keeps read access to its history but cannot act on orders.
 *       {@code BAN_GRACE} works as usual (D4).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccountGuard {

    private final SecurityStateCache cache;

    public void requireCustomerCanBuy(Actor actor) {
        UserStatus status = status(actor);
        if (status == UserStatus.BANNED) {
            throw ApiException.forbidden(ErrorCode.CUSTOMER_BANNED,
                    "Your account is banned: you cannot place or pay for orders");
        }
        if (status != null && status != UserStatus.ACTIVE) {
            throw ApiException.forbidden(ErrorCode.CUSTOMER_NOT_ACTIVE, "Your account is not active");
        }
    }

    public void requireMerchantCanAct(Actor actor) {
        if (status(actor) == UserStatus.BANNED) {
            throw ApiException.forbidden(ErrorCode.ACCESS_DENIED,
                    "This account is banned: order history is read-only");
        }
    }

    private UserStatus status(Actor actor) {
        if (actor.id() == null) {
            return null;
        }
        Optional<SecurityState> state = cache.cached(actor.id());
        if (state.isPresent() && state.get().status() != null) {
            return state.get().status();
        }
        try {
            return cache.resolve(actor.id()).map(SecurityState::status)
                    .orElse(statusClaim(actor));
        } catch (ApiException e) {
            // user-service unreachable: fall back to the token's status claim (at most 10 minutes old)
            return statusClaim(actor);
        }
    }

    private static UserStatus statusClaim(Actor actor) {
        try {
            return actor.status() == null ? null : UserStatus.valueOf(actor.status());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
