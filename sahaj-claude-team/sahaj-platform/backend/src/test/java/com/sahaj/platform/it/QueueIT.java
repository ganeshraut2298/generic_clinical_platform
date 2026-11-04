package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Sweep: SAH-9 queue and tokens on the real database - issue, today board, stage moves, "mine" per role. */
@OnTestDatabase
class QueueIT extends SweepSupport {

    private static final String VISITS = "/api/v1/opd/visits";
    private static final String TODAY = VISITS + "/today";

    private static String tokenBody(UUID patient, UUID doctor) {
        return "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\"}";
    }

    private JsonNode issue(Clinic clinic, UUID patient) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(patient, clinic.doctorMembershipId()), 201);
    }

    private JsonNode move(Clinic clinic, String role, String visitId, String stage, int expected) throws Exception {
        return ok(HttpMethod.PATCH, VISITS + "/" + visitId + "/stage", clinic.as(role), "{\"stage\":\"" + stage + "\"}", expected);
    }

    // ---------------- issue a token ----------------

    @Test
    void issuedTokenCarriesNumberPatientDoctorFeeAndFirstStage() throws Exception {
        Clinic clinic = newClinic(true);
        UUID patient = newPatient(clinic);
        JsonNode v = issue(clinic, patient);
        assertThat(v.get("tokenNo").asInt()).isEqualTo(1);
        assertThat(v.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(v.get("age").asInt()).isEqualTo(45);
        assertThat(v.get("sex").asText()).isEqualTo("M");
        assertThat(v.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(v.get("visitType").asText()).isEqualTo("NEW");
        assertThat(v.get("feePaise").asLong()).isEqualTo(30000L);
        assertThat(v.get("stage").asText()).isEqualTo("VITALS");
        assertThat(v.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void withVitalsSwitchedOffTheFirstStageIsWithDoctor() throws Exception {
        Clinic clinic = newClinic(false);
        assertThat(issue(clinic, newPatient(clinic)).get("stage").asText()).isEqualTo("WITH_DOCTOR");
    }

    @Test
    void tokenNumbersCountUpPerClinicAndEachClinicStartsAtOne() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID pa = newPatient(a);
        assertThat(issue(a, pa).get("tokenNo").asInt()).isEqualTo(1);
        assertThat(issue(a, newPatient(a)).get("tokenNo").asInt()).isEqualTo(2);
        assertThat(issue(a, newPatient(a)).get("tokenNo").asInt()).isEqualTo(3);
        assertThat(issue(b, newPatient(b)).get("tokenNo").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentTokensGetDistinctNumbers() throws Exception {
        Clinic clinic = newClinic();
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            patients.add(newPatient(clinic));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (UUID p : patients) {
                Callable<Integer> task = () -> call(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(p, clinic.doctorMembershipId()))
                        .andReturn().getResponse().getStatus();
                futures.add(pool.submit(task));
            }
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        Set<Integer> numbers = new HashSet<>();
        for (JsonNode v : ok(HttpMethod.GET, TODAY, clinic.owner(), null, 200)) {
            numbers.add(v.get("tokenNo").asInt());
        }
        assertThat(numbers).hasSize(6);
    }

    @Test
    void secondTokenInsideTheFollowUpWindowIsAFollowUpWithTheConfiguredFee() throws Exception {
        Clinic clinic = newClinic(); // wizard: follow-up window 7 days, free
        UUID patient = newPatient(clinic);
        assertThat(issue(clinic, patient).get("visitType").asText()).isEqualTo("NEW");
        JsonNode followUp = issue(clinic, patient);
        assertThat(followUp.get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(followUp.get("feePaise").asLong()).isZero();
        ok(HttpMethod.PUT, "/api/v1/opd/fee-settings", clinic.owner(),
                "{\"feeTiming\":\"before\",\"followUpDays\":7,\"followUpFee\":\"half\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true}", 200);
        assertThat(issue(clinic, patient).get("feePaise").asLong()).isEqualTo(15000L);
        ok(HttpMethod.PUT, "/api/v1/opd/fee-settings", clinic.owner(),
                "{\"feeTiming\":\"before\",\"followUpDays\":7,\"followUpFee\":\"full\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true}", 200);
        assertThat(issue(clinic, patient).get("feePaise").asLong()).isEqualTo(30000L);
    }

    @Test
    void aVisitWithADifferentDoctorIsNewNotFollowUp() throws Exception {
        Clinic clinic = newClinic();
        Member second = addMember(clinic, "DOCTOR");
        UUID patient = newPatient(clinic);
        issue(clinic, patient);
        JsonNode v = ok(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(patient, second.membershipId()), 201);
        assertThat(v.get("visitType").asText()).isEqualTo("NEW");
        assertThat(v.get("feePaise").asLong()).isEqualTo(25000L);
    }

    @Test
    void tokenCanBeIssuedForAVisitingDoctorToo() throws Exception {
        Clinic clinic = newClinic();
        Member visiting = addMember(clinic, "VISITING_DOCTOR");
        ok(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(newPatient(clinic), visiting.membershipId()), 201);
    }

    @Test
    void tokenValidationAndNotFound() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        Member nurse = addMember(clinic, "NURSE");
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), "{\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}"), 400);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patient + "\"}"), 400);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"abc\",\"doctorMembershipId\":\"def\"}"), 400);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), "{oops"), 400);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(UUID.randomUUID(), clinic.doctorMembershipId())), 404);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(patient, UUID.randomUUID())), 404);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(patient, nurse.membershipId())), 404);
    }

    @Test
    void deactivatedDoctorCannotGetANewToken() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        ok(HttpMethod.POST, "/api/v1/platform/team/" + clinic.doctorMembershipId() + "/deactivate", clinic.owner(), null, 200);
        problem(call(HttpMethod.POST, VISITS, clinic.owner(), tokenBody(patient, clinic.doctorMembershipId())), 404);
    }

    @Test
    void anotherClinicsPatientOrDoctorIsNotFound() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID patientB = newPatient(b);
        problem(call(HttpMethod.POST, VISITS, a.owner(), tokenBody(patientB, a.doctorMembershipId())), 404);
        problem(call(HttpMethod.POST, VISITS, a.owner(), tokenBody(newPatient(a), b.doctorMembershipId())), 404);
    }

    @Test
    void onlyOwnerFrontDeskAndNurseMayIssueTokens() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.POST, VISITS, clinic.as(role), tokenBody(patient, clinic.doctorMembershipId())), 403);
        }
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            ok(HttpMethod.POST, VISITS, clinic.as(role), tokenBody(patient, clinic.doctorMembershipId()), 201);
        }
    }

    // ---------------- today ----------------

    @Test
    void todayListsOnlyThisClinicsVisitsOldestTokenFirstForEveryRole() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        issue(a, newPatient(a));
        issue(a, newPatient(a));
        issue(b, newPatient(b));
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            JsonNode list = ok(HttpMethod.GET, TODAY, a.as(role), null, 200);
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).get("tokenNo").asInt()).isLessThan(list.get(1).get("tokenNo").asInt());
        }
        assertThat(ok(HttpMethod.GET, TODAY, b.owner(), null, 200).size()).isEqualTo(1);
    }

    @Test
    void mineFilterShowsOnlyTheStagesTheRoleOwns() throws Exception {
        Clinic clinic = newClinic(true);
        JsonNode atVitals = issue(clinic, newPatient(clinic));
        JsonNode second = issue(clinic, newPatient(clinic));
        move(clinic, "OWNER", second.get("id").asText(), "WITH_DOCTOR", 200);
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("OWNER"), null, 200).size()).isEqualTo(2);
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("FRONT_DESK"), null, 200).size()).isEqualTo(2);
        JsonNode nurse = ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("NURSE"), null, 200);
        assertThat(nurse.findValuesAsText("id")).containsExactly(atVitals.get("id").asText());
        JsonNode doctor = ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("DOCTOR"), null, 200);
        assertThat(doctor.findValuesAsText("id")).containsExactly(second.get("id").asText());
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("VISITING_DOCTOR"), null, 200).size()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("BILLING"), null, 200).size()).isZero();
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("PHARMACIST"), null, 200).size()).isZero();
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=false", clinic.as("NURSE"), null, 200).size()).isEqualTo(2);
        problem(call(HttpMethod.GET, TODAY + "?mine=maybe", clinic.owner(), null), 400);
    }

    @Test
    void emptyBoardIsAnEmptyList() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.GET, TODAY, clinic.owner(), null, 200).size()).isZero();
        assertThat(ok(HttpMethod.GET, TODAY + "?mine=true", clinic.as("NURSE"), null, 200).size()).isZero();
    }

    // ---------------- stage moves ----------------

    @Test
    void ownerWalksAVisitThroughEveryStageOneStepAtATime() throws Exception {
        Clinic clinic = newClinic(true);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        assertThat(move(clinic, "OWNER", id, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(move(clinic, "OWNER", id, "BILLING", 200).get("stage").asText()).isEqualTo("BILLING");
        assertThat(move(clinic, "OWNER", id, "DONE", 200).get("stage").asText()).isEqualTo("DONE");
        assertThat(ok(HttpMethod.GET, TODAY, clinic.owner(), null, 200).get(0).get("stage").asText()).isEqualTo("DONE");
    }

    @Test
    void invalidMovesAreRejected() throws Exception {
        Clinic clinic = newClinic(true);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}"), 400);   // skips a stage
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"VITALS\"}"), 400);    // same stage
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"COUNSEL\"}"), 400);   // switched off
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"FLYING\"}"), 400);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":null}"), 400);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{bad"), 400);
        problem(call(HttpMethod.PATCH, VISITS + "/" + UUID.randomUUID() + "/stage", clinic.owner(), "{\"stage\":\"WITH_DOCTOR\"}"), 404);
        problem(call(HttpMethod.PATCH, VISITS + "/xyz/stage", clinic.owner(), "{\"stage\":\"WITH_DOCTOR\"}"), 400);
    }

    @Test
    void nothingMovesPastDone() throws Exception {
        Clinic clinic = newClinic(false);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        move(clinic, "OWNER", id, "BILLING", 200);
        move(clinic, "OWNER", id, "DONE", 200);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"DONE\"}"), 400);
    }

    @Test
    void backwardMoveIsOwnerOnlyAndWorks() throws Exception {
        Clinic clinic = newClinic(true);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        move(clinic, "OWNER", id, "WITH_DOCTOR", 200);
        Member desk = addMember(clinic, "FRONT_DESK");
        Session deskSession = login(desk.mobile());
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", bearer(deskSession.accessToken()), "{\"stage\":\"VITALS\"}"), 403);
        assertThat(move(clinic, "OWNER", id, "VITALS", 200).get("stage").asText()).isEqualTo("VITALS");
        assertThat(move(clinic, "OWNER", id, "WAITING", 200).get("stage").asText()).isEqualTo("WAITING");
    }

    @Test
    void forwardMovesFollowStageOwnership() throws Exception {
        Clinic clinic = newClinic(true);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        // at VITALS: doctor and billing may not move it, the nurse may, front desk may
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.as("DOCTOR"), "{\"stage\":\"WITH_DOCTOR\"}"), 403);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.as("BILLING"), "{\"stage\":\"WITH_DOCTOR\"}"), 403);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.as("PHARMACIST"), "{\"stage\":\"WITH_DOCTOR\"}"), 403);
        assertThat(move(clinic, "NURSE", id, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        // at WITH_DOCTOR: the nurse may not, the doctor may
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.as("NURSE"), "{\"stage\":\"BILLING\"}"), 403);
        assertThat(move(clinic, "DOCTOR", id, "BILLING", 200).get("stage").asText()).isEqualTo("BILLING");
        // at BILLING: the doctor may not, front desk may
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.as("DOCTOR"), "{\"stage\":\"DONE\"}"), 403);
        assertThat(move(clinic, "FRONT_DESK", id, "DONE", 200).get("stage").asText()).isEqualTo("DONE");
    }

    @Test
    void anotherClinicCannotMoveMyVisit() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String id = issue(a, newPatient(a)).get("id").asText();
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", b.owner(), "{\"stage\":\"WITH_DOCTOR\"}"), 404);
    }

    @Test
    void stageMovesAreAuditedWithoutPatientNames() throws Exception {
        Clinic clinic = newClinic();
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        move(clinic, "OWNER", id, "WITH_DOCTOR", 200);
        String events = ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=50", clinic.owner(), null, 200).toString();
        assertThat(events).contains("VISIT_CREATED", "VISIT_STAGE_MOVED").doesNotContain("Ramesh").doesNotContain("Kulkarni");
    }

    @Test
    void visitsKeepTheirOwnWorkflowVersionWhenTheWorkflowChangesLater() throws Exception {
        Clinic clinic = newClinic(true);
        String id = issue(clinic, newPatient(clinic)).get("id").asText();
        String steps = "{\"steps\":[{\"key\":\"token\",\"on\":true},{\"key\":\"queue\",\"on\":true},"
                + "{\"key\":\"vitals\",\"on\":false},{\"key\":\"consult\",\"on\":true},{\"key\":\"counsel\",\"on\":true,\"owner\":\"DOCTOR\"},"
                + "{\"key\":\"casepaper\",\"on\":true},{\"key\":\"billing\",\"on\":true},{\"key\":\"followup\",\"on\":true}]}";
        ok(HttpMethod.PUT, "/api/v1/opd/workflow/draft", clinic.owner(), steps, 200);
        ok(HttpMethod.POST, "/api/v1/opd/workflow/publish", clinic.owner(), null, 200);
        // old visit still goes VITALS -> WITH_DOCTOR -> BILLING
        move(clinic, "OWNER", id, "WITH_DOCTOR", 200);
        move(clinic, "OWNER", id, "BILLING", 200);
        // a new visit starts at WITH_DOCTOR and goes through COUNSEL
        JsonNode fresh = issue(clinic, newPatient(clinic));
        assertThat(fresh.get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(move(clinic, "OWNER", fresh.get("id").asText(), "COUNSEL", 200).get("stage").asText()).isEqualTo("COUNSEL");
    }
}
