package com.sahaj.platform.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The refresh token cookie (SAH-3 technical design): {@code HttpOnly; Secure; SameSite=Strict},
 * scoped to the auth endpoints only, never read by client-side script and never present in any
 * JSON response.
 */
public final class RefreshTokenCookie {

    public static final String COOKIE_NAME = "sahaj_rt";
    private static final String PATH = "/api/v1/platform/auth";

    private RefreshTokenCookie() {
    }

    public static void write(HttpServletResponse response, String rawToken, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, maxAge).toString());
    }

    public static void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
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

    private static ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
