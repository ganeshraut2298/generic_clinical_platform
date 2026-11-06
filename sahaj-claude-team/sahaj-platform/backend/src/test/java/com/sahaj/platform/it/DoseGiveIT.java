package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-32 giving a dose and recording that it was not given ({@code POST /api/v1/ipd/medicine-orders/{id}/doses}, AC4 to AC9,
 * AC11, AC13) on the real {@code sahaj_test} database, with a medicine that is not high-alert (the second nurse is in
 * {@link DoseSecondNurseIT}): the happy paths with everything they write, every refusal in the order of the contract and the
 * proof that it writes nothing, the wristband normalisation and the deny entry, the order and slot rules at the 60-minute
 * edges (clock seam of {@link MarSupport}), replays and reused keys, roles, Read-only and a discharged patient, and that a
 * recorded dose cannot be changed or removed. Test plan: docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class DoseGiveIT extends MarSupport {

    private record Fix(Ward w, String order) {
    }

    /** A ward with one ACTIVE order of this medicine (verified the day before), the clock at 8:05 AM IST. */
    private Fix fix(String drug, String frequency) throws Exception {
        Ward w = ward();
        String order = activeOrder(w, drug, frequency);
        clockAt(8, 5);
        return new Fix(w, order);
    }

    private static String otherUhid(String uhid) {
        char last = uhid.charAt(uhid.length() - 1);
        return uhid.substring(0, uhid.length() - 1) + (char) (last == '9' ? '8' : last + 1);
    }

    private static String fullWidth(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            b.append(c >= 0x21 && c <= 0x7E ? (char) (c + 0xFEE0) : c);
        }
        return b.toString();
    }

    private JsonNode auditAfter(Clinic clinic, String action, int index) throws Exception {
        return objectMapper.readTree(audit(clinic, action).get(index)[3]);
    }

    private static String eventOf(List<String> lines, String kind) {
        for (String l : lines) {
            if (l.startsWith(kind + "|")) {
                return l;
            }
        }
        return null;
    }

    // ---------------- Given (AC7) ----------------

    @Test
    void aNurseGivesANonHighAlertDoseAndEverythingTheAcceptanceCriterionNamesIsWrittenInOneStep() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        DSnap before = dsnap(w);
        Map<String, Object> body = given(w, ist(8, 0));
        JsonNode a = doseOk(w.nurse().auth(), f.order(), body);

        assertThat(keys(a)).containsExactlyInAnyOrder("replayed", "dose", "mar");
        JsonNode dose = a.get("dose");
        assertThat(keys(dose)).containsExactlyInAnyOrder("id", "slotAt", "outcome", "reason", "recordedAt", "recordedByName",
                "secondCheckByName");
        assertThat(dose.get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(dose.get("reason").isNull()).isTrue();
        assertThat(Instant.parse(dose.get("slotAt").asText())).isEqualTo(ist(8, 0));
        assertThat(dose.get("recordedByName").asText()).isEqualTo(displayName(w.nurse()));
        assertThat(dose.get("secondCheckByName").isNull()).isTrue();
        assertThat(Math.abs(Instant.parse(dose.get("recordedAt").asText()).getEpochSecond() - Instant.now().getEpochSecond()))
                .as("recordedAt is the database clock of the insert").isLessThan(120);
        JsonNode slot = slot(a.get("mar"), f.order(), 0);
        assertThat(slot.get("state").asText()).isEqualTo("GIVEN");
        assertThat(slot.get("canGive").asBoolean()).isFalse();
        assertThat(slot.get("dose").get("id").asText()).isEqualTo(dose.get("id").asText());
        assertThat(a.get("mar").get("doseCount").asInt()).isZero();

        DoseDb row = doseDb(w.clinic(), f.order(), ist(8, 0));
        assertThat(row.id()).isEqualTo(dose.get("id").asText());
        assertThat(row.outcome()).isEqualTo("GIVEN");
        assertThat(row.reason()).isEmpty();
        assertThat(row.recordedBy()).isEqualTo(w.nurse().userId().toString());
        assertThat(row.secondCheckBy()).isEmpty();
        assertThat(row.highAlert()).isFalse();
        assertThat(row.scan()).isEqualTo(w.uhid());
        assertThat(row.confirmed()).isEqualTo("true");
        assertThat(row.clientKey()).isEqualTo(body.get("clientKey"));
        assertThat(row.recordedAtIsDbClock()).isTrue();

        List<String> events = eventLines(w.clinic(), w.adm().id());
        assertThat(events).contains("give|Gave Tab. Paracetamol 650 mg (8 AM dose) · wristband scanned");
        assertThat(giveEvents(w.clinic(), w.adm().id())).isOne();
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_GIVEN");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[0]).isEqualTo("IPD_DOSE");
        assertThat(audits.get(0)[1]).isEqualTo(row.id());
        assertThat(audits.get(0)[2]).isNull();
        assertThat(audits.get(0)[4]).isEqualTo(w.nurse().userId().toString());
        JsonNode after = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 0);
        assertThat(keys(after)).containsExactlyInAnyOrder("doseId", "orderId", "admissionId", "drugKey", "slotAt", "outcome",
                "highAlert", "secondCheckUserId");
        assertThat(after.get("doseId").asText()).isEqualTo(row.id());
        assertThat(after.get("orderId").asText()).isEqualTo(f.order());
        assertThat(after.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(after.get("drugKey").asText()).isEqualTo("pcm");
        assertThat(Instant.parse(after.get("slotAt").asText())).isEqualTo(ist(8, 0));
        assertThat(after.get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(after.get("highAlert").asBoolean()).isFalse();
        assertThat(after.get("secondCheckUserId").isNull()).isTrue();

        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses() + 1);
        assertThat(now.events()).isEqualTo(before.events() + 1);
        assertThat(now.audits()).isEqualTo(before.audits() + 1);
        assertThat(now.access()).as("a 201 writes no record-access row").isEqualTo(before.access());
        assertThat(now.pins()).isEqualTo(before.pins());
    }

    @Test
    void theOwnerMayGiveToo() throws Exception {
        Fix f = fix("pcm", "od");
        JsonNode a = doseOk(f.w().clinic().owner(), f.order(), given(f.w(), ist(8, 0)));
        assertThat(a.get("dose").get("recordedByName").asText()).isEqualTo(ownerStaff(f.w().clinic()).name());
        assertThat(doseDb(f.w().clinic(), f.order(), ist(8, 0)).recordedBy()).isEqualTo(f.w().clinic().ownerUserId().toString());
    }

    @Test
    void theDoseTimeInTheEventFollowsTheSlotAndTheOffsetFormOfTheSameInstantIsAccepted() throws Exception {
        Ward w = ward();
        String q8 = activeOrder(w, "ceft", "q8");
        clockAt(22, 10);
        doseOk(w.nurse().auth(), q8, given(w, ist(6, 0)));
        doseOk(w.nurse().auth(), q8, notGiven(ist(14, 0), "AWAY_FROM_WARD"));
        Map<String, Object> offset = given(w, ist(22, 0));
        offset.put("slotAt", day + "T22:00:00+05:30");
        doseOk(w.nurse().auth(), q8, offset);
        List<String> events = eventLines(w.clinic(), w.adm().id());
        assertThat(events).contains("give|Gave Inj. Ceftriaxone 1 g (6 AM dose) · wristband scanned",
                "give|Did not give Inj. Ceftriaxone 1 g (2 PM dose) · Patient away from the ward",
                "give|Gave Inj. Ceftriaxone 1 g (10 PM dose) · wristband scanned");
        assertThat(doseDb(w.clinic(), q8, ist(22, 0)).outcome()).isEqualTo("GIVEN");
    }

    @Test
    void unknownPropertiesInTheBodyAreIgnored() throws Exception {
        Fix f = fix("pcm", "od");
        Map<String, Object> body = given(f.w(), ist(8, 0));
        body.put("recordedBy", UUID.randomUUID().toString());
        body.put("tenantId", UUID.randomUUID().toString());
        body.put("recordedAt", "2020-01-01T00:00:00Z");
        body.put("highAlert", true);
        doseOk(f.w().nurse().auth(), f.order(), body);
        DoseDb row = doseDb(f.w().clinic(), f.order(), ist(8, 0));
        assertThat(row.recordedBy()).as("the caller is always the recorder").isEqualTo(f.w().nurse().userId().toString());
        assertThat(row.highAlert()).isFalse();
        assertThat(row.recordedAtIsDbClock()).isTrue();
    }

    // ---------------- Record not given (AC8) ----------------

    @Test
    void notGivenIsRefusedForPatientRefusedAndHeldForTheOtherFourReasonsWithTheEntryTheAuditAndNoBedsideFields() throws Exception {
        Ward w = ward();
        String[] drugs = {"pcm", "panto", "ondan", "ceft", "cotri"};
        String[] names = {"Tab. Paracetamol 650 mg", "Inj. Pantoprazole 40 mg", "Inj. Ondansetron 4 mg", "Inj. Ceftriaxone 1 g",
                "Tab. Cotrimoxazole 960 mg"};
        String[] orders = new String[5];
        for (int i = 0; i < 5; i++) {
            orders[i] = activeOrder(w, drugs[i], "od");
        }
        clockAt(8, 5);
        DSnap before = dsnap(w);
        for (int i = 0; i < 5; i++) {
            String reason = REASONS[i][0];
            JsonNode a = doseOk(w.nurse().auth(), orders[i], notGiven(ist(8, 0), reason));
            String stored = i == 0 ? "REFUSED" : "HELD";
            assertThat(a.get("dose").get("outcome").asText()).as(reason).isEqualTo(stored);
            assertThat(a.get("dose").get("reason").get("key").asText()).isEqualTo(reason);
            assertThat(a.get("dose").get("reason").get("label").asText()).isEqualTo(REASONS[i][1]);
            assertThat(a.get("dose").get("secondCheckByName").isNull()).isTrue();
            assertThat(slot(a.get("mar"), orders[i], 0).get("state").asText()).isEqualTo(stored);
            DoseDb row = doseDb(w.clinic(), orders[i], ist(8, 0));
            assertThat(row.outcome()).isEqualTo(stored);
            assertThat(row.reason()).isEqualTo(reason);
            assertThat(row.scan()).as("no scan for a dose not given").isEmpty();
            assertThat(row.confirmed()).isEmpty();
            assertThat(row.secondCheckBy()).isEmpty();
            assertThat(row.highAlert()).isFalse();
            assertThat(eventLines(w.clinic(), w.adm().id()))
                    .contains("give|Did not give " + names[i] + " (8 AM dose) · " + REASONS[i][1]);
            JsonNode after = null;
            for (int k = 0; k < audit(w.clinic(), "IPD_DOSE_NOT_GIVEN").size(); k++) {
                JsonNode candidate = auditAfter(w.clinic(), "IPD_DOSE_NOT_GIVEN", k);
                if (row.id().equals(candidate.get("doseId").asText())) {
                    after = candidate;
                }
            }
            assertThat(after).as("audit row of " + reason).isNotNull();
            assertThat(keys(after)).containsExactlyInAnyOrder("doseId", "orderId", "admissionId", "drugKey", "slotAt", "outcome",
                    "reasonKey");
            assertThat(after.get("outcome").asText()).isEqualTo(stored);
            assertThat(after.get("reasonKey").asText()).isEqualTo(reason);
            assertThat(after.get("drugKey").asText()).isEqualTo(drugs[i]);
        }
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses() + 5);
        assertThat(now.events()).isEqualTo(before.events() + 5);
        assertThat(now.audits()).isEqualTo(before.audits() + 5);
        assertThat(now.access()).isEqualTo(before.access());
        assertThat(audits(w.clinic(), "IPD_DOSE_GIVEN")).isZero();
    }

    @Test
    void notGivenNeedsNoWristbandNoStatementAndNoSecondNurseAndIgnoresWhatIsSentForThem() throws Exception {
        Ward w = ward();
        String pcm = activeOrder(w, "pcm", "od");
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        Map<String, Object> body = notGiven(ist(8, 0), "HELD_DOCTOR");
        body.put("wristband", "TOTALLY-WRONG");
        body.put("identityConfirmed", false);
        body.put("secondNurseId", UUID.randomUUID().toString());
        body.put("secondNursePin", "xx");
        doseOk(w.nurse().auth(), pcm, body);
        assertThat(denyEvents(w.clinic(), w.adm().id())).as("the wristband is not looked at").isZero();
        // a high-alert medicine not given: no second nurse, no PIN, stored with the high-alert flag
        doseOk(w.nurse().auth(), hep, notGiven(ist(8, 0), "NIL_BY_MOUTH"));
        DoseDb row = doseDb(w.clinic(), hep, ist(8, 0));
        assertThat(row.highAlert()).isTrue();
        assertThat(row.secondCheckBy()).isEmpty();
        assertThat(row.outcome()).isEqualTo("HELD");
        assertThat(colleagueAudits(w.clinic())).isZero();
    }

    @Test
    void aMissingOrUnknownReasonIs400ChooseWhyAndNothingIsWritten() throws Exception {
        Fix f = fix("pcm", "od");
        DSnap before = dsnap(f.w());
        Map<String, Object> base = notGiven(ist(8, 0), "NIL_BY_MOUTH");
        List<Map<String, Object>> bodies = new ArrayList<>();
        bodies.add(without(base, "reasonKey"));
        bodies.add(set(base, "reasonKey", null));
        bodies.add(set(base, "reasonKey", ""));
        bodies.add(set(base, "reasonKey", "OTHER"));
        bodies.add(set(base, "reasonKey", "nil_by_mouth"));
        bodies.add(set(base, "reasonKey", "Nil by mouth"));
        bodies.add(set(base, "reasonKey", 7));
        for (Map<String, Object> b : bodies) {
            invalid(doseCall(f.w().nurse().auth(), f.order(), set(b, "clientKey", newKey())), "reasonKey", CHOOSE_REASON);
        }
        assertThat(dsnap(f.w())).isEqualTo(before);
    }

    // ---------------- the 60-minute window (AC3, AC4) ----------------

    @Test
    void aSlotIsGivenFromExactlySixtyMinutesBeforeAndUntilAnyTimeAfterAndRefusedAt61MinutesAhead() throws Exception {
        Ward w = ward();
        String[] drugs = {"pcm", "panto", "ondan", "ceft", "cotri", "met"};
        String[] o = new String[6];
        for (int i = 0; i < 6; i++) {
            o[i] = activeOrder(w, drugs[i], "od");
        }
        // 61 minutes ahead (06:59:00): not due, for Given and for Not given
        clockAt(ist(6, 59, 0));
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), o[0], given(w, ist(8, 0))), 409, "dose-not-due", NOT_DUE);
        refused(doseCall(w.nurse().auth(), o[0], notGiven(ist(8, 0), "HELD_DOCTOR")), 409, "dose-not-due", NOT_DUE);
        assertThat(dsnap(w)).as("a refusal writes nothing, not even a deny entry").isEqualTo(before);
        // 60 minutes 59 seconds ahead rounds down to 60: due
        clockAt(ist(6, 59, 1));
        doseOk(w.nurse().auth(), o[0], given(w, ist(8, 0)));
        // exactly 60 minutes ahead: due
        clockAt(ist(7, 0, 0));
        doseOk(w.nurse().auth(), o[1], given(w, ist(8, 0)));
        // exactly 60 minutes after and 60 min 59 s after: still due; 61 minutes after: late, and a late dose can be given
        clockAt(ist(9, 0, 0));
        doseOk(w.nurse().auth(), o[2], given(w, ist(8, 0)));
        clockAt(ist(9, 0, 59));
        doseOk(w.nurse().auth(), o[3], given(w, ist(8, 0)));
        clockAt(ist(9, 1, 0));
        doseOk(w.nurse().auth(), o[4], given(w, ist(8, 0)));
        clockAt(ist(23, 59, 0));
        doseOk(w.nurse().auth(), o[5], notGiven(ist(8, 0), "NOT_AVAILABLE"));
        assertThat(doseRows(w.clinic(), w.adm().id())).isEqualTo(6);
    }

    @Test
    void aSlotLaterTodayIsNotDueAndTheSecondSlotOfTheDayIsJudgedOnItsOwnTime() throws Exception {
        Ward w = ward();
        String tds = activeOrder(w, "ondan", "tds");
        clockAt(8, 5);
        refused(doseCall(w.nurse().auth(), tds, given(w, ist(14, 0))), 409, "dose-not-due", NOT_DUE);
        refused(doseCall(w.nurse().auth(), tds, given(w, ist(20, 0))), 409, "dose-not-due", NOT_DUE);
        doseOk(w.nurse().auth(), tds, given(w, ist(8, 0)));
        clockAt(13, 0);
        doseOk(w.nurse().auth(), tds, given(w, ist(14, 0)));
        refused(doseCall(w.nurse().auth(), tds, given(w, ist(20, 0))), 409, "dose-not-due", NOT_DUE);
        assertThat(doseRowsOfOrder(w.clinic(), tds)).isEqualTo(2);
    }

    @Test
    void aSlotBeforeTheOrderStartedIs409DoseBeforeStartAndTheLaterSlotFollowsTheClock() throws Exception {
        Ward w = ward();
        String bd = freshActiveOrder(w, "panto", "bd");
        verifiedAt(w.clinic(), bd, ist(9, 30), ist(10, 30));
        clockAt(10, 35);
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), bd, given(w, ist(8, 0))), 409, "dose-before-start", BEFORE_START);
        refused(doseCall(w.nurse().auth(), bd, notGiven(ist(8, 0), "NIL_BY_MOUTH")), 409, "dose-before-start", BEFORE_START);
        refused(doseCall(w.nurse().auth(), bd, given(w, ist(20, 0))), 409, "dose-not-due", NOT_DUE);
        assertThat(dsnap(w)).isEqualTo(before);
        // verified 8:45: the 8 AM slot counts and can be given at 9:01 as a late dose
        verifiedAt(w.clinic(), bd, ist(7, 45), ist(8, 45));
        clockAt(9, 1);
        doseOk(w.nurse().auth(), bd, given(w, ist(8, 0)));
    }

    // ---------------- the state of the order and of the patient (AC4, AC11) ----------------

    @Test
    void anOrderThatIsPendingStoppedOrRejectedAnswersItsOwn409BeforeAnyBedsideCheck() throws Exception {
        Ward w = ward();
        String pending = pendingOrder(w, "panto", "od");
        String stopped = activeOrder(w, "ondan", "od");
        stopOk(w.doctor().auth(), stopped);
        String rejected = pendingOrder(w, "ceft", "od");
        rejectOk(w.pharmacist().auth(), rejected, "WRONG_DOSE");
        clockAt(8, 5);
        DSnap before = dsnap(w);
        Map<String, Object> badBand = given(ist(8, 0), "WRONG-00000");
        for (Map<String, Object> b : List.of(badBand, given(w, ist(8, 0)), notGiven(ist(8, 0), "NIL_BY_MOUTH"), Map.<String, Object>of())) {
            Map<String, Object> body = b.isEmpty() ? b : set(b, "clientKey", newKey());
            if (!b.isEmpty()) {
                refused(doseCall(w.nurse().auth(), pending, body), 409, "order-not-verified", NOT_VERIFIED);
                refused(doseCall(w.nurse().auth(), stopped, body), 409, "order-state", DOSE_STOPPED);
                refused(doseCall(w.nurse().auth(), rejected, body), 409, "order-state", DOSE_REJECTED);
            }
        }
        assertThat(dsnap(w)).as("no deny entry either: the order is judged before the wristband").isEqualTo(before);
    }

    @Test
    void aDischargedPatientAnswers409NotAdmittedForEveryOrderBeforeTheOrderStateAndNothingIsWritten() throws Exception {
        Ward w = ward();
        String active = activeOrder(w, "pcm", "od");
        String pending = pendingOrder(w, "panto", "od");
        clockAt(8, 5);
        discharge(w);
        DSnap before = dsnap(w);
        for (String order : List.of(active, pending)) {
            refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "not-admitted", DOSE_NOT_ADMITTED);
            refused(doseCall(w.nurse().auth(), order, notGiven(ist(8, 0), "NIL_BY_MOUTH")), 409, "not-admitted", DOSE_NOT_ADMITTED);
        }
        refused(doseCall(w.clinic().owner(), active, given(w, ist(8, 0))), 409, "not-admitted", DOSE_NOT_ADMITTED);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    @Test
    void aSlotThatIsNotADoseTimeOfTheMedicineToday400sOnSlotAt() throws Exception {
        Fix f = fix("pcm", "od");
        DSnap before = dsnap(f.w());
        Instant[] bad = {ist(8, 1), ist(7, 59), ist(9, 0), ist(14, 0), ist(8, 0, 30), istOn(day.minusDays(1), 8, 0),
                istOn(day.plusDays(1), 8, 0), ist(8, 0).plusMillis(500)};
        for (Instant slot : bad) {
            invalid(doseCall(f.w().nurse().auth(), f.order(), given(f.w(), slot)), "slotAt", NOT_A_DOSE_TIME);
            invalid(doseCall(f.w().nurse().auth(), f.order(), notGiven(slot, "NIL_BY_MOUTH")), "slotAt", NOT_A_DOSE_TIME);
        }
        assertThat(dsnap(f.w())).isEqualTo(before);
    }

    // ---------------- the body (AC13) ----------------

    @Test
    void everyFormatErrorOfTheBodyIsReportedTogetherInTheOrderClientKeySlotAtOutcome() throws Exception {
        Fix f = fix("pcm", "od");
        DSnap before = dsnap(f.w());
        JsonNode p = refused(doseRaw(f.w().nurse().auth(), f.order(), "{}"), 400, "validation", RELOAD_TEXT);
        assertThat(fieldNames(p)).containsExactly("clientKey", "slotAt", "outcome");
        p = refused(doseCall(f.w().nurse().auth(), f.order(), Map.of("clientKey", "not-a-uuid", "slotAt", "yesterday", "outcome", "give")),
                400, "validation", RELOAD_TEXT);
        assertThat(fieldNames(p)).containsExactly("clientKey", "slotAt", "outcome");
        for (String outcome : new String[] {"given", "GIVE", "Given", "NOT GIVEN", "REFUSED", "HELD", ""}) {
            p = refused(doseCall(f.w().nurse().auth(), f.order(), set(given(f.w(), ist(8, 0)), "outcome", outcome)), 400,
                    "validation", RELOAD_TEXT);
            assertThat(fieldNames(p)).containsExactly("outcome");
        }
        p = refused(doseCall(f.w().nurse().auth(), f.order(), without(given(f.w(), ist(8, 0)), "clientKey")), 400, "validation",
                RELOAD_TEXT);
        assertThat(fieldNames(p)).containsExactly("clientKey");
        p = refused(doseCall(f.w().nurse().auth(), f.order(), set(given(f.w(), ist(8, 0)), "slotAt", "2026-13-45")), 400,
                "validation", RELOAD_TEXT);
        assertThat(fieldNames(p)).containsExactly("slotAt");
        problem(doseRaw(f.w().nurse().auth(), f.order(), "this is not json"), 400);
        assertThat(dsnap(f.w())).isEqualTo(before);
    }

    // ---------------- the bedside checks in the order of AC6 ----------------

    @Test
    void anEmptyWristbandBoxIs400ScanFirstWhateverElseIsMissingAndWhateverTheBoxHoldsThatNormalisesToNothing() throws Exception {
        Fix f = fix("pcm", "od");
        DSnap before = dsnap(f.w());
        List<Object> empties = new ArrayList<>();
        empties.add(null);
        empties.add("");
        empties.add("   ");
        empties.add("\t\r\n");
        empties.add("​‏");
        empties.add(" ");
        for (Object band : empties) {
            Map<String, Object> body = set(given(ist(8, 0), "x"), "wristband", band);
            invalid(doseCall(f.w().nurse().auth(), f.order(), body), "wristband", SCAN_FIRST);
            // and before the statement and the second nurse are looked at
            Map<String, Object> worse = set(set(body, "identityConfirmed", false), "clientKey", newKey());
            invalid(doseCall(f.w().nurse().auth(), f.order(), worse), "wristband", SCAN_FIRST);
        }
        invalid(doseCall(f.w().nurse().auth(), f.order(), without(given(f.w(), ist(8, 0)), "wristband")), "wristband", SCAN_FIRST);
        assertThat(dsnap(f.w())).isEqualTo(before);
    }

    @Test
    void theStatementOfNameAndDateOfBirthMustBeTheJsonValueTrue() throws Exception {
        Fix f = fix("pcm", "od");
        DSnap before = dsnap(f.w());
        List<Object> notTrue = new ArrayList<>();
        notTrue.add(false);
        notTrue.add(null);
        notTrue.add("true");
        notTrue.add("yes");
        notTrue.add(1);
        notTrue.add(0);
        for (Object value : notTrue) {
            invalid(doseCall(f.w().nurse().auth(), f.order(), set(given(f.w(), ist(8, 0)), "identityConfirmed", value)),
                    "identityConfirmed", CONFIRM_TEXT);
        }
        invalid(doseCall(f.w().nurse().auth(), f.order(), without(given(f.w(), ist(8, 0)), "identityConfirmed")),
                "identityConfirmed", CONFIRM_TEXT);
        assertThat(dsnap(f.w())).isEqualTo(before);
    }

    // ---------------- the wristband (AC6, ADR-011 D4) ----------------

    @Test
    void aWristbandOfAnotherPatientIs409MismatchWithTheDenyEntryTheAuditAndNoDoseNoAccessRowAndNoNameInTheProblem() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        String other = otherUhid(w.uhid());
        DSnap before = dsnap(w);
        JsonNode p = refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), other)), 409, "wristband-mismatch", MISMATCH_TEXT);
        String text = p.toString();
        assertThat(text).doesNotContain(w.adm().name()).doesNotContain(w.uhid()).doesNotContain(w.adm().mobile())
                .doesNotContain(other).doesNotContain(w.adm().name().split(" ")[0]);
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.access()).isEqualTo(before.access());
        assertThat(now.pins()).isEqualTo(before.pins());
        assertThat(now.events()).as("the deny entry is committed although the answer is 409").isEqualTo(before.events() + 1);
        assertThat(now.audits()).isEqualTo(before.audits() + 1);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("deny|Stopped at the wristband check: scanned " + other
                + ", expected " + w.uhid() + " (Tab. Paracetamol 650 mg)");
        assertThat(denyEvents(w.clinic(), w.adm().id())).isOne();
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_WRISTBAND_MISMATCH");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[0]).isEqualTo("IPD_MEDICINE_ORDER");
        assertThat(audits.get(0)[1]).isEqualTo(f.order());
        assertThat(audits.get(0)[4]).isEqualTo(w.nurse().userId().toString());
        JsonNode after = auditAfter(w.clinic(), "IPD_DOSE_WRISTBAND_MISMATCH", 0);
        assertThat(keys(after)).containsExactlyInAnyOrder("orderId", "admissionId", "drugKey", "slotAt");
        assertThat(audits.get(0)[3]).doesNotContain(other).doesNotContain(w.uhid());
        // the slot is still open; each further try writes another entry; the right band then works
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), f.order(), 0).get("state").asText()).isEqualTo("DUE");
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), other)), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(denyEvents(w.clinic(), w.adm().id())).isEqualTo(2);
        assertThat(audits(w.clinic(), "IPD_DOSE_WRISTBAND_MISMATCH")).isEqualTo(2);
        doseOk(w.nurse().auth(), f.order(), given(w, ist(8, 0)));
    }

    @Test
    void aMismatchIsJudgedBeforeTheStatementAndAMismatchDoesNotUseUpTheClientKey() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Map<String, Object> wrong = set(given(ist(8, 0), otherUhid(w.uhid())), "identityConfirmed", false);
        refused(doseCall(w.nurse().auth(), f.order(), wrong), 409, "wristband-mismatch", MISMATCH_TEXT);
        // the same key with the right band and the statement
        Map<String, Object> fixed = set(set(wrong, "wristband", w.uhid()), "identityConfirmed", true);
        JsonNode a = doseOk(w.nurse().auth(), f.order(), fixed);
        assertThat(a.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
    }

    @Test
    void textThatIsNotAUhidIsNeverWrittenToTheEventLogNotEvenThePatientsOwnNameOrMobile() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        String[] typed = {w.adm().name(), w.adm().mobile(), "patient allergic to penicillin note", "AB-1", "ab-123", "PT00042",
                "РТ-00042", "PT-0004x", "7 AM tablet given before"};
        for (String text : typed) {
            refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), text)), 409, "wristband-mismatch", MISMATCH_TEXT);
        }
        List<String> events = eventLines(w.clinic(), w.adm().id());
        long deny = events.stream().filter(l -> l.startsWith("deny|")).count();
        assertThat(deny).isEqualTo(typed.length);
        for (String l : events) {
            if (l.startsWith("deny|")) {
                assertThat(l).isEqualTo("deny|Stopped at the wristband check: scanned text that is not a UHID, expected " + w.uhid()
                        + " (Tab. Paracetamol 650 mg)");
            }
        }
        String all = allEventText(w.clinic()) + allAuditText(w.clinic());
        assertThat(all).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain("allergic")
                .doesNotContain("tablet given before");
    }

    @Test
    void aBoxLongerThan200UnitsIsAMismatchEvenWhenItNormalisesToTheUhidAndExactly200UnitsMatch() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        String padded201 = w.uhid() + " ".repeat(201 - w.uhid().length());
        assertThat(padded201.length()).isEqualTo(201);
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), padded201)), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("deny|Stopped at the wristband check: scanned text that is not a "
                + "UHID, expected " + w.uhid() + " (Tab. Paracetamol 650 mg)");
        String padded200 = w.uhid() + " ".repeat(200 - w.uhid().length());
        doseOk(w.nurse().auth(), f.order(), given(ist(8, 0), padded200));
        assertThat(doseDb(w.clinic(), f.order(), ist(8, 0)).scan()).isEqualTo(w.uhid());
    }

    @Test
    void aUhidShapedScanIsWrittenAsTypedUpToNineDigitsAndNotMoreAndNeverAnythingElse() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        String expected = ", expected " + w.uhid() + " (Tab. Paracetamol 650 mg)";
        String words = "deny|Stopped at the wristband check: scanned text that is not a UHID" + expected;
        // the shape is ^[A-Z]{2,6}-[0-9]{5,9}$ (8 to 16 characters): the normal UHID shape stays shown, as typed in capitals
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), "pt-12345")), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("deny|Stopped at the wristband check: scanned PT-12345" + expected);
        // the longest shape that is still shown: six letters and nine digits (16 characters)
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), "abcdef-123456789")), 409, "wristband-mismatch", null);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("deny|Stopped at the wristband check: scanned ABCDEF-123456789"
                + expected);
        long shown = eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("deny|")).count();
        assertThat(shown).isEqualTo(2);
        // one digit more, a 14-digit run like an ABHA number, a 10-digit mobile number and over-long shapes are only the fixed words
        String[] hidden = {"ABCDEF-1234567890", "PT-1234567890", "AB-12345678901234", "9876543210", "pt-1234567890 ",
                "ABCDEF-" + "1234567890".repeat(3) + "1234", "ABCDEF-" + "1234567890".repeat(3) + "12345"};
        for (String text : hidden) {
            refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), text)), 409, "wristband-mismatch", MISMATCH_TEXT);
        }
        List<String> denies = eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("deny|")).toList();
        assertThat(denies).hasSize(2 + hidden.length);
        assertThat(denies.stream().filter(l -> l.equals(words)).count()).as("each hidden scan shows the fixed words")
                .isEqualTo(hidden.length);
        String all = allEventText(w.clinic()) + allAuditText(w.clinic());
        assertThat(all).doesNotContain("9876543210").doesNotContain("12345678901234").doesNotContain("PT-1234567890")
                .doesNotContain("ABCDEF-1234567890");
        // too short a prefix, too long a prefix and too few digits are never the shape
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), "A-12345")), 409, "wristband-mismatch", null);
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), "ABCDEFG-12345")), 409, "wristband-mismatch", null);
        refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), "AB-1234")), 409, "wristband-mismatch", null);
        List<String> now = eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("deny|")).toList();
        assertThat(now.stream().filter(l -> l.contains("scanned A-12345")).count()).as("one letter is not the shape").isZero();
        assertThat(now.stream().filter(l -> l.contains("scanned ABCDEFG-")).count()).as("seven letters").isZero();
        assertThat(now.stream().filter(l -> l.contains("scanned AB-1234,")).count()).as("four digits").isZero();
        assertThat(now.stream().filter(l -> l.equals(words)).count()).isEqualTo(hidden.length + 3);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).as("a mismatch never writes a dose").isZero();
    }

    @Test
    void theBoxIsNormalisedLikeAScannerSendsItSpacesCaseControlAndFormatCharactersAndFullWidthFormsAreRemovedOrFolded()
            throws Exception {
        Ward w = ward();
        String[] variants = {w.uhid().toLowerCase(), " " + w.uhid() + " ", w.uhid() + "\r\n", "\t" + w.uhid() + "\t",
                String.join(" ", w.uhid().split("")), w.uhid().substring(0, 3) + "​" + w.uhid().substring(3),
                "‏" + w.uhid() + "‎", " " + w.uhid() + " ", fullWidth(w.uhid()),
                w.uhid().substring(0, 2) + "\u0000" + w.uhid().substring(2)};
        String[] drugs = {"pcm", "panto", "ondan", "ns", "ceft", "cotri", "met", "amox", "pip", "ibu"};
        String[] orders = new String[variants.length];
        for (int i = 0; i < variants.length; i++) {
            orders[i] = activeOrder(w, drugs[i], "od");
        }
        clockAt(8, 5);
        for (int i = 0; i < variants.length; i++) {
            JsonNode a = ok(HttpMethod.POST, dosesUrl(orders[i]), w.nurse().auth(),
                    js(given(ist(8, 0), variants[i])), 201);
            assertThat(a.get("dose").get("outcome").asText()).as("variant " + i).isEqualTo("GIVEN");
            assertThat(doseDb(w.clinic(), orders[i], ist(8, 0)).scan()).as("the stored scan is the normalised one").isEqualTo(w.uhid());
        }
        assertThat(denyEvents(w.clinic(), w.adm().id())).isZero();
    }

    @Test
    void aBoxThatDiffersEvenSlightlyIsAMismatchDigitsOnlyNoDashAHomoglyphAndTheNeighbourUhid() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        String[] differ = {otherUhid(w.uhid()), w.uhid().replace("-", ""), w.uhid().substring(w.uhid().indexOf('-') + 1),
                "РТ" + w.uhid().substring(2), w.uhid() + "0", w.uhid().substring(1), w.uhid().replace('-', '_'),
                "0" + w.uhid()};
        for (String band : differ) {
            refused(doseCall(w.nurse().auth(), f.order(), given(ist(8, 0), band)), 409, "wristband-mismatch", MISMATCH_TEXT);
        }
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
        assertThat(denyEvents(w.clinic(), w.adm().id())).isEqualTo(differ.length);
    }

    // ---------------- replays, reused keys and the same dose twice (AC4, AC13) ----------------

    @Test
    void theSameKeyAndValuesAgainAnswers200ReplayedWithTheStoredDoseAndWritesOnlyOneAccessRow() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Map<String, Object> body = given(w, ist(8, 0));
        JsonNode first = doseOk(w.nurse().auth(), f.order(), body);
        DSnap afterFirst = dsnap(w);
        for (int i = 1; i <= 3; i++) {
            JsonNode again = ok(HttpMethod.POST, dosesUrl(f.order()), w.nurse().auth(), js(body), 200);
            assertThat(again.get("replayed").asBoolean()).isTrue();
            assertThat(again.get("dose").get("id").asText()).isEqualTo(first.get("dose").get("id").asText());
            assertThat(again.get("dose").get("recordedAt").asText()).isEqualTo(first.get("dose").get("recordedAt").asText());
            assertThat(slot(again.get("mar"), f.order(), 0).get("state").asText()).isEqualTo("GIVEN");
            DSnap now = dsnap(w);
            assertThat(now.doses()).isEqualTo(afterFirst.doses());
            assertThat(now.events()).isEqualTo(afterFirst.events());
            assertThat(now.audits()).isEqualTo(afterFirst.audits());
            assertThat(now.access()).isEqualTo(afterFirst.access() + i);
        }
        // a replay needs no wristband and no statement: it returns only what the caller may read
        JsonNode bare = ok(HttpMethod.POST, dosesUrl(f.order()), w.nurse().auth(),
                js(without(body, "wristband", "identityConfirmed")), 200);
        assertThat(bare.get("replayed").asBoolean()).isTrue();
    }

    @Test
    void aNotGivenReplayNeedsTheSameReasonAndAnyOtherValueWithTheKeyIs409ClientKeyReused() throws Exception {
        Ward w = ward();
        String a = activeOrder(w, "pcm", "bd");
        String b = activeOrder(w, "panto", "od");
        clockAt(20, 5);
        Map<String, Object> ng = notGiven(ist(8, 0), "NIL_BY_MOUTH");
        doseOk(w.nurse().auth(), a, ng);
        assertThat(ok(HttpMethod.POST, dosesUrl(a), w.nurse().auth(), js(ng), 200).get("replayed").asBoolean()).isTrue();
        DSnap before = dsnap(w);
        // same key, other reason / other slot / other outcome
        refused(doseCall(w.nurse().auth(), a, set(ng, "reasonKey", "HELD_DOCTOR")), 409, "client-key-reused", DOSE_KEY_REUSED);
        refused(doseCall(w.nurse().auth(), a, set(ng, "slotAt", ist(20, 0).toString())), 409, "client-key-reused", DOSE_KEY_REUSED);
        refused(doseCall(w.nurse().auth(), a, set(set(ng, "outcome", "GIVEN"), "wristband", w.uhid())), 409, "client-key-reused",
                DOSE_KEY_REUSED);
        Map<String, Object> g = given(w, ist(8, 0));
        doseOk(w.nurse().auth(), b, g);
        refused(doseCall(w.nurse().auth(), b, set(set(without(g, "wristband", "identityConfirmed"), "outcome", "NOT_GIVEN"),
                "reasonKey", "NIL_BY_MOUTH")), 409, "client-key-reused", DOSE_KEY_REUSED);
        assertThat(dsnap(w).doses()).isEqualTo(before.doses() + 1);
        // the key of one order sent for another order is a 400 on the key
        invalid(doseCall(w.nurse().auth(), b, set(ng, "slotAt", ist(8, 0).toString())), "clientKey", RELOAD_TEXT);
        assertThat(doseRowsOfOrder(w.clinic(), b)).isOne();
    }

    @Test
    void aReplayStaysAReplayAfterTheOrderIsStoppedAndAfterDischargeButANewKeyForTheSlotIsRefused() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "bd");
        clockAt(8, 5);
        Map<String, Object> body = given(w, ist(8, 0));
        doseOk(w.nurse().auth(), order, body);
        stopOk(w.doctor().auth(), order);
        assertThat(ok(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(body), 200).get("replayed").asBoolean()).isTrue();
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "order-state", DOSE_STOPPED);
        discharge(w);
        assertThat(ok(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(body), 200).get("replayed").asBoolean()).isTrue();
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "not-admitted", DOSE_NOT_ADMITTED);
    }

    @Test
    void aSlotThatHasARecordOfAnyKindIs409DoseRecordedForEveryCombinationAndTheSecondTryWritesNothing() throws Exception {
        Ward w = ward();
        String[] drugs = {"pcm", "panto", "ondan", "ceft"};
        String[] orders = new String[4];
        for (int i = 0; i < 4; i++) {
            orders[i] = activeOrder(w, drugs[i], "od");
        }
        clockAt(8, 5);
        doseOk(w.nurse().auth(), orders[0], given(w, ist(8, 0)));
        doseOk(w.nurse().auth(), orders[1], given(w, ist(8, 0)));
        doseOk(w.nurse().auth(), orders[2], notGiven(ist(8, 0), "PATIENT_REFUSED"));
        doseOk(w.nurse().auth(), orders[3], notGiven(ist(8, 0), "NOT_AVAILABLE"));
        DSnap before = dsnap(w);
        // given then given by the owner, given then not given, not given then given, not given then not given
        refused(doseCall(w.clinic().owner(), orders[0], given(w, ist(8, 0))), 409, "dose-recorded", DOSE_RECORDED);
        refused(doseCall(w.nurse().auth(), orders[1], notGiven(ist(8, 0), "HELD_DOCTOR")), 409, "dose-recorded", DOSE_RECORDED);
        refused(doseCall(w.nurse().auth(), orders[2], given(w, ist(8, 0))), 409, "dose-recorded", DOSE_RECORDED);
        refused(doseCall(w.nurse().auth(), orders[3], notGiven(ist(8, 0), "PATIENT_REFUSED")), 409, "dose-recorded", DOSE_RECORDED);
        // judged before the bedside: a wrong band on a recorded slot is not a wristband stop
        refused(doseCall(w.nurse().auth(), orders[0], given(ist(8, 0), "WRONG-00000")), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(dsnap(w)).isEqualTo(before);
        assertThat(denyEvents(w.clinic(), w.adm().id())).isZero();
    }

    // ---------------- roles (AC4) ----------------

    @Test
    void everyRoleButNurseAndOwnerGets403WithTheExactTextOnBothOutcomesEvenWithAnInvalidBodyAndNothingIsWritten() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        DSnap before = dsnap(w);
        for (String role : NON_GIVE_ROLES) {
            Staff s = staff(w.clinic(), role);
            refused(doseCall(s.auth(), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
            refused(doseCall(s.auth(), f.order(), notGiven(ist(8, 0), "NIL_BY_MOUTH")), 403, "forbidden", ONLY_GIVERS);
            refused(doseRaw(s.auth(), f.order(), "{}"), 403, "forbidden", ONLY_GIVERS);
        }
        assertThat(dsnap(w)).isEqualTo(before);
        refused(call(HttpMethod.POST, dosesUrl(f.order()), noSession(), js(given(w, ist(8, 0)))), 401, "unauthorized", null);
        refused(doseCall(w.nurse().auth(), UUID.randomUUID().toString(), given(w, ist(8, 0))), 404, "not-found", null);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    @Test
    void theRoleOfTheActiveMembershipIsReadAgainAndAnInactiveMembershipOrAWrongTokenRoleIsRefused() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        DSnap before = dsnap(w);
        Pn becameFrontDesk = newPn(w.clinic(), "NURSE");
        setMembershipRole(w.clinic(), becameFrontDesk.userId(), "FRONT_DESK");
        refused(doseCall(becameFrontDesk.auth(), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        Pn becameDoctor = newPn(w.clinic(), "NURSE");
        setMembershipRole(w.clinic(), becameDoctor.userId(), "DOCTOR");
        refused(doseCall(becameDoctor.auth(), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        Pn gone = newPn(w.clinic(), "NURSE");
        setMembershipStatus(w.clinic().tenantId(), gone.userId(), "INACTIVE");
        refused(doseCall(gone.auth(), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        // a nurse whose token claims DOCTOR, a doctor whose token claims OWNER or NURSE
        refused(doseCall(tokenOf(w.clinic(), w.nurse().userId(), "DOCTOR"), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        refused(doseCall(tokenOf(w.clinic(), w.doctor().userId(), "OWNER"), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        refused(doseCall(tokenOf(w.clinic(), w.doctor().userId(), "NURSE"), f.order(), given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        assertThat(dsnap(w)).isEqualTo(before);
        doseOk(w.nurse().auth(), f.order(), given(w, ist(8, 0)));
    }

    // ---------------- Read-only (AC11) ----------------

    @Test
    void inReadOnlyEveryDoseIs409IpdReadonlyBeforeTheBodyIsLookedAtAndTheRoleCheckStillComesFirst() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        expireTrial(w.clinic());
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), f.order(), given(w, ist(8, 0))), 409, "ipd-readonly", READONLY_TEXT);
        refused(doseCall(w.nurse().auth(), f.order(), notGiven(ist(8, 0), "NIL_BY_MOUTH")), 409, "ipd-readonly", READONLY_TEXT);
        refused(doseRaw(w.nurse().auth(), f.order(), "{}"), 409, "ipd-readonly", READONLY_TEXT);
        refused(doseCall(w.clinic().owner(), f.order(), given(w, ist(8, 0))), 409, "ipd-readonly", READONLY_TEXT);
        refused(doseRaw(w.doctor().auth(), f.order(), "{}"), 403, "forbidden", ONLY_GIVERS);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    // ---------------- append-only (AC9) ----------------

    @Test
    void thereIsNoWayToChangeOrRemoveARecordedDoseThroughTheApi() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        JsonNode a = doseOk(w.nurse().auth(), f.order(), given(w, ist(8, 0)));
        String id = a.get("dose").get("id").asText();
        DoseDb before = doseDb(w.clinic(), f.order(), ist(8, 0));
        for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            for (String url : new String[] {dosesUrl(f.order()), dosesUrl(f.order()) + "/" + id, dosesUrl(id),
                    IPD + "/doses/" + id}) {
                int status = call(method, url, w.clinic().owner(), "{\"outcome\":\"NOT_GIVEN\",\"reasonKey\":\"NIL_BY_MOUTH\"}")
                        .andReturn().getResponse().getStatus();
                assertThat(status).as(method + " " + url).isBetween(400, 499);
            }
        }
        assertThat(doseDb(w.clinic(), f.order(), ist(8, 0))).isEqualTo(before);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        // and no "undo" by recording the other outcome for the same slot
        refused(doseCall(w.clinic().owner(), f.order(), notGiven(ist(8, 0), "NIL_BY_MOUTH")), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(doseDb(w.clinic(), f.order(), ist(8, 0))).isEqualTo(before);
    }

    // ---------------- where the doctor sees it (OQ-32-4, ADR-011 D11) ----------------

    @Test
    void theEmrShowsTheLastDoseOfEachOrderByTheHighestSlotAndNullForAnOrderWithNoDose() throws Exception {
        Ward w = ward();
        String bd = activeOrder(w, "pcm", "bd");
        String od = activeOrder(w, "panto", "od");
        clockAt(20, 5);
        // the 8 PM dose is recorded first (not given), the 8 AM dose after it: the last dose is the one with the later slot
        doseOk(w.nurse().auth(), bd, notGiven(ist(20, 0), "PATIENT_REFUSED"));
        doseOk(w.nurse().auth(), bd, given(w, ist(8, 0)));
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            JsonNode emr = emr(role.equals("OWNER") ? w.clinic().owner() : role.equals("DOCTOR") ? w.doctor().auth()
                    : w.nurse().auth(), w.adm().id());
            JsonNode row = sheetRow(emr, bd);
            assertThat(row.has("lastDose")).as(role).isTrue();
            JsonNode last = row.get("lastDose");
            assertThat(keys(last)).containsExactlyInAnyOrder("id", "slotAt", "outcome", "reason", "recordedAt", "recordedByName",
                    "secondCheckByName");
            assertThat(Instant.parse(last.get("slotAt").asText())).isEqualTo(ist(20, 0));
            assertThat(last.get("outcome").asText()).isEqualTo("REFUSED");
            assertThat(last.get("reason").get("label").asText()).isEqualTo("Patient refused");
            assertThat(sheetRow(emr, od).get("lastDose").isNull()).as(role).isTrue();
        }
    }

    // ---------------- a failure of the database (AC13) ----------------

    @Test
    void aDatabaseRefusalOnTheDoseInsertIsAGeneric500WithoutNamesTheWholeTransactionRolledBackAndTheSameKeyWorksAfterwards()
            throws Exception {
        String[][] errors = {{"23505", "uq_dose_administration_order_slot"}, {"23505", "uq_dose_administration_tenant_client_key"},
                {"23514", "ck_dose_administration_outcome"}, {"23503", "fk_dose_administration_order"}};
        for (String[] error : errors) {
            Fix f = fix("pcm", "od");
            Ward w = f.w();
            DSnap before = dsnap(w);
            Map<String, Object> body = given(w, ist(8, 0));
            forcedDoseFailure(w.clinic(), error[0], error[1], () -> {
                try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
                    JsonNode p = problem(doseCall(w.nurse().auth(), f.order(), body), 500);
                    assertThat(p.get("type").asText()).endsWith("/internal-error");
                    assertThat(p.get("detail").asText()).isEqualTo(INTERNAL_TEXT);
                    assertThat(p.toString()).doesNotContain("forced failure").doesNotContain("ipd.").doesNotContain("dose_administration")
                            .doesNotContain("constraint").doesNotContain(error[0]).doesNotContain("Exception")
                            .doesNotContain(w.adm().name()).doesNotContain(w.uhid()).doesNotContain(w.adm().mobile());
                    assertThat(logs.allText()).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile())
                            .doesNotContain("scanned").doesNotContain(w.uhid());
                }
            });
            assertThat(dsnap(w)).as("nothing survived the rollback: " + error[0] + " " + error[1]).isEqualTo(before);
            assertThat(slot(mar(w.nurse().auth(), w.adm().id()), f.order(), 0).get("state").asText()).isEqualTo("DUE");
            JsonNode retry = doseOk(w.nurse().auth(), f.order(), body);
            assertThat(retry.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        }
    }

    /**
     * The real guard trigger (not a forced one): the application reads the catalogue once and keeps it (ADR-009 D3), so when
     * the database's high-alert flag of a medicine is changed behind its back, the dose it sends carries a flag the guard
     * does not accept. That is the "Java read the flag wrongly" fault of docs/database/SAH-32.md: 500 generic, everything rolled
     * back, no name and no guard text in the answer. The flag is put back in a finally block (reference data, one clinic only
     * sees it for the length of the request).
     */
    @Test
    void theRealGuardTriggerRefusalIsAGeneric500WithoutTheGuardTextOrNamesAndNothingIsLeftBehind() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        DSnap before = dsnap(w);
        Map<String, Object> body = given(w, ist(8, 0));
        try (java.sql.Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "update ipd.drug set high_alert = 'Insulin' where key = 'pcm'");
            try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
                JsonNode p = problem(doseCall(w.nurse().auth(), f.order(), body), 500);
                assertThat(p.get("type").asText()).endsWith("/internal-error");
                assertThat(p.get("detail").asText()).isEqualTo(INTERNAL_TEXT);
                assertThat(p.toString()).doesNotContain("high-alert flag").doesNotContain("dose can be recorded")
                        .doesNotContain("dose_administration").doesNotContain("check_violation").doesNotContain("23514")
                        .doesNotContain(w.adm().name()).doesNotContain(w.uhid()).doesNotContain(w.adm().mobile());
                assertThat(logs.allText()).contains("sqlState=23514").doesNotContain(w.adm().name()).doesNotContain(w.uhid())
                        .doesNotContain(w.adm().mobile());
            } finally {
                DbAccess.execute(c, "update ipd.drug set high_alert = null where key = 'pcm'");
            }
        }
        assertThat(dsnap(w)).as("nothing survived the rollback").isEqualTo(before);
        assertThat(doseDbCount(w, f.order())).isZero();
        doseOk(w.nurse().auth(), f.order(), body);
    }

    private long doseDbCount(Ward w, String order) throws Exception {
        return doseRowsOfOrder(w.clinic(), order);
    }

    private void forcedDoseFailure(Clinic clinic, String errcode, String constraint, Action action) throws Exception {
        String suffix = clinic.tenantId().toString().replace('-', '_');
        String function = "ipd.it_fail_dose_" + suffix;
        String trigger = "trg_it_fail_dose_" + suffix;
        try (java.sql.Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the rollback test' using errcode = '" + errcode + "', constraint = '"
                    + constraint + "'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before insert on ipd.dose_administration for each row "
                    + "when (new.tenant_id = '" + clinic.tenantId() + "'::uuid) execute function " + function + "()");
            try {
                action.run();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on ipd.dose_administration");
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
    }

    // ---------------- values that must not be mixed up ----------------

    @Test
    void theIdentityBarFieldsInTheMarMatchTheUhidTheWristbandIsComparedWith() throws Exception {
        Fix f = fix("pcm", "od");
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("uhid", mar(f.w().nurse().auth(), f.w().adm().id()).get("patient").get("uhid").asText());
        assertThat(hold.get("uhid")).isEqualTo(f.w().uhid());
        doseOk(f.w().nurse().auth(), f.order(), given(ist(8, 0), (String) hold.get("uhid")));
    }
}
