package com.achintha.orderservice.checkout;

import com.achintha.orderservice.checkout.CheckoutDtos.CheckoutRequest;
import com.achintha.orderservice.checkout.CheckoutDtos.CheckoutResponse;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.idempotency.IdempotencyService;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAuthority('ROLE_CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Customer: checkout")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final IdempotencyService idempotency;

    @PostMapping("/api/customer/checkout")
    @Operation(summary = "Place one order per store from the selected cart lines (requires Idempotency-Key)",
            description = "201 when at least one store's order was placed, 422 when none was. Each store group "
                    + "succeeds or fails on its own; failed lines stay in the cart. Error codes per group: "
                    + "STORE_NOT_ACCEPTING_ORDERS, CUSTOMER_BLOCKED_BY_STORE, COD_NOT_AVAILABLE_FOR_STORE, "
                    + "COD_NOT_ALLOWED_FOR_PRODUCT, COD_SUSPENDED, OPEN_ORDER_LIMIT_REACHED, VARIANT_NOT_AVAILABLE, "
                    + "INSUFFICIENT_STOCK. Whole-request errors: CUSTOMER_BANNED, ADDRESS_NOT_FOUND, "
                    + "CART_ITEM_NOT_FOUND, PAYMENT_METHOD_MISSING.")
    public ResponseEntity<?> checkout(@AuthenticationPrincipal Jwt jwt,
                                      @RequestHeader(value = IdempotencyService.HEADER, required = false)
                                      String idempotencyKey,
                                      @Valid @RequestBody CheckoutRequest request, HttpServletRequest http) {
        Actor actor = Actor.from(jwt);
        return idempotency.execute(actor.id(), idempotencyKey, http.getRequestURI(), request, () -> {
            CheckoutResponse response = checkoutService.checkout(actor, request);
            return ResponseEntity.status(response.anyPlaced() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(response);
        });
    }
}
