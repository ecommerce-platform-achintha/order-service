package com.achintha.orderservice.security;

/** Access-token claim names (section 3.3). Other services rely on these names: never rename them. */
public final class TokenClaims {

    /** User publicId. */
    public static final String PUBLIC_ID = "pid";
    public static final String ROLE = "role";
    public static final String STATUS = "status";
    /** Merchant's store id, or the employing store for assistants. */
    public static final String STORE_ID = "storeId";
    /** Assistant permissions (assistants only). */
    public static final String PERMISSIONS = "perms";
    /** Token version; tokens older than the user's current version are rejected. */
    public static final String TOKEN_VERSION = "tv";
    /** Service tokens only: the calling service's client id. */
    public static final String SERVICE = "svc";

    private TokenClaims() {
    }
}
