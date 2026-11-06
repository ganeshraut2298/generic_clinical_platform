package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.DoctorOpdDaysPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-21 Leave requests and balances on the real {@code sahaj_test} database: module gate, role-scoped list,
 * apply, validation, overlap, concurrency, approve and decline, balances, affected appointments, roster,
 * attendance and tiles, doctor booking closure through the opd port, audit without free text, and row level
 * security of {@code hra.leave_request}.
 *
 * <p>There is no per-date slot-list endpoint on the server (slots are computed from the doctor's settings),
 * so "the doctor has no slots" is observed through the {@code opd.api} {@link DoctorOpdDaysPort} bean (the
 * very computation the roster and booking use) and through the booking POST refusing with 409.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class LeaveRequestsIT extends SweepSupport {

    private static final String HRA = "/api/v1/hra";
    private static final String LEAVE = HRA + "/leave";
    private static final String MODULE_HRA = "/api/v1/platform/modules/hra";
    private static final String TEAM = "/api/v1/platform/team";
    private static final String ME = "/api/v1/platform/me";
    private static final String APPTS = "/api/v1/opd/appointments";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String OVERLAP = "That person already has leave on some of these days.";
    private static final String DECIDED = "This request was already decided.";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private DoctorOpdDaysPort opdDays;

    // ---------------- helpers ----------------

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    private static LocalDate d(int plusDays) {
        return today().plusDays(plusDays);
    }

    private Clinic hraClinic() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":true}", 200);
        return clinic;
    }

    private record Staff(UUID membershipId, UUID userId, RequestPostProcessor auth) {
    }

    private Staff staff(Clinic clinic, String role) throws Exception {
        return staffOf(clinic, addMember(clinic, role).membershipId(), role);
    }

    private Staff staffOf(Clinic clinic, UUID membershipId, String role) throws SQLException {
        UUID userId = db().inTenant(clinic.tenantId(), c -> UUID.fromString(
                DbAccess.text(c, "select user_id::text from platform.membership where id = ?", membershipId)));
        return new Staff(membershipId, userId, asUser(clinic.tenantId(), userId, role));
    }

    private UUID ownerMembership(Clinic clinic) throws Exception {
        return UUID.fromString(ok(HttpMethod.GET, ME, clinic.owner(), null, 200).get("membershipId").asText());
    }

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long leaveRows(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from hra.leave_request");
    }

    private static String body(UUID member, String type, Object from, Object to, String reason) {
        StringBuilder b = new StringBuilder("{");
        if (member != null) {
            b.append("\"membershipId\":\"").append(member).append("\",");
        }
        if (type != null) {
            b.append("\"type\":\"").append(type).append("\",");
        }
        if (from != null) {
            b.append("\"from\":\"").append(from).append("\",");
        }
        if (to != null) {
            b.append("\"to\":\"").append(to).append("\",");
        }
        if (reason != null) {
            b.append("\"reason\":\"").append(reason).append("\",");
        }
        if (b.charAt(b.length() - 1) == ',') {
            b.setLength(b.length() - 1);
        }
        return b.append('}').toString();
    }

    private ResultActions tryApply(RequestPostProcessor auth, UUID member, String type, Object from, Object to,
                                   String reason) throws Exception {
        return call(HttpMethod.POST, LEAVE, auth, body(member, type, from, to, reason));
    }

    /** POST /leave expecting 201; returns the view. */
    private JsonNode apply(RequestPostProcessor auth, UUID member, String type, Object from, Object to) throws Exception {
        return ok(HttpMethod.POST, LEAVE, auth, body(member, type, from, to, null), 201);
    }

    private static UUID newest(JsonNode view) {
        return UUID.fromString(view.get("requests").get(0).get("id").asText());
    }

    private static JsonNode request(JsonNode view, UUID id) {
        for (JsonNode r : view.get("requests")) {
            if (r.get("id").asText().equals(id.toString())) {
                return r;
            }
        }
        return null;
    }

    private static JsonNode balance(JsonNode view, UUID member) {
        for (JsonNode b : view.get("balances")) {
            if (b.get("membershipId").asText().equals(member.toString())) {
                return b.get("remaining");
            }
        }
        return null;
    }

    private JsonNode approve(Clinic clinic, UUID id) throws Exception {
        return ok(HttpMethod.POST, LEAVE + "/" + id + "/approve", clinic.owner(), null, 200);
    }

    private JsonNode decline(Clinic clinic, UUID id) throws Exception {
        return ok(HttpMethod.POST, LEAVE + "/" + id + "/decline", clinic.owner(), null, 200);
    }

    /** A leave row for a person already decided as approved, made by the API. */
    private UUID approvedLeave(Clinic clinic, UUID member, String type, Object from, Object to) throws Exception {
        UUID id = newest(apply(clinic.owner(), member, type, from, to));
        approve(clinic, id);
        return id;
    }

    private record Row(String status, Object decidedAt, UUID decidedBy, long version, UUID createdBy, String reason,
                       UUID membershipId) {
    }

    private Row row(Clinic clinic, UUID id) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("select status, decided_at, decided_by, version, "
                    + "created_by, reason, membership_id from hra.leave_request where id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(rs.getString(1), rs.getObject(2), rs.getObject(3, UUID.class),
                            rs.getLong(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class))
                            : null;
                }
            }
        });
    }

    private static void assertType(JsonNode problem, String suffix) {
        assertThat(problem.get("type").asText()).endsWith(suffix);
    }

    private static void assertFieldError(JsonNode problem, String field) {
        assertThat(problem.get("fieldErrors")).isNotNull();
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
    }

    private void commitAsTenant(UUID tenantId, String sql, Object... params) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            DbAccess.execute(c, sql, params);
            c.commit();
        }
    }

    private void assertDenied(Clinic clinic, String sql, Object... params) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql, params);
            return null;
        })).as(sql).isInstanceOf(SQLException.class);
    }

    private JsonNode book(Clinic clinic, UUID doctor, LocalDate date, String time, int expected) throws Exception {
        UUID patient = newPatient(clinic);
        return ok(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\"" + date
                        + "\",\"apptTime\":\"" + time + "\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                        + "\"note\":\"Fever\"}", expected);
    }

    private JsonNode rosterRowOf(Clinic clinic, UUID member) throws Exception {
        for (JsonNode r : ok(HttpMethod.GET, HRA + "/roster", clinic.owner(), null, 200).get("rows")) {
            if (r.get("membershipId").asText().equals(member.toString())) {
                return r;
            }
        }
        return null;
    }

    private static String kindOn(JsonNode row, LocalDate date) {
        for (JsonNode c : row.get("cells")) {
            if (c.get("date").asText().equals(date.toString())) {
                return c.get("kind").asText();
            }
        }
        return null;
    }

    private JsonNode attendanceRow(Clinic clinic, UUID member) throws Exception {
        for (JsonNode r : ok(HttpMethod.GET, HRA + "/attendance", clinic.owner(), null, 200).get("rows")) {
            if (r.get("membershipId").asText().equals(member.toString())) {
                return r;
            }
        }
        return null;
    }

    private static int daysInThisYear(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getYear() == today().getYear()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasSlots(Clinic clinic, UUID doctor, LocalDate date) {
        java.util.function.Supplier<Boolean> action = () -> opdDays.hasOpdSlots(clinic.tenantId(), doctor, date);
        return runAs(authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER"), action);
    }

    // ---------------- Module gate ----------------

    @Test
    void hraOffAllLeaveEndpointsAre403ModuleOffForEveryRole() throws Exception {
        Clinic clinic = newClinic();
        UUID id = UUID.randomUUID();
        String valid = body(null, "CL", d(1), d(2), null);
        assertType(problem(call(HttpMethod.GET, LEAVE, clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, LEAVE, clinic.owner(), valid), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, LEAVE + "/" + id + "/approve", clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, LEAVE + "/" + id + "/decline", clinic.owner(), null), 403), "module-off");
        for (String role : NON_OWNERS) {
            assertType(problem(call(HttpMethod.GET, LEAVE, clinic.as(role), null), 403), "module-off");
            assertType(problem(call(HttpMethod.POST, LEAVE, clinic.as(role), valid), 403), "module-off");
        }
        assertThat(leaveRows(clinic)).isZero();
    }

    @Test
    void unauthenticatedCallsGet401() throws Exception {
        mvc.perform(get(LEAVE)).andExpect(status().isUnauthorized());
        mvc.perform(post(LEAVE).contentType(MediaType.APPLICATION_JSON).content(body(null, "CL", d(1), d(1), null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(LEAVE + "/" + UUID.randomUUID() + "/approve")).andExpect(status().isUnauthorized());
        mvc.perform(post(LEAVE + "/" + UUID.randomUUID() + "/decline")).andExpect(status().isUnauthorized());
    }

    // ---------------- GET /leave ----------------

    @Test
    void ownerSeesEveryoneOthersOnlyTheirOwnTypesAllowancesNewestFirst() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        JsonNode empty = ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200);
        assertThat(empty.get("requests")).isEmpty();
        assertThat(empty.get("pendingCount").asInt()).isZero();
        assertThat(empty.get("canApprove").asBoolean()).isTrue();

        UUID first = newest(apply(nurse.auth(), null, "CL", d(3), d(4)));
        UUID second = newest(apply(desk.auth(), null, "SL", d(3), d(4)));
        UUID third = newest(apply(clinic.owner(), nurse.membershipId(), "EL", d(10), d(11)));

        JsonNode owner = ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200);
        assertThat(owner.get("types")).hasSize(3);
        assertThat(owner.get("types").get(0).get("key").asText()).isEqualTo("CL");
        assertThat(owner.get("types").get(0).get("days").asInt()).isEqualTo(8);
        assertThat(owner.get("types").get(0).get("name").asText()).isEqualTo("Casual leave");
        assertThat(owner.get("types").get(1).get("key").asText()).isEqualTo("SL");
        assertThat(owner.get("types").get(1).get("days").asInt()).isEqualTo(7);
        assertThat(owner.get("types").get(1).get("name").asText()).isEqualTo("Sick leave");
        assertThat(owner.get("types").get(2).get("key").asText()).isEqualTo("EL");
        assertThat(owner.get("types").get(2).get("days").asInt()).isEqualTo(15);
        assertThat(owner.get("types").get(2).get("name").asText()).isEqualTo("Earned leave");
        assertThat(owner.get("canApprove").asBoolean()).isTrue();
        assertThat(owner.get("pendingCount").asInt()).isEqualTo(3);
        assertThat(owner.get("requests")).hasSize(3);
        assertThat(owner.get("requests").get(0).get("id").asText()).isEqualTo(third.toString());
        assertThat(owner.get("requests").get(1).get("id").asText()).isEqualTo(second.toString());
        assertThat(owner.get("requests").get(2).get("id").asText()).isEqualTo(first.toString());
        // all active members: owner, doctor, nurse, front desk
        assertThat(owner.get("balances")).hasSize(4);
        assertThat(owner.get("applicants")).hasSize(4);
        JsonNode firstRow = request(owner, first);
        assertThat(firstRow.get("name").asText()).isEqualTo("Member NURSE");
        assertThat(firstRow.get("roleLabel").asText()).isEqualTo("Nurse");
        assertThat(firstRow.get("typeName").asText()).isEqualTo("Casual leave");
        assertThat(firstRow.get("days").asInt()).isEqualTo(2);
        assertThat(firstRow.get("askedAt").asText()).isNotBlank();
        for (JsonNode b : owner.get("balances")) {
            assertThat(b.get("remaining").get("CL").asInt()).isEqualTo(8);
            assertThat(b.get("remaining").get("SL").asInt()).isEqualTo(7);
            assertThat(b.get("remaining").get("EL").asInt()).isEqualTo(15);
        }

        JsonNode own = ok(HttpMethod.GET, LEAVE, nurse.auth(), null, 200);
        assertThat(own.get("canApprove").asBoolean()).isFalse();
        assertThat(own.get("requests")).hasSize(2);
        assertThat(own.get("requests").get(0).get("id").asText()).isEqualTo(third.toString());
        assertThat(own.get("requests").get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(own.get("pendingCount").asInt()).isEqualTo(2);
        assertThat(own.get("balances")).hasSize(1);
        assertThat(own.get("balances").get(0).get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(own.get("applicants")).hasSize(1);
        assertThat(own.get("applicants").get(0).get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(own.toString()).doesNotContain(second.toString());

        JsonNode deskView = ok(HttpMethod.GET, LEAVE, desk.auth(), null, 200);
        assertThat(deskView.get("requests")).hasSize(1);
        assertThat(deskView.get("pendingCount").asInt()).isEqualTo(1);
        assertThat(deskView.get("canApprove").asBoolean()).isFalse();
    }

    @Test
    void inactiveMembersAreLeftOutOfBalancesAndApplicants() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        apply(clinic.owner(), nurse.membershipId(), "CL", d(3), d(3));
        ok(HttpMethod.POST, TEAM + "/" + nurse.membershipId() + "/deactivate", clinic.owner(), null, 200);
        JsonNode view = ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200);
        assertThat(balance(view, nurse.membershipId())).isNull();
        assertThat(view.get("applicants").toString()).doesNotContain(nurse.membershipId().toString());
        assertThat(view.get("requests")).hasSize(1);
        problem(tryApply(clinic.owner(), nurse.membershipId(), "CL", d(8), d(8), null), 404);
    }

    // ---------------- POST /leave ----------------

    @Test
    void staffAppliesForSelfCreatedPendingWithMembershipFromTokenAndOwnerAppliesForAnother() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");

        JsonNode view = ok(HttpMethod.POST, LEAVE, nurse.auth(), body(null, "CL", d(2), d(4), "Family function"), 201);
        UUID id = newest(view);
        JsonNode r = request(view, id);
        assertThat(r.get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(r.get("status").asText()).isEqualTo("pending");
        assertThat(r.get("type").asText()).isEqualTo("CL");
        assertThat(r.get("from").asText()).isEqualTo(d(2).toString());
        assertThat(r.get("to").asText()).isEqualTo(d(4).toString());
        assertThat(r.get("days").asInt()).isEqualTo(3);
        assertThat(r.get("reason").asText()).isEqualTo("Family function");
        assertThat(r.get("decidedAt").isNull()).isTrue();
        assertThat(r.get("decidedByName").isNull()).isTrue();
        assertThat(r.get("affectedAppointments").asInt()).isZero();
        assertThat(view.get("canApprove").asBoolean()).isFalse();
        assertThat(view.get("requests")).hasSize(1);
        Row db = row(clinic, id);
        assertThat(db.status()).isEqualTo("pending");
        assertThat(db.decidedAt()).isNull();
        assertThat(db.decidedBy()).isNull();
        assertThat(db.version()).isZero();
        assertThat(db.createdBy()).isEqualTo(nurse.userId());
        assertThat(db.membershipId()).isEqualTo(nurse.membershipId());

        // naming themselves explicitly is the same as omitting it
        JsonNode again = apply(desk.auth(), desk.membershipId(), "SL", d(2), d(2));
        assertThat(row(clinic, newest(again)).membershipId()).isEqualTo(desk.membershipId());

        // the owner applies for the nurse on other days; the response is the owner's view (everyone's)
        JsonNode ownerView = ok(HttpMethod.POST, LEAVE, clinic.owner(), body(nurse.membershipId(), "EL", d(10), d(10), null), 201);
        UUID forNurse = newest(ownerView);
        assertThat(row(clinic, forNurse).membershipId()).isEqualTo(nurse.membershipId());
        assertThat(row(clinic, forNurse).createdBy()).isEqualTo(clinic.ownerUserId());
        assertThat(row(clinic, forNurse).status()).isEqualTo("pending");
        assertThat(ownerView.get("requests")).hasSize(3);
        assertThat(ownerView.get("canApprove").asBoolean()).isTrue();

        // the owner applies for themselves
        UUID mine = newest(apply(clinic.owner(), null, "CL", d(20), d(20)));
        assertThat(row(clinic, mine).membershipId()).isEqualTo(ownerMembership(clinic));
        assertThat(leaveRows(clinic)).isEqualTo(4);
    }

    @Test
    void blankReasonIsStoredAsNullAndReasonIsTrimmed() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        UUID blank = newest(ok(HttpMethod.POST, LEAVE, nurse.auth(), body(null, "CL", d(2), d(2), "   "), 201));
        assertThat(row(clinic, blank).reason()).isNull();
        UUID padded = newest(ok(HttpMethod.POST, LEAVE, nurse.auth(), body(null, "CL", d(5), d(5), "  Fever  "), 201));
        assertThat(row(clinic, padded).reason()).isEqualTo("Fever");
    }

    @Test
    void nonOwnerNamingAnotherPersonIs403AndNothingIsWritten() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        problem(tryApply(nurse.auth(), desk.membershipId(), "CL", d(2), d(2), null), 403);
        problem(tryApply(nurse.auth(), clinic.doctorMembershipId(), "CL", d(2), d(2), null), 403);
        for (String role : NON_OWNERS) {
            // role from the token; the signed-in user here is the owner's, so any other member is "someone else"
            problem(tryApply(clinic.as(role), nurse.membershipId(), "CL", d(2), d(2), null), 403);
        }
        assertThat(leaveRows(clinic)).isZero();
    }

    @Test
    void unknownInactiveAndOtherTenantMembersAre404() throws Exception {
        Clinic clinic = hraClinic();
        Clinic other = hraClinic();
        Staff foreign = staff(other, "NURSE");
        Staff gone = staff(clinic, "BILLING");
        ok(HttpMethod.POST, TEAM + "/" + gone.membershipId() + "/deactivate", clinic.owner(), null, 200);
        problem(tryApply(clinic.owner(), UUID.randomUUID(), "CL", d(2), d(2), null), 404);
        problem(tryApply(clinic.owner(), foreign.membershipId(), "CL", d(2), d(2), null), 404);
        problem(tryApply(clinic.owner(), gone.membershipId(), "CL", d(2), d(2), null), 404);
        assertThat(leaveRows(clinic)).isZero();
        assertThat(leaveRows(other)).isZero();
    }

    @Test
    void invalidInputGets400ProblemWithFieldErrorsAndWritesNothing() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        String t = today().toString();
        String y = today().minusDays(1).toString();
        String reason201 = "x".repeat(201);
        Object[][] cases = {
                {body(null, null, t, t, null), "type"},
                {body(null, "XX", t, t, null), "type"},
                {body(null, "cl", t, t, null), "type"},
                {body(null, "", t, t, null), "type"},
                {body(null, "CL", null, t, null), "from"},
                {body(null, "CL", t, null, null), "to"},
                {body(null, "CL", y, t, null), "from"},
                {body(null, "CL", y, y, null), "from"},
                {body(null, "CL", d(5), d(4), null), "to"},
                {body(null, "CL", d(1), d(62), null), "to"},
                {body(null, "CL", t, t, reason201), "reason"},
        };
        for (Object[] c : cases) {
            JsonNode p = problem(call(HttpMethod.POST, LEAVE, nurse.auth(), (String) c[0]), 400);
            assertFieldError(p, (String) c[1]);
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isNotBlank();
        }
        problem(call(HttpMethod.POST, LEAVE, nurse.auth(), "{not json"), 400);
        problem(call(HttpMethod.POST, LEAVE, nurse.auth(),
                "{\"type\":\"CL\",\"from\":\"not-a-date\",\"to\":\"" + t + "\"}"), 400);
        assertThat(leaveRows(clinic)).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'LEAVE_REQUESTED'")).isZero();
    }

    @Test
    void boundariesAreAcceptedTodaySpanOfSixtyDaysAndReasonOfTwoHundredCharacters() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        Staff billing = staff(clinic, "BILLING");
        apply(nurse.auth(), null, "CL", today(), today());
        apply(desk.auth(), null, "EL", d(1), d(61)); // 60 days after from
        ok(HttpMethod.POST, LEAVE, billing.auth(), body(null, "SL", d(1), d(1), "y".repeat(200)), 201);
        assertThat(leaveRows(clinic)).isEqualTo(3);
        // the same span one day longer is refused
        problem(tryApply(billing.auth(), null, "SL", d(100), d(161), null), 400);
        assertThat(leaveRows(clinic)).isEqualTo(3);
    }

    @Test
    void overlapWithPendingOrApprovedIs409DeclinedDoesNotBlockAndOtherPeopleAreNotAffected() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        UUID pending = newest(apply(nurse.auth(), null, "CL", d(10), d(12)));

        // every kind of overlap with a pending request
        LocalDate[][] clashes = {{d(10), d(12)}, {d(8), d(10)}, {d(12), d(14)}, {d(11), d(11)}, {d(9), d(13)}};
        for (LocalDate[] c : clashes) {
            JsonNode p = problem(tryApply(nurse.auth(), null, "SL", c[0], c[1], null), 409);
            assertThat(p.get("detail").asText()).isEqualTo(OVERLAP);
            assertType(p, "leave-overlap");
        }
        // the owner applying for the same person is refused as well
        problem(tryApply(clinic.owner(), nurse.membershipId(), "SL", d(11), d(11), null), 409);
        // the day before and the day after are free; another person on the same days is free
        apply(nurse.auth(), null, "CL", d(8), d(9));
        apply(nurse.auth(), null, "CL", d(13), d(13));
        apply(desk.auth(), null, "CL", d(10), d(12));
        assertThat(leaveRows(clinic)).isEqualTo(4);

        // an approved request blocks too
        approve(clinic, pending);
        assertThat(problem(tryApply(nurse.auth(), null, "EL", d(11), d(11), null), 409).get("detail").asText())
                .isEqualTo(OVERLAP);

        // a declined request does not block
        UUID toDecline = newest(apply(nurse.auth(), null, "CL", d(30), d(32)));
        decline(clinic, toDecline);
        apply(nurse.auth(), null, "CL", d(31), d(31));
        assertThat(leaveRows(clinic)).isEqualTo(6);
    }

    @Test
    void twoConcurrentRequestsForTheSamePersonAndDaysGiveExactlyOneCreatedAndOneConflict() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        int rounds = 4;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String payload = body(null, "CL", d(20 + i * 5), d(21 + i * 5), null);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Integer> attempt = () -> {
                    start.await();
                    return mvc.perform(post(LEAVE).with(nurse.auth()).contentType(MediaType.APPLICATION_JSON)
                            .content(payload)).andReturn().getResponse().getStatus();
                };
                List<Future<Integer>> futures = new ArrayList<>();
                futures.add(pool.submit(attempt));
                futures.add(pool.submit(attempt));
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : futures) {
                    statuses.add(f.get());
                }
                assertThat(statuses).as("round " + i).containsExactlyInAnyOrder(201, 409);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(leaveRows(clinic)).isEqualTo(rounds);
    }

    // ---------------- approve and decline ----------------

    @Test
    void ownerApprovesAndDeclinesDecisionFieldsAreSetTogetherVersionBumpedAndASecondDecisionIs409() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        UUID a = newest(apply(nurse.auth(), null, "CL", d(3), d(3)));
        UUID b = newest(apply(nurse.auth(), null, "CL", d(5), d(5)));

        JsonNode afterApprove = approve(clinic, a);
        JsonNode ra = request(afterApprove, a);
        assertThat(ra.get("status").asText()).isEqualTo("approved");
        assertThat(ra.get("decidedAt").isNull()).isFalse();
        assertThat(ra.get("decidedByName").asText()).isNotBlank();
        assertThat(afterApprove.get("pendingCount").asInt()).isEqualTo(1);
        Row rowA = row(clinic, a);
        assertThat(rowA.status()).isEqualTo("approved");
        assertThat(rowA.decidedAt()).isNotNull();
        assertThat(rowA.decidedBy()).isEqualTo(clinic.ownerUserId());
        assertThat(rowA.version()).isEqualTo(1);

        JsonNode afterDecline = decline(clinic, b);
        assertThat(request(afterDecline, b).get("status").asText()).isEqualTo("declined");
        Row rowB = row(clinic, b);
        assertThat(rowB.status()).isEqualTo("declined");
        assertThat(rowB.decidedAt()).isNotNull();
        assertThat(rowB.decidedBy()).isEqualTo(clinic.ownerUserId());
        assertThat(rowB.version()).isEqualTo(1);
        assertThat(afterDecline.get("pendingCount").asInt()).isZero();

        // a decided request can never change
        for (String action : new String[] {"approve", "decline"}) {
            for (UUID id : new UUID[] {a, b}) {
                JsonNode p = problem(call(HttpMethod.POST, LEAVE + "/" + id + "/" + action, clinic.owner(), null), 409);
                assertThat(p.get("detail").asText()).isEqualTo(DECIDED);
                assertType(p, "leave-already-decided");
            }
        }
        assertThat(row(clinic, a).status()).isEqualTo("approved");
        assertThat(row(clinic, a).version()).isEqualTo(1);
        assertThat(row(clinic, b).status()).isEqualTo("declined");
        assertThat(row(clinic, b).version()).isEqualTo(1);
    }

    @Test
    void onlyTheOwnerCanDecideEveryOtherRoleGets403AndTheRequestStaysPending() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        UUID id = newest(apply(nurse.auth(), null, "CL", d(3), d(3)));
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, LEAVE + "/" + id + "/approve", clinic.as(role), null), 403);
            problem(call(HttpMethod.POST, LEAVE + "/" + id + "/decline", clinic.as(role), null), 403);
        }
        problem(call(HttpMethod.POST, LEAVE + "/" + id + "/approve", nurse.auth(), null), 403);
        problem(call(HttpMethod.POST, LEAVE + "/" + id + "/decline", nurse.auth(), null), 403);
        Row r = row(clinic, id);
        assertThat(r.status()).isEqualTo("pending");
        assertThat(r.decidedAt()).isNull();
        assertThat(r.decidedBy()).isNull();
        assertThat(r.version()).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action in ('LEAVE_APPROVED','LEAVE_DECLINED')"))
                .isZero();
    }

    @Test
    void decidingAnUnknownOrOtherTenantRequestIs404() throws Exception {
        Clinic a = hraClinic();
        Clinic b = hraClinic();
        Staff nurseA = staff(a, "NURSE");
        UUID id = newest(apply(nurseA.auth(), null, "CL", d(3), d(3)));
        for (String action : new String[] {"approve", "decline"}) {
            problem(call(HttpMethod.POST, LEAVE + "/" + UUID.randomUUID() + "/" + action, a.owner(), null), 404);
            problem(call(HttpMethod.POST, LEAVE + "/" + id + "/" + action, b.owner(), null), 404);
        }
        assertThat(row(a, id).status()).isEqualTo("pending");
        assertThat(row(a, id).version()).isZero();
    }

    // ---------------- balances ----------------

    @Test
    void approvedDaysReducePendingAndDeclinedDoNotAndNeverBelowZero() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        UUID cl = newest(apply(nurse.auth(), null, "CL", d(2), d(4))); // 3 days
        approve(clinic, cl);
        int clUsed = daysInThisYear(d(2), d(4));
        apply(nurse.auth(), null, "CL", d(6), d(7)); // pending, 2 days
        UUID sl = newest(apply(nurse.auth(), null, "SL", d(9), d(13))); // 5 days
        decline(clinic, sl);
        // 1 to 31 January of this year, 31 days against an allowance of 15: committed through the database
        commitAsTenant(clinic.tenantId(), "insert into hra.leave_request (tenant_id, membership_id, leave_type, "
                + "from_date, to_date, status, created_by, decided_at, decided_by) "
                + "values (?, ?, 'EL', ?, ?, 'approved', ?, now(), ?)", clinic.tenantId(), nurse.membershipId(),
                LocalDate.of(today().getYear(), 1, 1), LocalDate.of(today().getYear(), 1, 31), clinic.ownerUserId(),
                clinic.ownerUserId());

        JsonNode own = balance(ok(HttpMethod.GET, LEAVE, nurse.auth(), null, 200), nurse.membershipId());
        assertThat(own.get("CL").asInt()).isEqualTo(8 - clUsed);
        assertThat(own.get("SL").asInt()).isEqualTo(7);
        assertThat(own.get("EL").asInt()).as("floor at zero").isZero();
        JsonNode ownerSees = balance(ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200), nurse.membershipId());
        assertThat(ownerSees.get("CL").asInt()).isEqualTo(8 - clUsed);
        assertThat(ownerSees.get("EL").asInt()).isZero();
        // someone else is untouched
        JsonNode doctor = balance(ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200), clinic.doctorMembershipId());
        assertThat(doctor.get("CL").asInt()).isEqualTo(8);
    }

    @Test
    void aRequestAcrossNewYearCountsOnlyTheDaysInTheCurrentYear() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        int year = today().getYear();
        // 31 Dec of this year to 2 Jan of next year, 3 days of which 1 is this year. Committed through the database:
        // the API refuses a first day more than 60 days ahead, which 31 Dec is for most of the year.
        commitAsTenant(clinic.tenantId(), "insert into hra.leave_request (tenant_id, membership_id, leave_type, "
                + "from_date, to_date, status, created_by, decided_at, decided_by) "
                + "values (?, ?, 'EL', ?, ?, 'approved', ?, now(), ?)", clinic.tenantId(), nurse.membershipId(),
                LocalDate.of(year, 12, 31), LocalDate.of(year + 1, 1, 2), clinic.ownerUserId(), clinic.ownerUserId());
        // wholly next year: counts nothing. Committed through the database because the API now refuses a first
        // day more than 60 days ahead.
        commitAsTenant(clinic.tenantId(), "insert into hra.leave_request (tenant_id, membership_id, leave_type, "
                + "from_date, to_date, status, created_by, decided_at, decided_by) "
                + "values (?, ?, 'SL', ?, ?, 'approved', ?, now(), ?)", clinic.tenantId(), nurse.membershipId(),
                LocalDate.of(year + 1, 1, 10), LocalDate.of(year + 1, 1, 12), clinic.ownerUserId(),
                clinic.ownerUserId());
        // committed through the database, as the tenant: 30 Dec last year to 2 Jan this year, 2 days this year
        commitAsTenant(clinic.tenantId(), "insert into hra.leave_request (tenant_id, membership_id, leave_type, "
                + "from_date, to_date, status, created_by, decided_at, decided_by) "
                + "values (?, ?, 'CL', ?, ?, 'approved', ?, now(), ?)", clinic.tenantId(), nurse.membershipId(),
                LocalDate.of(year - 1, 12, 30), LocalDate.of(year, 1, 2), clinic.ownerUserId(), clinic.ownerUserId());
        JsonNode own = balance(ok(HttpMethod.GET, LEAVE, nurse.auth(), null, 200), nurse.membershipId());
        assertThat(own.get("EL").asInt()).isEqualTo(14);
        assertThat(own.get("SL").asInt()).isEqualTo(7);
        assertThat(own.get("CL").asInt()).isEqualTo(6);
        // the request itself still reports its full length
        JsonNode view = ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200);
        boolean sawThree = false;
        for (JsonNode r : view.get("requests")) {
            if ("EL".equals(r.get("type").asText())) {
                assertThat(r.get("days").asInt()).isEqualTo(3);
                sawThree = true;
            }
        }
        assertThat(sawThree).isTrue();
    }

    // ---------------- affected appointments ----------------

    @Test
    void pendingDoctorRequestCountsOnlyBookedAppointmentsOnThoseDaysNonDoctorsAndDecidedRequestsHaveZero() throws Exception {
        Clinic clinic = hraClinic();
        UUID doctor = clinic.doctorMembershipId();
        Staff nurse = staff(clinic, "NURSE");
        book(clinic, doctor, d(4), "09:00", 201); // outside (day before)
        book(clinic, doctor, d(8), "09:00", 201); // outside (day after)
        book(clinic, doctor, d(5), "09:00", 201); // counted
        book(clinic, doctor, d(6), "09:00", 201); // counted
        JsonNode cancelled = book(clinic, doctor, d(7), "09:00", 201);
        JsonNode noShow = book(clinic, doctor, d(7), "09:15", 201);
        JsonNode arrived = book(clinic, doctor, d(7), "09:30", 201);
        ok(HttpMethod.PATCH, APPTS + "/" + cancelled.get("id").asText(), clinic.as("FRONT_DESK"),
                "{\"status\":\"CANCELLED\"}", 200);
        ok(HttpMethod.PATCH, APPTS + "/" + noShow.get("id").asText(), clinic.as("FRONT_DESK"),
                "{\"status\":\"NOSHOW\"}", 200);
        ok(HttpMethod.POST, APPTS + "/" + arrived.get("id").asText() + "/token", clinic.as("FRONT_DESK"), null, 200);

        JsonNode doctorView = ok(HttpMethod.POST, LEAVE, clinic.owner(), body(doctor, "CL", d(5), d(7), null), 201);
        UUID doctorRequest = newest(doctorView);
        assertThat(request(doctorView, doctorRequest).get("affectedAppointments").asInt()).isEqualTo(2);
        // a nurse asking for the same days has no appointments to disturb
        JsonNode nurseView = ok(HttpMethod.POST, LEAVE, nurse.auth(), body(null, "CL", d(5), d(7), null), 201);
        assertThat(request(nurseView, newest(nurseView)).get("affectedAppointments").asInt()).isZero();
        // a request that covers none of the booked days
        JsonNode other = ok(HttpMethod.POST, LEAVE, clinic.owner(), body(doctor, "SL", d(15), d(16), null), 201);
        assertThat(request(other, newest(other)).get("affectedAppointments").asInt()).isZero();

        // once decided the number is no longer shown, and the appointments stay as they are
        JsonNode decided = approve(clinic, doctorRequest);
        assertThat(request(decided, doctorRequest).get("affectedAppointments").asInt()).isZero();
        JsonNode day = ok(HttpMethod.GET, APPTS + "?date=" + d(5), clinic.owner(), null, 200);
        assertThat(day).hasSize(1);
        assertThat(day.get(0).get("status").asText()).isEqualTo("BOOKED");
    }

    // ---------------- roster, attendance, summary ----------------

    @Test
    void approvedLeaveShowsAsLeaveInTheRosterForStaffAndDoctorsAndOverridesCellEdits() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        UUID doctor = clinic.doctorMembershipId();
        approvedLeave(clinic, nurse.membershipId(), "CL", d(1), d(2));
        approvedLeave(clinic, doctor, "SL", d(2), d(3));
        apply(desk.auth(), null, "CL", d(1), d(1)); // pending: shows nothing
        UUID declined = newest(apply(desk.auth(), null, "CL", d(4), d(4)));
        decline(clinic, declined);

        JsonNode nurseRow = rosterRowOf(clinic, nurse.membershipId());
        assertThat(kindOn(nurseRow, d(0))).isEqualTo("SHIFT");
        assertThat(kindOn(nurseRow, d(1))).isEqualTo("LEAVE");
        assertThat(kindOn(nurseRow, d(2))).isEqualTo("LEAVE");
        assertThat(kindOn(nurseRow, d(3))).isEqualTo("SHIFT");
        JsonNode doctorRow = rosterRowOf(clinic, doctor);
        assertThat(kindOn(doctorRow, d(1))).isEqualTo("OPD");
        assertThat(kindOn(doctorRow, d(2))).isEqualTo("LEAVE");
        assertThat(kindOn(doctorRow, d(3))).isEqualTo("LEAVE");
        assertThat(kindOn(doctorRow, d(4))).isEqualTo("OPD");
        JsonNode deskRow = rosterRowOf(clinic, desk.membershipId());
        assertThat(kindOn(deskRow, d(1))).isEqualTo("SHIFT");
        assertThat(kindOn(deskRow, d(4))).isEqualTo("SHIFT");

        // Security/code review fix: a shift edit on an approved-leave day is refused with 409 (on-leave), nothing
        // is stored, and the cell keeps showing LEAVE.
        ok(HttpMethod.PUT, HRA + "/roster/cells", clinic.owner(),
                "{\"membershipId\":\"" + nurse.membershipId() + "\",\"date\":\"" + d(1) + "\",\"shift\":\"M\"}", 409);
        assertThat(kindOn(rosterRowOf(clinic, nurse.membershipId()), d(1))).isEqualTo("LEAVE");
        assertThat(count(clinic, "select count(*) from hra.roster_shift where membership_id = ? and work_date = ?",
                nurse.membershipId(), d(1))).isZero();
    }

    @Test
    void attendanceShowsOnLeaveTodayAndRefusesAMarkWith409() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        Staff pharmacist = staff(clinic, "PHARMACIST");
        UUID doctor = clinic.doctorMembershipId();
        approvedLeave(clinic, nurse.membershipId(), "CL", today(), d(1));
        approvedLeave(clinic, doctor, "SL", today(), today());
        apply(desk.auth(), null, "CL", today(), today()); // pending
        UUID declined = newest(apply(pharmacist.auth(), null, "CL", today(), today()));
        decline(clinic, declined);
        approvedLeave(clinic, pharmacist.membershipId(), "CL", d(1), d(2)); // tomorrow, not today

        assertThat(attendanceRow(clinic, nurse.membershipId()).get("onLeave").asBoolean()).isTrue();
        assertThat(attendanceRow(clinic, doctor).get("onLeave").asBoolean()).isTrue();
        assertThat(attendanceRow(clinic, desk.membershipId()).get("onLeave").asBoolean()).isFalse();
        assertThat(attendanceRow(clinic, pharmacist.membershipId()).get("onLeave").asBoolean()).isFalse();

        for (String status : new String[] {"in", "late", "abs"}) {
            JsonNode p = problem(call(HttpMethod.PUT, HRA + "/attendance/" + nurse.membershipId(), clinic.owner(),
                    "{\"status\":\"" + status + "\"}"), 409);
            assertType(p, "on-leave");
        }
        problem(call(HttpMethod.PUT, HRA + "/attendance/" + doctor, clinic.as("FRONT_DESK"), "{\"status\":\"in\"}"), 409);
        assertThat(count(clinic, "select count(*) from hra.attendance where membership_id = ?", nurse.membershipId()))
                .isZero();
        // not on leave: marking still works
        ok(HttpMethod.PUT, HRA + "/attendance/" + desk.membershipId(), clinic.owner(), "{\"status\":\"in\"}", 200);
        ok(HttpMethod.PUT, HRA + "/attendance/" + pharmacist.membershipId(), clinic.owner(), "{\"status\":\"in\"}", 200);
    }

    @Test
    void summaryLeaveRequestsIsThePendingCountForTheOwnerAndZeroForEveryoneElse() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        assertThat(ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200).get("leaveRequests").asInt()).isZero();
        UUID a = newest(apply(nurse.auth(), null, "CL", d(3), d(3)));
        apply(desk.auth(), null, "CL", d(3), d(3));
        apply(clinic.owner(), nurse.membershipId(), "SL", d(9), d(9));
        assertThat(ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200).get("leaveRequests").asInt()).isEqualTo(3);
        for (String role : NON_OWNERS) {
            assertThat(ok(HttpMethod.GET, HRA + "/summary", clinic.as(role), null, 200).get("leaveRequests").asInt())
                    .as(role).isZero();
        }
        assertThat(ok(HttpMethod.GET, HRA + "/summary", nurse.auth(), null, 200).get("leaveRequests").asInt()).isZero();
        approve(clinic, a);
        assertThat(ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200).get("leaveRequests").asInt()).isEqualTo(2);
    }

    // ---------------- booking closure ----------------

    @Test
    void doctorOnApprovedLeaveHasNoSlotsThatDayBookingIs409ExistingAppointmentsStayAndHraOffUnblocks() throws Exception {
        Clinic clinic = hraClinic();
        UUID doctor = clinic.doctorMembershipId();
        Staff visiting = staff(clinic, "VISITING_DOCTOR");
        LocalDate leaveDay = d(3);
        JsonNode existing = book(clinic, doctor, leaveDay, "09:00", 201);

        // a pending request closes nothing
        UUID id = newest(ok(HttpMethod.POST, LEAVE, clinic.owner(), body(doctor, "CL", leaveDay, d(4), null), 201));
        assertThat(hasSlots(clinic, doctor, leaveDay)).isTrue();
        book(clinic, doctor, leaveDay, "09:15", 201);
        approve(clinic, id);

        // approved: no slots on both days, slots on the days around
        assertThat(hasSlots(clinic, doctor, leaveDay)).isFalse();
        assertThat(hasSlots(clinic, doctor, d(4))).isFalse();
        assertThat(hasSlots(clinic, doctor, d(2))).isTrue();
        assertThat(hasSlots(clinic, doctor, d(5))).isTrue();
        assertThat(hasSlots(clinic, visiting.membershipId(), leaveDay)).as("another doctor").isTrue();

        JsonNode p = problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"), bookingBody(clinic, doctor, leaveDay, "10:00")), 409);
        assertType(p, "doctor-unavailable");
        assertThat(p.get("detail").asText()).isEqualTo("This doctor is not taking bookings on that date.");
        book(clinic, doctor, d(5), "10:00", 201);
        book(clinic, visiting.membershipId(), leaveDay, "10:00", 201);

        // existing appointments are untouched
        JsonNode day = ok(HttpMethod.GET, APPTS + "?date=" + leaveDay + "&doctorMembershipId=" + doctor, clinic.owner(), null, 200);
        assertThat(day).hasSize(2);
        for (JsonNode a : day) {
            assertThat(a.get("status").asText()).isEqualTo("BOOKED");
        }
        assertThat(day.toString()).contains(existing.get("id").asText());

        // HRA off: the leave row stays, nothing is blocked, the leave pages are locked
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":false}", 200);
        assertThat(leaveRows(clinic)).isEqualTo(1);
        assertThat(row(clinic, id).status()).isEqualTo("approved");
        assertThat(hasSlots(clinic, doctor, leaveDay)).isTrue();
        book(clinic, doctor, leaveDay, "10:30", 201);
        assertType(problem(call(HttpMethod.GET, LEAVE, clinic.owner(), null), 403), "module-off");

        // HRA on again: blocked again
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":true}", 200);
        assertThat(hasSlots(clinic, doctor, leaveDay)).isFalse();
        assertType(problem(call(HttpMethod.POST, APPTS, clinic.as("FRONT_DESK"), bookingBody(clinic, doctor, leaveDay, "11:00")), 409),
                "doctor-unavailable");
        assertThat(ok(HttpMethod.GET, LEAVE, clinic.owner(), null, 200).get("requests")).hasSize(1);
    }

    @Test
    void declinedLeaveDoesNotCloseBooking() throws Exception {
        Clinic clinic = hraClinic();
        UUID doctor = clinic.doctorMembershipId();
        UUID id = newest(ok(HttpMethod.POST, LEAVE, clinic.owner(), body(doctor, "CL", d(3), d(3), null), 201));
        decline(clinic, id);
        assertThat(hasSlots(clinic, doctor, d(3))).isTrue();
        book(clinic, doctor, d(3), "09:00", 201);
    }

    @Test
    void anotherTenantsDoctorLeaveDoesNotCloseThisClinicsDoctor() throws Exception {
        Clinic a = hraClinic();
        Clinic b = hraClinic();
        approvedLeave(a, a.doctorMembershipId(), "CL", d(3), d(3));
        assertThat(hasSlots(a, a.doctorMembershipId(), d(3))).isFalse();
        assertThat(hasSlots(b, b.doctorMembershipId(), d(3))).isTrue();
        book(b, b.doctorMembershipId(), d(3), "09:00", 201);
        // and a booking in B naming A's doctor is a plain not-found, not a leave answer
        problem(call(HttpMethod.POST, APPTS, b.as("FRONT_DESK"), bookingBody(b, a.doctorMembershipId(), d(3), "09:30")), 404);
    }

    private String bookingBody(Clinic clinic, UUID doctor, LocalDate date, String time) throws Exception {
        return "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + doctor + "\",\"apptDate\":\""
                + date + "\",\"apptTime\":\"" + time + "\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                + "\"note\":\"Fever\"}";
    }

    // ---------------- audit ----------------

    @Test
    void auditHasIdsDatesTypeAndStatusButNeverTheReasonText(CapturedOutput output) throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        Staff desk = staff(clinic, "FRONT_DESK");
        String secret = "ZXQ-" + UUID.randomUUID();
        UUID a = newest(ok(HttpMethod.POST, LEAVE, nurse.auth(), body(null, "CL", d(3), d(4), secret), 201));
        UUID b = newest(ok(HttpMethod.POST, LEAVE, clinic.owner(), body(desk.membershipId(), "SL", d(6), d(6), secret + "-b"), 201));
        approve(clinic, a);
        decline(clinic, b);

        List<String[]> requested = audit(clinic, "LEAVE_REQUESTED");
        List<String[]> approved = audit(clinic, "LEAVE_APPROVED");
        List<String[]> declined = audit(clinic, "LEAVE_DECLINED");
        assertThat(requested).hasSize(2);
        assertThat(approved).hasSize(1);
        assertThat(declined).hasSize(1);
        for (String[] row : requested) {
            assertThat(row[0]).isEqualTo("LEAVE_REQUEST");
            assertThat(row[2]).as("a new request has no before").isNull();
            assertThat(row[3]).contains("pending");
        }
        assertThat(requested.get(0)[1]).isEqualTo(a.toString());
        assertThat(requested.get(0)[3]).contains(nurse.membershipId().toString()).contains("CL")
                .contains(d(3).toString()).contains(d(4).toString());
        assertThat(requested.get(0)[4]).isEqualTo(nurse.userId().toString());
        assertThat(requested.get(1)[1]).isEqualTo(b.toString());
        assertThat(requested.get(1)[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(approved.get(0)[0]).isEqualTo("LEAVE_REQUEST");
        assertThat(approved.get(0)[1]).isEqualTo(a.toString());
        assertThat(approved.get(0)[2]).contains("pending");
        assertThat(approved.get(0)[3]).contains("approved").contains("CL").contains(d(3).toString());
        assertThat(approved.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(declined.get(0)[1]).isEqualTo(b.toString());
        assertThat(declined.get(0)[2]).contains("pending");
        assertThat(declined.get(0)[3]).contains("declined").contains("SL").contains(d(6).toString());

        // the reason texts are in the leave rows (so the check below has teeth) and nowhere in the audit
        assertThat(count(clinic, "select count(*) from hra.leave_request where reason like ?", "ZXQ-%")).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from platform.audit_event "
                + "where before_json::text like '%ZXQ-%' or after_json::text like '%ZXQ-%' "
                + "or entity_id::text like '%ZXQ-%' or audit_event::text like '%ZXQ-%'")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'LEAVE_%' "
                + "and (audit_event::text like '%Member NURSE%' or audit_event::text like '%Member FRONT_DESK%')")).isZero();
        assertThat(output.getAll()).doesNotContain(secret);
    }

    /** Audit rows: entity_type, entity_id, before_json, after_json, actor_user_id. */
    private List<String[]> audit(Clinic clinic, String action) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select entity_type, entity_id::text, before_json::text, after_json::text, actor_user_id::text "
                            + "from platform.audit_event where action = ? order by at")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5)});
                    }
                }
            }
            return rows;
        });
    }

    // ---------------- row level security and constraints ----------------

    @Test
    void anotherTenantSeesNoneOfThisClinicsLeaveThroughApiOrSql() throws Exception {
        Clinic a = hraClinic();
        Clinic b = hraClinic();
        Staff nurseA = staff(a, "NURSE");
        UUID id = newest(apply(nurseA.auth(), null, "CL", d(3), d(4)));

        JsonNode viewB = ok(HttpMethod.GET, LEAVE, b.owner(), null, 200);
        assertThat(viewB.get("requests")).isEmpty();
        assertThat(viewB.get("pendingCount").asInt()).isZero();
        assertThat(viewB.toString()).doesNotContain(nurseA.membershipId().toString()).doesNotContain(id.toString());
        assertThat(ok(HttpMethod.GET, HRA + "/summary", b.owner(), null, 200).get("leaveRequests").asInt()).isZero();

        assertThat(count(a, "select count(*) from hra.leave_request")).isEqualTo(1);
        assertThat(count(b, "select count(*) from hra.leave_request")).isZero();
        assertThat(count(b, "select count(*) from hra.leave_request where tenant_id = ?", a.tenantId())).isZero();
        assertThat(count(b, "select count(*) from hra.leave_request where id = ?", id)).isZero();
        Integer updated = db().inTenant(b.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("update hra.leave_request set status = 'declined'")) {
                return ps.executeUpdate();
            }
        });
        assertThat(updated).isZero();
        assertDenied(b, "insert into hra.leave_request (tenant_id, membership_id, leave_type, from_date, to_date, "
                + "created_by) values (?, gen_random_uuid(), 'CL', current_date, current_date, gen_random_uuid())",
                a.tenantId());
        assertThat(row(a, id).status()).isEqualTo("pending");
    }

    @Test
    void withNoTenantBoundTheAppRoleSeesNoLeaveRows() throws Exception {
        Clinic a = hraClinic();
        Staff nurse = staff(a, "NURSE");
        apply(nurse.auth(), null, "CL", d(3), d(4));
        assertThat(count(a, "select count(*) from hra.leave_request")).isEqualTo(1);
        Long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from hra.leave_request"));
        assertThat(unbound).isZero();
    }

    @Test
    void appRoleCannotDeleteLeaveRowsAndTheTableChecksRejectBadRows() throws Exception {
        Clinic a = hraClinic();
        Staff nurse = staff(a, "NURSE");
        UUID id = newest(apply(nurse.auth(), null, "CL", d(3), d(4)));
        assertDenied(a, "delete from hra.leave_request");
        assertDenied(a, "delete from hra.leave_request where id = ?", id);
        assertDenied(a, "truncate hra.leave_request");

        String prefix = "insert into hra.leave_request (membership_id, leave_type, from_date, to_date, status, "
                + "created_by, decided_at, decided_by, reason) values (gen_random_uuid(), ";
        String ok = "'CL', current_date, current_date, ";
        // pending with a decider, pending with a decision time, decided without either or with only one
        assertDenied(a, prefix + ok + "'pending', gen_random_uuid(), null, gen_random_uuid(), null)");
        assertDenied(a, prefix + ok + "'pending', gen_random_uuid(), now(), null, null)");
        assertDenied(a, prefix + ok + "'approved', gen_random_uuid(), null, null, null)");
        assertDenied(a, prefix + ok + "'declined', gen_random_uuid(), now(), null, null)");
        assertDenied(a, prefix + ok + "'declined', gen_random_uuid(), null, gen_random_uuid(), null)");
        assertDenied(a, prefix + ok + "'cancelled', gen_random_uuid(), null, null, null)");
        assertDenied(a, prefix + "'XX', current_date, current_date, 'pending', gen_random_uuid(), null, null, null)");
        assertDenied(a, prefix + "'CL', current_date + 1, current_date, 'pending', gen_random_uuid(), null, null, null)");
        assertDenied(a, prefix + "'CL', current_date, current_date + 61, 'pending', gen_random_uuid(), null, null, null)");
        assertDenied(a, prefix + ok + "'pending', gen_random_uuid(), null, null, repeat('x', 201))");
        // and the same shapes that are valid do go in (rolled back), so the denials above are about the checks
        Long inserted = db().inTenant(a.tenantId(), c -> {
            DbAccess.execute(c, prefix + ok + "'pending', gen_random_uuid(), null, null, repeat('x', 200))");
            DbAccess.execute(c, prefix + "'EL', current_date, current_date + 60, 'approved', gen_random_uuid(), now(), "
                    + "gen_random_uuid(), null)");
            return DbAccess.count(c, "select count(*) from hra.leave_request");
        });
        assertThat(inserted).isEqualTo(3);
        assertThat(row(a, id).status()).isEqualTo("pending");
    }

    // ---------------- misc ----------------

    @Test
    void leaveViewJsonKeepsTheContractFieldNames() throws Exception {
        Clinic clinic = hraClinic();
        Staff nurse = staff(clinic, "NURSE");
        JsonNode view = apply(nurse.auth(), null, "CL", d(2), d(2));
        for (String key : new String[] {"types", "requests", "balances", "applicants", "pendingCount", "canApprove"}) {
            assertThat(view.has(key)).as(key).isTrue();
        }
        JsonNode r = view.get("requests").get(0);
        for (String key : new String[] {"id", "membershipId", "name", "roleLabel", "type", "typeName", "from", "to",
                "days", "status", "askedAt", "decidedAt", "decidedByName", "affectedAppointments"}) {
            assertThat(r.has(key)).as(key).isTrue();
        }
        Map<String, Object> remaining = new LinkedHashMap<>();
        view.get("balances").get(0).get("remaining").fieldNames().forEachRemaining(n -> remaining.put(n, true));
        assertThat(remaining.keySet()).containsExactlyInAnyOrder("CL", "SL", "EL");
    }
}
