package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class RefreshTokenCookieTest {

    @Test
    void writesASecureCookieWhenSecureIsOn() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefreshTokenCookie.write(response, "token", Duration.ofDays(1), true);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Secure").contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void leavesOutSecureWhenTurnedOffForLocalDev() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefreshTokenCookie.write(response, "token", Duration.ofDays(1), false);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure").contains("HttpOnly");
    }

    @Test
    void clearFollowsTheSameSecureSetting() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RefreshTokenCookie.clear(response, false);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure").contains("Max-Age=0");
    }
}
