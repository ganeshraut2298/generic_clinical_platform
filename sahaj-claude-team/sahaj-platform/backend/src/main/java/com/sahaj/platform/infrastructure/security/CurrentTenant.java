package com.sahaj.platform.infrastructure.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the tenant principal of the current thread from the security context, and only from there. */
public final class CurrentTenant {

    private CurrentTenant() {
    }

    public static Optional<TenantPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TenantPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
