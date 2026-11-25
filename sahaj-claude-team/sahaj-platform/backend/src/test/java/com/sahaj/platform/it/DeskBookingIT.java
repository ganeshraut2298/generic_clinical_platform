package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-91 (booking checks, cut-off, reschedule) against the real sahaj_test database. Bookings are for tomorrow or later so
 * the result never depends on the time of day. Test plan: docs/test-plans/SAH-90.md (T91-*).
 */
@OnTestDatabase
class DeskBookingIT extends DeskSupport {

    private static final String SLUG_BASE = "https://sahaj.in/problems/";

    // ---------------------------------------------------------------- the checks of ADR-032 D4

    @Test
    void bookingRefusalsCarryTheirOwnProblemTypeAndStatus() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID patient = newPatient(clinic);
        setSlots(clinic, doctor, "09:00", "10:00", 15, 0, null);

        // past: yesterday on the grid
        JsonNode past = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, istToday().minusDays(1), "09:00")), 400);
        assertThat(past.get("type").asText()).isEqualTo(SLUG_BASE + "slot-in-past");

        // off the grid
        JsonNode offGrid = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, tomorrow(), "09:07")), 400);
        assertThat(slugOf(offGrid)).isEqualTo("slot-not-on-grid");

        // outside the session is also off the grid
        JsonNode outside = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, tomorrow(), "11:00")), 400);
        assertThat(slugOf(outside)).isEqualTo("slot-not-on-grid");

        // malformed time: plain 400 problem body
        problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, tomorrow(), "nine")), 400);

        // a valid slot books (201)
        book(clinic, patient, doctor, tomorrow(), "09:15");
    }

    @Test
    void aDoctorWhoDoesNotWorkThatWeekdayIsRefusedWithDoctorNotWorking() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID patient = newPatient(clinic);
        LocalDate day = tomorrow();
        int mockupDay = day.getDayOfWeek().getValue() % 7; // Sunday 0 .. Saturday 6
        StringBuilder others = new StringBuilder("[");
        for (int d = 0; d < 7; d++) {
            if (d != mockupDay) {
                others.append(others.length() > 1 ? "," : "").append(d);
            }
        }
        setSlots(clinic, doctor, others.append("]").toString(), "09:00", "10:00", 15, 0, null);
        JsonNode body = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, day, "09:00")), 400);
        assertThat(slugOf(body)).isEqualTo("doctor-not-working");
        // the day-slots read agrees with the write: working false, no slots
        JsonNode slots = daySlots(clinic.owner(), doctor, day);
        assertThat(slots.get("working").asBoolean()).isFalse();
        assertThat(slots.get("freeCount").asInt()).isZero();
    }

    @Test
    void aWalkInReservedSlotIsNotOfferedAndNotBookable() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID patient = newPatient(clinic);
        setSlots(clinic, doctor, "09:00", "10:00", 15, 2, null); // every 2nd slot kept for walk-ins
        List<String> offered = times(daySlots(clinic.owner(), doctor, tomorrow()));
        List<String> grid = List.of("09:00", "09:15", "09:30", "09:45");
        List<String> reserved = grid.stream().filter(t -> !offered.contains(t)).toList();
        assertThat(reserved).as("some slots are reserved for walk-ins").isNotEmpty();
        assertThat(offered).isNotEmpty();
        JsonNode body = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(patient, doctor, tomorrow(), reserved.get(0))), 400);
        assertThat(slugOf(body)).isEqualTo("slot-walk-in-only");
        book(clinic, patient, doctor, tomorrow(), offered.get(0));
    }

    @Test
    void aNurseCannotBookAndGetsAProblem403WhileTheOwnerAndFrontDeskCan() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        UUID doctor = clinic.doctorMembershipId();
        for (String role : List.of("NURSE", "DOCTOR", "BILLING")) {
            problem(call(HttpMethod.POST, APPTS, clinic.as(role), bookBody(patient, doctor, tomorrow(), "09:00")), 403);
        }
        assertThat(bookStatus(clinic, "OWNER", patient, doctor, tomorrow(), "09:00")).isEqualTo(201);
        assertThat(bookStatus(clinic, "FRONT_DESK", newPatient(clinic), doctor, tomorrow(), "09:15")).isEqualTo(201);
        // nothing was written by the refused callers
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment")).isEqualTo(2);
    }

    @Test
    void theSameSlotTwiceGivesSlotTaken409ThePatientTwiceGivesDuplicate409AndOverbookAllowsStaff() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID a = newPatient(clinic);
        UUID b = newPatient(clinic);
        setRules(clinic, 0, false, 15);
        JsonNode first = book(clinic, a, doctor, tomorrow(), "09:00");
        JsonNode taken = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(b, doctor, tomorrow(), "09:00")), 409);
        assertThat(slugOf(taken)).isEqualTo("appointment-slot-taken");
        JsonNode duplicate = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                bookBody(a, doctor, tomorrow(), "09:00")), 409);
        assertThat(slugOf(duplicate)).isEqualTo("appointment-duplicate");
        assertThat(duplicate.get("appointmentId").asText()).isEqualTo(first.get("id").asText());
        setRules(clinic, 0, true, 15);
        book(clinic, b, doctor, tomorrow(), "09:00");
    }

    // ---------------------------------------------------------------- cut-off: doctor against clinic, staff exempt

    @Test
    void theDoctorsOwnCutOffBeatsTheClinicsAndStaffAreNeverRefusedButSeeTheNote() throws Exception {
        Clinic clinic = newClinic();
        UUID strict = clinic.doctorMembershipId();
        UUID relaxed = addDoctor(clinic);
        UUID patient = newPatient(clinic);
        setRules(clinic, 0, false, 15); // clinic: no cut-off
        setSlots(clinic, strict, "09:00", "10:00", 15, 0, 48); // the doctor: 48 h, so tomorrow is inside it
        setSlots(clinic, relaxed, "09:00", "10:00", 15, 0, null); // clinic value (0)

        JsonNode staffStrict = daySlots(clinic.as("FRONT_DESK"), strict, tomorrow());
        assertThat(staffStrict.get("closeBeforeHours").asInt()).isEqualTo(48);
        assertThat(times(staffStrict)).as("staff still see the slots").hasSize(4);
        for (JsonNode slot : staffStrict.get("groups").get(0).get("slots")) {
            assertThat(slot.get("insideCutOff").asBoolean()).isTrue();
        }
        // a read-only role does not get the slots inside the cut-off
        assertThat(times(daySlots(clinic.as("NURSE"), strict, tomorrow()))).isEmpty();
        assertThat(daySlots(clinic.as("NURSE"), strict, tomorrow()).get("freeCount").asInt()).isZero();

        // the doctor without an own value uses the clinic's 0 h: nothing is inside the cut-off
        JsonNode nurseRelaxed = daySlots(clinic.as("NURSE"), relaxed, tomorrow());
        assertThat(nurseRelaxed.get("closeBeforeHours").asInt()).isZero();
        assertThat(times(nurseRelaxed)).hasSize(4);

        // the staff exemption (SAH-11 rule kept): the booking inside the cut-off is accepted
        assertThat(bookStatus(clinic, "FRONT_DESK", patient, strict, tomorrow(), "09:00")).isEqualTo(201);
        assertThat(bookStatus(clinic, "OWNER", newPatient(clinic), strict, tomorrow(), "09:15")).isEqualTo(201);
    }

    @Test
    void closeBeforeHoursOutOfRangeIsRefusedWithAProblem400() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        for (String bad : List.of("49", "-1", "1.5", "\"x\"")) {
            problem(call(HttpMethod.PUT, SLOTS + "/" + doctor, clinic.owner(),
                    "{\"slotMinutes\":15,\"workingDays\":[1],\"walkInEveryNth\":0,\"maxOnlinePerDay\":0,"
                            + "\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"10:00\"}],\"closeBeforeHours\":" + bad + "}"), 400);
        }
        // 0 and 48 are the two boundaries and are accepted
        setSlots(clinic, doctor, "09:00", "10:00", 15, 0, 0);
        setSlots(clinic, doctor, "09:00", "10:00", 15, 0, 48);
    }

    // ---------------------------------------------------------------- the day-slots read

    @Test
    void daySlotsHidesTakenSlotsAndRefusesDatesOutsideTheWindow() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setSlots(clinic, doctor, "09:00", "10:00", 15, 0, null);
        book(clinic, newPatient(clinic), doctor, tomorrow(), "09:30");
        List<String> free = times(daySlots(clinic.owner(), doctor, tomorrow()));
        assertThat(free).containsExactly("09:00", "09:15", "09:45");
        assertThat(daySlots(clinic.owner(), doctor, tomorrow()).get("groups").get(0).get("label").asText())
                .isEqualTo("MORNING");

        problem(call(HttpMethod.GET, DESK + "/doctors/" + doctor + "/day-slots?date=" + istToday().minusDays(1),
                clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, DESK + "/doctors/" + doctor + "/day-slots?date=" + istToday().plusDays(121),
                clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, DESK + "/doctors/" + doctor + "/day-slots?date=not-a-date", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, DESK + "/doctors/" + doctor + "/day-slots", clinic.owner(), null), 400);
        // boundary: 120 days ahead is allowed
        daySlots(clinic.owner(), doctor, istToday().plusDays(120));
    }

    @Test
    void anEveningSessionIsGroupedUnderEvening() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setSlots(clinic, doctor, "17:00", "18:00", 30, 0, null);
        JsonNode slots = daySlots(clinic.owner(), doctor, tomorrow());
        assertThat(slots.get("groups")).hasSize(1);
        assertThat(slots.get("groups").get(0).get("label").asText()).isEqualTo("EVENING");
        assertThat(times(slots)).containsExactly("17:00", "17:30");
    }

    // ---------------------------------------------------------------- reschedule

    @Test
    void rescheduleMovesTheSameAppointmentOnceAndBumpsTheVersion() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID other = addDoctor(clinic);
        UUID patient = newPatient(clinic);
        JsonNode created = book(clinic, patient, doctor, tomorrow(), "09:00");
        String id = created.get("id").asText();
        long version = created.get("version").asLong();

        JsonNode moved = ok(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.as("FRONT_DESK"),
                "{\"version\":" + version + ",\"doctorMembershipId\":\"" + other + "\",\"apptDate\":\"" + tomorrow()
                        + "\",\"apptTime\":\"09:30\"}", 200);
        assertThat(moved.get("id").asText()).isEqualTo(id);
        assertThat(moved.get("doctorMembershipId").asText()).isEqualTo(other.toString());
        assertThat(moved.get("apptTime").asText()).isEqualTo("09:30");
        assertThat(moved.get("status").asText()).isEqualTo("BOOKED");
        assertThat(moved.get("version").asLong()).isGreaterThan(version);
        // exactly one active appointment for the patient, the old slot is free again
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where patient_id = ?::uuid "
                + "and status <> 'CANCELLED'", patient.toString())).isEqualTo(1);
        assertThat(times(daySlots(clinic.owner(), doctor, tomorrow()))).contains("09:00");
        assertThat(times(daySlots(clinic.owner(), other, tomorrow()))).doesNotContain("09:30");
        // stale version: 409 appointment-changed, nothing written
        JsonNode stale = problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.as("FRONT_DESK"),
                "{\"version\":" + version + ",\"doctorMembershipId\":\"" + other + "\",\"apptDate\":\"" + tomorrow()
                        + "\",\"apptTime\":\"09:45\"}"), 409);
        assertThat(slugOf(stale)).isEqualTo("appointment-changed");
        // same placement again answers the stored row, writes nothing (no version change)
        JsonNode same = ok(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.as("FRONT_DESK"),
                "{\"version\":" + moved.get("version").asLong() + ",\"doctorMembershipId\":\"" + other
                        + "\",\"apptDate\":\"" + tomorrow() + "\",\"apptTime\":\"09:30\"}", 200);
        assertThat(same.get("version").asLong()).isEqualTo(moved.get("version").asLong());
    }

    @Test
    void rescheduleChecksTheNewSlotRolesAndStatus() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID a = newPatient(clinic);
        UUID b = newPatient(clinic);
        setRules(clinic, 0, false, 15);
        JsonNode first = book(clinic, a, doctor, tomorrow(), "09:00");
        book(clinic, b, doctor, tomorrow(), "09:15");
        String id = first.get("id").asText();
        long v = first.get("version").asLong();

        String intoTaken = "{\"version\":" + v + ",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\""
                + tomorrow() + "\",\"apptTime\":\"09:15\"}";
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(), intoTaken), 409)))
                .isEqualTo("appointment-slot-taken");
        String offGrid = intoTaken.replace("09:15", "09:20");
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(), offGrid), 400)))
                .isEqualTo("slot-not-on-grid");
        String inPast = "{\"version\":" + v + ",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\""
                + istToday().minusDays(1) + "\",\"apptTime\":\"09:00\"}";
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(), inPast), 400)))
                .isEqualTo("slot-in-past");
        // validation: missing version
        problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(),
                "{\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\"" + tomorrow() + "\",\"apptTime\":\"09:30\"}"), 400);
        // role
        problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.as("NURSE"),
                intoTaken.replace("09:15", "09:30")), 403);
        // unknown appointment
        problem(call(HttpMethod.POST, APPTS + "/" + UUID.randomUUID() + "/reschedule", clinic.owner(),
                intoTaken.replace("09:15", "09:30")), 404);
        // a cancelled appointment cannot be moved
        JsonNode cancelled = ok(HttpMethod.PATCH, APPTS + "/" + id, clinic.owner(), "{\"status\":\"CANCELLED\"}", 200);
        String fresh = intoTaken.replace("\"version\":" + v, "\"version\":" + cancelled.get("version").asLong()).replace("09:15", "09:30");
        assertThat(slugOf(problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(), fresh), 409)))
                .isEqualTo("appointment-not-reschedulable");
    }

    @Test
    void aNoShowAppointmentCanBeRebookedIntoANewSlotAndBecomesBookedAgain() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        JsonNode created = book(clinic, newPatient(clinic), doctor, tomorrow(), "09:00");
        String id = created.get("id").asText();
        JsonNode noShow = ok(HttpMethod.PATCH, APPTS + "/" + id, clinic.owner(), "{\"status\":\"NOSHOW\"}", 200);
        JsonNode back = ok(HttpMethod.POST, APPTS + "/" + id + "/reschedule", clinic.owner(),
                "{\"version\":" + noShow.get("version").asLong() + ",\"doctorMembershipId\":\"" + doctor
                        + "\",\"apptDate\":\"" + tomorrow() + "\",\"apptTime\":\"09:30\"}", 200);
        assertThat(back.get("status").asText()).isEqualTo("BOOKED");
        assertThat(back.get("id").asText()).isEqualTo(id);
    }

    @Test
    void twoReschedulesOfDifferentAppointmentsIntoOneSlotGiveOneSuccessAndOneConflict() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setRules(clinic, 0, false, 15);
        for (int round = 0; round < 3; round++) {
            JsonNode a = book(clinic, newPatient(clinic), doctor, tomorrow(), "09:" + (round == 0 ? "00" : round == 1 ? "15" : "30"));
            JsonNode b = book(clinic, newPatient(clinic), doctor, tomorrow().plusDays(1),
                    "09:" + (round == 0 ? "00" : round == 1 ? "15" : "30"));
            String target = "10:" + (round == 0 ? "00" : round == 1 ? "15" : "30");
            String bodyA = "{\"version\":" + a.get("version").asLong() + ",\"doctorMembershipId\":\"" + doctor
                    + "\",\"apptDate\":\"" + tomorrow().plusDays(2) + "\",\"apptTime\":\"" + target + "\"}";
            String bodyB = bodyA.replace("\"version\":" + a.get("version").asLong(), "\"version\":" + b.get("version").asLong());
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, APPTS + "/" + a.get("id").asText() + "/reschedule", clinic.owner(), bodyA),
                    request(HttpMethod.POST, APPTS + "/" + b.get("id").asText() + "/reschedule", clinic.owner(), bodyB)));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where doctor_membership_id = ?::uuid "
                    + "and appt_date = ?::date and appt_time = ?::time and status = 'BOOKED'", doctor.toString(),
                    tomorrow().plusDays(2).toString(), target)).isEqualTo(1);
        }
    }

    @Test
    void twoReschedulesOfTheSameAppointmentWithTheSameVersionGiveOneSuccessAndOneAppointmentChanged() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        JsonNode a = book(clinic, newPatient(clinic), doctor, tomorrow(), "09:00");
        String path = APPTS + "/" + a.get("id").asText() + "/reschedule";
        long v = a.get("version").asLong();
        String one = "{\"version\":" + v + ",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\"" + tomorrow()
                + "\",\"apptTime\":\"10:00\"}";
        String two = one.replace("10:00", "10:15");
        List<Integer> statuses = race(List.of(request(HttpMethod.POST, path, clinic.owner(), one),
                request(HttpMethod.POST, path, clinic.owner(), two)));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment where status <> 'CANCELLED'")).isEqualTo(1);
    }

    @Test
    void twoParallelBookingsOfOneSlotGiveOneCreatedAndOneConflictInEveryRound() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setRules(clinic, 0, false, 15);
        for (int round = 0; round < 6; round++) {
            String time = String.format("%02d:%02d", 9 + round / 4, (round % 4) * 15);
            UUID p1 = newPatient(clinic);
            UUID p2 = newPatient(clinic);
            List<Integer> statuses = race(List.of(
                    request(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"), bookBody(p1, doctor, tomorrow(), time)),
                    request(HttpMethod.POST, APPTS, clinic.as("OWNER"), bookBody(p2, doctor, tomorrow(), time))));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(countOf(clinic.tenantId(), "select count(*) from opd.appointment")).isEqualTo(6);
    }

    // ---------------------------------------------------------------- tenant isolation of the write side

    @Test
    void anotherClinicCannotBookRescheduleOrCheckInThisClinicsAppointment() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode created = book(a, newPatient(a), a.doctorMembershipId(), tomorrow(), "09:00");
        String id = created.get("id").asText();
        // B cannot book with A's doctor or A's patient
        UUID bPatient = newPatient(b);
        problem(call(HttpMethod.POST, APPTS, b.owner(), bookBody(bPatient, a.doctorMembershipId(), tomorrow(), "09:30")), 404);
        problem(call(HttpMethod.POST, APPTS, b.owner(), bookBody(newPatient(a), b.doctorMembershipId(), tomorrow(), "09:30")), 404);
        problem(call(HttpMethod.POST, APPTS + "/" + id + "/reschedule", b.owner(),
                "{\"version\":" + created.get("version").asLong() + ",\"doctorMembershipId\":\"" + b.doctorMembershipId()
                        + "\",\"apptDate\":\"" + tomorrow() + "\",\"apptTime\":\"09:30\"}"), 404);
        problem(call(HttpMethod.POST, APPTS + "/" + id + "/token", b.owner(), null), 404);
        problem(call(HttpMethod.PATCH, APPTS + "/" + id, b.owner(), "{\"status\":\"CANCELLED\"}"), 404);
        // A's appointment is untouched
        assertThat(scalar(a.tenantId(), "select status from opd.appointment where id = ?::uuid", id)).isEqualTo("BOOKED");
    }
}
