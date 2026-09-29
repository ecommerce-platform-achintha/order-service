package com.achintha.orderservice.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** The subset of product-service's product response that orders need. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductResponse(UUID id, String name, BigDecimal price, Stock stock) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stock(int quantityAvailable) {
    }

    public int quantityAvailable() {
        return stock == null ? 0 : stock.quantityAvailable();
    }
}
