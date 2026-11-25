package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-90..93 front desk console integration tests (real SQL, real row level security on
 * {@code sahaj_test}). Bookings are made for tomorrow or later, so they never depend on the time of day the suite runs;
 * a check-in needs an appointment dated today, so {@link #moveToToday} moves a booked appointment to today with committed
 * SQL (the application clock cannot move). Patient names in these tests are fixed test strings, never real people.
 */
abstract class DeskSupport extends SweepSupport {

    static final String OPD = "/api/v1/opd";
    static final String APPTS = OPD + "/appointments";
    static final String VISITS = OPD + "/visits";
    static final String DESK = OPD + "/desk";
    static final String SLOTS = OPD + "/doctor-slots";
    static final String RULES = OPD + "/booking-rules";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static LocalDate istToday() {
        return LocalDate.now(IST);
    }

    static LocalDate tomorrow() {
        return istToday().plusDays(1);
    }

    // ------------------------------------------------------------------ set-up

    /** A doctor beside the clinic's own one, through the real Team endpoint. */
    protected UUID addDoctor(Clinic clinic) throws Exception {
        return addMember(clinic, "DOCTOR").membershipId();
    }

    /**
     * Doctors added straight into the tables with committed SQL as sahaj_app (tenant bound). The free plan allows 5 users, so
     * the Team endpoint cannot make 12 or 50 doctors; the rows are the ones the Team endpoint writes.
     */
    protected List<UUID> addDoctorsDirect(Clinic clinic, int count) throws Exception {
        String[] specialties = {"Cardiology", "Orthopaedics", "Paediatrics", "General physician"};
        List<UUID> ids = new ArrayList<>();
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            bind(c, clinic.tenantId());
            for (int i = 0; i < count; i++) {
                UUID user = UUID.randomUUID();
                UUID membership = UUID.randomUUID();
                try (PreparedStatement u = c.prepareStatement(
                        "insert into platform.app_user (id, mobile, full_name) values (?, ?, ?)")) {
                    u.setObject(1, user);
                    u.setString(2, uniqueMobile());
                    u.setString(3, "Dr Test " + i);
                    u.executeUpdate();
                }
                try (PreparedStatement m = c.prepareStatement("insert into platform.membership "
                        + "(id, user_id, role, status, display_name, created_by, updated_by) values (?, ?, 'DOCTOR', 'ACTIVE', ?, ?, ?)")) {
                    m.setObject(1, membership);
                    m.setObject(2, user);
                    m.setString(3, "Dr Test " + i + " " + (char) ('A' + i % 26));
                    m.setObject(4, clinic.ownerUserId());
                    m.setObject(5, clinic.ownerUserId());
                    m.executeUpdate();
                }
                try (PreparedStatement d = c.prepareStatement("insert into platform.doctor_profile "
                        + "(membership_id, tenant_id, specialty, fee_paise, degree, registration_no) values (?, ?, ?, 30000, 'MBBS', 'R1')")) {
                    d.setObject(1, membership);
                    d.setObject(2, clinic.tenantId());
                    d.setString(3, specialties[i % specialties.length]);
                    d.executeUpdate();
                }
                ids.add(membership);
            }
            c.commit();
        }
        return ids;
    }

    /**
     * The doctor's slot plan: every day, one session, 15-minute slots, no walk-in reserve. A null {@code closeBefore}
     * clears the doctor's own cut-off (the clinic value applies).
     */
    protected void setSlots(Clinic clinic, UUID doctor, String start, String end, int slotMinutes, int walkInEveryNth,
                            Integer closeBefore) throws Exception {
        setSlots(clinic, doctor, "[0,1,2,3,4,5,6]", start, end, slotMinutes, walkInEveryNth, closeBefore);
    }

    protected void setSlots(Clinic clinic, UUID doctor, String workingDaysJson, String start, String end, int slotMinutes,
                            int walkInEveryNth, Integer closeBefore) throws Exception {
        ok(HttpMethod.PUT, SLOTS + "/" + doctor, clinic.owner(), "{\"slotMinutes\":" + slotMinutes + ",\"workingDays\":"
                + workingDaysJson + ",\"walkInEveryNth\":" + walkInEveryNth + ",\"maxOnlinePerDay\":0,"
                + "\"sessions\":[{\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}],"
                + "\"closeBeforeHours\":" + closeBefore + "}", 200);
    }

    protected void setRules(Clinic clinic, int closeBeforeHours, boolean overbook, int noShowAfterMinutes)
            throws Exception {
        ok(HttpMethod.PUT, RULES, clinic.owner(), "{\"closeBeforeHours\":" + closeBeforeHours
                + ",\"remindBeforeHours\":4,\"noShowAfterMinutes\":" + noShowAfterMinutes
                + ",\"followPriority\":false,\"selfBook\":true,\"overbook\":" + overbook + "}", 200);
    }

    // ------------------------------------------------------------------ calls

    protected static String bookBody(UUID patient, UUID doctor, LocalDate date, String time) {
        return "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\"" + date
                + "\",\"apptTime\":\"" + time + "\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\"}";
    }

    protected JsonNode book(Clinic clinic, UUID patient, UUID doctor, LocalDate date, String time) throws Exception {
        return ok(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"), bookBody(patient, doctor, date, time), 201);
    }

    protected int bookStatus(Clinic clinic, String role, UUID patient, UUID doctor, LocalDate date, String time)
            throws Exception {
        return call(HttpMethod.POST, APPTS, clinic.as(role), bookBody(patient, doctor, date, time)).andReturn()
                .getResponse().getStatus();
    }

    protected JsonNode snapshot(Clinic clinic, RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, DESK + "/snapshot", auth, null, 200);
    }

    protected JsonNode daySlots(RequestPostProcessor auth, UUID doctor, LocalDate date) throws Exception {
        return ok(HttpMethod.GET, DESK + "/doctors/" + doctor + "/day-slots?date=" + date, auth, null, 200);
    }

    protected static List<String> times(JsonNode daySlots) {
        List<String> out = new ArrayList<>();
        for (JsonNode group : daySlots.get("groups")) {
            for (JsonNode slot : group.get("slots")) {
                out.add(slot.get("time").asText());
            }
        }
        return out;
    }

    /** The slug at the end of the problem type, for example {@code slot-in-past}. */
    protected String slugOf(JsonNode problem) {
        String type = problem.get("type").asText();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    protected JsonNode checkIn(Clinic clinic, String appointmentId) throws Exception {
        return ok(HttpMethod.POST, APPTS + "/" + appointmentId + "/token", clinic.as("FRONT_DESK"), null, 200);
    }

    protected JsonNode moveStage(Clinic clinic, String visitId, String stage, String expected, int status)
            throws Exception {
        String body = "{\"stage\":\"" + stage + "\"" + (expected == null ? "" : ",\"expectedStage\":\"" + expected + "\"")
                + "}";
        return ok(HttpMethod.PATCH, VISITS + "/" + visitId + "/stage", clinic.as("FRONT_DESK"), body, status);
    }

    /** The board card of an appointment or visit in a snapshot, or null. */
    protected JsonNode card(JsonNode snapshot, String appointmentId) {
        for (JsonNode card : snapshot.get("board").get("cards")) {
            if (card.hasNonNull("appointmentId") && card.get("appointmentId").asText().equals(appointmentId)) {
                return card;
            }
        }
        return null;
    }

    protected JsonNode cardOfVisit(JsonNode snapshot, String visitId) {
        for (JsonNode card : snapshot.get("board").get("cards")) {
            if (card.hasNonNull("visitId") && card.get("visitId").asText().equals(visitId)) {
                return card;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ races

    /** Runs the calls at the same moment and returns their HTTP statuses in the order given. */
    protected List<Integer> race(List<Callable<Integer>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    protected Callable<Integer> request(HttpMethod method, String path, RequestPostProcessor auth, String body) {
        return () -> {
            MvcResult result = call(method, path, auth, body).andReturn();
            return result.getResponse().getStatus();
        };
    }

    // ------------------------------------------------------------------ committed SQL (sahaj_app, tenant bound)

    /** One committed statement as sahaj_app with the tenant bound; returns the rows changed. */
    protected int exec(UUID tenantId, String sql, Object... params) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            bind(c, tenantId);
            try (PreparedStatement u = c.prepareStatement(sql)) {
                set(u, params);
                int n = u.executeUpdate();
                c.commit();
                return n;
            }
        }
    }

    /** First column of the first row, as text; null when no row. */
    protected String scalar(UUID tenantId, String sql, Object... params) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            bind(c, tenantId);
            try (PreparedStatement q = c.prepareStatement(sql)) {
                set(q, params);
                try (ResultSet rs = q.executeQuery()) {
                    String value = rs.next() ? rs.getString(1) : null;
                    c.rollback();
                    return value;
                }
            }
        }
    }

    protected long countOf(UUID tenantId, String sql, Object... params) throws SQLException {
        return Long.parseLong(scalar(tenantId, sql, params));
    }

    private static void bind(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            bind.setString(1, tenantId.toString());
            bind.execute();
        }
    }

    private static void set(PreparedStatement s, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            s.setObject(i + 1, params[i]);
        }
    }

    /** Moves a booked appointment to today with committed SQL so that it can be checked in (same slot time). */
    protected void moveToToday(Clinic clinic, String appointmentId) throws Exception {
        assertThat(exec(clinic.tenantId(), "update opd.appointment set appt_date = ?::date where id = ?::uuid",
                istToday().toString(), appointmentId)).isEqualTo(1);
    }

    /** Books for tomorrow, then moves the appointment to today. Returns the appointment JSON. */
    protected JsonNode bookForToday(Clinic clinic, UUID patient, UUID doctor, String time) throws Exception {
        JsonNode created = book(clinic, patient, doctor, tomorrow(), time);
        moveToToday(clinic, created.get("id").asText());
        return created;
    }

    protected long visitCount(Clinic clinic, String appointmentId) throws SQLException {
        return countOf(clinic.tenantId(), "select count(*) from opd.visit v join opd.appointment a on a.visit_id = v.id "
                + "where a.id = ?::uuid", appointmentId);
    }
}
