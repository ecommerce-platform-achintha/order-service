package com.achintha.orderservice.order;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.idempotency.IdempotencyService;
import com.achintha.orderservice.order.OrderDtos.ObjectionRequest;
import com.achintha.orderservice.order.OrderService.BankAccountView;
import com.achintha.orderservice.payment.PaymentDtos.PaymentRequest;
import com.achintha.orderservice.payment.PaymentService;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer-side order endpoints. Always scoped by the JWT subject; another customer's order is a 404. */
@RestController
@RequestMapping("/api/customer/orders")
@PreAuthorize("hasAuthority('ROLE_CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Customer: orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class OrderController {

    private static final String ID = Validation.PUBLIC_ID_REGEX;

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    @GetMapping
    @Operation(summary = "My orders (paged; sort by placedAt, updatedAt, grandTotal, status, deadlineAt)")
    public PageResponse<OrderResponse.Summary> list(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(required = false) List<OrderStatus> status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "placedAt,desc") String sort) {
        return orderService.customerOrders(Actor.from(jwt), status,
                Pagination.of(page, size, sort, OrderService.SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.customerOrder(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/cancel")
    @Operation(summary = "Cancel free of penalty (only while AWAITING_MERCHANT)")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.cancel(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/confirm")
    @Operation(summary = "Accept the merchant's quote (COD: ready to ship; bank transfer: awaiting payment)")
    public OrderResponse confirm(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.confirmQuote(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/decline")
    @Operation(summary = "Decline the quote (customer score -1)")
    public OrderResponse decline(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.declineQuote(Actor.from(jwt), publicId);
    }

    @GetMapping("/{publicId}/bank-accounts")
    @Operation(summary = "The store's active bank accounts to pay into (while a transfer is expected)")
    public ResponseEntity<List<BankAccountView>> bankAccounts(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable @Pattern(regexp = ID) String publicId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orderService.bankAccounts(Actor.from(jwt), publicId));
    }

    @PostMapping("/{publicId}/payments")
    @Operation(summary = "Report a bank transfer (requires Idempotency-Key)",
            description = "A reference already used with the same bank (any store) is refused with "
                    + "409 PAYMENT_REJECTED and flagged to admins.")
    public ResponseEntity<?> submitPayment(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable @Pattern(regexp = ID) String publicId,
                                           @RequestHeader(value = IdempotencyService.HEADER, required = false)
                                           String idempotencyKey,
                                           @Valid @RequestBody PaymentRequest request, HttpServletRequest http) {
        Actor actor = Actor.from(jwt);
        return idempotency.execute(actor.id(), idempotencyKey, http.getRequestURI(), request,
                () -> ResponseEntity.ok(paymentService.submit(actor, publicId, request)));
    }

    @PostMapping("/{publicId}/received")
    @Operation(summary = "Confirm the parcel arrived (completes the order)")
    public OrderResponse received(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.markReceived(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/cod-objection")
    @Operation(summary = "Object to a COD refusal (within cod.objection-window-days); an admin decides")
    public OrderResponse object(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId,
                                @Valid @RequestBody ObjectionRequest request) {
        return orderService.objectToCodRefusal(Actor.from(jwt), publicId, request);
    }
}
