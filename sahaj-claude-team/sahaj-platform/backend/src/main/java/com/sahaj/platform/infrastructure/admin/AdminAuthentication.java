package com.sahaj.platform.infrastructure.admin;

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** An authenticated platform admin. Its only authority is {@code ROLE_PLATFORM_ADMIN}. */
public class AdminAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    public static final String ROLE = "PLATFORM_ADMIN";

    private final AdminPrincipal principal;

    public AdminAuthentication(AdminPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AdminPrincipal getPrincipal() {
        return principal;
    }
}
