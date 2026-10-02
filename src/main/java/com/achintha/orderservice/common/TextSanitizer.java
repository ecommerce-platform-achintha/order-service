package com.achintha.orderservice.common;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitizes free text (names, reasons, business names, address lines) with the OWASP Java HTML Sanitizer.
 *
 * <p>No HTML is allowed in this service, so every tag is removed. The sanitizer's entity-escaped output is decoded
 * back to plain text (so "O'Brien &amp; Sons" is stored as typed), and any remaining angle bracket is dropped, so
 * the stored value can never form markup again. Control characters are removed and whitespace is trimmed.
 */
public final class TextSanitizer {

    private static final PolicyFactory NO_HTML = new HtmlPolicyBuilder().toFactory();

    private TextSanitizer() {
    }

    /** Returns the sanitized text, or {@code null} for null or blank input. */
    public static String clean(String input) {
        if (input == null) {
            return null;
        }
        String sanitized = NO_HTML.sanitize(input);
        String plain = decodeEntities(sanitized)
                .replace("<", "")
                .replace(">", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .strip();
        return plain.isEmpty() ? null : plain;
    }

    /** Like {@link #clean} but for single-line values: newlines and tabs collapse to one space. */
    public static String cleanLine(String input) {
        String cleaned = clean(input);
        return cleaned == null ? null : cleaned.replaceAll("\\s+", " ");
    }

    private static String decodeEntities(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int end = c == '&' ? text.indexOf(';', i) : -1;
            if (end > i && end - i <= 10) {
                String entity = text.substring(i + 1, end);
                String decoded = decodeEntity(entity);
                if (decoded != null) {
                    out.append(decoded);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String decodeEntity(String entity) {
        switch (entity) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                break;
        }
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return Character.toString(Integer.parseInt(entity.substring(2), 16));
            }
            if (entity.startsWith("#")) {
                return Character.toString(Integer.parseInt(entity.substring(1)));
            }
        } catch (IllegalArgumentException ignored) {
            // not a numeric entity: keep the text as is
        }
        return null;
    }
}
