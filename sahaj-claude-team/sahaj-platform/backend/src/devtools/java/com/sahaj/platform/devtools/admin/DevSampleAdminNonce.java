package com.sahaj.platform.devtools.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * The per-start secret of gate check G7 (ADR-008 R1). 16 random bytes (128 bits) written as 32 lower-case hex
 * characters, kept in memory only and created once per application context start. It is printed once, in the start-up
 * warning, and nowhere else: it is not a Spring property, not persisted, not in a response, header, audit row or other
 * log line. A forwarder that adds no header (a port proxy, a tunnel, a bare reverse proxy) can make a remote caller
 * look like the developer to G2 to G4, but that caller cannot read the developer's console and so cannot send this
 * value.
 *
 * <p>The comparison is {@link MessageDigest#isEqual} on the bytes, with no trimming and no case folding. The length is
 * public. {@link #toString()} prints no value.
 */
final class DevSampleAdminNonce {

    static final String HEADER = "X-Sahaj-Dev-Nonce";
    static final int BYTES = 16;
    static final int LENGTH = BYTES * 2;

    private final String text;
    private final byte[] ascii;

    /** A new value from the given source; production passes a {@link SecureRandom}. */
    DevSampleAdminNonce(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        this.text = HexFormat.of().formatHex(bytes);
        this.ascii = text.getBytes(StandardCharsets.US_ASCII);
    }

    static DevSampleAdminNonce generate() {
        return new DevSampleAdminNonce(new SecureRandom());
    }

    /** The value, for the one start-up warning line. Never call this anywhere else. */
    String valueForStartupNotice() {
        return text;
    }

    /**
     * G7: exactly one header value, US-ASCII only, equal to the nonce. Zero values, two or more values, a different
     * length, any non-ASCII character, any other case, or surrounding white space is false.
     */
    boolean accepts(List<String> headerValues) {
        if (headerValues == null || headerValues.size() != 1) {
            return false;
        }
        String candidate = headerValues.get(0);
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) > 0x7F) {
                return false;
            }
        }
        return MessageDigest.isEqual(ascii, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return "DevSampleAdminNonce[redacted]";
    }
}
