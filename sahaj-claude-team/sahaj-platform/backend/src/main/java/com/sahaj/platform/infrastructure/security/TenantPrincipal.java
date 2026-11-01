package com.sahaj.platform.infrastructure.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The logged-in person and the clinic (tenant) they are working in.
 *
 * <p>This is the only source of the tenant for database access. It is built by the login
 * mechanism (SAH-3) or by the test security helper, never from a request body, header or query
 * parameter.
 */
public record TenantPrincipal(UUID userId, UUID tenantId) {

    public TenantPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
