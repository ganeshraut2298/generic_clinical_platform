package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared set-up of the IPD go-live journey tests ({@link IpdJourneyIT}, DoseOrderStateIT, DoseOrderRaceIT, IpdMoneyStayIT; spec:
 * docs/reviews/GO-LIVE-ipd-test-gaps.md section 3.1 and the QA-1 table; plan: docs/test-plans/IPD-go-live.md).
 *
 * <p>Every write of a journey goes through the real endpoint. SQL is used for two things only: reading state back, and moving a
 * time stamp back. The clock seam of {@link MarSupport} answers a fixed instant for the dose, order and board reads, but the
 * database stamps some times itself ({@code held_at}, {@code resumed_at}, {@code superseded_at}, {@code verified_at},
 * {@code created_at} of a version, {@code undo_until}, the break-glass window). When a test has fixed the clock at 8:05 AM, those
 * times are "now" of the real clock, which can be later than the fixed clock, so the helpers here move them to the fixed
 * clock (the owner, guard triggers of that one table off inside one transaction, the order version and the order head together as
 * {@link Sah76NursingSupport#backdate} does). No test depends on the time of day it runs.
 */
abstract class IpdJourneySupport extends Sah76NursingSupport {

    /** A clinical marker that must never reach a log, an audit row or an event: it is put into every free-text field. */
    static final String REVIEWS = IPD + "/pharmacist-reviews";
    static final String MARK = "QzJrn" + Long.toString(Math.abs(System.nanoTime() % 100000), 36);

    /**
     * The database refuses "recorded in error" unless the real IST date is the date of the dose's slot, and the fixed clock of a
     * test sits on the real IST day. A test that starts in the last 15 minutes of the IST day therefore waits until the new day has
     * begun (the test is not skipped and does not pass vacuously); {@link MarSupport#day} is read again afterwards.
     */
    @BeforeEach
    void waitOutTheIstMidnight() throws InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(IST);
        if (now.getHour() == 23 && now.getMinute() >= 45) {
            Thread.sleep(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(IST).plusMinutes(1)).toMillis());
        }
        day = LocalDate.now(IST);
    }

    /** The people, wards and ids of one journey. Filled step by step. */
    static final class Jy {
        Clinic clinic;
        Clinic other;
        Adm otherAdm;
        Staff doctor;
        Pn nurseA;
        Pn nurseA2;
        String nurseA2Pin;
        Pn nurseB;
        String nurseBPin;
        Pn pharmacist;
        String pharmacistPin;
        Pn owner;
        String ownerPin;
        String wardA;
        String wardB;
        String allergy;
        Adm adm;
        String uhid;
        UUID bedA;
        UUID bedB;
        String hep;
        String pcm;
        String stat;
        String panto;
        String escalationId;
        final List<String> orderIds = new ArrayList<>();
    }

    /** The ward of the MAR helpers: nurse = nurse A, second nurse = nurse A2. */
    protected Ward wardOf(Jy j) {
        return new Ward(j.clinic, j.doctor, j.nurseA, j.nurseA2, j.nurseA2Pin, j.pharmacist, j.pharmacistPin, j.adm, j.uhid);
    }

    /** Clinic, second clinic, people with PINs set through the real endpoints. No ward assignment, no admission yet. */
    protected Jy people(String allergy) throws Exception {
        Jy j = new Jy();
        j.allergy = allergy;
        j.clinic = hospital();
        j.wardA = wardIdByName(j.clinic, "General ward");
        j.wardB = newWard(j.clinic, "Surgical ward");
        j.doctor = staff(j.clinic, "DOCTOR");
        j.nurseA = newPn(j.clinic, "NURSE");
        j.nurseA2 = newPn(j.clinic, "NURSE");
        j.nurseB = newPn(j.clinic, "NURSE");
        j.pharmacist = newPn(j.clinic, "PHARMACIST");
        j.owner = ownerPn(j.clinic);
        j.nurseA2Pin = goodPin(j.nurseA2);
        setPin(j.nurseA2, j.nurseA2Pin);
        j.nurseBPin = goodPin(j.nurseB);
        setPin(j.nurseB, j.nurseBPin);
        j.pharmacistPin = goodPin(j.pharmacist);
        setPin(j.pharmacist, j.pharmacistPin);
        j.ownerPin = goodPin(j.owner);
        setPin(j.owner, j.ownerPin);
        j.other = hospital();
        j.otherAdm = admitWithAllergy(j.other, "None known");
        return j;
    }

    // ---------------- the clock: stamps the database wrote with its own clock are moved to the fixed clock ----------------

    private static Timestamp stamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    /**
     * Sets created_at, start_at, verified_at (when the version has a verifier) and superseded_at (when it is superseded) of one
     * version, and the head's verified_at to the verified_at of its current version (owner, guards off, committed).
     */
    protected void stampVersion(Clinic clinic, String orderId, int versionNo, Instant createdAt, Instant startAt, Instant verifiedAt,
                                Instant supersededAt) throws SQLException {
        asOwner(true, new String[] {"ipd.medicine_order_version", "ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order_version disable trigger user");
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger user");
            try (PreparedStatement ps = c.prepareStatement("update ipd.medicine_order_version set created_at = ?, start_at = ?, "
                    + "verified_at = case when verified_by is null then null else ?::timestamptz end, "
                    + "superseded_at = case when superseded_at is null then null else ?::timestamptz end "
                    + "where order_id = ?::uuid and version_no = ?")) {
                ps.setTimestamp(1, stamp(createdAt));
                ps.setTimestamp(2, stamp(startAt));
                ps.setTimestamp(3, stamp(verifiedAt));
                ps.setTimestamp(4, stamp(supersededAt));
                ps.setString(5, orderId);
                ps.setInt(6, versionNo);
                assertThat(ps.executeUpdate()).as("version " + versionNo + " restamped").isOne();
            }
            // the head carries the verification time of its current version, when that version has one
            DbAccess.execute(c, "update ipd.medicine_order o set verified_at = "
                    + "(select v.verified_at from ipd.medicine_order_version v where v.order_id = o.id and v.version_no = o.current_version) "
                    + "where o.id = ?::uuid and o.verified_at is not null and exists (select 1 from ipd.medicine_order_version v "
                    + "where v.order_id = o.id and v.version_no = o.current_version and v.verified_at is not null)", orderId);
            DbAccess.execute(c, "set constraints all immediate");
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger user");
            DbAccess.execute(c, "alter table ipd.medicine_order_version enable trigger user");
            return null;
        });
    }

    /** Moves the newest hold of the order: held_at, and resumed_at when it was resumed (owner, guards off, committed). */
    protected void stampHold(Clinic clinic, String orderId, Instant heldAt, Instant resumedAt) throws SQLException {
        asOwner(true, new String[] {"ipd.order_hold", "ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.order_hold disable trigger user");
            try (PreparedStatement ps = c.prepareStatement("update ipd.order_hold set held_at = ?, "
                    + "resumed_at = case when resumed_at is null then null else ?::timestamptz end where id = "
                    + "(select id from ipd.order_hold where medicine_order_id = ?::uuid order by held_at desc limit 1)")) {
                ps.setTimestamp(1, stamp(heldAt));
                ps.setTimestamp(2, stamp(resumedAt));
                ps.setString(3, orderId);
                assertThat(ps.executeUpdate()).as("hold restamped").isOne();
            }
            DbAccess.execute(c, "alter table ipd.order_hold enable trigger user");
            return null;
        });
    }

    /** Moves the stop time of a stopped order to this instant (owner, guard off, committed). */
    protected void stampStop(Clinic clinic, String orderId, Instant stoppedAt) throws SQLException {
        asOwner(true, new String[] {"ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger user");
            try (PreparedStatement ps = c.prepareStatement("update ipd.medicine_order set stopped_at = ? where id = ?::uuid "
                    + "and stopped_at is not null")) {
                ps.setTimestamp(1, stamp(stoppedAt));
                ps.setString(2, orderId);
                assertThat(ps.executeUpdate()).as("stop restamped").isOne();
            }
            DbAccess.execute(c, "set constraints all immediate");
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger user");
            return null;
        });
    }

    /** Makes the undo window of a dose end at this instant (owner, append-only trigger off inside the transaction). */
    protected void undoUntil(Clinic clinic, String doseId, Instant until) throws SQLException {
        asOwner(true, new String[] {"ipd.dose_administration", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger user");
            try (PreparedStatement ps = c.prepareStatement("update ipd.dose_administration set undo_until = ? where id = ?::uuid")) {
                ps.setTimestamp(1, stamp(until));
                ps.setString(2, doseId);
                assertThat(ps.executeUpdate()).isOne();
            }
            DbAccess.execute(c, "alter table ipd.dose_administration enable trigger user");
            return null;
        });
    }

    /** Moves a break-glass grant back in time so that it has expired {@code minutes} minutes ago plus its 30 (owner). */
    protected void ageGrant(Clinic clinic, String grantId, int minutes) throws SQLException {
        asOwner(true, new String[] {"ipd.emergency_access_grant", "ipd.admission", "ipd.bed", "ipd.clinical_event"}, c -> {
            DbAccess.execute(c, "alter table ipd.emergency_access_grant disable trigger user");
            try (PreparedStatement ps = c.prepareStatement("update ipd.emergency_access_grant set opened_at = opened_at - (? * interval '1 minute'), "
                    + "expires_at = expires_at - (? * interval '1 minute') where id = ?::uuid")) {
                ps.setInt(1, minutes);
                ps.setInt(2, minutes);
                ps.setString(3, grantId);
                assertThat(ps.executeUpdate()).isOne();
            }
            DbAccess.execute(c, "alter table ipd.emergency_access_grant enable trigger user");
            return null;
        });
    }

    // ---------------- order actions with the contract bodies ----------------

    protected JsonNode holdOk(Staff by, String orderId, int version, String reason) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.holdUrl(orderId), by.auth(), js(Sah76Support.actBody(version, "reason", reason)), 200);
    }

    protected JsonNode resumeOk(Staff by, String orderId, int version) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.resumeUrl(orderId), by.auth(), js(Sah76Support.actBody(version, null, null)), 200);
    }

    protected JsonNode changeOk(Staff by, String orderId, int version, String reason, Object dose) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.changeUrl(orderId), by.auth(), js(changeBodyFor(version, reason, dose, "TABLET", "ORAL", "od")), 200);
    }

    /** A change body: every field of the order as it stays, the dose as it changes. */
    protected static Map<String, Object> changeBodyFor(int version, String reason, Object dose, String unit, String route, String frequency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("orderVersion", version);
        m.put("reason", reason);
        m.put("doseAmount", dose);
        m.put("doseUnit", unit);
        m.put("routeKey", route);
        m.put("frequencyKey", frequency);
        m.put("priority", "ROUTINE");
        return m;
    }

    protected JsonNode changeOk(Staff by, String orderId, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.changeUrl(orderId), by.auth(), js(body), 200);
    }

    protected ResultActions verifyWith(Pn pharmacist, String orderId, String pin, int version) throws Exception {
        String token = tokenOfOrder(pharmacist.auth(), orderId);
        freshPinAllowance(pharmacist);
        return call(HttpMethod.POST, verifyUrl(orderId), pharmacist.auth(),
                "{\"pin\":\"" + pin + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":" + version + "}");
    }

    protected JsonNode verifyVersionOk(Pn pharmacist, String pin, String orderId, int version) throws Exception {
        String token = tokenOfOrder(pharmacist.auth(), orderId);
        freshPinAllowance(pharmacist);
        return ok(HttpMethod.POST, verifyUrl(orderId), pharmacist.auth(),
                "{\"pin\":\"" + pin + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":" + version + "}", 200);
    }

    protected static Map<String, Object> versioned(Map<String, Object> body, int version) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put("orderVersion", version);
        return m;
    }

    protected JsonNode queryOk(Pn pharmacist, String orderId, int version, String question) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.queryUrl(orderId), pharmacist.auth(),
                js(Sah76Support.actBody(version, "question", question)), 200);
    }

    protected JsonNode replyOk(Staff doctor, String orderId, int version, String reply) throws Exception {
        return ok(HttpMethod.POST, Sah76Support.replyUrl(orderId), doctor.auth(), js(Sah76Support.actBody(version, "reply", reply)), 200);
    }

    // ---------------- steps that report together, and one audit row per write ----------------

    /** What a step failed with, in order. A failed step does not stop the next one; the test fails at the end with all of them. */
    protected final List<String> failures = new ArrayList<>();

    /** Runs one step of a journey; a failure is kept (with the line of the test that failed) and the journey goes on. */
    protected void step(String name, Action body) {
        long started = System.nanoTime();
        try {
            body.run();
            System.out.println("[IpdJourney] step ok     " + name + " (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
        } catch (Throwable t) {
            StringBuilder where = new StringBuilder();
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("com.sahaj.platform.it.") && !e.getMethodName().startsWith("lambda$step")) {
                    where.append(" at ").append(e.getFileName()).append(':').append(e.getLineNumber());
                    break;
                }
            }
            String message = String.valueOf(t.getMessage());
            failures.add(name + ": " + t.getClass().getSimpleName() + where + "\n      "
                    + (message.length() > 1800 ? message.substring(0, 1800) : message).replace("\n", "\n      "));
            System.out.println("[IpdJourney] step FAILED " + name + where);
        }
    }

    protected void failIfAny() {
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " step(s) of the journey failed:\n- " + String.join("\n- ", failures));
        }
    }

    @FunctionalInterface
    protected interface Call<T> {
        T get() throws Exception;
    }

    /** Runs a write and checks that it wrote exactly {@code rows} audit rows of this action. */
    protected <T> T audited(Clinic clinic, String action, int rows, Call<T> call) throws Exception {
        long before = audit(clinic, action).size();
        T result = call.get();
        assertThat(audit(clinic, action).size() - before).as("audit rows of " + action).isEqualTo(rows);
        return result;
    }

    // ---------------- reading ----------------

    /** The execution line of the EMR row of an order, as the doctor reads it. */
    protected JsonNode executionOf(Ward w, String orderId) throws Exception {
        JsonNode row = sheetRow(emr(w.doctor().auth(), w.adm().id()), orderId);
        assertThat(row).as("order " + orderId + " on the EMR sheet").isNotNull();
        return row.get("execution");
    }

    /**
     * The contract's execution line (ipd.yaml OrderExecution) worked out from the MAR of the same order and the same instant:
     * lateCount = slots LATE, notGivenToday = slots whose live record is HELD or REFUSED, nextDueAt = the earliest DUE or LATER
     * slot (null when none). The doctor's EMR line must say the same as the nurse's MAR.
     */
    protected void assertEmrEqualsMar(Ward w, MarReader nurse, String orderId, String when) throws Exception {
        JsonNode mar = nurse.read();
        JsonNode order = marOrder(mar, orderId);
        assertThat(order).as(when + ": order in the MAR").isNotNull();
        int late = 0;
        int notGiven = 0;
        Instant next = null;
        for (JsonNode s : order.get("slots")) {
            String state = s.get("state").asText();
            if ("LATE".equals(state)) {
                late++;
            }
            if ("HELD".equals(state) || "REFUSED".equals(state)) {
                notGiven++;
            }
            if ("DUE".equals(state) || "LATER".equals(state)) {
                Instant at = Instant.parse(s.get("slotAt").asText());
                if (next == null || at.isBefore(next)) {
                    next = at;
                }
            }
        }
        JsonNode ex = executionOf(w, orderId);
        assertThat(ex.get("lateCount").asInt()).as(when + ": EMR late count equals the MAR").isEqualTo(late);
        assertThat(ex.get("notGivenToday").asInt()).as(when + ": EMR not given today equals the MAR").isEqualTo(notGiven);
        if (next == null) {
            assertThat(ex.get("nextDueAt").isNull()).as(when + ": EMR next due is empty like the MAR").isTrue();
        } else {
            assertThat(ex.get("nextDueAt").isNull()).as(when + ": EMR next due is " + next).isFalse();
            assertThat(Instant.parse(ex.get("nextDueAt").asText())).as(when + ": EMR next due equals the MAR").isEqualTo(next);
        }
    }

    @FunctionalInterface
    interface MarReader {
        JsonNode read() throws Exception;
    }

    /** The slots of every order of a MAR as "orderId@epoch/state/doseId" lines, for comparing two reads. */
    protected static List<String> slotLines(JsonNode mar) {
        List<String> out = new ArrayList<>();
        for (JsonNode o : mar.get("orders")) {
            for (JsonNode s : o.get("slots")) {
                out.add(o.get("orderId").asText() + "@" + Instant.parse(s.get("slotAt").asText()).getEpochSecond() + "/"
                        + s.get("state").asText() + "/" + (s.get("dose").isNull() ? "-" : s.get("dose").get("id").asText()));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    protected long auditRows(Clinic clinic, String action) throws SQLException {
        return audit(clinic, action).size();
    }

    /** All the text of the admission's clinical events as one text. */
    protected String eventText(Clinic clinic, String admissionId) throws SQLException {
        return String.join("\n", eventLines(clinic, admissionId));
    }

    // ---------------- the second clinic: it sees nothing of the journey ----------------

    private static final String[] ADMISSION_TABLES = {"admission", "medicine_order", "dose_administration", "order_notice", "ews_escalation",
        "vitals", "deposit", "admission_charge", "bed_transfer", "clinical_event", "nursing_note", "discharge", "final_bill",
        "emergency_access_grant"};

    /**
     * What the other clinic sees of this journey, which is nothing: the by-id reads and the writes of the journey's rows answer 404,
     * its lists and board do not hold the journey's rows, and (row level security) its tenant sees none of the rows by SQL.
     */
    protected void otherClinicSeesNothing(Jy j, String step) throws Exception {
        if (j.adm == null) {
            return;
        }
        var o = j.other.owner();
        String id = j.adm.id();
        String at = " [other clinic, after " + step + "]";
        for (String url : new String[] {ADMISSIONS + "/" + id, marUrl(id), emrUrl(id), nursingUrl(id), Sah76Support.noticesUrl(id),
            dischargeUrl(id)}) {
            problem(call(HttpMethod.GET, url, o, null), 404);
        }
        assertThat(idsOf(listOf(o))).as("admissions list" + at).doesNotContain(id);
        assertThat(rowIds(boardOf(o))).as("safety board" + at).doesNotContain(j.escalationId == null ? "none" : j.escalationId);
        for (JsonNode e : boardOf(o).get("escalations")) {
            assertThat(e.get("admissionId").asText()).as("board row" + at).isNotEqualTo(id);
        }
        for (JsonNode r : queue(o, "limit=100").get("items")) {
            assertThat(j.orderIds).as("pharmacist queue" + at).doesNotContain(r.get("orderId").asText());
        }
        assertThat(ok(HttpMethod.GET, REVIEWS, o, null, 200).get("items")).as("review list" + at).isEmpty();
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, o, null, 200).get("items")).as("notice list" + at).isEmpty();
        String dose = js(given(Instant.now(), "PT-00000"));
        for (String order : j.orderIds) {
            problem(call(HttpMethod.POST, dosesUrl(order), o, dose), 404);
            problem(call(HttpMethod.POST, verifyUrl(order), o,
                    "{\"pin\":\"4827\",\"checkToken\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"orderVersion\":1}"), 404);
            problem(call(HttpMethod.POST, stopUrl(order), o, stopBody(1)), 404);
        }
        for (String table : ADMISSION_TABLES) {
            String column = "admission".equals(table) ? "id" : "admission_id";
            assertThat(count(j.other, "select count(*) from ipd." + table + " where " + column + " = ?::uuid", id))
                    .as("row level security: ipd." + table + at).isZero();
        }
        assertThat(count(j.other, "select count(*) from patient.patient where id = ?::uuid", j.adm.patientId().toString()))
                .as("patient row" + at).isZero();
        assertThat(count(j.other, "select count(*) from patient.record_access where patient_id = ?::uuid", j.adm.patientId().toString()))
                .as("record access" + at).isZero();
    }
}
