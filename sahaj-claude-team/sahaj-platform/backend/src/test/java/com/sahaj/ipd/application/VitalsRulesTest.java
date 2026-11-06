package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.application.VitalsRules.Valid;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SAH-30 AC4 and the contract's VitalsInput rules: format, plausible range, the note text and the event texts. */
class VitalsRulesTest {

    private static final String FORMAT = "Enter all five vitals. BP as 120/80.";
    private static final UUID KEY = UUID.randomUUID();

    private static VitalsInput with(String bp, String pulse, String spo2, String temp, String rr, String note) {
        return new VitalsInput(KEY.toString(), bp, pulse, spo2, temp, rr, note);
    }

    private static VitalsInput ok() {
        return with("120/80", "80", "98", "37.0", "16", null);
    }

    private static List<FieldMessage> errorsOf(VitalsInput input) {
        InvalidFieldsException e = org.junit.jupiter.api.Assertions.assertThrows(InvalidFieldsException.class,
                () -> VitalsRules.validate(input));
        return e.errors();
    }

    // ---- a good body ----

    @Test
    void aGoodBodyIsParsedWithTheTemperatureInTenths() {
        Valid valid = VitalsRules.validate(ok());

        assertThat(valid.systolic()).isEqualTo(120);
        assertThat(valid.diastolic()).isEqualTo(80);
        assertThat(valid.pulse()).isEqualTo(80);
        assertThat(valid.spo2()).isEqualTo(98);
        assertThat(valid.tempTenths()).isEqualTo(370);
        assertThat(valid.rr()).isEqualTo(16);
        assertThat(valid.note()).isNull();
    }

    @Test
    void spacesAtBothEndsAreStrippedAndAWholeTemperatureOrOneDecimalIsAccepted() {
        assertThat(VitalsRules.validate(with("  120/80 ", " 80 ", " 98", "37 ", "16  ", null)).tempTenths())
                .isEqualTo(370);
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "36.8", "16", null)).tempTenths()).isEqualTo(368);
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "25.5", "16", null)).tempTenths()).isEqualTo(255);
    }

    @Test
    void theRangeEdgesAreInclusive() {
        Valid low = VitalsRules.validate(with("40/20", "1", "1", "25.0", "1", null));
        Valid high = VitalsRules.validate(with("300/200", "300", "100", "45.0", "300", null));

        assertThat(low.systolic()).isEqualTo(40);
        assertThat(low.tempTenths()).isEqualTo(250);
        assertThat(high.diastolic()).isEqualTo(200);
        assertThat(high.tempTenths()).isEqualTo(450);
    }

    // ---- format ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "120", "120/", "/80", "1/80", "120/8", "1200/80", "120 / 80",
            "120/ 80", "120/80/90", "12a/80", "-120/80", "120.5/80", "120\\80",
            "\uff11\uff12\uff10/\uff18\uff10"})
    void aBpThatIsNotTwoWholeNumbersOfTwoOrThreeDigitsIsAFormatError(String bp) {
        List<FieldMessage> errors = errorsOf(with(bp, "80", "98", "37.0", "16", null));

        assertThat(errors).containsExactly(new FieldMessage("bp", FORMAT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "0", "00", "000", "1000", "8.5", "-5", "abc", "8 0", "\uff18\uff10"})
    void aPulseSpo2OrRrThatIsNotAWholeNumberAbove0Of1To3DigitsIsAFormatError(String value) {
        assertThat(errorsOf(with("120/80", value, "98", "37.0", "16", null)))
                .containsExactly(new FieldMessage("pulse", FORMAT));
        assertThat(errorsOf(with("120/80", "80", value, "37.0", "16", null)))
                .containsExactly(new FieldMessage("spo2", FORMAT));
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", value, null)))
                .containsExactly(new FieldMessage("rr", FORMAT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "0", "00", "0.0", "00.0", "370", "37.55", "37,5", ".5", "37.", "-37.0", "abc",
            "3 7"})
    void aTemperatureThatIsNotAtMostTwoDigitsAndOneDecimalAbove0IsAFormatError(String temp) {
        assertThat(errorsOf(with("120/80", "80", "98", temp, "16", null)))
                .containsExactly(new FieldMessage("temp", FORMAT));
    }

    // ---- plausible range ----

    @ParameterizedTest
    @ValueSource(strings = {"39/80", "301/80", "120/19", "120/201", "120/800", "00/00", "999/999"})
    void aBpOutsideTheRangeGetsTheRangeMessage(String bp) {
        assertThat(errorsOf(with(bp, "80", "98", "37.0", "16", null)))
                .containsExactly(new FieldMessage("bp", "Check the BP. Systolic 40 to 300, diastolic 20 to 200."));
    }

    @Test
    void pulseSpo2TemperatureAndRrOutsideTheRangeGetTheirOwnMessages() {
        assertThat(errorsOf(with("120/80", "301", "101", "45.1", "301", null))).containsExactly(
                new FieldMessage("pulse", "Check the pulse. Enter 1 to 300 per minute."),
                new FieldMessage("spo2", "Check the SpO\u2082. Enter 1 to 100 %."),
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 \u00b0C."),
                new FieldMessage("rr", "Check the RR. Enter 1 to 300 per minute."));
        assertThat(errorsOf(with("120/80", "80", "98", "24.9", "16", null))).containsExactly(
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 \u00b0C."));
        assertThat(errorsOf(with("120/80", "80", "98", "98.6", "16", null))).containsExactly(
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 \u00b0C."));
    }

    @Test
    void aSystolicAtOrBelowTheDiastolicIsNotRefused() {
        Valid valid = VitalsRules.validate(with("80/120", "80", "98", "37.0", "16", null));

        assertThat(valid.systolic()).isEqualTo(80);
        assertThat(valid.diastolic()).isEqualTo(120);
    }

    @Test
    void everyFieldErrorIsReportedTogetherInTheOrderBpPulseSpo2TempRrNote() {
        List<FieldMessage> errors = errorsOf(with("x", "x", "x", "x", "x", "bad\u0007"));

        assertThat(errors).extracting(FieldMessage::field).containsExactly("bp", "pulse", "spo2", "temp", "rr", "note");
        assertThat(errors.subList(0, 5)).extracting(FieldMessage::message).containsOnly(FORMAT);
        assertThat(errors.get(5).message()).isEqualTo("Note cannot contain control or text-direction characters");
    }

    @Test
    void theMessagesNeverEchoWhatWasTyped() {
        List<FieldMessage> errors = errorsOf(with("987654", "secret1", "secret2", "secret3", "secret4", "bad\u0007"));

        for (FieldMessage error : errors) {
            assertThat(error.message()).doesNotContain("987654").doesNotContain("secret");
        }
    }

    // ---- the client key ----

    @Test
    void theClientKeyIsAUuidAndIsStripped() {
        assertThat(VitalsRules.clientKey(new VitalsInput(" " + KEY + " ", null, null, null, null, null, null)))
                .isEqualTo(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "1234", "1-1-1-1-1", "12345678-1234-1234-1234-12345678901",
            "12345678-1234-1234-1234-1234567890", "12345678123412341234123456789012",
            "1234567g-1234-1234-1234-123456789012", "１２345678-1234-1234-1234-123456789012",
            "+1234567-1234-1234-1234-123456789012", "-1234567-1234-1234-1234-123456789012"})
    void aMissingOrBrokenClientKeyIsA400OnClientKey(String key) {
        assertThatThrownBy(() -> VitalsRules.clientKey(new VitalsInput(key, "120/80", "80", "98", "37.0", "16", null)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("clientKey");
                    assertThat(e.getMessage()).isEqualTo("Reload the page and try again.");
                });
    }

    @Test
    void aCanonicalClientKeyInCapitalsIsAccepted() {
        assertThat(VitalsRules.clientKey(new VitalsInput(KEY.toString().toUpperCase(java.util.Locale.ROOT), null, null,
                null, null, null, null))).isEqualTo(KEY);
    }

    @Test
    void severalFieldErrorsAskForTheFirstMessageAsTheProblemDetail() {
        InvalidFieldsException e = org.junit.jupiter.api.Assertions.assertThrows(InvalidFieldsException.class,
                () -> VitalsRules.validate(with("x", "0", "98", "37.0", "16", null)));

        assertThat(e.errors()).hasSizeGreaterThan(1);
        assertThat(e.firstMessageAsDetail()).isTrue();
    }

    // ---- the note ----

    @Test
    void aMissingNullOrBlankNoteMeansNoNote() {
        for (String note : new String[] {null, "", "  ", "\n", " \r\n\r\n ", "\u00a0", "\u2007\u202f", "\u3000\n\u00a0"}) {
            assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", note)).note()).isNull();
        }
    }

    @Test
    void aNoteIsComposedToNfcStrippedAndKeepsItsLineBreaksAsLf() {
        Valid valid = VitalsRules.validate(with("120/80", "80", "98", "37.0", "16",
                "\n  Cafe\u0301 first\r\nsecond\rthird  \n\n"));

        assertThat(valid.note()).isEqualTo("Caf\u00e9 first\nsecond\nthird");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0007b", "a\u0000b", "a\u007Fb", "a\u0085b", "a\u200bb", "a\u202eb",
            "a\u2066b", "a\u200eb", "a\u061cb", "a\u2028b", "a\u2029b", "a\u2060b", "a\ufeffb", "a\u001Bb"})
    void controlBidiAndInvisibleCharactersInTheNoteAreRefused(String note) {
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", note)))
                .containsExactly(new FieldMessage("note", "Note cannot contain control or text-direction characters"));
    }

    @Test
    void aTabInTheNoteBecomesOneSpaceAndIsNotRefused() {
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", "BP\tchecked\t\tagain")).note())
                .isEqualTo("BP checked  again");
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", "\t note \t")).note())
                .isEqualTo("note");
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", "\t\t")).note()).isNull();
    }

    @Test
    void aTabCountsAsOneCodePointForTheStoredLimit() {
        String text = "a\t".repeat(500);

        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", text)).note()).hasSize(999);
    }

    @Test
    void aRawNoteOver4000CharactersIsRefusedBeforeNormalisationEvenWhenMostlySpaces() {
        String spaces = " ".repeat(4000) + "a";

        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", spaces)))
                .containsExactly(new FieldMessage("note", "Keep the note to 1,000 characters."));
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "\ud83d\ude00".repeat(2001))))
                .containsExactly(new FieldMessage("note", "Keep the note to 1,000 characters."));
    }

    @Test
    void theRawLengthIsCheckedBeforeTheCharacterRuleSoOnlyTheLengthMessageIsGiven() {
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "a".repeat(4000) + "\u0007")))
                .containsExactly(new FieldMessage("note", "Keep the note to 1,000 characters."));
    }

    @Test
    void aRawNoteOfExactly4000CharactersIsStillCheckedByTheNormalRules() {
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", " ".repeat(3999) + "a")).note())
                .isEqualTo("a");
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "a".repeat(4000))))
                .containsExactly(new FieldMessage("note", "Keep the note to 1,000 characters."));
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", " ".repeat(3999) + "\u0007")))
                .containsExactly(new FieldMessage("note", "Note cannot contain control or text-direction characters"));
    }

    @Test
    void aControlCharacterAtTheEdgeIsRefusedNotStrippedAway() {

        // The check runs on the raw text before stripping, so a stray character at either end is not dropped.
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "\u200bhello")))
                .extracting(FieldMessage::field).containsExactly("note");
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "hello\u0007")))
                .extracting(FieldMessage::field).containsExactly("note");
    }

    @Test
    void aJoinerIsAllowedOnlyAfterAViramaAndBeforeALetterAndNeverNextToALineBreak() {
        // Devanagari virama U+094D then ZWJ then a letter
        String conjunct = "\u0915\u094d\u200d\u0937";
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", conjunct)).note())
                .isEqualTo(conjunct);

        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "\u200dab"))).hasSize(1);
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "\u0915\u094d\n\u200d\u0937"))).hasSize(1);
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "a\u200db"))).hasSize(1);
    }

    @Test
    void aNoteOf1000CodePointsIsAcceptedAnd1001IsRefused() {
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", "a".repeat(1000))).note())
                .hasSize(1000);

        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", "a".repeat(1001))))
                .containsExactly(new FieldMessage("note", "Keep the note to 1,000 characters."));
    }

    @Test
    void theLengthIsCountedInCodePointsAndALineBreakCountsAsOne() {
        String emoji = "\ud83d\ude00".repeat(1000);
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", emoji)).note()).isEqualTo(emoji);
        assertThat(errorsOf(with("120/80", "80", "98", "37.0", "16", emoji + "\ud83d\ude00"))).hasSize(1);

        // 499 x "a" + CR LF is 998 code points + "a" = 999 once CR LF is one line break
        String lines = "a\r\n".repeat(499) + "a";
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", lines)).note()).hasSize(999);
    }

    @Test
    void theLengthIsCheckedAfterStrippingSoPaddingDoesNotCount() {
        String padded = " ".repeat(50) + "a".repeat(1000) + "\n".repeat(50);

        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", padded)).note()).hasSize(1000);
    }

    @Test
    void theValidRecordHidesTheValuesFromToString() {
        assertThat(VitalsRules.validate(with("120/80", "80", "98", "37.0", "16", "private note")).toString())
                .doesNotContain("120").doesNotContain("private");
    }

    // ---- fixed texts of the clinical event log ----

    @Test
    void theEventTextsAreTheFixedTemplatesOfTheStory() {
        assertThat(VitalsRules.recordedText(6, Risk.MEDIUM)).isEqualTo("Recorded vitals \u00b7 EWS 6 (medium)");
        assertThat(VitalsRules.recordedText(0, Risk.LOW)).isEqualTo("Recorded vitals \u00b7 EWS 0 (low)");
        assertThat(VitalsRules.raisedText(6, Risk.MEDIUM, 60)).isEqualTo(
                "Raised early warning escalation \u00b7 EWS 6 (medium) \u00b7 doctor review within 1 hour");
        assertThat(VitalsRules.raisedText(9, Risk.HIGH, 15)).isEqualTo(
                "Raised early warning escalation \u00b7 EWS 9 (high) \u00b7 doctor review within 15 min");
        assertThat(VitalsRules.updatedText(8, Risk.HIGH)).isEqualTo("Escalation updated \u00b7 EWS now 8 (high)");
    }

    // ---- roles and the flags of the admission sheet ----

    @Test
    void whoMayReadAndWhoMayRecord() {
        assertThat(VitalsRules.READ_ROLES).containsExactlyInAnyOrder("OWNER", "NURSE", "DOCTOR");
        assertThat(VitalsRules.RECORD_ROLES).containsExactlyInAnyOrder("OWNER", "NURSE");
        assertThat(VitalsRules.canRead("FRONT_DESK")).isFalse();
        assertThat(VitalsRules.canRead(null)).isFalse();
        assertThat(VitalsRules.canRead("DOCTOR")).isTrue();
    }

    @Test
    void vitalsDueIsNullForARoleWithoutVitalsAccess() {
        Instant now = Instant.parse("2026-11-11T05:30:00Z");

        assertThat(VitalsRules.vitalsDue("FRONT_DESK", true, null, now)).isNull();
        assertThat(VitalsRules.vitalsDue("NURSE", true, null, now)).isTrue();
        assertThat(VitalsRules.vitalsDue("NURSE", true, now.minusSeconds(60), now)).isFalse();
        assertThat(VitalsRules.vitalsDue("DOCTOR", false, null, now)).isFalse();
    }
}
