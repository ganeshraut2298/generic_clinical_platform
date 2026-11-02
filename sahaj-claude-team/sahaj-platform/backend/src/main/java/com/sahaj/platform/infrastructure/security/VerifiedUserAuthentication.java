package com.sahaj.platform.infrastructure.security;

import java.util.List;
import java.util.Objects;

/**
 * Transient authentication for the {@link VerifiedUserPrincipal} window (SAH-3, DEC-007). Never
 * produced by the security filter chain; set and cleared by
 * {@code AuthenticationScope.runAsVerifiedUser}, around one narrow transaction only.
 */
public class VerifiedUserAuthentication extends org.springframework.security.authentication.AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final VerifiedUserPrincipal principal;

    public VerifiedUserAuthentication(VerifiedUserPrincipal principal) {
        super(List.of());
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public VerifiedUserPrincipal getPrincipal() {
        return principal;
    }
}
