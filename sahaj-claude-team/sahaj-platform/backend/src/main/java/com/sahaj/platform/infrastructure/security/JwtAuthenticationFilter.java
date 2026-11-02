package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer} access token (SAH-3) and, only when it is valid and the
 * membership it names is still ACTIVE (checked at most once every 60 seconds, see
 * {@link CachedActiveMembershipChecker}), sets a {@link TenantAuthentication} for the rest of the
 * request. A missing, invalid, expired token, or a membership that is no longer ACTIVE, leaves
 * the request anonymous; every protected route then answers 401 through the usual entry point.
 * The tenant and role always come from the signed token, never from any other part of the request.
 *
 * <p>Both collaborators arrive through {@link ObjectProvider} and not as plain constructor
 * arguments, so this filter - and so {@code SecurityConfig} - can still build in a
 * {@code @WebMvcTest} slice that never wires a real {@link TokenService} or membership
 * repository; those tests build their own {@link TenantAuthentication} directly
 * ({@code TestTenantSecurity}) and never present a bearer header.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectProvider<TokenService> tokenService;
    private final ObjectProvider<CachedActiveMembershipChecker> activeMembershipChecker;

    public JwtAuthenticationFilter(ObjectProvider<TokenService> tokenService,
                                    ObjectProvider<CachedActiveMembershipChecker> activeMembershipChecker) {
        this.tokenService = tokenService;
        this.activeMembershipChecker = activeMembershipChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        TokenService tokens = header != null && header.startsWith(BEARER_PREFIX) ? tokenService.getIfAvailable() : null;
        if (tokens != null) {
            CachedActiveMembershipChecker checker = activeMembershipChecker.getIfAvailable();
            String token = header.substring(BEARER_PREFIX.length());
            tokens.parseAccessToken(token).ifPresent(claims -> {
                if (checker == null) {
                    return;
                }
                // The membership check runs in its own transaction, and the tenant is bound to the
                // database from the thread's authentication when that transaction begins. So the
                // signed-token authentication must be in place first; otherwise row level security
                // sees no tenant, finds no membership and every request answers 401.
                TenantPrincipal principal = new TenantPrincipal(claims.userId(), claims.tenantId());
                SecurityContextHolder.getContext().setAuthentication(new TenantAuthentication(principal,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()))));
                if (!checker.isActive(claims.tenantId(), claims.userId())) {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        chain.doFilter(request, response);
    }
}
