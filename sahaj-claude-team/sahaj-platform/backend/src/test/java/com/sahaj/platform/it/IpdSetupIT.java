package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-22 IPD setup (wards, rooms, beds, bed categories, charges) on the real {@code sahaj_test} database:
 * module state gate, lazy defaults, naming and code rules, bed limit, concurrency, roles, audit and
 * row level security. Test plan: docs/test-plans/SAH-22.md.
 */
@OnTestDatabase
class IpdSetupIT extends SweepSupport {

    static final String IPD = "/api/v1/ipd";
    static final String LAYOUT = IPD + "/layout";
    static final String WARDS = IPD + "/wards";
    static final String BEDS = IPD + "/beds";
    static final String ROOM_LEVEL = IPD + "/room-level";
    static final String CHARGES = IPD + "/charges";
    static final String TRIAL = "/api/v1/platform/modules/ipd-trial";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] TABLES = {"ward", "room", "bed", "bed_category", "config"};
    private static final String[] IPD_ACTIONS = {"IPD_WARD_ADDED", "IPD_ROOM_ADDED", "IPD_BED_ADDED",
            "IPD_BED_CATEGORY_CHANGED", "IPD_BED_REMOVED", "IPD_ROOM_LEVEL_CHANGED", "IPD_CATEGORY_CHARGE_CHANGED",
            "IPD_CHARGES_CHANGED"};

    // ---------------- helpers ----------------

    private Clinic ipdClinic() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        return clinic;
    }

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long rows(Clinic clinic, String table) throws SQLException {
        return count(clinic, "select count(*) from ipd." + table);
    }

    private long audits(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    private long ipdAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_%'");
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

    private void assertDenied(Clinic clinic, String sql) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql);
            return null;
        })).isInstanceOf(SQLException.class);
    }

    private JsonNode layout(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, LAYOUT, clinic.owner(), null, 200);
    }

    private JsonNode addWard(Clinic clinic, String name) throws Exception {
        return ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":" + objectMapper.writeValueAsString(name) + "}", 201);
    }

    private JsonNode addBed(Clinic clinic, String wardId, String roomId) throws Exception {
        return ok(HttpMethod.POST, BEDS, clinic.owner(), bedBody(wardId, roomId), 201);
    }

    private String wardId(JsonNode layout, String name) {
        return ward(layout, name).get("id").asText();
    }

    private static String bedBody(String wardId, String roomId) {
        return "{\"wardId\":" + (wardId == null ? "null" : "\"" + wardId + "\"")
                + (roomId == null ? "" : ",\"roomId\":\"" + roomId + "\"") + "}";
    }

    private static JsonNode ward(JsonNode layout, String name) {
        for (JsonNode w : layout.get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w;
            }
        }
        throw new AssertionError("ward not found: " + name + " in " + layout);
    }

    private static JsonNode category(JsonNode layout, String key) {
        for (JsonNode c : layout.get("categories")) {
            if (key.equals(c.get("key").asText())) {
                return c;
            }
        }
        throw new AssertionError("category not found: " + key);
    }

    private static List<String> roomNames(JsonNode ward) {
        List<String> names = new ArrayList<>();
        ward.get("rooms").forEach(r -> names.add(r.get("name").asText()));
        return names;
    }

    private static List<String> bedCodes(JsonNode ward) {
        List<String> codes = new ArrayList<>();
        ward.get("beds").forEach(b -> codes.add(b.get("code").asText()));
        return codes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private static JsonNode bedByCode(JsonNode layout, String code) {
        for (JsonNode w : layout.get("wards")) {
            for (JsonNode b : w.get("beds")) {
                if (code.equals(b.get("code").asText())) {
                    return b;
                }
            }
        }
        throw new AssertionError("bed not found: " + code);
    }

    private static void assertType(JsonNode problem, String suffix) {
        assertThat(problem.get("type").asText()).endsWith(suffix);
    }

    private static void assertFieldError(JsonNode problem, String field) {
        assertThat(problem.get("fieldErrors")).isNotNull();
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
    }

    /** One call per endpoint; ids are random so the answer shows which gate fired first. */
    private List<Object[]> allWrites() {
        UUID id = UUID.randomUUID();
        return List.of(
                new Object[] {HttpMethod.POST, WARDS, "{\"name\":\"X\"}"},
                new Object[] {HttpMethod.POST, WARDS + "/" + id + "/rooms", null},
                new Object[] {HttpMethod.POST, BEDS, "{\"wardId\":\"" + id + "\"}"},
                new Object[] {HttpMethod.PUT, BEDS + "/" + id, "{\"categoryKey\":\"icu\"}"},
                new Object[] {HttpMethod.DELETE, BEDS + "/" + id, null},
                new Object[] {HttpMethod.PUT, ROOM_LEVEL, "{\"on\":false}"},
                new Object[] {HttpMethod.PUT, IPD + "/categories/gen", "{\"ratePaise\":90000,\"multiplier\":1.5}"},
                new Object[] {HttpMethod.PUT, CHARGES,
                        "{\"bedDayCut\":\"24h\",\"nursingPaise\":10000,\"visitPaise\":20000}"});
    }

    // ---------------- module state ----------------

    @Test
    void ipdOffByDefaultAllNineEndpointsAre403ModuleOffThenTrialOpensTheLayout() throws Exception {
        Clinic clinic = newClinic();
        assertType(problem(call(HttpMethod.GET, LAYOUT, clinic.owner(), null), 403), "module-off");
        for (Object[] w : allWrites()) {
            JsonNode wp = problem(call((HttpMethod) w[0], (String) w[1], clinic.owner(), (String) w[2]), 403);
            assertType(wp, "module-off");
        }
        assertThat(ipdAudits(clinic)).isZero();
        for (String t : TABLES) {
            assertThat(rows(clinic, t)).as(t).isZero();
        }
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        assertThat(layout(clinic).get("state").asText()).isEqualTo("trial");
    }

    @Test
    void expiredTrialIsReadonlyReadsWorkAndEveryWriteIs409IpdReadonlyWithNothingChanged() throws Exception {
        Clinic clinic = ipdClinic();
        addWard(clinic, "General ward");
        commitAsTenant(clinic.tenantId(),
                "update platform.module_entitlement set ipd_trial_ends_at = now() - interval '1 day'");
        long wards = rows(clinic, "ward");
        long rooms = rows(clinic, "room");
        long audits = ipdAudits(clinic);

        JsonNode l = layout(clinic);
        assertThat(l.get("state").asText()).isEqualTo("readonly");
        assertThat(l.get("wards")).hasSize(1);
        for (String role : ALL_ROLES) {
            assertThat(ok(HttpMethod.GET, LAYOUT, clinic.as(role), null, 200).get("state").asText()).as(role)
                    .isEqualTo("readonly");
        }
        for (Object[] w : allWrites()) {
            JsonNode wp = problem(call((HttpMethod) w[0], (String) w[1], clinic.owner(), (String) w[2]), 409);
            assertType(wp, "ipd-readonly");
            assertThat(wp.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
        }
        assertThat(rows(clinic, "ward")).isEqualTo(wards);
        assertThat(rows(clinic, "room")).isEqualTo(rooms);
        assertThat(rows(clinic, "bed")).isZero();
        assertThat(rows(clinic, "config")).isZero();
        assertThat(rows(clinic, "bed_category")).isZero();
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
    }

    // ---------------- layout ----------------

    @Test
    void freshTenantLayoutHasDefaultsAndGetWritesNoRowAllRolesReadAnd401WithoutToken() throws Exception {
        Clinic clinic = ipdClinic();
        for (String role : ALL_ROLES) {
            JsonNode l = ok(HttpMethod.GET, LAYOUT, clinic.as(role), null, 200);
            assertThat(l.get("state").asText()).as(role).isEqualTo("trial");
            assertThat(l.get("wards")).as(role).isEmpty();
            assertThat(l.get("roomLevel").asBoolean()).isTrue();
            assertThat(l.get("bedLimit").asInt()).isEqualTo(25);
            assertThat(l.get("bedCount").asInt()).isZero();
            assertThat(l.get("categories")).hasSize(4);
        }
        JsonNode l = layout(clinic);
        long[] rates = {80000, 150000, 250000, 500000};
        double[] multipliers = {1, 1.25, 1.5, 2};
        String[] keys = {"gen", "semi", "pvt", "icu"};
        String[] names = {"General", "Semi-private", "Private", "ICU"};
        for (int i = 0; i < 4; i++) {
            JsonNode c = l.get("categories").get(i);
            assertThat(c.get("key").asText()).isEqualTo(keys[i]);
            assertThat(c.get("name").asText()).isEqualTo(names[i]);
            assertThat(c.get("ratePaise").asLong()).isEqualTo(rates[i]);
            assertThat(c.get("multiplier").asDouble()).isEqualTo(multipliers[i]);
            assertThat(c.get("beds").asInt()).isZero();
        }
        assertThat(l.get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
        assertThat(l.get("charges").get("nursingPaise").asLong()).isEqualTo(30000);
        assertThat(l.get("charges").get("visitPaise").asLong()).isEqualTo(50000);
        for (String t : TABLES) {
            assertThat(rows(clinic, t)).as(t).isZero();
        }
        assertThat(ipdAudits(clinic)).isZero();
        mvc.perform(get(LAYOUT)).andExpect(status().isUnauthorized());
        mvc.perform(post(WARDS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void layoutJsonKeepsTheContractFieldNames() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode first = addWard(clinic, "General ward");
        JsonNode l = addBed(clinic, wardId(first, "General ward"), null);
        assertThat(fieldNames(l)).containsExactlyInAnyOrder("state", "roomLevel", "bedLimit", "bedCount",
                "wards", "categories", "charges");
        JsonNode w = l.get("wards").get(0);
        assertThat(fieldNames(w)).containsExactlyInAnyOrder("id", "name", "station", "bedCount", "rooms", "beds");
        assertThat(fieldNames(w.get("rooms").get(0))).containsExactlyInAnyOrder("id", "name", "beds");
        assertThat(fieldNames(w.get("beds").get(0))).containsExactlyInAnyOrder("id", "code", "categoryKey", "status");
        assertThat(fieldNames(l.get("categories").get(0))).containsExactlyInAnyOrder("key", "name", "ratePaise",
                "multiplier", "beds");
        assertThat(fieldNames(l.get("charges"))).containsExactlyInAnyOrder("bedDayCut", "nursingPaise", "visitPaise");
    }

    // ---------------- wards ----------------

    @Test
    void wardNameIsTrimmedStationDefaultsAndRoomsOnCreatesRoom101Then201Then301() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode l = addWard(clinic, "  General ward · Female  ");
        JsonNode w = ward(l, "General ward · Female");
        assertThat(w.get("station").asText()).isEqualTo("Nursing station 1");
        assertThat(roomNames(w)).containsExactly("Room 101");
        assertThat(w.get("bedCount").asInt()).isZero();
        assertThat(roomNames(ward(addWard(clinic, "Second"), "Second"))).containsExactly("Room 201");
        assertThat(roomNames(ward(addWard(clinic, "Third"), "Third"))).containsExactly("Room 301");
        assertThat(count(clinic, "select count(*) from ipd.ward where name = 'General ward · Female'")).isOne();
        assertThat(audits(clinic, "IPD_WARD_ADDED")).isEqualTo(3);
        assertThat(audits(clinic, "IPD_ROOM_ADDED")).isEqualTo(3);
    }

    @Test
    void roomsOffCreatesNoRoomWithTheWard() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        JsonNode l = addWard(clinic, "Casualty");
        assertThat(l.get("roomLevel").asBoolean()).isFalse();
        assertThat(ward(l, "Casualty").get("rooms")).isEmpty();
        assertThat(rows(clinic, "room")).isZero();
        assertThat(audits(clinic, "IPD_ROOM_ADDED")).isZero();
    }

    @Test
    void duplicateWardNameIsRefusedCaseInsensitivelyAfterTrimming() throws Exception {
        Clinic clinic = ipdClinic();
        addWard(clinic, "General ward");
        for (String dup : new String[] {"General ward", "GENERAL WARD", "  general ward "}) {
            JsonNode p = problem(call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"" + dup + "\"}"), 409);
            assertType(p, "ward-exists");
        }
        assertThat(rows(clinic, "ward")).isOne();
        assertThat(rows(clinic, "room")).isOne();
        assertThat(audits(clinic, "IPD_WARD_ADDED")).isOne();
        // the same name in another clinic is fine
        addWard(ipdClinic(), "General ward");
    }

    @Test
    void invalidWardNamesGet400WithFieldErrorsAndBoundaryOf80IsAccepted() throws Exception {
        Clinic clinic = ipdClinic();
        for (String body : new String[] {"{\"name\":\"\"}", "{\"name\":\"   \"}", "{}", "{\"name\":null}"}) {
            assertFieldError(problem(call(HttpMethod.POST, WARDS, clinic.owner(), body), 400), "name");
        }
        assertThat(problem(call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"\"}"), 400).get("fieldErrors")
                .get(0).get("message").asText()).isEqualTo("Type a ward name first");
        assertFieldError(problem(call(HttpMethod.POST, WARDS, clinic.owner(),
                "{\"name\":\"" + "a".repeat(81) + "\"}"), 400), "name");
        problem(call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":"), 400);
        assertThat(rows(clinic, "ward")).isZero();
        assertThat(rows(clinic, "room")).isZero();
        addWard(clinic, "b".repeat(80));
        assertThat(rows(clinic, "ward")).isOne();
    }

    // ---------------- rooms ----------------

    @Test
    void roomNumberIsHighestPlusOneAndFirstRoomForAWardWithNoneIs100TimesPositionPlusOne() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200); // wards without rooms
        addWard(clinic, "Alpha");
        JsonNode l = addWard(clinic, "Beta");
        String alpha = wardId(l, "Alpha");
        String beta = wardId(l, "Beta");
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);

        l = ok(HttpMethod.POST, WARDS + "/" + beta + "/rooms", clinic.owner(), null, 201);
        assertThat(roomNames(ward(l, "Beta"))).containsExactly("Room 201");
        l = ok(HttpMethod.POST, WARDS + "/" + beta + "/rooms", clinic.owner(), null, 201);
        assertThat(roomNames(ward(l, "Beta"))).containsExactly("Room 201", "Room 202");
        l = ok(HttpMethod.POST, WARDS + "/" + alpha + "/rooms", clinic.owner(), null, 201);
        assertThat(roomNames(ward(l, "Alpha"))).containsExactly("Room 101");
        l = ok(HttpMethod.POST, WARDS + "/" + alpha + "/rooms", clinic.owner(), null, 201);
        assertThat(roomNames(ward(l, "Alpha"))).containsExactly("Room 101", "Room 102");
        assertThat(audits(clinic, "IPD_ROOM_ADDED")).isEqualTo(4);
    }

    @Test
    void aWardWhoseOnlyRoomHasNoNumberGetsPositionBasedRoomNumber() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        String first = wardId(addWard(clinic, "Surgical"), "Surgical");
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);
        // first bed on a ward with no room creates a room named as the ward
        JsonNode l = addBed(clinic, first, null);
        assertThat(roomNames(ward(l, "Surgical"))).containsExactly("Surgical");
        assertThat(bedCodes(ward(l, "Surgical"))).containsExactly("S-A");
        l = ok(HttpMethod.POST, WARDS + "/" + first + "/rooms", clinic.owner(), null, 201);
        assertThat(roomNames(ward(l, "Surgical"))).containsExactly("Surgical", "Room 101");
    }

    @Test
    void roomOnUnknownOrOtherTenantWardIs404() throws Exception {
        Clinic a = ipdClinic();
        Clinic b = ipdClinic();
        String wardA = wardId(addWard(a, "Alpha"), "Alpha");
        problem(call(HttpMethod.POST, WARDS + "/" + UUID.randomUUID() + "/rooms", a.owner(), null), 404);
        problem(call(HttpMethod.POST, WARDS + "/" + wardA + "/rooms", b.owner(), null), 404);
        assertThat(rows(a, "room")).isOne();
        assertThat(rows(b, "room")).isZero();
    }

    // ---------------- beds ----------------

    @Test
    void bedCodesFollowRoomNumberThenFreeLetterUniqueAcrossTheClinic() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode l = addWard(clinic, "General ward");
        String wardId = wardId(l, "General ward");
        String room101 = ward(l, "General ward").get("rooms").get(0).get("id").asText();
        l = addBed(clinic, wardId, room101);
        assertThat(bedCodes(ward(l, "General ward"))).containsExactly("101-A");
        l = addBed(clinic, wardId, room101);
        assertThat(bedCodes(ward(l, "General ward"))).containsExactly("101-A", "101-B");
        l = ok(HttpMethod.POST, WARDS + "/" + wardId + "/rooms", clinic.owner(), null, 201);
        String room102 = ward(l, "General ward").get("rooms").get(1).get("id").asText();
        l = addBed(clinic, wardId, room102);
        assertThat(bedCodes(ward(l, "General ward"))).containsExactly("101-A", "101-B", "102-C");
        // no room named: the ward's first room; the letter continues across wards
        String other = wardId(addWard(clinic, "Second"), "Second");
        l = addBed(clinic, other, null);
        assertThat(bedCodes(ward(l, "Second"))).containsExactly("201-D");
        // the flat list of a ward holds the beds of all its rooms; the room holds only its own
        assertThat(ward(l, "General ward").get("rooms").get(1).get("beds")).hasSize(1);
        assertThat(ward(l, "General ward").get("bedCount").asInt()).isEqualTo(3);
        assertThat(l.get("bedCount").asInt()).isEqualTo(4);
        assertThat(count(clinic, "select count(*) from ipd.bed where status = 'free'")).isEqualTo(4);
        assertThat(audits(clinic, "IPD_BED_ADDED")).isEqualTo(4);
    }

    @Test
    void bedOnAWardWithRoomsOffHasNoRoomAndTakesCapitalLettersOfTheWardName() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        String wardId = wardId(addWard(clinic, "Casualty Block"), "Casualty Block");
        JsonNode l = addBed(clinic, wardId, null);
        assertThat(bedCodes(ward(l, "Casualty Block"))).containsExactly("CB-A");
        assertThat(ward(l, "Casualty Block").get("rooms")).isEmpty();
        assertThat(count(clinic, "select count(*) from ipd.bed where room_id is null")).isOne();
        assertThat(rows(clinic, "room")).isZero();
        // more than three capitals use the first three; no capitals at all gives B
        String many = wardId(addWard(clinic, "A B C D"), "A B C D");
        assertThat(bedCodes(ward(addBed(clinic, many, null), "A B C D"))).containsExactly("ABC-B");
        String lower = wardId(addWard(clinic, "annex"), "annex");
        assertThat(bedCodes(ward(addBed(clinic, lower, null), "annex"))).containsExactly("B-C");
    }

    @Test
    void defaultCategoryComesFromTheWardName() throws Exception {
        Clinic clinic = ipdClinic();
        String icu = wardId(addWard(clinic, "Surgical ICU"), "Surgical ICU");
        String pvt = wardId(addWard(clinic, "PRIVATE wing"), "PRIVATE wing");
        String gen = wardId(addWard(clinic, "General ward"), "General ward");
        addBed(clinic, icu, null);
        addBed(clinic, pvt, null);
        JsonNode l = addBed(clinic, gen, null);
        assertThat(ward(l, "Surgical ICU").get("beds").get(0).get("categoryKey").asText()).isEqualTo("icu");
        assertThat(ward(l, "PRIVATE wing").get("beds").get(0).get("categoryKey").asText()).isEqualTo("pvt");
        assertThat(ward(l, "General ward").get("beds").get(0).get("categoryKey").asText()).isEqualTo("gen");
        assertThat(category(l, "icu").get("beds").asInt()).isOne();
        assertThat(category(l, "pvt").get("beds").asInt()).isOne();
        assertThat(category(l, "gen").get("beds").asInt()).isOne();
        assertThat(category(l, "semi").get("beds").asInt()).isZero();
    }

    @Test
    void bedWithUnknownWardOrForeignRoomOrOtherTenantWardIs404AndBadBodyIs400() throws Exception {
        Clinic clinic = ipdClinic();
        Clinic other = ipdClinic();
        addWard(clinic, "Alpha");
        JsonNode l = addWard(clinic, "Beta");
        String alpha = wardId(l, "Alpha");
        String beta = wardId(l, "Beta");
        String alphaRoom = ward(l, "Alpha").get("rooms").get(0).get("id").asText();
        String otherWard = wardId(addWard(other, "Alpha"), "Alpha");
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(UUID.randomUUID().toString(), null)), 404);
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(beta, alphaRoom)), 404); // room of another ward
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(alpha, UUID.randomUUID().toString())), 404);
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(otherWard, null)), 404); // another clinic's ward
        assertFieldError(problem(call(HttpMethod.POST, BEDS, clinic.owner(), "{}"), 400), "wardId");
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), "{\"wardId\":\"not-a-uuid\"}"), 400);
        assertThat(rows(clinic, "bed")).isZero();
        assertThat(rows(other, "bed")).isZero();
    }

    @Test
    void bedLimitGivesConflictWithTheExactMessageAndCreatesNothing() throws Exception {
        Clinic clinic = ipdClinic();
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set bed_limit = 5");
        JsonNode l = addWard(clinic, "General ward");
        String wardId = wardId(l, "General ward");
        for (int i = 0; i < 5; i++) {
            l = addBed(clinic, wardId, null);
        }
        assertThat(l.get("bedLimit").asInt()).isEqualTo(5);
        assertThat(l.get("bedCount").asInt()).isEqualTo(5);
        long beforeAudit = audits(clinic, "IPD_BED_ADDED");
        JsonNode p = problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(wardId, null)), 409);
        assertType(p, "bed-limit");
        assertThat(p.get("detail").asText()).isEqualTo("Your plan allows 5 beds. Ask Sahaj to raise the limit.");
        assertThat(rows(clinic, "bed")).isEqualTo(5);
        assertThat(audits(clinic, "IPD_BED_ADDED")).isEqualTo(beforeAudit);
        // removing one frees a place
        String anyBed = ward(layout(clinic), "General ward").get("beds").get(0).get("id").asText();
        ok(HttpMethod.DELETE, BEDS + "/" + anyBed, clinic.owner(), null, 200);
        addBed(clinic, wardId, null);
        assertThat(rows(clinic, "bed")).isEqualTo(5);
    }

    @Test
    void bedLimitCheckComesBeforeAnyRoomIsCreated() throws Exception {
        Clinic clinic = ipdClinic();
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set bed_limit = 5");
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        String alpha = wardId(addWard(clinic, "Alpha"), "Alpha");
        for (int i = 0; i < 5; i++) {
            addBed(clinic, alpha, null);
        }
        // a ward with no room, rooms on again: the refused bed must not leave a room behind
        String beta = wardId(addWard(clinic, "Beta"), "Beta");
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);
        assertThat(rows(clinic, "room")).isZero();
        problem(call(HttpMethod.POST, BEDS, clinic.owner(), bedBody(beta, null)), 409);
        assertThat(rows(clinic, "room")).isZero();
        assertThat(rows(clinic, "bed")).isEqualTo(5);
    }

    @Test
    void changingACategoryPersistsAndCountsFollowASameCategoryChangeWritesNothing() throws Exception {
        Clinic clinic = ipdClinic();
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        addBed(clinic, wardId, null);
        JsonNode l = addBed(clinic, wardId, null);
        UUID bedId = UUID.fromString(bedByCode(l, "101-A").get("id").asText());
        assertThat(category(l, "gen").get("beds").asInt()).isEqualTo(2);

        l = ok(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(), "{\"categoryKey\":\"pvt\"}", 200);
        assertThat(bedByCode(l, "101-A").get("categoryKey").asText()).isEqualTo("pvt");
        assertThat(category(l, "gen").get("beds").asInt()).isOne();
        assertThat(category(l, "pvt").get("beds").asInt()).isOne();
        assertThat(db().<String>inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select category_key from ipd.bed where id = ?", bedId))).isEqualTo("pvt");
        assertThat(audits(clinic, "IPD_BED_CATEGORY_CHANGED")).isOne();
        long version = count(clinic, "select version from ipd.bed where id = ?", bedId);

        ok(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(), "{\"categoryKey\":\"pvt\"}", 200);
        assertThat(audits(clinic, "IPD_BED_CATEGORY_CHANGED")).isOne();
        assertThat(count(clinic, "select version from ipd.bed where id = ?", bedId)).isEqualTo(version);

        assertFieldError(problem(call(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(),
                "{\"categoryKey\":\"vip\"}"), 400), "categoryKey");
        problem(call(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.PUT, BEDS + "/" + UUID.randomUUID(), clinic.owner(), "{\"categoryKey\":\"icu\"}"), 404);
        assertThat(audits(clinic, "IPD_BED_CATEGORY_CHANGED")).isOne();
    }

    @Test
    void deletingABedRemovesTheRowAnUnknownIdIs404AndAnOccupiedBedIs409() throws Exception {
        Clinic clinic = ipdClinic();
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        addBed(clinic, wardId, null);
        JsonNode l = addBed(clinic, wardId, null);
        UUID a = UUID.fromString(bedByCode(l, "101-A").get("id").asText());
        UUID b = UUID.fromString(bedByCode(l, "101-B").get("id").asText());

        l = ok(HttpMethod.DELETE, BEDS + "/" + a, clinic.owner(), null, 200);
        assertThat(l.get("bedCount").asInt()).isOne();
        assertThat(count(clinic, "select count(*) from ipd.bed where id = ?", a)).isZero();
        assertThat(audits(clinic, "IPD_BED_REMOVED")).isOne();
        problem(call(HttpMethod.DELETE, BEDS + "/" + a, clinic.owner(), null), 404);
        problem(call(HttpMethod.DELETE, BEDS + "/" + UUID.randomUUID(), clinic.owner(), null), 404);

        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = 'occ' where id = ?", b);
        JsonNode p = problem(call(HttpMethod.DELETE, BEDS + "/" + b, clinic.owner(), null), 409);
        assertType(p, "bed-occupied");
        assertThat(count(clinic, "select count(*) from ipd.bed where id = ?", b)).isOne();
        assertThat(audits(clinic, "IPD_BED_REMOVED")).isOne();
        assertThat(ward(layout(clinic), "General ward").get("beds").get(0).get("status").asText()).isEqualTo("occ");
        // the freed letter is reusable
        l = addBed(clinic, wardId, null);
        assertThat(bedCodes(ward(l, "General ward"))).containsExactlyInAnyOrder("101-B", "101-A");
    }

    // ---------------- concurrency ----------------

    @Test
    void sixParallelBedCreatesAllSucceedWithDistinctCodes() throws Exception {
        Clinic clinic = ipdClinic();
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        List<Integer> statuses = parallelPosts(clinic, 6, BEDS, bedBody(wardId, null));
        assertThat(statuses).containsOnly(201);
        assertThat(rows(clinic, "bed")).isEqualTo(6);
        Set<String> codes = new HashSet<>(bedCodes(ward(layout(clinic), "General ward")));
        assertThat(codes).hasSize(6);
        assertThat(rows(clinic, "room")).isOne();
    }

    @Test
    void parallelBedCreatesNeverExceedTheLimit() throws Exception {
        Clinic clinic = ipdClinic();
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set bed_limit = 5");
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        List<Integer> statuses = parallelPosts(clinic, 8, BEDS, bedBody(wardId, null));
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
        assertThat(rows(clinic, "bed")).isEqualTo(5);
    }

    @Test
    void parallelWardCreatesWithTheSameNameGiveExactlyOneCreated() throws Exception {
        Clinic clinic = ipdClinic();
        List<Integer> statuses = parallelPosts(clinic, 6, WARDS, "{\"name\":\"Alpha\"}");
        assertThat(statuses.stream().filter(s -> s == 201).count()).isOne();
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(rows(clinic, "ward")).isOne();
        assertThat(rows(clinic, "room")).isOne();
    }

    private List<Integer> parallelPosts(Clinic clinic, int n, String path, String payload) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> attempt = () -> {
                start.await();
                return mvc.perform(post(path).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                        .content(payload)).andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- room level ----------------

    @Test
    void roomLevelSwitchPersistsKeepsRoomsAndLinksAndFlatBedsStayTheSame() throws Exception {
        Clinic clinic = ipdClinic();
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        addBed(clinic, wardId, null);
        JsonNode before = addBed(clinic, wardId, null);
        List<String> flat = bedCodes(ward(before, "General ward"));

        JsonNode off = ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        assertThat(off.get("roomLevel").asBoolean()).isFalse();
        assertThat(rows(clinic, "config")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.config where room_level = false")).isOne();
        assertThat(rows(clinic, "room")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.bed where room_id is not null")).isEqualTo(2);
        assertThat(bedCodes(ward(off, "General ward"))).isEqualTo(flat);
        assertThat(layout(clinic).get("roomLevel").asBoolean()).isFalse();
        assertThat(audits(clinic, "IPD_ROOM_LEVEL_CHANGED")).isOne();

        JsonNode on = ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);
        assertThat(on.get("roomLevel").asBoolean()).isTrue();
        assertThat(ward(on, "General ward").get("rooms").get(0).get("beds")).hasSize(2);
        assertThat(rows(clinic, "config")).isOne();
        assertThat(audits(clinic, "IPD_ROOM_LEVEL_CHANGED")).isEqualTo(2);
    }

    @Test
    void invalidRoomLevelBodiesGet400() throws Exception {
        Clinic clinic = ipdClinic();
        assertFieldError(problem(call(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{}"), 400), "on");
        problem(call(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":null}"), 400);
        problem(call(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":\"maybe\"}"), 400);
        problem(call(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":[true]}"), 400);
        assertThat(rows(clinic, "config")).isZero();
    }

    // ---------------- categories and charges ----------------

    @Test
    void categoryUpdateUpsertsOneRowAndASecondUpdateChangesIt() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode l = ok(HttpMethod.PUT, IPD + "/categories/semi", clinic.owner(),
                "{\"ratePaise\":200000,\"multiplier\":1.75}", 200);
        assertThat(category(l, "semi").get("ratePaise").asLong()).isEqualTo(200000);
        assertThat(category(l, "semi").get("multiplier").asDouble()).isEqualTo(1.75);
        assertThat(category(l, "gen").get("ratePaise").asLong()).isEqualTo(80000);
        assertThat(rows(clinic, "bed_category")).isOne();
        ok(HttpMethod.PUT, IPD + "/categories/semi", clinic.owner(), "{\"ratePaise\":250000,\"multiplier\":2}", 200);
        assertThat(rows(clinic, "bed_category")).isOne();
        assertThat(count(clinic, "select rate_paise from ipd.bed_category where key = 'semi'")).isEqualTo(250000);
        assertThat(category(layout(clinic), "semi").get("multiplier").asDouble()).isEqualTo(2.0);
        assertThat(audits(clinic, "IPD_CATEGORY_CHARGE_CHANGED")).isEqualTo(2);
        // the stored values again, and the defaults of an untouched category, write nothing
        ok(HttpMethod.PUT, IPD + "/categories/semi", clinic.owner(), "{\"ratePaise\":250000,\"multiplier\":2.00}", 200);
        ok(HttpMethod.PUT, IPD + "/categories/icu", clinic.owner(), "{\"ratePaise\":500000,\"multiplier\":2}", 200);
        assertThat(rows(clinic, "bed_category")).isOne();
        assertThat(audits(clinic, "IPD_CATEGORY_CHARGE_CHANGED")).isEqualTo(2);
    }

    @Test
    void categoryBoundariesAreAcceptedAndBadValuesGet400() throws Exception {
        Clinic clinic = ipdClinic();
        String cat = IPD + "/categories/gen";
        ok(HttpMethod.PUT, cat, clinic.owner(), "{\"ratePaise\":10000000,\"multiplier\":5}", 200);
        ok(HttpMethod.PUT, cat, clinic.owner(), "{\"ratePaise\":0,\"multiplier\":0.5}", 200);
        long audits = audits(clinic, "IPD_CATEGORY_CHARGE_CHANGED");
        String[][] bad = {
                {"ratePaise", "{\"ratePaise\":10000100,\"multiplier\":1}"},
                {"ratePaise", "{\"ratePaise\":-100,\"multiplier\":1}"},
                {"ratePaise", "{\"ratePaise\":15050,\"multiplier\":1}"},
                {"ratePaise", "{\"multiplier\":1}"},
                {"multiplier", "{\"ratePaise\":80000,\"multiplier\":0.4}"},
                {"multiplier", "{\"ratePaise\":80000,\"multiplier\":5.01}"},
                {"multiplier", "{\"ratePaise\":80000,\"multiplier\":1.234}"},
                {"multiplier", "{\"ratePaise\":80000}"},
        };
        for (String[] b : bad) {
            assertFieldError(problem(call(HttpMethod.PUT, cat, clinic.owner(), b[1]), 400), b[0]);
        }
        problem(call(HttpMethod.PUT, cat, clinic.owner(), "{\"ratePaise\":\"abc\",\"multiplier\":1}"), 400);
        // the last accepted values are still the stored ones
        assertThat(count(clinic, "select rate_paise from ipd.bed_category where key = 'gen'")).isZero();
        assertThat(db().<String>inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select multiplier::text from ipd.bed_category where key = 'gen'")))
                .isEqualTo("0.50");
        assertThat(audits(clinic, "IPD_CATEGORY_CHARGE_CHANGED")).isEqualTo(audits);
        problem(call(HttpMethod.PUT, IPD + "/categories/vip", clinic.owner(), "{\"ratePaise\":80000,\"multiplier\":1}"), 404);
        assertThat(rows(clinic, "bed_category")).isOne();
    }

    @Test
    void chargesPersistAndBadValuesGet400() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode l = ok(HttpMethod.PUT, CHARGES, clinic.owner(),
                "{\"bedDayCut\":\"24h\",\"nursingPaise\":40000,\"visitPaise\":60000}", 200);
        assertThat(l.get("charges").get("bedDayCut").asText()).isEqualTo("24h");
        assertThat(l.get("charges").get("nursingPaise").asLong()).isEqualTo(40000);
        assertThat(layout(clinic).get("charges").get("visitPaise").asLong()).isEqualTo(60000);
        assertThat(rows(clinic, "config")).isOne();
        assertThat(audits(clinic, "IPD_CHARGES_CHANGED")).isOne();
        // keeps the room level choice, accepts the boundaries
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        l = ok(HttpMethod.PUT, CHARGES, clinic.owner(),
                "{\"bedDayCut\":\"midnight\",\"nursingPaise\":0,\"visitPaise\":10000000}", 200);
        assertThat(l.get("roomLevel").asBoolean()).isFalse();
        assertThat(rows(clinic, "config")).isOne();

        String[][] bad = {
                {"bedDayCut", "{\"bedDayCut\":\"weekly\",\"nursingPaise\":1,\"visitPaise\":1}"},
                {"bedDayCut", "{\"nursingPaise\":1,\"visitPaise\":1}"},
                {"nursingPaise", "{\"bedDayCut\":\"24h\",\"nursingPaise\":-1,\"visitPaise\":1}"},
                {"nursingPaise", "{\"bedDayCut\":\"24h\",\"nursingPaise\":10000001,\"visitPaise\":1}"},
                {"nursingPaise", "{\"bedDayCut\":\"24h\",\"visitPaise\":1}"},
                {"visitPaise", "{\"bedDayCut\":\"24h\",\"nursingPaise\":1,\"visitPaise\":-5}"},
                {"visitPaise", "{\"bedDayCut\":\"24h\",\"nursingPaise\":1}"},
        };
        long audits = audits(clinic, "IPD_CHARGES_CHANGED");
        for (String[] b : bad) {
            assertFieldError(problem(call(HttpMethod.PUT, CHARGES, clinic.owner(), b[1]), 400), b[0]);
        }
        assertThat(audits(clinic, "IPD_CHARGES_CHANGED")).isEqualTo(audits);
        assertThat(layout(clinic).get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
    }

    @Test
    void settingTheDefaultsAgainWritesNoRowAndNoAudit() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);
        ok(HttpMethod.PUT, CHARGES, clinic.owner(),
                "{\"bedDayCut\":\"midnight\",\"nursingPaise\":30000,\"visitPaise\":50000}", 200);
        ok(HttpMethod.PUT, IPD + "/categories/gen", clinic.owner(), "{\"ratePaise\":80000,\"multiplier\":1}", 200);
        assertThat(rows(clinic, "config")).isZero();
        assertThat(rows(clinic, "bed_category")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
    }

    // ---------------- roles ----------------

    @Test
    void theSevenNonOwnerRolesGet403OnEveryWriteAndNothingChanges() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode l = addWard(clinic, "General ward");
        String wardId = wardId(l, "General ward");
        l = addBed(clinic, wardId, null);
        String bedId = bedByCode(l, "101-A").get("id").asText();
        long audits = ipdAudits(clinic);
        long[] before = new long[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) {
            before[i] = rows(clinic, TABLES[i]);
        }
        List<Object[]> writes = List.of(
                new Object[] {HttpMethod.POST, WARDS, "{\"name\":\"Other\"}"},
                new Object[] {HttpMethod.POST, WARDS + "/" + wardId + "/rooms", null},
                new Object[] {HttpMethod.POST, BEDS, bedBody(wardId, null)},
                new Object[] {HttpMethod.PUT, BEDS + "/" + bedId, "{\"categoryKey\":\"icu\"}"},
                new Object[] {HttpMethod.DELETE, BEDS + "/" + bedId, null},
                new Object[] {HttpMethod.PUT, ROOM_LEVEL, "{\"on\":false}"},
                new Object[] {HttpMethod.PUT, IPD + "/categories/gen", "{\"ratePaise\":90000,\"multiplier\":1.5}"},
                new Object[] {HttpMethod.PUT, CHARGES, "{\"bedDayCut\":\"24h\",\"nursingPaise\":1,\"visitPaise\":2}"});
        for (String role : NON_OWNERS) {
            for (Object[] w : writes) {
                problem(call((HttpMethod) w[0], (String) w[1], clinic.as(role), (String) w[2]), 403);
            }
        }
        for (int i = 0; i < TABLES.length; i++) {
            assertThat(rows(clinic, TABLES[i])).as(TABLES[i]).isEqualTo(before[i]);
        }
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        assertThat(layout(clinic).get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
    }

    // ---------------- audit ----------------

    @Test
    void allEightIpdActionsAreAuditedWithIdsNamesAndSettingsButNoPatientData() throws Exception {
        Clinic clinic = ipdClinic();
        UUID patient = newPatient(clinic);
        JsonNode l = addWard(clinic, "General ward");
        String wardId = wardId(l, "General ward");
        l = addBed(clinic, wardId, null);
        String bedId = bedByCode(l, "101-A").get("id").asText();
        ok(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(), "{\"categoryKey\":\"icu\"}", 200);
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":false}", 200);
        ok(HttpMethod.PUT, IPD + "/categories/icu", clinic.owner(), "{\"ratePaise\":600000,\"multiplier\":2.5}", 200);
        ok(HttpMethod.PUT, CHARGES, clinic.owner(),
                "{\"bedDayCut\":\"24h\",\"nursingPaise\":35000,\"visitPaise\":55000}", 200);
        ok(HttpMethod.DELETE, BEDS + "/" + bedId, clinic.owner(), null, 200);

        for (String action : IPD_ACTIONS) {
            assertThat(audits(clinic, action)).as(action).isEqualTo(1);
        }
        assertThat(ipdAudits(clinic)).isEqualTo(8);
        String all = db().inTenant(clinic.tenantId(), c -> {
            StringBuilder sb = new StringBuilder();
            try (PreparedStatement ps = c.prepareStatement("select action, entity_type, entity_id::text, "
                    + "actor_user_id::text, coalesce(before_json::text,''), coalesce(after_json::text,'') "
                    + "from platform.audit_event where action like 'IPD\\_%'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= 6; i++) {
                        sb.append(rs.getString(i)).append('|');
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        });
        assertThat(all).doesNotContain("Ramesh", "Kulkarni", "Shivaji", patient.toString());
        assertThat(all).contains("General ward", "101-A", "Room 101", wardId, bedId, clinic.ownerUserId().toString());
        assertThat(all).contains("600000", "24h", "35000", "55000", "roomLevel");
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'IPD_BED_ADDED' "
                + "and entity_type = 'IPD_BED' and entity_id = ?", UUID.fromString(bedId))).isOne();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'IPD_WARD_ADDED' "
                + "and entity_type = 'IPD_WARD' and entity_id = ?", UUID.fromString(wardId))).isOne();
    }

    @Test
    void noOpChangesWriteNoAudit() throws Exception {
        Clinic clinic = ipdClinic();
        String wardId = wardId(addWard(clinic, "General ward"), "General ward");
        JsonNode l = addBed(clinic, wardId, null);
        String bedId = bedByCode(l, "101-A").get("id").asText();
        long audits = ipdAudits(clinic);
        ok(HttpMethod.PUT, BEDS + "/" + bedId, clinic.owner(), "{\"categoryKey\":\"gen\"}", 200);
        ok(HttpMethod.PUT, ROOM_LEVEL, clinic.owner(), "{\"on\":true}", 200);
        ok(HttpMethod.PUT, CHARGES, clinic.owner(),
                "{\"bedDayCut\":\"midnight\",\"nursingPaise\":30000,\"visitPaise\":50000}", 200);
        ok(HttpMethod.PUT, IPD + "/categories/gen", clinic.owner(), "{\"ratePaise\":80000,\"multiplier\":1.00}", 200);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
    }

    // ---------------- row level security ----------------

    @Test
    void anotherTenantSeesNoneOfThisClinicsIpdDataThroughApiOrSql() throws Exception {
        Clinic a = ipdClinic();
        Clinic b = ipdClinic();
        JsonNode l = addWard(a, "General ward");
        String wardA = wardId(l, "General ward");
        String roomA = ward(l, "General ward").get("rooms").get(0).get("id").asText();
        l = addBed(a, wardA, roomA);
        String bedA = bedByCode(l, "101-A").get("id").asText();
        ok(HttpMethod.PUT, IPD + "/categories/gen", a.owner(), "{\"ratePaise\":90000,\"multiplier\":1.5}", 200);
        ok(HttpMethod.PUT, CHARGES, a.owner(), "{\"bedDayCut\":\"24h\",\"nursingPaise\":1000,\"visitPaise\":2000}", 200);
        for (String t : TABLES) {
            assertThat(rows(a, t)).as("A " + t).isPositive();
        }

        JsonNode lb = layout(b);
        assertThat(lb.get("wards")).isEmpty();
        assertThat(lb.get("bedCount").asInt()).isZero();
        assertThat(category(lb, "gen").get("ratePaise").asLong()).isEqualTo(80000);
        assertThat(lb.get("charges").get("bedDayCut").asText()).isEqualTo("midnight");
        for (String t : TABLES) {
            assertThat(rows(b, t)).as("B " + t).isZero();
            assertThat(count(b, "select count(*) from ipd." + t + " where tenant_id = ?", a.tenantId())).as(t).isZero();
        }
        // B cannot touch A's ids through the API
        problem(call(HttpMethod.POST, WARDS + "/" + wardA + "/rooms", b.owner(), null), 404);
        problem(call(HttpMethod.POST, BEDS, b.owner(), bedBody(wardA, roomA)), 404);
        problem(call(HttpMethod.PUT, BEDS + "/" + bedA, b.owner(), "{\"categoryKey\":\"icu\"}"), 404);
        problem(call(HttpMethod.DELETE, BEDS + "/" + bedA, b.owner(), null), 404);
        assertThat(rows(a, "bed")).isOne();
        assertThat(rows(a, "room")).isOne();
        // raw SQL as B: updates touch nothing and inserts for A are refused
        commitAsTenant(b.tenantId(), "update ipd.bed set category_key = 'icu' where tenant_id = ?", a.tenantId());
        commitAsTenant(b.tenantId(), "update ipd.config set nursing_paise = 1 where tenant_id = ?", a.tenantId());
        assertThat(count(a, "select count(*) from ipd.bed where category_key = 'gen'")).isOne();
        assertThat(layout(a).get("charges").get("nursingPaise").asLong()).isEqualTo(1000);
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.ward (tenant_id, name, created_by) values (?, 'X', ?)", a.tenantId(),
                    b.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class);
        // B working does not change A
        addWard(b, "General ward");
        assertThat(rows(a, "ward")).isOne();
        assertThat(count(b, "select count(*) from platform.audit_event where tenant_id = ?", a.tenantId())).isZero();
    }

    @Test
    void withNoTenantBoundTheAppRoleSeesNoRowsOnAllFiveTables() throws Exception {
        Clinic a = ipdClinic();
        String wardId = wardId(addWard(a, "General ward"), "General ward");
        addBed(a, wardId, null);
        ok(HttpMethod.PUT, IPD + "/categories/gen", a.owner(), "{\"ratePaise\":90000,\"multiplier\":1.5}", 200);
        ok(HttpMethod.PUT, ROOM_LEVEL, a.owner(), "{\"on\":false}", 200);
        for (String t : TABLES) {
            assertThat(rows(a, t)).as(t).isPositive();
            assertThat(db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from ipd." + t))).as(t).isZero();
        }
    }

    @Test
    void aBedCannotPointAtAnotherTenantsWardButOwnWardWithNullRoomWorks() throws Exception {
        Clinic a = ipdClinic();
        Clinic b = ipdClinic();
        UUID wardA = UUID.fromString(wardId(addWard(a, "Alpha"), "Alpha"));
        UUID wardB = UUID.fromString(wardId(addWard(b, "Beta"), "Beta"));
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.bed (ward_id, code, created_by) values (?, 'X-1', ?)", wardA,
                    b.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("fk_bed_ward");
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.room (ward_id, name, created_by) values (?, 'Room X', ?)", wardA,
                    b.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("fk_room_ward");
        long inserted = db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.bed (ward_id, room_id, code, created_by) values (?, null, 'X-1', ?)",
                    wardB, b.ownerUserId());
            return DbAccess.count(c, "select count(*) from ipd.bed where room_id is null and code = 'X-1'");
        });
        assertThat(inserted).isOne();
        assertThat(rows(b, "bed")).isZero(); // rolled back
    }

    @Test
    void theAppRoleCannotDeleteWardRoomConfigOrCategoryRowsButCanDeleteABed() throws Exception {
        Clinic a = ipdClinic();
        String wardId = wardId(addWard(a, "General ward"), "General ward");
        addBed(a, wardId, null);
        ok(HttpMethod.PUT, ROOM_LEVEL, a.owner(), "{\"on\":false}", 200);
        ok(HttpMethod.PUT, IPD + "/categories/gen", a.owner(), "{\"ratePaise\":90000,\"multiplier\":1.5}", 200);
        assertDenied(a, "delete from ipd.ward");
        assertDenied(a, "delete from ipd.room");
        assertDenied(a, "delete from ipd.config");
        assertDenied(a, "delete from ipd.bed_category");
        assertDenied(a, "truncate ipd.bed");
        assertThat(rows(a, "ward")).isOne();
        assertThat(rows(a, "room")).isOne();
        assertThat(rows(a, "config")).isOne();
        assertThat(rows(a, "bed_category")).isOne();
        long deleted = db().inTenant(a.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from ipd.bed")) {
                return (long) ps.executeUpdate();
            }
        });
        assertThat(deleted).isOne();
    }

    @Test
    void tableChecksRejectBadRowsAndBedCodesAreUniquePerClinicOnly() throws Exception {
        Clinic a = ipdClinic();
        UUID wardId = UUID.fromString(wardId(addWard(a, "General ward"), "General ward"));
        UUID owner = a.ownerUserId();
        String[] bad = {
                "insert into ipd.bed (ward_id, code, category_key, created_by) values ('" + wardId + "', 'Z-1', 'vip', '" + owner + "')",
                "insert into ipd.bed (ward_id, code, status, created_by) values ('" + wardId + "', 'Z-1', 'broken', '" + owner + "')",
                "insert into ipd.bed (ward_id, code, created_by) values ('" + wardId + "', '', '" + owner + "')",
                "insert into ipd.bed (ward_id, code, created_by) values ('" + wardId + "', '" + "z".repeat(21) + "', '" + owner + "')",
                "insert into ipd.ward (name, created_by) values ('General WARD', '" + owner + "')",
                "insert into ipd.bed_category (key, rate_paise, multiplier) values ('gen', -1, 1)",
                "insert into ipd.bed_category (key, rate_paise, multiplier) values ('gen', 100, 0.4)",
                "insert into ipd.bed_category (key, rate_paise, multiplier) values ('gen', 100, 5.01)",
                "insert into ipd.bed_category (key, rate_paise, multiplier) values ('vip', 100, 1)",
                "insert into ipd.config (bed_day_cut) values ('weekly')",
                "insert into ipd.config (nursing_paise) values (-1)",
        };
        for (String stmt : bad) {
            assertThatThrownBy(() -> db().inTenant(a.tenantId(), c -> {
                DbAccess.execute(c, stmt);
                return null;
            })).as(stmt).isInstanceOf(SQLException.class);
        }
        Clinic b = ipdClinic();
        UUID wardB = UUID.fromString(wardId(addWard(b, "General ward"), "General ward"));
        assertThatThrownBy(() -> db().inTenant(a.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.bed (ward_id, code, created_by) values (?, 'D-1', ?)", wardId, owner);
            DbAccess.execute(c, "insert into ipd.bed (ward_id, code, created_by) values (?, 'D-1', ?)", wardId, owner);
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("uq_bed_tenant_code");
        db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.bed (ward_id, code, created_by) values (?, 'D-1', ?)", wardB,
                    b.ownerUserId());
            return null;
        });
    }
}
