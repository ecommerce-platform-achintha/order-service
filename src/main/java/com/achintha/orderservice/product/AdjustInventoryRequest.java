package com.achintha.orderservice.product;

/** {@code delta} is added to the available quantity: negative to take stock out, positive to restock. */
public record AdjustInventoryRequest(int delta) {
}
