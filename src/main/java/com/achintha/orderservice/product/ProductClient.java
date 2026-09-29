package com.achintha.orderservice.product;

import com.achintha.orderservice.security.GatewayUserHeaderFilter;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * product-service, resolved through Eureka by its service id (no hardcoded URL). Call it through
 * {@link ProductServiceGateway}, which adds the circuit breaker, retry and timeout.
 */
@FeignClient(name = "product-service", path = "/api/products")
public interface ProductClient {

    @GetMapping("/{id}")
    ProductResponse getProduct(@PathVariable("id") UUID id);

    /**
     * Adds {@code delta} to the available quantity; product-service answers 409 if it would go negative. It is a
     * write, so product-service's gateway guard requires {@code X-User-Id} (401 without it).
     */
    @PatchMapping("/{id}/inventory")
    InventoryResponse adjustInventory(@PathVariable("id") UUID id,
                                      @RequestHeader(GatewayUserHeaderFilter.USER_ID_HEADER) String userIdHeader,
                                      @RequestBody AdjustInventoryRequest request);
}
