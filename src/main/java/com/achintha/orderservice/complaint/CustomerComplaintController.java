package com.achintha.orderservice.complaint;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.Pagination;
import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.complaint.ComplaintDtos.ComplaintResponse;
import com.achintha.orderservice.complaint.ComplaintDtos.CreateComplaintRequest;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/complaints")
@PreAuthorize("hasAuthority('ROLE_CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Customer: complaints")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CustomerComplaintController {

    private final ComplaintService complaintService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "File a complaint about one of my orders (from READY_TO_SHIP until complaints.window-days "
            + "after completion)")
    public ComplaintResponse create(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody CreateComplaintRequest request) {
        return complaintService.create(Actor.from(jwt), request);
    }

    @GetMapping
    public PageResponse<ComplaintResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = ComplaintControllers.SORT_DEFAULT)
                                                String sort) {
        return complaintService.customerList(Actor.from(jwt),
                Pagination.of(page, size, sort, ComplaintControllers.SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    public ComplaintResponse get(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId) {
        return complaintService.customerGet(Actor.from(jwt), publicId);
    }
}
