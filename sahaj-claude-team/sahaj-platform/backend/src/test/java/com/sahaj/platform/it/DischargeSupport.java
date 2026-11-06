package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-27 integration tests (discharge, final bill, Discharge setup): the sheet, the sign-off and
 * the discharge through the real API, the expected bills worked out from the story and the default rates (never read
 * from the application), what a discharge wrote read back by SQL, and a sign-off dated in the past.
 *
 * <p>Default rates: General bed {@code ₹800}, doctor visit {@code ₹500} (multiplier 1), nursing {@code ₹300}, so a
 * General bed costs {@code ₹1,600} a bill day; the deposit of {@link #admitGeneral} is {@code ₹10,000}.
 */
abstract class DischargeSupport extends BedTransferSupport {

    static final String DISCHARGE_SETTINGS = IPD + "/discharge-settings";
    static final String FOLLOW_UPS = "/api/v1/opd/follow-ups";
    static final String VISITS = "/api/v1/opd/visits";
    static final String FEES = "/api/v1/opd/fee-settings";

    static final String SIGN_FIRST = "The doctor signs the summary first";
    static final String BILL_CHANGED = "The bill changed. Review the final bill and try again.";
    static final String NOT_ADMITTED_DISCHARGE = "This patient is not admitted any more. The patient cannot be discharged.";
    static final String NOT_ADMITTED_SIGN = "This patient is not admitted any more. The summary cannot be signed.";
    static final String NOT_ADMITTED_SHEET = "This patient is not admitted any more.";
    static final String ONLY_OWNER_REFUNDS = "Only the owner can refund a deposit.";
    static final String CHOOSE_MODE = "Choose UPI, Cash or Card";
    static final String READ_ONLY = "IPD is read-only. Nothing can be changed.";

    /** One General bill day: bed, doctor visit and nursing at the default rates (paise). */
    static final long DAY = DAY_ONE_GENERAL;
    /** The deposit of {@link #admitGeneral} (paise). */
    static final long DEPOSIT = 1_000_000L;

    static final String[] SIGNERS = {"DOCTOR", "OWNER"};
    static final String[] NON_SIGNERS = {"FRONT_DESK", "NURSE", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    static final String[] DISCHARGERS = {"OWNER", "FRONT_DESK"};
    static final String[] NON_DISCHARGERS = {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};

    // ---------------- urls and bodies ----------------

    protected static String dischargeUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/discharge";
    }

    protected static String signoffUrl(Object admissionId) {
        return dischargeUrl(admissionId) + "/signoff";
    }

    protected static String signBody(String type, String template) {
        return "{\"type\":" + quote(type) + ",\"templateKey\":" + quote(template) + "}";
    }

    /** A discharge body for a stay with the standard deposit of {@link #DEPOSIT} (admitGeneral, quickAdmit). */
    protected static String dischargeBody(String type, String template, Long expectedTotal, String mode) {
        return dischargeBody(type, template, expectedTotal, DEPOSIT, mode);
    }

    /** A discharge body with the total and the deposits the user saw on the sheet (null is sent as JSON null). */
    protected static String dischargeBody(String type, String template, Long expectedTotal, Long expectedDeposits,
                                          String mode) {
        return "{\"type\":" + quote(type) + ",\"templateKey\":" + quote(template) + ",\"expectedTotalPaise\":"
                + expectedTotal + ",\"expectedDepositsPaise\":" + expectedDeposits
                + (mode == null ? "" : ",\"mode\":" + quote(mode)) + "}";
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ---------------- calls ----------------

    protected JsonNode sheet(Clinic clinic, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, dischargeUrl(admissionId), clinic.owner(), null, 200);
    }

    protected JsonNode sheetAs(Clinic clinic, String role, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, dischargeUrl(admissionId), clinic.as(role), null, 200);
    }

    protected ResultActions signAs(Clinic clinic, RequestPostProcessor auth, Object admissionId, String type,
                                   String template) throws Exception {
        return call(HttpMethod.PUT, signoffUrl(admissionId), auth, signBody(type, template));
    }

    /** A sign-off by a DOCTOR (token role; the person is the clinic owner's user); returns the sheet. */
    protected JsonNode sign(Clinic clinic, Object admissionId, String type, String template) throws Exception {
        return ok(HttpMethod.PUT, signoffUrl(admissionId), clinic.as("DOCTOR"), signBody(type, template), 200);
    }

    protected JsonNode signNormal(Clinic clinic, Object admissionId) throws Exception {
        return sign(clinic, admissionId, "normal", "general");
    }

    protected ResultActions postDischarge(RequestPostProcessor auth, Object admissionId, String json) throws Exception {
        return call(HttpMethod.POST, dischargeUrl(admissionId), auth, json);
    }

    protected static long totalOf(JsonNode sheet) {
        return sheet.get("bill").get("totalPaise").asLong();
    }

    protected static long depositsOf(JsonNode sheet) {
        return sheet.get("bill").get("depositsPaise").asLong();
    }

    /** Discharges as the clinic owner with the total and deposits the sheet shows now; the summary must be signed. */
    protected JsonNode dischargeNow(Clinic clinic, String admissionId, String type, String template, String mode)
            throws Exception {
        JsonNode sheet = sheet(clinic, admissionId);
        return ok(HttpMethod.POST, dischargeUrl(admissionId), clinic.owner(),
                dischargeBody(type, template, totalOf(sheet), depositsOf(sheet), mode), 200);
    }

    /** Signs Normal / General medicine and discharges by UPI as the owner. */
    protected JsonNode signAndDischarge(Clinic clinic, String admissionId) throws Exception {
        signNormal(clinic, admissionId);
        return dischargeNow(clinic, admissionId, "normal", "general", "UPI");
    }

    /** A real second person with the role, signed in by OTP: its token carries its own user id. */
    protected RequestPostProcessor otherPerson(Clinic clinic, String role) throws Exception {
        Member member = addMember(clinic, role);
        return bearer(login(member.mobile()).accessToken());
    }

    // ---------------- admissions at a bill day ----------------

    /** A Self patient on a new General bed with the minimum deposit (₹10,000) at the bill day (midnight cut). */
    protected JsonNode admitGeneral(Clinic clinic, int billDays) throws Exception {
        return admitAtDay(clinic, "gen", billDays, "midnight");
    }

    /** The expected lines of a General stay of {@code days} bill days with no extras: bed, visits, nursing. */
    protected static Object[] generalStay(int days) {
        return new Object[] {current("gen", days), nursing(days)};
    }

    protected static final L DRESSING = new L("Major dressing", 40_000, 1, false);

    protected String patientOf(Clinic clinic, String admissionId) throws SQLException {
        return values(clinic, "select patient_id::text from ipd.admission where id = ?::uuid", admissionId).get(0);
    }

    // ---------------- what a discharge wrote ----------------

    /** Row counts and states a refused call must leave exactly as they were. */
    protected record Written(long signoffs, long discharges, long bills, long lines, long followUps, long audits,
                             long deposits, long charges, long access, String status, long admissionVersion,
                             String bedStatus, long bedVersion) {
    }

    protected Written written(Clinic clinic, String admissionId) throws SQLException {
        UUID bed = bedOf(clinic, admissionId);
        UUID patient = UUID.fromString(patientOf(clinic, admissionId));
        return new Written(rows(clinic, "discharge_signoff"), rows(clinic, "discharge"), rows(clinic, "final_bill"),
                rows(clinic, "final_bill_line"), count(clinic, "select count(*) from opd.follow_up"),
                count(clinic, "select count(*) from platform.audit_event"), rows(clinic, "deposit"),
                rows(clinic, "admission_charge"), recordAccess(clinic, patient),
                values(clinic, "select status from ipd.admission where id = ?::uuid", admissionId).get(0),
                admissionVersion(clinic, admissionId), bedStatus(clinic, bed), bedVersion(clinic, bed));
    }

    protected static String sheetOrder(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.forEach(n -> keys.add(n.get("key").asText()));
        return String.join(",", keys);
    }

    /** The lines of a bill or final bill as "name|unit|qty|amount|segment", in order. */
    protected static List<String> lineKeys(JsonNode bill) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : bill.get("lines")) {
            out.add(l.get("name").asText() + "|" + l.get("unitPaise").asLong() + "|" + l.get("qty").asInt() + "|"
                    + l.get("amountPaise").asLong() + "|" + l.path("segment").asBoolean(false));
        }
        return out;
    }

    /** The stored final bill lines as "label|unit|qty|amount|segment", in line order. */
    protected List<String> storedLines(Clinic clinic, String admissionId) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select l.label, l.unit_paise, l.qty, l.amount_paise, l.segment "
                    + "from ipd.final_bill_line l join ipd.final_bill b on b.tenant_id = l.tenant_id and b.id = l.final_bill_id "
                    + "where b.admission_id = ?::uuid order by l.line_no")) {
                ps.setString(1, admissionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + "|" + rs.getLong(2) + "|" + rs.getInt(3) + "|" + rs.getLong(4) + "|"
                                + rs.getBoolean(5));
                    }
                }
            }
            return out;
        });
    }

    // ---------------- a sign-off dated in the past ----------------

    /**
     * Inserts a sign-off with a chosen {@code signed_at} (committed). {@code sahaj_app} cannot write that column, so
     * this runs as the table owner in ONE transaction with row level security switched off and on again, as
     * {@link #backdate} does for the admission time.
     */
    protected void insertSignoffAt(Clinic clinic, String admissionId, String type, String template, Instant signedAt)
            throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "set local lock_timeout = '30s'");
                DbAccess.execute(c, "alter table ipd.discharge_signoff no force row level security");
                try (PreparedStatement u = c.prepareStatement("insert into ipd.discharge_signoff "
                        + "(tenant_id, admission_id, discharge_type, template_key, signed_by, signed_at) "
                        + "values (?, ?::uuid, ?, ?, ?, ?)")) {
                    u.setObject(1, clinic.tenantId());
                    u.setString(2, admissionId);
                    u.setString(3, type);
                    u.setString(4, template);
                    u.setObject(5, clinic.ownerUserId());
                    u.setTimestamp(6, Timestamp.from(signedAt));
                    assertThat(u.executeUpdate()).isEqualTo(1);
                }
                DbAccess.execute(c, "alter table ipd.discharge_signoff force row level security");
                c.commit();
            } catch (SQLException | RuntimeException | AssertionError e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---------------- follow-ups ----------------

    protected JsonNode followUps(Clinic clinic, String status) throws Exception {
        return ok(HttpMethod.GET, FOLLOW_UPS + "?status=" + status, clinic.owner(), null, 200);
    }

    /** The follow-up item that came from the admission, or null. */
    protected static JsonNode followUpOf(JsonNode list, String admissionId) {
        for (JsonNode item : list.get("items")) {
            if (admissionId.equals(item.path("sourceAdmissionId").asText(null))) {
                return item;
            }
        }
        return null;
    }

    protected JsonNode issueToken(Clinic clinic, String patientId, Object doctorId) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctorId + "\"}", 201);
    }

    /** Fee settings body with the follow-up window and fee kind (free, half or full). */
    protected static String feesBody(int days, String fee) {
        return "{\"feeTiming\":\"before\",\"followUpDays\":" + days + ",\"followUpFee\":\"" + fee
                + "\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true}";
    }

    protected void setFees(Clinic clinic, int days, String fee) throws Exception {
        ok(HttpMethod.PUT, FEES, clinic.owner(), feesBody(days, fee), 200);
    }

    // ---------------- Discharge setup ----------------

    protected static String settingsBody(String template, boolean chain, int days) {
        return "{\"templateKey\":" + quote(template) + ",\"approvalChain\":" + chain + ",\"followUpDays\":" + days + "}";
    }

    protected JsonNode putSettings(Clinic clinic, String template, boolean chain, int days) throws Exception {
        return ok(HttpMethod.PUT, DISCHARGE_SETTINGS, clinic.owner(), settingsBody(template, chain, days), 200);
    }

    /** A committed update of an admission's reason is not possible (no grant), so reasons are chosen at admission. */
    protected JsonNode admitWithReason(Clinic clinic, String reason) throws Exception {
        java.util.Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("reason", reason);
        return admitOk(clinic, m);
    }

    /** Admits an admission of another type (maternity or daycare) with that type's default minimum deposit. */
    protected JsonNode admitOfType(Clinic clinic, String type, long deposit) throws Exception {
        java.util.Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("admissionType", type);
        m.put("depositPaise", deposit);
        return admitOk(clinic, m);
    }
}
