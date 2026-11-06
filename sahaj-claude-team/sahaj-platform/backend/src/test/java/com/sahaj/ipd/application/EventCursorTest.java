package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** SAH-33 the opaque page cursor of the clinical audit trail (ADR-007 D13). */
class EventCursorTest {

    private static String base64(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aPositionSurvivesARoundTripToTheMicrosecond() {
        Position position = new Position(Instant.parse("2026-11-11T05:30:00.123456Z"), 4711);

        String cursor = EventCursor.encode(position);

        assertThat(EventCursor.decode(cursor)).isEqualTo(position);
    }

    @Test
    void theCursorUsesOnlyUrlSafeCharactersAndFitsTheContractsLength() {
        String cursor = EventCursor.encode(new Position(Instant.parse("2026-11-11T05:30:00.999999Z"),
                Long.MAX_VALUE / 10));

        assertThat(cursor).matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void aCursorIsNotJustTheTimeAsReadableText() {
        String cursor = EventCursor.encode(new Position(Instant.parse("2026-11-11T05:30:00Z"), 7));

        assertThat(cursor).doesNotContain("2026").doesNotContain(".");
    }

    @Test
    void textThatThisServerDidNotMakeIsRefused() {
        String[] bad = {null, "", " ", "abc", "not a cursor", "!!!!", "a.b", base64("12345"), base64("12.ab"),
            base64("-5.3"), base64("5.-3"), base64("1.2.3"), base64(".5"), base64("5."), base64("1234567890123456789.1"),
            base64("1.1234567890123456789"), "A".repeat(65), base64("1 .2")};
        for (String text : bad) {
            assertThat(EventCursor.decode(text)).as(String.valueOf(text)).isNull();
        }
    }

    @Test
    void aWellFormedCursorWithSmallNumbersIsAccepted() {
        assertThat(EventCursor.decode(base64("0.0"))).isEqualTo(new Position(Instant.EPOCH, 0));
        assertThat(EventCursor.decode(base64("1000000.5")))
                .isEqualTo(new Position(Instant.EPOCH.plusSeconds(1), 5));
    }
}
