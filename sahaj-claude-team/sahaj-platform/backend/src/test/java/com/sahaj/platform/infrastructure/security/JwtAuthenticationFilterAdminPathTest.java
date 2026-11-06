package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.TokenService.AccessTokenClaims;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADM-1: the clinic JWT filter is also registered with the servlet container, so it must stay out of
 * {@code /api/v1/admin/**}. A clinic token is never honoured there, even on the public sign-in routes.
 */
class JwtAuthenticationFilterAdminPathTest {

    private final TokenService tokens = mock(TokenService.class);
    private final CachedActiveMembershipChecker checker = mock(CachedActiveMembershipChecker.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private void run(String path) throws Exception {
        when(tokens.parseAccessToken("good")).thenReturn(Optional.of(
                new AccessTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "OWNER", Instant.now().plusSeconds(60))));
        when(checker.isActive(any(), any())).thenReturn(true);
        ObjectProvider<TokenService> tokenProvider = mock(ObjectProvider.class);
        when(tokenProvider.getIfAvailable()).thenReturn(tokens);
        ObjectProvider<CachedActiveMembershipChecker> checkerProvider = mock(ObjectProvider.class);
        when(checkerProvider.getIfAvailable()).thenReturn(checker);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer good");
        FilterChain chain = mock(FilterChain.class);
        new JwtAuthenticationFilter(tokenProvider, checkerProvider).doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aClinicTokenIsIgnoredOnTheAdminLoginRoute() throws Exception {
        run("/api/v1/admin/auth/login");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokens, never()).parseAccessToken(any());
    }

    @Test
    void aClinicTokenIsIgnoredOnEveryAdminRoute() throws Exception {
        run("/api/v1/admin/me");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        SecurityContextHolder.clearContext();
        run("/api/v1/admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aClinicTokenStillWorksOnTheClinicApi() throws Exception {
        run("/api/v1/platform/me");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(TenantAuthentication.class);
    }

    @Test
    void aPathThatOnlyLooksLikeTheAdminPrefixIsStillClinic() throws Exception {
        run("/api/v1/administrators");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(TenantAuthentication.class);
    }
}
