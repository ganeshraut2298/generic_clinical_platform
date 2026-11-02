package com.sahaj.platform.infrastructure.security;

import java.util.Objects;
import java.util.UUID;

/**
 * A tenant id read from the (not yet trusted) prefix of a presented refresh token cookie
 * (SAH-3), before the matching row - and so the user it belongs to - is confirmed. Binding this
 * alone only grants the ordinary tenant-scoped row level security a refresh token lookup needs
 * ({@code p_refresh_token_tenant}); nothing is treated as authenticated for any other purpose.
 */
public record TenantOnlyPrincipal(UUID tenantId) {

    public TenantOnlyPrincipal {
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
