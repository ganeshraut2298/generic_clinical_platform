package com.sahaj.platform.infrastructure.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Sahaj admin realm (ADR-003, ADM-1). Everything is off unless {@code enabled}.
 * {@code jwtSecret} and {@code totpKey} are secrets with no default: they come from
 * {@code SAHAJ_ADMIN_JWT_SECRET} and {@code SAHAJ_ADMIN_TOTP_KEY}. The seed values are for the
 * {@code dev} profile only. {@code toString} never prints a secret.
 */
@ConfigurationProperties(prefix = "sahaj.admin")
public record AdminProperties(
        boolean enabled,
        String jwtSecret,
        String totpKey,
        String allowedNetworks,
        @DefaultValue Totp totp,
        @DefaultValue Seed seed) {

    /** {@code devBypass}: accept any 6-digit code from a loopback address, dev profile only. */
    public record Totp(boolean devBypass) {
    }

    public record Seed(String email, String password, @DefaultValue("Dev admin") String name) {
        @Override
        public String toString() {
            return "Seed[]";
        }
    }

    public boolean secretsConfigured() {
        return jwtSecret != null && !jwtSecret.isBlank() && totpKey != null && !totpKey.isBlank();
    }

    @Override
    public String toString() {
        return "AdminProperties[enabled=" + enabled + "]";
    }
}
