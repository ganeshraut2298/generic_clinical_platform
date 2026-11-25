package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-92 and SAH-93 against the real sahaj_test database: check-in and the token, the stage compare-and-set, the consult
 * times, the follow-up writes, and the races between them. Test plan: docs/test-plans/SAH-90.md (T92-*, T93-*).
 * The clinic has Vitals off, so a token starts in WAITING and the next stages are WITH_DOCTOR and BILLING.
 */
@OnTestDatabase
class DeskFlowIT extends DeskSupport {

    private static final String PATIENT_NAME = "Ramesh Kulkarni";
    private static final String DOCTOR_NAME = "Asha";

    private long auditRowsNaming(UUID tenantId, String text) throws SQLException {
        return countOf(tenantId, "select count(*) from platform.audit_event where audit_event::text ilike ?", "%" + text + "%");
    }

    /** Audit rows of the desk actions (appointment, visit and follow-up events) that name the text. */
    private long deskAuditRowsNaming(UUID tenantId, String text) throws SQLException {
        return countOf(tenantId, "select count(*) from platform.audit_event where audit_event::text ilike ? and (action like 'APPOINTMENT%' "
                + "or action like 'VISIT%' or action like 'FOLLOW_UP%')", "%" + text + "%");
    }

    private String tokenOf(UUID tenantId, String appointmentId) throws SQLException {
        return scalar(tenantId, "select v.token_no from opd.visit v join opd.appointment a on a.visit_id = v.id "
                + "where a.id = ?::uuid", appointmentId);
    }

    private JsonNode walkIn(Clinic clinic, UUID patient) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.as("FRONT_DESK"),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
    }

    /** A walk-in token through the Queue endpoint, moved to BILLING (visit completed). Returns the visit id. */
    private String completedWalkIn(Clinic clinic, UUID patient) throws Exception {
        JsonNode visit = walkIn(clinic, patient);
        String id = visit.get("id").asText();
        assertThat(visit.get("stage").asText()).isEqualTo("VITALS");
        moveStage(clinic, id, "WITH_DOCTOR", "VITALS", 200);
        moveStage(clinic, id, "BILLING", "WITH_DOCTOR", 200);
        return id;
    }

    private String visitFollowUp(String interval, LocalDate date, String time) {
        return "{\"intervalKey\":\"" + interval + "\",\"apptDate\":\"" + date + "\",\"apptTime\":\"" + time + "\"}";
    }

    // ---------------------------------------------------------------- SAH-92-1 check-in

    @Test
    void checkInIssuesTheTokenOnTheIstDayAnswersThePanelAndKeepsNamesOutOfTheAudit() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        JsonNode appt = bookForToday(clinic, patient, clinic.doctorMembershipId(), "09:00");
        String id = appt.get("id").asText();

        JsonNode checked = checkIn(clinic, id);
        assertThat(checked.get("status").asText()).isEqualTo("ARRIVED");
        assertThat(checked.get("tokenNo").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode panel = checked.get("tokenPanel");
        assertThat(panel.get("tokenNo").asInt()).isEqualTo(checked.get("tokenNo").asInt());
        assertThat(panel.get("patientsAhead").asInt()).isZero();
        assertThat(panel.get("estimatedWaitMinutes").asInt()).isZero();
        assertThat(panel.get("stage").asText()).isEqualTo("VITALS");
        assertThat(panel.get("tokenDate").asText()).isEqualTo(istToday().toString());

        // one visit, token day = the clinic's IST calendar day, created in the same IST day
        assertThat(visitCount(clinic, id)).isEqualTo(1);
        assertThat(scalar(clinic.tenantId(), "select v.token_date::text from opd.visit v join opd.appointment a on a.visit_id = v.id "
                + "where a.id = ?::uuid", id)).isEqualTo(istToday().toString());
        assertThat(scalar(clinic.tenantId(), "select (v.created_at at time zone 'Asia/Kolkata')::date::text from opd.visit v "
                + "join opd.appointment a on a.visit_id = v.id where a.id = ?::uuid", id)).isEqualTo(istToday().toString());

        // the token panel can be read again by any clinic role, with no-store
        String visitId = checked.get("visitId").asText();
        for (String role : List.of("OWNER", "NURSE", "DOCTOR", "BILLING")) {
            mvc.perform(get(DESK + "/visits/" + visitId + "/token-panel").with(clinic.as(role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        }

        // audit: written, and carries no patient or doctor name
        assertThat(auditRowsNaming(clinic.tenantId(), "APPOINTMENT_TOKEN_ISSUED")).isGreaterThanOrEqualTo(1);
        assertThat(auditRowsNaming(clinic.tenantId(), PATIENT_NAME)).isZero();
        assertThat(auditRowsNaming(clinic.tenantId(), "Kulkarni")).isZero();
        assertThat(deskAuditRowsNaming(clinic.tenantId(), DOCTOR_NAME)).isZero();
    }

    @Test
    void aSecondCheckInIs409AlreadyCheckedInWithTheTokenAndNoSecondVisit() throws Exception {
        Clinic clinic = newClinic();
        String id = bookForToday(clinic, newPatient(clinic), clinic.doctorMembershipId(), "09:00").get("id").asText();
        JsonNode first = checkIn(clinic, id);
        JsonNode second = problem(call(HttpMethod.POST, APPTS + "/" + id + "/token", clinic.as("FRONT_DESK"), null), 409);
        assertThat(slugOf(second)).isEqualTo("appointment-already-checked-in");
        assertThat(second.get("tokenNo").asInt()).isEqualTo(first.get("tokenNo").asInt());
        assertThat(second.get("visitId").asText()).isEqualTo(first.get("visitId").asText());
        assertThat(visitCount(clinic, id)).isEqualTo(1);
    }

    @Test
    void checkInRefusalsNotTodayNotBookedRoleAndOtherClinic() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        String later = book(clinic, newPatient(clinic), doctor, tomorrow(), "09:00").get("id").asText();
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + later + "/token", clinic.as("FRONT_DESK"), null), 400)))
                .isEqualTo("check-in-not-today");

        String cancelled = bookForToday(clinic, newPatient(clinic), doctor, "09:15").get("id").asText();
        ok(HttpMethod.PATCH, APPTS + "/" + cancelled, clinic.owner(), "{\"status\":\"CANCELLED\"}", 200);
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + cancelled + "/token", clinic.as("FRONT_DESK"), null), 409)))
                .isEqualTo("appointment-not-bookable");
        assertThat(visitCount(clinic, cancelled)).isZero();
        // cancelling again: the appointment is no longer booked
        assertThat(slugOf(problem(call(HttpMethod.PATCH, APPTS + "/" + cancelled, clinic.owner(), "{\"status\":\"CANCELLED\"}"), 409)))
                .isEqualTo("appointment-not-booked");

        String today = bookForToday(clinic, newPatient(clinic), doctor, "09:30").get("id").asText();
        for (String role : List.of("NURSE", "DOCTOR", "BILLING")) {
            problem(call(HttpMethod.POST, APPTS + "/" + today + "/token", clinic.as(role), null), 403);
        }
        Clinic other = newClinic();
        problem(call(HttpMethod.POST, APPTS + "/" + today + "/token", other.owner(), null), 404);
        assertThat(visitCount(clinic, today)).isZero();

        // arrived: a manual no-show or cancel is refused with 409
        checkIn(clinic, today);
        assertThat(slugOf(problem(call(HttpMethod.PATCH, APPTS + "/" + today, clinic.owner(), "{\"status\":\"NOSHOW\"}"), 409)))
                .isEqualTo("appointment-not-booked");
        // a status that is not Cancelled or No-show: 400
        String another = bookForToday(clinic, newPatient(clinic), doctor, "09:45").get("id").asText();
        problem(call(HttpMethod.PATCH, APPTS + "/" + another, clinic.owner(), "{\"status\":\"ARRIVED\"}"), 400);
    }

    @Test
    void twoCheckInsOfOneAppointmentGiveOneVisitOneTokenAndOneAlreadyCheckedIn409() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        for (int round = 0; round < 4; round++) {
            String id = bookForToday(clinic, newPatient(clinic), doctor, String.format("09:%02d", round * 15)).get("id").asText();
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, APPTS + "/" + id + "/token", clinic.as("FRONT_DESK"), null),
                    request(HttpMethod.POST, APPTS + "/" + id + "/token", clinic.as("OWNER"), null)));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(visitCount(clinic, id)).as("one visit, round " + round).isEqualTo(1);
        }
        assertThat(countOf(clinic.tenantId(), "select count(distinct token_no) from opd.visit")).isEqualTo(4);
    }

    @Test
    void twoCheckInsOfDifferentAppointmentsGetTwoDifferentConsecutiveTokens() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        for (int round = 0; round < 4; round++) {
            String a = bookForToday(clinic, newPatient(clinic), doctor, String.format("%02d:00", 9 + round)).get("id").asText();
            String b = bookForToday(clinic, newPatient(clinic), doctor, String.format("%02d:30", 9 + round)).get("id").asText();
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, APPTS + "/" + a + "/token", clinic.as("FRONT_DESK"), null),
                    request(HttpMethod.POST, APPTS + "/" + b + "/token", clinic.as("FRONT_DESK"), null)));
            assertThat(statuses).as("round " + round).containsExactly(200, 200);
            int ta = Integer.parseInt(tokenOf(clinic.tenantId(), a));
            int tb = Integer.parseInt(tokenOf(clinic.tenantId(), b));
            assertThat(Math.abs(ta - tb)).as("consecutive tokens, round " + round).isEqualTo(1);
        }
        // gap-free and unique over the whole day: 1..8
        assertThat(countOf(clinic.tenantId(), "select count(distinct token_no) from opd.visit where token_date = ?::date",
                istToday())).isEqualTo(8);
        assertThat(countOf(clinic.tenantId(), "select max(token_no) from opd.visit where token_date = ?::date",
                istToday())).isEqualTo(8);
    }

    @Test
    void aCancelRacingACheckInEndsInOneOutcomeWithNoVisitForACancelledAppointment() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        for (int round = 0; round < 4; round++) {
            String id = bookForToday(clinic, newPatient(clinic), doctor, String.format("%02d:00", 9 + round)).get("id").asText();
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, APPTS + "/" + id + "/token", clinic.as("FRONT_DESK"), null),
                    request(HttpMethod.PATCH, APPTS + "/" + id, clinic.as("FRONT_DESK"), "{\"status\":\"CANCELLED\"}")));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            String status = scalar(clinic.tenantId(), "select status from opd.appointment where id = ?::uuid", id);
            long visits = visitCount(clinic, id);
            if (status.equals("ARRIVED")) {
                assertThat(visits).isEqualTo(1);
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(visits).isZero();
            }
        }
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment a where a.status = 'CANCELLED' "
                + "and a.visit_id is not null")).isZero();
    }

    @Test
    void theTokenCounterIsPerClinicPerDayAndTheDateOverloadStartsAtOneForEachDay() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String first = bookForToday(a, newPatient(a), a.doctorMembershipId(), "09:00").get("id").asText();
        checkIn(a, first);
        String other = bookForToday(b, newPatient(b), b.doctorMembershipId(), "09:00").get("id").asText();
        JsonNode inB = checkIn(b, other);
        assertThat(inB.get("tokenNo").asInt()).as("another clinic starts at 1").isEqualTo(1);
        // the IST-day overload: each date has its own counter (rolled back afterwards)
        LocalDate far = istToday().plusDays(400);
        long[] seen = db().<long[]>inTenant(a.tenantId(), c -> {
            long[] out = new long[3];
            for (int i = 0; i < 3; i++) {
                try (java.sql.PreparedStatement s = c.prepareStatement("select opd.next_token_no(?::date)")) {
                    s.setString(1, i < 2 ? far.toString() : far.plusDays(1).toString());
                    try (java.sql.ResultSet rs = s.executeQuery()) {
                        rs.next();
                        out[i] = rs.getLong(1);
                    }
                }
            }
            return out;
        });
        assertThat(seen).containsExactly(1, 2, 1);
        // with no tenant bound, no counter row can be made
        assertThat(db().<Boolean>asApp(c -> {
            try (java.sql.PreparedStatement s = c.prepareStatement("select opd.next_token_no(current_date)")) {
                s.executeQuery();
                return false;
            } catch (SQLException expected) {
                return true;
            }
        })).isTrue();
    }

    // ---------------------------------------------------------------- SAH-92-3 stage moves

    @Test
    void theStageMovesOneStepWithExpectedStageAndRefusesAStaleOrSkippingMove() throws Exception {
        Clinic clinic = newClinic();
        String visit = completedWalkIn(clinic, newPatient(clinic)); // now BILLING
        assertThat(scalar(clinic.tenantId(), "select stage from opd.visit where id = ?::uuid", visit)).isEqualTo("BILLING");
        assertThat(slugOf(problem(call(HttpMethod.PATCH, VISITS + "/" + visit + "/stage", clinic.as("FRONT_DESK"),
                "{\"stage\":\"DONE\",\"expectedStage\":\"VITALS\"}"), 409))).isEqualTo("visit-stage-changed");

        String second = walkIn(clinic, newPatient(clinic)).get("id").asText();
        // skipping a stage with the compare-and-set is 409, not 400
        assertThat(slugOf(problem(call(HttpMethod.PATCH, VISITS + "/" + second + "/stage", clinic.as("FRONT_DESK"),
                "{\"stage\":\"BILLING\",\"expectedStage\":\"VITALS\"}"), 409))).isEqualTo("visit-stage-changed");
        assertThat(scalar(clinic.tenantId(), "select stage from opd.visit where id = ?::uuid", second)).isEqualTo("VITALS");
        // invalid stage word: 400
        problem(call(HttpMethod.PATCH, VISITS + "/" + second + "/stage", clinic.as("FRONT_DESK"),
                "{\"stage\":\"NOPE\",\"expectedStage\":\"VITALS\"}"), 400);
        // a role that does not own the step: 403
        problem(call(HttpMethod.PATCH, VISITS + "/" + second + "/stage", clinic.as("BILLING"),
                "{\"stage\":\"WITH_DOCTOR\",\"expectedStage\":\"VITALS\"}"), 403);
        // another clinic: 404
        Clinic other = newClinic();
        problem(call(HttpMethod.PATCH, VISITS + "/" + second + "/stage", other.owner(),
                "{\"stage\":\"WITH_DOCTOR\",\"expectedStage\":\"VITALS\"}"), 404);
        // the right move works
        assertThat(moveStage(clinic, second, "WITH_DOCTOR", "VITALS", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
    }

    @Test
    void twoPressesOfTheSameStageMoveMakeOneStepAndOneVisitStageChanged409() throws Exception {
        Clinic clinic = newClinic();
        for (int round = 0; round < 5; round++) {
            String visit = walkIn(clinic, newPatient(clinic)).get("id").asText();
            String body = "{\"stage\":\"WITH_DOCTOR\",\"expectedStage\":\"VITALS\"}";
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.PATCH, VISITS + "/" + visit + "/stage", clinic.as("FRONT_DESK"), body),
                    request(HttpMethod.PATCH, VISITS + "/" + visit + "/stage", clinic.as("OWNER"), body)));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(scalar(clinic.tenantId(), "select stage from opd.visit where id = ?::uuid", visit)).isEqualTo("WITH_DOCTOR");
            assertThat(countOf(clinic.tenantId(), "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED' "
                    + "and entity_id = ?::uuid", visit)).as("one audited step").isEqualTo(1);
        }
    }

    @Test
    void theConsultationTimesAreStoredOnceEvenWhenTheOwnerMovesTheCardBackAndForward() throws Exception {
        Clinic clinic = newClinic();
        String visit = walkIn(clinic, newPatient(clinic)).get("id").asText();
        String q = "select %s::text from opd.visit where id = ?::uuid";
        assertThat(scalar(clinic.tenantId(), q.formatted("consult_started_at"), visit)).isNull();
        moveStage(clinic, visit, "WITH_DOCTOR", "VITALS", 200);
        String startedAt = scalar(clinic.tenantId(), q.formatted("consult_started_at"), visit);
        assertThat(startedAt).isNotNull();
        assertThat(scalar(clinic.tenantId(), q.formatted("consult_ended_at"), visit)).isNull();
        moveStage(clinic, visit, "BILLING", "WITH_DOCTOR", 200);
        String endedAt = scalar(clinic.tenantId(), q.formatted("consult_ended_at"), visit);
        assertThat(endedAt).isNotNull();
        // the owner corrects a mistake: back into the room and forward again
        ok(HttpMethod.PATCH, VISITS + "/" + visit + "/stage", clinic.owner(), "{\"stage\":\"WITH_DOCTOR\"}", 200);
        ok(HttpMethod.PATCH, VISITS + "/" + visit + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        assertThat(scalar(clinic.tenantId(), q.formatted("consult_started_at"), visit)).isEqualTo(startedAt);
        assertThat(scalar(clinic.tenantId(), q.formatted("consult_ended_at"), visit)).isEqualTo(endedAt);
        // the database refuses an end before the start
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> exec(clinic.tenantId(),
                "update opd.visit set consult_ended_at = consult_started_at - interval '1 minute' where id = ?::uuid", visit));
        assertThat(refused).isInstanceOf(SQLException.class);
    }

    // ---------------------------------------------------------------- SAH-93 follow-up writes

    @Test
    void aWalkInVisitCanBookItsFollowUpAfterCompleteVisitAndASecondBookingIs409() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        String visit = completedWalkIn(clinic, patient);
        String path = VISITS + "/" + visit + "/follow-up/appointment";

        JsonNode booked = ok(HttpMethod.POST, path, clinic.as("FRONT_DESK"), visitFollowUp("D7", tomorrow(), "09:15"), 201);
        assertThat(booked.get("state").asText()).isEqualTo("BOOKED");
        assertThat(booked.get("dueDate").asText()).isEqualTo(istToday().plusDays(7).toString());
        assertThat(booked.get("appointment").get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(booked.get("appointment").get("apptTime").asText()).isEqualTo("09:15");
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid "
                + "and appointment_id is not null", visit)).isEqualTo(1);
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid", visit)).isEqualTo(1);

        assertThat(slugOf(problem(call(HttpMethod.POST, path, clinic.as("FRONT_DESK"),
                visitFollowUp("D7", tomorrow(), "09:30")), 409))).isEqualTo("follow-up-already-booked");
        assertThat(slugOf(problem(call(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/not-needed", clinic.as("FRONT_DESK"),
                null), 409))).isEqualTo("follow-up-already-booked");
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where visit_type = 'FOLLOW_UP' "
                + "and patient_id = ?::uuid", patient.toString())).isEqualTo(1);
        assertThat(auditRowsNaming(clinic.tenantId(), "FOLLOW_UP_BOOKED")).isEqualTo(1);
        assertThat(auditRowsNaming(clinic.tenantId(), PATIENT_NAME)).isZero();
        assertThat(auditRowsNaming(clinic.tenantId(), "Kulkarni")).isZero();
    }

    @Test
    void followUpWritesValidateRoleVisitStateAndInput() throws Exception {
        Clinic clinic = newClinic();
        String waiting = walkIn(clinic, newPatient(clinic)).get("id").asText();
        assertThat(slugOf(problem(call(HttpMethod.POST, VISITS + "/" + waiting + "/follow-up/not-needed", clinic.as("FRONT_DESK"),
                null), 409))).isEqualTo("visit-not-done");
        assertThat(slugOf(problem(call(HttpMethod.POST, VISITS + "/" + waiting + "/follow-up/appointment", clinic.as("FRONT_DESK"),
                visitFollowUp("D7", tomorrow(), "09:00")), 409))).isEqualTo("visit-not-done");

        String done = completedWalkIn(clinic, newPatient(clinic));
        String path = VISITS + "/" + done + "/follow-up/appointment";
        problem(call(HttpMethod.POST, path, clinic.as("FRONT_DESK"), visitFollowUp("D99", tomorrow(), "09:00")), 400);
        problem(call(HttpMethod.POST, path, clinic.as("FRONT_DESK"), "{\"intervalKey\":\"D7\",\"apptTime\":\"09:00\"}"), 400);
        problem(call(HttpMethod.POST, path, clinic.as("FRONT_DESK"),
                "{\"intervalKey\":\"D7\",\"apptDate\":\"" + tomorrow() + "\",\"apptTime\":\"09:00\",\"visitType\":\"OTHER\"}"), 400);
        assertThat(slugOf(problem(call(HttpMethod.POST, path, clinic.as("FRONT_DESK"),
                visitFollowUp("D7", tomorrow(), "09:07")), 400))).isEqualTo("slot-not-on-grid");
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where visit_type = 'FOLLOW_UP'")).isZero();
        for (String role : List.of("NURSE", "DOCTOR", "BILLING")) {
            problem(call(HttpMethod.POST, path, clinic.as(role), visitFollowUp("D7", tomorrow(), "09:00")), 403);
            problem(call(HttpMethod.POST, VISITS + "/" + done + "/follow-up/not-needed", clinic.as(role), null), 403);
        }
        Clinic other = newClinic();
        problem(call(HttpMethod.POST, path, other.owner(), visitFollowUp("D7", tomorrow(), "09:00")), 404);
        problem(call(HttpMethod.POST, VISITS + "/" + done + "/follow-up/not-needed", other.owner(), null), 404);
        problem(call(HttpMethod.POST, OPD + "/follow-ups/" + UUID.randomUUID() + "/appointment", clinic.owner(),
                "{\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\",\"apptDate\":\"" + tomorrow()
                        + "\",\"apptTime\":\"09:00\"}"), 404);
    }

    @Test
    void noFollowUpNeededIsStoredOnceIsIdempotentAndClosesTheRecordForBooking() throws Exception {
        Clinic clinic = newClinic();
        String visit = completedWalkIn(clinic, newPatient(clinic));
        String path = VISITS + "/" + visit + "/follow-up/not-needed";
        JsonNode first = ok(HttpMethod.POST, path, clinic.as("FRONT_DESK"), null, 200);
        assertThat(first.get("state").asText()).isEqualTo("NOT_NEEDED");
        ok(HttpMethod.POST, path, clinic.as("FRONT_DESK"), null, 200); // same answer, writes nothing more
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid "
                + "and not_needed_at is not null and not_needed_by is not null", visit)).isEqualTo(1);
        assertThat(auditRowsNaming(clinic.tenantId(), "FOLLOW_UP_NOT_NEEDED")).isEqualTo(1);
        assertThat(slugOf(problem(call(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/appointment", clinic.as("FRONT_DESK"),
                visitFollowUp("D7", tomorrow(), "09:00")), 409))).isEqualTo("follow-up-closed");
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid", visit)).isEqualTo(1);
    }

    @Test
    void aNotNeededFollowUpLeavesTheFollowUpListsTheCountsTheTodaySummaryAndTheDeskList() throws Exception {
        Clinic clinic = newClinic();
        String visit = completedWalkIn(clinic, newPatient(clinic));
        BackdateSupport.setFollowUpDueDate(db(), clinic.tenantId(), visit, istToday());

        JsonNode due = getJson(OPD + "/follow-ups?status=DUE", clinic.owner());
        assertThat(due.get("counts").get("due").asInt()).isEqualTo(1);
        assertThat(due.get("items")).hasSize(1);
        assertThat(getJson(OPD + "/today-summary", clinic.owner()).get("followUpsDue").asInt()).isEqualTo(1);
        JsonNode snap = snapshot(clinic, clinic.owner());
        assertThat(snap.get("followUpsToBook").get("total").asInt()).isEqualTo(1);
        assertThat(snap.get("followUpsToBook").get("items").get(0).get("status").asText()).isEqualTo("DUE");

        ok(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/not-needed", clinic.as("FRONT_DESK"), null, 200);

        JsonNode after = getJson(OPD + "/follow-ups?status=DUE", clinic.owner());
        assertThat(after.get("counts").get("due").asInt()).isZero();
        assertThat(after.get("items")).isEmpty();
        for (String status : List.of("MISSED", "UPCOMING", "RETURNED")) {
            assertThat(getJson(OPD + "/follow-ups?status=" + status, clinic.owner()).get("items")).as(status).isEmpty();
        }
        JsonNode summary = getJson(OPD + "/today-summary", clinic.owner());
        assertThat(summary.get("followUpsDue").asInt()).isZero();
        assertThat(summary.get("followUpsMissed").asInt()).isZero();
        JsonNode snapAfter = snapshot(clinic, clinic.owner());
        assertThat(snapAfter.get("followUpsToBook").get("total").asInt()).isZero();
        JsonNode card = cardOfVisit(snapAfter, visit);
        assertThat(card).isNotNull();
        assertThat(card.get("kind").asText()).isEqualTo("WALK_IN");
        assertThat(card.get("column").asText()).isEqualTo("DONE");
        assertThat(card.get("followUp").get("state").asText()).isEqualTo("NOT_NEEDED");
        assertThat(card.get("primaryAction").asText()).isEqualTo("NONE");
    }

    @Test
    void findSlotBooksAFollowUpFromTheListWithoutChangingItsDueDateAndTheListDropsIt() throws Exception {
        Clinic clinic = newClinic();
        String visit = completedWalkIn(clinic, newPatient(clinic));
        BackdateSupport.setFollowUpDueDate(db(), clinic.tenantId(), visit, istToday().minusDays(3));
        JsonNode item = getJson(OPD + "/follow-ups?status=MISSED", clinic.owner()).get("items").get(0);
        String followUpId = item.get("id").asText();
        JsonNode toBook = snapshot(clinic, clinic.owner()).get("followUpsToBook");
        assertThat(toBook.get("total").asInt()).isEqualTo(1);
        assertThat(toBook.get("items").get(0).get("followUpId").asText()).isEqualTo(followUpId);
        assertThat(toBook.get("items").get(0).get("status").asText()).isEqualTo("MISSED");
        assertThat(toBook.get("items").get(0).get("daysLate").asInt()).isEqualTo(3);

        String body = "{\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\",\"apptDate\":\"" + tomorrow()
                + "\",\"apptTime\":\"09:00\",\"visitType\":\"NEW\"}";
        JsonNode booked = ok(HttpMethod.POST, OPD + "/follow-ups/" + followUpId + "/appointment", clinic.as("FRONT_DESK"), body, 201);
        assertThat(booked.get("state").asText()).isEqualTo("BOOKED");
        assertThat(booked.get("dueDate").asText()).isEqualTo(istToday().minusDays(3).toString());
        assertThat(booked.get("appointment").get("visitType").asText()).isEqualTo("NEW");
        assertThat(snapshot(clinic, clinic.owner()).get("followUpsToBook").get("total").asInt()).isZero();
        assertThat(slugOf(problem(call(HttpMethod.POST, OPD + "/follow-ups/" + followUpId + "/appointment",
                clinic.as("FRONT_DESK"), body.replace("09:00", "09:15")), 409))).isEqualTo("follow-up-already-booked");
        problem(call(HttpMethod.POST, OPD + "/follow-ups/" + followUpId + "/appointment", clinic.as("NURSE"), body), 403);
        problem(call(HttpMethod.POST, OPD + "/follow-ups/" + followUpId + "/appointment", newClinic().owner(), body), 404);
    }

    @Test
    void aFollowUpBookingRacingNoFollowUpNeededEndsInOneOutcome() throws Exception {
        Clinic clinic = newClinic();
        for (int round = 0; round < 4; round++) {
            String visit = completedWalkIn(clinic, newPatient(clinic));
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/appointment", clinic.as("FRONT_DESK"),
                            visitFollowUp("D7", tomorrow(), String.format("09:%02d", round * 15))),
                    request(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/not-needed", clinic.as("OWNER"), null)));
            boolean bookWon = statuses.get(0) == 201;
            boolean markWon = statuses.get(1) == 200;
            assertThat(bookWon ^ markWon).as("exactly one winner in round " + round + ": " + statuses).isTrue();
            long booked = countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid "
                    + "and appointment_id is not null", visit);
            long marked = countOf(clinic.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid "
                    + "and not_needed_at is not null", visit);
            assertThat(booked + marked).as("one stored result").isEqualTo(1);
        }
    }

    @Test
    void twoBookingsOfTheSameFollowUpGiveOneAppointment() throws Exception {
        Clinic clinic = newClinic();
        String visit = completedWalkIn(clinic, newPatient(clinic));
        String path = VISITS + "/" + visit + "/follow-up/appointment";
        List<Integer> statuses = race(List.of(
                request(HttpMethod.POST, path, clinic.as("FRONT_DESK"), visitFollowUp("D7", tomorrow(), "09:00")),
                request(HttpMethod.POST, path, clinic.as("OWNER"), visitFollowUp("D7", tomorrow(), "09:15"))));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where visit_type = 'FOLLOW_UP'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the whole flow on the server

    @Test
    void theWholeFlowBookCheckInStartCompleteFollowUpShowsOnTheBoardAtEachStep() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID patient = newPatient(clinic);
        String appt = bookForToday(clinic, patient, doctor, "09:00").get("id").asText();

        JsonNode s1 = snapshot(clinic, clinic.owner());
        assertThat(card(s1, appt).get("column").asText()).isEqualTo("BOOKED");
        assertThat(card(s1, appt).get("primaryAction").asText()).isIn("CHECK_IN", "REBOOK");
        int bookedBefore = s1.get("counters").get("booked").asInt();

        JsonNode checked = checkIn(clinic, appt);
        String visit = checked.get("visitId").asText();
        JsonNode s2 = snapshot(clinic, clinic.owner());
        assertThat(card(s2, appt).get("column").asText()).isEqualTo("WAITING");
        assertThat(card(s2, appt).get("tokenNo").asInt()).isEqualTo(checked.get("tokenNo").asInt());
        assertThat(card(s2, appt).get("primaryAction").asText()).isEqualTo("START_CONSULTATION");
        assertThat(s2.get("counters").get("waiting").asInt()).isEqualTo(1);
        assertThat(s2.get("counters").get("booked").asInt()).isLessThanOrEqualTo(bookedBefore);

        moveStage(clinic, visit, "WITH_DOCTOR", "VITALS", 200);
        JsonNode s3 = snapshot(clinic, clinic.owner());
        assertThat(card(s3, appt).get("column").asText()).isEqualTo("IN_CONSULTATION");
        assertThat(card(s3, appt).get("primaryAction").asText()).isEqualTo("COMPLETE_VISIT");
        assertThat(s3.get("counters").get("inConsult").asInt()).isEqualTo(1);

        moveStage(clinic, visit, "BILLING", "WITH_DOCTOR", 200);
        JsonNode s4 = snapshot(clinic, clinic.owner());
        assertThat(card(s4, appt).get("column").asText()).isEqualTo("DONE");
        assertThat(card(s4, appt).get("primaryAction").asText()).isEqualTo("SET_FOLLOW_UP");
        assertThat(s4.get("counters").get("done").asInt()).isEqualTo(1);

        ok(HttpMethod.POST, VISITS + "/" + visit + "/follow-up/appointment", clinic.as("FRONT_DESK"),
                visitFollowUp("D7", tomorrow(), "09:30"), 201);
        JsonNode s5 = snapshot(clinic, clinic.owner());
        JsonNode followUp = card(s5, appt).get("followUp");
        assertThat(followUp.get("state").asText()).isEqualTo("BOOKED");
        assertThat(followUp.get("apptDate").asText()).isEqualTo(tomorrow().toString());
        assertThat(followUp.get("apptTime").asText()).isEqualTo("09:30");
        assertThat(card(s5, appt).get("primaryAction").asText()).isEqualTo("NONE");
        assertThat(times(daySlots(clinic.owner(), doctor, tomorrow()))).doesNotContain("09:30");

        assertThat(auditRowsNaming(clinic.tenantId(), PATIENT_NAME)).isZero();
        assertThat(auditRowsNaming(clinic.tenantId(), "Kulkarni")).isZero();
    }
}
