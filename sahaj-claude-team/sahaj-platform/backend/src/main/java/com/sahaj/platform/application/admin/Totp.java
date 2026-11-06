package com.sahaj.platform.application.admin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords on RFC 4226 HOTP: HMAC-SHA1 from the JDK, 30-second step,
 * 6 digits, dynamic truncation (ADR-003 D2.2). Codes of the current step and one step either side
 * are accepted; every comparison is constant time; a step not later than the last used one is
 * refused (replay). The database repeats the replay check atomically.
 */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final int WINDOW_STEPS = 1;

    private Totp() {
    }

    public static long stepAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
    }

    /** The zero-padded code for a counter (RFC 4226 section 5.3). */
    public static String generate(byte[] secret, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] message = new byte[8];
            for (int i = 7, shift = 0; i >= 0; i--, shift += 8) {
                message[i] = (byte) (counter >>> shift);
            }
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            long modulus = 1;
            for (int i = 0; i < digits; i++) {
                modulus *= 10;
            }
            return String.format("%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA1 is not available", ex);
        }
    }

    /**
     * The step whose code equals {@code code}, among the steps from one before to one after
     * {@code now}, ignoring any step not later than {@code lastUsedStep}. All steps are always
     * compared (no early exit). When two steps match, the later one is returned. Null when none.
     */
    public static Long matchStep(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        Long matched = null;
        for (long step = current - WINDOW_STEPS; step <= current + WINDOW_STEPS; step++) {
            byte[] expected = generate(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            boolean equal = MessageDigest.isEqual(expected, given);
            boolean fresh = lastUsedStep == null || step > lastUsedStep;
            if (equal && fresh) {
                matched = step;
            }
        }
        return matched;
    }
}
