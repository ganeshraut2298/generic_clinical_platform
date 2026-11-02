package com.sahaj.platform.support;

import com.sahaj.platform.infrastructure.security.TenantAuthentication;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test security helper (SAH-1: there is no login yet, so tests build the principal themselves).
 *
 * <p>For MockMvc calls use {@code mockMvc.perform(get(...).with(TestTenantSecurity.asUser(tenantId, userId)))}.
 * For direct service or repository calls inside a transaction use {@link #runAs}, so the tenant
 * binding in the transaction manager sees the same principal the real login (SAH-3) will provide.
 * Use {@link #signedInWithoutTenant()} to test the fail-closed case: a logged-in user with no
 * tenant bound reads no tenant rows.
 */
public final class TestTenantSecurity {

    private TestTenantSecurity() {
    }

    public static Authentication authentication(UUID tenantId, UUID userId, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new TenantAuthentication(new TenantPrincipal(userId, tenantId), authorities);
    }

    /** A logged-in user whose principal carries no tenant. Tenant tables must return no rows for it. */
    public static Authentication signedInWithoutTenant() {
        return UsernamePasswordAuthenticationToken.authenticated("no-tenant-user", "", List.of());
    }

    public static RequestPostProcessor asUser(UUID tenantId, UUID userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.authentication(authentication(tenantId, userId, roles));
    }

    public static RequestPostProcessor asUserWithoutTenant() {
        return SecurityMockMvcRequestPostProcessors.authentication(signedInWithoutTenant());
    }

    /** Runs the action with the given authentication on this thread and always clears it afterwards. */
    public static <T> T runAs(Authentication authentication, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public static void runAs(Authentication authentication, Runnable action) {
        runAs(authentication, () -> {
            action.run();
            return null;
        });
    }
}
