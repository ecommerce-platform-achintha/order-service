package com.achintha.orderservice.payment;

import java.util.Locale;

/** Bank references are compared normalised (section 6.3): trimmed, upper-case, without any whitespace. */
public final class ReferenceNormalizer {

    private ReferenceNormalizer() {
    }

    public static String normalize(String reference) {
        return reference == null ? null : reference.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
