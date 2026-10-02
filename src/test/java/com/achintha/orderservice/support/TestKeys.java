package com.achintha.orderservice.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A throwaway RSA key pair generated per test JVM, as PEM text (never a committed key). */
public final class TestKeys {

    public static final KeyPair KEY_PAIR = generate();
    public static final String PRIVATE_PEM = pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
    public static final String PUBLIC_PEM = pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded());

    private TestKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
