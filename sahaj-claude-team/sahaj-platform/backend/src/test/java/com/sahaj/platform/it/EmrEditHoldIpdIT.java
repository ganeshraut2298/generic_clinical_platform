package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-4 for the hospital side on the real database (docs/api/opd.yaml 0.6.2, ADR-025 D4, DEC-048 H35, security L6): the
 * admitting doctor of an IPD round may take over another doctor's edit hold (audited {@code EMR_EDIT_HOLD_TAKEN_OVER}, ids
 * only), an owner may take over any hold, and any other doctor is 403 {@code forbidden}. For an Emergency assessment only the
 * owner may take over. The OPD rules are in EmrEditHoldIT. Test plan: docs/test-plans/SAH-51-68.md (T-51-4.9, T-51-4.10).
 */
@OnTestDatabase
class EmrEditHoldIpdIT extends EmrIpdSupport {

    private static final String TAKE_OVER = "{\"takeOver\":true}";

    private String holdPath(String recordId) {
        return EMR_RECORDS + "/" + recordId + "/edit-hold";
    }

    private long takeOvers(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from platform.audit_event where action = 'EMR_EDIT_HOLD_TAKEN_OVER'");
    }

    private String holder(Clinic clinic, String recordId) throws Exception {
        return values(clinic, "select edit_hold_membership_id::text from opd.emr_record where id = ?::uuid", UUID.fromString(recordId)).get(0);
    }

    // ---------------- IPD round: the admitting doctor may take over ----------------

    @Test
    void theAdmittingDoctorTakesOverAnIpdRoundHeldByAnotherDoctorWithOneAuditEntryAndTheFirstDoctorIsThenHeldOut() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        Pn other = newPn(clinic, "DOCTOR");
        // the other doctor (not admitting) opens the round and holds it
        JsonNode opened = openRecord(other.auth(), "IPD_ROUND", adm.id(), 201).get("record");
        String id = opened.get("id").asText();
        assertThat(holder(clinic, id)).isEqualTo(other.membershipId().toString());
        var admitting = admittingDoctor(clinic);
        JsonNode seen = getRecord(admitting, id);
        assertThat(seen.get("readOnly").asBoolean()).isTrue();
        assertThat(seen.get("editHold").get("mine").asBoolean()).isFalse();
        assertThat(seen.get("editHold").get("canTakeOver").asBoolean()).as("the admitting doctor is offered 'Take over'").isTrue();
        // without the take-over the admitting doctor is held out like anyone else
        problemOf(HttpMethod.PUT, holdPath(id), admitting, null, 409, "emr-record-held");
        assertThat(takeOvers(clinic)).isZero();
        JsonNode hold = ok(HttpMethod.PUT, holdPath(id), admitting, TAKE_OVER, 200);
        assertThat(hold.get("mine").asBoolean()).isTrue();
        assertThat(holder(clinic, id)).as("the admitting doctor holds it now").isNotEqualTo(other.membershipId().toString());
        List<List<String>> audits = db().inTenant(clinic.tenantId(), c -> EmrSupport.allRows(c,
                "select after_json::text from platform.audit_event where action = 'EMR_EDIT_HOLD_TAKEN_OVER'"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(0)).contains(other.membershipId().toString()).doesNotContain("Dr ");
        // the first doctor's next save is refused and the screen turns read-only
        problemOf(HttpMethod.PUT, holdPath(id), other.auth(), null, 409, "emr-record-held");
        JsonNode theirs = getRecord(other.auth(), id);
        assertThat(theirs.get("readOnly").asBoolean()).isTrue();
        assertThat(theirs.get("readOnlyReason").asText()).isEqualTo("HELD_BY_OTHER");
        assertThat(theirs.get("editHold").get("canTakeOver").asBoolean()).as("not the admitting doctor, not the owner").isFalse();
    }

    @Test
    void aDoctorWhoIsNotTheAdmittingDoctorCannotTakeOverAnIpdRoundHoldAndTheOwnerCan() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        giveOwnerDoctorDetails(clinic);
        var admitting = admittingDoctor(clinic);
        JsonNode opened = openRecord(admitting, "IPD_ROUND", adm.id(), 201).get("record");
        String id = opened.get("id").asText();
        Pn third = newPn(clinic, "DOCTOR");
        problemOf(HttpMethod.PUT, holdPath(id), third.auth(), TAKE_OVER, 403, "forbidden");
        assertThat(takeOvers(clinic)).isZero();
        assertThat(holder(clinic, id)).as("the admitting doctor still holds it").isNotEqualTo(third.membershipId().toString());
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            problemOf(HttpMethod.PUT, holdPath(id), newPn(clinic, role).auth(), TAKE_OVER, 403, "forbidden");
        }
        ok(HttpMethod.PUT, holdPath(id), clinic.owner(), TAKE_OVER, 200);
        assertThat(takeOvers(clinic)).as("the owner's take-over is audited too").isOne();
        problemOf(HttpMethod.PUT, holdPath(id), request -> request, TAKE_OVER, 401, "unauthorized");
    }

    // ---------------- Emergency assessment: owner only ----------------

    @Test
    void anEmergencyAssessmentHoldIsTakenOverByTheOwnerOnlyNotByTheAdmittingDoctor() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Asha Patil", 3);
        giveOwnerDoctorDetails(clinic);
        Pn other = newPn(clinic, "DOCTOR");
        String id = openRecord(other.auth(), "ER_ASSESSMENT", caseId, 201).get("record").get("id").asText();
        var admitting = admittingDoctor(clinic);
        JsonNode seen = getRecord(admitting, id);
        assertThat(seen.get("editHold").get("canTakeOver").asBoolean()).as("H35 covers an IPD round, not an Emergency assessment").isFalse();
        problemOf(HttpMethod.PUT, holdPath(id), admitting, TAKE_OVER, 403, "forbidden");
        assertThat(takeOvers(clinic)).isZero();
        assertThat(holder(clinic, id)).isEqualTo(other.membershipId().toString());
        ok(HttpMethod.PUT, holdPath(id), clinic.owner(), TAKE_OVER, 200);
        assertThat(takeOvers(clinic)).isOne();
    }

    // ---------------- a hold that lapses or a record signed meanwhile ----------------

    @Test
    void aTakeOverOfARoundThatWasSignedMeanwhileIs409EmrRecordSignedNeverAnotherDoctorIsEditing() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        var admitting = admittingDoctor(clinic);
        JsonNode opened = openRecord(admitting, "IPD_ROUND", adm.id(), 201).get("record");
        JsonNode filled = fillWith(admitting, opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
        sign(admitting, filled, "{}");
        String id = filled.get("id").asText();
        problemOf(HttpMethod.PUT, holdPath(id), admitting, TAKE_OVER, 409, "emr-record-signed");
        problemOf(HttpMethod.PUT, holdPath(id), clinic.owner(), TAKE_OVER, 409, "emr-record-signed");
        assertThat(takeOvers(clinic)).isZero();
    }
}
