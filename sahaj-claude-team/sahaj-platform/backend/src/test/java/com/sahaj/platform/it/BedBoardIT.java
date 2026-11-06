package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-24 IPD bed board on the real {@code sahaj_test} database: module state gate, board shape and order, counts,
 * occupied-bed details, the ready and block transitions, bed offering for admission, concurrency, privacy and
 * row level security. Cleaning, Reserved and Blocked beds are set by committed tenant SQL because no story enters
 * them through the app yet. Test plan: docs/test-plans/SAH-24.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class BedBoardIT extends SweepSupport {

    static final String IPD = "/api/v1/ipd";
    static final String BOARD = IPD + "/board";
    static final String BEDS = IPD + "/beds";
    static final String WARDS = IPD + "/wards";
    static final String OPTIONS = IPD + "/admission-options";
    static final String ADMISSIONS = IPD + "/admissions";
    static final String ROOM_LEVEL = IPD + "/room-level";
    static final String TRIAL = "/api/v1/platform/modules/ipd-trial";

    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] READERS = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};
    private static final String[] NON_READERS = {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String[] CHANGERS = {"OWNER", "FRONT_DESK", "NURSE"};
    private static final String[] NON_CHANGERS = {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};

    // ---------------- helpers ----------------

    /** IPD in trial, no settings row yet (the application default is "Use rooms" on, for layout and board). */
    private Clinic ipdClinic() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        return clinic;
    }

    private JsonNode layout(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, IPD + "/layout", clinic.owner(), null, 200);
    }

    private static JsonNode wardIn(JsonNode layout, String name) {
        for (JsonNode w : layout.get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w;
            }
        }
        throw new AssertionError("ward not found " + name);
    }

    private static Set<String> bedIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("beds").forEach(b -> ids.add(b.get("id").asText())));
        return ids;
    }

    private static Set<String> roomIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("rooms").forEach(r -> ids.add(r.get("id").asText())));
        return ids;
    }

    private String addWard(Clinic clinic, String name) throws Exception {
        JsonNode l = ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"" + name + "\"}", 201);
        return wardIn(l, name).get("id").asText();
    }

    private String firstRoom(Clinic clinic, String wardName) throws Exception {
        return wardIn(layout(clinic), wardName).get("rooms").get(0).get("id").asText();
    }

    private String addRoom(Clinic clinic, String wardId) throws Exception {
        Set<String> known = roomIds(layout(clinic));
        Set<String> now = roomIds(ok(HttpMethod.POST, WARDS + "/" + wardId + "/rooms", clinic.owner(), null, 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        return now.iterator().next();
    }

    /** Adds a bed (in the given room, or the ward's first room when null) through the SAH-22 API. */
    private UUID addBed(Clinic clinic, String wardId, String roomId) throws Exception {
        Set<String> known = bedIds(layout(clinic));
        String body = "{\"wardId\":\"" + wardId + "\"" + (roomId == null ? "" : ",\"roomId\":\"" + roomId + "\"") + "}";
        Set<String> now = bedIds(ok(HttpMethod.POST, BEDS, clinic.owner(), body, 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        return UUID.fromString(now.iterator().next());
    }

    /** A hospital with "General ward" (rooms on, Room 101) holding {@code beds} free beds. */
    private record Hospital(Clinic clinic, String wardId, List<UUID> beds) {
    }

    private Hospital hospital(int beds) throws Exception {
        Clinic clinic = ipdClinic();
        String ward = addWard(clinic, "General ward");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < beds; i++) {
            ids.add(addBed(clinic, ward, null));
        }
        return new Hospital(clinic, ward, ids);
    }

    private JsonNode board(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, BOARD, clinic.owner(), null, 200);
    }

    private static List<JsonNode> allBeds(JsonNode board) {
        List<JsonNode> beds = new ArrayList<>();
        board.get("wards").forEach(w -> w.get("rooms").forEach(r -> r.get("beds").forEach(beds::add)));
        return beds;
    }

    private static JsonNode boardBed(JsonNode board, UUID id) {
        for (JsonNode b : allBeds(board)) {
            if (id.toString().equals(b.get("id").asText())) {
                return b;
            }
        }
        throw new AssertionError("bed not on board " + id);
    }

    private static JsonNode boardWard(JsonNode board, String name) {
        for (JsonNode w : board.get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w;
            }
        }
        throw new AssertionError("ward not on board " + name);
    }

    private static boolean absent(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull();
    }

    private static List<String> codes(JsonNode room) {
        List<String> codes = new ArrayList<>();
        room.get("beds").forEach(b -> codes.add(b.get("code").asText()));
        return codes;
    }

    private static String statusOf(JsonNode board, UUID bed) {
        return boardBed(board, bed).get("status").asText();
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

    private void setStatus(Clinic clinic, UUID bed, String status) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = ? where id = ?", status, bed);
    }

    private void expireTrial(Clinic clinic) throws SQLException {
        commitAsTenant(clinic.tenantId(),
                "update platform.module_entitlement set ipd_trial_ends_at = now() - interval '1 day'");
    }

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long audits(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    private long ipdAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_%'");
    }

    private String dbStatus(Clinic clinic, UUID bed) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("select status from ipd.bed where id = ?")) {
                ps.setObject(1, bed);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private long dbVersion(Clinic clinic, UUID bed) throws SQLException {
        return count(clinic, "select version from ipd.bed where id = ?", bed);
    }

    /** Audit rows: entity_type, entity_id, before_json, after_json, oldest first. */
    private List<String[]> audit(Clinic clinic, String action) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select entity_type, entity_id::text, before_json::text, after_json::text "
                            + "from platform.audit_event where action = ? order by at")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                    }
                }
            }
            return rows;
        });
    }

    private static void assertType(JsonNode problem, String suffix) {
        assertThat(problem.get("type").asText()).endsWith(suffix);
    }

    private JsonNode person(Clinic clinic, String name) throws Exception {
        return ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":" + objectMapper.writeValueAsString(name) + ",\"age\":56,\"sex\":\"M\",\"mobile\":\""
                        + uniqueMobile() + "\",\"area\":\"Shivaji Nagar\",\"language\":\"mr\"}", 201);
    }

    private Map<String, Object> admitBody(UUID patientId, UUID doctorId, UUID bedId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patientId);
        m.put("doctorMembershipId", doctorId);
        m.put("admissionType", "planned");
        m.put("bedId", bedId);
        m.put("reason", "Fever");
        m.put("payer", "Self");
        m.put("depositPaise", 1000000);
        m.put("depositMode", "UPI");
        m.put("consentSigned", true);
        return m;
    }

    /** Admits a patient (given name) into the bed; returns the 201 body. */
    private JsonNode admit(Clinic clinic, String patientName, UUID bed) throws Exception {
        UUID patientId = UUID.fromString(person(clinic, patientName).get("id").asText());
        return ok(HttpMethod.POST, ADMISSIONS, clinic.owner(),
                objectMapper.writeValueAsString(admitBody(patientId, clinic.doctorMembershipId(), bed)), 201);
    }

    private org.springframework.test.web.servlet.ResultActions tryAdmit(Clinic clinic, UUID bed) throws Exception {
        Map<String, Object> m = admitBody(newPatient(clinic), clinic.doctorMembershipId(), bed);
        return call(HttpMethod.POST, ADMISSIONS, clinic.owner(), objectMapper.writeValueAsString(m));
    }

    private Set<String> offeredBeds(Clinic clinic) throws Exception {
        Set<String> ids = new HashSet<>();
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds").forEach(b -> ids.add(b.get("id").asText()));
        return ids;
    }

    private record Resp(int status, String body) {
    }

    private List<Resp> parallel(Clinic clinic, List<String[]> pathAndBody) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(pathAndBody.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (String[] pb : pathAndBody) {
                Callable<Resp> attempt = () -> {
                    start.await();
                    var req = post(java.net.URI.create(pb[0])).with(clinic.owner());
                    if (pb[1] != null) {
                        req = req.contentType(MediaType.APPLICATION_JSON).content(pb[1]);
                    }
                    var r = mvc.perform(req).andReturn().getResponse();
                    return new Resp(r.getStatus(), r.getContentAsString());
                };
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            List<Resp> result = new ArrayList<>();
            for (Future<Resp> f : futures) {
                result.add(f.get());
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- AC1/AC7 module state ----------------

    @Test
    void ipdOffTheThreeEndpointsAre403ModuleOffAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        UUID id = UUID.randomUUID();
        List<Object[]> calls = List.of(new Object[] {HttpMethod.GET, BOARD},
                new Object[] {HttpMethod.POST, BEDS + "/" + id + "/ready"},
                new Object[] {HttpMethod.POST, BEDS + "/" + id + "/block"});
        for (String role : new String[] {"OWNER", "NURSE", "FRONT_DESK", "DOCTOR"}) {
            for (Object[] c : calls) {
                JsonNode p = problem(call((HttpMethod) c[0], (String) c[1], clinic.as(role), null), 403);
                if ("OWNER".equals(role)) {
                    assertType(p, "module-off");
                }
            }
        }
        assertThat(ipdAudits(clinic)).isZero();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        ok(HttpMethod.GET, BOARD, clinic.owner(), null, 200);
    }

    @Test
    void readonlyBoardReadsWithStateReadonlyAndNoActionsAndReadyBlockAre409IpdReadonly() throws Exception {
        Hospital h = hospital(2);
        UUID cleanBed = h.beds().get(0);
        UUID blockedBed = h.beds().get(1);
        setStatus(h.clinic(), cleanBed, "clean");
        setStatus(h.clinic(), blockedBed, "blk");
        JsonNode live = board(h.clinic());
        assertThat(live.get("state").asText()).isEqualTo("trial");
        assertThat(live.get("canAdmit").asBoolean()).isTrue();
        assertThat(live.get("canChangeBeds").asBoolean()).isTrue();
        expireTrial(h.clinic());
        long audits = ipdAudits(h.clinic());

        for (String role : READERS) {
            JsonNode b = ok(HttpMethod.GET, BOARD, h.clinic().as(role), null, 200);
            assertThat(b.get("state").asText()).as(role).isEqualTo("readonly");
            assertThat(b.get("canAdmit").asBoolean()).as(role).isFalse();
            assertThat(b.get("canChangeBeds").asBoolean()).as(role).isFalse();
            assertThat(statusOf(b, cleanBed)).isEqualTo("clean");
        }
        for (String action : new String[] {"ready", "block"}) {
            for (UUID bed : new UUID[] {cleanBed, blockedBed}) {
                JsonNode p = problem(call(HttpMethod.POST, BEDS + "/" + bed + "/" + action, h.clinic().owner(), null), 409);
                assertType(p, "ipd-readonly");
                assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
            }
        }
        assertThat(dbStatus(h.clinic(), cleanBed)).isEqualTo("clean");
        assertThat(dbStatus(h.clinic(), blockedBed)).isEqualTo("blk");
        assertThat(ipdAudits(h.clinic())).isEqualTo(audits);
    }

    // ---------------- AC7 GET /board: roles ----------------

    @Test
    void boardIsOpenToFourRolesAndOtherFourGet403AndAnonymousGets401() throws Exception {
        Hospital h = hospital(1);
        for (String role : READERS) {
            JsonNode b = ok(HttpMethod.GET, BOARD, h.clinic().as(role), null, 200);
            assertThat(b.get("wards")).as(role).hasSize(1);
            boolean changer = !"DOCTOR".equals(role);
            assertThat(b.get("canAdmit").asBoolean()).as(role + " canAdmit").isEqualTo(changer);
            assertThat(b.get("canChangeBeds").asBoolean()).as(role + " canChangeBeds").isEqualTo(changer);
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, BOARD, h.clinic().as(role), null), 403);
        }
        mvc.perform(get(BOARD)).andExpect(status().isUnauthorized());
        mvc.perform(post(BEDS + "/" + h.beds().get(0) + "/ready")).andExpect(status().isUnauthorized());
        mvc.perform(post(BEDS + "/" + h.beds().get(0) + "/block")).andExpect(status().isUnauthorized());
    }

    // ---------------- AC2 empty, counts, order ----------------

    @Test
    void emptyClinicHasNoWardsAndZeroCounts() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode b = board(clinic);
        assertThat(b.get("wards")).isEmpty();
        for (String k : new String[] {"free", "occ", "clean", "res", "blk"}) {
            assertThat(b.get("counts").get(k).asInt()).as(k).isZero();
        }
        assertThat(b.get("state").asText()).isEqualTo("trial");
        assertThat(b.get("roomLevel").asBoolean()).isTrue();
    }

    /**
     * With no ipd.config row the layout (IpdService.DEFAULT_CONFIG) and the board both use rooms on, so wards show
     * "Room 101". Regression guard for the earlier board default of false (fixed in the application).
     */
    @Test
    void withNoSettingsRowTheBoardUsesTheDefaultRoomsOn() throws Exception {
        Clinic clinic = ipdClinic();
        String ward = addWard(clinic, "General ward");
        addBed(clinic, ward, null);
        assertThat(layout(clinic).get("roomLevel").asBoolean()).isTrue();
        JsonNode b = board(clinic);
        assertThat(b.get("roomLevel").asBoolean()).isTrue();
        assertThat(boardWard(b, "General ward").get("rooms").get(0).get("name").asText()).isEqualTo("Room 101");
    }

    @Test
    void countsPerStatusMatchTheBedsAcrossWards() throws Exception {
        Hospital h = hospital(7);
        String second = addWard(h.clinic(), "Second ward");
        UUID extra = addBed(h.clinic(), second, null);
        List<UUID> beds = new ArrayList<>(h.beds());
        beds.add(extra);
        // 8 beds: 2 occ (admitted), 2 clean, 1 res, 2 blk, 1 free
        admit(h.clinic(), "Count One", beds.get(0));
        admit(h.clinic(), "Count Two", extra);
        setStatus(h.clinic(), beds.get(1), "clean");
        setStatus(h.clinic(), beds.get(2), "clean");
        setStatus(h.clinic(), beds.get(3), "res");
        setStatus(h.clinic(), beds.get(4), "blk");
        setStatus(h.clinic(), beds.get(5), "blk");
        JsonNode b = board(h.clinic());
        JsonNode c = b.get("counts");
        assertThat(c.get("free").asInt()).isOne();
        assertThat(c.get("occ").asInt()).isEqualTo(2);
        assertThat(c.get("clean").asInt()).isEqualTo(2);
        assertThat(c.get("res").asInt()).isOne();
        assertThat(c.get("blk").asInt()).isEqualTo(2);
        Map<String, Integer> seen = new LinkedHashMap<>();
        allBeds(b).forEach(x -> seen.merge(x.get("status").asText(), 1, Integer::sum));
        assertThat(seen).containsEntry("free", 1).containsEntry("occ", 2).containsEntry("clean", 2)
                .containsEntry("res", 1).containsEntry("blk", 2);
        assertThat(boardWard(b, "General ward").get("occupied").asInt()).isOne();
        assertThat(boardWard(b, "General ward").get("total").asInt()).isEqualTo(7);
        assertThat(boardWard(b, "Second ward").get("occupied").asInt()).isOne();
        assertThat(boardWard(b, "Second ward").get("total").asInt()).isOne();
    }

    @Test
    void wardsInCreationOrderRoomsGroupedBedsByRoomThenCodeNoRoomGroupLastAndEmptyRoomsHidden() throws Exception {
        Clinic clinic = ipdClinic();
        String alpha = addWard(clinic, "Zulu ward"); // created first, sorts last by name
        String beta = addWard(clinic, "Alpha ward");
        String room101 = firstRoom(clinic, "Zulu ward");
        String room102 = addRoom(clinic, alpha);
        addRoom(clinic, alpha); // Room 103 stays empty and must not be listed
        UUID inRoom102 = addBed(clinic, alpha, room102); // created first but its room is later
        UUID inRoom101a = addBed(clinic, alpha, room101);
        UUID inRoom101b = addBed(clinic, alpha, room101);
        UUID loose = addBed(clinic, alpha, room101);
        commitAsTenant(clinic.tenantId(), "update ipd.bed set room_id = null where id = ?", loose);
        UUID otherWardBed = addBed(clinic, beta, null);

        JsonNode b = board(clinic);
        assertThat(b.get("roomLevel").asBoolean()).isTrue();
        List<String> wardNames = new ArrayList<>();
        b.get("wards").forEach(w -> wardNames.add(w.get("name").asText()));
        assertThat(wardNames).containsExactly("Zulu ward", "Alpha ward");

        JsonNode rooms = boardWard(b, "Zulu ward").get("rooms");
        assertThat(rooms).hasSize(3);
        assertThat(rooms.get(0).get("name").asText()).isEqualTo("Room 101");
        assertThat(rooms.get(0).get("id").asText()).isEqualTo(room101);
        assertThat(codes(rooms.get(0))).hasSize(2).isSorted();
        assertThat(rooms.get(0).get("beds").get(0).get("id").asText()).isEqualTo(inRoom101a.toString());
        assertThat(rooms.get(0).get("beds").get(1).get("id").asText()).isEqualTo(inRoom101b.toString());
        assertThat(rooms.get(1).get("name").asText()).isEqualTo("Room 102");
        assertThat(rooms.get(1).get("id").asText()).isEqualTo(room102);
        assertThat(rooms.get(1).get("beds").get(0).get("id").asText()).isEqualTo(inRoom102.toString());
        JsonNode noRoom = rooms.get(2);
        assertThat(absent(noRoom, "id")).isTrue();
        assertThat(noRoom.get("name").asText()).isEmpty();
        assertThat(noRoom.get("beds")).hasSize(1);
        assertThat(noRoom.get("beds").get(0).get("id").asText()).isEqualTo(loose.toString());
        // the other ward holds its own bed only
        assertThat(allBeds(b)).hasSize(5);
        assertThat(boardWard(b, "Alpha ward").get("rooms").get(0).get("beds").get(0).get("id").asText())
                .isEqualTo(otherWardBed.toString());
        assertThat(boardWard(b, "Zulu ward").get("total").asInt()).isEqualTo(4);
    }

    @Test
    void roomsOffGivesOneGroupWithEmptyNameAndAWardWithNoBedsIsStillListed() throws Exception {
        Clinic clinic = ipdClinic();
        String ward = addWard(clinic, "General ward");
        String empty = addWard(clinic, "Day care");
        String room101 = firstRoom(clinic, "General ward");
        String room102 = addRoom(clinic, ward);
        UUID inRoom102 = addBed(clinic, ward, room102);
        UUID inRoom101 = addBed(clinic, ward, room101);

        // rooms on: the empty ward is listed with total 0 and no rooms
        JsonNode on = board(clinic);
        JsonNode emptyOn = boardWard(on, "Day care");
        assertThat(emptyOn.get("total").asInt()).isZero();
        assertThat(emptyOn.get("occupied").asInt()).isZero();
        assertThat(emptyOn.get("hasFreeBed").asBoolean()).isFalse();
        assertThat(absent(emptyOn, "firstFreeBedId")).isTrue();
        assertThat(emptyOn.get("rooms")).isEmpty();
        assertThat(empty).isNotBlank();

        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        JsonNode off = board(clinic);
        assertThat(off.get("roomLevel").asBoolean()).isFalse();
        JsonNode rooms = boardWard(off, "General ward").get("rooms");
        assertThat(rooms).hasSize(1);
        assertThat(absent(rooms.get(0), "id")).isTrue();
        assertThat(rooms.get(0).get("name").asText()).isEmpty();
        assertThat(rooms.get(0).get("beds")).hasSize(2);
        // bed order is still room then code
        assertThat(rooms.get(0).get("beds").get(0).get("id").asText()).isEqualTo(inRoom101.toString());
        assertThat(rooms.get(0).get("beds").get(1).get("id").asText()).isEqualTo(inRoom102.toString());
        JsonNode emptyOff = boardWard(off, "Day care");
        assertThat(emptyOff.get("total").asInt()).isZero();
        assertThat(emptyOff.get("hasFreeBed").asBoolean()).isFalse();
        for (JsonNode r : emptyOff.get("rooms")) {
            assertThat(r.get("beds")).isEmpty();
            assertThat(r.get("name").asText()).isEmpty();
        }
    }

    // ---------------- AC3 tile content ----------------

    @Test
    void occupiedBedShowsAdmissionPatientDayOneAndDoctorShortNameAndAFreeBedShowsNone() throws Exception {
        Hospital h = hospital(2);
        UUID occBed = h.beds().get(0);
        UUID freeBed = h.beds().get(1);
        JsonNode admission = admit(h.clinic(), "Sunil Jadhav", occBed);
        String doctorName = null;
        for (JsonNode d : ok(HttpMethod.GET, OPTIONS, h.clinic().owner(), null, 200).get("doctors")) {
            if (h.clinic().doctorMembershipId().toString().equals(d.get("membershipId").asText())) {
                doctorName = d.get("name").asText();
            }
        }
        assertThat(doctorName).isNotNull();
        String[] words = doctorName.trim().split("\\s+");
        String expectedShort = words.length <= 2 ? doctorName.trim() : words[0] + " " + words[1];

        JsonNode b = board(h.clinic());
        JsonNode occ = boardBed(b, occBed);
        assertThat(occ.get("status").asText()).isEqualTo("occ");
        assertThat(occ.get("admissionId").asText()).isEqualTo(admission.get("id").asText());
        assertThat(occ.get("patientName").asText()).isEqualTo("Sunil Jadhav");
        assertThat(occ.get("dayNumber").asInt()).isOne();
        assertThat(occ.get("doctorShortName").asText()).isEqualTo(expectedShort).isEqualTo("Dr Asha");
        assertThat(occ.get("categoryKey").asText()).isEqualTo("gen");
        assertThat(occ.get("categoryName").asText()).isEqualTo("General");
        assertThat(occ.get("code").asText()).isEqualTo("101-A");

        JsonNode free = boardWard(b, "General ward").get("rooms").get(0).get("beds").get(1);
        assertThat(free.get("id").asText()).isEqualTo(freeBed.toString());
        assertThat(free.get("status").asText()).isEqualTo("free");
        for (String f : new String[] {"admissionId", "patientName", "dayNumber", "doctorShortName"}) {
            assertThat(absent(free, f)).as("free bed has no " + f).isTrue();
        }
    }

    @Test
    void aDischargedAdmissionOnACleaningBedShowsNoPatient() throws Exception {
        Hospital h = hospital(1);
        UUID bed = h.beds().get(0);
        JsonNode a = admit(h.clinic(), "Left Hospital", bed);
        commitAsTenant(h.clinic().tenantId(), "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid",
                a.get("id").asText());
        setStatus(h.clinic(), bed, "clean");
        JsonNode tile = boardBed(board(h.clinic()), bed);
        assertThat(tile.get("status").asText()).isEqualTo("clean");
        for (String f : new String[] {"admissionId", "patientName", "dayNumber", "doctorShortName"}) {
            assertThat(absent(tile, f)).as(f).isTrue();
        }
        assertThat(board(h.clinic()).get("wards").get(0).get("occupied").asInt()).isZero();
    }

    @Test
    void threeAdmittedPatientsAllGetTheirOwnNamesAndOccupiedCount() throws Exception {
        Hospital h = hospital(4);
        String[] names = {"Alka Deshmukh", "Bhaskar Pawar", "Chitra Naik"};
        Map<UUID, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            admit(h.clinic(), names[i], h.beds().get(i));
            expected.put(h.beds().get(i), names[i]);
        }
        JsonNode b = board(h.clinic());
        expected.forEach((bed, name) -> assertThat(boardBed(b, bed).get("patientName").asText()).isEqualTo(name));
        assertThat(boardWard(b, "General ward").get("occupied").asInt()).isEqualTo(3);
        assertThat(absent(boardBed(b, h.beds().get(3)), "patientName")).isTrue();
        assertThat(b.get("counts").get("occ").asInt()).isEqualTo(3);
    }

    @Test
    void rateEqualsTheCategoryRateAlsoAfterARateChange() throws Exception {
        Hospital h = hospital(2);
        ok(HttpMethod.PUT, BEDS + "/" + h.beds().get(1), h.clinic().owner(), "{\"categoryKey\":\"icu\"}", 200);
        JsonNode l = layout(h.clinic());
        Map<String, Long> rates = new LinkedHashMap<>();
        l.get("categories").forEach(c -> rates.put(c.get("key").asText(), c.get("ratePaise").asLong()));
        JsonNode b = board(h.clinic());
        assertThat(boardBed(b, h.beds().get(0)).get("ratePaise").asLong()).isEqualTo(rates.get("gen")).isEqualTo(80000);
        assertThat(boardBed(b, h.beds().get(1)).get("ratePaise").asLong()).isEqualTo(rates.get("icu")).isEqualTo(500000);
        assertThat(boardBed(b, h.beds().get(1)).get("categoryName").asText()).isEqualTo("ICU");

        ok(HttpMethod.PUT, IPD + "/categories/gen", h.clinic().owner(), "{\"ratePaise\":90000,\"multiplier\":1.5}", 200);
        JsonNode after = board(h.clinic());
        assertThat(boardBed(after, h.beds().get(0)).get("ratePaise").asLong()).isEqualTo(90000);
        assertThat(boardBed(after, h.beds().get(1)).get("ratePaise").asLong()).isEqualTo(500000);
    }

    // ---------------- AC6 first free bed ----------------

    @Test
    void firstFreeBedIsTheFirstFreeInRoomThenCodeOrderAndHasFreeBedFollows() throws Exception {
        Clinic clinic = ipdClinic();
        String ward = addWard(clinic, "General ward");
        String room101 = firstRoom(clinic, "General ward");
        String room102 = addRoom(clinic, ward);
        UUID later = addBed(clinic, ward, room102); // 102-A, created first
        UUID a = addBed(clinic, ward, room101); // 101-B
        UUID b = addBed(clinic, ward, room101); // 101-C
        JsonNode w = boardWard(board(clinic), "General ward");
        assertThat(w.get("hasFreeBed").asBoolean()).isTrue();
        assertThat(w.get("firstFreeBedId").asText()).isEqualTo(a.toString());

        setStatus(clinic, a, "clean");
        assertThat(boardWard(board(clinic), "General ward").get("firstFreeBedId").asText()).isEqualTo(b.toString());
        setStatus(clinic, b, "blk");
        assertThat(boardWard(board(clinic), "General ward").get("firstFreeBedId").asText()).isEqualTo(later.toString());
        setStatus(clinic, later, "res");
        w = boardWard(board(clinic), "General ward");
        assertThat(w.get("hasFreeBed").asBoolean()).isFalse();
        assertThat(absent(w, "firstFreeBedId")).isTrue();
        setStatus(clinic, a, "free");
        w = boardWard(board(clinic), "General ward");
        assertThat(w.get("hasFreeBed").asBoolean()).isTrue();
        assertThat(w.get("firstFreeBedId").asText()).isEqualTo(a.toString());
    }

    // ---------------- AC7 ready ----------------

    @Test
    void readyFromCleaningAndFromBlockedMakesTheBedFreeBumpsVersionAuditsAndReturnsTheBoard() throws Exception {
        Hospital h = hospital(2);
        UUID cleaning = h.beds().get(0);
        UUID blocked = h.beds().get(1);
        setStatus(h.clinic(), cleaning, "clean");
        setStatus(h.clinic(), blocked, "blk");
        long v1 = dbVersion(h.clinic(), cleaning);
        long v2 = dbVersion(h.clinic(), blocked);

        JsonNode afterFirst = ok(HttpMethod.POST, BEDS + "/" + cleaning + "/ready", h.clinic().owner(), null, 200);
        assertThat(statusOf(afterFirst, cleaning)).isEqualTo("free");
        assertThat(statusOf(afterFirst, blocked)).isEqualTo("blk");
        assertThat(afterFirst.get("counts").get("free").asInt()).isOne();
        assertThat(afterFirst.get("counts").get("blk").asInt()).isOne();
        assertThat(afterFirst.get("wards")).isNotNull();
        JsonNode afterSecond = ok(HttpMethod.POST, BEDS + "/" + blocked + "/ready", h.clinic().owner(), null, 200);
        assertThat(statusOf(afterSecond, blocked)).isEqualTo("free");
        assertThat(afterSecond.get("counts").get("free").asInt()).isEqualTo(2);

        assertThat(dbStatus(h.clinic(), cleaning)).isEqualTo("free");
        assertThat(dbStatus(h.clinic(), blocked)).isEqualTo("free");
        assertThat(dbVersion(h.clinic(), cleaning)).isEqualTo(v1 + 1);
        assertThat(dbVersion(h.clinic(), blocked)).isEqualTo(v2 + 1);

        List<String[]> rows = audit(h.clinic(), "IPD_BED_READY");
        assertThat(rows).hasSize(2);
        String[] first = rows.get(0);
        assertThat(first[0]).isEqualTo("IPD_BED");
        assertThat(first[1]).isEqualTo(cleaning.toString());
        assertThat(first[2]).isNull();
        JsonNode after = objectMapper.readTree(first[3]);
        Set<String> fields = new TreeSet<>();
        after.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("bedCode", "bedId", "from", "to");
        assertThat(after.get("bedId").asText()).isEqualTo(cleaning.toString());
        assertThat(after.get("bedCode").asText()).isEqualTo("101-A");
        assertThat(after.get("from").asText()).isEqualTo("clean");
        assertThat(after.get("to").asText()).isEqualTo("free");
        assertThat(objectMapper.readTree(rows.get(1)[3]).get("from").asText()).isEqualTo("blk");
        assertThat(audits(h.clinic(), "IPD_BED_BLOCKED")).isZero();
    }

    @Test
    void readyFromFreeOccupiedOrReservedIs409BedStateWithTheExactMessage() throws Exception {
        Hospital h = hospital(3);
        UUID free = h.beds().get(0);
        UUID occ = h.beds().get(1);
        UUID res = h.beds().get(2);
        admit(h.clinic(), "Occupant One", occ);
        setStatus(h.clinic(), res, "res");
        long audits = ipdAudits(h.clinic());
        long freeVersion = dbVersion(h.clinic(), free);
        Object[][] cases = {{free, "Available"}, {occ, "Occupied"}, {res, "Reserved"}};
        for (Object[] c : cases) {
            JsonNode p = problem(call(HttpMethod.POST, BEDS + "/" + c[0] + "/ready", h.clinic().owner(), null), 409);
            assertType(p, "bed-state");
            assertThat(p.get("detail").asText()).isEqualTo("This bed cannot be changed from " + c[1] + ".");
        }
        assertThat(dbStatus(h.clinic(), free)).isEqualTo("free");
        assertThat(dbStatus(h.clinic(), occ)).isEqualTo("occ");
        assertThat(dbStatus(h.clinic(), res)).isEqualTo("res");
        assertThat(dbVersion(h.clinic(), free)).isEqualTo(freeVersion);
        assertThat(ipdAudits(h.clinic())).isEqualTo(audits);
        assertThat(audits(h.clinic(), "IPD_BED_READY")).isZero();
    }

    // ---------------- AC7 block ----------------

    @Test
    void blockFromCleaningMakesItBlockedAuditsAndEveryOtherStateIs409() throws Exception {
        Hospital h = hospital(5);
        UUID clean = h.beds().get(0);
        setStatus(h.clinic(), clean, "clean");
        long v = dbVersion(h.clinic(), clean);
        JsonNode b = ok(HttpMethod.POST, BEDS + "/" + clean + "/block", h.clinic().owner(), null, 200);
        assertThat(statusOf(b, clean)).isEqualTo("blk");
        assertThat(b.get("counts").get("blk").asInt()).isOne();
        assertThat(dbStatus(h.clinic(), clean)).isEqualTo("blk");
        assertThat(dbVersion(h.clinic(), clean)).isEqualTo(v + 1);
        List<String[]> rows = audit(h.clinic(), "IPD_BED_BLOCKED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("IPD_BED");
        assertThat(rows.get(0)[1]).isEqualTo(clean.toString());
        JsonNode after = objectMapper.readTree(rows.get(0)[3]);
        Set<String> fields = new TreeSet<>();
        after.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("bedCode", "bedId", "from", "to");
        assertThat(after.get("from").asText()).isEqualTo("clean");
        assertThat(after.get("to").asText()).isEqualTo("blk");

        UUID free = h.beds().get(1);
        UUID occ = h.beds().get(2);
        UUID res = h.beds().get(3);
        admit(h.clinic(), "Occupant Two", occ);
        setStatus(h.clinic(), res, "res");
        Object[][] cases = {{clean, "Blocked"}, {free, "Available"}, {occ, "Occupied"}, {res, "Reserved"}};
        for (Object[] c : cases) {
            JsonNode p = problem(call(HttpMethod.POST, BEDS + "/" + c[0] + "/block", h.clinic().owner(), null), 409);
            assertType(p, "bed-state");
            assertThat(p.get("detail").asText()).isEqualTo("This bed cannot be changed from " + c[1] + ".");
        }
        assertThat(dbStatus(h.clinic(), free)).isEqualTo("free");
        assertThat(dbStatus(h.clinic(), occ)).isEqualTo("occ");
        assertThat(dbStatus(h.clinic(), res)).isEqualTo("res");
        assertThat(audits(h.clinic(), "IPD_BED_BLOCKED")).isOne();
    }

    @Test
    void unknownAndMalformedBedIdsGet404And400ProblemBodies() throws Exception {
        Hospital h = hospital(1);
        long audits = ipdAudits(h.clinic());
        for (String action : new String[] {"ready", "block"}) {
            problem(call(HttpMethod.POST, BEDS + "/" + UUID.randomUUID() + "/" + action, h.clinic().owner(), null), 404);
            for (String bad : new String[] {"not-a-uuid", "123", "00000000-0000-0000-0000"}) {
                JsonNode p = problem(call(HttpMethod.POST, BEDS + "/" + bad + "/" + action, h.clinic().owner(), null), 400);
                assertThat(p.get("title")).isNotNull();
            }
        }
        assertThat(ipdAudits(h.clinic())).isEqualTo(audits);
    }

    @Test
    void ownerFrontDeskAndNurseMayChangeBedsOtherRolesGet403AndNothingChanges() throws Exception {
        Hospital h = hospital(1);
        UUID bed = h.beds().get(0);
        setStatus(h.clinic(), bed, "clean");
        long audits = ipdAudits(h.clinic());
        for (String role : NON_CHANGERS) {
            for (String action : new String[] {"ready", "block"}) {
                problem(call(HttpMethod.POST, BEDS + "/" + bed + "/" + action, h.clinic().as(role), null), 403);
            }
        }
        assertThat(dbStatus(h.clinic(), bed)).isEqualTo("clean");
        assertThat(ipdAudits(h.clinic())).isEqualTo(audits);
        assertThat(audits(h.clinic(), "IPD_BED_READY")).isZero();
        assertThat(audits(h.clinic(), "IPD_BED_BLOCKED")).isZero();

        for (String role : CHANGERS) {
            ok(HttpMethod.POST, BEDS + "/" + bed + "/block", h.clinic().as(role), null, 200);
            assertThat(dbStatus(h.clinic(), bed)).as(role).isEqualTo("blk");
            ok(HttpMethod.POST, BEDS + "/" + bed + "/ready", h.clinic().as(role), null, 200);
            assertThat(dbStatus(h.clinic(), bed)).as(role).isEqualTo("free");
            setStatus(h.clinic(), bed, "clean");
        }
        assertThat(audits(h.clinic(), "IPD_BED_READY")).isEqualTo(3);
        assertThat(audits(h.clinic(), "IPD_BED_BLOCKED")).isEqualTo(3);
        assertThat(ALL_ROLES).hasSize(8);
    }

    // ---------------- AC4 offering and admitting ----------------

    @Test
    void afterReadyTheBedIsOfferedAndAdmittingWorksAndABlockedBedIsNotOfferedAndAdmitIs409BedNotFree() throws Exception {
        Hospital h = hospital(2);
        UUID cleaning = h.beds().get(0);
        UUID blocked = h.beds().get(1);
        setStatus(h.clinic(), cleaning, "clean");
        setStatus(h.clinic(), blocked, "blk");
        assertThat(offeredBeds(h.clinic())).doesNotContain(cleaning.toString(), blocked.toString());
        assertType(problem(tryAdmit(h.clinic(), blocked), 409), "bed-not-free");
        assertType(problem(tryAdmit(h.clinic(), cleaning), 409), "bed-not-free");

        ok(HttpMethod.POST, BEDS + "/" + cleaning + "/ready", h.clinic().owner(), null, 200);
        assertThat(offeredBeds(h.clinic())).contains(cleaning.toString()).doesNotContain(blocked.toString());
        JsonNode a = admit(h.clinic(), "Ready Patient", cleaning);
        assertThat(a.get("id")).isNotNull();
        JsonNode b = board(h.clinic());
        assertThat(statusOf(b, cleaning)).isEqualTo("occ");
        assertThat(boardBed(b, cleaning).get("patientName").asText()).isEqualTo("Ready Patient");
        assertThat(statusOf(b, blocked)).isEqualTo("blk");

        // unblocking offers it again
        ok(HttpMethod.POST, BEDS + "/" + blocked + "/ready", h.clinic().owner(), null, 200);
        assertThat(offeredBeds(h.clinic())).contains(blocked.toString());
    }

    // ---------------- concurrency ----------------

    @Test
    void twoSimultaneousReadyCallsOnTheSameCleanBedGiveOne200AndOne409BedState() throws Exception {
        Hospital h = hospital(1);
        UUID bed = h.beds().get(0);
        setStatus(h.clinic(), bed, "clean");
        long v = dbVersion(h.clinic(), bed);
        String path = BEDS + "/" + bed + "/ready";
        List<Resp> r = parallel(h.clinic(), List.of(new String[] {path, null}, new String[] {path, null}));
        List<Integer> statuses = r.stream().map(Resp::status).sorted().toList();
        // The service takes the clinic's IPD lock and re-reads the bed, so the second call sees Available: 409.
        assertThat(statuses).containsExactly(200, 409);
        for (Resp x : r) {
            if (x.status() == 409) {
                JsonNode p = objectMapper.readTree(x.body());
                assertType(p, "bed-state");
                assertThat(p.get("detail").asText()).isEqualTo("This bed cannot be changed from Available.");
            }
        }
        assertThat(dbStatus(h.clinic(), bed)).isEqualTo("free");
        assertThat(dbVersion(h.clinic(), bed)).isEqualTo(v + 1);
        assertThat(audits(h.clinic(), "IPD_BED_READY")).isOne();
    }

    @Test
    void readyRacingAnAdmitOnTheSameBedLeavesAConsistentStateWithNo500() throws Exception {
        Hospital h = hospital(1);
        UUID bed = h.beds().get(0);
        setStatus(h.clinic(), bed, "clean");
        Map<String, Object> admitPayload = admitBody(newPatient(h.clinic()), h.clinic().doctorMembershipId(), bed);
        List<Resp> r = parallel(h.clinic(), List.of(new String[] {BEDS + "/" + bed + "/ready", null},
                new String[] {ADMISSIONS, objectMapper.writeValueAsString(admitPayload)}));
        Resp ready = r.get(0);
        Resp admit = r.get(1);
        assertThat(ready.status()).as("ready never fails here: the bed is Cleaning or Available").isEqualTo(200);
        assertThat(admit.status()).as("admit is 201 (ready first) or 409 (admit first); never 500")
                .isIn(201, 409);
        long admitted = count(h.clinic(), "select count(*) from ipd.admission where bed_id = ? and status = 'ADMITTED'", bed);
        if (admit.status() == 201) {
            assertThat(dbStatus(h.clinic(), bed)).isEqualTo("occ");
            assertThat(admitted).isOne();
        } else {
            assertType(objectMapper.readTree(admit.body()), "bed-not-free");
            assertThat(dbStatus(h.clinic(), bed)).isEqualTo("free");
            assertThat(admitted).isZero();
        }
        JsonNode b = board(h.clinic());
        assertThat(statusOf(b, bed)).isEqualTo(dbStatus(h.clinic(), bed));
    }

    // ---------------- privacy ----------------

    @Test
    void patientNamesAreOnTheBoardButNotInAuditRowsLogsOrTheRecordAccessLog(CapturedOutput output) throws Exception {
        Hospital h = hospital(2);
        String secretName = "Zaheer Pimpalkhute" + uniqueMobile().substring(5);
        UUID occ = h.beds().get(0);
        UUID clean = h.beds().get(1);
        admit(h.clinic(), secretName, occ);
        setStatus(h.clinic(), clean, "clean");
        long accessBefore = count(h.clinic(), "select count(*) from patient.record_access");

        JsonNode b = board(h.clinic());
        ok(HttpMethod.GET, BOARD, h.clinic().as("NURSE"), null, 200);
        ok(HttpMethod.GET, BOARD, h.clinic().as("DOCTOR"), null, 200);
        assertThat(boardBed(b, occ).get("patientName").asText()).isEqualTo(secretName);
        ok(HttpMethod.POST, BEDS + "/" + clean + "/block", h.clinic().owner(), null, 200);
        ok(HttpMethod.POST, BEDS + "/" + clean + "/ready", h.clinic().owner(), null, 200);

        assertThat(count(h.clinic(), "select count(*) from patient.record_access")).isEqualTo(accessBefore);
        assertThat(count(h.clinic(), "select count(*) from patient.patient where name = ?", secretName)).isOne();
        for (String secret : new String[] {secretName, "Zaheer"}) {
            assertThat(count(h.clinic(), "select count(*) from platform.audit_event where audit_event::text like ?",
                    "%" + secret + "%")).as("audit rows must not contain " + secret).isZero();
            assertThat(output.getAll()).as("log must not contain " + secret).doesNotContain(secret);
        }
        assertThat(audits(h.clinic(), "IPD_BED_BLOCKED")).isOne();
        assertThat(audits(h.clinic(), "IPD_BED_READY")).isOne();
    }

    // ---------------- RLS ----------------

    @Test
    void anotherClinicSeesOnlyItsOwnBoardAndCannotChangeOrSeeTheOthersBeds() throws Exception {
        Hospital a = hospital(2);
        Hospital bClinic = hospital(1);
        UUID aBed = a.beds().get(0);
        setStatus(a.clinic(), aBed, "clean");
        admit(a.clinic(), "Tenant A Patient", a.beds().get(1));

        JsonNode boardB = board(bClinic.clinic());
        assertThat(allBeds(boardB)).hasSize(1);
        assertThat(boardB.get("wards")).hasSize(1);
        assertThat(boardB.get("counts").get("free").asInt()).isOne();
        assertThat(boardB.get("counts").get("occ").asInt()).isZero();
        assertThat(boardB.get("counts").get("clean").asInt()).isZero();
        assertThat(allBeds(boardB).get(0).get("id").asText()).isEqualTo(bClinic.beds().get(0).toString());
        assertThat(boardB.toString()).doesNotContain("Tenant A Patient").doesNotContain(aBed.toString());

        JsonNode boardA = board(a.clinic());
        assertThat(allBeds(boardA)).hasSize(2);
        assertThat(boardA.toString()).doesNotContain(bClinic.beds().get(0).toString());

        for (String action : new String[] {"ready", "block"}) {
            problem(call(HttpMethod.POST, BEDS + "/" + aBed + "/" + action, bClinic.clinic().owner(), null), 404);
        }
        assertThat(dbStatus(a.clinic(), aBed)).isEqualTo("clean");
        assertThat(audits(a.clinic(), "IPD_BED_READY")).isZero();
        assertThat(audits(bClinic.clinic(), "IPD_BED_READY")).isZero();
        assertThat(audits(bClinic.clinic(), "IPD_BED_BLOCKED")).isZero();
        assertThat(new HashSet<>(List.of(a.clinic().tenantId(), bClinic.clinic().tenantId()))).hasSize(2);
    }
}
