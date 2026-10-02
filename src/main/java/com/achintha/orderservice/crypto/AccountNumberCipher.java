package com.achintha.orderservice.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of the customer's depositing account number at rest (sections 6.3 and 11), same format as
 * store-service: each value gets a fresh random 96-bit IV; the stored form is {@code v1:} + base64(IV || ciphertext
 * || 128-bit tag), so the key can be rotated later by adding a {@code v2}. Plain account numbers are never logged;
 * {@link #mask} gives the display form.
 */
@Component
public class AccountNumberCipher {

    static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AccountNumberCipher(CryptoProperties properties) {
        this.key = parseKey(properties.paymentAccountKey());
    }

    static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank() || base64Key.contains("${")) {
            throw new IllegalStateException("app.crypto.payment-account-key is required "
                    + "(PAYMENT_ACCOUNT_ENCRYPTION_KEY: base64 of 32 random bytes, e.g. `openssl rand -base64 32`)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.crypto.payment-account-key is not valid base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("app.crypto.payment-account-key must decode to 32 bytes (AES-256), got "
                    + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Account number encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Unsupported account number format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never include the stored value or the key in the message
            throw new IllegalStateException("Account number decryption failed", e);
        }
    }

    /** Display form: only the last 4 digits, e.g. {@code ****6789}. */
    public static String mask(String last4) {
        return "****" + (last4 == null ? "" : last4);
    }

    public static String lastFour(String accountNumber) {
        return accountNumber.length() <= 4 ? accountNumber : accountNumber.substring(accountNumber.length() - 4);
    }
}
