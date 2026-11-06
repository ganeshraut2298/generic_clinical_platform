package com.sahaj.platform.infrastructure.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The admin refresh cookie (ADR-003 D3): {@code sahaj_admin_rt}, {@code HttpOnly; Secure;
 * SameSite=Strict; Path=/api/v1/admin/auth}. Never shares a name or a path with the clinic
 * {@code sahaj_rt}, and never appears in a JSON body.
 */
public final class AdminRefreshCookie {

    public static final String COOKIE_NAME = "sahaj_admin_rt";
    public static final String PATH = "/api/v1/admin/auth";

    private AdminRefreshCookie() {
    }

    /** {@code secure} is true everywhere except local dev (same setting as the clinic cookie). */
    public static void write(HttpServletResponse response, String rawToken, Duration maxAge, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, maxAge, secure).toString());
    }

    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO, secure).toString());
    }

    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseCookie build(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
