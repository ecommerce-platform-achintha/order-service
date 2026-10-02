package com.achintha.orderservice.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Human-readable public ids: {@code PREFIX-YYMM-XXXXXX} (section 1), where {@code XXXXXX} is 6 random Crockford
 * base32 characters (no I, L, O, U), so ids do not leak volumes. YYMM is the UTC creation month.
 *
 * <p>Uniqueness is enforced by a unique constraint on every {@code public_id} column; {@link #generateUnique}
 * additionally retries on the (very rare, 1 in 2^30 per month) collision before the insert.
 */
@Component
public class PublicIdGenerator {

    public static final String ORDER_PREFIX = "ORD";
    public static final String CHECKOUT_PREFIX = "CHK";
    public static final String PAYMENT_PREFIX = "PAY";
    public static final String COMPLAINT_PREFIX = "CMP";
    public static final String FLAGGED_REFERENCE_PREFIX = "FLG";
    // Not in the contract's prefix list (customer-side cart lines only); chosen here in the same style
    public static final String CART_ITEM_PREFIX = "CRT";

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int SUFFIX_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);
    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{3}-\\d{4}-[0-9A-HJKMNP-TV-Z]{6}$");

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public PublicIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String generate(String prefix) {
        return prefix + '-' + YYMM.format(clock.instant()) + '-' + randomSuffix(SUFFIX_LENGTH);
    }

    /** {@code length} random Crockford base32 characters. */
    public String randomSuffix(int length) {
        StringBuilder suffix = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            suffix.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return suffix.toString();
    }

    /** Generates ids until {@code exists} reports a free one. */
    public String generateUnique(String prefix, Predicate<String> exists) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generate(prefix);
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique " + prefix + " id");
    }

    public static boolean isValid(String publicId) {
        return publicId != null && FORMAT.matcher(publicId).matches();
    }
}
