package com.achintha.orderservice.product;

import com.achintha.orderservice.client.RemoteCalls;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.InsufficientStockException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.product.ProductDtos.AdjustRequest;
import com.achintha.orderservice.product.ProductDtos.LineRequest;
import com.achintha.orderservice.product.ProductDtos.QuoteItem;
import com.achintha.orderservice.product.ProductDtos.QuoteRequest;
import com.achintha.orderservice.product.ProductDtos.QuoteResponse;
import com.achintha.orderservice.product.ProductDtos.ReservationResponse;
import com.achintha.orderservice.product.ProductDtos.ReserveRequest;
import com.achintha.orderservice.security.ServiceTokenProvider;
import feign.FeignException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resilient access to product-service ({@link RemoteCalls}, instance {@code productService}, the same
 * breaker/retry/time-limiter pattern as before) with a service token.
 *
 * <p>Business answers pass through as typed errors: {@code INSUFFICIENT_STOCK} as {@link InsufficientStockException},
 * unknown or unsellable variants as {@code VARIANT_NOT_AVAILABLE}. Reservation calls are idempotent per
 * {@code orderRef}, so retrying them is always safe.
 */
@Component
public class ProductServiceGateway {

    public static final String INSTANCE = "productService";
    private static final String SERVICE = "Product service";

    private final ProductClient client;
    private final RemoteCalls remote;
    private final ServiceTokenProvider serviceTokens;

    public ProductServiceGateway(ProductClient client, RemoteCalls remote, ServiceTokenProvider serviceTokens) {
        this.client = client;
        this.remote = remote;
        this.serviceTokens = serviceTokens;
    }

    /** Live price, discount, stock and availability, keyed by variant UUID. Unknown variants are left out. */
    public Map<UUID, QuoteItem> quote(Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        QuoteResponse response = call("price the cart",
                bearer -> client.quote(new QuoteRequest(List.copyOf(variantIds), null), bearer));
        return response.items().stream().collect(Collectors.toMap(QuoteItem::variantId, Function.identity()));
    }

    /** One variant by its public id; 404 {@code VARIANT_NOT_AVAILABLE} if it does not exist. */
    public QuoteItem quoteByPublicId(String variantPublicId) {
        QuoteResponse response = call("look up the variant",
                bearer -> client.quote(new QuoteRequest(null, List.of(variantPublicId)), bearer));
        return response.items().stream().filter(i -> variantPublicId.equals(i.variantPublicId())).findFirst()
                .orElseThrow(
                () -> new NotFoundException(ErrorCode.VARIANT_NOT_AVAILABLE, "Variant not found"));
    }

    /** Holds stock for an order: all lines or none. */
    public ReservationResponse reserve(String orderRef, Instant expiresAt, List<LineRequest> lines) {
        return call("reserve stock", bearer -> client.reserve(new ReserveRequest(orderRef, expiresAt, lines), bearer));
    }

    /** Keeps only {@code lines} (reductions only) and moves the expiry. */
    public ReservationResponse adjust(String orderRef, List<LineRequest> lines, Instant expiresAt) {
        return call("adjust the stock hold", bearer -> client.adjust(orderRef, new AdjustRequest(lines, expiresAt), bearer));
    }

    public ReservationResponse commit(String orderRef) {
        return call("commit the stock hold", bearer -> client.commit(orderRef, bearer));
    }

    /** Returns held units to stock. An unknown hold counts as released (nothing to give back). */
    public void release(String orderRef) {
        try {
            call("release the stock hold", bearer -> client.release(orderRef, bearer));
        } catch (NotFoundException e) {
            // no hold was ever placed for this order
        }
    }

    private <T> T call(String action, Function<String, T> feignCall) {
        return remote.call(INSTANCE, SERVICE, action, () -> {
            try {
                return feignCall.apply("Bearer " + serviceTokens.token());
            } catch (FeignException.Unauthorized e) {
                serviceTokens.invalidate();
                throw e;
            } catch (FeignException.NotFound e) {
                throw new NotFoundException(ErrorCode.VARIANT_NOT_AVAILABLE, "Not found in product-service");
            } catch (FeignException.Conflict | FeignException.BadRequest e) {
                throw translate(e);
            }
        });
    }

    private static RuntimeException translate(FeignException e) {
        String code = RemoteCalls.errorCode(e).orElse("");
        return switch (code) {
            case "INSUFFICIENT_STOCK" -> new InsufficientStockException("Not enough stock for one of the items");
            case "VARIANT_NOT_AVAILABLE" -> new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE,
                    "One of the items is no longer available");
            case "RESERVATION_RELEASED", "RESERVATION_COMMITTED", "RESERVATION_CONFLICT",
                 "RESERVATION_ADJUST_NOT_ALLOWED" -> new ConflictException(ErrorCode.INVALID_ORDER_STATE,
                    "The order's stock hold cannot be changed (" + code + ")");
            // A 400 we caused (e.g. expiry not in the future) is not product-service's fault: surface as a conflict
            default -> new ConflictException(ErrorCode.INVALID_ORDER_STATE,
                    "product-service refused the request (" + (code.isEmpty() ? e.status() : code) + ")");
        };
    }
}
