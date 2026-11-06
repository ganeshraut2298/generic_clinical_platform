package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-14 Follow-up lists against the real {@code sahaj_test} database: creation when the doctor step is left,
 * the per-visit workflow version, idempotence, closing on a return visit, the computed DUE / MISSED / UPCOMING /
 * RETURNED tabs (the clock cannot move, so {@code due_date} is back-dated with committed SQL), roles, row level
 * security, the missing DELETE grant and audit privacy.
 */
@OnTestDatabase
class FollowUpListsIT extends SweepSupport {

    private static final String BASE = "/api/v1/opd/follow-ups";
    private static final String VISITS = "/api/v1/opd/visits";
    private static final String APPTS = "/api/v1/opd/appointments";
    private static final String WORKFLOW = "/api/v1/opd/workflow";
    private static final String FEES = "/api/v1/opd/fee-settings";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String REASON = "Review after treatment";

    // ---------------- helpers ----------------

    private static LocalDate istToday() {
        return LocalDate.now(IST);
    }

    private JsonNode list(Clinic clinic, String status) throws Exception {
        return ok(HttpMethod.GET, BASE + "?status=" + status, clinic.owner(), null, 200);
    }

    private JsonNode issue(Clinic clinic, UUID patient, UUID doctor) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\"}", 201);
    }

    private JsonNode move(Clinic clinic, String visitId, String stage, int expected) throws Exception {
        return ok(HttpMethod.PATCH, VISITS + "/" + visitId + "/stage", clinic.owner(),
                "{\"stage\":\"" + stage + "\"}", expected);
    }

    /** Walks a token from its first stage to the stage after the doctor (BILLING; counselling is off by default). */
    private String walkToBilling(Clinic clinic, JsonNode visit) throws Exception {
        String id = visit.get("id").asText();
        if (visit.get("stage").asText().equals("VITALS")) {
            move(clinic, id, "WITH_DOCTOR", 200);
        }
        move(clinic, id, "BILLING", 200);
        return id;
    }

    /** Issues a token for the clinic's own doctor and finishes the consultation. Returns the visit id. */
    private String consultDone(Clinic clinic, UUID patient) throws Exception {
        return walkToBilling(clinic, issue(clinic, patient, clinic.doctorMembershipId()));
    }

    private JsonNode itemForVisit(JsonNode list, String sourceVisitId) {
        for (JsonNode item : list.get("items")) {
            if (item.get("sourceVisitId").asText().equals(sourceVisitId)) {
                return item;
            }
        }
        throw new AssertionError("No follow-up for visit " + sourceVisitId + " in " + list);
    }

    private void assertCounts(JsonNode body, int due, int missed, int upcoming, int returned) {
        JsonNode c = body.get("counts");
        assertThat(c.get("due").asInt()).as("due").isEqualTo(due);
        assertThat(c.get("missed").asInt()).as("missed").isEqualTo(missed);
        assertThat(c.get("upcoming").asInt()).as("upcoming").isEqualTo(upcoming);
        assertThat(c.get("returned").asInt()).as("returned").isEqualTo(returned);
    }

    /** Committed update as sahaj_app with the tenant bound (the app role may UPDATE opd.follow_up). */
    private void setDueDate(UUID tenantId, String sourceVisitId, LocalDate dueDate) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            try (PreparedStatement u = c.prepareStatement(
                    "update opd.follow_up set due_date = ?::date where source_visit_id = ?::uuid")) {
                u.setString(1, dueDate.toString());
                u.setString(2, sourceVisitId);
                assertThat(u.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }

    private long rowCount(UUID tenantId, String where, Object... params) throws SQLException {
        return db().<Long>inTenant(tenantId, c -> DbAccess.count(c, "select count(*) from opd.follow_up " + where, params));
    }

    private static String stepsBody(boolean followupOn) {
        return """
                {"steps":[{"key":"token","on":true},{"key":"queue","on":true},
                 {"key":"vitals","on":true,"owner":"NURSE"},{"key":"consult","on":true},
                 {"key":"counsel","on":false,"owner":"DOCTOR"},{"key":"casepaper","on":true},
                 {"key":"billing","on":true},{"key":"followup","on":%s}]}
                """.formatted(followupOn);
    }

    private static String feesBody(int days) {
        return "{\"feeTiming\":\"before\",\"followUpDays\":" + days
                + ",\"followUpFee\":\"free\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true}";
    }

    // ---------------- AC1: consult done creates a follow-up ----------------

    @Test
    void consultDoneCreatesOneUpcomingFollowUpWithDefaultWindowNamesMobileAndCounts() throws Exception {
        Clinic clinic = newClinic(true); // vitals on: WAITING is skipped, VITALS -> WITH_DOCTOR -> BILLING
        UUID patient = newPatient(clinic);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 0, 0);

        JsonNode visit = issue(clinic, patient, clinic.doctorMembershipId());
        assertThat(visit.get("stage").asText()).isEqualTo("VITALS");
        String visitId = visit.get("id").asText();
        move(clinic, visitId, "WITH_DOCTOR", 200);
        // Still with the doctor: nothing yet.
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 0, 0);
        move(clinic, visitId, "BILLING", 200);

        JsonNode body = list(clinic, "UPCOMING");
        assertCounts(body, 0, 0, 1, 0);
        assertThat(body.get("items")).hasSize(1);
        JsonNode item = body.get("items").get(0);
        // No fee settings row: the default window of 7 days applies (the wizard also chose 7).
        assertThat(getJson(FEES, clinic.owner()).get("followUpDays").asInt()).isEqualTo(7);
        assertThat(item.get("dueDate").asText()).isEqualTo(istToday().plusDays(7).toString());
        assertThat(item.get("reason").asText()).isEqualTo(REASON);
        assertThat(item.get("status").asText()).isEqualTo("UPCOMING");
        assertThat(item.get("daysLate").asInt()).isZero();
        assertThat(item.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(item.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(item.get("patientId").asText()).isEqualTo(patient.toString());
        assertThat(item.get("doctorMembershipId").asText()).isEqualTo(clinic.doctorMembershipId().toString());
        assertThat(item.get("sourceVisitId").asText()).isEqualTo(visitId);
        assertThat(item.get("returnedVisitId").isNull()).isTrue();
        String mobile = getJson("/api/v1/patient/patients/" + patient, clinic.owner()).get("mobile").asText();
        assertThat(item.get("mobile").asText()).isEqualTo(mobile).matches("[6-9][0-9]{9}");

        // Other tabs stay empty.
        for (String tab : List.of("DUE", "MISSED", "RETURNED")) {
            assertThat(list(clinic, tab).get("items")).as(tab).isEmpty();
        }
    }

    @Test
    void withVitalsOffTheDoctorStepIsFirstAndStillCreatesTheFollowUp() throws Exception {
        Clinic clinic = newClinic(false);
        JsonNode visit = issue(clinic, newPatient(clinic), clinic.doctorMembershipId());
        assertThat(visit.get("stage").asText()).isEqualTo("WITH_DOCTOR");
        move(clinic, visit.get("id").asText(), "BILLING", 200);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 1, 0);
    }

    @Test
    void changingTheFollowUpWindowInFeeSettingsMovesTheDueDateOfLaterVisitsOnly() throws Exception {
        Clinic clinic = newClinic();
        String first = consultDone(clinic, newPatient(clinic));
        ok(HttpMethod.PUT, FEES, clinic.owner(), feesBody(3), 200);
        String second = consultDone(clinic, newPatient(clinic));

        JsonNode body = list(clinic, "UPCOMING");
        assertCounts(body, 0, 0, 2, 0);
        assertThat(itemForVisit(body, first).get("dueDate").asText()).isEqualTo(istToday().plusDays(7).toString());
        assertThat(itemForVisit(body, second).get("dueDate").asText()).isEqualTo(istToday().plusDays(3).toString());
        // Oldest due date first.
        assertThat(body.get("items").get(0).get("sourceVisitId").asText()).isEqualTo(second);
        assertThat(body.get("items").get(1).get("sourceVisitId").asText()).isEqualTo(first);
    }

    // ---------------- AC2: the visit keeps its own workflow version ----------------

    @Test
    void followUpStepSwitchedOffAppliesToNewVisitsButNotToVisitsIssuedBeforeThePublish() throws Exception {
        Clinic clinic = newClinic();
        JsonNode before = issue(clinic, newPatient(clinic), clinic.doctorMembershipId()); // stamped version 1 (follow-up on)
        ok(HttpMethod.PUT, WORKFLOW + "/draft", clinic.owner(), stepsBody(false), 200);
        ok(HttpMethod.POST, WORKFLOW + "/publish", clinic.owner(), null, 200);
        assertThat(getJson(WORKFLOW + "/current", clinic.owner()).get("versionNo").asInt()).isEqualTo(2);
        JsonNode after = issue(clinic, newPatient(clinic), clinic.doctorMembershipId()); // stamped version 2 (off)

        String newVisit = walkToBilling(clinic, after);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 0, 0);
        assertThat(rowCount(clinic.tenantId(), "where source_visit_id = ?::uuid", newVisit)).isZero();

        String oldVisit = walkToBilling(clinic, before);
        JsonNode body = list(clinic, "UPCOMING");
        assertCounts(body, 0, 0, 1, 0);
        assertThat(body.get("items").get(0).get("sourceVisitId").asText()).isEqualTo(oldVisit);
    }

    // ---------------- AC1 / idempotence ----------------

    @Test
    void exactlyOneFollowUpPerVisitEvenWhenTheForwardMoveIsRepeated() throws Exception {
        Clinic clinic = newClinic();
        String visitId = consultDone(clinic, newPatient(clinic));
        // Repeating the same move is refused (already there) and creates nothing.
        problem(call(HttpMethod.PATCH, VISITS + "/" + visitId + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}"), 400);
        assertThat(rowCount(clinic.tenantId(), "where source_visit_id = ?::uuid", visitId)).isEqualTo(1);

        // Owner corrects the mistake (back to the doctor) and the doctor finishes again: still one row.
        move(clinic, visitId, "WITH_DOCTOR", 200);
        move(clinic, visitId, "BILLING", 200);
        assertThat(rowCount(clinic.tenantId(), "where source_visit_id = ?::uuid", visitId)).isEqualTo(1);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 1, 0);
        long created = db().<Long>inTenant(clinic.tenantId(), c -> DbAccess.count(c,
                "select count(*) from platform.audit_event where action = 'FOLLOW_UP_CREATED' and after_json ->> 'sourceVisitId' = ?",
                visitId));
        assertThat(created).as("one creation audit row").isEqualTo(1L);
    }

    // ---------------- AC3: closing on a return visit ----------------

    @Test
    void newTokenForSamePatientAndDoctorReturnsTheOldestOpenFollowUpOnlyOneAtATime() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        UUID doctor = clinic.doctorMembershipId();
        // Two tokens first, so that two consultations can finish with the follow-up still open.
        JsonNode v1 = issue(clinic, patient, doctor);
        JsonNode v2 = issue(clinic, patient, doctor);
        String first = walkToBilling(clinic, v1);
        String second = walkToBilling(clinic, v2);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 2, 0);

        // A different doctor's token does not close anything.
        Member other = addMember(clinic, "DOCTOR");
        issue(clinic, patient, other.membershipId());
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 2, 0);

        // Same doctor: the oldest (first) closes.
        String third = issue(clinic, patient, doctor).get("id").asText();
        JsonNode afterThird = list(clinic, "RETURNED");
        assertCounts(afterThird, 0, 0, 1, 1);
        JsonNode closed = itemForVisit(afterThird, first);
        assertThat(closed.get("status").asText()).isEqualTo("RETURNED");
        assertThat(closed.get("returnedVisitId").asText()).isEqualTo(third);
        assertThat(closed.get("daysLate").asInt()).isZero();
        assertThat(itemForVisit(list(clinic, "UPCOMING"), second).get("status").asText()).isEqualTo("UPCOMING");

        // Next token closes the next oldest open row, not the one already returned.
        String fourth = issue(clinic, patient, doctor).get("id").asText();
        JsonNode afterFourth = list(clinic, "RETURNED");
        assertCounts(afterFourth, 0, 0, 0, 2);
        assertThat(itemForVisit(afterFourth, first).get("returnedVisitId").asText()).isEqualTo(third);
        assertThat(itemForVisit(afterFourth, second).get("returnedVisitId").asText()).isEqualTo(fourth);

        // Nothing is open any more: another token changes nothing.
        issue(clinic, patient, doctor);
        assertCounts(list(clinic, "RETURNED"), 0, 0, 0, 2);
        assertThat(rowCount(clinic.tenantId(), "where returned_visit_id is not null and returned_at is not null"))
                .isEqualTo(2);
    }

    @Test
    void createTokenFromAnAppointmentAlsoReturnsTheFollowUp() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        String source = consultDone(clinic, patient);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 1, 0);

        JsonNode appt = ok(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId()
                        + "\",\"apptDate\":\"" + LocalDate.now().plusDays(3) + "\",\"apptTime\":\"10:00\","
                        + "\"visitType\":\"FOLLOW_UP\",\"source\":\"FRONT_DESK\",\"note\":\"Review\"}", 201);
        assertCounts(list(clinic, "UPCOMING"), 0, 0, 1, 0); // booking alone does not close it
        JsonNode tokened = ok(HttpMethod.POST, APPTS + "/" + appt.get("id").asText() + "/token",
                clinic.as("FRONT_DESK"), null, 200);

        JsonNode body = list(clinic, "RETURNED");
        assertCounts(body, 0, 0, 0, 1);
        assertThat(itemForVisit(body, source).get("returnedVisitId").asText()).isEqualTo(tokened.get("visitId").asText());
    }

    // ---------------- AC4/5: computed statuses and tab membership ----------------

    @Test
    void duePastDueUpcomingAndReturnedTabsAreComputedFromTodayWithOldestDueFirst() throws Exception {
        Clinic clinic = newClinic();
        LocalDate today = istToday();
        List<UUID> patients = new ArrayList<>();
        List<String> visits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID p = newPatient(clinic);
            patients.add(p);
            visits.add(consultDone(clinic, p));
        }
        String dueToday = visits.get(0);
        String late3 = visits.get(1);
        String late10 = visits.get(2);
        String upcoming7 = visits.get(3); // keeps the natural +7 date
        String upcoming2 = visits.get(4);
        String returned = visits.get(5);
        setDueDate(clinic.tenantId(), dueToday, today);
        setDueDate(clinic.tenantId(), late3, today.minusDays(3));
        setDueDate(clinic.tenantId(), late10, today.minusDays(10));
        setDueDate(clinic.tenantId(), upcoming2, today.plusDays(2));
        issue(clinic, patients.get(5), clinic.doctorMembershipId()); // the sixth patient comes back

        JsonNode due = list(clinic, "DUE");
        assertCounts(due, 1, 2, 2, 1);
        assertThat(due.get("items")).hasSize(1);
        assertThat(due.get("items").get(0).get("sourceVisitId").asText()).isEqualTo(dueToday);
        assertThat(due.get("items").get(0).get("status").asText()).isEqualTo("DUE");
        assertThat(due.get("items").get(0).get("daysLate").asInt()).isZero();

        JsonNode missed = list(clinic, "MISSED");
        assertThat(missed.get("items")).hasSize(2);
        assertThat(missed.get("items").get(0).get("sourceVisitId").asText()).as("oldest due date first").isEqualTo(late10);
        assertThat(missed.get("items").get(0).get("daysLate").asInt()).isEqualTo(10);
        assertThat(missed.get("items").get(0).get("status").asText()).isEqualTo("MISSED");
        assertThat(missed.get("items").get(1).get("sourceVisitId").asText()).isEqualTo(late3);
        assertThat(missed.get("items").get(1).get("daysLate").asInt()).isEqualTo(3);

        JsonNode upcoming = list(clinic, "UPCOMING");
        assertThat(upcoming.get("items")).hasSize(2);
        assertThat(upcoming.get("items").get(0).get("sourceVisitId").asText()).isEqualTo(upcoming2);
        assertThat(upcoming.get("items").get(1).get("sourceVisitId").asText()).isEqualTo(upcoming7);
        assertThat(upcoming.get("items").get(0).get("daysLate").asInt()).isZero();

        JsonNode ret = list(clinic, "RETURNED");
        assertThat(ret.get("items")).hasSize(1);
        assertThat(ret.get("items").get(0).get("sourceVisitId").asText()).isEqualTo(returned);
    }

    @Test
    void aReturnedFollowUpStaysReturnedEvenWhenItsDueDateIsInThePast() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        String source = consultDone(clinic, patient);
        setDueDate(clinic.tenantId(), source, istToday().minusDays(5));
        assertCounts(list(clinic, "MISSED"), 0, 1, 0, 0);
        issue(clinic, patient, clinic.doctorMembershipId());
        assertCounts(list(clinic, "MISSED"), 0, 0, 0, 1);
        assertThat(list(clinic, "MISSED").get("items")).isEmpty();
        assertThat(itemForVisit(list(clinic, "RETURNED"), source).get("daysLate").asInt()).isZero();
    }

    // ---------------- AC6: validation, roles, authentication ----------------

    @Test
    void missingOrUnknownStatusIsA400ProblemBody() throws Exception {
        Clinic clinic = newClinic();
        JsonNode missing = problem(call(HttpMethod.GET, BASE, clinic.owner(), null), 400);
        assertThat(missing.get("type").asText()).isEqualTo(PROBLEM_BASE + "validation");
        problem(call(HttpMethod.GET, BASE + "?status=", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, BASE + "?status=BOGUS", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, BASE + "?status=due", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, BASE + "?status=DUE%20OR%201=1", clinic.owner(), null), 400);
    }

    @Test
    void everyClinicRoleCanReadAndAnonymousCallersGet401() throws Exception {
        Clinic clinic = newClinic();
        consultDone(clinic, newPatient(clinic));
        for (String role : List.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING",
                "PHARMACIST", "COUNSELLOR")) {
            RequestPostProcessor auth = clinic.as(role);
            JsonNode body = ok(HttpMethod.GET, BASE + "?status=UPCOMING", auth, null, 200);
            assertThat(body.get("items")).as(role).hasSize(1);
        }
        mvc.perform(get(BASE + "?status=UPCOMING")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesThatCannotReadPatientsSeeNamesButNoMobile() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        String mobile = getJson("/api/v1/patient/patients/" + patient, clinic.owner()).get("mobile").asText();
        consultDone(clinic, patient);
        for (String role : List.of("PHARMACIST", "BILLING", "COUNSELLOR")) {
            JsonNode item = ok(HttpMethod.GET, BASE + "?status=UPCOMING", clinic.as(role), null, 200)
                    .get("items").get(0);
            assertThat(item.get("patientName").asText()).as(role).isEqualTo("Ramesh Kulkarni");
            assertThat(item.get("mobile") == null || item.get("mobile").isNull()).as(role).isTrue();
        }
        for (String role : List.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR")) {
            JsonNode item = ok(HttpMethod.GET, BASE + "?status=UPCOMING", clinic.as(role), null, 200)
                    .get("items").get(0);
            assertThat(item.get("mobile").asText()).as(role).isEqualTo(mobile);
        }
    }

    @Test
    void followUpsAreReadOnlyThroughTheApi() throws Exception {
        Clinic clinic = newClinic();
        // No write verbs exist on the resource: POST is refused (405 or 404, never a 2xx).
        int statusCode = mvc.perform(post(BASE).with(clinic.owner())).andReturn().getResponse().getStatus();
        assertThat(statusCode).isGreaterThanOrEqualTo(400);
    }

    // ---------------- tenant isolation, grants ----------------

    @Test
    void anotherClinicNeverSeesTheFollowUpsNeitherThroughTheApiNorUnderRowLevelSecurity() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        consultDone(a, newPatient(a));
        consultDone(a, newPatient(a));
        assertCounts(list(a, "UPCOMING"), 0, 0, 2, 0);

        JsonNode seenByB = list(b, "UPCOMING");
        assertCounts(seenByB, 0, 0, 0, 0);
        assertThat(seenByB.get("items")).isEmpty();
        assertThat(rowCount(a.tenantId(), "")).isEqualTo(2);
        assertThat(rowCount(b.tenantId(), "")).isZero();
        // No tenant bound: fail-closed, no rows.
        long unbound = db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from opd.follow_up"));
        assertThat(unbound).isZero();
        // B bound, A's row addressed by id: invisible and not updatable.
        db().inTenant(b.tenantId(), c -> {
            try (PreparedStatement u = c.prepareStatement("update opd.follow_up set reason = 'x' where tenant_id = ?::uuid")) {
                u.setString(1, a.tenantId().toString());
                assertThat(u.executeUpdate()).isZero();
            }
            return null;
        });
        // A's visits closing never touch B: a token in B for a fresh patient creates no follow-up in A.
        issue(b, newPatient(b), b.doctorMembershipId());
        assertCounts(list(a, "UPCOMING"), 0, 0, 2, 0);
    }

    @Test
    void theApplicationRoleCannotDeleteFollowUps() throws Exception {
        Clinic clinic = newClinic();
        consultDone(clinic, newPatient(clinic));
        db().inTenant(clinic.tenantId(), c -> {
            assertThatThrownBy(() -> DbAccess.execute(c, "delete from opd.follow_up"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            return null;
        });
        assertThat(rowCount(clinic.tenantId(), "")).isEqualTo(1);
    }

    // ---------------- privacy ----------------

    @Test
    void followUpAuditRowsCarryIdsOnlyNoNameMobileOrReason() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        String mobile = getJson("/api/v1/patient/patients/" + patient, clinic.owner()).get("mobile").asText();
        consultDone(clinic, patient);
        issue(clinic, patient, clinic.doctorMembershipId()); // returns it: FOLLOW_UP_RETURNED

        List<String> texts = db().inTenant(clinic.tenantId(), c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select action, coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, '') "
                            + "from platform.audit_event where action like 'FOLLOW\\_UP\\_%'")) {
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getString(1) + " " + rs.getString(2));
                    }
                }
            }
            return rows;
        });
        assertThat(texts).anyMatch(t -> t.startsWith("FOLLOW_UP_CREATED"));
        assertThat(texts).anyMatch(t -> t.startsWith("FOLLOW_UP_RETURNED"));
        for (String t : texts) {
            assertThat(t).doesNotContain("Ramesh").doesNotContain("Kulkarni").doesNotContain(mobile)
                    .doesNotContain("Review after treatment").doesNotContain("Dr Asha");
        }
    }
}
