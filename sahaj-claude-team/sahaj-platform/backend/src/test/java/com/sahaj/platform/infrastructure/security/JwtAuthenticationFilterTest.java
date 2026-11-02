package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.TokenService.AccessTokenClaims;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TokenService tokens = mock(TokenService.class);
    private final CachedActiveMembershipChecker checker = mock(CachedActiveMembershipChecker.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantIsBoundBeforeTheMembershipCheckRuns() throws Exception {
        when(tokens.parseAccessToken("good")).thenReturn(Optional.of(
                new AccessTokenClaims(userId, tenantId, "OWNER", Instant.now().plusSeconds(60))));
        AtomicReference<Optional<UUID>> tenantSeenByCheck = new AtomicReference<>();
        when(checker.isActive(any(), any())).thenAnswer(invocation -> {
            tenantSeenByCheck.set(CurrentTenant.principal().map(TenantPrincipal::tenantId));
            return true;
        });

        runWithBearer("good");

        assertThat(tenantSeenByCheck.get()).contains(tenantId);
        Authentication after = SecurityContextHolder.getContext().getAuthentication();
        assertThat(after).isInstanceOf(TenantAuthentication.class);
        assertThat(after.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
    }

    @Test
    void inactiveMembershipLeavesTheRequestAnonymous() throws Exception {
        when(tokens.parseAccessToken("good")).thenReturn(Optional.of(
                new AccessTokenClaims(userId, tenantId, "NURSE", Instant.now().plusSeconds(60))));
        when(checker.isActive(tenantId, userId)).thenReturn(false);

        runWithBearer("good");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenLeavesTheRequestAnonymous() throws Exception {
        when(tokens.parseAccessToken("bad")).thenReturn(Optional.empty());

        runWithBearer("bad");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @SuppressWarnings("unchecked")
    private void runWithBearer(String token) throws Exception {
        ObjectProvider<TokenService> tokenProvider = mock(ObjectProvider.class);
        when(tokenProvider.getIfAvailable()).thenReturn(tokens);
        ObjectProvider<CachedActiveMembershipChecker> checkerProvider = mock(ObjectProvider.class);
        when(checkerProvider.getIfAvailable()).thenReturn(checker);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        new JwtAuthenticationFilter(tokenProvider, checkerProvider)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }
}
