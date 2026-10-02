package com.achintha.orderservice.order;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.customer.CustomerAdminService;
import com.achintha.orderservice.customer.CustomerDtos.ObjectionDecisionRequest;
import com.achintha.orderservice.customer.CustomerDtos.ObjectionResponse;
import com.achintha.orderservice.customer.ObjectionStatus;
import com.achintha.orderservice.order.OrderDtos.ResolveRequest;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
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

@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin: orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminOrderController {

    private static final String ID = Validation.PUBLIC_ID_REGEX;

    private final OrderService orderService;
    private final CustomerAdminService customerAdminService;

    @GetMapping
    @Operation(summary = "Orders; needsResolution=true lists open orders of banned merchants")
    public PageResponse<OrderResponse.Summary> list(@RequestParam(required = false) List<OrderStatus> status,
                                                    @RequestParam(defaultValue = "false") boolean needsResolution,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "placedAt,desc") String sort) {
        return orderService.adminOrders(status, needsResolution,
                Pagination.of(page, size, sort, OrderService.SORTABLE, Pagination.MAX_ADMIN_SIZE));
    }

    @GetMapping("/cod-objections")
    @Operation(summary = "Customers' objections to COD refusals")
    public PageResponse<ObjectionResponse> objections(@RequestParam(required = false) ObjectionStatus status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return customerAdminService.objections(status, Pagination.of(page, size, sort,
                Map.of("createdAt", "createdAt", "status", "status"), Pagination.MAX_ADMIN_SIZE));
    }

    @GetMapping("/{publicId}")
    public OrderResponse get(@PathVariable @Pattern(regexp = ID) String publicId) {
        return orderService.adminOrder(publicId);
    }

    @PostMapping("/{publicId}/resolve")
    @Operation(summary = "FORCE_COMPLETE or CANCEL (CLOSED_BY_ADMIN) an open order, with a reason")
    public OrderResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = ID) String publicId,
                                 @Valid @RequestBody ResolveRequest request) {
        return orderService.resolve(Actor.from(jwt), publicId, request);
    }

    @PostMapping("/{publicId}/cod-objection/decision")
    @Operation(summary = "UPHELD reverses the COD refusal (and lifts a suspension it caused); or DISMISSED")
    public ObjectionResponse decideObjection(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable @Pattern(regexp = ID) String publicId,
                                             @Valid @RequestBody ObjectionDecisionRequest request) {
        return customerAdminService.decide(Actor.from(jwt), publicId, request);
    }
}
