package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-32 {@code ipd.dose_administration} and the new unique on {@code ipd.medicine_order} on the real {@code sahaj_test}
 * database (docs/database/SAH-32.md "QA test list", items 1 to 21): the insert the application sends and what it returns, every
 * refusal with its constraint name, the insert guard trigger and its rule order, the foreign keys, the column grant, the
 * append-only triggers for the application role and for the table owner, row level security, the order changing after a dose,
 * the catalog, the query plans with 200,000 rows and the row-lock cases on two connections (the API-level races are in
 * {@link DoseConcurrencyIT}). Rows are made by the application role with a bound clinic, or by the table owner, in a transaction
 * that is rolled back; committed state (a stopped order, the other clinic's doses) is made through the real API. Test plan:
 * docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class DoseTableIT extends MarSupport {

    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();
    private static final UUID U3 = UUID.randomUUID();
    private static final Instant S_8AM = Instant.parse("2026-11-06T02:30:00Z");
    private static final Instant S_2PM = Instant.parse("2026-11-06T08:30:00Z");
    private static final Instant S_8PM = Instant.parse("2026-11-06T14:30:00Z");

    private static final String INS = "insert into ipd.dose_administration (admission_id, order_id, client_key, slot_at, outcome, "
            + "reason_key, recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed) values (?::uuid, ?::uuid, "
            + "?::uuid, ?, ?, ?, ?::uuid, ?::uuid, ?, ?, ?) returning id::text, recorded_at::text";
    private static final String BY_ID = " where id = ?::uuid";

    /** Both clinics, their orders in every state, and a second admission of clinic A; made once (committed). */
    private record Fx(Ward a, String pcmA, String hepA, String pendingA, String rejectedA, String stoppedA, Ward a2, String pcmA2,
                      Ward b, String pcmB, String hepB) {
    }

    private static Fx shared;

    private synchronized Fx fx() throws Exception {
        if (shared == null) {
            Ward a = ward();
            String pcm = freshActiveOrder(a, "pcm", "od");
            String hep = freshActiveOrder(a, "hep", "od");
            String pending = pendingOrder(a, "panto", "od");
            String rejected = pendingOrder(a, "ondan", "od");
            rejectOk(a.pharmacist().auth(), rejected, "WRONG_DOSE");
            String stopped = freshActiveOrder(a, "ceft", "od");
            stopOk(a.doctor().auth(), stopped);
            Adm second = admitWithAllergy(a.clinic(), "None known");
            Ward a2 = new Ward(a.clinic(), a.doctor(), a.nurse(), a.nurse2(), a.nurse2Pin(), a.pharmacist(), a.pharmacistPin(), second,
                    values(a.clinic(), "select uhid from patient.patient where id = ?::uuid", second.patientId()).get(0));
            String pcmA2 = freshActiveOrder(a2, "pcm", "od");
            Ward b = ward();
            String pcmB = freshActiveOrder(b, "pcm", "od");
            String hepB = freshActiveOrder(b, "hep", "od");
            shared = new Fx(a, pcm, hep, pending, rejected, stopped, a2, pcmA2, b, pcmB, hepB);
        }
        return shared;
    }

    @FunctionalInterface
    private interface Body {
        void run(Connection c, Fx fx) throws Exception;
    }

    private void inA(Body body) throws Exception {
        Fx fx = fx();
        db().inTenant(fx.a().clinic().tenantId(), c -> {
            try {
                body.run(c, fx);
            } catch (SQLException | RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    private void asOwnerRun(Body body) throws Exception {
        Fx fx = fx();
        asOwner(false, DOSE_TABLES, c -> {
            try {
                body.run(c, fx);
            } catch (SQLException | RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    // ---------------- harness ----------------

    private SqlErr err(Connection c, String sql, Object... params) throws SQLException {
        Savepoint savepoint = c.setSavepoint();
        try {
            DbAccess.execute(c, sql, params);
        } catch (SQLException e) {
            c.rollback(savepoint);
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        c.rollback(savepoint);
        throw new AssertionError("the statement was accepted: " + sql);
    }

    private static Object[] row(String adm, String order, UUID key, Instant slot, String outcome, String reason, UUID by, UUID second,
                                Boolean high, String scan, Boolean confirmed) {
        return new Object[] {adm, order, key.toString(), Timestamp.from(slot), outcome, reason, by.toString(),
                second == null ? null : second.toString(), high, scan, confirmed};
    }

    private static Object[] given(String adm, String order, Instant slot, boolean high, UUID second) {
        return row(adm, order, UUID.randomUUID(), slot, "GIVEN", null, U1, second, high, "SJ-00001", true);
    }

    private static Object[] held(String adm, String order, Instant slot, String reason, boolean high) {
        return row(adm, order, UUID.randomUUID(), slot, reason.equals("PATIENT_REFUSED") ? "REFUSED" : "HELD", reason, U1, null,
                high, null, null);
    }

    private String insert(Connection c, Object[] r) throws SQLException {
        return DbAccess.text(c, INS, r);
    }

    private SqlErr insErr(Connection c, Object[] r) throws SQLException {
        return err(c, INS, r);
    }

    private static void is(SqlErr e, String state, String... fragments) {
        assertThat(e.state()).as(e.message()).isEqualTo(state);
        for (String f : fragments) {
            assertThat(e.message()).containsIgnoringCase(f);
        }
    }

    private static void constraint(SqlErr e, String... anyOf) {
        assertThat(e.state()).as(e.message()).isEqualTo("23514");
        boolean found = false;
        for (String name : anyOf) {
            found |= e.message().contains(name);
        }
        assertThat(found).as(e.message() + " names one of " + List.of(anyOf)).isTrue();
    }

    // ---------------- 1 to 6 : the rows the application sends ----------------

    @Test
    void item1AGivenDoseOfANonHighAlertMedicineIsInsertedWithTheDatabaseDefaultsAndTwoRowsOfOneTransactionGetIncreasingTimes()
            throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            String id = DbAccess.text(c, "insert into ipd.dose_administration (admission_id, order_id, client_key, slot_at, outcome, "
                    + "reason_key, recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed) values (?::uuid, ?::uuid, "
                    + "?::uuid, ?, 'GIVEN', null, ?::uuid, null, false, 'SJ-00001', true) returning id::text", adm, fx.pcmA(),
                    UUID.randomUUID().toString(), Timestamp.from(S_8AM), U1.toString());
            assertThat(DbAccess.text(c, "select (id is not null and tenant_id = ?::uuid and reason_key is null and second_check_by is null "
                    + "and identity_scan = 'SJ-00001' and identity_confirmed and not high_alert and recorded_at <= clock_timestamp() "
                    + "and recorded_at > clock_timestamp() - interval '1 second')::text from ipd.dose_administration" + BY_ID,
                    fx.a().clinic().tenantId().toString(), id)).isEqualTo("true");
            String second = DbAccess.text(c, "insert into ipd.dose_administration (admission_id, order_id, client_key, slot_at, outcome, "
                    + "recorded_by, high_alert, identity_scan, identity_confirmed) values (?::uuid, ?::uuid, gen_random_uuid(), ?, 'GIVEN', "
                    + "?::uuid, false, 'SJ-00001', true) returning id::text", adm, fx.pcmA(), Timestamp.from(S_2PM), U1.toString());
            assertThat(DbAccess.text(c, "select ((select recorded_at from ipd.dose_administration where id = ?::uuid) < "
                    + "(select recorded_at from ipd.dose_administration where id = ?::uuid))::text", id, second))
                    .as("clock_timestamp, not the transaction start").isEqualTo("true");
        });
    }

    @Test
    void item2AGivenDoseOfAHighAlertMedicineNeedsAndStoresASecondNurseOtherThanTheRecorder() throws Exception {
        inA((c, fx) -> {
            String returned = insert(c, given(fx.a().adm().id(), fx.hepA(), S_8AM, true, U2));
            assertThat(returned).isNotBlank();
            assertThat(DbAccess.text(c, "select (high_alert and second_check_by = ?::uuid and recorded_by = ?::uuid)::text from "
                    + "ipd.dose_administration where order_id = ?::uuid", U2.toString(), U1.toString(), fx.hepA())).isEqualTo("true");
        });
    }

    @Test
    void item3EveryNotGivenReasonIsInsertedWithNoSecondNurseScanOrStatementAlsoForAHighAlertMedicineWithoutASecondNurse()
            throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            int i = 0;
            for (String[] reason : REASONS) {
                Instant slot = S_8AM.plusSeconds(60L * i++);
                insert(c, held(adm, fx.pcmA(), slot, reason[0], false));
                insert(c, held(adm, fx.hepA(), slot, reason[0], true));
            }
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid and outcome = 'REFUSED'",
                    fx.pcmA())).isOne();
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid and outcome = 'HELD'",
                    fx.pcmA())).isEqualTo(4);
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid and high_alert and "
                    + "second_check_by is null and identity_scan is null and identity_confirmed is null", fx.hepA())).isEqualTo(5);
        });
    }

    @Test
    void item4AFortyCharacterScanAndTheEightAmIstSlotAreAccepted() throws Exception {
        inA((c, fx) -> {
            Object[] r = row(fx.a().adm().id(), fx.pcmA(), UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, "A".repeat(40), true);
            insert(c, r);
            assertThat(DbAccess.text(c, "select (slot_at at time zone 'Asia/Kolkata')::time::text from ipd.dose_administration where "
                    + "order_id = ?::uuid", fx.pcmA())).isEqualTo("08:00:00");
        });
    }

    @Test
    void item5TheSameSlotForTwoOrdersTwoSlotsForOneOrderAndTheSameClientKeyInAnotherClinicAreAccepted() throws Exception {
        Fx fx = fx();
        inA((c, f) -> {
            insert(c, given(f.a().adm().id(), f.pcmA(), S_8AM, false, null));
            insert(c, given(f.a().adm().id(), f.hepA(), S_8AM, true, U2));
            insert(c, given(f.a().adm().id(), f.pcmA(), S_2PM, false, null));
            insert(c, given(f.a().adm().id(), f.pcmA(), S_8PM, false, null));
        });
        UUID key = UUID.randomUUID();
        db().inTenant(fx.a().clinic().tenantId(), c -> {
            insert(c, row(fx.a().adm().id(), fx.pcmA(), key, S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", true));
            return null;
        });
        db().inTenant(fx.b().clinic().tenantId(), c -> {
            insert(c, row(fx.b().adm().id(), fx.pcmB(), key, S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", true));
            return null;
        });
    }

    @Test
    void item6ReturningTheIdAndTheRecordedTimeWorksForTheApplicationRoleThroughItsSelectPrivilege() throws Exception {
        inA((c, fx) -> {
            try (PreparedStatement ps = c.prepareStatement(INS)) {
                Object[] r = given(fx.a().adm().id(), fx.pcmA(), S_8AM, false, null);
                for (int i = 0; i < r.length; i++) {
                    ps.setObject(i + 1, r[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1, String.class)).isNotBlank();
                    assertThat(rs.getObject(2, String.class)).isNotBlank();
                }
            }
        });
    }

    // ---------------- 7 to 11 : refusals ----------------

    @Test
    void item7TheSameOrderAndSlotTwiceAndTheSameClientKeyTwiceAre23505WithTheirConstraintNames() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            UUID key = UUID.randomUUID();
            insert(c, row(adm, fx.pcmA(), key, S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", true));
            is(insErr(c, given(adm, fx.pcmA(), S_8AM, false, null)), "23505", "uq_dose_administration_order_slot");
            is(insErr(c, held(adm, fx.pcmA(), S_8AM, "NIL_BY_MOUTH", false)), "23505", "uq_dose_administration_order_slot");
            is(insErr(c, row(adm, fx.pcmA(), key, S_2PM, "GIVEN", null, U1, null, false, "SJ-00001", true)), "23505",
                    "uq_dose_administration_tenant_client_key");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid", fx.pcmA())).isOne();
        });
    }

    @Test
    void item8ASlotWithSecondsOrMillisecondsIs23514SlotMinute() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            is(insErr(c, given(adm, fx.pcmA(), S_8AM.plusSeconds(30), false, null)), "23514", "ck_dose_administration_slot_minute");
            is(insErr(c, given(adm, fx.pcmA(), S_8AM.plusMillis(500), false, null)), "23514", "ck_dose_administration_slot_minute");
            is(insErr(c, given(adm, fx.pcmA(), S_8AM.plusNanos(1_000), false, null)), "23514", "ck_dose_administration_slot_minute");
            insert(c, given(adm, fx.pcmA(), S_8AM.plusSeconds(60), false, null));
        });
    }

    @Test
    void item9EveryWrongOutcomeAndReasonPairIs23514WithTheMatchingConstraint() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            String o = fx.pcmA();
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", "NIL_BY_MOUTH", U1, null, false, "SJ-00001", true)),
                    "ck_dose_administration_reason_by_outcome");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "HELD", null, U1, null, false, null, null)),
                    "ck_dose_administration_reason_by_outcome");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "REFUSED", "HELD_DOCTOR", U1, null, false, null, null)),
                    "ck_dose_administration_refused_reason");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "HELD", "PATIENT_REFUSED", U1, null, false, null, null)),
                    "ck_dose_administration_refused_reason");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "HELD", "OTHER", U1, null, false, null, null)),
                    "ck_dose_administration_reason");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GONE", null, U1, null, false, null, null)),
                    "ck_dose_administration_outcome", "ck_dose_administration_reason_by_outcome",
                    "ck_dose_administration_identity_scan_by_outcome");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid", o)).isZero();
        });
    }

    @Test
    void item10TheSecondNurseRulesAreSecondCheckOtherSecondCheckOutcomeAndHighAlertSecondCheck() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            constraint(insErr(c, row(adm, fx.pcmA(), UUID.randomUUID(), S_8AM, "GIVEN", null, U1, U1, false, "SJ-00001", true)),
                    "ck_dose_administration_second_check_other");
            constraint(insErr(c, row(adm, fx.pcmA(), UUID.randomUUID(), S_8AM, "HELD", "NIL_BY_MOUTH", U1, U2, false, null, null)),
                    "ck_dose_administration_second_check_outcome");
            constraint(insErr(c, row(adm, fx.pcmA(), UUID.randomUUID(), S_8AM, "REFUSED", "PATIENT_REFUSED", U1, U2, false, null, null)),
                    "ck_dose_administration_second_check_outcome");
            constraint(insErr(c, row(adm, fx.hepA(), UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, true, "SJ-00001", true)),
                    "ck_dose_administration_high_alert_second_check");
        });
    }

    @Test
    void item11TheIdentityRulesNeedAScanAndAStatementForGivenAndForbidThemOtherwise() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            String o = fx.pcmA();
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, null, true)),
                    "ck_dose_administration_identity_scan_by_outcome");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", null)),
                    "ck_dose_administration_identity_confirmed_by_outcome");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", false)),
                    "ck_dose_administration_identity_confirmed_by_outcome", "ck_dose_administration_identity_confirmed_only_given");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "HELD", "NIL_BY_MOUTH", U1, null, false, "SJ-00001", null)),
                    "ck_dose_administration_identity_scan_by_outcome");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "HELD", "NIL_BY_MOUTH", U1, null, false, null, false)),
                    "ck_dose_administration_identity_confirmed_only_given");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, "", true)),
                    "ck_dose_administration_identity_scan");
            constraint(insErr(c, row(adm, o, UUID.randomUUID(), S_8AM, "GIVEN", null, U1, null, false, "A".repeat(41), true)),
                    "ck_dose_administration_identity_scan");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid", o)).isZero();
        });
    }

    // ---------------- 12 : the guard trigger ----------------

    @Test
    void item12TheGuardRefusesAnOrderThatIsNotActiveADischargedAdmissionAndAWrongHighAlertFlagWithTheDoseMessages() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            for (String order : new String[] {fx.pendingA(), fx.rejectedA(), fx.stoppedA()}) {
                is(insErr(c, given(adm, order, S_8AM, false, null)), "23514", "dose can be recorded only for an active medicine order");
                is(insErr(c, held(adm, order, S_8AM, "NIL_BY_MOUTH", false)), "23514",
                        "dose can be recorded only for an active medicine order");
            }
            // the state error comes before a pair error (a bad reason on a pending order)
            is(insErr(c, row(adm, fx.pendingA(), UUID.randomUUID(), S_8AM, "HELD", "OTHER", U1, null, false, null, null)), "23514",
                    "dose can be recorded only for an active medicine order");
            is(insErr(c, row(adm, fx.hepA(), UUID.randomUUID(), S_8AM, "HELD", "NIL_BY_MOUTH", U1, null, false, null, null)), "23514",
                    "high-alert flag does not match the medicine");
            is(insErr(c, row(adm, fx.pcmA(), UUID.randomUUID(), S_8AM, "HELD", "NIL_BY_MOUTH", U1, null, true, null, null)), "23514",
                    "high-alert flag does not match the medicine");
            // a discharged admission with an active order
            DbAccess.execute(c, "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid", adm);
            is(insErr(c, given(adm, fx.pcmA(), S_8AM, false, null)), "23514", "only while the patient is admitted");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where admission_id = ?::uuid", adm)).isZero();
        });
    }

    // ---------------- 13 : foreign keys ----------------

    @Test
    void item13AnUnknownOrderAnOrderOfAnotherAdmissionAndAnotherClinicsOrderAre23503() throws Exception {
        inA((c, fx) -> {
            is(insErr(c, given(fx.a().adm().id(), UUID.randomUUID().toString(), S_8AM, false, null)), "23503",
                    "fk_dose_administration_order");
            is(insErr(c, given(fx.a().adm().id(), fx.pcmA2(), S_8AM, false, null)), "23503", "fk_dose_administration_order");
            is(insErr(c, given(fx.a2().adm().id(), fx.pcmA(), S_8AM, false, null)), "23503", "fk_dose_administration_order");
            SqlErr other = insErr(c, given(fx.b().adm().id(), fx.pcmB(), S_8AM, false, null));
            assertThat(other.state()).as(other.message()).isEqualTo("23503");
            assertThat(other.message()).containsAnyOf("fk_dose_administration_order", "fk_dose_administration_admission");
            is(insErr(c, given(UUID.randomUUID().toString(), fx.pcmA(), S_8AM, false, null)), "23503", "fk_dose_administration_");
            insert(c, given(fx.a2().adm().id(), fx.pcmA2(), S_8AM, false, null));
        });
    }

    // ---------------- 14 and 15 : grants and append-only ----------------

    @Test
    void item14NamingIdTenantIdOrRecordedAtInAnInsertIs42501() throws Exception {
        inA((c, fx) -> {
            String adm = fx.a().adm().id();
            String cols = "(%s admission_id, order_id, client_key, slot_at, outcome, recorded_by, high_alert, identity_scan, "
                    + "identity_confirmed) values (%s ?::uuid, ?::uuid, gen_random_uuid(), ?, 'GIVEN', ?::uuid, false, 'SJ-00001', true)";
            Object[] p = {adm, fx.pcmA(), Timestamp.from(S_8AM), U1.toString()};
            is(err(c, "insert into ipd.dose_administration " + String.format(cols, "id,", "gen_random_uuid(),"), p), "42501");
            is(err(c, "insert into ipd.dose_administration " + String.format(cols, "tenant_id,", "'" + fx.a().clinic().tenantId() + "'::uuid,"), p),
                    "42501");
            is(err(c, "insert into ipd.dose_administration " + String.format(cols, "recorded_at,", "now(),"), p), "42501");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid", fx.pcmA())).isZero();
        });
    }

    @Test
    void item15UpdateDeleteAndTruncateAreRefusedForTheApplicationByPrivilegeAndForTheOwnerByTheTriggerAndTheRowsStay() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        JsonNode a = doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        String id = a.get("dose").get("id").asText();
        DoseDb before = doseDb(w.clinic(), order, ist(8, 0));
        db().inTenant(w.clinic().tenantId(), c -> {
            is(err(c, "update ipd.dose_administration set outcome = 'HELD'" + BY_ID, id), "42501");
            is(err(c, "update ipd.dose_administration set recorded_by = recorded_by" + BY_ID, id), "42501");
            is(err(c, "delete from ipd.dose_administration" + BY_ID, id), "42501");
            is(err(c, "truncate ipd.dose_administration"), "42501");
            return null;
        });
        asOwner(false, DOSE_TABLES, c -> {
            is(err(c, "update ipd.dose_administration set outcome = 'HELD', reason_key = 'NIL_BY_MOUTH', identity_scan = null, "
                    + "identity_confirmed = null" + BY_ID, id), "42501", "append-only");
            is(err(c, "update ipd.dose_administration set recorded_by = recorded_by" + BY_ID, id), "42501", "append-only");
            is(err(c, "delete from ipd.dose_administration" + BY_ID, id), "42501", "append-only");
            is(err(c, "truncate ipd.dose_administration"), "42501", "append-only");
            return null;
        });
        assertThat(doseDb(w.clinic(), order, ist(8, 0))).isEqualTo(before);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    // ---------------- 16 : tenants ----------------

    @Test
    void item16AnotherClinicsDosesAreInvisibleNothingShowsWithoutATenantAndAnInsertWithoutOneNeverStoresARow() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String orderB = activeOrder(b, "pcm", "od");
        clockAt(8, 5);
        JsonNode doseB = doseOk(b.nurse().auth(), orderB, given(b, ist(8, 0)));
        String idB = doseB.get("dose").get("id").asText();
        db().inTenant(a.clinic().tenantId(), c -> {
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration" + BY_ID, idB)).isZero();
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where tenant_id = ?::uuid",
                    b.clinic().tenantId().toString())).isZero();
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where order_id = ?::uuid", orderB)).isZero();
            is(err(c, INS, given(b.adm().id(), orderB, S_8AM, false, null)), "23503");
            is(err(c, "insert into ipd.dose_administration (tenant_id, admission_id, order_id, client_key, slot_at, outcome, "
                    + "recorded_by, high_alert, identity_scan, identity_confirmed) values (?::uuid, ?::uuid, ?::uuid, gen_random_uuid(), "
                    + "?, 'GIVEN', ?::uuid, false, 'SJ-00001', true)", b.clinic().tenantId().toString(), b.adm().id(), orderB,
                    Timestamp.from(S_8AM), U1.toString()), "42501");
            return null;
        });
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration" + BY_ID, idB)).as("no tenant bound").isZero();
            SqlErr e = err(c, INS, given(b.adm().id(), orderB, S_8AM, false, null));
            assertThat(e.state()).as(e.message()).isIn("42501", "23502", "23503");
            return null;
        });
        assertThat(doseDb(b.clinic(), orderB, ist(8, 0)).id()).isEqualTo(idB);
        assertThat(doseRowsOfOrder(b.clinic(), orderB)).isOne();
    }

    // ---------------- 17 and 18 : the order after a dose ----------------

    @Test
    void item17StoppingAnActiveOrderThatHasADoseKeepsTheDoseReadableAndTheTruncateOfTheOrderTableIsRefusedBecauseOfTheForeignKey()
            throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "bd");
        clockAt(8, 5);
        JsonNode a = doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        stopOk(w.doctor().auth(), order);
        assertThat(state(w.clinic(), order).status()).isEqualTo("STOPPED");
        db().inTenant(w.clinic().tenantId(), c -> {
            assertThat(DbAccess.text(c, "select outcome from ipd.dose_administration where order_id = ?::uuid", order)).isEqualTo("GIVEN");
            return null;
        });
        assertThat(doseDb(w.clinic(), order, ist(8, 0)).id()).isEqualTo(a.get("dose").get("id").asText());
        // delete stays refused (privilege, trigger); truncating the order table alone now needs the dose table in the statement
        db().inTenant(w.clinic().tenantId(), c -> {
            is(err(c, "delete from ipd.medicine_order" + BY_ID, order), "42501");
            is(err(c, "truncate ipd.medicine_order"), "42501");
            return null;
        });
        asOwner(false, DOSE_TABLES, c -> {
            is(err(c, "truncate ipd.medicine_order"), "0A000");
            is(err(c, "truncate ipd.medicine_order, ipd.dose_administration"), "42501", "append-only");
            is(err(c, "truncate ipd.medicine_order cascade"), "42501", "append-only");
            is(err(c, "delete from ipd.medicine_order" + BY_ID, order), "42501", "append-only");
            return null;
        });
        assertThat(state(w.clinic(), order).status()).isEqualTo("STOPPED");
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    @Test
    void item18TheOrderGuardStillRefusesChangingIdOrAdmissionOfAnOrderAndTheNewUniquePairExists() throws Exception {
        Fx fx = fx();
        asOwner(false, DOSE_TABLES, c -> {
            refusedByOrderGuard(err(c, "update ipd.medicine_order set admission_id = ?::uuid, version = version + 1" + BY_ID,
                    fx.a2().adm().id(), fx.pcmA()));
            refusedByOrderGuard(err(c, "update ipd.medicine_order set id = gen_random_uuid(), version = version + 1" + BY_ID, fx.pcmA()));
            return null;
        });
        db().asApp(c -> {
            String def = DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' and indexname = "
                    + "'uq_medicine_order_tenant_id_admission'");
            assertThat(def).contains("UNIQUE").contains("tenant_id, id, admission_id");
            // really unique: the same triple twice is refused by the constraint
            assertThat(DbAccess.text(c, "select contype::text from pg_constraint where conname = 'uq_medicine_order_tenant_id_admission'"))
                    .isEqualTo("u");
            return null;
        });
    }

    private static void refusedByOrderGuard(SqlErr e) {
        assertThat(e.state()).as(e.message()).isEqualTo("23514");
        assertThat(e.message()).containsIgnoringCase("cannot change");
    }

    // ---------------- 19 : the catalog ----------------

    @Test
    void item19TheCatalogShowsRlsPoliciesColumnGrantsTriggersFunctionConstraintsIndexesAndTheTableCommentAsDesigned() throws Exception {
        db().asApp(c -> {
            try (PreparedStatement ps = c.prepareStatement("select relrowsecurity, relforcerowsecurity from pg_class cl join "
                    + "pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = 'dose_administration'");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("enabled").isTrue();
                assertThat(rs.getBoolean(2)).as("forced").isTrue();
            }
            assertThat(policies(c)).containsExactlyInAnyOrder("p_dose_administration_select:r:sahaj_app",
                    "p_dose_administration_insert:a:sahaj_app");
            assertThat(DbAccess.text(c, "select string_agg(pg_get_expr(p.polqual, p.polrelid), ',') from pg_policy p join pg_class cl on "
                    + "cl.oid = p.polrelid where cl.relname = 'dose_administration' and p.polcmd = 'r'")).contains("current_tenant");
            assertThat(DbAccess.text(c, "select pg_get_expr(p.polwithcheck, p.polrelid) from pg_policy p join pg_class cl on "
                    + "cl.oid = p.polrelid where cl.relname = 'dose_administration' and p.polcmd = 'a'")).contains("current_tenant");

            Set<String> insert = Set.of("admission_id", "order_id", "client_key", "slot_at", "outcome", "reason_key", "recorded_by",
                    "second_check_by", "high_alert", "identity_scan", "identity_confirmed");
            Set<String> insertable = new HashSet<>();
            Set<String> updatable = new HashSet<>();
            List<String> all = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select column_name from information_schema.columns where table_schema = "
                    + "'ipd' and table_name = 'dose_administration'"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(rs.getString(1));
                }
            }
            assertThat(all).hasSize(14);
            for (String column : all) {
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd.dose_administration', ?, "
                        + "'INSERT')::text", column))) {
                    insertable.add(column);
                }
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd.dose_administration', ?, "
                        + "'UPDATE')::text", column))) {
                    updatable.add(column);
                }
                assertThat(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd.dose_administration', ?, 'SELECT')::text",
                        column)).as("select " + column).isEqualTo("true");
            }
            assertThat(insertable).isEqualTo(insert);
            assertThat(updatable).isEmpty();
            assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'ipd.dose_administration', 'SELECT')::text")).isEqualTo("true");
            for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'ipd.dose_administration', ?)::text", privilege))
                        .as(privilege).isEqualTo("false");
            }
            assertThat(DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace, "
                    + "aclexplode(cl.relacl) a where n.nspname = 'ipd' and cl.relname = 'dose_administration' and a.grantee = 0"))
                    .as("grants to PUBLIC").isZero();

            List<String> triggers = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select t.tgname || ':' || t.tgenabled::text from pg_trigger t join pg_class cl "
                    + "on cl.oid = t.tgrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = "
                    + "'dose_administration' and not t.tgisinternal"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(rs.getString(1));
                }
            }
            assertThat(triggers).containsExactlyInAnyOrder("trg_dose_administration_no_update_delete:O",
                    "trg_dose_administration_no_truncate:O", "trg_dose_administration_guard_insert:O");
            try (PreparedStatement ps = c.prepareStatement("select prosecdef, proconfig::text, has_function_privilege('sahaj_app', p.oid, "
                    + "'EXECUTE'), (p.proacl is not null and not exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0)) from "
                    + "pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'ipd' and p.proname = "
                    + "'guard_dose_administration_insert'"); ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("invoker").isFalse();
                assertThat(rs.getString(2)).contains("search_path=pg_catalog");
                assertThat(rs.getBoolean(3)).as("sahaj_app execute").isFalse();
                assertThat(rs.getBoolean(4)).as("public execute").isTrue();
            }
            for (String name : new String[] {"uq_dose_administration_order_slot", "uq_dose_administration_tenant_client_key",
                    "uq_medicine_order_tenant_id_admission"}) {
                assertThat(DbAccess.count(c, "select count(*) from pg_constraint where conname = ?", name)).as(name).isOne();
            }
            for (String name : new String[] {"ck_dose_administration_outcome", "ck_dose_administration_reason",
                    "ck_dose_administration_slot_minute", "ck_dose_administration_reason_by_outcome",
                    "ck_dose_administration_refused_reason", "ck_dose_administration_second_check_outcome",
                    "ck_dose_administration_second_check_other", "ck_dose_administration_high_alert_second_check",
                    "ck_dose_administration_identity_scan", "ck_dose_administration_identity_scan_by_outcome",
                    "ck_dose_administration_identity_confirmed_by_outcome", "ck_dose_administration_identity_confirmed_only_given"}) {
                assertThat(DbAccess.count(c, "select count(*) from pg_constraint where conname = ?", name)).as(name).isOne();
            }
            assertThat(DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' and indexname = "
                    + "'ix_dose_administration_tenant_admission_slot'")).contains("tenant_id, admission_id, slot_at");
            assertThat(DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' and indexname = "
                    + "'uq_dose_administration_order_slot'")).contains("UNIQUE").contains("tenant_id, order_id, slot_at");
            assertThat(DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' and indexname = "
                    + "'uq_dose_administration_tenant_client_key'")).contains("UNIQUE").contains("tenant_id, client_key");
            String comment = DbAccess.text(c, "select obj_description('ipd.dose_administration'::regclass)");
            assertThat(comment).contains("slot_at").containsIgnoringCase("partition").containsIgnoringCase("append-only");
            // nothing points at the table by a foreign key (it can be partitioned later)
            assertThat(DbAccess.count(c, "select count(*) from pg_constraint where contype = 'f' and confrelid = "
                    + "'ipd.dose_administration'::regclass")).isZero();
            return null;
        });
    }

    private static List<String> policies(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select p.polname || ':' || p.polcmd::text || ':' || coalesce((select "
                + "string_agg(r.rolname, ',') from pg_roles r where r.oid = any (p.polroles)), '') from pg_policy p join pg_class cl on "
                + "cl.oid = p.polrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = "
                + "'dose_administration'"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ---------------- 20 : query plans ----------------

    @Test
    void item20TheMarOfTodayTheLastDosePerOrderTheSlotProbeAndTheReplayLookupUseTheirIndexesWith200000Rows() throws Exception {
        Ward w = ward();
        asOwner(false, DOSE_TABLES, c -> {
            for (String fk : new String[] {"fk_dose_administration_admission", "fk_dose_administration_order", "fk_dose_administration_tenant"}) {
                DbAccess.execute(c, "alter table ipd.dose_administration drop constraint " + fk);
            }
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger trg_dose_administration_guard_insert");
            // 200,000 rows: 20,000 orders x 10 slots, 2,000 admissions; one in ten orders belongs to this clinic, the rest to 20 others
            DbAccess.execute(c, "insert into ipd.dose_administration (tenant_id, admission_id, order_id, client_key, slot_at, outcome, "
                    + "reason_key, recorded_by, high_alert) select (case when (g % 20000) % 10 = 0 then ?::uuid else "
                    + "md5(((g % 20000) % 20)::text)::uuid end), md5(((g % 20000) / 10)::text)::uuid, md5((g % 20000)::text)::uuid, "
                    + "gen_random_uuid(), date_trunc('minute', now()) - ((g / 20000) || ' hours')::interval - ((g % 20000) || ' minutes')::interval, "
                    + "'HELD', 'HELD_DOCTOR', gen_random_uuid(), false from generate_series(1, 200000) g", w.clinic().tenantId().toString());
            DbAccess.execute(c, "analyze ipd.dose_administration");
            String t = w.clinic().tenantId().toString();
            String admission = DbAccess.text(c, "select admission_id::text from ipd.dose_administration where tenant_id = ?::uuid limit 1", t);
            String order = DbAccess.text(c, "select order_id::text from ipd.dose_administration where tenant_id = ?::uuid and admission_id = "
                    + "?::uuid limit 1", t, admission);
            String key = DbAccess.text(c, "select client_key::text from ipd.dose_administration where tenant_id = ?::uuid limit 1", t);
            String slot = DbAccess.text(c, "select slot_at::text from ipd.dose_administration where tenant_id = ?::uuid and order_id = "
                    + "?::uuid limit 1", t, order);

            String mar = explain(c, "select id from ipd.dose_administration where tenant_id = '" + t + "'::uuid and admission_id = '"
                    + admission + "'::uuid and slot_at >= now() - interval '1 day' and slot_at < now() + interval '1 day' "
                    + "order by slot_at, id");
            assertThat(mar).contains("ix_dose_administration_tenant_admission_slot").doesNotContain("Seq Scan");
            String last = explain(c, "select distinct on (order_id) id from ipd.dose_administration where tenant_id = '" + t
                    + "'::uuid and admission_id = '" + admission + "'::uuid order by order_id, slot_at desc");
            assertThat(last).containsAnyOf("ix_dose_administration_tenant_admission_slot", "uq_dose_administration_order_slot")
                    .doesNotContain("Seq Scan");
            String probe = explain(c, "select id from ipd.dose_administration where tenant_id = '" + t + "'::uuid and order_id = '" + order
                    + "'::uuid and slot_at = '" + slot + "'");
            assertThat(probe).contains("uq_dose_administration_order_slot").doesNotContain("Seq Scan");
            String replay = explain(c, "select id from ipd.dose_administration where tenant_id = '" + t + "'::uuid and client_key = '" + key
                    + "'::uuid");
            assertThat(replay).contains("uq_dose_administration_tenant_client_key").doesNotContain("Seq Scan");
            return null;
        });
    }

    private static String explain(Connection c, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("explain " + sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    // ---------------- 21 : two connections (the admission lock of the application skipped) ----------------

    @Test
    void item21aTwoInsertsOfTheSameSlotWithDifferentKeysWithoutTheAdmissionLockMeetTheUniqueIndexAs23505() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        try (Connection one = tenantConnection(w.clinic()); Connection two = tenantConnection(w.clinic())) {
            DbAccess.text(one, INS, given(w.adm().id(), order, S_8AM, false, null));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try {
                    DbAccess.text(two, INS, given(w.adm().id(), order, S_8AM, false, null));
                    two.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState() + " " + e.getMessage();
                }
            });
            assertThat(awaitLockWaiters("%insert into ipd.dose_administration%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).startsWith("23505").contains("uq_dose_administration_order_slot");
        }
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    @Test
    void item21bTheSameClientKeyForTwoSlotsAtTheSameMomentMeetsTheClientKeyUniqueAs23505() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "bd");
        UUID key = UUID.randomUUID();
        try (Connection one = tenantConnection(w.clinic()); Connection two = tenantConnection(w.clinic())) {
            DbAccess.text(one, INS, row(w.adm().id(), order, key, S_8AM, "GIVEN", null, U1, null, false, "SJ-00001", true));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try {
                    DbAccess.text(two, INS, row(w.adm().id(), order, key, S_8PM, "GIVEN", null, U1, null, false, "SJ-00001", true));
                    two.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState() + " " + e.getMessage();
                }
            });
            assertThat(awaitLockWaiters("%insert into ipd.dose_administration%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).startsWith("23505").contains("uq_dose_administration_tenant_client_key");
        }
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    @Test
    void item21cAStopThatIsCommittedBeforeAnInsertMakesTheInsert23514AndADoseInsertedBeforeTheStopCommitsStaysAndTheStopSucceeds()
            throws Exception {
        Ward w = ward();
        String stopsFirst = activeOrder(w, "pcm", "od");
        String givesFirst = activeOrder(w, "panto", "od");
        // stop committed first: the guard refuses the insert
        stopOk(w.doctor().auth(), stopsFirst);
        try (Connection c = tenantConnection(w.clinic())) {
            SqlErr e = err(c, INS, given(w.adm().id(), stopsFirst, S_8AM, false, null));
            is(e, "23514", "dose can be recorded only for an active medicine order");
        }
        // an uncommitted stop does not block the insert (a non-key update and a key-share check do not conflict): both commit
        try (Connection stop = tenantConnection(w.clinic()); Connection give = tenantConnection(w.clinic())) {
            DbAccess.execute(stop, "update ipd.medicine_order set status = 'STOPPED', stopped_by = ?::uuid, version = version + 1 "
                    + "where id = ?::uuid", U3.toString(), givesFirst);
            DbAccess.text(give, INS, given(w.adm().id(), givesFirst, S_8AM, false, null));
            give.commit();
            stop.commit();
        }
        assertThat(state(w.clinic(), givesFirst).status()).isEqualTo("STOPPED");
        assertThat(doseRowsOfOrder(w.clinic(), givesFirst)).as("the dose stays").isOne();
        assertThat(doseRowsOfOrder(w.clinic(), stopsFirst)).isZero();
    }

    @Test
    void item21dADischargeCommittedBeforeAnInsertMakesTheInsert23514() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        try (Connection one = tenantConnection(w.clinic()); Connection two = tenantConnection(w.clinic())) {
            DbAccess.execute(one, "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid", w.adm().id());
            one.commit();
            SqlErr e = err(two, INS, given(w.adm().id(), order, S_8AM, false, null));
            is(e, "23514", "dose can be recorded only");
        }
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
    }
}
