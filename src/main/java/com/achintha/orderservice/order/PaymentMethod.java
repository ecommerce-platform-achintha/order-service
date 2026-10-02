package com.achintha.orderservice.order;

/** Chosen per store at checkout. The platform never handles money: bank transfers are verified by the merchant. */
public enum PaymentMethod {
    BANK_TRANSFER,
    COD
}
