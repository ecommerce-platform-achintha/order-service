package com.achintha.orderservice.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param paymentAccountKey base64 of 32 random bytes (AES-256), from {@code PAYMENT_ACCOUNT_ENCRYPTION_KEY}; never
 *                          logged
 */
@ConfigurationProperties("app.crypto")
public record CryptoProperties(String paymentAccountKey) {

    @Override
    public String toString() {
        return "CryptoProperties[paymentAccountKey=****]";
    }
}
