package com.achintha.orderservice.payment;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.payment.PaymentDtos.FlagReviewRequest;
import com.achintha.orderservice.payment.PaymentDtos.FlaggedReferenceResponse;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/admin/flagged-references")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin: flagged payment references")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminFlaggedReferenceController {

    private final PaymentService paymentService;

    @GetMapping
    @Operation(summary = "Review queue of duplicate bank references")
    public PageResponse<FlaggedReferenceResponse> list(@RequestParam(required = false) FlagStatus status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return paymentService.flagged(status, Pagination.of(page, size, sort,
                Map.of("createdAt", "createdAt", "status", "status"), Pagination.MAX_ADMIN_SIZE));
    }

    @GetMapping("/{publicId}")
    public FlaggedReferenceResponse get(@PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId) {
        return paymentService.flaggedOne(publicId);
    }

    @PostMapping("/{publicId}/review")
    @Operation(summary = "Mark REVIEWED, or ESCALATED to a customer/merchant ban (done in user-service)")
    public FlaggedReferenceResponse review(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId,
                                           @Valid @RequestBody FlagReviewRequest request) {
        return paymentService.review(Actor.from(jwt), publicId, request);
    }
}
