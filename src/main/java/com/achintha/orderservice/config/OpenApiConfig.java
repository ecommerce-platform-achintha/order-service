package com.achintha.orderservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Public API docs. {@code /internal/**} is excluded ({@code springdoc.paths-to-exclude}). */
@Configuration
@OpenAPIDefinition(info = @Info(title = "Order Service API", version = "v1",
        description = "Cart, checkout (one order per store under a CHK- group), the order state machine "
                + "(quote, confirm, pay, ship, complete), bank-transfer payments, COD privilege, store blocks and "
                + "complaints. Customer identity always comes from the JWT. Public APIs use publicIds only (ORD-, "
                + "CHK-, PAY-, CMP-, FLG-, CRT-). Errors carry a stable 'code'. State diagram: see the README."))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
