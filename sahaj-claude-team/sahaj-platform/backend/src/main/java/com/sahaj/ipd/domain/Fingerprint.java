package com.sahaj.ipd.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * SAH-31 the 22-character opaque fingerprint of ADR-007 R1: the UTF-8 text of the lines joined by a line feed
 * (empty text for no line), SHA-256, the first 16 bytes, base64url without padding. Used for the check token of
 * {@link MedicineSafetyCheck} and for the change token of the pharmacist summary.
 */
public final class Fingerprint {

    public static final int LENGTH = 22;

    private Fingerprint() {
    }

    public static String of(List<String> lines) {
        try {
            byte[] bytes = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
