package com.achintha.orderservice.support;

import com.achintha.orderservice.security.Role;
import java.util.List;
import java.util.UUID;

/** A user as user-service would describe it in a token. */
public record TestUser(UUID id, String publicId, Role role, UUID storeId, List<String> perms, long tokenVersion) {

    public TestUser withTokenVersion(long tv) {
        return new TestUser(id, publicId, role, storeId, perms, tv);
    }
}
