package com.achintha.orderservice.product;

import com.achintha.orderservice.exception.InsufficientStockException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.exception.ProductServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Resilient access to product-service: every call runs as Retry(CircuitBreaker(TimeLimiter(feign call))), so each
 * attempt has its own timeout and is counted by the breaker. All settings come from the named instances under
 * {@code resilience4j.*} in application.yml.
 *
 * <p>Business answers from product-service (404 unknown product, 409 not enough stock) pass through as
 * {@link NotFoundException} / {@link InsufficientStockException}; the yml config tells the breaker and the retries
 * to ignore them. Anything else (down, timeout, 5xx, breaker open) ends in {@link ProductServiceUnavailableException}.
 */
@Component
public class ProductServiceGateway {

    /** One breaker for both calls: they hit the same service, so either failing says product-service is unhealthy. */
    public static final String CIRCUIT_BREAKER = "productService";
    public static final String LOOKUP_RETRY = "productLookup";
    /** Retries only when the request never reached product-service, since a decrement is not idempotent. */
    public static final String INVENTORY_RETRY = "inventoryUpdate";
    public static final String TIME_LIMITER = "productService";

    private final ProductClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry lookupRetry;
    private final Retry inventoryRetry;
    private final TimeLimiter timeLimiter;
    // TimeLimiter needs a future to time out; virtual threads keep blocking Feign calls cheap
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ProductServiceGateway(ProductClient client, CircuitBreakerRegistry circuitBreakerRegistry,
                                 RetryRegistry retryRegistry, TimeLimiterRegistry timeLimiterRegistry) {
        this.client = client;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.lookupRetry = retryRegistry.retry(LOOKUP_RETRY);
        this.inventoryRetry = retryRegistry.retry(INVENTORY_RETRY);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(TIME_LIMITER);
    }

    /** Current name, price and available stock of a product. */
    public ProductResponse getProduct(UUID productId) {
        return call(lookupRetry, "look up product " + productId, () -> {
            try {
                return client.getProduct(productId);
            } catch (FeignException.NotFound e) {
                throw new NotFoundException("Product not found: " + productId);
            }
        });
    }

    /**
     * Adds {@code delta} (negative to take stock out) to the product's available quantity, on behalf of the user in
     * {@code userIdHeader} (the X-User-Id the Gateway set on the incoming request). Passed explicitly because the
     * Feign call runs on another thread, where the incoming request isn't available.
     */
    public InventoryResponse adjustInventory(UUID productId, int delta, String userIdHeader) {
        return call(inventoryRetry, "adjust inventory of product " + productId, () -> {
            try {
                return client.adjustInventory(productId, userIdHeader, new AdjustInventoryRequest(delta));
            } catch (FeignException.NotFound e) {
                throw new NotFoundException("Product not found: " + productId);
            } catch (FeignException.Conflict e) {
                throw new InsufficientStockException("Not enough stock left for product " + productId);
            }
        });
    }

    private <T> T call(Retry retry, String action, Supplier<T> feignCall) {
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(feignCall, executor));
        Callable<T> guarded = CircuitBreaker.decorateCallable(circuitBreaker, timed);
        Callable<T> retried = Retry.decorateCallable(retry, guarded);
        try {
            return retried.call();
        } catch (Exception e) {
            throw fallback(action, unwrap(e));
        }
    }

    /** Rethrows business errors as they are; turns everything else into a clear 503, never fabricated data. */
    private static RuntimeException fallback(String action, Throwable failure) {
        if (failure instanceof NotFoundException || failure instanceof InsufficientStockException) {
            return (RuntimeException) failure;
        }
        String reason = switch (failure) {
            case CallNotPermittedException ignored -> "circuit breaker is open";
            case TimeoutException ignored -> "request timed out";
            default -> "request failed";
        };
        return new ProductServiceUnavailableException(
                "Product service is unavailable (" + reason + "): could not " + action + ". Try again later.",
                failure);
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
