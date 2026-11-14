package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.support.LogCapture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-3 and SAH-51-4 on the real database (docs/api/opd.yaml 0.6.1, ADR-025 D4, D5): the two-minute edit hold that
 * opening and every save take or renew, the read-only banner for a second editor, expiry, release, the owner's take-over,
 * and autosave (AUTO writes no draft audit). Also the rule "after an admission request the next save must not 409".
 * Test plan: docs/test-plans/SAH-51-68.md (T-51-3.x, T-51-4.x).
 */
@OnTestDatabase
class EmrEditHoldIT extends EmrSignSupport {

    private String holdPath(Rec rec) {
        return RECORDS + "/" + rec.id() + "/edit-hold";
    }

    private List<String> holdColumns(Rec rec) throws Exception {
        return row(rec.clinic(), "select edit_hold_membership_id::text, edit_hold_user_id::text, "
                + "(edit_hold_expires_at > now())::text, (edit_hold_expires_at > now() + interval '60 seconds')::text "
                + "from opd.emr_record where id = ?::uuid", rec.id());
    }

    private void expireHold(Rec rec) throws Exception {
        commit(rec.clinic().tenantId(), "update opd.emr_record set edit_hold_expires_at = now() - interval '5 seconds' "
                + "where id = ?::uuid", rec.id());
    }

    private Staff ownerEditor(Rec rec) throws Exception {
        return ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
    }

    private String saveBody(Rec rec, String note) {
        return draftBody(rec.revision(), "ASSESSMENT", null, sectionValues0("hpi", "{\"narrative\":\"" + note + "\"}")).toString();
    }

    private ObjectNode sectionValues0(String key, String json) {
        try {
            return sectionValues(key, json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- taking the hold ----------------

    @Test
    void openingARecordTakesTheHoldForTheDoctorForTwoMinutes() throws Exception {
        Rec rec = newRec();
        JsonNode view = record(rec.token(), rec.id());
        assertThat(view.get("editHold").get("mine").asBoolean()).isTrue();
        assertThat(view.get("editHold").get("holderMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(view.get("editHold").get("canTakeOver").asBoolean()).isFalse();
        assertThat(view.get("readOnly").asBoolean()).isFalse();
        Instant expires = Instant.parse(view.get("editHold").get("expiresAt").asText());
        assertThat(expires).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(125));
        List<String> columns = holdColumns(rec);
        assertThat(columns.get(0)).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(columns.get(1)).isEqualTo(rec.doctor().userId().toString());
        assertThat(columns.get(2)).isEqualTo("true");
    }

    @Test
    void everyDraftSaveRenewsTheHoldToAboutTwoMinutesFromNow() throws Exception {
        Rec rec = newRec();
        commit(rec.clinic().tenantId(), "update opd.emr_record set edit_hold_expires_at = now() + interval '5 seconds' where id = ?::uuid", rec.id());
        assertThat(holdColumns(rec).get(3)).isEqualTo("false");
        save(rec, sectionValues("hpi", "{\"narrative\":\"renews\"}"));
        assertThat(holdColumns(rec).get(3)).as("more than 60 seconds left after the save").isEqualTo("true");
    }

    @Test
    void theTickThatHasNothingToSaveRenewsTheHoldWithAPutAndAnswersWhoHoldsIt() throws Exception {
        Rec rec = newRec();
        commit(rec.clinic().tenantId(), "update opd.emr_record set edit_hold_expires_at = now() + interval '5 seconds' where id = ?::uuid", rec.id());
        JsonNode hold = put(holdPath(rec), rec.token(), null, 200);
        assertThat(hold.get("mine").asBoolean()).isTrue();
        assertThat(Instant.parse(hold.get("expiresAt").asText())).isAfter(Instant.now().plusSeconds(60));
        assertThat(holdColumns(rec).get(3)).isEqualTo("true");
        assertThat(auditCount(rec.clinic(), "EMR_EDIT_HOLD_TAKEN_OVER")).isZero();
    }

    // ---------------- a second editor ----------------

    @Test
    void aSecondEditorSeesTheRecordReadOnlyWithTheBannerAndCannotSaveSignOrTakeTheHold() throws Exception {
        Rec rec = readyRec();
        Staff owner = ownerEditor(rec);
        JsonNode view = record(owner.token(rec.clinic()), rec.id());
        assertThat(view.get("readOnly").asBoolean()).isTrue();
        assertThat(view.get("readOnlyReason").asText()).isEqualTo("HELD_BY_OTHER");
        assertThat(view.get("editHold").get("mine").asBoolean()).isFalse();
        assertThat(view.get("editHold").get("holderName").asText()).isNotBlank();
        assertThat(view.get("editHold").get("canTakeOver").asBoolean()).as("the owner may take over").isTrue();
        JsonNode save = problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", owner.token(rec.clinic()),
                saveBody(rec, "by the owner"), 409, "emr-record-held");
        assertThat(save.get("detail").asText()).startsWith("Open for editing by Dr.");
        assertThat(save.get("holderName").asText()).startsWith("Dr.");
        problemKeyed(HttpMethod.POST, signPath(rec.id()), owner.token(rec.clinic()), signBody(rec.revision(), "{}"), key(), 409, "emr-record-held");
        problemOf(HttpMethod.PUT, holdPath(rec), owner.token(rec.clinic()), null, 409, "emr-record-held");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(holdColumns(rec).get(0)).as("the doctor still holds it").isEqualTo(rec.doctor().membershipId().toString());
        // the holder's own view is writable
        assertThat(record(rec.token(), rec.id()).get("readOnly").asBoolean()).isFalse();
    }

    @Test
    void theDoctorWhoOpensWhileTheOwnerHoldsItSeesTheBannerWithoutATakeOverButton() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff owner = ownerWithDoctorDetails(clinic, "General physician", "MBBS", "REG-" + shortId());
        JsonNode visit = issueVisit(clinic, doctor.membershipId());
        JsonNode opened = openVisit(owner.token(clinic), visit.get("id").asText(), 201).get("record");
        assertThat(opened.get("editHold").get("mine").asBoolean()).isTrue();
        JsonNode seenByDoctor = openVisit(doctor.token(clinic), visit.get("id").asText(), 200).get("record");
        assertThat(seenByDoctor.get("readOnly").asBoolean()).isTrue();
        assertThat(seenByDoctor.get("readOnlyReason").asText()).isEqualTo("HELD_BY_OTHER");
        assertThat(seenByDoctor.get("editHold").get("canTakeOver").asBoolean()).isFalse();
        problemOf(HttpMethod.PUT, RECORDS + "/" + opened.get("id").asText() + "/draft", doctor.token(clinic),
                draftBody(opened.get("revision").asLong(), "ASSESSMENT", null).toString(), 409, "emr-record-held");
    }

    // ---------------- expiry, release, take over ----------------

    @Test
    void anExpiredHoldCanBeTakenByTheOtherEditorBySavingAndThenTheFirstDoctorIsHeldOut() throws Exception {
        Rec rec = newRec();
        Staff owner = ownerEditor(rec);
        expireHold(rec);
        JsonNode view = record(owner.token(rec.clinic()), rec.id());
        assertThat(view.get("editHold").isNull()).as("an expired hold is no hold").isTrue();
        assertThat(view.get("readOnly").asBoolean()).isFalse();
        JsonNode saved = put(RECORDS + "/" + rec.id() + "/draft", owner.token(rec.clinic()), saveBody(rec, "the owner saves"), 200);
        assertThat(saved.get("editHold").get("mine").asBoolean()).isTrue();
        assertThat(holdColumns(rec).get(0)).isEqualTo(owner.membershipId().toString());
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), saveBody(rec.with(saved), "the doctor saves"), 409, "emr-record-held");
    }

    @Test
    void theHolderReleasesWithDeleteAndAnotherPersonsDeleteReleasesNothing() throws Exception {
        Rec rec = newRec();
        Staff owner = ownerEditor(rec);
        ok(HttpMethod.DELETE, holdPath(rec), owner.token(rec.clinic()), null, 204);
        assertThat(holdColumns(rec).get(0)).as("not the owner's hold: left as it was").isEqualTo(rec.doctor().membershipId().toString());
        ok(HttpMethod.DELETE, holdPath(rec), rec.token(), null, 204);
        assertThat(holdColumns(rec)).containsOnlyNulls();
        JsonNode saved = put(RECORDS + "/" + rec.id() + "/draft", owner.token(rec.clinic()), saveBody(rec, "free now"), 200);
        assertThat(saved.get("readOnly").asBoolean()).isFalse();
    }

    @Test
    void theOwnerTakesOverWithTheAuditedPutAndTheDoctorsNextSaveIs409ThenTheOwnerSignsInHisOwnName() throws Exception {
        Rec rec = readyRec();
        Staff owner = ownerEditor(rec);
        JsonNode hold = put(holdPath(rec), owner.token(rec.clinic()), "{\"takeOver\":true}", 200);
        assertThat(hold.get("mine").asBoolean()).isTrue();
        assertThat(holdColumns(rec).get(0)).isEqualTo(owner.membershipId().toString());
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_EDIT_HOLD_TAKEN_OVER");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(2)).contains(rec.doctor().membershipId().toString()).doesNotContain("Dr ");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), saveBody(rec, "late"), 409, "emr-record-held");
        JsonNode doctorsView = record(rec.token(), rec.id());
        assertThat(doctorsView.get("readOnly").asBoolean()).isTrue();
        assertThat(doctorsView.get("readOnlyReason").asText()).isEqualTo("HELD_BY_OTHER");
        JsonNode result = okKeyed(HttpMethod.POST, signPath(rec.id()), owner.token(rec.clinic()), signBody(rec.revision(), "{}"), key(), 200);
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(signColumns(rec.clinic(), rec.id()).get(2)).as("signed by the owner's membership").isEqualTo(owner.membershipId().toString());
    }

    @Test
    void takeOverIsForTheOwnerOnlyAndEveryNonDoctorRoleIs403OnTheHoldEndpoints() throws Exception {
        Rec rec = newRec();
        problemOf(HttpMethod.PUT, holdPath(rec), rec.token(), "{\"takeOver\":true}", 403, "forbidden");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.PUT, holdPath(rec), staff.token(rec.clinic()), null, 403, "forbidden");
            problemOf(HttpMethod.DELETE, holdPath(rec), staff.token(rec.clinic()), null, 403, "forbidden");
        }
        Rec other = newRec();
        problemOf(HttpMethod.PUT, holdPath(rec), other.token(), null, 404, "record-not-found");
        problemOf(HttpMethod.DELETE, holdPath(rec), other.token(), null, 404, "record-not-found");
        problemOf(HttpMethod.PUT, holdPath(rec), anonymous(), null, 401, "unauthorized");
        assertThat(auditCount(rec.clinic(), "EMR_EDIT_HOLD_TAKEN_OVER")).isZero();
        assertThat(holdColumns(rec).get(0)).isEqualTo(rec.doctor().membershipId().toString());
    }

    @Test
    void anotherDoctorCannotTakeTheHoldOfAPatientThatIsNotTheirs() throws Exception {
        Rec rec = newRec();
        Staff other = addDoctor(rec.clinic(), "Dentist");
        problemOf(HttpMethod.PUT, holdPath(rec), other.token(rec.clinic()), null, 403, "not-own-patient");
    }

    // ---------------- autosave and revision ----------------

    @Test
    void anAutosaveWritesNoDraftAuditAndAManualSaveWritesOne() throws Exception {
        Rec rec = newRec();
        long before = auditCount(rec.clinic(), "EMR_RECORD_DRAFT_SAVED");
        ObjectNode auto = draftBody(rec.revision(), "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"one\"}"));
        auto.put("saveKind", "AUTO");
        JsonNode first = put(RECORDS + "/" + rec.id() + "/draft", rec.token(), auto.toString(), 200);
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_DRAFT_SAVED")).as("AUTO is not audited").isEqualTo(before);
        ObjectNode manual = draftBody(first.get("revision").asLong(), "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"two\"}"));
        manual.put("saveKind", "MANUAL");
        JsonNode second = put(RECORDS + "/" + rec.id() + "/draft", rec.token(), manual.toString(), 200);
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_DRAFT_SAVED")).isEqualTo(before + 1);
        put(RECORDS + "/" + rec.id() + "/draft", rec.token(),
                draftBody(second.get("revision").asLong(), "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"three\"}")).toString(), 200);
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_DRAFT_SAVED")).as("no saveKind means MANUAL").isEqualTo(before + 2);
    }

    @Test
    void anUnknownSaveKindIs400BecauseTheContractAllowsOnlyManualAndAuto() throws Exception {
        Rec rec = newRec();
        ObjectNode body = draftBody(rec.revision(), "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"x\"}"));
        body.put("saveKind", "LOUD");
        List<String> holdBefore = holdColumns(rec);
        long audits = emrAudits(rec.clinic());
        long revision = Long.parseLong(row(rec.clinic(), "select revision::text from opd.emr_record where id = ?::uuid", rec.id()).get(0));
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), body.toString(), 400, "validation");
        // F-1: refused before the hold is taken, nothing is saved and nothing is audited
        assertThat(holdColumns(rec)).as("no hold taken or renewed").isEqualTo(holdBefore);
        assertThat(emrAudits(rec.clinic())).as("nothing audited").isEqualTo(audits);
        assertThat(row(rec.clinic(), "select revision::text from opd.emr_record where id = ?::uuid", rec.id()).get(0))
                .as("nothing saved").isEqualTo(Long.toString(revision));
    }

    @Test
    void aSaveOfAnOldRevisionIs409EmrRecordChangedAndNothingIsOverwritten() throws Exception {
        Rec rec = newRec();
        Rec first = save(rec, sectionValues("hpi", "{\"narrative\":\"first\"}"));
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), saveBody(rec, "stale"), 409, "emr-record-changed");
        JsonNode current = record(rec.token(), rec.id());
        assertThat(current.get("revision").asLong()).isEqualTo(first.revision());
        assertThat(valueOf(current, "hpi", "narrative").asText()).isEqualTo("first");
    }

    // ---------------- after an admission request the next save must not 409 ----------------

    @Test
    void afterAnAdmissionRequestTheNextSaveWithTheClientsRevisionDoesNotConflict() throws Exception {
        Rec rec = newRec();
        ok(HttpMethod.POST, TRIAL_URL, rec.clinic().owner(), null, 200);
        Rec filled = fillRequired(rec);
        JsonNode requested = okKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/admission-request", rec.token(),
                "{\"admissionType\":\"planned\",\"plannedStayDays\":3,\"note\":\"Observation\"}", key(), 201);
        assertThat(requested.get("status").asText()).isEqualTo("OPEN");
        JsonNode view = record(rec.token(), rec.id());
        assertThat(view.get("admission").get("state").asText()).isEqualTo("REQUESTED");
        assertThat(view.get("admissionAdvised").asBoolean()).isTrue();
        assertThat(view.get("revision").asLong()).as("the request does not bump the revision").isEqualTo(filled.revision());
        JsonNode saved = put(RECORDS + "/" + rec.id() + "/draft", rec.token(), saveBody(filled, "after the request"), 200);
        assertThat(saved.get("revision").asLong()).isEqualTo(filled.revision() + 1);
        assertThat(saved.get("admission").get("state").asText()).isEqualTo("REQUESTED");
        // and the visit still signs with the new revision
        okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(saved.get("revision").asLong(), "{}"), key(), 200);
    }

    @Test
    void noDraftTextIsLoggedByASaveARenewalOrAHoldConflict() throws Exception {
        Rec rec = newRec();
        Staff owner = ownerEditor(rec);
        try (LogCapture logs = new LogCapture()) {
            put(RECORDS + "/" + rec.id() + "/draft", rec.token(), saveBody(rec, "Zxqhold secret note"), 200);
            problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", owner.token(rec.clinic()), saveBody(rec, "Zxqhold other note"), 409, "emr-record-held");
            assertThat(logs.allText()).doesNotContain("Zxqhold");
        }
        assertThat(allAuditText(rec.clinic())).doesNotContain("Zxqhold");
    }
}
