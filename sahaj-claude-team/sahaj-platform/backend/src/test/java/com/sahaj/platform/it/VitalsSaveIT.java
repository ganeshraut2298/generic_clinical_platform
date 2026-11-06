package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 AC1, AC2, AC5, AC9, AC12 on the real {@code sahaj_test} database: the Nursing sheet before and after a save,
 * the time shared by every row of one save, the order of the event log, a repeat of the same client key (200, nothing
 * written), the key used for another admission, a repeat after discharge, and a failure inside the save that rolls
 * back the vitals, the note, the events, the escalation and the audit row together. Test plan:
 * docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsSaveIT extends VitalsSupport {

    private record Setup(Clinic clinic, Adm adm) {
    }

    private Setup setup() throws Exception {
        Clinic clinic = hospital();
        return new Setup(clinic, admitOne(clinic));
    }

    // ---------------- the sheet ----------------

    @Test
    void theSheetOfAnAdmissionWithoutVitalsHasNoLatestNoNotesAndVitalsDue() throws Exception {
        Setup s = setup();
        JsonNode sheet = nurseSheet(s.clinic(), s.adm().id());
        assertThat(sheet.get("admissionId").asText()).isEqualTo(s.adm().id());
        assertThat(sheet.get("patientName").asText()).isEqualTo(s.adm().name());
        assertThat(sheet.get("bedCode").asText()).isEqualTo(s.adm().bedCode());
        assertThat(sheet.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(sheet.get("state").asText()).isEqualTo("trial");
        assertThat(sheet.get("canRecord").asBoolean()).isTrue();
        assertThat(sheet.get("readOnlyReason").isNull()).isTrue();
        assertThat(sheet.get("latest").isNull()).isTrue();
        assertThat(sheet.get("recentNotes")).isEmpty();
        assertThat(sheet.get("vitalsDue").asBoolean()).isTrue();
        assertThat(instant(sheet.get("asOf"))).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
        // opening the sheet writes no IPD data
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isZero();
        assertThat(eventCount(s.clinic(), s.adm().id())).isZero();
        assertThat(vitalsAudits(s.clinic())).isZero();
    }

    @Test
    void theAnswerOfASaveIsTheSheetAfterTheSaveAndAGetShowsTheSameLatestWithTheValuesToPrefill() throws Exception {
        Setup s = setup();
        JsonNode saved = save(s.clinic(), s.adm().id(), noted(vit("137/83", "77", "97", "36.7", "17"), "Slept well"));
        JsonNode latest = saved.get("latest");
        assertThat(latest.get("bp").asText()).isEqualTo("137/83");
        assertThat(latest.get("bpSystolic").asInt()).isEqualTo(137);
        assertThat(latest.get("bpDiastolic").asInt()).isEqualTo(83);
        assertThat(latest.get("pulse").asInt()).isEqualTo(77);
        assertThat(latest.get("spo2").asInt()).isEqualTo(97);
        assertThat(latest.get("tempC").decimalValue()).isEqualByComparingTo("36.7");
        assertThat(latest.get("rr").asInt()).isEqualTo(17);
        assertThat(latest.get("ewsScore").asInt()).isZero();
        assertThat(latest.get("ewsRisk").asText()).isEqualTo("LOW");
        assertThat(latest.get("id").asText()).isEqualTo(saved.get("saved").get("vitalsId").asText());
        assertThat(latest.get("recordedByName").asText()).isNotBlank();
        assertThat(saved.get("vitalsDue").asBoolean()).isFalse();
        assertThat(saved.get("canRecord").asBoolean()).isTrue();
        assertThat(saved.get("recentNotes").get(0).get("text").asText()).isEqualTo("Slept well");
        assertThat(saved.get("patientName").asText()).isEqualTo(s.adm().name());

        // a temperature typed without a decimal is stored and shown with one
        JsonNode whole = save(s.clinic(), s.adm().id(), with(low(), "temp", "37"));
        assertThat(whole.get("latest").get("tempC").decimalValue()).isEqualByComparingTo("37.0");
        assertThat(values(s.clinic(), "select temp_c::text from ipd.vitals where id = ?::uuid",
                whole.get("saved").get("vitalsId").asText())).containsExactly("37.0");

        // the sheet opened later: the newest record is the latest, the note field starts empty (no note in the answer)
        JsonNode sheet = nurseSheet(s.clinic(), s.adm().id());
        assertThat(sheet.get("latest").get("id").asText()).isEqualTo(whole.get("saved").get("vitalsId").asText());
        assertThat(sheet.get("latest").get("bp").asText()).isEqualTo("120/80");
        assertThat(sheet.get("recentNotes")).hasSize(1);
        assertThat(sheet.get("vitalsDue").asBoolean()).isFalse();
        assertThat(sheet.get("recentNotes").get(0).get("recordedByName").asText())
                .isEqualTo(latest.get("recordedByName").asText());
    }

    // ---------------- time and order ----------------

    @Test
    void everyRowOfOneSaveCarriesTheTransactionTimeAndTheAnswerAsOfIsNotBeforeThatTime() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        String id = s.adm().id();
        JsonNode saved = save(clinic, id, noted(medium3(), "Restless"));
        Instant recordedAt = instant(saved.get("saved").get("recordedAt"));
        // asOf is the database clock read after the writes (clock_timestamp), so it is never before the saved time
        assertThat(instant(saved.get("asOf"))).isAfterOrEqualTo(recordedAt).isBefore(recordedAt.plusSeconds(30));
        assertThat(instant(saved.get("latest").get("recordedAt"))).isEqualTo(recordedAt);
        assertThat(instant(saved.get("recentNotes").get(0).get("recordedAt"))).isEqualTo(recordedAt);
        assertThat(instant(saved.get("escalation").get("open").get("raisedAt"))).isEqualTo(recordedAt);
        assertThat(instantOf(clinic, "select recorded_at from ipd.vitals where admission_id = ?::uuid", id)).isEqualTo(recordedAt);
        assertThat(instantOf(clinic, "select recorded_at from ipd.nursing_note where admission_id = ?::uuid", id)).isEqualTo(recordedAt);
        assertThat(instantOf(clinic, "select raised_at from ipd.ews_escalation where admission_id = ?::uuid", id)).isEqualTo(recordedAt);
        assertThat(values(clinic, "select count(distinct at)::text from ipd.clinical_event where admission_id = ?::uuid", id))
                .containsExactly("1");
        assertThat(instantOf(clinic, "select max(at) from ipd.clinical_event where admission_id = ?::uuid", id)).isEqualTo(recordedAt);
        assertThat(instantOf(clinic, "select at from platform.audit_event where action = 'IPD_VITALS_RECORDED'"))
                .isBetween(recordedAt.minusSeconds(5), recordedAt.plusSeconds(60));
        assertThat(recordedAt).isBetween(Instant.now().minusSeconds(120), Instant.now().plusSeconds(5));
    }

    @Test
    void theEventsOfOneSaveAreReadEscalationLineFirstBecauseTheirSequenceDecidesAtTheSameTime() throws Exception {
        Setup s = setup();
        save(s.clinic(), s.adm().id(), medium3());
        assertThat(eventLines(s.clinic(), s.adm().id())).hasSize(2);
        assertThat(eventLines(s.clinic(), s.adm().id()).get(0)).startsWith("esc|Raised early warning escalation");
        assertThat(eventLines(s.clinic(), s.adm().id()).get(1)).startsWith("info|Recorded vitals");
        // oldest first by sequence is the order the code wrote them: vitals line, then the escalation line
        String firstWritten = db().inTenant(s.clinic().tenantId(), c -> DbAccess.text(c,
                "select kind from ipd.clinical_event where admission_id = ?::uuid order by seq limit 1", s.adm().id()));
        assertThat(firstWritten).isEqualTo("info");
    }

    // ---------------- idempotency ----------------

    @Test
    void theSameClientKeyTwiceGives201ThenA200ReplayThatWritesOnlyOneRecordAccessRow() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(medium3(), "Needs review");
        JsonNode first = save(clinic, s.adm().id(), body);
        assertThat(first.get("saved").get("replayed").asBoolean()).isFalse();
        Snap afterFirst = snap(clinic, s.adm());
        assertThat(afterFirst.vitals()).isOne();
        assertThat(afterFirst.notes()).isOne();
        assertThat(afterFirst.events()).isEqualTo(2);
        assertThat(vitalsAudits(clinic)).isOne();

        JsonNode second = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), clinic.owner(), js(body), 200);
        assertThat(second.get("saved").get("replayed").asBoolean()).isTrue();
        assertThat(second.get("saved").get("vitalsId").asText()).isEqualTo(first.get("saved").get("vitalsId").asText());
        assertThat(second.get("saved").get("ewsScore").asInt()).isEqualTo(3);
        assertThat(second.get("saved").get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(second.get("saved").get("noteAdded").asBoolean()).isTrue();
        assertThat(instant(second.get("saved").get("recordedAt"))).isEqualTo(instant(first.get("saved").get("recordedAt")));
        assertThat(second.get("escalation").get("outcome").asText()).isEqualTo("NONE");
        assertThat(second.get("escalation").get("open").get("id").asText())
                .isEqualTo(first.get("escalation").get("open").get("id").asText());
        assertThat(second.get("latest").get("id").asText()).isEqualTo(first.get("latest").get("id").asText());
        assertThat(second.get("recentNotes")).hasSize(1);

        // the answer carries the sheet, so the replay is recorded in the record-access log (security review L2): one row,
        // and no vitals, note, event, escalation or audit row
        assertThat(snap(clinic, s.adm())).as("a replay writes one access row and nothing else")
                .isEqualTo(plusAccess(afterFirst, 1));
        assertThat(vitalsAudits(clinic)).isOne();
        // three more taps: still one record, one more access row each
        for (int i = 0; i < 3; i++) {
            replay(clinic, s.adm().id(), body);
        }
        assertThat(snap(clinic, s.adm())).isEqualTo(plusAccess(afterFirst, 4));
    }

    @Test
    void aReplayWithEqualValuesReturnsTheFirstRecordAndTheSheetAsItIsNow() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = low();
        JsonNode first = save(clinic, s.adm().id(), body);
        JsonNode later = save(clinic, s.adm().id(), noted(high7(), "Worse"));
        Snap before = snap(clinic, s.adm());

        // same key, same five values, still no note: a replay (the later records do not matter)
        JsonNode replay = replay(clinic, s.adm().id(), body);
        assertThat(replay.get("saved").get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("saved").get("vitalsId").asText()).isEqualTo(first.get("saved").get("vitalsId").asText());
        assertThat(replay.get("saved").get("ewsScore").asInt()).isZero();
        assertThat(replay.get("saved").get("noteAdded").asBoolean()).isFalse();
        // the sheet is the sheet now: the newest record and its note
        assertThat(replay.get("latest").get("id").asText()).isEqualTo(later.get("saved").get("vitalsId").asText());
        assertThat(replay.get("recentNotes").get(0).get("text").asText()).isEqualTo("Worse");
        assertThat(replay.get("escalation").get("outcome").asText()).isEqualTo("NONE");
        assertThat(replay.get("escalation").get("open").get("ewsScore").asInt()).isEqualTo(7);
        assertThat(instant(replay.get("asOf"))).isAfterOrEqualTo(instant(later.get("saved").get("recordedAt")));
        assertThat(snap(clinic, s.adm())).as("only the record-access row").isEqualTo(plusAccess(before, 1));
    }

    @Test
    void theNoteTextIsNotComparedOnAReplayOnlyWhetherThereIsOne() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(low(), "First wording");
        JsonNode first = save(clinic, s.adm().id(), body);
        Snap before = snap(clinic, s.adm());
        // another text, still a note: equal for the replay rule (the contract: the note text is not compared)
        JsonNode replay = replay(clinic, s.adm().id(), noted(body, "Another wording"));
        assertThat(replay.get("saved").get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("saved").get("vitalsId").asText()).isEqualTo(first.get("saved").get("vitalsId").asText());
        assertThat(replay.get("recentNotes")).hasSize(1);
        assertThat(replay.get("recentNotes").get(0).get("text").asText()).isEqualTo("First wording");
        assertThat(snap(clinic, s.adm())).isEqualTo(plusAccess(before, 1));
    }

    @Test
    void aReusedKeyWithAnyDifferentValueIs409ClientKeyReusedAndNothingIsWrittenNotEvenAnAccessRow() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = low();
        save(clinic, s.adm().id(), body);
        Map<String, Object> withNote = noted(low(), "Has a note");
        save(clinic, s.adm().id(), withNote);
        Snap before = snap(clinic, s.adm());

        // each of the seven compared values on its own, against the first record (120/80, 80, 98, 37.0, 16, no note)
        Object[][] differences = {
                {"bp", "121/80", "systolic"}, {"bp", "120/81", "diastolic"}, {"pulse", "81", "pulse"},
                {"spo2", "97", "spo2"}, {"temp", "37.1", "temperature"}, {"rr", "17", "rr"},
                {"note", "Now a note is sent", "noteAdded (none stored, one sent)"}};
        for (Object[] d : differences) {
            Map<String, Object> other = with(body, (String) d[0], d[1]);
            JsonNode p = problem(saveCall(clinic.owner(), s.adm().id(), other), 409);
            assertType(p, "client-key-reused");
            assertThat(p.get("detail").asText()).as((String) d[2]).isEqualTo(KEY_REUSED_DETAIL);
            assertThat(snap(clinic, s.adm())).as(d[2] + ": nothing written, no access row").isEqualTo(before);
        }
        // the other way round: stored with a note, sent without
        JsonNode p = problem(saveCall(clinic.owner(), s.adm().id(), with(withNote, "note", null)), 409);
        assertType(p, "client-key-reused");
        assertThat(p.get("detail").asText()).isEqualTo(KEY_REUSED_DETAIL);
        // a whole other reading under the same key (a worse one must never be dropped silently)
        JsonNode worse = problem(saveCall(clinic.owner(), s.adm().id(), with(noted(high14(), "Much worse"), "clientKey",
                body.get("clientKey"))), 409);
        assertType(worse, "client-key-reused");
        assertThat(snap(clinic, s.adm())).isEqualTo(before);
        assertThat(openEscalationCount(clinic, s.adm().id())).isZero();
        // equal values under the same key are still a replay afterwards
        assertThat(replay(clinic, s.adm().id(), body).get("saved").get("replayed").asBoolean()).isTrue();
    }

    @Test
    void aReusedKeyWithDifferentValuesAfterTheDischargeIsStill409AndNeverNotAdmitted() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = low();
        save(clinic, s.adm().id(), body);
        discharge(clinic, s.adm().id());
        Snap before = snap(clinic, s.adm());
        JsonNode p = problem(saveCall(clinic.owner(), s.adm().id(), with(body, "pulse", "99")), 409);
        assertType(p, "client-key-reused");
        assertThat(snap(clinic, s.adm())).isEqualTo(before);
    }

    @Test
    void theMissingPatientOfAnAdmissionCannotHappenOnTheRealDatabaseBecauseTheForeignKeyHoldsIt() throws Exception {
        // N6 (a missing patient is a 404, never the name "Patient") is covered by VitalsServiceTest with mocks: here the
        // composite foreign key (fk_admission_patient) makes the state unreachable, so the owner cannot even delete it.
        Setup s = setup();
        Clinic clinic = s.clinic();
        SqlErr err = ownerErr("patient.patient", "delete from patient.patient where tenant_id = ? and id = ?",
                clinic.tenantId(), s.adm().patientId());
        assertThat(err.state()).as(err.message()).isIn("23503", "42501", "23514");
        assertThat(nurseSheet(clinic, s.adm().id()).get("patientName").asText()).isEqualTo(s.adm().name());
    }

    @Test
    void anInvalidBodyOnARepeatIsA400AndNotAReplay() throws Exception {
        // the body is checked before the lock, so an invalid repeat is a 400 and not a replay
        Setup s = setup();
        Map<String, Object> body = low();
        save(s.clinic(), s.adm().id(), body);
        Snap before = snap(s.clinic(), s.adm());
        JsonNode p = refused(s.clinic(), s.adm().id(), with(body, "bp", "x"));
        assertThat(fieldNames(p)).containsExactly("bp");
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    @Test
    void aReplayByAnotherNurseOfTheSameClinicIsAnsweredToo() throws Exception {
        Setup s = setup();
        Map<String, Object> body = low();
        save(s.clinic(), s.adm().id(), body);
        JsonNode replay = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), s.clinic().as("NURSE"), js(body), 200);
        assertThat(replay.get("saved").get("replayed").asBoolean()).isTrue();
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isOne();
    }

    @Test
    void theSameClientKeyForAnotherAdmissionIs400OnClientKeyAndWritesNothingToEither() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Adm second = admitOne(clinic);
        Map<String, Object> body = noted(medium3(), "First patient");
        save(clinic, s.adm().id(), body);
        Snap firstBefore = snap(clinic, s.adm());
        Snap secondBefore = snap(clinic, second);

        JsonNode p = problem(saveCall(clinic.owner(), second.id(), body), 400);
        assertType(p, "validation");
        assertThat(fieldNames(p)).containsExactly("clientKey");
        assertThat(fieldMessage(p, "clientKey")).isEqualTo(KEY_MESSAGE);
        assertThat(snap(clinic, s.adm())).isEqualTo(firstBefore);
        assertThat(snap(clinic, second)).isEqualTo(secondBefore);
        assertThat(vitalsCount(clinic, second.id())).isZero();
    }

    @Test
    void aReplayWithEqualValuesIsStillAnsweredAfterTheDischargeButANewKeyIs409NotAdmitted() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(medium3(), "Before the discharge");
        JsonNode first = save(clinic, s.adm().id(), body);
        discharge(clinic, s.adm().id());
        Snap before = snap(clinic, s.adm());

        JsonNode replay = replay(clinic, s.adm().id(), body);
        assertThat(replay.get("saved").get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("saved").get("vitalsId").asText()).isEqualTo(first.get("saved").get("vitalsId").asText());
        assertThat(replay.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(replay.get("canRecord").asBoolean()).isFalse();
        assertThat(replay.get("readOnlyReason").asText()).isEqualTo("NOT_ADMITTED");
        // the replay writes exactly one record-access row (the answer is the full sheet) and nothing else
        assertThat(snap(clinic, s.adm())).isEqualTo(plusAccess(before, 1));
        before = snap(clinic, s.adm());

        JsonNode refused = problem(saveCall(clinic.owner(), s.adm().id(), newKey(body)), 409);
        assertType(refused, "not-admitted");
        assertThat(refused.get("detail").asText()).isEqualTo(VITALS_NOT_ADMITTED);
        assertThat(snap(clinic, s.adm())).isEqualTo(before);
    }

    // ---------------- a failure inside the save rolls everything back ----------------

    @Test
    void aFailureWhenTheFirstEventIsWrittenRollsBackTheVitalsTheNoteAndEverythingAndTheSameKeyThenSavesNormally() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(high7(), "ZXQ must not be kept");
        failingInsert(clinic, "ipd.clinical_event", "new.kind = 'info'", () -> {
            JsonNode p = problem(saveCall(clinic.owner(), s.adm().id(), body), 500);
            assertThat(p.toString()).doesNotContain("forced failure").doesNotContain("ZXQ").doesNotContain("trigger");
        });
        assertNothingKept(clinic, s.adm());
        assertThat(retrySaves(clinic, s.adm(), body)).isTrue();
    }

    @Test
    void aFailureWhenTheEscalationLineIsWrittenRollsBackTheNewEscalationToo() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(medium3(), "Kept only when everything works");
        failingInsert(clinic, "ipd.clinical_event", "new.kind = 'esc'", () ->
                problem(saveCall(clinic.owner(), s.adm().id(), body), 500));
        assertNothingKept(clinic, s.adm());
        assertThat(retrySaves(clinic, s.adm(), body)).isTrue();
        assertThat(openEscalation(clinic, s.adm().id())).isEqualTo("3|MEDIUM|60|OPEN|0");
    }

    @Test
    void aFailureWhenTheOpenEscalationWasAlreadyUpdatedPutsTheOldScoreBack() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        save(clinic, s.adm().id(), noted(medium3(), "Before"));
        Snap before = snap(clinic, s.adm());
        assertThat(openEscalation(clinic, s.adm().id())).isEqualTo("3|MEDIUM|60|OPEN|0");

        Map<String, Object> worse = noted(high7(), "Worse");
        failingInsert(clinic, "ipd.clinical_event", "new.kind = 'esc'", () ->
                problem(saveCall(clinic.owner(), s.adm().id(), worse), 500));
        assertThat(snap(clinic, s.adm())).as("the update of the escalation was rolled back with the rest").isEqualTo(before);
        assertThat(openEscalation(clinic, s.adm().id())).isEqualTo("3|MEDIUM|60|OPEN|0");

        JsonNode retry = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), clinic.owner(), js(worse), 201);
        assertThat(retry.get("saved").get("replayed").asBoolean()).isFalse();
        assertThat(retry.get("escalation").get("outcome").asText()).isEqualTo("UPDATED");
        assertThat(openEscalation(clinic, s.adm().id())).isEqualTo("7|HIGH|15|OPEN|1");
    }

    @Test
    void aFailureWhenTheAuditRowIsWrittenLastRollsBackEverythingBeforeIt() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Map<String, Object> body = noted(high7(), "Audit must not be the odd one out");
        failingInsert(clinic, "platform.audit_event", "new.action = 'IPD_VITALS_RECORDED'", () ->
                problem(saveCall(clinic.owner(), s.adm().id(), body), 500));
        assertNothingKept(clinic, s.adm());
        assertThat(retrySaves(clinic, s.adm(), body)).isTrue();
    }

    private void assertNothingKept(Clinic clinic, Adm adm) throws Exception {
        assertThat(vitalsCount(clinic, adm.id())).as("vitals").isZero();
        assertThat(noteCount(clinic, adm.id())).as("notes").isZero();
        assertThat(eventCount(clinic, adm.id())).as("events").isZero();
        assertThat(escalationCount(clinic, adm.id())).as("escalations").isZero();
        assertThat(vitalsAudits(clinic)).as("audit rows").isZero();
        assertThat(nurseSheet(clinic, adm.id()).get("latest").isNull()).as("the sheet shows no saved record").isTrue();
    }

    /** The very same request (same key) after the failure is a normal first save: 201, not a replay. */
    private boolean retrySaves(Clinic clinic, Adm adm, Map<String, Object> body) throws Exception {
        JsonNode retry = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(adm.id()), clinic.owner(), js(body), 201);
        assertThat(retry.get("saved").get("replayed").asBoolean()).isFalse();
        assertThat(vitalsCount(clinic, adm.id())).isOne();
        assertThat(noteCount(clinic, adm.id())).isOne();
        assertThat(vitalsAudits(clinic)).isOne();
        assertThat(eventCount(clinic, adm.id())).isGreaterThanOrEqualTo(1);
        return true;
    }
}
