package com.sahaj.platform.devtools.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keyed hash of the two halves of the bootstrap ticket (ADR-018 D5): {@code HMAC-SHA256(k, value)} as 64 lower-case
 * hex characters, with {@code k = HMAC-SHA256(SAHAJ_ADMIN_JWT_SECRET, "sahaj-admin-bootstrap-ticket-v1")}. This is the
 * keyed-hash pattern of ADR-003 D3 with its own label, so a stored value cannot be computed by someone who can only run
 * SQL, and a pre-auth or refresh token hash can never equal a ticket hash. Also makes new ticket values.
 */
final class BootstrapTicketHasher {

    static final String KEY_LABEL = "sahaj-admin-bootstrap-ticket-v1";
    static final int VALUE_LENGTH = 43;

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern WELL_SHAPED = Pattern.compile("[A-Za-z0-9_-]{" + VALUE_LENGTH + "}");

    private final byte[] key;

    BootstrapTicketHasher(String adminJwtSecret) {
        this.key = hmac(adminJwtSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    /** The database format of a stored half: 64 lower-case hex characters. */
    String hash(String value) {
        return HexFormat.of().formatHex(hmac(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 256 random bits as Base64url without padding: 43 characters. */
    static String newValue(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The contract's pattern {@code ^[A-Za-z0-9_-]{43}$}. Anything else is treated as a missing value. */
    static boolean wellShaped(String value) {
        return value != null && WELL_SHAPED.matcher(value).matches();
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

    @Override
    public String toString() {
        return "BootstrapTicketHasher[]";
    }
}
