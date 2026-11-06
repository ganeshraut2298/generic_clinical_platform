package com.sahaj.platform.application.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt, strength 12, for admin passwords (ADR-003 D2.1). Deliberately its own object and not a
 * Spring {@code PasswordEncoder} bean: the clinic OTP service injects the existing encoder by type.
 *
 * <p>BCrypt reads at most 72 bytes. A longer input is treated as wrong without calling the encoder
 * on it (so it is never truncated into a match), and the dummy hash is still checked so the time
 * taken does not change. {@link #dummyMatch} gives unknown emails the same cost as known ones.
 */
public final class AdminPasswordHasher {

    public static final int STRENGTH = 12;
    public static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;
    private volatile String dummyHash;

    public AdminPasswordHasher(SecureRandom random) {
        this.encoder = new BCryptPasswordEncoder(STRENGTH, random);
    }

    public String hash(String password) {
        if (tooLong(password)) {
            throw new IllegalArgumentException("A password over 72 bytes cannot be hashed");
        }
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null || tooLong(password)) {
            dummyMatch("");
            return false;
        }
        return encoder.matches(password, hash);
    }

    /** Spends the same time as a real check, against a fixed dummy hash of the same strength. */
    public void dummyMatch(String password) {
        String candidate = password == null || tooLong(password) ? "" : password;
        encoder.matches(candidate, dummyHash());
    }

    private String dummyHash() {
        String value = dummyHash;
        if (value == null) {
            synchronized (this) {
                value = dummyHash;
                if (value == null) {
                    value = encoder.encode(UUID.randomUUID().toString());
                    dummyHash = value;
                }
            }
        }
        return value;
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
