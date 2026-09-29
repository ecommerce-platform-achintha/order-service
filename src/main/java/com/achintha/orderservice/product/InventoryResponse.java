package com.achintha.orderservice.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryResponse(UUID productId, int quantityAvailable) {
}
