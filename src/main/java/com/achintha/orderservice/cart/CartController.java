package com.achintha.orderservice.cart;

import com.achintha.orderservice.cart.CartDtos.AddItemRequest;
import com.achintha.orderservice.cart.CartDtos.CartResponse;
import com.achintha.orderservice.cart.CartDtos.UpdateItemRequest;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/cart")
@PreAuthorize("hasAuthority('ROLE_CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Customer: cart")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "The cart, re-priced live, grouped by store, with availability per line")
    public CartResponse get(@AuthenticationPrincipal Jwt jwt) {
        return cartService.get(Actor.from(jwt));
    }

    @PostMapping("/items")
    @Operation(summary = "Add a variant (or raise its quantity); lines from several stores are allowed")
    public CartResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddItemRequest request) {
        return cartService.add(Actor.from(jwt), request);
    }

    @PutMapping("/items/{cartItemId}")
    @Operation(summary = "Set a line's quantity")
    public CartResponse update(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String cartItemId,
                               @Valid @RequestBody UpdateItemRequest request) {
        return cartService.update(Actor.from(jwt), cartItemId, request.quantity());
    }

    @DeleteMapping("/items/{cartItemId}")
    @Operation(summary = "Remove a line")
    public CartResponse remove(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String cartItemId) {
        return cartService.remove(Actor.from(jwt), cartItemId);
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    public CartResponse clear(@AuthenticationPrincipal Jwt jwt) {
        return cartService.clear(Actor.from(jwt));
    }
}
