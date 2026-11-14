package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-67-3 on the real database (docs/api/opd.yaml 0.6.1, ipd.yaml 0.3.0, ADR-025 D10): the pre-filled admission sheet, the
 * doctor's admission request from an OPD visit (one OPEN request per record, "Admission advised"), the IPD "Admission
 * requests" list for the admit roles, and the refusals (IPD off, signed record, roles, another clinic). Completing the
 * admission from a request is in EmrAdmitFromRequestIT (it needs the IPD set-up). Test plan: docs/test-plans/SAH-51-68.md
 * (T-67-3.x).
 */
@OnTestDatabase
class EmrAdmissionRequestIT extends EmrSignSupport {

    private static final String IPD_REQUESTS = "/api/v1/ipd/admission-requests";
    private static final String BODY = "{\"admissionType\":\"planned\",\"plannedStayDays\":3,\"note\":\"Zxqnote for the ward\"}";

    /** A hospital-side record: IPD in trial, a patient with a name and a mobile, a filled record. */
    private Rec hospitalRec() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL_URL, clinic.owner(), null, 200);
        Staff doctor = addDoctor(clinic, "General physician");
        return fillRequired(startRecord(clinic, doctor, patientWith(clinic, "Test Admit Patient", null, null)));
    }

    private String path(Rec rec, String what) {
        return RECORDS + "/" + rec.id() + "/" + what;
    }

    private JsonNode request(Rec rec, String body, String key, int expected) throws Exception {
        return okKeyed(HttpMethod.POST, path(rec, "admission-request"), rec.token(), body, key, expected);
    }

    // ---------------- the pre-filled sheet ----------------

    @Test
    void thePrefillHasThePatientTheSuggestedTypeTheDiagnosesAndNoOpenRequestOrAdmission() throws Exception {
        Rec rec = hospitalRec();
        JsonNode prefill = get(path(rec, "admission-prefill"), rec.token());
        assertThat(prefill.get("patient").get("patientId").asText()).isEqualTo(rec.patientId().toString());
        assertThat(prefill.get("patient").get("name").asText()).isEqualTo("Test Admit Patient");
        assertThat(isNullOrMissing(prefill.get("patient").path("mobile")))
                .as("a doctor who cannot admit directly gets no mobile number (DEC-048 H34)").isTrue();
        assertThat(prefill.get("suggestedType").asText()).isEqualTo("planned");
        assertThat(texts(prefill.get("diagnoses"), null)).containsExactly("Viral fever");
        assertThat(prefill.get("openRequest").isNull()).isTrue();
        assertThat(prefill.get("openAdmission").isNull()).isTrue();
        assertThat(prefill.get("canAdmitDirectly").asBoolean()).as("a doctor sends a request").isFalse();
    }

    @Test
    void anOwnerWithDoctorDetailsMayAdmitDirectlyAndTheRequestShowsInThePrefillOnceMade() throws Exception {
        Rec rec = hospitalRec();
        request(rec, BODY, key(), 201);
        JsonNode prefill = get(path(rec, "admission-prefill"), rec.token());
        assertThat(prefill.get("openRequest").get("status").asText()).isEqualTo("OPEN");
        assertThat(prefill.get("openRequest").get("admissionType").asText()).isEqualTo("planned");
        ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
        JsonNode forOwner = get(path(rec, "admission-prefill"), rec.clinic().owner());
        assertThat(forOwner.get("canAdmitDirectly").asBoolean()).isTrue();
        assertThat(isNullOrMissing(forOwner.path("patient").path("mobile"))).as("the owner who can admit directly gets the mobile (H34)").isFalse();
        assertThat(forOwner.get("patient").get("mobile").asText()).isNotBlank();
    }

    private static boolean isNullOrMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    // ---------------- the request ----------------

    @Test
    void aRequestIsOneOpenRowMarksTheVisitAdmissionAdvisedAndIsAuditedWithIdsAndTheTypeOnly() throws Exception {
        Rec rec = hospitalRec();
        JsonNode created = request(rec, BODY, key(), 201);
        assertThat(created.get("status").asText()).isEqualTo("OPEN");
        assertThat(created.get("admissionType").asText()).isEqualTo("planned");
        assertThat(created.get("plannedStayDays").asInt()).isEqualTo(3);
        assertThat(created.get("admissionId").isNull()).isTrue();
        List<String> stored = row(rec.clinic(), "select status, encounter_type, opd_visit_id::text, er_case_id::text, patient_id::text, "
                + "requested_by_membership_id::text from opd.emr_admission_request");
        assertThat(stored.get(0)).isEqualTo("OPEN");
        assertThat(stored.get(1)).isEqualTo("OPD_VISIT");
        assertThat(stored.get(2)).isEqualTo(rec.visitId());
        assertThat(stored.get(3)).isNull();
        assertThat(stored.get(4)).isEqualTo(rec.patientId().toString());
        assertThat(stored.get(5)).isEqualTo(rec.doctor().membershipId().toString());
        JsonNode view = record(rec.token(), rec.id());
        assertThat(view.get("admissionAdvised").asBoolean()).isTrue();
        assertThat(view.get("admission").get("state").asText()).isEqualTo("REQUESTED");
        assertThat(view.get("admission").get("requestId").asText()).isEqualTo(created.get("id").asText());
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_ADMISSION_REQUESTED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(2)).contains(created.get("id").asText()).contains("planned")
                .doesNotContain("Viral fever").doesNotContain("Zxqnote").doesNotContain("Test Admit Patient");
    }

    @Test
    void aSecondRequestForTheSameRecordReturnsTheOpenOneWith200AndMakesNoSecondRowOrAudit() throws Exception {
        Rec rec = hospitalRec();
        JsonNode first = request(rec, BODY, key(), 201);
        JsonNode again = request(rec, "{\"admissionType\":\"daycare\"}", key(), 200);
        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(again.get("admissionType").asText()).as("the open request is returned unchanged").isEqualTo("planned");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isOne();
        assertThat(auditCount(rec.clinic(), "EMR_ADMISSION_REQUESTED")).isOne();
    }

    @Test
    void theSameKeyWithAnotherBodyIs409IdempotencyConflictAndTheSameBodyReturnsTheSameRequest() throws Exception {
        Rec rec = hospitalRec();
        String key = key();
        JsonNode first = request(rec, BODY, key, 201);
        assertThat(request(rec, BODY, key, 200).get("id").asText()).isEqualTo(first.get("id").asText());
        problemKeyed(HttpMethod.POST, path(rec, "admission-request"), rec.token(), "{\"admissionType\":\"emergency\"}", key, 409,
                "idempotency-conflict");
    }

    @Test
    void anInvalidTypeStayOrNoteOrAMissingKeyIs400AndNothingIsStored() throws Exception {
        Rec rec = hospitalRec();
        String url = path(rec, "admission-request");
        for (String body : new String[] {"{}", "{\"admissionType\":\"maternity\"}", "{\"admissionType\":\"planned\",\"plannedStayDays\":0}",
                "{\"admissionType\":\"planned\",\"plannedStayDays\":61}",
                "{\"admissionType\":\"planned\",\"note\":\"" + "n".repeat(301) + "\"}"}) {
            problemKeyed(HttpMethod.POST, url, rec.token(), body, key(), 400, "validation");
        }
        problemKeyed(HttpMethod.POST, url, rec.token(), BODY, null, 400, "validation");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
        // the limits themselves are accepted
        request(rec, "{\"admissionType\":\"daycare\",\"plannedStayDays\":60,\"note\":\"" + "n".repeat(300) + "\"}", key(), 201);
    }

    @Test
    void aSignedRecordRefusesTheRequestWith409AndAnIpdThatIsOffIs403ModuleOff() throws Exception {
        Rec rec = hospitalRec();
        signOk(rec);
        problemKeyed(HttpMethod.POST, path(rec, "admission-request"), rec.token(), BODY, key(), 409, "emr-record-signed");
        Rec off = readyRec();
        JsonNode problem = problemKeyed(HttpMethod.POST, path(off, "admission-request"), off.token(), BODY, key(), 403, "module-off");
        assertThat(problem.get("detail").asText()).isEqualTo("In-patients (IPD) is not switched on for your clinic.");
        problemOf(HttpMethod.GET, path(off, "admission-prefill"), off.token(), null, 403, "module-off");
        assertThat(count(off.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
    }

    @Test
    void rolesAnotherDoctorAnotherClinicAndNoSessionAreRefused() throws Exception {
        Rec rec = hospitalRec();
        Rec foreign = hospitalRec();
        Staff other = addDoctor(rec.clinic(), "Dentist");
        problemKeyed(HttpMethod.POST, path(rec, "admission-request"), other.token(rec.clinic()), BODY, key(), 403, "not-own-patient");
        problemOf(HttpMethod.GET, path(rec, "admission-prefill"), other.token(rec.clinic()), null, 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemKeyed(HttpMethod.POST, path(rec, "admission-request"), staff.token(rec.clinic()), BODY, key(), 403, "forbidden");
            problemOf(HttpMethod.GET, path(rec, "admission-prefill"), staff.token(rec.clinic()), null, 403, "forbidden");
        }
        problemKeyed(HttpMethod.POST, path(rec, "admission-request"), foreign.token(), BODY, key(), 404, "record-not-found");
        problemOf(HttpMethod.GET, path(rec, "admission-prefill"), foreign.token(), null, 404, "record-not-found");
        problemKeyed(HttpMethod.POST, path(rec, "admission-request"), anonymous(), BODY, key(), 401, "unauthorized");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
    }

    // ---------------- the key, the record, the hold (opd.yaml 0.6.2, security L1 and L7) ----------------

    /** A second filled record of the same clinic and doctor, for another patient. */
    private Rec secondRec(Rec first) throws Exception {
        return fillRequired(startRecord(first.clinic(), first.doctor(), patientWith(first.clinic(), "Test Second Patient", null, null)));
    }

    @Test
    void aClientKeyThatStartsWithSignIsReservedAndIs400WithNothingStoredWhateverTheCase() throws Exception {
        Rec rec = hospitalRec();
        String reserved32 = "sign-" + rec.id().replace("-", "");
        for (String key : new String[] {reserved32, "sign-abcdefgh", "sign-" + "0".repeat(32)}) {
            problemKeyed(HttpMethod.POST, path(rec, "admission-request"), rec.token(), BODY, key, 400, "validation");
        }
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
        assertThat(auditCount(rec.clinic(), "EMR_ADMISSION_REQUESTED")).isZero();
        // a key that merely contains sign- later is a normal key
        request(rec, BODY, "my-sign-" + shortId(), 201);
    }

    @Test
    void theSameKeyOnAnotherRecordIs409IdempotencyConflictAndTheOtherRecordGetsNoRequest() throws Exception {
        Rec first = hospitalRec();
        Rec second = secondRec(first);
        String key = key();
        JsonNode made = request(first, BODY, key, 201);
        // same key and the same body, but another record: not a replay (security L1 a)
        problemKeyed(HttpMethod.POST, path(second, "admission-request"), second.token(), BODY, key, 409, "idempotency-conflict");
        assertThat(count(first.clinic(), "select count(*) from opd.emr_admission_request")).as("only the first record's request").isOne();
        assertThat(row(first.clinic(), "select record_id::text from opd.emr_admission_request where id = ?::uuid", made.get("id").asText()).get(0))
                .isEqualTo(first.id());
        assertThat(record(second.token(), second.id()).get("admissionAdvised").asBoolean()).as("the second visit is not marked").isFalse();
        // the first record still replays its own request
        assertThat(request(first, BODY, key, 200).get("id").asText()).isEqualTo(made.get("id").asText());
        // and the second record can make its own request with its own key
        request(second, BODY, key(), 201);
        assertThat(count(first.clinic(), "select count(*) from opd.emr_admission_request")).isEqualTo(2);
    }

    @Test
    void anotherDoctorsActiveHoldIs409EmrRecordHeldAndALapsedHoldIsNoObstacle() throws Exception {
        Rec rec = hospitalRec();
        Staff owner = ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
        JsonNode problem = problemKeyed(HttpMethod.POST, path(rec, "admission-request"), owner.token(rec.clinic()), BODY, key(), 409,
                "emr-record-held");
        assertThat(problem.get("detail").asText()).startsWith("Open for editing by Dr.");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
        assertThat(record(rec.token(), rec.id()).get("admissionAdvised").asBoolean()).isFalse();
        // the doctor who holds the record is not held out
        request(rec, BODY, key(), 201);
        // once the hold has lapsed another doctor's request is accepted
        Rec lapsed = secondRec(rec);
        commit(rec.clinic().tenantId(), "update opd.emr_record set edit_hold_expires_at = now() - interval '5 seconds' where id = ?::uuid", lapsed.id());
        okKeyed(HttpMethod.POST, path(lapsed, "admission-request"), owner.token(rec.clinic()), BODY, key(), 201);
    }

    // ---------------- the IPD "Admission requests" list ----------------

    @Test
    void theAdmitRolesSeeTheOpenRequestAndOnlyOwnerAndNurseSeeTheDiagnosesAndTheNoteWhileTheFrontDeskGetsNullForBoth() throws Exception {
        Rec rec = hospitalRec();
        JsonNode created = request(rec, BODY, key(), 201);
        // DEC-048 H33 (security M3): the diagnoses and the doctor's note go to OWNER and NURSE only; FRONT_DESK gets null for both
        JsonNode nurseList = get(IPD_REQUESTS, addNonDoctor(rec.clinic(), "NURSE").token(rec.clinic()));
        assertThat(nurseList.get("items")).as("NURSE").hasSize(1);
        assertThat(nurseList.get("items").get(0).get("diagnoses").asText()).as("NURSE sees the diagnoses").isEqualTo("Viral fever");
        assertThat(nurseList.get("items").get(0).get("note").asText()).as("NURSE sees the note").isEqualTo("Zxqnote for the ward");
        JsonNode frontDeskList = get(IPD_REQUESTS, addNonDoctor(rec.clinic(), "FRONT_DESK").token(rec.clinic()));
        assertThat(frontDeskList.get("items")).as("FRONT_DESK").hasSize(1);
        JsonNode forFrontDesk = frontDeskList.get("items").get(0);
        assertThat(isNullOrMissing(forFrontDesk.path("diagnoses"))).as("FRONT_DESK gets no diagnoses").isTrue();
        assertThat(isNullOrMissing(forFrontDesk.path("note"))).as("FRONT_DESK gets no note").isTrue();
        assertThat(forFrontDesk.get("requestId").asText()).isEqualTo(created.get("id").asText());
        assertThat(forFrontDesk.get("patientName").asText()).as("the front desk still admits: patient, type and stay").isEqualTo("Test Admit Patient");
        assertThat(forFrontDesk.get("admissionType").asText()).isEqualTo("planned");
        assertThat(forFrontDesk.get("plannedStayDays").asInt()).isEqualTo(3);
        assertThat(frontDeskList.toString()).doesNotContain("Viral fever").doesNotContain("Zxqnote");
        JsonNode list = get(IPD_REQUESTS, rec.clinic().owner());
        assertThat(list.get("canAdmit").asBoolean()).isTrue();
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("EMR");
        assertThat(item.get("requestId").asText()).isEqualTo(created.get("id").asText());
        assertThat(item.get("patientId").asText()).isEqualTo(rec.patientId().toString());
        assertThat(item.get("patientName").asText()).isEqualTo("Test Admit Patient");
        assertThat(item.get("admissionType").asText()).isEqualTo("planned");
        assertThat(item.get("plannedStayDays").asInt()).isEqualTo(3);
        assertThat(item.get("doctorName").asText()).isNotBlank();
        assertThat(item.get("diagnoses").asText()).as("OWNER sees the diagnoses").isEqualTo("Viral fever");
        assertThat(item.get("note").asText()).as("OWNER sees the note").isEqualTo("Zxqnote for the ward");
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problemOf(HttpMethod.GET, IPD_REQUESTS, addNonDoctor(rec.clinic(), role).token(rec.clinic()), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, IPD_REQUESTS, rec.token(), null, 403, "forbidden");
        problemOf(HttpMethod.GET, IPD_REQUESTS, anonymous(), null, 401, "unauthorized");
    }

    @Test
    void anotherClinicSeesNoRequestAndAClinicWithIpdOffIsRefused() throws Exception {
        Rec rec = hospitalRec();
        request(rec, BODY, key(), 201);
        Rec foreign = hospitalRec();
        assertThat(get(IPD_REQUESTS, foreign.clinic().owner()).get("items")).isEmpty();
        Clinic off = newClinic();
        call(HttpMethod.GET, IPD_REQUESTS, off.owner(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    // ---------------- privacy ----------------

    @Test
    void noLogLineOfThePrefillOrTheRequestHoldsThePatientTheDiagnosisOrTheNote() throws Exception {
        Rec rec = hospitalRec();
        try (LogCapture logs = new LogCapture()) {
            get(path(rec, "admission-prefill"), rec.token());
            request(rec, BODY, key(), 201);
            get(IPD_REQUESTS, rec.clinic().owner());
            assertThat(logs.allText()).doesNotContain("Test Admit Patient").doesNotContain("Viral fever").doesNotContain("Zxqnote");
        }
        assertThat(allAuditText(rec.clinic())).doesNotContain("Zxqnote").doesNotContain("Test Admit Patient");
    }
}
