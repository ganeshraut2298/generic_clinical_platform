package com.sahaj.platform.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SAH-3 token settings. {@code secret} has no default: it is a secret and must come from the
 * environment ({@code SAHAJ_JWT_SECRET}), never committed to code.
 */
@ConfigurationProperties(prefix = "sahaj.jwt")
public record JwtProperties(String secret, int accessTokenMinutes, int selectionTokenMinutes) {

    public JwtProperties {
        if (accessTokenMinutes <= 0) {
            accessTokenMinutes = 15;
        }
        if (selectionTokenMinutes <= 0) {
            selectionTokenMinutes = 5;
        }
    }
}
