package com.sahaj.platform.api;

import com.sahaj.platform.infrastructure.security.CurrentRole;
import com.sahaj.platform.infrastructure.security.CurrentTenant;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * The signed-in clinic user, for other modules (SAH-7, SAH-9). The ids and role come from the
 * access token's principal only; they are never read from a request body, header or query
 * parameter. A request with no signed-in clinic user fails with an authentication error, which the
 * error handler maps to 401. {@code role} is the membership role carried by the token at sign-in
 * time (SAH-3); it is not re-read from the database here (see {@link OwnerGuard} for callers that
 * need the freshest membership state for a sensitive write).
 */
public record ClinicUser(UUID userId, UUID tenantId, String role) {

    public static ClinicUser current() {
        return CurrentTenant.principal()
                .map(principal -> new ClinicUser(principal.userId(), principal.tenantId(),
                        CurrentRole.role().orElse(null)))
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sign in required"));
    }
}
