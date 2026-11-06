package com.sahaj.platform.infrastructure.admin;

import java.util.Objects;
import java.util.UUID;

/**
 * The signed-in platform admin and their session. Built only by the admin JWT filter after the
 * session check. It has no tenant, by design: the transaction manager binds {@code app.admin_id} and
 * {@code app.admin_session_id} from it and never {@code app.tenant_id}.
 */
public record AdminPrincipal(UUID adminId, UUID sessionId) {

    public AdminPrincipal {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
