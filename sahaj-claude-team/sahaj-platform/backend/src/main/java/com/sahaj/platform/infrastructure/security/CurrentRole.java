package com.sahaj.platform.infrastructure.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the signed-in membership's role from the current {@code ROLE_*} authority (SAH-5
 * {@code GET /me}), the same role the access token carries ({@link JwtAuthenticationFilter}).
 * Never derived again from a database lookup: the token is already the source of truth for role
 * within its own lifetime (SAH-3 technical design).
 */
public final class CurrentRole {

    private static final String PREFIX = "ROLE_";

    private CurrentRole() {
    }

    public static Optional<String> role() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(PREFIX))
                .map(authority -> authority.substring(PREFIX.length()))
                .findFirst();
    }
}
