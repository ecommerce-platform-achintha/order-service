package com.achintha.orderservice.order;

/** Why a merchant rejected an order before quoting. No reason carries a penalty for either side (section 13.1). */
public enum RejectionReason {
    OUT_OF_STOCK,
    LOW_CUSTOMER_SCORE,
    COD_HISTORY,
    CANNOT_DELIVER_TO_AREA,
    OTHER
}
