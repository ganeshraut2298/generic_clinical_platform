package com.sahaj.platform.devtools.bootstrap;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The browser-binding half of the bootstrap ticket (ADR-018 D5): cookie {@code sahaj_admin_bootstrap}, {@code HttpOnly;
 * SameSite=Strict; Path=/api/v1/admin/bootstrap; Max-Age=300}, and {@code Secure} except where the existing dev-only
 * setting {@code sahaj.auth.refresh-cookie-secure=false} applies (plain http://localhost). The path covers only the two
 * bootstrap calls, so the cookie never reaches any other admin or clinic endpoint. The value is never logged.
 */
final class BootstrapCookie {

    static final String NAME = "sahaj_admin_bootstrap";
    static final String PATH = BootstrapGate.BASE;
    static final Duration LIFE = Duration.ofMinutes(5);

    private BootstrapCookie() {
    }

    static void write(HttpServletResponse response, String value, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(value, LIFE, secure).toString());
    }

    static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO, secure).toString());
    }

    /** The one cookie of this name, or null when it is missing or sent more than once (both are "no binding"). */
    static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String found = null;
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                if (found != null) {
                    return null;
                }
                found = cookie.getValue();
            }
        }
        return found;
    }

    private static ResponseCookie build(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
