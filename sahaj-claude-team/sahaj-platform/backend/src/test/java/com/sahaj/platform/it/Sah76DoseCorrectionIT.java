package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76-5 undo within the window and recorded in error ({@code POST /api/v1/ipd/doses/{id}/undo} and
 * {@code /recorded-in-error}) on the real {@code sahaj_test} database: undo by the recorder inside the window only, the IST day
 * rule of recorded in error, two corrections refused, attempt n+1 after a correction, replay by client key, roles, Read-only and
 * a discharged patient, the ward scope guard on both operations, another clinic, and what each correction writes (event, audit
 * row with the device label, review item closed) without ever rewriting the original dose. STAT orders are used for the doses
 * because their slot is the minute of ordering, so the whole test runs on the real database clock the undo window needs.
 * Test plan: docs/test-plans/SAH-76-dose-settings.md (tests C-01 to C-20).
 */
@OnTestDatabase
@WithPinPepper
class Sah76DoseCorrectionIT extends Sah76NursingSupport {

    private record G(Ward w, String order, String doseId, Instant slot, String code) {
    }

    /** A new ward whose STAT order of this drug was given by this nurse (real clock, before the pharmacist check). */
    private G given(Ward w, String drug, String unit, Pn giver) throws Exception {
        realClock();
        String order = placeOrder(w.doctor().auth(), w.adm().id(), statOrder(drug, unit, "IV"));
        Instant slot = slotOf(mar(giver.auth(), w.adm().id()), order, 0);
        String code = codeOf(drug);
        JsonNode a = doseOk(giver.auth(), order, scanGiven(w, slot, 1, code));
        return new G(w, order, a.get("dose").get("id").asText(), slot, code);
    }

    private G given(Ward w) throws Exception {
        return given(w, "panto", "MG", w.nurse());
    }

    private static JsonNode dosePart(JsonNode mar, String order, int slotIndex) {
        return slot(mar, order, slotIndex).get("dose");
    }

    // ---------------- undo ----------------

    @Test
    void theRecorderUndoesADoseInsideTheWindowAndTheSlotIsDueAgainWithTheOriginalKept() throws Exception {
        Ward w = ward();
        G g = given(w);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        JsonNode row = dosePart(mar, g.order(), 0);
        assertThat(row.get("canUndo").asBoolean()).isTrue();
        assertThat(row.get("undoSecondsLeft").asInt()).isBetween(1, 10);
        assertThat(row.get("canMarkInError").asBoolean()).isTrue();

        long audits = audit(w.clinic(), "IPD_DOSE_GIVEN").size();
        String key = newKey();
        JsonNode a = ok(HttpMethod.POST, undoUrl(g.doseId()), w.nurse().auth(), corrBody(key, null), 200);
        assertThat(a.get("replayed").asBoolean()).isFalse();
        assertThat(a.get("dose").get("id").asText()).isEqualTo(g.doseId());
        assertThat(a.get("dose").get("correction").get("kind").asText()).isEqualTo("UNDO");
        assertThat(a.get("dose").get("correction").get("correctedByName").asText()).isEqualTo(displayName(w.nurse()));
        assertThat(a.get("dose").get("canUndo").asBoolean()).isFalse();

        JsonNode slot = slot(a.get("mar"), g.order(), 0);
        assertThat(slot.get("state").asText()).as("the slot returns to its clock state").isIn("DUE", "LATE");
        assertThat(slot.get("dose").isNull()).isTrue();
        assertThat(slot.get("canGive").asBoolean()).isTrue();
        assertThat(slot.get("history")).as("an undone record is not listed").isEmpty();

        List<List<String>> corrections = table(w.clinic(), "select kind, corrected_by::text, client_key::text, coalesce(reason_key, '') "
                + "from ipd.dose_correction where dose_id = ?::uuid", g.doseId());
        assertThat(corrections).hasSize(1);
        assertThat(corrections.get(0)).containsExactly("UNDO", w.nurse().userId().toString(), key, "");
        assertThat(dosesOf(w.clinic(), g.order())).as("nothing is deleted or changed").hasSize(1);
        assertThat(dosesOf(w.clinic(), g.order()).get(0).outcome()).isEqualTo("GIVEN");
        assertThat(audit(w.clinic(), "IPD_DOSE_GIVEN")).as("the give and its audit stay").hasSize((int) audits);
        assertThat(count(w.clinic(), "select count(*) from ipd.pharmacist_review where dose_id = ?::uuid and outcome = 'DOSE_UNDONE' "
                + "and closed_by = ?::uuid", g.doseId(), w.nurse().userId().toString())).isOne();
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("give|Undid the dose of Inj. Pantoprazole 40 mg");
        List<String[]> undone = audit(w.clinic(), "IPD_DOSE_UNDONE");
        assertThat(undone).hasSize(1);
        assertThat(undone.get(0)[0]).isEqualTo("IPD_DOSE");
        assertThat(undone.get(0)[1]).isEqualTo(g.doseId());
        JsonNode after = objectMapper.readTree(undone.get(0)[3]);
        assertThat(keys(after)).containsExactlyInAnyOrder("doseId", "orderId", "admissionId", "drugKey", "slotAt", "orderVersion",
                "device");
        assertThat(after.get("drugKey").asText()).isEqualTo("panto");
        assertThat(after.get("orderVersion").asInt()).isEqualTo(1);
    }

    @Test
    void anUndoRepeatedWithTheSameKeyIsAReplayAndAKeyOfAnotherDoseOrCallerIsRefused() throws Exception {
        Ward w = ward();
        G g = given(w);
        String key = newKey();
        ok(HttpMethod.POST, undoUrl(g.doseId()), w.nurse().auth(), corrBody(key, null), 200);
        long audits = allAudits(w.clinic());
        long events = eventCount(w.clinic(), w.adm().id());
        JsonNode again = ok(HttpMethod.POST, undoUrl(g.doseId()), w.nurse().auth(), corrBody(key, null), 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(correctionRows(w.clinic(), g.doseId())).isOne();
        assertThat(allAudits(w.clinic())).isEqualTo(audits);
        assertThat(eventCount(w.clinic(), w.adm().id())).isEqualTo(events);

        G other = given(w, "ondan", "MG", w.nurse());
        refused(undoCall(w.nurse().auth(), other.doseId(), key), 409, "client-key-reused", null);
        assertThat(correctionRows(w.clinic(), other.doseId())).isZero();
        // contract 0.4.1: a key already stored on a correction by another caller is 409 client-key-reused (the key check comes first)
        refused(undoCall(w.nurse2().auth(), g.doseId(), key), 409, "client-key-reused", null);
        refused(undoCall(w.nurse2().auth(), other.doseId(), newKey()), 403, "forbidden", ONLY_RECORDER);
    }

    @Test
    void onlyTheNurseWhoRecordedTheDoseMayUndoItAndEveryOtherRoleIsRefused() throws Exception {
        Ward w = ward();
        G g = given(w);
        String before = written(w.clinic());
        refused(undoCall(w.nurse2().auth(), g.doseId(), newKey()), 403, "forbidden", ONLY_RECORDER);
        refused(undoCall(w.clinic().owner(), g.doseId(), newKey()), 403, "forbidden", ONLY_RECORDER);
        for (String role : CORRECT_NON_ROLES) {
            refused(undoCall(staff(w.clinic(), role).auth(), g.doseId(), newKey()), 403, "forbidden", ONLY_GIVERS);
        }
        assertThat(written(w.clinic())).isEqualTo(before);
        assertThat(correctionRowsOfClinic(w.clinic())).isZero();
        JsonNode viewedByOther = dosePart(mar(w.nurse2().auth(), w.adm().id()), g.order(), 0);
        assertThat(viewedByOther.get("canUndo").asBoolean()).isFalse();
        assertThat(viewedByOther.get("undoSecondsLeft").asInt()).isZero();
        assertThat(viewedByOther.get("canMarkInError").asBoolean()).isTrue();
        JsonNode viewedByDoctor = dosePart(mar(w.doctor().auth(), w.adm().id()), g.order(), 0);
        assertThat(viewedByDoctor.get("canUndo").asBoolean()).isFalse();
        assertThat(viewedByDoctor.get("canMarkInError").asBoolean()).isFalse();
    }

    @Test
    void anUndoAfterTheWindowIsRefusedAndTheDoseCanStillBeMarkedInError() throws Exception {
        Ward w = ward();
        G g = given(w);
        expireUndo(w.clinic(), g.doseId());
        DSnap before = dsnap(w);
        refused(undoCall(w.nurse().auth(), g.doseId(), newKey()), 409, "undo-expired", UNDO_EXPIRED);
        assertThat(dsnap(w)).isEqualTo(before);
        JsonNode row = dosePart(mar(w.nurse().auth(), w.adm().id()), g.order(), 0);
        assertThat(row.get("canUndo").asBoolean()).isFalse();
        assertThat(row.get("undoSecondsLeft").asInt()).isZero();
        errorOk(w.nurse().auth(), g.doseId(), "WRONG_TIME");
    }

    @Test
    void theClinicUndoWindowIsStampedOnEachDoseAndNeverAboveTenSeconds() throws Exception {
        Ward w = ward();
        ok(HttpMethod.PUT, SETTINGS, w.clinic().owner(), "{\"medVerificationRequired\":true,\"doseWindowBeforeMinutes\":60,"
                + "\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,\"unackFlagMinutes\":30,\"unackFlagStatMinutes\":5,"
                + "\"undoSeconds\":3}", 200);
        G g = given(w);
        assertThat(dosesOf(w.clinic(), g.order()).get(0).undoSeconds()).isEqualTo(3.0);
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("windows").get("undoSeconds").asInt()).isEqualTo(3);
        ok(HttpMethod.PUT, SETTINGS, w.clinic().owner(), "{\"medVerificationRequired\":true,\"doseWindowBeforeMinutes\":60,"
                + "\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,\"unackFlagMinutes\":30,\"unackFlagStatMinutes\":5,"
                + "\"undoSeconds\":10}", 200);
        assertThat(dosesOf(w.clinic(), g.order()).get(0).undoSeconds()).as("past records are never rewritten").isEqualTo(3.0);
    }

    // ---------------- two corrections, not given ----------------

    @Test
    void aDoseCanBeCorrectedOnlyOnceWhateverTheKindsAreAndANotGivenDoseCannotBeCorrected() throws Exception {
        Ward w = ward();
        G g = given(w);
        undoOk(w.nurse().auth(), g.doseId());
        refused(undoCall(w.nurse().auth(), g.doseId(), newKey()), 409, "dose-state", CANNOT_UNDO);
        refused(errorCall(w.nurse().auth(), g.doseId(), newKey(), "DUPLICATE"), 409, "dose-state", CANNOT_MARK_ERROR);
        assertThat(correctionRows(w.clinic(), g.doseId())).isOne();

        G h = given(w, "ondan", "MG", w.nurse());
        errorOk(w.nurse().auth(), h.doseId(), "WRONG_TIME");
        refused(undoCall(w.nurse().auth(), h.doseId(), newKey()), 409, "dose-state", CANNOT_UNDO);
        refused(errorCall(w.nurse().auth(), h.doseId(), newKey(), "DUPLICATE"), 409, "dose-state", CANNOT_MARK_ERROR);
        assertThat(correctionRows(w.clinic(), h.doseId())).isOne();

        realClock();
        String order = placeOrder(w.doctor().auth(), w.adm().id(), statOrder("ceft", "MG", "IV"));
        Instant slot = slotOf(mar(w.nurse().auth(), w.adm().id()), order, 0);
        JsonNode notGiven = doseOk(w.nurse().auth(), order, notGivenBody(slot, 1, "NIL_BY_MOUTH", null));
        String id = notGiven.get("dose").get("id").asText();
        assertThat(notGiven.get("dose").get("canUndo").asBoolean()).isFalse();
        assertThat(notGiven.get("dose").get("canMarkInError").asBoolean()).isFalse();
        refused(undoCall(w.nurse().auth(), id, newKey()), 409, "dose-state", CANNOT_UNDO);
        refused(errorCall(w.nurse().auth(), id, newKey(), "DUPLICATE"), 409, "dose-state", CANNOT_MARK_ERROR);
        assertThat(correctionRows(w.clinic(), id)).isZero();
    }

    // ---------------- recorded in error ----------------

    @Test
    void anyNurseMayMarkADoseInErrorOnTheDayAndTheOriginalStaysOnTheMarStruckThrough() throws Exception {
        Ward w = ward();
        G g = given(w);
        String key = newKey();
        JsonNode a = ok(HttpMethod.POST, errorUrl(g.doseId()), w.nurse2().auth(), corrBody(key, "NOT_GIVEN_ACTUALLY"), 200);
        JsonNode correction = a.get("dose").get("correction");
        assertThat(correction.get("kind").asText()).isEqualTo("IN_ERROR");
        assertThat(correction.get("reason").get("key").asText()).isEqualTo("NOT_GIVEN_ACTUALLY");
        assertThat(correction.get("reason").get("label").asText()).isEqualTo("Dose was not actually given");
        assertThat(correction.get("correctedByName").asText()).isEqualTo(displayName(w.nurse2()));

        JsonNode slot = slot(a.get("mar"), g.order(), 0);
        assertThat(slot.get("dose").isNull()).isTrue();
        assertThat(slot.get("history")).hasSize(1);
        assertThat(slot.get("history").get(0).get("id").asText()).isEqualTo(g.doseId());
        assertThat(slot.get("history").get(0).get("correction").get("kind").asText()).isEqualTo("IN_ERROR");
        assertThat(slot.get("state").asText()).isIn("DUE", "LATE");
        assertThat(slot.get("canGive").asBoolean()).isTrue();

        assertThat(table(w.clinic(), "select kind, corrected_by::text, reason_key from ipd.dose_correction where dose_id = ?::uuid",
                g.doseId())).containsExactly(List.of("IN_ERROR", w.nurse2().userId().toString(), "NOT_GIVEN_ACTUALLY"));
        assertThat(dosesOf(w.clinic(), g.order())).hasSize(1);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains(
                "give|Marked the dose of Inj. Pantoprazole 40 mg recorded in error: Dose was not actually given");
        JsonNode after = objectMapper.readTree(audit(w.clinic(), "IPD_DOSE_RECORDED_IN_ERROR").get(0)[3]);
        assertThat(after.get("reasonKey").asText()).isEqualTo("NOT_GIVEN_ACTUALLY");
        assertThat(after.get("device").has("agent")).isTrue();
        assertThat(count(w.clinic(), "select count(*) from ipd.pharmacist_review where dose_id = ?::uuid and outcome = 'DOSE_UNDONE'",
                g.doseId())).isOne();
        // a replay with the same key and reason, and the same key with another reason
        JsonNode again = ok(HttpMethod.POST, errorUrl(g.doseId()), w.nurse2().auth(), corrBody(key, "NOT_GIVEN_ACTUALLY"), 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        refused(errorCall(w.nurse2().auth(), g.doseId(), key, "DUPLICATE"), 409, "client-key-reused", null);
        assertThat(correctionRows(w.clinic(), g.doseId())).isOne();
        // the four reasons are offered in order
        JsonNode reasons = a.get("mar").get("inErrorReasons");
        assertThat(reasons).hasSize(4);
        assertThat(reasons.get(0).get("key").asText()).isEqualTo("NOT_GIVEN_ACTUALLY");
        assertThat(reasons.get(3).get("key").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void recordedInErrorNeedsAReasonOfTheListAndAClientKeyAndWritesNothingWhenRefused() throws Exception {
        Ward w = ward();
        G g = given(w);
        String before = written(w.clinic());
        invalid(call(HttpMethod.POST, errorUrl(g.doseId()), w.nurse().auth(), "{\"clientKey\":\"" + newKey() + "\"}"), "reasonKey",
                CHOOSE_IN_ERROR);
        invalid(errorCall(w.nurse().auth(), g.doseId(), newKey(), "BECAUSE"), "reasonKey", CHOOSE_IN_ERROR);
        invalid(call(HttpMethod.POST, errorUrl(g.doseId()), w.nurse().auth(), "{\"reasonKey\":\"DUPLICATE\"}"), "clientKey", RELOAD_TEXT);
        invalid(call(HttpMethod.POST, errorUrl(g.doseId()), w.nurse().auth(), "{\"clientKey\":\"nope\",\"reasonKey\":\"DUPLICATE\"}"),
                "clientKey", RELOAD_TEXT);
        invalid(call(HttpMethod.POST, undoUrl(g.doseId()), w.nurse().auth(), "{}"), "clientKey", RELOAD_TEXT);
        for (String role : CORRECT_NON_ROLES) {
            refused(errorCall(staff(w.clinic(), role).auth(), g.doseId(), newKey(), "DUPLICATE"), 403, "forbidden", ONLY_CORRECTORS);
        }
        assertThat(written(w.clinic())).isEqualTo(before);
        assertThat(correctionRowsOfClinic(w.clinic())).isZero();
        // the owner may mark it
        errorOk(w.clinic().owner(), g.doseId(), "WRONG_PATIENT");
    }

    @Test
    void recordedInErrorIsOnlyAllowedOnTheIstDayOfTheSlot() throws Exception {
        Ward w = ward();
        G g = given(w);
        clockAt(istOn(day.plusDays(1), 0, 0));
        refused(errorCall(w.nurse().auth(), g.doseId(), newKey(), "WRONG_TIME"), 409, "error-window-closed", WINDOW_CLOSED);
        assertThat(correctionRows(w.clinic(), g.doseId())).isZero();
        JsonNode tomorrow = marOrder(mar(w.nurse().auth(), w.adm().id()), g.order());
        if (tomorrow != null && tomorrow.get("slots").size() > 0 && !tomorrow.get("slots").get(0).get("dose").isNull()) {
            assertThat(tomorrow.get("slots").get(0).get("dose").get("canMarkInError").asBoolean())
                    .as("the flag is off once the IST day has changed").isFalse();
        }
        clockAt(istOn(day, 23, 59));
        errorOk(w.nurse().auth(), g.doseId(), "WRONG_TIME");
        assertThat(correctionRows(w.clinic(), g.doseId())).isOne();
    }

    // ---------------- attempt n+1 ----------------

    @Test
    void aCorrectedSlotCanBeGivenAgainAsTheNextAttemptLinkedToTheCorrectedRecord() throws Exception {
        Ward w = ward();
        G g = given(w);
        String code = g.code();
        undoOk(w.nurse().auth(), g.doseId());
        JsonNode second = doseOk(w.nurse2().auth(), g.order(), scanGiven(w, g.slot(), 1, code));
        String secondId = second.get("dose").get("id").asText();
        errorOk(w.nurse().auth(), secondId, "DUPLICATE");
        JsonNode third = doseOk(w.nurse().auth(), g.order(), scanGiven(w, g.slot(), 1, code));
        String thirdId = third.get("dose").get("id").asText();

        List<Dose> rows = dosesOf(w.clinic(), g.order());
        assertThat(rows).extracting(Dose::attempt).containsExactly(1, 2, 3);
        assertThat(rows.get(0).supersedes()).isEmpty();
        assertThat(rows.get(1).supersedes()).isEqualTo(g.doseId());
        assertThat(rows.get(2).supersedes()).isEqualTo(secondId);
        assertThat(rows.get(2).id()).isEqualTo(thirdId);
        assertThat(rows).extracting(Dose::recordedBy).containsExactly(w.nurse().userId().toString(), w.nurse2().userId().toString(),
                w.nurse().userId().toString());
        assertThat(correctionRowsOfClinic(w.clinic())).isEqualTo(2);

        JsonNode slot = slot(mar(w.nurse().auth(), w.adm().id()), g.order(), 0);
        assertThat(slot.get("state").asText()).isEqualTo("GIVEN");
        assertThat(slot.get("dose").get("id").asText()).isEqualTo(thirdId);
        assertThat(slot.get("history")).as("only the record marked in error is listed").hasSize(1);
        assertThat(slot.get("history").get(0).get("id").asText()).isEqualTo(secondId);
        // a live record blocks the next attempt (the order must be checked first: a STAT order not yet verified with a live dose answers order-not-verified)
        verifyNow(w, g.order());
        refused(doseCall(w.nurse().auth(), g.order(), scanGiven(w, g.slot(), 1, code)), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(audit(w.clinic(), "IPD_DOSE_GIVEN")).hasSize(3);
    }

    // ---------------- Read-only, discharged, another clinic, scope ----------------

    @Test
    void aReadOnlyClinicAndADischargedPatientCannotCorrectADose() throws Exception {
        Ward w = ward();
        G g = given(w);
        String before = written(w.clinic());
        setIpdState(w.clinic(), "readonly");
        refused(undoCall(w.nurse().auth(), g.doseId(), newKey()), 409, "ipd-readonly", READONLY_TEXT);
        refused(errorCall(w.nurse().auth(), g.doseId(), newKey(), "DUPLICATE"), 409, "ipd-readonly", READONLY_TEXT);
        setIpdState(w.clinic(), "off");
        refused(undoCall(w.nurse().auth(), g.doseId(), newKey()), 403, "module-off", OFF_TEXT);
        setIpdState(w.clinic(), "on");
        assertThat(written(w.clinic())).isEqualTo(before);

        discharge(w);
        refused(undoCall(w.nurse().auth(), g.doseId(), newKey()), 409, "not-admitted", DOSE_NOT_ADMITTED);
        refused(errorCall(w.nurse().auth(), g.doseId(), newKey(), "DUPLICATE"), 409, "not-admitted", DOSE_NOT_ADMITTED);
        assertThat(correctionRowsOfClinic(w.clinic())).isZero();
    }

    @Test
    void aNurseOfAnotherClinicSeesNothingAndABadIdIsAnErrorWithAProblemBody() throws Exception {
        Ward w = ward();
        G g = given(w);
        Ward other = ward();
        problem(undoCall(other.nurse().auth(), g.doseId(), newKey()), 404);
        problem(errorCall(other.nurse().auth(), g.doseId(), newKey(), "DUPLICATE"), 404);
        problem(undoCall(w.nurse().auth(), java.util.UUID.randomUUID(), newKey()), 404);
        JsonNode bad = problem(call(HttpMethod.POST, IPD + "/doses/not-a-uuid/undo", w.nurse().auth(), corrBody(newKey(), null)), 400);
        assertType(bad, "bad-request");
        org.springframework.test.web.servlet.request.RequestPostProcessor anonymous = r -> r;
        problem(call(HttpMethod.POST, undoUrl(g.doseId()), anonymous, corrBody(newKey(), null)), 401);
        assertThat(correctionRowsOfClinic(w.clinic())).isZero();
        assertThat(correctionRowsOfClinic(other.clinic())).isZero();
    }

    @Test
    void theWardScopeGuardAppliesToTheDoseAndBothCorrectionsAndTheOwnerSeesEveryWard() throws Exception {
        Hosp h = hosp();
        Pn nurseA2 = newPn(h.clinic(), "NURSE");
        assign(h.clinic(), h.wardA(), h.nurseA(), nurseA2);
        String uhid = values(h.clinic(), "select uhid from patient.patient where id = ?::uuid", h.inA().patientId().toString()).get(0);
        Ward asA = new Ward(h.clinic(), null, h.nurseA(), nurseA2, null, null, null, h.inA(), uhid);
        realClock();
        String order = placeOrder(h.clinic().owner(), h.inA().id(), statOrder("panto", "MG", "IV"));
        Instant slot = slotOf(mar(h.nurseA().auth(), h.inA().id()), order, 0);
        String code = codeOf("panto");

        String before = written(h.clinic());
        outside(doseCall(h.nurseB().auth(), order, scanGiven(asA, slot, 1, code)));
        assertThat(written(h.clinic())).as("a refused dose writes nothing, not even a deny entry").isEqualTo(before);

        JsonNode a = doseOk(h.nurseA().auth(), order, scanGiven(asA, slot, 1, code));
        String doseId = a.get("dose").get("id").asText();
        String afterGive = written(h.clinic());
        outside(undoCall(h.nurseB().auth(), doseId, newKey()));
        outside(errorCall(h.nurseB().auth(), doseId, newKey(), "DUPLICATE"));
        assertThat(written(h.clinic())).isEqualTo(afterGive);
        assertThat(correctionRowsOfClinic(h.clinic())).isZero();

        // a colleague of the same ward may mark it in error but may not undo it; the owner sees every ward
        refused(undoCall(nurseA2.auth(), doseId, newKey()), 403, "forbidden", ONLY_RECORDER);
        errorOk(nurseA2.auth(), doseId, "WRONG_TIME");
        Ward asB = new Ward(h.clinic(), null, h.nurseB(), null, null, null, null, h.inB(),
                values(h.clinic(), "select uhid from patient.patient where id = ?::uuid", h.inB().patientId().toString()).get(0));
        String orderB = placeOrder(h.clinic().owner(), h.inB().id(), statOrder("panto", "MG", "IV"));
        Instant slotB = slotOf(mar(h.nurseB().auth(), h.inB().id()), orderB, 0);
        JsonNode b = doseOk(h.clinic().owner(), orderB, scanGiven(asB, slotB, 1, code));
        errorOk(h.clinic().owner(), b.get("dose").get("id").asText(), "DUPLICATE");
        outside(undoCall(h.nurseA().auth(), b.get("dose").get("id").asText(), newKey()));
    }
}
