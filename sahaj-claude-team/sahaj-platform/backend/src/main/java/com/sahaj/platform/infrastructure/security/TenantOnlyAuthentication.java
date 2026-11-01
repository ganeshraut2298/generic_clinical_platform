package com.sahaj.platform.infrastructure.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Transient authentication for the {@link TenantOnlyPrincipal} window (SAH-3 refresh lookup). */
public class TenantOnlyAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final TenantOnlyPrincipal principal;

    public TenantOnlyAuthentication(TenantOnlyPrincipal principal) {
        super(List.of());
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public TenantOnlyPrincipal getPrincipal() {
        return principal;
    }
}
