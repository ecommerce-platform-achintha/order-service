package com.achintha.orderservice.complaint;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.complaint.ComplaintDtos.ComplaintResponse;
import com.achintha.orderservice.complaint.ComplaintDtos.RespondRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Complaints against the caller's store (under the order routes, so the gateway sends them here). */
@RestController
@RequestMapping("/api/merchant/orders/complaints")
@RequiredArgsConstructor
@Tag(name = "Merchant: complaints")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MerchantComplaintController {

    private final ComplaintService complaintService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW')")
    @Operation(summary = "Complaints against my store (ORDER_VIEW)")
    public PageResponse<ComplaintResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = ComplaintControllers.SORT_DEFAULT)
                                                String sort) {
        return complaintService.storeList(Actor.from(jwt),
                Pagination.of(page, size, sort, ComplaintControllers.SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW')")
    public ComplaintResponse get(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId) {
        return complaintService.storeGet(Actor.from(jwt), publicId);
    }

    @PostMapping("/{publicId}/response")
    @PreAuthorize("hasAuthority('PERM_ORDER_QUOTE')")
    @Operation(summary = "Respond once to a complaint (ORDER_QUOTE)")
    public ComplaintResponse respond(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId,
                                     @Valid @RequestBody RespondRequest request) {
        return complaintService.respond(Actor.from(jwt), publicId, request.response());
    }
}
