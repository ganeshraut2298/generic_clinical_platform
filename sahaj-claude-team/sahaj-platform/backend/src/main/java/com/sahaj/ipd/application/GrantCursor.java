package com.sahaj.ipd.application;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SAH-34 (OQ-34-32): the opaque keyset cursor of the owner's list of unreviewed emergency access grants. Base64url,
 * without padding, of the last row's {@code opened_at} (epoch microseconds, 8 bytes) and its id (16 bytes): 32 characters.
 * The client sends it back unchanged; text that does not decode is refused. It is not signed: a decoded cursor only moves
 * the start of a page inside the owner's own clinic.
 */
final class GrantCursor {

    static final int MAX_LENGTH = 64;
    private static final Pattern TEXT = Pattern.compile("^[A-Za-z0-9_-]+$");

    private GrantCursor() {
    }

    record Position(Instant openedAt, UUID id) {
    }

    static String encode(Instant openedAt, UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putLong(ChronoUnit.MICROS.between(Instant.EPOCH, openedAt));
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    /** The position, or null when the text is not a cursor this server made. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH || !TEXT.matcher(cursor).matches()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length != 24) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long micros = buffer.getLong();
        if (micros < 0) {
            return null;
        }
        return new Position(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), new UUID(buffer.getLong(), buffer.getLong()));
    }
}
