package com.achintha.orderservice.product;

import feign.FeignException;
import java.net.ConnectException;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;

/**
 * Retry predicate for non-idempotent calls (the inventory decrement): only retry when the request provably never
 * reached product-service, i.e. the connection was refused or the load balancer had no instance (503). A timeout
 * or a dropped connection is not retried, because the first decrement may already have been applied.
 *
 * <p>Referenced by name from {@code resilience4j.retry.instances.inventoryUpdate.retry-exception-predicate}.
 */
public class ConnectFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
            if (t instanceof FeignException fe && fe.status() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
