package com.achintha.orderservice.ports;

/**
 * Stores uploaded files (payment receipts, complaint photos) by opaque key (section 10). Clients upload elsewhere and
 * send the keys ({@code attachmentKeys}); this service only checks and resolves them.
 */
public interface ImageStoragePort {

    /** Whether an uploaded file exists under this key. */
    boolean exists(String key);

    /** A URL the client can display. */
    String urlFor(String key);
}
