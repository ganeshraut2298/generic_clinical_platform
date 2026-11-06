package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.WristbandCheck.Result;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SAH-32 the wristband check (ADR-011 D4): normalisation, match, mismatch, and what may reach the event log. */
class WristbandCheckTest {

    private static final String UHID = "SJ-00042";

    /** A character by code point, so the source holds no invisible characters. */
    private static String ch(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** The full-width forms (U+FF01 to U+FF5E) of ASCII text; NFKC folds them back. */
    private static String fullWidth(String ascii) {
        StringBuilder wide = new StringBuilder();
        for (char c : ascii.toCharArray()) {
            wide.append(c == '-' ? ch(0xFF0D) : String.valueOf((char) (c + 0xFEE0)));
        }
        return wide.toString();
    }

    // ---- the box is empty ----

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("blankBoxes")
    void aBoxWithNothingInItAfterNormalisationIsEmpty(String box) {
        assertThat(WristbandCheck.check(box, UHID)).isEqualTo(Result.EMPTY);
    }

    /** Blanks, control characters and format characters. */
    static Stream<String> blankBoxes() {
        return Stream.of(" ", "   ", "\t", "\r\n", ch(0xA0), ch(0x200B), ch(0x2028), ch(0),
                " " + ch(0x200E) + ch(0x200F) + " ");
    }

    // ---- it matches ----

    @ParameterizedTest
    @MethodSource("matchingBoxes")
    void theBoxMatchesWhenBothSidesAreEqualAfterNormalisation(String box) {
        assertThat(WristbandCheck.check(box, UHID)).isEqualTo(Result.MATCH);
    }

    static Stream<String> matchingBoxes() {
        return Stream.of("SJ-00042", "sj-00042", "Sj-00042", " SJ-00042 ", "SJ - 00042", "S J - 0 0 0 4 2",
                "SJ-00042\r", "SJ-00042\n", "SJ-00042\r\n", "SJ-00042\t", "\tSJ-00042",
                ch(0x200E) + "SJ-00042" + ch(0x200F), "SJ" + ch(0xA0) + "-00042", fullWidth("SJ-00042"),
                fullWidth("SJ") + "-" + fullWidth("00042"));
    }

    @Test
    void aScannerSTrailingEnterOrTabIsDropped() {
        assertThat(WristbandCheck.check("SJ-00042\n", UHID)).isEqualTo(Result.MATCH);
        assertThat(WristbandCheck.check("SJ-00042\t", UHID)).isEqualTo(Result.MATCH);
        assertThat(WristbandCheck.check("SJ-00042\r", UHID)).isEqualTo(Result.MATCH);
    }

    @Test
    void theUhidIsNormalisedTooSoALowerCaseOrPaddedUhidOnFileStillMatches() {
        assertThat(WristbandCheck.check("SJ-00042", " sj-00042 ")).isEqualTo(Result.MATCH);
    }

    @Test
    void fullWidthLettersAndDigitsAreFoldedByNfkc() {
        assertThat(WristbandCheck.normalise(fullWidth("SJ-00042"))).isEqualTo("SJ-00042");
    }

    // ---- it does not match ----

    @ParameterizedTest
    @ValueSource(strings = {"SJ-00043", "SJ-0042", "SJ-000420", "XX-00042", "SJ00042", "00042", "SJ-",
            "Ramesh Kale", "SJ-00042 Ramesh", "SJ-00042;SJ-00043", "SJ-00O42"})
    void aDifferentTextIsAMismatch(String box) {
        assertThat(WristbandCheck.check(box, UHID)).isEqualTo(Result.MISMATCH);
    }

    @Test
    void aMissingUhidNeverMatches() {
        assertThat(WristbandCheck.check("SJ-00042", null)).isEqualTo(Result.MISMATCH);
        assertThat(WristbandCheck.check("SJ-00042", "")).isEqualTo(Result.MISMATCH);
    }

    @Test
    void aBoxLongerThanTwoHundredUnitsIsAMismatchEvenWhenItHoldsTheUhid() {
        String long201 = "SJ-00042" + " ".repeat(193);
        assertThat(long201).hasSize(201);

        assertThat(WristbandCheck.check(long201, UHID)).isEqualTo(Result.MISMATCH);
        assertThat(WristbandCheck.check(" ".repeat(201), UHID)).isEqualTo(Result.MISMATCH);
    }

    @Test
    void aBoxOfExactlyTwoHundredUnitsIsStillNormalised() {
        String exactly200 = "SJ-00042" + " ".repeat(192);
        assertThat(exactly200).hasSize(200);

        assertThat(WristbandCheck.check(exactly200, UHID)).isEqualTo(Result.MATCH);
    }

    // ---- what the event log may show ----

    @ParameterizedTest
    @ValueSource(strings = {"SJ-00043", "sj-00043", " SJ-00043\n", "AB-12345", "ABCDEF-123456789"})
    void aScanInTheShapeOfAUhidIsShownNormalised(String box) {
        assertThat(WristbandCheck.scanForEvent(box)).isEqualTo(WristbandCheck.normalise(box));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Ramesh Kale", "9876543210", "ABHA 12-3456", "S-00042", "ABCDEFG-00042", "SJ-0042",
            "SJ-00042 note", "SJ-0004A", "PENICILLIN ALLERGY"})
    void anyOtherScanIsShownAsTheFixedWords(String box) {
        assertThat(WristbandCheck.scanForEvent(box)).isEqualTo("text that is not a UHID");
    }

    @Test
    void aScanLongerThanTheShapeAllowsIsTheFixedWords() {
        // the shape allows 2 to 6 letters, a hyphen and 5 to 9 digits, 16 characters at most
        String longest = "ABCDEF-" + "1".repeat(9);

        assertThat(WristbandCheck.scanForEvent(longest)).isEqualTo(longest);
        assertThat(WristbandCheck.scanForEvent("ABCDEF-" + "1".repeat(10))).isEqualTo(WristbandCheck.NOT_A_UHID);
        assertThat(longest.length()).isLessThanOrEqualTo(40);
    }

    @Test
    void aBoxOverTwoHundredUnitsIsTheFixedWordsInTheEventToo() {
        assertThat(WristbandCheck.scanForEvent("SJ-00043" + " ".repeat(200))).isEqualTo(WristbandCheck.NOT_A_UHID);
    }

    @Test
    void normaliseOfNullIsEmpty() {
        assertThat(WristbandCheck.normalise(null)).isEmpty();
    }

    @Test
    void upperCaseUsesTheRootLocaleSoATurkishDeviceDoesNotChangeIt() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(WristbandCheck.normalise("sj-iii")).isEqualTo("SJ-III");
        } finally {
            Locale.setDefault(before);
        }
    }
}
