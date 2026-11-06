package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-27 tail items on the real {@code sahaj_test} database. (1) The discharge sheet of a discharged or unknown
 * admission answers 409 not-admitted or 404 and writes no {@code patient.record_access} row (the access is logged only
 * when the sheet is really shown). (2) The stale-signer rule of security review L1: a person whose role was changed or
 * who was deactivated cannot sign the discharge summary with the older token, because the membership is read again; a
 * real second person is used, not a forged token. Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeTailIT extends DischargeSupport {

    private static final String TEAM = "/api/v1/platform/team";

    // ---------------- (1) the sheet and the access log ----------------

    @Test
    void theSheetOfADischargedOrUnknownAdmissionAnswers409Or404AndWritesNoRecordAccessRow() throws Exception {
        Clinic clinic = hospital();
        String live = quickAdmit(clinic).get("id").asText();
        String done = quickAdmit(clinic).get("id").asText();
        signAndDischarge(clinic, done);
        UUID donePatient = UUID.fromString(patientOf(clinic, done));
        UUID livePatient = UUID.fromString(patientOf(clinic, live));

        // positive control: the sheet of an admitted patient is shown and writes one access row
        long liveBefore = recordAccess(clinic, livePatient);
        sheet(clinic, live);
        assertThat(recordAccess(clinic, livePatient)).as("a shown sheet is logged once").isEqualTo(liveBefore + 1);

        long totalBefore = count(clinic, "select count(*) from patient.record_access");
        long doneBefore = recordAccess(clinic, donePatient);
        for (String role : READERS) {
            JsonNode gone = problem(call(HttpMethod.GET, dischargeUrl(done), clinic.as(role), null), 409);
            assertType(gone, "not-admitted");
            assertThat(gone.get("detail").asText()).as(role).isEqualTo(NOT_ADMITTED_SHEET);
            problem(call(HttpMethod.GET, dischargeUrl(UUID.randomUUID()), clinic.as(role), null), 404);
        }
        // the other roles are refused before anything is read
        for (String role : NON_READERS) {
            assertType(problem(call(HttpMethod.GET, dischargeUrl(done), clinic.as(role), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.GET, dischargeUrl(UUID.randomUUID()), clinic.as(role), null), 403), "forbidden");
        }
        assertThat(recordAccess(clinic, donePatient)).as("the discharged patient's record was not opened").isEqualTo(doneBefore);
        assertThat(count(clinic, "select count(*) from patient.record_access")).as("no access row at all").isEqualTo(totalBefore);

        // another clinic's admission is a 404 and no row either
        Clinic other = hospital();
        long otherBefore = count(other, "select count(*) from patient.record_access");
        problem(call(HttpMethod.GET, dischargeUrl(live), other.owner(), null), 404);
        problem(call(HttpMethod.GET, dischargeUrl(done), other.owner(), null), 404);
        assertThat(count(other, "select count(*) from patient.record_access")).isEqualTo(otherBefore);
        assertThat(count(clinic, "select count(*) from patient.record_access")).isEqualTo(totalBefore);
    }

    @Test
    void aSheetRefusedBecauseTheModuleIsOffWritesNoAccessRowEither() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID patient = UUID.fromString(patientOf(clinic, id));
        long before = recordAccess(clinic, patient);
        // read-only still shows the sheet, and logs it
        expireTrial(clinic);
        sheet(clinic, id);
        assertThat(recordAccess(clinic, patient)).isEqualTo(before + 1);
        Clinic off = newClinic();
        long offBefore = count(off, "select count(*) from patient.record_access");
        assertType(problem(call(HttpMethod.GET, dischargeUrl(UUID.randomUUID()), off.owner(), null), 403), "module-off");
        assertThat(count(off, "select count(*) from patient.record_access")).isEqualTo(offBefore);
    }

    // ---------------- (2) the stale signer ----------------

    private long signoffs(Clinic clinic) throws Exception {
        return rows(clinic, "discharge_signoff");
    }

    @Test
    void aDoctorWhoWasChangedToNurseCannotSignWithTheOlderDoctorTokenAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        Member second = addMember(clinic, "DOCTOR");
        RequestPostProcessor doctorToken = bearer(login(second.mobile()).accessToken());
        // while the membership is a DOCTOR the same token signs (positive control, then undone by a new choice)
        JsonNode signed = ok(HttpMethod.PUT, signoffUrl(id), doctorToken, signBody("normal", "general"), 200);
        assertThat(signed.get("signature")).isNotNull();
        assertThat(signoffs(clinic)).isOne();
        String signedBy = values(clinic, "select signed_by::text from ipd.discharge_signoff").get(0);
        assertThat(signedBy).as("the second person signed, not the owner").isNotEqualTo(clinic.ownerUserId().toString());

        String other = quickAdmit(clinic).get("id").asText();
        ok(HttpMethod.PATCH, TEAM + "/" + second.membershipId(), clinic.owner(), "{\"role\":\"NURSE\"}", 200);
        Written before = written(clinic, other);
        long audits = audits(clinic, "IPD_DISCHARGE_SIGNED");

        // the old token still says DOCTOR; the membership says NURSE
        JsonNode refused = problem(call(HttpMethod.PUT, signoffUrl(other), doctorToken, signBody("normal", "general")), 403);
        assertType(refused, "forbidden");
        assertType(problem(call(HttpMethod.PUT, signoffUrl(id), doctorToken, signBody("lama", "surgical")), 403), "forbidden");
        assertThat(signoffs(clinic)).as("the refused calls wrote nothing").isOne();
        assertThat(written(clinic, other)).isEqualTo(before);
        assertThat(audits(clinic, "IPD_DISCHARGE_SIGNED")).isEqualTo(audits);
        assertThat(values(clinic, "select discharge_type from ipd.discharge_signoff")).containsExactly("normal");

        // the person is a DOCTOR again: the same old token signs again
        ok(HttpMethod.PATCH, TEAM + "/" + second.membershipId(), clinic.owner(), "{\"role\":\"DOCTOR\"}", 200);
        ok(HttpMethod.PUT, signoffUrl(other), doctorToken, signBody("normal", "general"), 200);
        assertThat(signoffs(clinic)).isEqualTo(2);
    }

    @Test
    void aDeactivatedDoctorCannotSignWithTheOlderTokenAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        Member second = addMember(clinic, "DOCTOR");
        RequestPostProcessor doctorToken = bearer(login(second.mobile()).accessToken());
        ok(HttpMethod.POST, TEAM + "/" + second.membershipId() + "/deactivate", clinic.owner(), null, 200);
        Written before = written(clinic, id);

        int status = call(HttpMethod.PUT, signoffUrl(id), doctorToken, signBody("normal", "general")).andReturn().getResponse().getStatus();
        assertThat(status).as("a deactivated person is refused").isIn(401, 403);
        assertThat(signoffs(clinic)).isZero();
        assertThat(written(clinic, id)).isEqualTo(before);
        assertThat(audits(clinic, "IPD_DISCHARGE_SIGNED")).isZero();

        // the same for reading the sheet: the older token opens nothing and logs nothing
        int read = call(HttpMethod.GET, dischargeUrl(id), doctorToken, null).andReturn().getResponse().getStatus();
        assertThat(read).isIn(401, 403);
        assertThat(written(clinic, id).access()).isEqualTo(before.access());
    }

    @Test
    void anOwnerWhoWasChangedToNurseCannotSignWithTheOlderOwnerTokenButARealDoctorCan() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        Member second = addMember(clinic, "OWNER");
        RequestPostProcessor ownerToken = bearer(login(second.mobile()).accessToken());
        ok(HttpMethod.PATCH, TEAM + "/" + second.membershipId(), clinic.owner(), "{\"role\":\"NURSE\"}", 200);
        Written before = written(clinic, id);
        assertType(problem(call(HttpMethod.PUT, signoffUrl(id), ownerToken, signBody("normal", "general")), 403), "forbidden");
        assertThat(signoffs(clinic)).isZero();
        assertThat(written(clinic, id)).isEqualTo(before);
        // a person who really is a doctor signs, by a token of their own
        RequestPostProcessor doctor = otherPerson(clinic, "DOCTOR");
        ok(HttpMethod.PUT, signoffUrl(id), doctor, signBody("normal", "general"), 200);
        assertThat(signoffs(clinic)).isOne();
        assertThat(audits(clinic, "IPD_DISCHARGE_SIGNED")).isOne();
    }
}
