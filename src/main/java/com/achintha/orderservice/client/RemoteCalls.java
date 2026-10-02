package com.achintha.orderservice.client;

import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resilient execution of Feign calls to the other services, the pattern order-service has always used for
 * product-service: every call runs as Retry(CircuitBreaker(TimeLimiter(feign call))), so each attempt has its own
 * timeout and is counted by the breaker. Settings come from the named instances under {@code resilience4j.*}
 * ({@code productService}, {@code storeService}, {@code userService}).
 *
 * <p>Business answers ({@link ApiException}: 404, 409, ...) pass through unchanged and are ignored by the breaker
 * and the retries. Anything else (down, timeout, 5xx, breaker open) ends in a 503, never in fabricated data.
 */
@Component
public class RemoteCalls {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final TimeLimiterRegistry timeLimiters;
    // TimeLimiter needs a future to time out; virtual threads keep blocking Feign calls cheap
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RemoteCalls(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                       TimeLimiterRegistry timeLimiters) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.timeLimiters = timeLimiters;
    }

    /**
     * @param instance  the Resilience4j instance name (one per target service)
     * @param service   the target's name, for the error message
     * @param action    what was attempted, for the error message
     * @param feignCall runs on a virtual thread: pass tokens explicitly, the request context is not available there
     */
    public <T> T call(String instance, String service, String action, Supplier<T> feignCall) {
        TimeLimiter timeLimiter = timeLimiters.timeLimiter(instance);
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(feignCall, executor));
        Callable<T> guarded = CircuitBreaker.decorateCallable(circuitBreakers.circuitBreaker(instance), timed);
        Callable<T> retried = Retry.decorateCallable(retries.retry(instance), guarded);
        try {
            return retried.call();
        } catch (Exception e) {
            throw fallback(service, action, unwrap(e));
        }
    }

    /** The stable {@code code} of another service's error body, if it has one. */
    public static Optional<String> errorCode(FeignException e) {
        try {
            byte[] body = e.content();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            JsonNode code = JSON.readTree(new String(body, StandardCharsets.UTF_8)).get("code");
            return code == null || code.isNull() ? Optional.empty() : Optional.of(code.asString());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static RuntimeException fallback(String service, String action, Throwable failure) {
        if (failure instanceof ApiException api) {
            return api;
        }
        String reason = switch (failure) {
            case CallNotPermittedException ignored -> "circuit breaker is open";
            case TimeoutException ignored -> "request timed out";
            default -> "request failed";
        };
        return new ServiceUnavailableException(
                service + " is unavailable (" + reason + "): could not " + action + ". Try again later.", failure);
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
