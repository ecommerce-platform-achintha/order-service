package com.achintha.orderservice.customer;

import com.achintha.orderservice.common.Validation;
import com.achintha.orderservice.config.OpenApiConfig;
import com.achintha.orderservice.customer.CustomerDtos.CustomerScoreResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin: customers")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminCustomerController {

    private final CustomerAdminService customerAdminService;

    @GetMapping("/api/admin/customers/{customerPublicId}/score")
    @Operation(summary = "A customer's score, COD refusals and suspension")
    public CustomerScoreResponse score(
            @PathVariable @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String customerPublicId) {
        return customerAdminService.score(customerPublicId);
    }
}
