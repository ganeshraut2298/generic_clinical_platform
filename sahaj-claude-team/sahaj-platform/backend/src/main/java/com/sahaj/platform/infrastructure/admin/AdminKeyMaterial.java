package com.sahaj.platform.infrastructure.admin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The admin secrets after the startup checks of ADR-003 D2.3 and D3: the signing secret has at least
 * 32 characters and differs from the clinic secret; the authenticator key is the Base64 of exactly
 * 32 bytes and equals the bytes of neither secret. A failure stops the application and names the
 * environment variable, never its value.
 */
public final class AdminKeyMaterial {

    private final String jwtSecret;
    private final byte[] totpKey;

    private AdminKeyMaterial(String jwtSecret, byte[] totpKey) {
        this.jwtSecret = jwtSecret;
        this.totpKey = totpKey;
    }

    public static AdminKeyMaterial validate(AdminProperties properties, String clinicJwtSecret) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "SAHAJ_ADMIN_JWT_SECRET must be set to at least 32 characters. Refusing to start the admin portal.");
        }
        if (secret.equals(clinicJwtSecret)) {
            throw new IllegalStateException(
                    "SAHAJ_ADMIN_JWT_SECRET must differ from SAHAJ_JWT_SECRET. Refusing to start the admin portal.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.totpKey().trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "SAHAJ_ADMIN_TOTP_KEY must be the Base64 of exactly 32 bytes. Refusing to start the admin portal.");
        }
        if (key.length != 32) {
            throw new IllegalStateException(
                    "SAHAJ_ADMIN_TOTP_KEY must be the Base64 of exactly 32 bytes. Refusing to start the admin portal.");
        }
        if (Arrays.equals(key, secret.getBytes(StandardCharsets.UTF_8))
                || (clinicJwtSecret != null && Arrays.equals(key, clinicJwtSecret.getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException(
                    "SAHAJ_ADMIN_TOTP_KEY must not equal SAHAJ_JWT_SECRET or SAHAJ_ADMIN_JWT_SECRET. "
                            + "Refusing to start the admin portal.");
        }
        return new AdminKeyMaterial(secret, key);
    }

    public String jwtSecret() {
        return jwtSecret;
    }

    public byte[] totpKey() {
        return totpKey.clone();
    }

    @Override
    public String toString() {
        return "AdminKeyMaterial[]";
    }
}
