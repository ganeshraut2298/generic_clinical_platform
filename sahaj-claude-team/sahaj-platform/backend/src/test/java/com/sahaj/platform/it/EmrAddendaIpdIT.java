package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-51-5 for the hospital side on the real database (docs/api/opd.yaml 0.6.2, ADR-025 D3, DEC-048 H29): an addendum to a
 * signed IPD round or Emergency assessment is added by its signer, the admitting doctor (IPD round) or an owner with doctor
 * details; any other doctor with IPD scope is 403 {@code not-own-patient} (a correction to another doctor's signed note stays
 * with that doctor). The OPD rules are in EmrAddendaIT. Test plan: docs/test-plans/SAH-51-68.md (T-51-5.7, T-51-5.8).
 */
@OnTestDatabase
class EmrAddendaIpdIT extends EmrIpdSupport {

    private static final String BODY = "{\"reason\":\"Correction\",\"text\":\"Dose to be checked\"}";

    private String path(String recordId) {
        return EMR_RECORDS + "/" + recordId + "/addenda";
    }

    private long addenda(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from opd.emr_record_addendum");
    }

    /** A signed IPD round of an admitted patient: the admitting doctor filled and signed it. */
    private record Signed(Clinic clinic, Adm adm, RequestPostProcessor admitting, String recordId) {
    }

    private Signed signedRound() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        RequestPostProcessor doctor = admittingDoctor(clinic);
        JsonNode opened = openRecord(doctor, "IPD_ROUND", adm.id(), 201).get("record");
        JsonNode filled = fillWith(doctor, opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
        sign(doctor, filled, "{}");
        return new Signed(clinic, adm, doctor, filled.get("id").asText());
    }

    // ---------------- IPD round ----------------

    @Test
    void anIpdRoundTakesAnAddendumFromTheAdmittingDoctorAndFromAnOwnerWithDoctorDetailsButNotFromAnotherDoctor() throws Exception {
        Signed s = signedRound();
        giveOwnerDoctorDetails(s.clinic());
        Pn other = newPn(s.clinic(), "DOCTOR");
        problemKeyed(HttpMethod.POST, path(s.recordId()), other.auth(), BODY, idemKey(), 403, "not-own-patient");
        assertThat(addenda(s.clinic())).as("another doctor with IPD scope wrote nothing").isZero();
        assertThat(getRecord(other.auth(), s.recordId()).get("canAddAddendum").asBoolean()).as("the screen does not offer it").isFalse();
        // the admitting doctor (who is also the signer here)
        assertThat(getRecord(s.admitting(), s.recordId()).get("canAddAddendum").asBoolean()).isTrue();
        okKeyed(HttpMethod.POST, path(s.recordId()), s.admitting(), BODY, idemKey(), 201);
        // an owner with doctor details
        assertThat(getRecord(s.clinic().owner(), s.recordId()).get("canAddAddendum").asBoolean()).isTrue();
        okKeyed(HttpMethod.POST, path(s.recordId()), s.clinic().owner(), BODY, idemKey(), 201);
        assertThat(addenda(s.clinic())).isEqualTo(2);
        // the other roles stay refused as before
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            problemKeyed(HttpMethod.POST, path(s.recordId()), newPn(s.clinic(), role).auth(), BODY, idemKey(), 403, "forbidden");
        }
        assertThat(addenda(s.clinic())).isEqualTo(2);
    }

    @Test
    void theOwnersSignedRoundTakesAnAddendumFromTheOwnerAsSignerAndFromTheAdmittingDoctorButNotFromAThirdDoctor() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        giveOwnerDoctorDetails(clinic);
        JsonNode opened = openRecord(clinic.owner(), "IPD_ROUND", adm.id(), 201).get("record");
        JsonNode filled = fillWith(clinic.owner(), opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
        sign(clinic.owner(), filled, "{}");
        String id = filled.get("id").asText();
        okKeyed(HttpMethod.POST, path(id), clinic.owner(), BODY, idemKey(), 201);
        okKeyed(HttpMethod.POST, path(id), admittingDoctor(clinic), BODY, idemKey(), 201);
        problemKeyed(HttpMethod.POST, path(id), newPn(clinic, "DOCTOR").auth(), BODY, idemKey(), 403, "not-own-patient");
        assertThat(addenda(clinic)).isEqualTo(2);
    }

    // ---------------- Emergency assessment ----------------

    /** The second doctor opens, fills and signs an Emergency assessment; the admitting doctor of the hospital did not. */
    private record Er(Clinic clinic, String recordId, Pn signer, RequestPostProcessor other) {
    }

    private Er signedEr() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Asha Patil", 3);
        Pn signer = newPn(clinic, "DOCTOR");
        JsonNode opened = openRecord(signer.auth(), "ER_ASSESSMENT", caseId, 201).get("record");
        JsonNode filled = fillTyped(signer.auth(), opened);
        sign(signer.auth(), filled, "{\"erOutcome\":{\"kind\":\"DISCHARGE\"}}");
        return new Er(clinic, filled.get("id").asText(), signer, admittingDoctor(clinic));
    }

    @Test
    void anEmergencyAssessmentTakesAnAddendumFromItsSignerAndAnOwnerButNotFromAnotherDoctorWithIpdScope() throws Exception {
        Er e = signedEr();
        giveOwnerDoctorDetails(e.clinic());
        problemKeyed(HttpMethod.POST, path(e.recordId()), e.other(), BODY, idemKey(), 403, "not-own-patient");
        assertThat(getRecord(e.other(), e.recordId()).get("canAddAddendum").asBoolean()).isFalse();
        assertThat(addenda(e.clinic())).isZero();
        assertThat(getRecord(e.signer().auth(), e.recordId()).get("canAddAddendum").asBoolean()).isTrue();
        okKeyed(HttpMethod.POST, path(e.recordId()), e.signer().auth(), BODY, idemKey(), 201);
        okKeyed(HttpMethod.POST, path(e.recordId()), e.clinic().owner(), BODY, idemKey(), 201);
        assertThat(addenda(e.clinic())).isEqualTo(2);
    }

    @Test
    void aRefusedAddendumWritesNoRowNoAuditEntryAndNeverEchoesTheNoteOrTheCorrection() throws Exception {
        Er e = signedEr();
        JsonNode problem = problemKeyed(HttpMethod.POST, path(e.recordId()), e.other(),
                "{\"reason\":\"Zxqreason\",\"text\":\"Zxqtext\"}", idemKey(), 403, "not-own-patient");
        assertThat(problem.toString()).doesNotContain("Zxqreason").doesNotContain("Zxqtext");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'EMR_ADDENDUM_ADDED'")).isZero();
        assertThat(addenda(e.clinic())).isZero();
    }
}
