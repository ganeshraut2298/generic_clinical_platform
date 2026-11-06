package com.sahaj.platform.infrastructure.admin;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the {@link AdminPrincipal} of the current thread from the security context, and only from there. */
public final class CurrentAdmin {

    private CurrentAdmin() {
    }

    public static Optional<AdminPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
