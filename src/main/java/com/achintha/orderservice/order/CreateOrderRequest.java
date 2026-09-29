package com.achintha.orderservice.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotEmpty List<@Valid @NotNull Item> items) {

    public record Item(
            @NotNull UUID productId,
            @NotNull @Positive Integer quantity) {
    }
}
