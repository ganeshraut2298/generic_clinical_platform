package com.sahaj.platform.infrastructure.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the {@link VerifiedUserPrincipal} of the current thread, and only from there. */
public final class CurrentVerifiedUser {

    private CurrentVerifiedUser() {
    }

    public static Optional<UUID> userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof VerifiedUserPrincipal principal) {
            return Optional.of(principal.userId());
        }
        return Optional.empty();
    }
}
