package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-67-2 on the real database (docs/api/opd.yaml 0.6.1, ipd.yaml 0.3.0, ADR-025 D9, DEC-048 H2 and H22): the doctor picks
 * an ER outcome and signs the Emergency assessment in one action: Discharge from ER, Refer out (the referral line goes on the
 * record without "Admission advised"), Admit to IPD (an admission request; the case stays in the queue until an admit role
 * admits it) and Send to OPD (a token for an existing patient, the case closes as sent). Also the lock order against an
 * admission made at the same moment. Test plan: docs/test-plans/SAH-51-68.md (T-67-2.x).
 */
@OnTestDatabase
class EmrEmergencyFinishIT extends EmrIpdSupport {

    private record Er(Clinic clinic, String caseId, RequestPostProcessor doctor, JsonNode record) {

        String id() {
            return record.get("id").asText();
        }
    }

    /** A hospital, a waiting case and the admitting doctor's open Emergency assessment with the required sections filled. */
    private Er er() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Asha Patil", 3);
        RequestPostProcessor doctor = admittingDoctor(clinic);
        JsonNode opened = openRecord(doctor, "ER_ASSESSMENT", caseId, 201).get("record");
        return new Er(clinic, caseId, doctor, fillTyped(doctor, opened));
    }

    private static String outcome(String kind) {
        return "{\"erOutcome\":{\"kind\":\"" + kind + "\"}}";
    }

    private static String refer(String to, String note) {
        return "{\"erOutcome\":{\"kind\":\"REFER_OUT\",\"referTo\":\"" + to + "\""
                + (note == null ? "" : ",\"referNote\":\"" + note + "\"") + "}}";
    }

    private List<String> blockerKeys(JsonNode check) {
        return texts(check.get("blockers"), "key");
    }

    // ---------------- the check ----------------

    @Test
    void anOutcomeIsRequiredAndSendToOpdNeedsAPatient() throws Exception {
        Er e = er();
        JsonNode none = check(e.doctor(), e.record(), "{}", 200);
        assertThat(none.get("ready").asBoolean()).isFalse();
        assertThat(blockerKeys(none)).containsExactly("ER_OUTCOME_REQUIRED");
        assertThat(none.get("blockers").get(0).get("message").asText()).isEqualTo("Choose the ER outcome.");
        JsonNode noPatient = check(e.doctor(), e.record(), outcome("SEND_TO_OPD"), 200);
        assertThat(blockerKeys(noPatient)).containsExactly("PATIENT_REQUIRED");
        assertThat(noPatient.get("blockers").get(0).get("message").asText()).isEqualTo("Choose the patient to send to OPD.");
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
    }

    @Test
    void theCheckShowsTheTriageLevelTheOutcomeAndThePrintsOfEachOutcomeAndWritesNothing() throws Exception {
        Er e = er();
        UUID patient = newPatient(e.clinic());
        String[][] cases = {{outcome("DISCHARGE"), "DISCHARGE", "PRESCRIPTION"}, {refer("District hospital", null), "REFER_OUT", ""},
                {outcome("ADMIT"), "ADMIT", ""}, {"{\"erOutcome\":{\"kind\":\"SEND_TO_OPD\",\"opdPatientId\":\"" + patient + "\"}}", "SEND_TO_OPD", ""}};
        for (String[] c : cases) {
            JsonNode check = check(e.doctor(), e.record(), c[0], 200);
            assertThat(check.get("ready").asBoolean()).as(c[1]).isTrue();
            assertThat(check.get("encounterType").asText()).isEqualTo("ER_ASSESSMENT");
            JsonNode emergency = check.get("effects").get("emergency");
            assertThat(emergency.get("triageLevel").asInt()).isEqualTo(3);
            assertThat(emergency.get("triageLevelName").asText()).isNotBlank();
            assertThat(emergency.get("outcome").asText()).isEqualTo(c[1]);
            assertThat(texts(check.get("effects").get("prints"), null)).as(c[1]).isEqualTo(c[2].isEmpty() ? List.of() : List.of(c[2]));
            assertThat(check.get("effects").get("stage").isNull()).isTrue();
            assertThat(check.get("effects").get("billLines")).isEmpty();
        }
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(count(e.clinic(), "select count(*) from opd.visit")).as("a check issues no token").isZero();
    }

    // ---------------- discharge ----------------

    @Test
    void dischargeClosesTheCaseAsTreatAndReleaseSignsTheAssessmentAndPrintsThePrescriptionWithNoBillLine() throws Exception {
        Er e = er();
        JsonNode result = sign(e.doctor(), e.record(), outcome("DISCHARGE"));
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(result.get("outcome").get("emergency").get("outcome").asText()).isEqualTo("DISCHARGE");
        assertThat(texts(result.get("outcome").get("prints"), null)).containsExactly("PRESCRIPTION");
        assertThat(result.get("outcome").get("billLines").asInt()).isZero();
        List<String> state = caseRow(e.clinic(), e.caseId());
        assertThat(state.get(0)).isEqualTo("RELEASED");
        assertThat(state.get(1)).as("closed_at set").isEqualTo("true");
        assertThat(state.get(2)).as("the number is cleared").isEqualTo("true");
        assertThat(state.get(5)).as("closed_by set").isEqualTo("true");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_RELEASED'")).isOne();
        assertThat(count(e.clinic(), "select count(*) from billing.bill_item")).isZero();
        assertThat(recordRow(e.clinic(), e.id()).get(3)).as("no patient record for an unknown patient").isNull();
    }

    @Test
    void aRetryOfTheDischargeWithTheSameKeyIsAReplayWithOneClosingAndThePrintsAgain() throws Exception {
        Er e = er();
        String key = idemKey();
        String body = signBody(e.record().get("revision").asLong(), outcome("DISCHARGE"));
        String path = EMR_RECORDS + "/" + e.id() + "/sign";
        JsonNode first = okKeyed(HttpMethod.POST, path, e.doctor(), body, key, 200);
        JsonNode again = okKeyed(HttpMethod.POST, path, e.doctor(), body, key, 200);
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(texts(again.get("outcome").get("prints"), null)).containsExactly("PRESCRIPTION");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_RELEASED'")).isOne();
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isOne();
    }

    // ---------------- refer out (DEC-048 H22) ----------------

    @Test
    void referOutClosesTheCaseAsReferredPutsTheReferralLineOnTheRecordAndDoesNotAdviseAdmission() throws Exception {
        Er e = er();
        JsonNode result = sign(e.doctor(), e.record(), refer("District hospital", "For CT head"));
        assertThat(result.get("outcome").get("emergency").get("outcome").asText()).isEqualTo("REFER_OUT");
        assertThat(result.get("outcome").get("prints")).as("only a discharge prints the prescription").isEmpty();
        JsonNode record = result.get("record");
        assertThat(record.get("referralLine").asText()).isEqualTo("Referred to District hospital. For CT head");
        assertThat(record.get("admissionAdvised").asBoolean()).as("a refer-out is not 'Admission advised'").isFalse();
        assertThat(record.get("admission").get("state").asText()).isEqualTo("NONE");
        List<String> row = recordRow(e.clinic(), e.id());
        assertThat(row.get(0)).isEqualTo("SIGNED");
        assertThat(row.get(1)).isEqualTo("false");
        assertThat(row.get(2)).isEqualTo("Referred to District hospital. For CT head");
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("REFERRED");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_REFERRED'")).isOne();
        assertThat(count(e.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
    }

    @Test
    void aReferralWithNoNoteHasJustThePlaceAndALongOneIsCutTo300CharactersWhichTheDatabaseCheckAccepts() throws Exception {
        Er plain = er();
        sign(plain.doctor(), plain.record(), refer("Civil hospital", null));
        assertThat(recordRow(plain.clinic(), plain.id()).get(2)).isEqualTo("Referred to Civil hospital");
        Er longOne = er();
        sign(longOne.doctor(), longOne.record(), refer("p".repeat(120), "n".repeat(300)));
        String line = recordRow(longOne.clinic(), longOne.id()).get(2);
        assertThat(line).hasSize(300).startsWith("Referred to ppp");
        assertThat(caseRow(longOne.clinic(), longOne.caseId()).get(0)).isEqualTo("REFERRED");
    }

    @Test
    void aReferralWithoutAPlaceOrWithTooLongTextIs400AndTheCaseAndTheRecordAreUntouched() throws Exception {
        Er e = er();
        for (String choices : new String[] {refer("   ", null), refer("p".repeat(121), null), refer("Hospital", "n".repeat(301)),
                "{\"erOutcome\":{\"kind\":\"REFER_OUT\"}}", "{\"erOutcome\":{\"kind\":\"TRANSFER\"}}"}) {
            problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + e.id() + "/sign", e.doctor(),
                    signBody(e.record().get("revision").asLong(), choices), idemKey(), 400, "validation");
        }
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(recordRow(e.clinic(), e.id()).get(2)).isNull();
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
    }

    // ---------------- admit to IPD ----------------

    @Test
    void admitSignsTheAssessmentMakesAnEmergencyAdmissionRequestAndLeavesTheCaseInTheQueue() throws Exception {
        Er e = er();
        JsonNode result = sign(e.doctor(), e.record(), "{\"erOutcome\":{\"kind\":\"ADMIT\",\"admission\":{\"admissionType\":\"emergency\",\"plannedStayDays\":2}}}");
        JsonNode emergency = result.get("outcome").get("emergency");
        assertThat(emergency.get("outcome").asText()).isEqualTo("ADMIT");
        String requestId = emergency.get("admissionRequestId").asText();
        assertThat(result.get("outcome").get("prints")).isEmpty();
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).as("the case closes when the admission is made").isEqualTo("WAITING");
        List<String> request = values(e.clinic(), "select status, encounter_type, er_case_id::text, (opd_visit_id is null)::text, "
                + "(patient_id is null)::text, admission_type, planned_stay_days::text from opd.emr_admission_request where id = ?::uuid", UUID.fromString(requestId));
        assertThat(request).containsExactly("OPEN", "ER_ASSESSMENT", e.caseId(), "true", "true", "emergency", "2");
        // opd.yaml 0.6.2: the sign-made request's key is "sign-" and the record id as 32 hex digits (never 24)
        String signKey = values(e.clinic(), "select idempotency_key from opd.emr_admission_request where id = ?::uuid", UUID.fromString(requestId)).get(0);
        assertThat(signKey).matches("^sign-[0-9a-f]{32}$").isEqualTo("sign-" + e.id().replace("-", ""));
        assertThat(recordRow(e.clinic(), e.id()).get(1)).as("admission advised").isEqualTo("true");
        assertThat(result.get("record").get("admission").get("state").asText()).isEqualTo("REQUESTED");
        JsonNode item = ok(HttpMethod.GET, IPD_REQUESTS, e.clinic().owner(), null, 200).get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("EMR");
        assertThat(item.get("emergencyCaseId").asText()).isEqualTo(e.caseId());
        assertThat(item.get("patientName").asText()).isEqualTo("Asha Patil");
        assertThat(item.get("admissionType").asText()).isEqualTo("emergency");
    }

    @Test
    void theAdmitRoleCompletesTheAdmissionFromTheCaseAndTheRequestClosesTheRecordGetsItsPatientAndTheCaseIsAdmitted() throws Exception {
        Er e = er();
        String requestId = sign(e.doctor(), e.record(), outcome("ADMIT")).get("outcome").get("emergency").get("admissionRequestId").asText();
        UUID patient = newPatient(e.clinic());
        Map<String, Object> m = admitBodyFor(e.clinic(), patient, requestId);
        m.put("admissionType", "emergency");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        m.put("emergencyCaseId", e.caseId());
        JsonNode admission = ok(HttpMethod.POST, ADMISSIONS, e.clinic().owner(), js(m), 201);
        List<String> request = values(e.clinic(), "select status, admission_id::text from opd.emr_admission_request where id = ?::uuid", UUID.fromString(requestId));
        assertThat(request).containsExactly("ADMITTED", admission.get("id").asText());
        assertThat(values(e.clinic(), "select admission_request_id::text from ipd.admission where id = ?::uuid", UUID.fromString(admission.get("id").asText())).get(0))
                .isEqualTo(requestId);
        List<String> record = recordRow(e.clinic(), e.id());
        assertThat(record.get(0)).as("still signed").isEqualTo("SIGNED");
        assertThat(record.get(3)).as("the patient is linked to the signed record").isEqualTo(patient.toString());
        List<String> state = caseRow(e.clinic(), e.caseId());
        assertThat(state.get(0)).isEqualTo("ADMITTED");
        assertThat(state.get(3)).isEqualTo(patient.toString());
        assertThat(ok(HttpMethod.GET, IPD_REQUESTS, e.clinic().owner(), null, 200).get("items")).isEmpty();
        // the request is closed: the same request cannot complete a second admission
        Map<String, Object> second = admitBodyFor(e.clinic(), newPatient(e.clinic()), requestId);
        problemOf(HttpMethod.POST, ADMISSIONS, e.clinic().owner(), js(second), 409, "admission-request-not-available");
        assertThat(getRecord(e.doctor(), e.id()).get("admission").get("state").asText()).isEqualTo("ADMITTED");
    }

    // ---------------- the admission request of an Emergency record (security L1, L7; opd.yaml 0.6.2) ----------------

    private String requestPath(Er e) {
        return EMR_RECORDS + "/" + e.id() + "/admission-request";
    }

    private long requestRows(Er e) throws Exception {
        return count(e.clinic(), "select count(*) from opd.emr_admission_request");
    }

    @Test
    void aClientCannotTakeTheKeyTheSignMakesAndTheAdmitSignAlwaysWorksAndKeepsThatKey() throws Exception {
        Er e = er();
        String reserved = "sign-" + e.id().replace("-", "");
        problemKeyed(HttpMethod.POST, requestPath(e), e.doctor(), "{\"admissionType\":\"emergency\"}", reserved, 400, "validation");
        assertThat(requestRows(e)).as("the refused key stored nothing").isZero();
        JsonNode result = sign(e.doctor(), e.record(), outcome("ADMIT"));
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        String requestId = result.get("outcome").get("emergency").get("admissionRequestId").asText();
        assertThat(values(e.clinic(), "select idempotency_key from opd.emr_admission_request where id = ?::uuid", UUID.fromString(requestId)).get(0))
                .isEqualTo(reserved);
        assertThat(requestRows(e)).isOne();
        // after the sign the reserved key is still no client key
        problemKeyed(HttpMethod.POST, requestPath(e), e.doctor(), "{\"admissionType\":\"emergency\"}", reserved, 400, "validation");
    }

    @Test
    void anOpenRequestOfTheRecordIsReusedByTheAdmitSignSoThereIsStillOneRequest() throws Exception {
        Er e = er();
        JsonNode made = okKeyed(HttpMethod.POST, requestPath(e), e.doctor(), "{\"admissionType\":\"emergency\",\"plannedStayDays\":2}", idemKey(), 201);
        JsonNode result = sign(e.doctor(), e.record(), outcome("ADMIT"));
        assertThat(result.get("outcome").get("emergency").get("admissionRequestId").asText()).isEqualTo(made.get("id").asText());
        assertThat(requestRows(e)).isOne();
    }

    @Test
    void theSameKeyOnAnotherRecordIs409IdempotencyConflictAndAClosedCaseIs409EncounterClosedWithNoRequest() throws Exception {
        Er first = er();
        String key = idemKey();
        okKeyed(HttpMethod.POST, requestPath(first), first.doctor(), "{\"admissionType\":\"emergency\"}", key, 201);
        String secondCase = caseOf(first.clinic(), "Meena Rao", 2);
        JsonNode secondRecord = fillTyped(first.doctor(), openRecord(first.doctor(), "ER_ASSESSMENT", secondCase, 201).get("record"));
        Er second = new Er(first.clinic(), secondCase, first.doctor(), secondRecord);
        problemKeyed(HttpMethod.POST, requestPath(second), second.doctor(), "{\"admissionType\":\"emergency\"}", key, 409, "idempotency-conflict");
        assertThat(requestRows(first)).as("the second record got no request").isOne();
        // a closed case takes no request (the closed check comes after the key and the signed check)
        ok(HttpMethod.POST, ER_CASES_PATH + "/" + secondCase + "/release", first.clinic().owner(), null, 200);
        JsonNode problem = problemKeyed(HttpMethod.POST, requestPath(second), second.doctor(), "{\"admissionType\":\"emergency\"}", idemKey(), 409, "encounter-closed");
        assertThat(problem.get("detail").asText()).isNotBlank();
        assertThat(requestRows(first)).isOne();
        assertThat(recordRow(first.clinic(), second.id()).get(1)).as("the closed case's record is not marked 'admission advised'").isEqualTo("false");
    }

    // ---------------- IPD Read-only (security L2; opd.yaml 0.6.2) ----------------

    @Test
    void whenIpdIsReadOnlyTheSignIs409IpdReadOnlyAndTheCaseTheRecordAndTheAuditAreUntouchedAndRenewingTheSubscriptionAllowsIt() throws Exception {
        Er e = er();
        expireTrial(e.clinic());
        long audits = count(e.clinic(), "select count(*) from platform.audit_event");
        JsonNode problem = signRefused(e.doctor(), e.record(), outcome("DISCHARGE"), 409, "ipd-read-only");
        assertThat(problem.get("detail").asText()).isNotBlank();
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event")).as("nothing audited").isEqualTo(audits);
        for (String kind : new String[] {"REFER_OUT", "ADMIT"}) {
            signRefused(e.doctor(), e.record(), kind.equals("ADMIT") ? outcome("ADMIT") : refer("District hospital", null), 409, "ipd-read-only");
        }
        assertThat(requestRows(e)).as("no admission request is made while IPD is read-only").isZero();
        assertThat(recordRow(e.clinic(), e.id()).get(2)).as("no referral line kept").isNull();
    }

    // ---------------- two sign requests at the same moment (code review 1) ----------------

    private List<MockHttpServletResponse> signTwice(Er e, String firstKey, String secondKey, String choices) throws Exception {
        String body = signBody(e.record().get("revision").asLong(), choices);
        String path = EMR_RECORDS + "/" + e.id() + "/sign";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try {
            for (String key : new String[] {firstKey, secondKey}) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return keyed(HttpMethod.POST, path, e.doctor(), body, key).andReturn().getResponse();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<MockHttpServletResponse> out = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                out.add(f.get(90, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameKeyAndBodyAtTheSameMomentOnAnEmergencyAssessmentThatClosesTheCaseGiveTwo200sOneReplayAndOneClosing() throws Exception {
        for (String outcome : new String[] {"DISCHARGE", "REFER_OUT"}) {
            Er e = er();
            String key = idemKey();
            List<MockHttpServletResponse> answers = signTwice(e, key, key, outcome.equals("DISCHARGE") ? outcome("DISCHARGE") : refer("District hospital", null));
            int replays = 0;
            for (MockHttpServletResponse r : answers) {
                assertThat(r.getStatus()).as(outcome + ": a retry racing the first sign is a replay, never case-closed").isEqualTo(200);
                replays += objectMapper.readTree(r.getContentAsString()).get("replayed").asBoolean() ? 1 : 0;
            }
            assertThat(replays).as(outcome).isOne();
            assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("SIGNED");
            assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo(outcome.equals("DISCHARGE") ? "RELEASED" : "REFERRED");
            assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isOne();
            assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action in ('IPD_ER_RELEASED', 'IPD_ER_REFERRED')")).isOne();
        }
    }

    @Test
    void twoDifferentKeysAtTheSameMomentOnAnEmergencyAssessmentGiveOne200AndOneAlreadySignedByNeverCaseClosed() throws Exception {
        Er e = er();
        List<MockHttpServletResponse> answers = signTwice(e, idemKey(), idemKey(), outcome("DISCHARGE"));
        List<Integer> statuses = new ArrayList<>();
        for (MockHttpServletResponse r : answers) {
            statuses.add(r.getStatus());
            if (r.getStatus() == 409) {
                JsonNode problem = objectMapper.readTree(r.getContentAsString());
                assertThat(problem.get("type").asText()).as("the second doctor is told who signed, not that the patient left the queue")
                        .isEqualTo(PROBLEM_BASE + "emr-record-signed");
                assertThat(problem.get("detail").asText()).startsWith("Already signed by Dr.");
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isOne();
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_RELEASED'")).isOne();
    }

    // ---------------- send to OPD (DEC-048 H2) ----------------

    @Test
    void sendToOpdIssuesATokenToTheChosenPatientWithTheSignerAsDoctorAndClosesTheCaseAsSentToOpd() throws Exception {
        Er e = er();
        UUID patient = newPatient(e.clinic());
        JsonNode result = sign(e.doctor(), e.record(), "{\"erOutcome\":{\"kind\":\"SEND_TO_OPD\",\"opdPatientId\":\"" + patient + "\"}}");
        JsonNode emergency = result.get("outcome").get("emergency");
        assertThat(emergency.get("outcome").asText()).isEqualTo("SEND_TO_OPD");
        String visitId = emergency.get("opdVisitId").asText();
        assertThat(emergency.get("tokenNo").asInt()).isPositive();
        List<String> state = caseRow(e.clinic(), e.caseId());
        assertThat(state.get(0)).isEqualTo("SENT_TO_OPD");
        assertThat(state.get(1)).isEqualTo("true");
        assertThat(state.get(2)).as("number cleared").isEqualTo("true");
        assertThat(state.get(3)).isEqualTo(patient.toString());
        assertThat(state.get(4)).isEqualTo(visitId);
        assertThat(recordRow(e.clinic(), e.id()).get(3)).isEqualTo(patient.toString());
        List<String> visit = values(e.clinic(), "select patient_id::text, doctor_membership_id::text from opd.visit where id = ?::uuid", UUID.fromString(visitId));
        assertThat(visit.get(0)).isEqualTo(patient.toString());
        assertThat(visit.get(1)).isEqualTo(e.clinic().doctorMembershipId().toString());
        boolean onTheQueue = false;
        for (JsonNode v : ok(HttpMethod.GET, OPD_VISITS + "/today", e.clinic().owner(), null, 200)) {
            onTheQueue |= visitId.equals(v.get("id").asText());
        }
        assertThat(onTheQueue).isTrue();
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_SENT_TO_OPD'")).isOne();
        assertThat(texts(result.get("outcome").get("prints"), null)).isEmpty();
    }

    @Test
    void aPatientOfAnotherClinicIs404AndNothingIsHalfDone() throws Exception {
        Er e = er();
        Clinic other = newClinic();
        UUID foreign = newPatient(other);
        problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + e.id() + "/sign", e.doctor(),
                signBody(e.record().get("revision").asLong(), "{\"erOutcome\":{\"kind\":\"SEND_TO_OPD\",\"opdPatientId\":\"" + foreign + "\"}}"),
                idemKey(), 404, "patient-not-found");
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
        assertThat(count(e.clinic(), "select count(*) from opd.visit")).isZero();
    }

    // ---------------- the case changed under the doctor ----------------

    @Test
    void aCaseClosedBySomeoneElseIs409CaseClosedAndNothingIsWritten() throws Exception {
        Er e = er();
        ok(HttpMethod.POST, ER_CASES_PATH + "/" + e.caseId() + "/release", e.clinic().owner(), null, 200);
        JsonNode problem = signRefused(e.doctor(), e.record(), outcome("DISCHARGE"), 409, "case-closed");
        assertThat(problem.get("detail").asText()).isEqualTo("This patient is no longer in the queue.");
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isZero();
        assertThat(count(e.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ER_RELEASED'")).isOne();
    }

    @Test
    void aSignAndAnAdmissionOfTheSameCaseAtTheSameMomentLetExactlyOneWinWithoutADeadlockOrAServerError() throws Exception {
        Er e = er();
        UUID patient = newPatient(e.clinic());
        Map<String, Object> m = admitBodyFor(e.clinic(), patient, null);
        m.put("admissionType", "emergency");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        m.put("emergencyCaseId", e.caseId());
        String admitBody = js(m);
        String signBody = signBody(e.record().get("revision").asLong(), outcome("DISCHARGE"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            Future<MockHttpServletResponse> signing = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return keyed(HttpMethod.POST, EMR_RECORDS + "/" + e.id() + "/sign", e.doctor(), signBody, idemKey()).andReturn().getResponse();
            });
            Future<MockHttpServletResponse> admitting = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return call(HttpMethod.POST, ADMISSIONS, e.clinic().owner(), admitBody).andReturn().getResponse();
            });
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            int signStatus = signing.get(90, TimeUnit.SECONDS).getStatus();
            int admitStatus = admitting.get(90, TimeUnit.SECONDS).getStatus();
            statuses.add(signStatus);
            statuses.add(admitStatus);
            assertThat(signStatus).isIn(200, 409);
            assertThat(admitStatus).isIn(201, 409);
            assertThat(signStatus == 200 && admitStatus == 201).as("one action closes the case, never both").isFalse();
            assertThat(signStatus != 200 && admitStatus != 201).as("one of them must win").isFalse();
            String status = caseRow(e.clinic(), e.caseId()).get(0);
            if (signStatus == 200) {
                assertThat(status).isEqualTo("RELEASED");
                assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("SIGNED");
            } else {
                assertThat(status).isEqualTo("ADMITTED");
                assertThat(recordRow(e.clinic(), e.id()).get(0)).as("a refused sign leaves the note a draft").isEqualTo("DRAFT");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).doesNotContain(500);
    }

    // ---------------- who may sign, privacy ----------------

    @Test
    void nurseFrontDeskBillingPharmacistCounsellorAndVisitingDoctorAre403AnotherClinicIs404AndNoSessionIs401() throws Exception {
        Er e = er();
        String path = EMR_RECORDS + "/" + e.id() + "/sign";
        String body = signBody(e.record().get("revision").asLong(), outcome("DISCHARGE"));
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            problemKeyed(HttpMethod.POST, path, newPn(e.clinic(), role).auth(), body, idemKey(), 403, "forbidden");
        }
        problemKeyed(HttpMethod.POST, path, newPn(hospital(), "DOCTOR").auth(), body, idemKey(), 404, "record-not-found");
        problemKeyed(HttpMethod.POST, path, request -> request, body, idemKey(), 401, "unauthorized");
        assertThat(caseRow(e.clinic(), e.caseId()).get(0)).isEqualTo("WAITING");
        assertThat(recordRow(e.clinic(), e.id()).get(0)).isEqualTo("DRAFT");
    }

    @Test
    void thePrefillOfAnEmergencyCaseHasTheCaseNameNoMobileAndSuggestsAnEmergencyAdmission() throws Exception {
        Er e = er();
        JsonNode prefill = ok(HttpMethod.GET, EMR_RECORDS + "/" + e.id() + "/admission-prefill", e.doctor(), null, 200);
        assertThat(prefill.get("patient").get("patientId").isNull()).isTrue();
        assertThat(prefill.get("patient").get("name").asText()).isEqualTo("Asha Patil");
        assertThat(prefill.get("patient").get("mobile").isNull()).isTrue();
        assertThat(prefill.get("suggestedType").asText()).isEqualTo("emergency");
        assertThat(prefill.get("emergencyCaseId").asText()).isEqualTo(e.caseId());
    }

    @Test
    void noLogLineOrAuditEntryOfTheEmergencySignHoldsThePatientsNameOrTheReferralText() throws Exception {
        Er e = er();
        try (LogCapture logs = new LogCapture()) {
            sign(e.doctor(), e.record(), refer("Zxqplace hospital", "Zxqnote reason"));
            assertThat(logs.allText()).doesNotContain("Asha Patil").doesNotContain("Patil").doesNotContain("Zxqplace").doesNotContain("Zxqnote");
        }
        String audit = String.join(" ", db().inTenant(e.clinic().tenantId(), c -> EmrSupport.allRows(c,
                "select coalesce(before_json::text, '') || coalesce(after_json::text, '') from platform.audit_event"))
                .stream().map(r -> r.get(0)).toList());
        assertThat(audit).doesNotContain("Asha Patil").doesNotContain("Patil").doesNotContain("Zxqplace").doesNotContain("Zxqnote");
    }
}
