package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** ADR-018 D5: the browser-binding half of the ticket. */
class BootstrapCookieTest {

    private static final String VALUE = "B".repeat(43);

    @Test
    void theCookieIsHttpOnlySameSiteStrictOnTheBootstrapPathFor300Seconds() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BootstrapCookie.write(response, VALUE, true);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("sahaj_admin_bootstrap=" + VALUE).contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict").contains("Path=/api/v1/admin/bootstrap").contains("Max-Age=300");
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
    }

    @Test
    void secureIsOmittedOnlyWhenTheDevSettingSaysSo() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BootstrapCookie.write(response, VALUE, false);

        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Secure").contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void clearingEmptiesTheValueAndEndsItNow() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BootstrapCookie.clear(response, false);

        assertThat(response.getHeader("Set-Cookie")).startsWith("sahaj_admin_bootstrap=;").contains("Max-Age=0")
                .contains("Path=/api/v1/admin/bootstrap").contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void theCookiePathCoversNoOtherAdminOrClinicEndpoint() {
        assertThat(BootstrapCookie.PATH).isEqualTo("/api/v1/admin/bootstrap");
        assertThat("/api/v1/admin/auth/login").doesNotStartWith(BootstrapCookie.PATH);
        assertThat("/api/v1/admin/me").doesNotStartWith(BootstrapCookie.PATH);
    }

    @Test
    void readFindsTheOneCookieOfThatName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie("sahaj_admin_bootstrap", VALUE));

        assertThat(BootstrapCookie.read(request)).isEqualTo(VALUE);
    }

    @Test
    void readIsNullWhenTheCookieIsMissingOrSentTwice() {
        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setCookies(new Cookie("sahaj_admin_rt", "x"));
        MockHttpServletRequest twice = new MockHttpServletRequest();
        twice.setCookies(new Cookie("sahaj_admin_bootstrap", VALUE), new Cookie("sahaj_admin_bootstrap", VALUE));

        assertThat(List.of(BootstrapCookie.read(none) == null, BootstrapCookie.read(other) == null,
                BootstrapCookie.read(twice) == null)).containsOnly(true);
    }
}
