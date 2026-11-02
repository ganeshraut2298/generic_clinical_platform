package com.sahaj.platform.infrastructure.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the {@link TenantOnlyPrincipal} of the current thread, and only from there. */
public final class CurrentTenantOnly {

    private CurrentTenantOnly() {
    }

    public static Optional<UUID> tenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TenantOnlyPrincipal principal) {
            return Optional.of(principal.tenantId());
        }
        return Optional.empty();
    }
}
