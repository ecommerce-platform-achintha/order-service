package com.achintha.orderservice.common;

/** Shared Bean Validation patterns, so every DTO validates the same fields the same way. */
public final class Validation {

    /** Opaque keys of the mock image storage (receipts, complaint photos). */
    public static final String STORAGE_KEY_REGEX = "^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$";
    public static final String PUBLIC_ID_REGEX = "^[A-Z]{3}-\\d{4}-[0-9A-HJKMNP-TV-Z]{6}$";
    public static final String PUBLIC_ID_MESSAGE = "must be a public id like ORD-2610-7K2M9Q";
    /** Master-data short codes chosen by admins (banks, couriers): BOC, COMB, DOMEX. */
    public static final String CODE_REGEX = "^[A-Z0-9][A-Z0-9_-]{0,19}$";
    /** Depositing account numbers: digits, optionally separated by spaces or dashes. */
    public static final String ACCOUNT_NUMBER_REGEX = "^[0-9][0-9 -]{3,38}[0-9]$";
    /** Bank transfer references as typed or pasted by the customer (normalised before use). */
    public static final String REFERENCE_REGEX = "^\\s*[A-Za-z0-9][A-Za-z0-9 /._-]{0,59}\\s*$";
    public static final int REASON_MAX = 500;
    public static final int TEXT_MAX = 2000;
    public static final int LONG_TEXT_MAX = 4000;
    public static final int MAX_ATTACHMENTS = 10;

    private Validation() {
    }
}
