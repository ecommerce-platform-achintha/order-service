package com.achintha.orderservice.product;

import com.achintha.orderservice.product.ProductDtos.AdjustRequest;
import com.achintha.orderservice.product.ProductDtos.QuoteRequest;
import com.achintha.orderservice.product.ProductDtos.QuoteResponse;
import com.achintha.orderservice.product.ProductDtos.ReservationResponse;
import com.achintha.orderservice.product.ProductDtos.ReserveRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * product-service's internal price/stock quote and reservation API ({@code ROLE_SERVICE}), resolved through Eureka by
 * its service id (no hardcoded URL). Call it through {@link ProductServiceGateway}, which adds the circuit breaker,
 * retry and timeout. Every call is idempotent (a read, or a reservation call keyed by {@code orderRef}).
 */
@FeignClient(name = "product-service", contextId = "productClient")
public interface ProductClient {

    @PostMapping("/internal/variants/quote")
    QuoteResponse quote(@RequestBody QuoteRequest request, @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @PostMapping("/internal/reservations")
    ReservationResponse reserve(@RequestBody ReserveRequest request,
                                @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @PutMapping("/internal/reservations/{orderRef}")
    ReservationResponse adjust(@PathVariable("orderRef") String orderRef, @RequestBody AdjustRequest request,
                               @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @PostMapping("/internal/reservations/{orderRef}/commit")
    ReservationResponse commit(@PathVariable("orderRef") String orderRef,
                               @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @PostMapping("/internal/reservations/{orderRef}/release")
    ReservationResponse release(@PathVariable("orderRef") String orderRef,
                                @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);
}
