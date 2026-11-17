package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-76 database rules of the dose (docs/database/SAH-76.md sections 1.7, 1.8, 2 and 4) on the real {@code sahaj_test}
 * database: the dose insert guard (attempt n+1 only after a correction, the version and the kind of the order, STAT before the
 * check, the scan method, the undo window stamped by the trigger), the new check constraints, the correction insert guard (the
 * undo window and the recorder, the IST day, only a given dose), uniqueness, append-only for the application role and for the
 * table owner, and row level security on {@code ipd.dose_correction}. Statements run as {@code sahaj_app} with a bound clinic
 * and are rolled back; committed rows are made through the real API or by the table owner (a fixture built once).
 * Test plan: docs/test-plans/SAH-76-dose-settings.md (tests B-01 to B-24).
 */
@OnTestDatabase
@WithPinPepper
class Sah76DoseDbIT extends Sah76NursingSupport {

    private static final String CORR = "insert into ipd.dose_correction (dose_id, kind, reason_key, corrected_by, client_key) "
            + "values (?::uuid, ?, ?, ?::uuid, ?::uuid)";

    /**
     * Clinic A: five uncorrected GIVEN STAT doses (by the nurse), one NOT_GIVEN dose, and a second ward with a scheduled order
     * whose 8 AM dose was marked in error. Clinic B: a dose of its own.
     */
    private record Fx(Ward a, String g1, String g2, String g3, String g4, String notGiven, Ward s, String sched, String schedDose,
                      Instant schedSlot, String pendingSched, String corrKey, Ward b, String bDose) {
    }

    private static Fx shared;

    private synchronized Fx fx() throws Exception {
        if (shared != null) {
            return shared;
        }
        Ward a = ward();
        realClock();
        String[][] stat = {{"panto", "MG", "IV"}, {"ondan", "MG", "IV"}, {"ceft", "MG", "IV"}, {"met", "MG", "ORAL"}};
        String[] ids = new String[4];
        for (int i = 0; i < 4; i++) {
            String order = placeOrder(a.doctor().auth(), a.adm().id(), statOrder(stat[i][0], stat[i][1], stat[i][2]));
            Instant slot = slotOf(mar(a.nurse().auth(), a.adm().id()), order, 0);
            ids[i] = doseOk(a.nurse().auth(), order, scanGiven(a, slot, 1, codeOf(stat[i][0]))).get("dose").get("id").asText();
        }
        String nsOrder = placeOrder(a.doctor().auth(), a.adm().id(), statOrder("ns", "ML", "IV_INFUSION"));
        Instant nsSlot = slotOf(mar(a.nurse().auth(), a.adm().id()), nsOrder, 0);
        String notGiven = doseOk(a.nurse().auth(), nsOrder, notGivenBody(nsSlot, 1, "NIL_BY_MOUTH", null)).get("dose").get("id").asText();
        expireUndo(a.clinic(), ids[1]);
        extendUndo(a.clinic(), ids[2]);
        ageDose(a.clinic(), ids[3], 3 * 24 * 60);

        Ward s = ward();
        String sched = activeScheduled(s, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 5);
        Instant slot = ist(8, 0);
        String schedDose = doseOk(s.nurse().auth(), sched, scanGiven(s, slot, 1, codeOf("pcm"))).get("dose").get("id").asText();
        String key = newKey();
        ok(org.springframework.http.HttpMethod.POST, errorUrl(schedDose), s.nurse().auth(), corrBody(key, "WRONG_TIME"), 200);
        Adm second = admitWithAllergy(s.clinic(), "None known");
        String pending = placeOrder(s.doctor().auth(), second.id(), sched("panto", "od", "MG", "IV"));
        realClock();

        Ward b = ward();
        String bOrder = placeOrder(b.doctor().auth(), b.adm().id(), statOrder("panto", "MG", "IV"));
        String bDose = doseOk(b.nurse().auth(), bOrder, scanGiven(b, slotOf(mar(b.nurse().auth(), b.adm().id()), bOrder, 0), 1,
                codeOf("panto"))).get("dose").get("id").asText();
        shared = new Fx(a, ids[0], ids[1], ids[2], ids[3], notGiven, s, sched, schedDose, slot, pending, key, b, bDose);
        return shared;
    }

    private Map<String, Object> cols(Fx f, Instant slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admission_id", UUID.fromString(f.s().adm().id()));
        m.put("order_id", UUID.fromString(f.sched()));
        m.put("client_key", UUID.randomUUID());
        m.put("slot_at", Timestamp.from(slot));
        m.put("outcome", "GIVEN");
        m.put("recorded_by", f.s().nurse().userId());
        m.put("high_alert", false);
        m.put("identity_scan", f.s().uhid());
        m.put("identity_confirmed", true);
        m.put("scan_method", "SCAN");
        return m;
    }

    private SqlErr insertErr(Fx f, Map<String, Object> columns) throws Exception {
        return insertErr(f.s().clinic(), "ipd.dose_administration", columns);
    }

    private static SqlErr hasState(SqlErr e, String state, String fragment) {
        assertThat(e.state()).as(e.message()).isEqualTo(state);
        if (fragment != null) {
            assertThat(e.message()).contains(fragment);
        }
        return e;
    }

    // ---------------- the dose insert guard ----------------

    @Test
    void aGivenDoseNeedsAScanMethodAndTheTriggerStampsTheUndoWindow() throws Exception {
        Fx f = fx();
        Instant slot = ist(14, 0);
        Map<String, Object> noMethod = cols(f, slot);
        noMethod.remove("scan_method");
        hasState(insertErr(f, noMethod), "23514", "scan method");

        Map<String, Object> ok = cols(f, slot);
        String undo = db().inTenant(f.s().clinic().tenantId(), c -> {
            DbAccess.execute(c, insertSql("ipd.dose_administration", ok), valuesOf(ok));
            return DbAccess.text(c, "select extract(epoch from (undo_until - recorded_at))::int::text from ipd.dose_administration "
                    + "where client_key = ?::uuid", ok.get("client_key").toString());
        });
        assertThat(undo).isEqualTo("10");
        Map<String, Object> cannotSet = cols(f, ist(15, 0));
        cannotSet.put("undo_until", Timestamp.from(Instant.now().plusSeconds(3600)));
        hasState(insertErr(f, cannotSet), "42501", null);
    }

    @Test
    void aNotGivenDoseHasNoUndoWindowAndTheNewColumnsKeepTheirChecks() throws Exception {
        Fx f = fx();
        Map<String, Object> vomited = cols(f, ist(14, 0));
        for (String k : new String[] {"identity_scan", "identity_confirmed", "scan_method"}) {
            vomited.remove(k);
        }
        vomited.put("outcome", "HELD");
        vomited.put("reason_key", "VOMITED");
        String undo = db().inTenant(f.s().clinic().tenantId(), c -> {
            DbAccess.execute(c, insertSql("ipd.dose_administration", vomited), valuesOf(vomited));
            return String.valueOf(DbAccess.text(c, "select coalesce(undo_until::text, 'none') from ipd.dose_administration "
                    + "where client_key = ?::uuid", vomited.get("client_key").toString()));
        });
        assertThat(undo).isEqualTo("none");

        Map<String, Object> other = new LinkedHashMap<>(vomited);
        other.put("client_key", UUID.randomUUID());
        other.put("reason_key", "OTHER");
        hasState(insertErr(f, other), "23514", "ck_dose_administration_other_needs_note");
        other.put("not_given_note", "abc");
        hasState(insertErr(f, other), "23514", "ck_dose_administration_not_given_note");

        Map<String, Object> scanOnHeld = new LinkedHashMap<>(vomited);
        scanOnHeld.put("client_key", UUID.randomUUID());
        scanOnHeld.put("scan_method", "SCAN");
        hasState(insertErr(f, scanOnHeld), "23514", "ck_dose_administration_scan_method_given");
        Map<String, Object> codeOnHeld = new LinkedHashMap<>(vomited);
        codeOnHeld.put("client_key", UUID.randomUUID());
        codeOnHeld.put("medicine_scan", "ABCD1234");
        hasState(insertErr(f, codeOnHeld), "23514", "ck_dose_administration_medicine_scan_given");

        Map<String, Object> overrideNoReason = cols(f, ist(16, 0));
        overrideNoReason.put("scan_method", "OVERRIDE");
        hasState(insertErr(f, overrideNoReason), "23514", "ck_dose_administration_override_");
        Map<String, Object> reasonNoOverride = cols(f, ist(16, 0));
        reasonNoOverride.put("override_reason_key", "EMERGENCY");
        reasonNoOverride.put("override_note", "Emergency, read the label");
        hasState(insertErr(f, reasonNoOverride), "23514", "ck_dose_administration_override_");
        Map<String, Object> shortNote = cols(f, ist(16, 0));
        shortNote.put("scan_method", "OVERRIDE");
        shortNote.put("override_reason_key", "EMERGENCY");
        shortNote.put("override_note", "abc");
        hasState(insertErr(f, shortNote), "23514", "ck_dose_administration_override_note");
        // DEC-057: the insert guard fires first for a given dose without the wristband scan, with the damaged-band reason and
        // with a medicine code next to an override (all SQLSTATE 23514)
        Map<String, Object> overrideNoBand = cols(f, ist(16, 0));
        overrideNoBand.remove("identity_scan");
        overrideNoBand.put("scan_method", "OVERRIDE");
        overrideNoBand.put("override_reason_key", "EMERGENCY");
        overrideNoBand.put("override_note", "Emergency, read the label");
        hasState(insertErr(f, overrideNoBand), "23514", "wristband scan");
        Map<String, Object> damaged = cols(f, ist(16, 0));
        damaged.put("scan_method", "OVERRIDE");
        damaged.put("override_reason_key", "WRISTBAND_DAMAGED");
        damaged.put("override_note", "Emergency, read the label");
        hasState(insertErr(f, damaged), "23514", "wristband damaged");
        Map<String, Object> overrideWithCode = cols(f, ist(16, 0));
        overrideWithCode.put("scan_method", "OVERRIDE");
        overrideWithCode.put("override_reason_key", "EMERGENCY");
        overrideWithCode.put("override_note", "Emergency, read the label");
        overrideWithCode.put("medicine_scan", "ABCD1234");
        hasState(insertErr(f, overrideWithCode), "23514", "no medicine scan");
        Map<String, Object> goodOverride = cols(f, ist(16, 0));
        goodOverride.put("scan_method", "OVERRIDE");
        goodOverride.put("override_reason_key", "EMERGENCY");
        goodOverride.put("override_note", "Emergency, read the label");
        insertOk(f.s().clinic(), "ipd.dose_administration", goodOverride);
    }

    @Test
    void anotherAttemptIsAllowedOnlyAfterTheEarlierOneWasCorrectedAndOnTheSameSlot() throws Exception {
        Fx f = fx();
        Map<String, Object> duplicate = cols(f, f.schedSlot());
        hasState(insertErr(f, duplicate), "23505", "uq_dose_administration_order_slot");

        Map<String, Object> next = cols(f, f.schedSlot());
        next.put("attempt", (short) 2);
        next.put("supersedes_dose_id", UUID.fromString(f.schedDose()));
        insertOk(f.s().clinic(), "ipd.dose_administration", next);

        Map<String, Object> otherSlot = cols(f, ist(14, 0));
        otherSlot.put("attempt", (short) 2);
        otherSlot.put("supersedes_dose_id", UUID.fromString(f.schedDose()));
        hasState(insertErr(f, otherSlot), "23514", "same slot");

        Map<String, Object> noLink = cols(f, f.schedSlot());
        noLink.put("attempt", (short) 2);
        hasState(insertErr(f, noLink), "23514", null);
        Map<String, Object> skipsOne = cols(f, f.schedSlot());
        skipsOne.put("attempt", (short) 3);
        skipsOne.put("supersedes_dose_id", UUID.fromString(f.schedDose()));
        hasState(insertErr(f, skipsOne), "23514", "same slot");

        // a live (uncorrected) record is never followed by another attempt
        Map<String, Object> live = cols(f, ist(14, 0));
        UUID liveKey = (UUID) live.get("client_key");
        SqlErr err = inTenantErr(f.s().clinic(), c -> {
            DbAccess.execute(c, insertSql("ipd.dose_administration", live), valuesOf(live));
            String liveId = DbAccess.text(c, "select id::text from ipd.dose_administration where client_key = ?::uuid", liveKey.toString());
            Map<String, Object> again = cols(f, ist(14, 0));
            again.put("attempt", (short) 2);
            again.put("supersedes_dose_id", UUID.fromString(liveId));
            DbAccess.execute(c, insertSql("ipd.dose_administration", again), valuesOf(again));
            return null;
        });
        hasState(err, "23514", "corrected");
    }

    @Test
    void theDoseMustMatchTheCurrentVersionTheKindAndTheStateOfTheOrder() throws Exception {
        Fx f = fx();
        Map<String, Object> version = cols(f, ist(14, 0));
        version.put("order_version", 2);
        hasState(insertErr(f, version), "23514", "current version");
        Map<String, Object> kind = cols(f, ist(14, 0));
        kind.put("dose_kind", "PRN");
        hasState(insertErr(f, kind), "23514", "dose kind");
        Map<String, Object> alert = cols(f, ist(14, 0));
        alert.put("high_alert", true);
        alert.put("second_check_by", UUID.randomUUID());
        hasState(insertErr(f, alert), "23514", null);
        Map<String, Object> early = cols(f, ist(14, 0));
        early.put("before_verification", true);
        hasState(insertErr(f, early), "23514", "not before verification");

        Map<String, Object> pending = cols(f, ist(14, 0));
        String pendingAdmission = values(f.s().clinic(), "select admission_id::text from ipd.medicine_order where id = ?::uuid",
                f.pendingSched()).get(0);
        pending.put("admission_id", UUID.fromString(pendingAdmission));
        pending.put("order_id", UUID.fromString(f.pendingSched()));
        hasState(insertErr(f, pending), "23514", "active medicine order");
    }

    @Test
    void aStatDoseBeforeTheCheckMustBeMarkedAndOnlyOneGivenDoseMayBeLive() throws Exception {
        Fx f = fx();
        String order = values(f.a().clinic(), "select order_id::text from ipd.dose_administration where id = ?::uuid", f.g1()).get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("admission_id", UUID.fromString(f.a().adm().id()));
        row.put("order_id", UUID.fromString(order));
        row.put("client_key", UUID.randomUUID());
        row.put("slot_at", Timestamp.from(Instant.now().plusSeconds(60)));
        row.put("outcome", "GIVEN");
        row.put("recorded_by", f.a().nurse().userId());
        row.put("high_alert", false);
        row.put("identity_scan", f.a().uhid());
        row.put("identity_confirmed", true);
        row.put("scan_method", "SCAN");
        row.put("dose_kind", "ONCE");
        hasState(insertErr(f.a().clinic(), "ipd.dose_administration", row), "23514", "must be marked before verification");
        row.put("before_verification", true);
        hasState(insertErr(f.a().clinic(), "ipd.dose_administration", row), "23514", "only once");
    }

    // ---------------- the correction insert guard ----------------

    @Test
    void anUndoNeedsTheRecorderAndAnOpenWindowAndAnInErrorNeedsTheSameIstDay() throws Exception {
        Fx f = fx();
        Clinic c = f.a().clinic();
        UUID recorder = f.a().nurse().userId();
        UUID other = f.a().nurse2().userId();
        hasState(appErr(c, CORR, f.g1(), "UNDO", null, other.toString(), UUID.randomUUID().toString()), "23514", "recorded it");
        hasState(appErr(c, CORR, f.g2(), "UNDO", null, recorder.toString(), UUID.randomUUID().toString()), "23514", "window has closed");
        appOk(c, CORR, f.g3(), "UNDO", null, recorder.toString(), UUID.randomUUID().toString());
        hasState(appErr(c, CORR, f.g4(), "IN_ERROR", "WRONG_TIME", recorder.toString(), UUID.randomUUID().toString()), "23514",
                "day of the slot");
        appOk(c, CORR, f.g1(), "IN_ERROR", "WRONG_TIME", other.toString(), UUID.randomUUID().toString());
        hasState(appErr(c, CORR, f.notGiven(), "IN_ERROR", "WRONG_TIME", recorder.toString(), UUID.randomUUID().toString()), "23514",
                "given dose");
        hasState(appErr(c, CORR, f.notGiven(), "UNDO", null, recorder.toString(), UUID.randomUUID().toString()), "23514", "given dose");
    }

    @Test
    void theReasonBelongsToAnInErrorOnlyAndADoseHasOneCorrectionAndEveryKeyIsUsedOnce() throws Exception {
        Fx f = fx();
        Clinic c = f.a().clinic();
        String recorder = f.a().nurse().userId().toString();
        hasState(appErr(c, CORR, f.g3(), "UNDO", "DUPLICATE", recorder, UUID.randomUUID().toString()), "23514",
                "ck_dose_correction_reason_by_kind");
        hasState(appErr(c, CORR, f.g1(), "IN_ERROR", null, recorder, UUID.randomUUID().toString()), "23514",
                "ck_dose_correction_reason_by_kind");
        hasState(appErr(c, CORR, f.g1(), "IN_ERROR", "BECAUSE", recorder, UUID.randomUUID().toString()), "23514", null);
        // the scheduled dose already has a correction and its key
        Clinic s = f.s().clinic();
        hasState(appErr(s, CORR, f.schedDose(), "IN_ERROR", "DUPLICATE", f.s().nurse().userId().toString(), UUID.randomUUID().toString()),
                "23505", null);
        Map<String, Object> noTenantColumn = new LinkedHashMap<>();
        noTenantColumn.put("tenant_id", f.b().clinic().tenantId());
        noTenantColumn.put("dose_id", UUID.fromString(f.g1()));
        noTenantColumn.put("kind", "IN_ERROR");
        noTenantColumn.put("reason_key", "DUPLICATE");
        noTenantColumn.put("corrected_by", f.a().nurse().userId());
        noTenantColumn.put("client_key", UUID.randomUUID());
        hasState(insertErr(c, "ipd.dose_correction", noTenantColumn), "42501", null);
    }

    @Test
    void aCorrectionKeyOfAnotherDoseOfTheSameClinicIsRefusedByTheUniqueIndex() throws Exception {
        Fx f = fx();
        Clinic c = f.s().clinic();
        // the scheduled order's second admission has no given dose: reuse the key on the fixture's other given dose is impossible
        // inside clinic S, so the key is reused on a new dose written in the same transaction
        UUID key = UUID.fromString(f.corrKey());
        SqlErr e = inTenantErr(c, conn -> {
            Map<String, Object> d = cols(f, ist(14, 0));
            DbAccess.execute(conn, insertSql("ipd.dose_administration", d), valuesOf(d));
            String id = DbAccess.text(conn, "select id::text from ipd.dose_administration where client_key = ?::uuid",
                    d.get("client_key").toString());
            DbAccess.execute(conn, CORR, id, "IN_ERROR", "DUPLICATE", f.s().nurse().userId().toString(), key.toString());
            return null;
        });
        hasState(e, "23505", null);
    }

    // ---------------- append-only and row level security ----------------

    @Test
    void aCorrectionCanNeverBeChangedOrDeletedByTheApplicationOrByTheTableOwner() throws Exception {
        Fx f = fx();
        hasState(appErr(f.s().clinic(), "update ipd.dose_correction set reason_key = 'DUPLICATE'"), "42501", null);
        hasState(appErr(f.s().clinic(), "delete from ipd.dose_correction"), "42501", null);
        hasState(ownerErr("ipd.dose_correction", "update ipd.dose_correction set reason_key = 'DUPLICATE'"), "42501", "append-only");
        hasState(ownerErr("ipd.dose_correction", "delete from ipd.dose_correction"), "42501", "append-only");
        hasState(ownerErr("ipd.dose_correction", "truncate ipd.dose_correction"), "42501", "append-only");
        assertThat(correctionRows(f.s().clinic(), f.schedDose())).isOne();
    }

    @Test
    void aCorrectionOfOneClinicIsInvisibleAndUnusableForAnotherClinic() throws Exception {
        Fx f = fx();
        assertThat(correctionRowsOfClinic(f.s().clinic())).isGreaterThanOrEqualTo(1);
        assertThat(correctionRowsOfClinic(f.b().clinic())).as("clinic B sees none of clinic S").isZero();
        assertThat(db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from ipd.dose_correction")).longValue())
                .as("no clinic bound: no row").isZero();
        // clinic B cannot correct a dose of clinic A: the foreign key sees no such dose
        hasState(appErr(f.b().clinic(), CORR, f.schedDose(), "IN_ERROR", "DUPLICATE", f.b().nurse().userId().toString(),
                UUID.randomUUID().toString()), "23503", null);
        // and cannot insert one with no clinic bound
        assertThat(appErrNoTenant(CORR, f.bDose(), "IN_ERROR", "DUPLICATE", f.b().nurse().userId().toString(),
                UUID.randomUUID().toString()).state()).as("refused when no clinic is bound").isNotEqualTo("00000");
        JsonNode mine = mar(f.b().nurse().auth(), f.b().adm().id());
        assertThat(mine.toString()).doesNotContain(f.schedDose());
    }

    @Test
    void theDoseTableOfAnotherClinicIsInvisibleToo() throws Exception {
        Fx f = fx();
        assertThat(count(f.b().clinic(), "select count(*) from ipd.dose_administration where id = ?::uuid", f.g1())).isZero();
        assertThat(count(f.a().clinic(), "select count(*) from ipd.dose_administration where id = ?::uuid", f.bDose())).isZero();
        assertThat(count(f.b().clinic(), "select count(*) from ipd.dose_administration where id = ?::uuid", f.bDose())).isOne();
        List<String> keys = column(f.b().clinic(), "select scan_method from ipd.dose_administration");
        assertThat(keys).containsOnly("SCAN");
    }
}
