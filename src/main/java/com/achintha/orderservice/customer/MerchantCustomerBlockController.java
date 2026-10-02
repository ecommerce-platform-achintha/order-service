package com.achintha.orderservice.customer;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.customer.CustomerDtos.BlockRequest;
import com.achintha.orderservice.customer.CustomerDtos.BlockResponse;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/customer-blocks")
@PreAuthorize("hasAuthority('PERM_CUSTOMER_BLOCK')")
@RequiredArgsConstructor
@Tag(name = "Merchant: customer blocks")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MerchantCustomerBlockController {

    private final CustomerBlockService blockService;

    @GetMapping
    @Operation(summary = "Customers blocked by my store (CUSTOMER_BLOCK)")
    public PageResponse<BlockResponse> list(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return blockService.list(Actor.from(jwt), Pagination.of(page, size, sort,
                Map.of("createdAt", "createdAt"), Pagination.MAX_SIZE));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Block a customer who ordered from my store (CUSTOMER_BLOCK)")
    public BlockResponse block(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BlockRequest request) {
        return blockService.block(Actor.from(jwt), request);
    }

    @DeleteMapping("/{customerPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unblock a customer (CUSTOMER_BLOCK)")
    public void unblock(@AuthenticationPrincipal Jwt jwt,
                        @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String customerPublicId,
                        @RequestParam(required = false) @Size(max = Validation.REASON_MAX) String reason) {
        blockService.unblock(Actor.from(jwt), customerPublicId, reason);
    }
}
