package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-20 Staff, roster, attendance and holidays on the real {@code sahaj_test} database: module gate and
 * menu, summary, 7-day roster (staff cells, doctors derived from slots and holidays), owner cell edits,
 * publish, attendance marking, audit and row level security of the three {@code hra} tables.
 */
@OnTestDatabase
class StaffRosterAttendanceIT extends SweepSupport {

    private static final String HRA = "/api/v1/hra";
    private static final String MODULE_HRA = "/api/v1/platform/modules/hra";
    private static final String TEAM = "/api/v1/platform/team";
    private static final String ME = "/api/v1/platform/me";
    private static final String HOLIDAYS = "/api/v1/platform/clinic/holidays";
    private static final String SLOTS = "/api/v1/opd/doctor-slots";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    // ---------------- helpers ----------------

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    private Clinic hraClinic() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":true}", 200);
        return clinic;
    }

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private UUID ownerMembership(Clinic clinic) throws Exception {
        return UUID.fromString(ok(HttpMethod.GET, ME, clinic.owner(), null, 200).get("membershipId").asText());
    }

    private JsonNode roster(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, HRA + "/roster", clinic.owner(), null, 200);
    }

    private static JsonNode rowOf(JsonNode view, UUID membershipId) {
        for (JsonNode r : view.get("rows")) {
            if (r.get("membershipId").asText().equals(membershipId.toString())) {
                return r;
            }
        }
        return null;
    }

    private static String cellBody(UUID id, LocalDate date, String shift) {
        return "{\"membershipId\":\"" + id + "\",\"date\":\"" + date + "\",\"shift\":\"" + shift + "\"}";
    }

    private JsonNode setCell(Clinic clinic, UUID member, LocalDate date, String shift, int expected) throws Exception {
        return ok(HttpMethod.PUT, HRA + "/roster/cells", clinic.owner(), cellBody(member, date, shift), expected);
    }

    private String shiftOf(Clinic clinic, UUID member, LocalDate date) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select shift from hra.roster_shift where membership_id = ? and work_date = ?")) {
                ps.setObject(1, member);
                ps.setObject(2, date);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private JsonNode mark(RequestPostProcessor auth, UUID member, String status, int expected) throws Exception {
        return ok(HttpMethod.PUT, HRA + "/attendance/" + member, auth, "{\"status\":\"" + status + "\"}", expected);
    }

    private record Att(String status, Time markedTime, long version) {
    }

    private Att attendanceRow(Clinic clinic, UUID member) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select status, marked_time, version from hra.attendance where membership_id = ? and work_date = ?")) {
                ps.setObject(1, member);
                ps.setObject(2, today());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Att(rs.getString(1), rs.getTime(2), rs.getLong(3)) : null;
                }
            }
        });
    }

    private List<JsonNode> activeTeam(Clinic clinic) throws Exception {
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode m : ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200)) {
            if ("ACTIVE".equals(m.get("status").asText())) {
                active.add(m);
            }
        }
        return active;
    }

    private static int mockupDay(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : d.getDayOfWeek().getValue();
    }

    private void assertProblemType(JsonNode p, String suffix) {
        assertThat(p.get("type").asText()).endsWith(suffix);
    }

    private void deactivate(Clinic clinic, UUID member) throws Exception {
        ok(HttpMethod.POST, TEAM + "/" + member + "/deactivate", clinic.owner(), null, 200);
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

    private void assertDenied(Clinic clinic, String sql) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql);
            return null;
        })).as(sql).isInstanceOf(SQLException.class);
    }

    private String publishedAt(Clinic clinic) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.text(c, "select published_at::text from hra.roster_state"));
    }

    private int updateCount(Clinic clinic, String sql) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                return ps.executeUpdate();
            }
        });
    }

    // ---------------- AC1 module gate and menu ----------------

    @Test
    void hraOffByDefaultAllSixEndpointsAreModuleOffAndMenuHasNoStaff() throws Exception {
        Clinic clinic = newClinic();
        Member nurse = addMember(clinic, "NURSE");
        String cell = cellBody(nurse.membershipId(), today(), "M");
        assertProblemType(problem(call(HttpMethod.GET, HRA + "/summary", clinic.owner(), null), 403), "module-off");
        assertProblemType(problem(call(HttpMethod.GET, HRA + "/roster", clinic.owner(), null), 403), "module-off");
        assertProblemType(problem(call(HttpMethod.PUT, HRA + "/roster/cells", clinic.owner(), cell), 403), "module-off");
        assertProblemType(problem(call(HttpMethod.POST, HRA + "/roster/publish", clinic.owner(), null), 403), "module-off");
        assertProblemType(problem(call(HttpMethod.GET, HRA + "/attendance", clinic.owner(), null), 403), "module-off");
        assertProblemType(problem(call(HttpMethod.PUT, HRA + "/attendance/" + nurse.membershipId(), clinic.owner(),
                "{\"status\":\"in\"}"), 403), "module-off");
        for (String role : ALL_ROLES) {
            assertProblemType(problem(call(HttpMethod.GET, HRA + "/summary", clinic.as(role), null), 403), "module-off");
            assertThat(ok(HttpMethod.GET, ME, clinic.as(role), null, 200).get("menu").toString())
                    .as(role).doesNotContain("staff");
        }
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isZero();
        assertThat(count(clinic, "select count(*) from hra.attendance")).isZero();
        assertThat(count(clinic, "select count(*) from hra.roster_state")).isZero();
    }

    @Test
    void ownerSwitchesHraOnMenuGetsStaffForEveryRoleAndEndpointsWorkThenOffAgain() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":true}", 200);
        for (String role : ALL_ROLES) {
            List<String> items = new ArrayList<>();
            ok(HttpMethod.GET, ME, clinic.as(role), null, 200).get("menu").forEach(n -> items.add(n.asText()));
            assertThat(items).as(role).contains("staff");
            ok(HttpMethod.GET, HRA + "/summary", clinic.as(role), null, 200);
            ok(HttpMethod.GET, HRA + "/roster", clinic.as(role), null, 200);
            ok(HttpMethod.GET, HRA + "/attendance", clinic.as(role), null, 200);
        }
        ok(HttpMethod.PUT, MODULE_HRA, clinic.owner(), "{\"on\":false}", 200);
        problem(call(HttpMethod.GET, HRA + "/roster", clinic.owner(), null), 403);
        assertThat(ok(HttpMethod.GET, ME, clinic.owner(), null, 200).get("menu").toString()).doesNotContain("staff");
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get(HRA + "/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get(HRA + "/roster")).andExpect(status().isUnauthorized());
        mvc.perform(get(HRA + "/attendance")).andExpect(status().isUnauthorized());
    }

    // ---------------- Summary ----------------

    @Test
    void summaryCountsStaffDoctorsOthersAndPresentLate() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        Member desk = addMember(clinic, "FRONT_DESK");
        List<JsonNode> team = activeTeam(clinic);
        long doctors = team.stream().filter(m -> m.get("role").asText().contains("DOCTOR")).count();
        assertThat(team.size()).isGreaterThanOrEqualTo(4); // owner, doctor, nurse, front desk
        assertThat(doctors).isGreaterThanOrEqualTo(1);
        JsonNode s = ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200);
        assertThat(s.get("staff").asInt()).isEqualTo(team.size());
        assertThat(s.get("doctors").asInt()).isEqualTo((int) doctors);
        assertThat(s.get("others").asInt()).isEqualTo(team.size() - (int) doctors);
        assertThat(s.get("presentToday").asInt()).isZero();
        assertThat(s.get("lateToday").asInt()).isZero();

        mark(clinic.owner(), nurse.membershipId(), "in", 200);
        mark(clinic.owner(), desk.membershipId(), "late", 200);
        mark(clinic.owner(), clinic.doctorMembershipId(), "abs", 200);
        s = ok(HttpMethod.GET, HRA + "/summary", clinic.as("NURSE"), null, 200);
        assertThat(s.get("presentToday").asInt()).isEqualTo(2);
        assertThat(s.get("lateToday").asInt()).isEqualTo(1);
    }

    @Test
    void inactiveMembersAreExcludedFromSummaryRosterAndAttendanceAndPresentCount() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        Member desk = addMember(clinic, "FRONT_DESK");
        mark(clinic.owner(), desk.membershipId(), "in", 200);
        int before = ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200).get("staff").asInt();
        deactivate(clinic, desk.membershipId());
        JsonNode s = ok(HttpMethod.GET, HRA + "/summary", clinic.owner(), null, 200);
        assertThat(s.get("staff").asInt()).isEqualTo(before - 1);
        assertThat(s.get("others").asInt()).isEqualTo(before - 1 - s.get("doctors").asInt());
        assertThat(s.get("presentToday").asInt()).as("an inactive member's mark is not counted").isZero();
        assertThat(rowOf(roster(clinic), desk.membershipId())).isNull();
        assertThat(rowOf(roster(clinic), nurse.membershipId())).isNotNull();
        assertThat(rowOf(ok(HttpMethod.GET, HRA + "/attendance", clinic.owner(), null, 200), desk.membershipId())).isNull();
    }

    // ---------------- Roster read ----------------

    @Test
    void rosterIsSevenIstDaysFromTodayWithEveryActiveMemberAndStaffDefaultOff() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        Member desk = addMember(clinic, "FRONT_DESK");
        JsonNode r = roster(clinic);
        assertThat(r.get("days")).hasSize(7);
        LocalDate start = LocalDate.parse(r.get("days").get(0).asText());
        assertThat(start).isBetween(today().minusDays(1), today().plusDays(1)); // tolerant of IST midnight
        for (int i = 0; i < 7; i++) {
            assertThat(r.get("days").get(i).asText()).isEqualTo(start.plusDays(i).toString());
        }
        assertThat(r.get("shifts")).hasSize(4);
        assertThat(r.get("shifts").get(0).get("key").asText()).isEqualTo("M");
        assertThat(r.get("shifts").get(0).get("time").asText()).isEqualTo("08:00 – 14:00");
        assertThat(r.get("shifts").get(1).get("time").asText()).isEqualTo("14:00 – 20:00");
        assertThat(r.get("shifts").get(2).get("time").asText()).isEqualTo("20:00 – 08:00");
        assertThat(r.get("published").asBoolean()).isFalse();
        assertThat(r.get("publishedAt").isNull()).isTrue();
        assertThat(r.get("rows")).hasSize(activeTeam(clinic).size());
        for (UUID id : new UUID[] {nurse.membershipId(), desk.membershipId(), ownerMembership(clinic)}) {
            JsonNode row = rowOf(r, id);
            assertThat(row).isNotNull();
            assertThat(row.get("doctor").asBoolean()).isFalse();
            assertThat(row.get("cells")).hasSize(7);
            for (int i = 0; i < 7; i++) {
                assertThat(row.get("cells").get(i).get("date").asText()).isEqualTo(start.plusDays(i).toString());
                assertThat(row.get("cells").get(i).get("kind").asText()).isEqualTo("SHIFT");
                assertThat(row.get("cells").get(i).get("shift").asText()).isEqualTo("X");
            }
        }
        assertThat(rowOf(r, nurse.membershipId()).get("roleLabel").asText()).isEqualTo("Nurse");
        assertThat(rowOf(r, desk.membershipId()).get("roleLabel").asText()).isEqualTo("Front desk");
        // reads write nothing
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isZero();
        assertThat(count(clinic, "select count(*) from hra.roster_state")).isZero();
        // every role reads the same roster
        for (String role : ALL_ROLES) {
            assertThat(ok(HttpMethod.GET, HRA + "/roster", clinic.as(role), null, 200).get("rows"))
                    .as(role).hasSize(r.get("rows").size());
        }
    }

    @Test
    void doctorCellsAreOpdOrOffDerivedFromDoctorSlotsAndNeverStored() throws Exception {
        Clinic clinic = hraClinic();
        UUID doctor = clinic.doctorMembershipId();
        JsonNode slots = null;
        for (JsonNode s : ok(HttpMethod.GET, SLOTS, clinic.owner(), null, 200)) {
            if (s.get("doctorMembershipId").asText().equals(doctor.toString())) {
                slots = s;
            }
        }
        assertThat(slots).isNotNull();
        assertDoctorMatches(roster(clinic), doctor, slots);

        String body = "{\"doctorMembershipId\":\"" + doctor + "\",\"slotMinutes\":15,\"workingDays\":[1,2,3,4,5],"
                + "\"walkInEveryNth\":0,\"maxOnlinePerDay\":0,\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"13:00\"}]}";
        JsonNode saved = ok(HttpMethod.PUT, SLOTS + "/" + doctor, clinic.owner(), body, 200);
        JsonNode r = roster(clinic);
        assertDoctorMatches(r, doctor, saved);
        int off = 0;
        for (JsonNode c : rowOf(r, doctor).get("cells")) {
            if ("OFF".equals(c.get("kind").asText())) {
                off++;
                int d = mockupDay(LocalDate.parse(c.get("date").asText()));
                assertThat(d == 0 || d == 6).isTrue();
            }
            assertThat(c.get("shift") == null || c.get("shift").isNull()).isTrue();
        }
        assertThat(off).as("a week always contains one Saturday and one Sunday").isEqualTo(2);
        assertThat(rowOf(r, doctor).get("doctor").asBoolean()).isTrue();
        assertThat(rowOf(r, doctor).get("roleLabel").asText()).isEqualTo("Doctor");
        assertThat(count(clinic, "select count(*) from hra.roster_shift where membership_id = ?", doctor)).isZero();
    }

    private void assertDoctorMatches(JsonNode roster, UUID doctor, JsonNode slots) {
        List<Integer> working = new ArrayList<>();
        slots.get("workingDays").forEach(n -> working.add(n.asInt()));
        JsonNode cells = rowOf(roster, doctor).get("cells");
        for (int i = 0; i < 7; i++) {
            LocalDate d = LocalDate.parse(cells.get(i).get("date").asText());
            String expected = working.contains(mockupDay(d)) ? "OPD" : "OFF";
            assertThat(cells.get(i).get("kind").asText()).as(d.toString()).isEqualTo(expected);
        }
    }

    @Test
    void closedClinicHolidayTurnsTheDoctorDayOffButNotStaffOrPartialHolidays() throws Exception {
        Clinic clinic = hraClinic();
        UUID doctor = clinic.doctorMembershipId();
        Member nurse = addMember(clinic, "NURSE");
        LocalDate closed = today().plusDays(2);
        LocalDate partial = today().plusDays(4);
        assertThat(rowOf(roster(clinic), doctor).get("cells").get(2).get("kind").asText()).isEqualTo("OPD");
        JsonNode h = ok(HttpMethod.POST, HOLIDAYS, clinic.owner(),
                "{\"date\":\"" + closed + "\",\"name\":\"Clinic closed\",\"type\":\"CLOSED\"}", 201);
        ok(HttpMethod.POST, HOLIDAYS, clinic.owner(),
                "{\"date\":\"" + partial + "\",\"name\":\"Half day\",\"type\":\"MORNING_ONLY\"}", 201);
        JsonNode r = roster(clinic);
        assertThat(rowOf(r, doctor).get("cells").get(2).get("kind").asText()).isEqualTo("OFF");
        assertThat(rowOf(r, doctor).get("cells").get(1).get("kind").asText()).isEqualTo("OPD");
        assertThat(rowOf(r, doctor).get("cells").get(4).get("kind").asText()).isEqualTo("OPD");
        assertThat(rowOf(r, nurse.membershipId()).get("cells").get(2).get("kind").asText()).isEqualTo("SHIFT");
        ok(HttpMethod.DELETE, HOLIDAYS + "/" + h.get("id").asText(), clinic.owner(), null, 204);
        assertThat(rowOf(roster(clinic), doctor).get("cells").get(2).get("kind").asText()).isEqualTo("OPD");
    }

    // ---------------- PUT /roster/cells ----------------

    @Test
    void ownerCyclesMorningEveningNightOffOneRowPerCellUpsert() throws Exception {
        Clinic clinic = hraClinic();
        UUID id = addMember(clinic, "NURSE").membershipId();
        LocalDate day = today().plusDays(1);
        assertThat(shiftOf(clinic, id, day)).isNull();
        for (String s : new String[] {"M", "E", "N", "X"}) {
            JsonNode r = setCell(clinic, id, day, s, 200);
            assertThat(rowOf(r, id).get("cells").get(1).get("shift").asText()).isEqualTo(s);
            assertThat(rowOf(r, id).get("cells").get(0).get("shift").asText()).as("other days untouched").isEqualTo("X");
            assertThat(shiftOf(clinic, id, day)).isEqualTo(s);
            assertThat(count(clinic, "select count(*) from hra.roster_shift where membership_id = ? and work_date = ?",
                    id, day)).isEqualTo(1);
            assertThat(rowOf(roster(clinic), id).get("cells").get(1).get("shift").asText()).isEqualTo(s);
        }
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isEqualTo(1);
        // first and last allowed day, and the owner's own cell
        setCell(clinic, id, today(), "M", 200);
        setCell(clinic, id, today().plusDays(6), "N", 200);
        setCell(clinic, ownerMembership(clinic), today(), "E", 200);
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isEqualTo(4);
        assertThat((long) count(clinic, "select count(*) from hra.roster_shift where updated_by = ?", clinic.ownerUserId()))
                .isEqualTo(4);
    }

    @Test
    void cellEditRejectsDoctorsDatesOutsideTheWeekUnknownShiftsAndBadBodies() throws Exception {
        Clinic clinic = hraClinic();
        UUID id = addMember(clinic, "NURSE").membershipId();
        Member visiting = addMember(clinic, "VISITING_DOCTOR");
        String put = HRA + "/roster/cells";
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(clinic.doctorMembershipId(), today(), "M")), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(visiting.membershipId(), today(), "M")), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(id, today().minusDays(1), "M")), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(id, today().plusDays(7), "M")), 400);
        for (String bad : new String[] {"Z", "m", "MM", ""}) {
            JsonNode p = problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(id, today(), bad)), 400);
            assertThat(p.get("fieldErrors").get(0).get("field").asText()).isEqualTo("shift");
        }
        problem(call(HttpMethod.PUT, put, clinic.owner(), "{\"membershipId\":\"" + id + "\",\"date\":\"" + today() + "\"}"), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), "{\"membershipId\":\"" + id + "\",\"shift\":\"M\"}"), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), "{\"date\":\"" + today() + "\",\"shift\":\"M\"}"), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(),
                "{\"membershipId\":\"" + id + "\",\"date\":\"not-a-date\",\"shift\":\"M\"}"), 400);
        problem(call(HttpMethod.PUT, put, clinic.owner(), "{not json"), 400);
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'ROSTER_CELL_CHANGED'")).isZero();
    }

    @Test
    void cellEditUnknownOtherTenantAndInactiveMembersAre404() throws Exception {
        Clinic clinic = hraClinic();
        Clinic other = hraClinic();
        Member foreign = addMember(other, "NURSE");
        Member gone = addMember(clinic, "NURSE");
        deactivate(clinic, gone.membershipId());
        String put = HRA + "/roster/cells";
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(UUID.randomUUID(), today(), "M")), 404);
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(foreign.membershipId(), today(), "M")), 404);
        problem(call(HttpMethod.PUT, put, clinic.owner(), cellBody(gone.membershipId(), today(), "M")), 404);
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isZero();
        assertThat(count(other, "select count(*) from hra.roster_shift")).isZero();
    }

    @Test
    void nonOwnersCannotEditCellsOrPublish() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, HRA + "/roster/cells", clinic.as(role), cellBody(nurse.membershipId(), today(), "M")), 403);
            problem(call(HttpMethod.POST, HRA + "/roster/publish", clinic.as(role), null), 403);
        }
        assertThat(count(clinic, "select count(*) from hra.roster_shift")).isZero();
        assertThat(count(clinic, "select count(*) from hra.roster_state")).isZero();
    }

    // ---------------- Publish ----------------

    @Test
    void ownerPublishesSecondCallKeepsFirstTimeAndByAndEditsKeepItPublished() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        assertThat(roster(clinic).get("published").asBoolean()).isFalse();
        JsonNode first = ok(HttpMethod.POST, HRA + "/roster/publish", clinic.owner(), null, 200);
        assertThat(first.get("published").asBoolean()).isTrue();
        assertThat(first.get("publishedAt").isNull()).isFalse();
        String firstAt = publishedAt(clinic);
        assertThat(count(clinic, "select count(*) from hra.roster_state where published_by = ?", clinic.ownerUserId()))
                .isEqualTo(1);

        JsonNode second = ok(HttpMethod.POST, HRA + "/roster/publish", clinic.owner(), null, 200);
        assertThat(second.get("published").asBoolean()).isTrue();
        assertThat(second.get("publishedAt").asText()).isEqualTo(first.get("publishedAt").asText());
        assertThat(publishedAt(clinic)).isEqualTo(firstAt);
        assertThat(count(clinic, "select count(*) from hra.roster_state")).isEqualTo(1);
        assertThat(audit(clinic, "ROSTER_PUBLISHED")).hasSize(1);

        JsonNode afterEdit = setCell(clinic, nurse.membershipId(), today(), "N", 200);
        assertThat(afterEdit.get("published").asBoolean()).isTrue();
        assertThat(roster(clinic).get("published").asBoolean()).isTrue();
        assertThat(publishedAt(clinic)).isEqualTo(firstAt);
        for (String role : ALL_ROLES) {
            assertThat(ok(HttpMethod.GET, HRA + "/roster", clinic.as(role), null, 200).get("published").asBoolean())
                    .as(role).isTrue();
        }
    }

    // ---------------- Attendance ----------------

    @Test
    void attendanceListsActiveMembersNotMarkedAndCanMarkOnlyForOwnerAndFrontDesk() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        Member gone = addMember(clinic, "BILLING");
        deactivate(clinic, gone.membershipId());
        JsonNode a = ok(HttpMethod.GET, HRA + "/attendance", clinic.owner(), null, 200);
        assertThat(LocalDate.parse(a.get("date").asText())).isBetween(today().minusDays(1), today().plusDays(1));
        assertThat(a.get("lateAfterMinutes").asInt()).isEqualTo(15);
        assertThat(a.get("rows")).hasSize(activeTeam(clinic).size());
        assertThat(rowOf(a, gone.membershipId())).isNull();
        for (JsonNode row : a.get("rows")) {
            assertThat(row.get("status").isNull()).isTrue();
            assertThat(row.get("markedTime").isNull()).isTrue();
        }
        assertThat(rowOf(a, nurse.membershipId()).get("roleLabel").asText()).isEqualTo("Nurse");
        for (String role : ALL_ROLES) {
            boolean expected = role.equals("OWNER") || role.equals("FRONT_DESK");
            assertThat(ok(HttpMethod.GET, HRA + "/attendance", clinic.as(role), null, 200).get("canMark").asBoolean())
                    .as(role).isEqualTo(expected);
        }
        assertThat(count(clinic, "select count(*) from hra.attendance")).isZero();
    }

    @Test
    void markingTransitionsRecordIstTimeOnlyForPresentAndLateAndBumpVersion() throws Exception {
        Clinic clinic = hraClinic();
        UUID id = addMember(clinic, "NURSE").membershipId();
        LocalTime before = LocalTime.now(IST).minusMinutes(1);

        JsonNode v = mark(clinic.owner(), id, "in", 200);
        Att att = attendanceRow(clinic, id);
        assertThat(att.status()).isEqualTo("in");
        assertThat(att.markedTime()).isNotNull();
        assertThat(att.version()).isZero();
        LocalTime after = LocalTime.now(IST).plusMinutes(1);
        if (before.isBefore(after)) { // not across IST midnight
            assertThat(att.markedTime().toLocalTime()).isBetween(before, after);
        }
        assertThat(rowOf(v, id).get("status").asText()).isEqualTo("in");
        assertThat(rowOf(v, id).get("markedTime").asText()).matches("\\d\\d:\\d\\d");

        mark(clinic.as("FRONT_DESK"), id, "late", 200);
        att = attendanceRow(clinic, id);
        assertThat(att.status()).isEqualTo("late");
        assertThat(att.markedTime()).isNotNull();
        assertThat(att.version()).isEqualTo(1);

        v = mark(clinic.owner(), id, "abs", 200);
        att = attendanceRow(clinic, id);
        assertThat(att.status()).isEqualTo("abs");
        assertThat(att.markedTime()).isNull();
        assertThat(att.version()).isEqualTo(2);
        assertThat(rowOf(v, id).get("status").asText()).isEqualTo("abs");
        assertThat(rowOf(v, id).get("markedTime").isNull()).isTrue();

        mark(clinic.owner(), id, "in", 200);
        att = attendanceRow(clinic, id);
        assertThat(att.status()).isEqualTo("in");
        assertThat(att.markedTime()).isNotNull();
        assertThat(att.version()).isEqualTo(3);
        assertThat(count(clinic, "select count(*) from hra.attendance where membership_id = ?", id)).isEqualTo(1);
        UUID other = addMember(clinic, "BILLING").membershipId();
        mark(clinic.owner(), other, "late", 200);
        assertThat(count(clinic, "select count(*) from hra.attendance")).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from hra.attendance where marked_by = ?", clinic.ownerUserId()))
                .isEqualTo(2);
    }

    @Test
    void attendanceMarkRefusedForOtherRolesUnknownInactiveAndBadStatus() throws Exception {
        Clinic clinic = hraClinic();
        Clinic other = hraClinic();
        UUID id = addMember(clinic, "NURSE").membershipId();
        UUID foreign = addMember(other, "NURSE").membershipId();
        Member gone = addMember(clinic, "BILLING");
        deactivate(clinic, gone.membershipId());
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.PUT, HRA + "/attendance/" + id, clinic.as(role), "{\"status\":\"in\"}"), 403);
        }
        problem(call(HttpMethod.PUT, HRA + "/attendance/" + UUID.randomUUID(), clinic.owner(), "{\"status\":\"in\"}"), 404);
        problem(call(HttpMethod.PUT, HRA + "/attendance/" + foreign, clinic.owner(), "{\"status\":\"in\"}"), 404);
        problem(call(HttpMethod.PUT, HRA + "/attendance/" + gone.membershipId(), clinic.owner(), "{\"status\":\"in\"}"), 404);
        for (String bad : new String[] {"{\"status\":\"present\"}", "{\"status\":\"IN\"}", "{\"status\":\"\"}",
                "{\"status\":null}", "{}", "{not json"}) {
            problem(call(HttpMethod.PUT, HRA + "/attendance/" + id, clinic.owner(), bad), 400);
        }
        assertThat(count(clinic, "select count(*) from hra.attendance")).isZero();
        assertThat(count(other, "select count(*) from hra.attendance")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'ATTENDANCE_MARKED'")).isZero();
    }

    // ---------------- Audit ----------------

    @Test
    void cellPublishAndAttendanceAreAuditedWithBeforeAfterAndNoNames() throws Exception {
        Clinic clinic = hraClinic();
        Member nurse = addMember(clinic, "NURSE");
        LocalDate day = today().plusDays(3);
        setCell(clinic, nurse.membershipId(), day, "E", 200);
        setCell(clinic, nurse.membershipId(), day, "N", 200);
        ok(HttpMethod.POST, HRA + "/roster/publish", clinic.owner(), null, 200);
        mark(clinic.owner(), nurse.membershipId(), "late", 200);
        mark(clinic.owner(), nurse.membershipId(), "abs", 200);

        List<String[]> cells = audit(clinic, "ROSTER_CELL_CHANGED");
        assertThat(cells).hasSize(2);
        assertThat(cells.get(0)[0]).isEqualTo("ROSTER_SHIFT");
        assertThat(cells.get(0)[1]).isEqualTo(nurse.membershipId().toString());
        assertThat(cells.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(cells.get(0)[2]).contains("\"X\"").contains(day.toString());
        assertThat(cells.get(0)[3]).contains("\"E\"").contains(day.toString());
        assertThat(cells.get(1)[2]).contains("\"E\"");
        assertThat(cells.get(1)[3]).contains("\"N\"");

        List<String[]> pub = audit(clinic, "ROSTER_PUBLISHED");
        assertThat(pub).hasSize(1);
        assertThat(pub.get(0)[0]).isEqualTo("ROSTER_STATE");
        assertThat(pub.get(0)[2]).contains("false");
        assertThat(pub.get(0)[3]).contains("true").contains("publishedAt");

        List<String[]> att = audit(clinic, "ATTENDANCE_MARKED");
        assertThat(att).hasSize(2);
        assertThat(att.get(0)[0]).isEqualTo("ATTENDANCE");
        assertThat(att.get(0)[1]).isEqualTo(nurse.membershipId().toString());
        assertThat(att.get(0)[2]).as("first mark has no before").isNull();
        assertThat(att.get(0)[3]).contains("late");
        assertThat(att.get(1)[2]).contains("late");
        assertThat(att.get(1)[3]).contains("abs");

        List<String[]> all = new ArrayList<>(cells);
        all.addAll(pub);
        all.addAll(att);
        StringBuilder text = new StringBuilder();
        for (String[] row : all) {
            text.append(row[0]).append('|').append(row[2]).append('|').append(row[3]).append('\n');
        }
        assertThat(text.toString()).doesNotContain("Member", "Asha", "Sahaj test clinic", nurse.mobile());
    }

    // ---------------- Row level security ----------------

    @Test
    void anotherTenantSeesNothingOfThisClinicsRosterPublishOrAttendanceThroughApiOrSql() throws Exception {
        Clinic a = hraClinic();
        Clinic b = hraClinic();
        Member nurseA = addMember(a, "NURSE");
        setCell(a, nurseA.membershipId(), today(), "M", 200);
        ok(HttpMethod.POST, HRA + "/roster/publish", a.owner(), null, 200);
        mark(a.owner(), nurseA.membershipId(), "in", 200);

        JsonNode rb = roster(b);
        assertThat(rowOf(rb, nurseA.membershipId())).isNull();
        assertThat(rb.get("published").asBoolean()).isFalse();
        assertThat(rb.toString()).doesNotContain(nurseA.membershipId().toString());
        JsonNode ab = ok(HttpMethod.GET, HRA + "/attendance", b.owner(), null, 200);
        assertThat(rowOf(ab, nurseA.membershipId())).isNull();
        for (JsonNode row : ab.get("rows")) {
            assertThat(row.get("status").isNull()).isTrue();
        }
        assertThat(ok(HttpMethod.GET, HRA + "/summary", b.owner(), null, 200).get("presentToday").asInt()).isZero();
        problem(call(HttpMethod.PUT, HRA + "/attendance/" + nurseA.membershipId(), b.owner(), "{\"status\":\"abs\"}"), 404);
        problem(call(HttpMethod.PUT, HRA + "/roster/cells", b.owner(), cellBody(nurseA.membershipId(), today(), "N")), 404);
        assertThat(shiftOf(a, nurseA.membershipId(), today())).isEqualTo("M");
        assertThat(attendanceRow(a, nurseA.membershipId()).status()).isEqualTo("in");

        for (String table : new String[] {"hra.roster_shift", "hra.roster_state", "hra.attendance"}) {
            assertThat(count(a, "select count(*) from " + table)).as("A " + table).isEqualTo(1);
            assertThat(count(b, "select count(*) from " + table)).as("B " + table).isZero();
            assertThat(count(b, "select count(*) from " + table + " where tenant_id = ?", a.tenantId())).isZero();
        }
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into hra.roster_shift (tenant_id, membership_id, work_date, shift, updated_by) "
                    + "values (?, gen_random_uuid(), current_date, 'M', gen_random_uuid())", a.tenantId());
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into hra.roster_state (tenant_id, published_at, published_by) "
                    + "values (?, now(), gen_random_uuid())", a.tenantId());
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(updateCount(b, "update hra.attendance set status = 'abs'")).isZero();
        assertThat(updateCount(b, "delete from hra.roster_shift")).isZero();
    }

    @Test
    void withNoTenantBoundTheAppRoleSeesNoRowsOfTheThreeTables() throws Exception {
        Clinic a = hraClinic();
        UUID id = addMember(a, "NURSE").membershipId();
        setCell(a, id, today(), "M", 200);
        ok(HttpMethod.POST, HRA + "/roster/publish", a.owner(), null, 200);
        mark(a.owner(), id, "in", 200);
        for (String table : new String[] {"hra.roster_shift", "hra.roster_state", "hra.attendance"}) {
            long n = db().asApp(c -> DbAccess.count(c, "select count(*) from " + table));
            assertThat(n).as(table).isZero();
        }
    }

    @Test
    void appRoleCannotDeleteAttendanceOrPublishMarkerAndTablesEnforceChecks() throws Exception {
        Clinic a = hraClinic();
        UUID id = addMember(a, "NURSE").membershipId();
        setCell(a, id, today(), "M", 200);
        ok(HttpMethod.POST, HRA + "/roster/publish", a.owner(), null, 200);
        mark(a.owner(), id, "in", 200);
        assertDenied(a, "delete from hra.attendance");
        assertDenied(a, "delete from hra.roster_state");
        assertDenied(a, "truncate hra.attendance");
        assertDenied(a, "truncate hra.roster_state");
        // roster cells are scheduling data: deletable by design (rolled back here)
        assertThat(updateCount(a, "delete from hra.roster_shift")).isEqualTo(1);
        assertThat(attendanceRow(a, id)).isNotNull();
        assertThat(shiftOf(a, id, today())).isEqualTo("M");
        // database checks
        assertDenied(a, "insert into hra.attendance (membership_id, work_date, status, marked_time, marked_by) "
                + "values (gen_random_uuid(), current_date, 'abs', '10:00', gen_random_uuid())");
        assertDenied(a, "insert into hra.attendance (membership_id, work_date, status, marked_time, marked_by) "
                + "values (gen_random_uuid(), current_date, 'in', null, gen_random_uuid())");
        assertDenied(a, "insert into hra.attendance (membership_id, work_date, status, marked_time, marked_by) "
                + "values (gen_random_uuid(), current_date, 'present', '10:00', gen_random_uuid())");
        assertDenied(a, "insert into hra.roster_shift (membership_id, work_date, shift, updated_by) "
                + "values (gen_random_uuid(), current_date, 'Z', gen_random_uuid())");
    }
}
