package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-34 OQ-34-32: the opaque keyset cursor of the owner's list. */
class GrantCursorTest {

    @Test
    void roundTripsTheMicrosecondTimeAndTheId() {
        Instant at = Instant.parse("2026-11-10T04:05:06.123456Z");
        UUID id = UUID.randomUUID();
        String cursor = GrantCursor.encode(at, id);

        GrantCursor.Position position = GrantCursor.decode(cursor);

        assertThat(position).isNotNull();
        assertThat(position.openedAt()).isEqualTo(at);
        assertThat(position.id()).isEqualTo(id);
        assertThat(cursor).hasSizeLessThanOrEqualTo(GrantCursor.MAX_LENGTH).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void refusesTextThisServerDidNotMake() {
        assertThat(GrantCursor.decode(null)).isNull();
        assertThat(GrantCursor.decode("")).isNull();
        assertThat(GrantCursor.decode("not a cursor!")).isNull();
        assertThat(GrantCursor.decode("AAAA")).isNull();
        assertThat(GrantCursor.decode("A".repeat(65))).isNull();
    }
}
