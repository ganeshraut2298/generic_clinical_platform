package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.EmrUsageRecorder;
import com.sahaj.opd.application.emr.MasterItemRef;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared set-up of the SAH-43 / SAH-44 integration tests (Doctor EMR masters, templates, overrides, settings, usage
 * ranking). Everything runs through the real endpoints and the real {@code sahaj_test} database as {@code sahaj_app};
 * the few direct writes are the ones no endpoint exists for (a doctor's specialty text, an owner's doctor details, a
 * clinic's specialty, a membership status), done the way the owning module would, committed under the clinic's tenant.
 * Throwaway data only: made-up names, made-up registration numbers, made-up mobile numbers.
 */
abstract class EmrSupport extends SweepSupport {

    static final String EMR = "/api/v1/opd/emr";
    static final String MASTERS = EMR + "/masters/";
    static final String QUICK = EMR + "/quick-options/";
    static final String TEMPLATES = EMR + "/templates";
    static final String SETTINGS = EMR + "/settings";
    static final String ME = EMR + "/me";
    static final String DIAGNOSES = "/api/v1/opd/diagnoses";
    static final String MEDICINES = "/api/v1/opd/medicines";

    static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    static final String[] NON_OWNER_ROLES = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    static final String[] NOT_DOCTOR_ROLES = {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"};
    static final String[] TEMPLATE_KEYS = {"gp", "dental", "paeds", "homeo"};

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected EmrUsageRecorder usageRecorder;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ---------------- people ----------------

    /** A member of a clinic: the membership row, the user behind it and the role. */
    protected record Staff(UUID membershipId, UUID userId, String role) {

        RequestPostProcessor token(Clinic clinic) {
            return asUser(clinic.tenantId(), userId, role);
        }

        /** A token for the same user whose role claim says something else (the membership may disagree). */
        RequestPostProcessor tokenClaiming(Clinic clinic, String claimedRole) {
            return asUser(clinic.tenantId(), userId, claimedRole);
        }
    }

    /**
     * Adds a member through the real Team endpoint. A doctor role gets doctor details (made-up); a null degree or
     * registration number is left out of the request, and a null specialty is cleared afterwards (the column is
     * optional since V2026_11_01_1024).
     */
    protected Staff addStaff(Clinic clinic, String role, String name, String specialty, String degree,
                             String registrationNo) throws Exception {
        StringBuilder body = new StringBuilder("{\"name\":\"").append(name).append("\",\"mobile\":\"")
                .append(uniqueMobile()).append("\",\"role\":\"").append(role).append('"');
        if (role.contains("DOCTOR")) {
            body.append(",\"doctor\":{\"specialty\":\"").append(specialty == null ? "General physician" : specialty)
                    .append("\",\"feeRupees\":250");
            if (degree != null) {
                body.append(",\"degree\":\"").append(degree).append('"');
            }
            if (registrationNo != null) {
                body.append(",\"registrationNo\":\"").append(registrationNo).append('"');
            }
            body.append('}');
        }
        body.append('}');
        JsonNode created = ok(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(), body.toString(), 201);
        UUID membershipId = UUID.fromString(created.get("id").asText());
        UUID userId = UUID.fromString(row(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                membershipId).get(0));
        if (role.contains("DOCTOR") && specialty == null) {
            commit(clinic.tenantId(), "update platform.doctor_profile set specialty = null where membership_id = ?",
                    membershipId);
        }
        return new Staff(membershipId, userId, role);
    }

    protected Staff addDoctor(Clinic clinic, String specialty) throws Exception {
        return addStaff(clinic, "DOCTOR", "Dr Test Doctor", specialty, "MBBS", "REG-" + shortId());
    }

    protected Staff addNonDoctor(Clinic clinic, String role) throws Exception {
        return addStaff(clinic, role, "Test " + role.toLowerCase().replace('_', ' '), null, null, null);
    }

    /** The owner as a Staff (the owner's membership id read from the database). */
    protected Staff owner(Clinic clinic) throws SQLException {
        UUID membershipId = UUID.fromString(row(clinic,
                "select id::text from platform.membership where user_id = ?::uuid and role = 'OWNER'",
                clinic.ownerUserId()).get(0));
        return new Staff(membershipId, clinic.ownerUserId(), "OWNER");
    }

    /** Gives the clinic's owner doctor details, the way Team and access would. Returns the owner. */
    protected Staff ownerWithDoctorDetails(Clinic clinic, String specialty, String degree, String registrationNo)
            throws SQLException {
        Staff owner = owner(clinic);
        commit(clinic.tenantId(), "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, "
                + "registration_no) values (?, ?, 0, ?, ?)", owner.membershipId(), specialty, degree, registrationNo);
        return owner;
    }

    protected void setDoctorSpecialty(Clinic clinic, Staff doctor, String specialty) throws SQLException {
        commit(clinic.tenantId(), "update platform.doctor_profile set specialty = ? where membership_id = ?",
                specialty, doctor.membershipId());
    }

    protected void setDoctorDegreeAndRegistration(Clinic clinic, Staff doctor, String degree, String registrationNo)
            throws SQLException {
        commit(clinic.tenantId(), "update platform.doctor_profile set degree = ?, registration_no = ? "
                + "where membership_id = ?", degree, registrationNo, doctor.membershipId());
    }

    protected void setClinicSpecialty(Clinic clinic, String code) throws SQLException {
        commit(clinic.tenantId(), "update platform.clinic_profile set specialty_code = ?", code);
    }

    protected void setMembershipStatusOf(Clinic clinic, Staff staff, String status) throws SQLException {
        setMembershipStatus(clinic.tenantId(), staff.userId(), status);
    }

    /** No credentials at all. */
    protected static RequestPostProcessor anonymous() {
        return request -> request;
    }

    protected static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ---------------- HTTP ----------------

    protected JsonNode get(String path, RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, path, auth, null, 200);
    }

    protected JsonNode put(String path, RequestPostProcessor auth, String body, int expected) throws Exception {
        return ok(HttpMethod.PUT, path, auth, body, expected);
    }

    protected JsonNode post(String path, RequestPostProcessor auth, String body, int expected) throws Exception {
        return ok(HttpMethod.POST, path, auth, body, expected);
    }

    /** A request that must be answered with a problem body of this status and type slug. */
    protected JsonNode problemOf(HttpMethod method, String path, RequestPostProcessor auth, String body, int status,
                                 String slug) throws Exception {
        JsonNode problem = problem(call(method, path, auth, body), status);
        assertThat(problem.get("type").asText()).as("problem type of " + method + " " + path)
                .isEqualTo(PROBLEM_BASE + slug);
        return problem;
    }

    /** Percent-encodes a query value (a space is %20, never +). */
    protected static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected static String overrideUrl(String template, String section) {
        return TEMPLATES + "/" + template + "/sections/" + section + "/override";
    }

    protected static String resetUrl(String template) {
        return TEMPLATES + "/" + template + "/reset";
    }

    protected static String overrideBody(boolean shown, boolean required, long revision) {
        return "{\"shown\":" + shown + ",\"required\":" + required + ",\"revision\":" + revision + "}";
    }

    protected static String settingsBody(String sectionPanel, String rightPanelFirst, long version) {
        return "{\"sectionPanel\":\"" + sectionPanel + "\",\"rightPanelFirst\":\"" + rightPanelFirst
                + "\",\"version\":" + version + "}";
    }

    protected JsonNode template(Clinic clinic, String key) throws Exception {
        return get(TEMPLATES + "/" + key, clinic.owner());
    }

    protected static JsonNode section(JsonNode template, String key) {
        for (JsonNode s : template.get("sections")) {
            if (key.equals(s.get("key").asText())) {
                return s;
            }
        }
        throw new AssertionError("no section " + key + " in " + template.get("templateKey"));
    }

    protected static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asText() : n.get(field).asText()));
        return out;
    }

    // ---------------- statements that must be refused ----------------

    /** The SQLSTATE and message of an error a statement raised. */
    protected record SqlError(String state, String message) {
    }

    /** Runs the statement on an open connection and returns the error it raises; fails when it is accepted. */
    protected static SqlError expectError(Connection c, String sql, Object... params) throws SQLException {
        DbAccess.execute(c, "savepoint probe");
        try {
            DbAccess.execute(c, sql, params);
        } catch (SQLException e) {
            DbAccess.execute(c, "rollback to savepoint probe");
            return new SqlError(e.getSQLState(), e.getMessage());
        }
        DbAccess.execute(c, "rollback to savepoint probe");
        throw new AssertionError("the statement was accepted: " + sql);
    }

    /** Runs the statement; it must be accepted (the savepoint keeps the transaction usable for the next probe). */
    protected static void expectOk(Connection c, String sql, Object... params) throws SQLException {
        DbAccess.execute(c, "savepoint ok_probe");
        DbAccess.execute(c, sql, params);
        DbAccess.execute(c, "release savepoint ok_probe");
    }

    // ---------------- SQL as sahaj_app, tenant bound ----------------

    /** First row of a query, every column as text (null stays null); empty when there is no row. Rolled back. */
    protected List<String> row(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> firstRow(c, sql, params));
    }

    protected long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    /** Every row of a query as text columns, in order. Rolled back. */
    protected List<List<String>> rows(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> allRows(c, sql, params));
    }

    /** Rows of a global table read as the app with no clinic bound at all. */
    protected List<List<String>> globalRows(String sql, Object... params) throws SQLException {
        return db().asApp(c -> allRows(c, sql, params));
    }

    protected long globalCount(String sql, Object... params) throws SQLException {
        return db().asApp(c -> DbAccess.count(c, sql, params));
    }

    static List<String> firstRow(Connection c, String sql, Object... params) throws SQLException {
        List<List<String>> all = allRows(c, sql, params);
        return all.isEmpty() ? List.of() : all.get(0);
    }

    static List<List<String>> allRows(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> out = new ArrayList<>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> line = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        line.add(rs.getString(i));
                    }
                    out.add(line);
                }
                return out;
            }
        }
    }

    /** One statement as sahaj_app with the clinic bound, committed. */
    protected void commit(UUID tenantId, String sql, Object... params) throws SQLException {
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

    /** The audit rows of one action for the clinic: entity_type, before_json, after_json, actor_user_id; oldest first. */
    protected List<List<String>> auditRows(Clinic clinic, String action) throws SQLException {
        return rows(clinic, "select entity_type, before_json::text, after_json::text, actor_user_id::text "
                + "from platform.audit_event where action = ? order by at, id", action);
    }

    protected long emrAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'EMR\\_%'");
    }

    // ---------------- master items ----------------

    /** Item keys of a master in a fixed order (ids as text; ICD-10 codes for diagnoses). Global tables, no clinic. */
    protected List<String> itemKeys(EmrMaster master, int n) throws SQLException {
        String sql = switch (master) {
            case COMPLAINTS -> "select id::text from opd.emr_complaint order by sort_order, lower(name), id limit ?";
            case INVESTIGATIONS -> "select id::text from opd.emr_investigation order by sort_order, lower(name), id limit ?";
            case ADVICE -> "select id::text from opd.emr_advice order by sort_order, lower(name), id limit ?";
            case DIAGNOSES -> "select code from opd.diagnosis order by lower(name), code limit ?";
            case MEDICINES -> "select id::text from opd.medicine order by lower(name), id limit ?";
        };
        List<String> keys = new ArrayList<>();
        globalRows(sql, n).forEach(r -> keys.add(r.get(0)));
        return keys;
    }

    protected static MasterItemRef ref(EmrMaster master, String key) {
        return new MasterItemRef(master, key);
    }

    /**
     * Counts one use of each item for the doctor, as the sign step of SAH-51 will: inside a transaction of the
     * clinic. Returns how many items were counted.
     */
    protected int recordVisit(Clinic clinic, UUID doctorMembershipId, Instant signedAt, MasterItemRef... items) {
        Authentication auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        Set<MasterItemRef> set = new LinkedHashSet<>(List.of(items));
        Integer counted = runAs(auth, () -> tx().execute(s ->
                usageRecorder.recordSignedVisit(clinic.tenantId(), doctorMembershipId, signedAt, set)));
        return counted == null ? 0 : counted;
    }

    protected JsonNode quick(Clinic clinic, Staff who, EmrMaster master, String query) throws Exception {
        return get(QUICK + master.key() + (query == null ? "" : "?" + query), who.token(clinic));
    }

    protected static List<String> keysOf(JsonNode quickList) {
        return texts(quickList.get("items"), "itemKey");
    }

    protected long usageRows(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from opd.emr_item_usage");
    }
}
