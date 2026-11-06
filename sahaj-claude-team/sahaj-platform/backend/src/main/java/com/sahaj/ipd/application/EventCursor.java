package com.sahaj.ipd.application;

import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The opaque page cursor of the clinical audit trail (ADR-007 D13): base64url, without padding, of the last entry's
 * {@code at} in epoch microseconds and its {@code seq}, written {@code micros.seq}. The client sends it back
 * unchanged; a cursor that does not decode is refused. It is not signed: a decoded cursor only moves the start of a
 * page inside the caller's own clinic and scope.
 */
final class EventCursor {

    static final int MAX_LENGTH = 64;
    private static final Pattern TEXT = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern PLAIN = Pattern.compile("^([0-9]{1,18})\\.([0-9]{1,18})$");

    private EventCursor() {
    }

    static String encode(Position position) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, position.at());
        String plain = micros + "." + position.seq();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
    }

    /** The position, or null when the text is not a cursor this server made. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH || !TEXT.matcher(cursor).matches()) {
            return null;
        }
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            return null;
        }
        java.util.regex.Matcher m = PLAIN.matcher(plain);
        if (!m.matches()) {
            return null;
        }
        return new Position(Instant.EPOCH.plus(Long.parseLong(m.group(1)), ChronoUnit.MICROS),
                Long.parseLong(m.group(2)));
    }
}
