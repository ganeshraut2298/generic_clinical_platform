package com.sahaj.platform.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Stores and checks the personal staff PIN (SAH-31, ADR-010 D2):
 * {@code BCrypt_12( base64( HMAC-SHA256( pepper, "sahaj-staff-pin-v1|" + tenantId + "|" + membershipId + "|" + pin ) ) )}.
 *
 * <p>The pepper ({@code SAHAJ_PIN_PEPPER}) lives outside the database, so a database copy alone reveals no PIN;
 * the tenant and membership ids in the HMAC input stop a hash copied from one row to another from matching. This
 * is a plain class, not a {@code PasswordEncoder} bean, so Spring Security and the one-time code encoder can never
 * pick it up by accident. The comparison is BCrypt's full-length check; the HMAC is computed in full for every
 * check. Nothing here keeps, logs or prints the PIN, the HMAC, the hash or the pepper.
 */
public final class StaffPinHasher {

    /** Stored in {@code pepper_version}; a rotation procedure is not designed (ADR-010 D2, Q6). */
    public static final int PEPPER_VERSION = 1;

    static final int BCRYPT_STRENGTH = 12;
    static final int MIN_PEPPER_BYTES = 32;
    private static final String HMAC = "HmacSHA256";
    private static final String INPUT_PREFIX = "sahaj-staff-pin-v1|";

    private final byte[] pepper;
    private final BCryptPasswordEncoder bcrypt;

    private StaffPinHasher(byte[] pepper, int strength) {
        this.pepper = pepper;
        this.bcrypt = new BCryptPasswordEncoder(strength);
    }

    /**
     * Builds the hasher from the environment value.
     *
     * @param pepperBase64 Base64 of at least 32 random bytes; null or blank means "not set": the hasher is then
     *                     unavailable and every PIN operation fails closed (503 pin-unavailable)
     * @param jwtSecret    the access token secret; the pepper must not be equal to it
     * @throws IllegalStateException when a value is present but invalid (the message never contains the value)
     */
    public static StaffPinHasher create(String pepperBase64, String jwtSecret) {
        return create(pepperBase64, jwtSecret, BCRYPT_STRENGTH);
    }

    /** Test seam: a lower BCrypt cost keeps unit tests fast. Production code always uses {@link #create(String, String)}. */
    static StaffPinHasher create(String pepperBase64, String jwtSecret, int strength) {
        if (pepperBase64 == null || pepperBase64.isBlank()) {
            return new StaffPinHasher(null, strength);
        }
        String trimmed = pepperBase64.trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SAHAJ_PIN_PEPPER is not valid Base64. Set it to the Base64 of at least "
                    + MIN_PEPPER_BYTES + " random bytes, or leave it unset to switch staff PIN checks off.");
        }
        if (decoded.length < MIN_PEPPER_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("SAHAJ_PIN_PEPPER is too short. It must be the Base64 of at least "
                    + MIN_PEPPER_BYTES + " random bytes.");
        }
        if (jwtSecret != null && !jwtSecret.isEmpty()
                && (MessageDigest.isEqual(trimmed.getBytes(StandardCharsets.UTF_8),
                        jwtSecret.trim().getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(decoded, jwtSecret.trim().getBytes(StandardCharsets.UTF_8)))) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("SAHAJ_PIN_PEPPER must not be the same as SAHAJ_JWT_SECRET. "
                    + "Use a separate random value.");
        }
        return new StaffPinHasher(decoded, strength);
    }

    /** False when no pepper is configured: nothing can be saved or checked. */
    public boolean isAvailable() {
        return pepper != null;
    }

    /** Hashes a PIN for storage. Fails when the pepper is not configured. */
    public String hash(UUID tenantId, UUID membershipId, String pin) {
        return bcrypt.encode(mac(tenantId, membershipId, pin));
    }

    /**
     * Checks a typed PIN against the stored hash. A null or malformed PIN simply does not match; the full
     * computation still runs, so the time does not tell a caller how the input looked.
     */
    public boolean matches(UUID tenantId, UUID membershipId, String pin, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return bcrypt.matches(mac(tenantId, membershipId, pin == null ? "" : pin), storedHash);
    }

    private String mac(UUID tenantId, UUID membershipId, String pin) {
        if (pepper == null) {
            throw new IllegalStateException("Staff PIN checks are off");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pepper, HMAC));
            byte[] input = (INPUT_PREFIX + tenantId + "|" + membershipId + "|" + pin).getBytes(StandardCharsets.UTF_8);
            byte[] digest = mac.doFinal(input);
            Arrays.fill(input, (byte) 0);
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available");
        }
    }

    /** Redacted: never the pepper. */
    @Override
    public String toString() {
        return "StaffPinHasher[available=" + isAvailable() + "]";
    }
}
