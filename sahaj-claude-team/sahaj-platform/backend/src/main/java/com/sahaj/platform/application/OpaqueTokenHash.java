package com.sahaj.platform.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic hashing for the opaque refresh token (SAH-3): lookup is by exact
 * {@code token_hash} match, so (unlike the one-time code, hashed with a salted
 * {@code PasswordEncoder}) this has to produce the same digest every time for the same input.
 * The token itself already has 256 bits of {@link java.security.SecureRandom} entropy, so a fast,
 * unsalted digest is an acceptable trade for being searchable.
 */
final class OpaqueTokenHash {

    private OpaqueTokenHash() {
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
