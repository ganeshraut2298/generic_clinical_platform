package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.AuthenticationScope;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Implements {@link AuthenticationScope} by swapping the thread's {@link Authentication} for the
 * duration of {@code action}, then always restoring what was there before (SAH-3, DEC-007).
 *
 * <p>This only changes what {@link TenantBindingTransactionManager} sees when the *next*
 * transaction begins. Callers must invoke a separately transactional method inside {@code action}
 * (never the same method that is already running), so a fresh transaction actually begins while
 * the swapped authentication is in place.
 */
@Component
public class SecurityContextAuthenticationScope implements AuthenticationScope {

    @Override
    public <T> T runAsTenant(UUID userId, UUID tenantId, Supplier<T> action) {
        return runAs(new TenantAuthentication(new TenantPrincipal(userId, tenantId), List.of()), action);
    }

    @Override
    public <T> T runAsVerifiedUser(UUID userId, Supplier<T> action) {
        return runAs(new VerifiedUserAuthentication(new VerifiedUserPrincipal(userId)), action);
    }

    @Override
    public <T> T runAsTenantOnly(UUID tenantId, Supplier<T> action) {
        return runAs(new TenantOnlyAuthentication(new TenantOnlyPrincipal(tenantId)), action);
    }

    private static <T> T runAs(Authentication authentication, Supplier<T> action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
