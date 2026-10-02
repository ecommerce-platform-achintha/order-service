package com.achintha.orderservice.complaint;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.complaint.ComplaintDtos.ComplaintResponse;
import com.achintha.orderservice.complaint.ComplaintDtos.DecideRequest;
import com.achintha.orderservice.config.OpenApiConfig;
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

@RestController
@RequestMapping("/api/admin/complaints")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin: complaints")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminComplaintController {

    private final ComplaintService complaintService;

    @GetMapping
    public PageResponse<ComplaintResponse> list(@RequestParam(required = false) List<ComplaintStatus> status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = ComplaintControllers.SORT_DEFAULT)
                                                String sort) {
        return complaintService.adminList(status,
                Pagination.of(page, size, sort, ComplaintControllers.SORTABLE, Pagination.MAX_ADMIN_SIZE));
    }

    @GetMapping("/{publicId}")
    public ComplaintResponse get(@PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId) {
        return complaintService.adminGet(publicId);
    }

    @PostMapping("/{publicId}/decision")
    @Operation(summary = "UPHELD (merchant penalty via store-service) or DISMISSED")
    public ComplaintResponse decide(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId,
                                    @Valid @RequestBody DecideRequest request) {
        return complaintService.decide(Actor.from(jwt), publicId, request);
    }
}
