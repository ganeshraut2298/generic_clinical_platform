package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 AC4, AC5, AC6 over HTTP on the real {@code sahaj_test} database: the format and the plausible range of the
 * five typed vitals with the messages of the contract, every field error together in the order of the screen, the
 * note rules (empty means none, 1,000 code points, control and text-direction characters, a line break stored as LF,
 * a joiner only after a virama), the client never sending a score, and the score table at the edge of each band.
 * A refused request writes nothing. Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsValidationIT extends VitalsSupport {

    private static final String EMOJI = "😀";

    private record Setup(Clinic clinic, Adm adm) {
    }

    private Setup setup() throws Exception {
        Clinic clinic = hospital();
        return new Setup(clinic, admitOne(clinic));
    }

    /** The save must be refused with this message on this field, and nothing may be written. */
    private void refusedField(Setup s, Map<String, Object> body, String field, String message, String label) throws Exception {
        Snap before = snap(s.clinic(), s.adm());
        JsonNode p = refused(s.clinic(), s.adm().id(), body);
        assertThat(fieldNames(p)).as(label).contains(field);
        assertThat(fieldMessage(p, field)).as(label).isEqualTo(message);
        assertThat(snap(s.clinic(), s.adm())).as(label + ": nothing written").isEqualTo(before);
    }

    private JsonNode accepted(Setup s, Map<String, Object> body, String label) throws Exception {
        JsonNode saved = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), s.clinic().owner(), js(body), 201);
        assertThat(saved.get("saved").get("replayed").asBoolean()).as(label).isFalse();
        return saved;
    }

    // ---------------- BP ----------------

    @Test
    void bpMustBeTwoWholeNumbersOfTwoOrThreeDigitsWithASlash() throws Exception {
        Setup s = setup();
        for (String bad : new String[] {"", "   ", "120", "120-80", "120/", "/80", "1/80", "120/8", "1200/80", "120/8000",
                "120 / 80", "120/ 80", "120 /80", "120/80/90", "abc", "12O/80", "120.5/80", "-120/80", "１２０/80",
                "120/80 mmHg"}) {
            refusedField(s, with(low(), "bp", bad), "bp", FORMAT, "bp '" + bad + "'");
        }
        refusedField(s, with(low(), "bp", null), "bp", FORMAT, "bp null");
        Map<String, Object> missing = low();
        missing.remove("bp");
        refusedField(s, missing, "bp", FORMAT, "bp missing");
    }

    @Test
    void bpRangeIsSystolic40To300AndDiastolic20To200WithBoundsIncluded() throws Exception {
        Setup s = setup();
        for (String bad : new String[] {"39/80", "301/80", "120/19", "120/201", "00/80", "99/10", "999/999", "12/80", "120/800"}) {
            refusedField(s, with(low(), "bp", bad), "bp", BP_RANGE, "bp " + bad);
        }
        for (String good : new String[] {"40/20", "300/200", "40/200", "300/20", "80/120", " 120/80 ", "090/060"}) {
            JsonNode saved = accepted(s, with(low(), "bp", good), "bp " + good);
            assertThat(saved.get("latest").get("bp").asText()).isEqualTo(good.strip().replaceAll("\\b0+(\\d)", "$1"));
        }
        // systolic at or below diastolic is not refused
        assertThat(accepted(s, with(low(), "bp", "60/70"), "60/70").get("latest").get("bpDiastolic").asInt()).isEqualTo(70);
    }

    // ---------------- pulse, SpO2, RR ----------------

    @Test
    void pulseSpo2AndRrAreWholeNumbersOfOneToThreeDigitsAboveZeroAndInsideTheirRange() throws Exception {
        Setup s = setup();
        Object[][] fields = {{"pulse", PULSE_RANGE, 300}, {"spo2", SPO2_RANGE, 100}, {"rr", RR_RANGE, 300}};
        for (Object[] f : fields) {
            String field = (String) f[0];
            for (String bad : new String[] {"", " ", "0", "00", "000", "-1", "+5", "80.5", "80,5", "1e2", "abc", "8 0",
                    "1000", "0x50", "８０", "٨٠"}) {
                refusedField(s, with(low(), field, bad), field, FORMAT, field + " '" + bad + "'");
            }
            refusedField(s, with(low(), field, null), field, FORMAT, field + " null");
            int max = (Integer) f[2];
            refusedField(s, with(low(), field, String.valueOf(max + 1)), field, (String) f[1], field + " " + (max + 1));
            // bounds, and a padded value that is stripped, and leading zeros that are not an error
            accepted(s, with(low(), field, "1"), field + " 1");
            accepted(s, with(low(), field, String.valueOf(max)), field + " " + max);
            accepted(s, with(low(), field, " 50 "), field + " ' 50 '");
            accepted(s, with(low(), field, "050"), field + " 050");
        }
    }

    @Test
    void aPulseSentAsTheStringEightyPointFiveOrAsAJsonNumberWithADecimalIsRefused() throws Exception {
        Setup s = setup();
        refusedField(s, with(low(), "pulse", "80.5"), "pulse", FORMAT, "string 80.5");
        Snap before = snap(s.clinic(), s.adm());
        String json = js(low()).replace("\"pulse\":\"80\"", "\"pulse\":80.5");
        assertThat(json).contains("\"pulse\":80.5");
        problem(saveRaw(s.clinic().owner(), s.adm().id(), json), 400);
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    // ---------------- temperature ----------------

    @Test
    void temperatureIsAWholeNumberOrOneDecimalBetween25AndFortyFive() throws Exception {
        Setup s = setup();
        for (String bad : new String[] {"", " ", "0", "0.0", "00.0", "37.55", "37.", ".5", "37,5", "370", "37.0C", "37.0 °C",
                "abc", "-37.0", "3 7", "1e1", "３７"}) {
            refusedField(s, with(low(), "temp", bad), "temp", FORMAT, "temp '" + bad + "'");
        }
        refusedField(s, with(low(), "temp", null), "temp", FORMAT, "temp null");
        for (String bad : new String[] {"24.9", "24", "45.1", "46", "98.6", "99", "5.0", "1", "10.5"}) {
            refusedField(s, with(low(), "temp", bad), "temp", TEMP_RANGE, "temp " + bad);
        }
        for (String good : new String[] {"25.0", "25", "45.0", "45", "37", "37.0", "36.6", " 38.5 ", "037.0".substring(1)}) {
            JsonNode saved = accepted(s, with(low(), "temp", good), "temp " + good);
            assertThat(saved.get("latest").get("tempC").decimalValue())
                    .as(good).isEqualByComparingTo(new java.math.BigDecimal(good.strip()));
        }
        // stored with one decimal
        assertThat(values(s.clinic(), "select temp_c::text from ipd.vitals where admission_id = ?::uuid order by recorded_at, id "
                + "limit 1", s.adm().id()).get(0)).isEqualTo("25.0");
    }

    // ---------------- all together ----------------

    @Test
    void everyFieldErrorIsReportedTogetherInTheOrderOfTheScreenAndTheDetailIsTheFirst() throws Exception {
        Setup s = setup();
        Map<String, Object> body = vit("x", "0", "101", "", "abc");
        body.put("note", "bad\u0007ZXQnote");
        Snap before = snap(s.clinic(), s.adm());
        JsonNode p = refused(s.clinic(), s.adm().id(), body);
        assertThat(p.get("status").asInt()).isEqualTo(400);
        assertThat(fieldNames(p)).containsExactly("bp", "pulse", "spo2", "temp", "rr", "note");
        assertThat(fieldMessage(p, "bp")).isEqualTo(FORMAT);
        assertThat(fieldMessage(p, "pulse")).isEqualTo(FORMAT);
        assertThat(fieldMessage(p, "spo2")).isEqualTo(SPO2_RANGE);
        assertThat(fieldMessage(p, "temp")).isEqualTo(FORMAT);
        assertThat(fieldMessage(p, "rr")).isEqualTo(FORMAT);
        assertThat(fieldMessage(p, "note")).isEqualTo(NOTE_CHARS);
        // the answer repeats no typed value
        assertThat(p.toString()).doesNotContain("ZXQ");
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    @Test
    void theDetailOfARefusalWithSeveralFieldErrorsIsTheFirstMessageAsTheContractSays() throws Exception {
        Setup s = setup();
        JsonNode p = refused(s.clinic(), s.adm().id(), vit("x", "0", "101", "", "abc"));
        assertThat(fieldNames(p)).containsExactly("bp", "pulse", "spo2", "temp", "rr");
        assertThat(p.get("detail").asText()).as("contract: detail is the first message").isEqualTo(FORMAT);
        // with one field error the detail is that message
        JsonNode one = refused(s.clinic(), s.adm().id(), with(low(), "spo2", "101"));
        assertThat(one.get("detail").asText()).isEqualTo(SPO2_RANGE);
    }

    @Test
    void aRangeErrorIsOnlyReportedForAFieldWhoseFormatIsRight() throws Exception {
        Setup s = setup();
        JsonNode p = refused(s.clinic(), s.adm().id(), vit("301/80", "abc", "101", "98.6", "0"));
        assertThat(fieldNames(p)).containsExactly("bp", "pulse", "spo2", "temp", "rr");
        assertThat(fieldMessage(p, "bp")).isEqualTo(BP_RANGE);
        assertThat(fieldMessage(p, "pulse")).isEqualTo(FORMAT);
        assertThat(fieldMessage(p, "spo2")).isEqualTo(SPO2_RANGE);
        assertThat(fieldMessage(p, "temp")).isEqualTo(TEMP_RANGE);
        assertThat(fieldMessage(p, "rr")).isEqualTo(FORMAT);
    }

    @Test
    void aClientKeyThatIsNotInTheCanonicalFormOfAUuidIsRefused() throws Exception {
        Setup s = setup();
        Snap before = snap(s.clinic(), s.adm());
        // java.util.UUID.fromString accepts the first two (short groups) although they are not UUIDs; the contract
        // says "not a UUID" is a 400 on clientKey
        for (String key : new String[] {"1-1-1-1-1", "12345678-1234-1234-1234-12345678901"}) {
            JsonNode p = refused(s.clinic(), s.adm().id(), with(low(), "clientKey", key));
            assertThat(fieldNames(p)).as("key " + key).containsExactly("clientKey");
            assertThat(fieldMessage(p, "clientKey")).isEqualTo(KEY_MESSAGE);
        }
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    // ---------------- the client key and the body as a whole ----------------

    @Test
    void aMissingOrBadClientKeyIsRefusedOnTheFieldClientKeyBeforeAnythingElse() throws Exception {
        Setup s = setup();
        Snap before = snap(s.clinic(), s.adm());
        Object[] keys = {null, "", " ", "not-a-uuid", "{}", "12345678-1234-1234-1234-1234567890zz"};
        for (Object key : keys) {
            JsonNode p = refused(s.clinic(), s.adm().id(), with(vit("x", "x", "x", "x", "x"), "clientKey", key));
            assertThat(fieldNames(p)).as("key " + key).containsExactly("clientKey");
            assertThat(fieldMessage(p, "clientKey")).isEqualTo(KEY_MESSAGE);
        }
        Map<String, Object> noKey = low();
        noKey.remove("clientKey");
        assertThat(fieldNames(refused(s.clinic(), s.adm().id(), noKey))).containsExactly("clientKey");
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    @Test
    void anEmptyOrMalformedBodyIsA400ProblemAndNeverA500() throws Exception {
        Setup s = setup();
        Snap before = snap(s.clinic(), s.adm());
        for (String json : new String[] {"{}", "[]", "null", "{\"clientKey\":", "not json", "\"text\""}) {
            JsonNode p = problem(saveRaw(s.clinic().owner(), s.adm().id(), json), 400);
            assertThat(p.get("title")).isNotNull();
        }
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    @Test
    void theClientNeverSendsAScoreAnythingItSendsForTheScoreTheTimeOrTheClinicIsIgnored() throws Exception {
        Setup s = setup();
        Clinic other = hospital();
        Map<String, Object> body = low();
        body.put("ewsScore", 15);
        body.put("ewsRisk", "HIGH");
        body.put("recordedAt", "2020-01-01T00:00:00Z");
        body.put("recordedBy", other.ownerUserId().toString());
        body.put("tenantId", other.tenantId().toString());
        body.put("admissionId", s.adm().id());
        JsonNode saved = accepted(s, body, "extra properties");
        assertThat(saved.get("saved").get("ewsScore").asInt()).isZero();
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("LOW");
        assertThat(escalationCount(s.clinic(), s.adm().id())).isZero();
        assertThat(instant(saved.get("saved").get("recordedAt"))).isAfter(java.time.Instant.now().minusSeconds(300));
        assertThat(values(s.clinic(), "select recorded_by::text from ipd.vitals where admission_id = ?::uuid", s.adm().id()))
                .containsExactly(s.clinic().ownerUserId().toString());
        assertThat(rows(other, "vitals")).isZero();
    }

    // ---------------- the note ----------------

    @Test
    void anEmptyNoteMeansNoNoteAndIsNotStored() throws Exception {
        Setup s = setup();
        Object[] empties = {null, "", "   ", "\n\n", " \r\n \r\n "};
        long expectedVitals = 0;
        for (Object note : empties) {
            JsonNode saved = accepted(s, with(low(), "note", note), "note " + note);
            expectedVitals++;
            assertThat(saved.get("saved").get("noteAdded").asBoolean()).as("note " + note).isFalse();
            assertThat(saved.get("recentNotes")).isEmpty();
        }
        Map<String, Object> noField = low();
        noField.remove("note");
        assertThat(accepted(s, noField, "no note field").get("saved").get("noteAdded").asBoolean()).isFalse();
        expectedVitals++;
        assertThat(noteCount(s.clinic(), s.adm().id())).isZero();
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isEqualTo(expectedVitals);
        assertThat(count(s.clinic(), "select count(*) from ipd.vitals where note_added")).isZero();
    }

    @Test
    void aNoteOfOnlyNoBreakSpacesAndOtherSpaceSeparatorsMeansNoNoteAndTheVitalsAreSaved() throws Exception {
        // ADR-007 R6 (code review 3): the same stripping as the plan; an all-space note is "no note", not an error
        Setup s = setup();
        String[] blanks = {"\u00A0", "\u2007", "\u202F", "\u3000", "\u00A0\u2007\u202F\u3000", "\u00A0".repeat(900),
                " \u00A0\n\u202F\r\n\u2007 ", "\u2003\u2009\u200A\u205F\u1680"};
        long expectedVitals = 0;
        for (String blank : blanks) {
            JsonNode saved = accepted(s, with(low(), "note", blank), "blank note U+" + Integer.toHexString(blank.codePointAt(0)));
            expectedVitals++;
            assertThat(saved.get("saved").get("noteAdded").asBoolean()).as("noteAdded for U+" + Integer.toHexString(blank.codePointAt(0)))
                    .isFalse();
            assertThat(saved.get("recentNotes")).isEmpty();
        }
        assertThat(noteCount(s.clinic(), s.adm().id())).isZero();
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isEqualTo(expectedVitals);
        assertThat(count(s.clinic(), "select count(*) from ipd.vitals where note_added")).isZero();
        // a real word between the spaces is a note: stored without the outer spaces, the inner no-break space kept
        JsonNode saved = accepted(s, noted(low(), "\u00A0\u202FDrank water\u00A0again\u3000"), "padded note");
        assertThat(saved.get("saved").get("noteAdded").asBoolean()).isTrue();
        assertThat(values(s.clinic(), "select note from ipd.nursing_note where admission_id = ?::uuid", s.adm().id()))
                .containsExactly("Drank water\u00A0again");
    }

    @Test
    void aNoteIsStoredStrippedWithTheSameUserAndTimeAndShownFirstInRecentNotes() throws Exception {
        Setup s = setup();
        JsonNode saved = accepted(s, noted(low(), "  Patient sleeping, drank water  "), "note");
        assertThat(saved.get("saved").get("noteAdded").asBoolean()).isTrue();
        JsonNode first = saved.get("recentNotes").get(0);
        assertThat(first.get("text").asText()).isEqualTo("Patient sleeping, drank water");
        assertThat(first.get("recordedByName").asText()).isNotBlank();
        assertThat(instant(first.get("recordedAt"))).isEqualTo(instant(saved.get("saved").get("recordedAt")));
        assertThat(values(s.clinic(), "select note || '|' || recorded_by from ipd.nursing_note where admission_id = ?::uuid",
                s.adm().id())).containsExactly("Patient sleeping, drank water|" + s.clinic().ownerUserId());
        assertThat(values(s.clinic(), "select note_added::text from ipd.vitals where admission_id = ?::uuid", s.adm().id()))
                .containsExactly("true");
    }

    @Test
    void aLineBreakIsAllowedInTheNoteAndStoredAsLfWhateverTheClientSent() throws Exception {
        Setup s = setup();
        JsonNode saved = accepted(s, noted(low(), "line1\r\nline2\rline3\nline4"), "crlf");
        assertThat(saved.get("recentNotes").get(0).get("text").asText()).isEqualTo("line1\nline2\nline3\nline4");
        String stored = values(s.clinic(), "select note from ipd.nursing_note where admission_id = ?::uuid", s.adm().id()).get(0);
        assertThat(stored).isEqualTo("line1\nline2\nline3\nline4").doesNotContain("\r");
    }

    @Test
    void theNoteLimitIsAThousandUnicodeCodePointsAndALineBreakCountsAsOne() throws Exception {
        Setup s = setup();
        Object[][] fine = {
                {"a".repeat(1000), "1000 letters"},
                {EMOJI.repeat(1000), "1000 emoji (2000 UTF-16 units)"},
                {"  " + "a".repeat(1000) + "  ", "1000 letters with padding that is stripped"},
                {"a\r\n".repeat(499) + "ab", "CRLF counts as one (1499 raw characters)"},
                {"é".repeat(600), "decomposed letters count after NFC (1200 raw code points)"}};
        for (Object[] f : fine) {
            JsonNode saved = accepted(s, noted(low(), (String) f[0]), (String) f[1]);
            assertThat(saved.get("saved").get("noteAdded").asBoolean()).as((String) f[1]).isTrue();
        }
        // the decomposed letters were composed (NFC) before they were stored
        assertThat(values(s.clinic(), "select note from ipd.nursing_note where note like 'é%' limit 1").get(0))
                .isEqualTo("é".repeat(600));
        String[][] tooLong = {{"a".repeat(1001), "1001 letters"}, {EMOJI.repeat(1001), "1001 emoji"},
                {"a\r\n".repeat(500) + "ab", "1002 with CRLF counted once"}};
        for (String[] t : tooLong) {
            refusedField(s, noted(low(), t[0]), "note", NOTE_LONG, t[1]);
        }
    }

    @Test
    void aNoteWithAControlBidiOrInvisibleCharacterIsRefused() throws Exception {
        Setup s = setup();
        String[] bad = {"a\u0007b", "a\u0000b", "a\u007fb", "a\u0085b", "a​b", "a‎b", "a‮b", "a⁦b",
                "a\u2028b", "a\u2029b", "a﻿b", "a­b", "a⁠b", "a️b", "a͏b", "‮alert", "a\u001bb"};
        for (String note : bad) {
            refusedField(s, noted(low(), note), "note", NOTE_CHARS, "note with U+" + Integer.toHexString(note.codePointAt(
                    note.length() > 1 ? 1 : 0)));
        }
        // a lone CR or LF or CRLF is the one allowed control character; it is not refused when the rest is fine
        accepted(s, noted(low(), "one\ntwo"), "LF");
        // a tab next to a refused character does not hide it
        refusedField(s, noted(low(), "a\t\u0007b"), "note", NOTE_CHARS, "tab beside a control character");
    }

    @Test
    void aTabInTheNoteIsStoredAsASpaceAndIsNotRefused() throws Exception {
        // code review N7: a pasted tab becomes one space before the character check
        Setup s = setup();
        JsonNode saved = accepted(s, noted(low(), "temp\tchart\tchecked"), "tab inside");
        assertThat(saved.get("recentNotes").get(0).get("text").asText()).isEqualTo("temp chart checked");
        assertThat(values(s.clinic(), "select note from ipd.nursing_note where admission_id = ?::uuid order by recorded_at desc limit 1",
                s.adm().id())).containsExactly("temp chart checked");
        // a tab at either end is padding like a space and is stripped; a tab alone is no note
        JsonNode padded = accepted(s, noted(low(), "\tPadded by tabs\t"), "tab padding");
        assertThat(padded.get("recentNotes").get(0).get("text").asText()).isEqualTo("Padded by tabs");
        JsonNode alone = accepted(s, noted(low(), "\t\t"), "tabs only");
        assertThat(alone.get("saved").get("noteAdded").asBoolean()).isFalse();
    }

    @Test
    void aRawNoteOverFourThousandUtf16UnitsIsRefusedAsTooLongBeforeAnyOtherCheck() throws Exception {
        // security review L8: the raw length is checked first, so Unicode normalisation never runs on a huge string
        Setup s = setup();
        // exactly 4,000 raw units, 1,000 characters after stripping the padding: accepted
        JsonNode saved = accepted(s, noted(low(), " ".repeat(3000) + "a".repeat(1000)), "4000 raw units");
        assertThat(saved.get("saved").get("noteAdded").asBoolean()).isTrue();
        // 4,001 raw units: refused although stripping would leave only 1,000 characters
        refusedField(s, noted(low(), " ".repeat(3001) + "a".repeat(1000)), "note", NOTE_LONG, "4001 raw units");
        // the length message wins over the character message because it is checked before normalisation
        refusedField(s, noted(low(), "a\u0007" + "b".repeat(4000)), "note", NOTE_LONG, "4002 raw units with a control character");
        refusedField(s, noted(low(), EMOJI.repeat(2001)), "note", NOTE_LONG, "4002 UTF-16 units of emoji");
        // 1,001 characters, well below the raw limit, keep the same message
        refusedField(s, noted(low(), "a".repeat(1001)), "note", NOTE_LONG, "1001 characters");
    }

    @Test
    void aJoinerIsAllowedInTheNoteOnlyDirectlyAfterAViramaAndBeforeALetter() throws Exception {
        Setup s = setup();
        String zwj = "‍";
        String zwnj = "‌";
        String ok1 = "क्" + zwj + "ष ताप";
        String ok2 = "क्" + zwnj + "ष पेशंट";
        accepted(s, noted(low(), ok1), "ZWJ after virama");
        accepted(s, noted(low(), ok2), "ZWNJ after virama");
        assertThat(values(s.clinic(), "select note from ipd.nursing_note where note = ?", ok1)).containsExactly(ok1);
        assertThat(values(s.clinic(), "select note from ipd.nursing_note where note = ?", ok2)).containsExactly(ok2);
        String[] bad = {"a" + zwj + "b", "a" + zwnj + "b", zwj + "ab", "ab" + zwj, "क्" + zwj, "क्" + zwj + zwj
                + "ष", "क्" + zwj + " ष", "क्" + zwj + "\nष", "IC" + zwj + "U"};
        for (String note : bad) {
            refusedField(s, noted(low(), note), "note", NOTE_CHARS, "joiner in " + note.length() + " chars");
        }
    }

    @Test
    void theThreeRecentNotesAreTheNewestFirstAndOlderOnesAreLeftOut() throws Exception {
        Setup s = setup();
        for (int i = 1; i <= 5; i++) {
            save(s.clinic(), s.adm().id(), noted(low(), "Note number " + i));
        }
        JsonNode sheet = nurseSheet(s.clinic(), s.adm().id());
        JsonNode notes = sheet.get("recentNotes");
        assertThat(notes).hasSize(3);
        assertThat(notes.get(0).get("text").asText()).isEqualTo("Note number 5");
        assertThat(notes.get(1).get("text").asText()).isEqualTo("Note number 4");
        assertThat(notes.get(2).get("text").asText()).isEqualTo("Note number 3");
        assertThat(noteCount(s.clinic(), s.adm().id())).isEqualTo(5);
    }

    // ---------------- AC6: the score table at each band edge ----------------

    /** Parameter, value (text as typed) and the score of that parameter alone; everything else is a normal reading. */
    private static final Object[][] EDGES = {
            {"spo2", "91", 3}, {"spo2", "92", 2}, {"spo2", "93", 2}, {"spo2", "94", 1}, {"spo2", "95", 1}, {"spo2", "96", 0},
            {"spo2", "100", 0}, {"spo2", "1", 3},
            {"rr", "8", 3}, {"rr", "9", 1}, {"rr", "11", 1}, {"rr", "12", 0}, {"rr", "20", 0}, {"rr", "21", 2}, {"rr", "24", 2},
            {"rr", "25", 3}, {"rr", "1", 3}, {"rr", "300", 3},
            {"pulse", "40", 3}, {"pulse", "41", 1}, {"pulse", "50", 1}, {"pulse", "51", 0}, {"pulse", "90", 0},
            {"pulse", "91", 1}, {"pulse", "110", 1}, {"pulse", "111", 2}, {"pulse", "130", 2}, {"pulse", "131", 3},
            {"pulse", "1", 3}, {"pulse", "300", 3},
            {"temp", "35.0", 3}, {"temp", "35.1", 1}, {"temp", "36.0", 1}, {"temp", "36.1", 0}, {"temp", "38.0", 0},
            {"temp", "38.1", 1}, {"temp", "39.0", 1}, {"temp", "39.1", 2}, {"temp", "25.0", 3}, {"temp", "45.0", 2},
            {"temp", "35", 3}, {"temp", "36", 1}, {"temp", "37", 0}, {"temp", "39", 1},
            {"bp", "90/80", 3}, {"bp", "91/80", 2}, {"bp", "100/80", 2}, {"bp", "101/80", 1}, {"bp", "110/80", 1},
            {"bp", "111/80", 0}, {"bp", "219/80", 0}, {"bp", "220/80", 3}, {"bp", "300/80", 3}, {"bp", "40/20", 3},
            // the diastolic value is stored but never scored
            {"bp", "120/20", 0}, {"bp", "120/200", 0}, {"bp", "120/119", 0}};

    @Test
    void eachBandEdgeOfEachParameterGivesTheScoreOfTheStoryThroughTheApi() throws Exception {
        Setup s = setup();
        for (Object[] e : EDGES) {
            String field = (String) e[0];
            String value = (String) e[1];
            int expected = (Integer) e[2];
            JsonNode saved = accepted(s, with(low(), field, value), field + " " + value);
            assertThat(saved.get("saved").get("ewsScore").asInt()).as(field + " " + value).isEqualTo(expected);
            assertThat(saved.get("saved").get("ewsRisk").asText()).as(field + " " + value).isEqualTo(riskOf(expected, expected == 3));
            assertThat(saved.get("latest").get("ewsScore").asInt()).isEqualTo(expected);
        }
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isEqualTo(EDGES.length);
        assertThat(count(s.clinic(), "select count(*) from ipd.vitals where ews_score = 3 and ews_risk = 'MEDIUM' "
                + "and admission_id = ?::uuid", s.adm().id())).isPositive();
    }

    @Test
    void theTotalAndTheRiskRuleGiveLowMediumAndHighAtTheirEdges() throws Exception {
        Setup s = setup();
        // {body, total, risk}
        Object[][] cases = {
                {low(), 0, "LOW"},
                // 2 + 1 = 3, none at 3: Low
                {vit("120/80", "95", "93", "37.0", "16"), 3, "LOW"},
                // 2 + 2 = 4: Low (the largest Low total without a parameter at 3)
                {vit("120/80", "80", "93", "37.0", "22"), 4, "LOW"},
                // 3 alone: Medium by the "any one parameter at 3" rule
                {medium3(), 3, "MEDIUM"},
                // 2 + 2 + 1 = 5: Medium by the total
                {medium5(), 5, "MEDIUM"},
                // 3 + 2 + 1 = 6: Medium
                {medium6(), 6, "MEDIUM"},
                // 3 + 2 + 2 = 7: High
                {high7(), 7, "HIGH"},
                // 2 + 2 + 2 + 1 = 7 with no parameter at 3: High
                {vit("105/80", "115", "93", "39.1", "16"), 7, "HIGH"},
                // 2 + 2 + 1 + 1 = 6 with no parameter at 3: Medium
                {vit("105/80", "95", "93", "39.1", "16"), 6, "MEDIUM"},
                {high14(), 14, "HIGH"},
                // everything at 3
                {vit("90/60", "131", "91", "35.0", "25"), 15, "HIGH"},
                {vit("40/20", "1", "1", "25.0", "1"), 15, "HIGH"}};
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) c[0];
            JsonNode saved = accepted(s, body, "total " + c[1]);
            assertThat(saved.get("saved").get("ewsScore").asInt()).as(body.toString()).isEqualTo((Integer) c[1]);
            assertThat(saved.get("saved").get("ewsRisk").asText()).as(body.toString()).isEqualTo(c[2]);
        }
        // stored the same way, and the database accepted every one (its own checks agree with the rule)
        List<String> stored = values(s.clinic(), "select string_agg(ews_score || ews_risk, ',' order by recorded_at, id) "
                + "from ipd.vitals where admission_id = ?::uuid", s.adm().id());
        assertThat(stored.get(0)).startsWith("0LOW,3LOW,4LOW,3MEDIUM,5MEDIUM,6MEDIUM,7HIGH,7HIGH,6MEDIUM,14HIGH,15HIGH,15HIGH");
    }

    @Test
    void theScoreIsTheSameWhenTheSameValuesAreTypedWithPaddingOrLeadingZeros() throws Exception {
        Setup s = setup();
        Map<String, Object> padded = vit(" 120/80 ", " 095 ", " 91 ", " 37 ", " 22 ");
        JsonNode saved = accepted(s, padded, "padded");
        assertThat(saved.get("saved").get("ewsScore").asInt()).isEqualTo(6);
        assertThat(saved.get("latest").get("pulse").asInt()).isEqualTo(95);
        assertThat(saved.get("latest").get("tempC").decimalValue()).isEqualByComparingTo("37.0");
    }

    @Test
    void aRefusedBodyAnswersJsonProblemDetailsAndNothingElse() throws Exception {
        Setup s = setup();
        Map<String, Object> m = new LinkedHashMap<>(low());
        m.put("bp", "x");
        saveCall(s.clinic().owner(), s.adm().id(), m).andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("application/problem+json"));
    }
}
