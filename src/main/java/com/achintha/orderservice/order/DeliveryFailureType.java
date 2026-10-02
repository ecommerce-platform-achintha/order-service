package com.achintha.orderservice.order;

/** Why a shipped order failed. For COD orders either counts as a COD refusal (section 6.2). */
public enum DeliveryFailureType {
    COD_REFUSED,
    UNDELIVERABLE
}
