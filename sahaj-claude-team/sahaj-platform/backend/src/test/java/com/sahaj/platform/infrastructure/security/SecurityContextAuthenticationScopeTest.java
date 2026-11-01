package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityContextAuthenticationScopeTest {

    private final SecurityContextAuthenticationScope scope = new SecurityContextAuthenticationScope();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runAsVerifiedUserBindsOnlyThatUserIdDuringTheAction() {
        UUID userId = UUID.randomUUID();

        UUID seenDuringAction = scope.runAsVerifiedUser(userId, () -> CurrentVerifiedUser.userId().orElseThrow());

        assertThat(seenDuringAction).isEqualTo(userId);
        assertThat(CurrentVerifiedUser.userId()).isEmpty();
        assertThat(CurrentTenant.principal()).isEmpty();
    }

    @Test
    void runAsTenantBindsAFullTenantPrincipalDuringTheAction() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        TenantPrincipal seen = scope.runAsTenant(userId, tenantId, () -> CurrentTenant.principal().orElseThrow());

        assertThat(seen.userId()).isEqualTo(userId);
        assertThat(seen.tenantId()).isEqualTo(tenantId);
        assertThat(CurrentTenant.principal()).isEmpty();
    }

    @Test
    void runAsTenantOnlyBindsOnlyTheTenantId() {
        UUID tenantId = UUID.randomUUID();

        UUID seen = scope.runAsTenantOnly(tenantId, () -> CurrentTenantOnly.tenantId().orElseThrow());

        assertThat(seen).isEqualTo(tenantId);
        assertThat(CurrentTenantOnly.tenantId()).isEmpty();
    }

    @Test
    void restoresThePreviousAuthenticationEvenWhenTheActionThrows() {
        TestTenantSecurityAuth previous = new TestTenantSecurityAuth();
        SecurityContextHolder.getContext().setAuthentication(previous);

        assertThatThrownBy(() -> scope.runAsVerifiedUser(UUID.randomUUID(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(previous);
    }

    /** A minimal stand-in authentication, just to prove restoration by identity. */
    private static final class TestTenantSecurityAuth extends org.springframework.security.authentication.AbstractAuthenticationToken {
        private static final long serialVersionUID = 1L;

        TestTenantSecurityAuth() {
            super(java.util.List.of());
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return "test";
        }
    }
}
