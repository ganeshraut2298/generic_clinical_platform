package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestLogContextFilterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void putsRequestIdTenantIdAndUserIdInTheContextDuringTheRequestAndClearsThemAfter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TenantAuthentication(new TenantPrincipal(USER, TENANT), AuthorityUtils.NO_AUTHORITIES));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] during = new String[3];

        new RequestLogContextFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/ipd/admissions"), response,
                (req, res) -> {
                    during[0] = MDC.get("requestId");
                    during[1] = MDC.get("tenantId");
                    during[2] = MDC.get("userId");
                });

        assertThat(during[0]).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(during[1]).isEqualTo(TENANT.toString());
        assertThat(during[2]).isEqualTo(USER.toString());
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void withNoSignedInUserOnlyTheRequestIdIsSet() throws Exception {
        String[] during = new String[2];

        new RequestLogContextFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/platform/health"),
                new MockHttpServletResponse(), (req, res) -> {
                    during[0] = MDC.get("tenantId");
                    during[1] = MDC.get("userId");
                });

        assertThat(during[0]).isNull();
        assertThat(during[1]).isNull();
    }
}
