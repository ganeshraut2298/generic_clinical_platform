package com.sahaj.platform.infrastructure.security;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * An authenticated session of a clinic user. Its principal is a {@link TenantPrincipal}; the
 * tenant binding reads the tenant from it and from nowhere else.
 */
public class TenantAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final TenantPrincipal principal;

    public TenantAuthentication(TenantPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public TenantPrincipal getPrincipal() {
        return principal;
    }
}
