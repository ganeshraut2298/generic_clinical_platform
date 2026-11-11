package com.sahaj.platform.devtools.bootstrap;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Checks the typed registration PIN against the BCrypt hash from {@code SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH} (ADR-018 D3).
 *
 * <ul>
 *   <li>BCrypt from {@code spring-security-crypto} (no new library). {@link BCrypt#checkpw(byte[], String)} compares the
 *       final hashes without an early return, so the time does not depend on how many characters were right.
 *   <li>The PIN is compared exactly as sent: no trimming, no digit rule, so a longer or non-numeric PIN later needs
 *       only a new hash.
 *   <li>More than 64 characters or more than 72 UTF-8 bytes is a wrong PIN. It is never given to BCrypt (BCrypt would
 *       cut it at 72 bytes and could match a prefix). A fixed dummy hash of the same cost is checked instead, so the
 *       time does not differ from a normal wrong PIN.
 *   <li>The byte copy of the typed PIN is zeroed after use. (The request's own {@code String} is out of our hands.)
 * </ul>
 *
 * <p>The blank-PIN rule (a form error, not counted) is the caller's: this class only answers right or wrong. The hash
 * is held once, never logged, never returned, and {@link #toString()} prints nothing.
 */
final class BootstrapPinVerifier {

    static final int MAX_CHARACTERS = 64;
    static final int MAX_BYTES = 72;

    private final String hash;
    private final String dummyHash;
    private final AtomicInteger dummyChecks = new AtomicInteger();

    /** @param hash a valid BCrypt hash (cost 12 to 16), already checked against B4 */
    BootstrapPinVerifier(String hash, SecureRandom random) {
        Matcher format = BootstrapConditions.PIN_HASH_FORMAT.matcher(hash);
        if (!format.matches()) {
            throw new IllegalArgumentException("The registration PIN hash is not a BCrypt hash of cost 12 to 16");
        }
        this.hash = hash;
        int cost = Integer.parseInt(format.group(1));
        byte[] filler = new byte[16];
        random.nextBytes(filler);
        this.dummyHash = BCrypt.hashpw(filler, BCrypt.gensalt(cost, random));
        Arrays.fill(filler, (byte) 0);
    }

    /** True only for the right PIN. Zeroes its own copies; never throws for any input text. */
    boolean matches(char[] typed) {
        byte[] bytes = null;
        try {
            if (Character.codePointCount(typed, 0, typed.length) > MAX_CHARACTERS) {
                return dummyCheck();
            }
            bytes = utf8(typed);
            if (bytes.length > MAX_BYTES) {
                return dummyCheck();
            }
            return BCrypt.checkpw(bytes, hash);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /** Spends the time of one real check on a fixed value and always answers wrong. */
    private boolean dummyCheck() {
        dummyChecks.incrementAndGet();
        byte[] fixed = new byte[] {'x'};
        BCrypt.checkpw(fixed, dummyHash);
        return false;
    }

    /** For the tests: how many times the dummy hash stood in for the real one. */
    int dummyChecks() {
        return dummyChecks.get();
    }

    private static byte[] utf8(char[] typed) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(typed));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }

    @Override
    public String toString() {
        return "BootstrapPinVerifier[]";
    }
}
