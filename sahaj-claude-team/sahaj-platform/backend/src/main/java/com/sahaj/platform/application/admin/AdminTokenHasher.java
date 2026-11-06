package com.sahaj.platform.application.admin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed hash of every opaque admin value that is stored (ADR-003 D3): pre-auth tokens, refresh
 * tokens, recovery codes and the sign-in throttle key. HMAC-SHA256 with a key derived as
 * {@code HMAC-SHA256(SAHAJ_ADMIN_JWT_SECRET, "sahaj-admin-token-hash-v1")}, so a database user
 * who can write rows cannot compute a stored value. The input is prefixed with a purpose label
 * ({@code preauth}, {@code refresh}, {@code recovery}, {@code email}) so a value of one kind can
 * never collide with another kind. Output is 64 lower-case hex characters, the database format.
 */
public final class AdminTokenHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public AdminTokenHasher(String adminJwtSecret) {
        this.key = hmac(adminJwtSecret.getBytes(StandardCharsets.UTF_8),
                "sahaj-admin-token-hash-v1".getBytes(StandardCharsets.UTF_8));
    }

    public String preAuth(String token) {
        return hash("preauth", token);
    }

    public String refresh(String token) {
        return hash("refresh", token);
    }

    /** {@code normalisedCode} is upper case without hyphens or spaces. */
    public String recovery(String normalisedCode) {
        return hash("recovery", normalisedCode);
    }

    /** {@code normalisedEmail} is trimmed and lower-cased. */
    public String emailKey(String normalisedEmail) {
        return hash("email", normalisedEmail);
    }

    private String hash(String label, String value) {
        return HexFormat.of().formatHex(hmac(key, (label + ":" + value).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] keyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
