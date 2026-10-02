package com.achintha.orderservice.order;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.order.OrderDtos.DeliveryFailedRequest;
import com.achintha.orderservice.order.OrderDtos.PaymentRejectRequest;
import com.achintha.orderservice.order.OrderDtos.QuoteRequest;
import com.achintha.orderservice.order.OrderDtos.RejectRequest;
import com.achintha.orderservice.order.OrderDtos.ShipRequest;
import com.achintha.orderservice.payment.PaymentService;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store-side order endpoints: the merchant, or an assistant holding the permission named on each route. Always
 * scoped by the JWT {@code storeId}; another store's order is a 404.
 */
@RestController
@RequestMapping("/api/merchant/orders")
@RequiredArgsConstructor
@Tag(name = "Merchant: orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MerchantOrderController {

    private static final String ID = Validation.PUBLIC_ID_REGEX;

    private final OrderService orderService;
    private final PaymentService paymentService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW')")
    @Operation(summary = "The store's orders (ORDER_VIEW)")
    public PageResponse<OrderResponse.Summary> list(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(required = false) List<OrderStatus> status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "placedAt,desc") String sort) {
        return orderService.storeOrders(Actor.from(jwt), status,
                Pagination.of(page, size, sort, OrderService.SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW')")
    @Operation(summary = "One order with the customer's score and COD refusal count (ORDER_VIEW)")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.storeOrder(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/reject")
    @PreAuthorize("hasAuthority('PERM_ORDER_QUOTE')")
    @Operation(summary = "Reject before quoting, with a reason code; no penalty (ORDER_QUOTE)")
    public OrderResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId,
                                @Valid @RequestBody RejectRequest request) {
        return orderService.reject(Actor.from(jwt), publicId, request);
    }

    @PostMapping("/{publicId}/quote")
    @PreAuthorize("hasAuthority('PERM_ORDER_QUOTE')")
    @Operation(summary = "Quote or re-quote: reduce/remove lines, courier charge, other charges, discount "
            + "(ORDER_QUOTE)")
    public OrderResponse quote(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId,
                               @Valid @RequestBody QuoteRequest request) {
        return orderService.quote(Actor.from(jwt), publicId, request);
    }

    @PostMapping("/{publicId}/payment/verify")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_VERIFY')")
    @Operation(summary = "Confirm the bank transfer arrived (PAYMENT_VERIFY)")
    public OrderResponse verifyPayment(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable @Pattern(regexp = ID) String publicId) {
        return paymentService.verify(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/payment/reject")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_VERIFY')")
    @Operation(summary = "Reject the reported transfer with a reason (PAYMENT_VERIFY)")
    public OrderResponse rejectPayment(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable @Pattern(regexp = ID) String publicId,
                                       @Valid @RequestBody PaymentRejectRequest request) {
        return paymentService.reject(Actor.from(jwt), publicId, request.reason());
    }

    @PostMapping("/{publicId}/ship")
    @PreAuthorize("hasAuthority('PERM_ORDER_SHIP')")
    @Operation(summary = "Ship with one of the store's couriers and a tracking number (ORDER_SHIP)")
    public OrderResponse ship(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId,
                              @Valid @RequestBody ShipRequest request) {
        return orderService.ship(Actor.from(jwt), publicId, request);
    }

    @PostMapping("/{publicId}/delivery-failed")
    @PreAuthorize("hasAuthority('PERM_ORDER_SHIP')")
    @Operation(summary = "COD refused or undeliverable (ORDER_SHIP)")
    public OrderResponse deliveryFailed(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable @Pattern(regexp = ID) String publicId,
                                        @Valid @RequestBody DeliveryFailedRequest request) {
        return orderService.deliveryFailed(Actor.from(jwt), publicId, request);
    }
}
