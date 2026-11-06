package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-31 {@code ipd.drug}, {@code ipd.drug_interaction} and {@code ipd.medicine_order} on the real {@code sahaj_test}
 * database (docs/database/SAH-31.md "QA test list", items 1 to 21): the seed, the statements the application sends, every
 * transition and its refusals, the checks as the second line, the grants, the append-only triggers, row level security on
 * the tenant table and the two reference tables, the catalog, the query plans with 100,000 rows, and two connections for
 * the row-lock cases (the API-level races are in {@link MedicineConcurrencyIT}). Rows are made by the application role with
 * a bound clinic, or by the table owner, in a transaction that is rolled back; committed state (a discharged admission, the
 * other clinic's order) is made through the real API. Flyway from scratch is not repeated here (a clean of the shared test
 * database would destroy the other tests' data). Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineOrderTableIT extends MedicineSupport {

    private static final String[] TABLES = {"ipd.medicine_order", "ipd.admission"};
    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();

    private static final String INS_APP = "insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, "
            + "ordered_by, override_reason_key, warnings) values (?::uuid, gen_random_uuid(), ?, 'od', ?::uuid, null, '{}') "
            + "returning id::text";
    private static final String INS_OWNER = "insert into ipd.medicine_order (tenant_id, admission_id, client_key, drug_key, "
            + "frequency_key, ordered_by) values (?::uuid, ?::uuid, gen_random_uuid(), ?, 'od', ?::uuid) returning id::text";
    private static final String ACT = "update ipd.medicine_order set status = 'ACTIVE', verified_by = ?::uuid, version = version + 1 "
            + "where id = ?::uuid and status = 'PENDING'";
    private static final String REJ = "update ipd.medicine_order set status = 'REJECTED', rejected_by = ?::uuid, "
            + "reject_reason_key = ?, version = version + 1 where id = ?::uuid and status = 'PENDING'";
    private static final String STP = "update ipd.medicine_order set status = 'STOPPED', stopped_by = ?::uuid, version = version + 1 "
            + "where id = ?::uuid and status in ('PENDING', 'ACTIVE')";
    private static final String SET = "update ipd.medicine_order set ";
    private static final String BY_ID = " where id = ?::uuid";

    @FunctionalInterface
    private interface Body {
        void run(Connection c, Clinic clinic, Adm adm, boolean owner) throws Exception;
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

    private static String val(Connection c, String id, String expression) throws SQLException {
        return DbAccess.text(c, "select (" + expression + ")::text from ipd.medicine_order" + BY_ID, id);
    }

    private static String insert(Connection c, Clinic clinic, Adm adm, String drug, boolean owner) throws SQLException {
        return owner ? DbAccess.text(c, INS_OWNER, clinic.tenantId().toString(), adm.id(), drug, U1.toString())
                : DbAccess.text(c, INS_APP, adm.id(), drug, U1.toString());
    }

    private static void is(SqlErr e, String state, String... fragments) {
        assertThat(e.state()).as(e.message()).isEqualTo(state);
        for (String f : fragments) {
            assertThat(e.message()).containsIgnoringCase(f);
        }
    }

    private static void refused(SqlErr e, String... fragments) {
        is(e, "23514", fragments);
    }

    private void run(boolean owner, Body body) throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        if (owner) {
            asOwner(false, TABLES, c -> {
                try {
                    body.run(c, clinic, adm, true);
                } catch (SQLException | RuntimeException | AssertionError e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        } else {
            db().inTenant(clinic.tenantId(), c -> {
                try {
                    body.run(c, clinic, adm, false);
                } catch (SQLException | RuntimeException | AssertionError e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        }
    }

    // ---------------- 1 : the seed ----------------

    @Test
    void item1TheSeedIsTheFourteenMedicinesAndThreeInteractionsOfTheMockupReadableWithAndWithoutATenant() throws Exception {
        String[][] expected = {
                {"pcm", "Tab. Paracetamol 650 mg", "Oral", "{}", "", "1"}, {"panto", "Inj. Pantoprazole 40 mg", "IV", "{}", "", "2"},
                {"ondan", "Inj. Ondansetron 4 mg", "IV", "{}", "", "3"}, {"ns", "IV Normal saline 500 ml", "IV", "{}", "", "4"},
                {"ceft", "Inj. Ceftriaxone 1 g", "IV", "{cephalosporin}", "", "5"},
                {"amox", "Tab. Amoxicillin + clavulanate 625 mg", "Oral", "{penicillin}", "", "6"},
                {"pip", "Inj. Piperacillin + tazobactam 4.5 g", "IV", "{penicillin}", "", "7"},
                {"cotri", "Tab. Cotrimoxazole 960 mg", "Oral", "{sulfa}", "", "8"},
                {"asp", "Tab. Aspirin 75 mg", "Oral", "{antiplatelet}", "", "9"},
                {"ibu", "Tab. Ibuprofen 400 mg", "Oral", "{nsaid}", "", "10"},
                {"met", "Tab. Metformin 500 mg", "Oral", "{}", "", "11"},
                {"hep", "Inj. Heparin 5,000 IU", "SC", "{anticoag}", "Anticoagulant", "12"},
                {"ins", "Inj. Regular insulin, sliding scale", "SC", "{insulin}", "Insulin", "13"},
                {"kcl", "Inj. Potassium chloride 20 mmol in 500 ml", "IV infusion", "{potassium}", "Concentrated electrolyte", "14"}};
        Clinic clinic = hospital();
        for (boolean bound : new boolean[] {false, true}) {
            DbAccess.SqlWork<Void> work = c -> {
                List<String> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("select key, name, route, classes::text, coalesce(high_alert, ''), "
                        + "sort_order::text as so from ipd.drug order by ipd.drug.sort_order");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6)));
                    }
                }
                List<String> want = new ArrayList<>();
                for (String[] e : expected) {
                    want.add(String.join("|", e));
                }
                assertThat(rows).isEqualTo(want);
                List<String> inter = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("select class_a, class_b, severity, text, sort_order::text as so from "
                        + "ipd.drug_interaction order by ipd.drug_interaction.sort_order"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inter.add(String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                    }
                }
                assertThat(inter).containsExactly("anticoag|antiplatelet|MAJOR|Bleeding risk: heparin with aspirin|1",
                        "anticoag|nsaid|MAJOR|Bleeding risk: heparin with an NSAID|2",
                        "antiplatelet|nsaid|MODERATE|Stomach bleeding risk: aspirin with an NSAID|3");
                return null;
            };
            if (bound) {
                db().inTenant(clinic.tenantId(), work);
            } else {
                db().asApp(work);
            }
        }
    }

    // ---------------- 2 to 4 : insert, transitions, reorder ----------------

    @Test
    void item2TheInsertTakesTheDefaultsTheSessionClinicAndTheDatabaseClockAndAcceptsWarningsWithAReasonOrNeither() throws Exception {
        run(false, (c, clinic, adm, owner) -> {
            String id = insert(c, clinic, adm, "pcm", false);
            assertThat(val(c, id, "status")).isEqualTo("PENDING");
            assertThat(val(c, id, "version")).isEqualTo("0");
            assertThat(val(c, id, "tenant_id")).isEqualTo(clinic.tenantId().toString());
            assertThat(val(c, id, "abs(extract(epoch from (clock_timestamp() - ordered_at))) < 1")).isEqualTo("true");
            assertThat(val(c, id, "verified_by is null and verified_at is null and rejected_by is null and stopped_by is null "
                    + "and override_reason_key is null and cardinality(warnings) = 0")).isEqualTo("true");
            DbAccess.execute(c, "insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by, "
                    + "override_reason_key, warnings) values (?::uuid, gen_random_uuid(), 'hep', 'bd', ?::uuid, 'CARDIOLOGY_STENT', "
                    + "array['Major: x', 'Moderate: y'])", adm.id(), U1.toString());
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order where override_reason_key = 'CARDIOLOGY_STENT'"
                    + " and cardinality(warnings) = 2")).isOne();
        });
    }

    @Test
    void item3EveryAllowedTransitionAddsOneToTheVersionStampsTheDatabaseClockAndKeepsTheEarlierStamps() throws Exception {
        for (boolean owner : new boolean[] {false, true}) {
            run(owner, (c, clinic, adm, o) -> {
                String a = insert(c, clinic, adm, "pcm", o);
                String b = insert(c, clinic, adm, "panto", o);
                String d = insert(c, clinic, adm, "ondan", o);
                String e = insert(c, clinic, adm, "ns", o);
                DbAccess.execute(c, ACT, U2.toString(), a);
                assertThat(val(c, a, "status || version")).isEqualTo("ACTIVE1");
                assertThat(val(c, a, "abs(extract(epoch from (clock_timestamp() - verified_at))) < 2")).isEqualTo("true");
                assertThat(val(c, a, "verified_by")).isEqualTo(U2.toString());
                DbAccess.execute(c, REJ, U2.toString(), "ALLERGY_CONFLICT", b);
                assertThat(val(c, b, "status || version || reject_reason_key")).isEqualTo("REJECTED1ALLERGY_CONFLICT");
                assertThat(val(c, b, "abs(extract(epoch from (clock_timestamp() - rejected_at))) < 2")).isEqualTo("true");
                DbAccess.execute(c, STP, U2.toString(), d);
                assertThat(val(c, d, "status || version")).isEqualTo("STOPPED1");
                assertThat(val(c, d, "verified_by is null and verified_at is null and stopped_at is not null")).isEqualTo("true");
                // ACTIVE to STOPPED: the verification stays and the stop is not earlier
                String verifiedAt = val(c, a, "verified_at");
                DbAccess.execute(c, STP, U2.toString(), a);
                assertThat(val(c, a, "status || version")).isEqualTo("STOPPED2");
                assertThat(val(c, a, "verified_at")).isEqualTo(verifiedAt);
                assertThat(val(c, a, "stopped_at >= verified_at")).isEqualTo("true");
                // a time sent with the statement is overwritten by the trigger for a column the role may not name: the owner may
                if (o) {
                    DbAccess.execute(c, "update ipd.medicine_order set status = 'STOPPED', stopped_by = ?::uuid, "
                            + "stopped_at = now() - interval '1 year', version = version + 1" + BY_ID, U2.toString(), e);
                    assertThat(val(c, e, "abs(extract(epoch from (clock_timestamp() - stopped_at))) < 2")).isEqualTo("true");
                }
            });
        }
    }

    @Test
    void item4ADrugCanBeOrderedAgainAfterRejectOrStopAndTwoAdmissionsOfOneClinicMayOrderTheSameDrug() throws Exception {
        Clinic clinic = hospital();
        Adm one = admitWithAllergy(clinic, "None known");
        Adm two = admitWithAllergy(clinic, "None known");
        db().inTenant(clinic.tenantId(), c -> {
            String a = insert(c, clinic, one, "pcm", false);
            insert(c, clinic, two, "pcm", false); // another admission
            DbAccess.execute(c, REJ, U2.toString(), "WRONG_DOSE", a);
            String b = insert(c, clinic, one, "pcm", false); // after reject
            DbAccess.execute(c, STP, U2.toString(), b);
            insert(c, clinic, one, "pcm", false); // after stop
            return null;
        });
    }

    // ---------------- 5 to 10 : refusals ----------------

    @Test
    void item5ARejectedOrStoppedOrderCannotBeUpdatedAgainEvenByVersionAloneForTheApplicationAndTheOwner() throws Exception {
        for (boolean owner : new boolean[] {false, true}) {
            run(owner, (c, clinic, adm, o) -> {
                String r = insert(c, clinic, adm, "pcm", o);
                String s = insert(c, clinic, adm, "panto", o);
                DbAccess.execute(c, REJ, U2.toString(), "WRONG_DOSE", r);
                DbAccess.execute(c, STP, U2.toString(), s);
                for (String id : new String[] {r, s}) {
                    refused(err(c, SET + "version = version + 1" + BY_ID, id), "medicine order is closed");
                    refused(err(c, SET + "status = 'STOPPED', stopped_by = ?::uuid, version = version + 1" + BY_ID, U2.toString(), id),
                            "medicine order");
                }
                assertThat(val(c, r, "status || version")).isEqualTo("REJECTED1");
                assertThat(val(c, s, "status || version")).isEqualTo("STOPPED1");
            });
        }
    }

    @Test
    void item6DisallowedStatusPairsAreRefusedAndAnUnknownStatusToo() throws Exception {
        run(true, (c, clinic, adm, o) -> {
            String active = insert(c, clinic, adm, "pcm", true);
            String pending = insert(c, clinic, adm, "panto", true);
            DbAccess.execute(c, ACT, U2.toString(), active);
            String[] fromActive = {"status = 'PENDING', version = version + 1", "status = 'ACTIVE', version = version + 1",
                    "status = 'REJECTED', rejected_by = '" + U2 + "', reject_reason_key = 'WRONG_DOSE', version = version + 1"};
            for (String change : fromActive) {
                refused(err(c, SET + change + BY_ID, active), "status change is not allowed");
            }
            refused(err(c, SET + "status = 'PENDING'" + BY_ID, pending), "status change is not allowed");
            refused(err(c, SET + "status = 'PENDING', version = version + 1" + BY_ID, pending), "status change is not allowed");
            refused(err(c, SET + "version = version + 1" + BY_ID, pending), "status change is not allowed");
            refused(err(c, SET + "status = 'GONE', version = version + 1" + BY_ID, pending));
            assertThat(val(c, pending, "status || version")).isEqualTo("PENDING0");
            assertThat(val(c, active, "status || version")).isEqualTo("ACTIVE1");
        });
    }

    @Test
    void item7TheVersionMustGoUpByExactlyOneOnATransition() throws Exception {
        for (boolean owner : new boolean[] {false, true}) {
            run(owner, (c, clinic, adm, o) -> {
                String id = insert(c, clinic, adm, "pcm", o);
                for (String version : new String[] {"version", "version + 2", "version - 1", "3000000000", "0"}) {
                    refused(err(c, SET + "status = 'ACTIVE', verified_by = '" + U2 + "', version = " + version + BY_ID, id), "medicine order");
                }
                assertThat(val(c, id, "status || version")).isEqualTo("PENDING0");
                DbAccess.execute(c, ACT, U2.toString(), id);
            });
        }
    }

    @Test
    void item8TheOrdererCanNeverVerifyTheirOwnOrderNotEvenTheOwnerOfTheTable() throws Exception {
        for (boolean owner : new boolean[] {false, true}) {
            run(owner, (c, clinic, adm, o) -> {
                String id = insert(c, clinic, adm, "pcm", o);
                is(err(c, ACT, U1.toString(), id), "23514", "ck_medicine_order_self_verify");
                assertThat(val(c, id, "status")).isEqualTo("PENDING");
                DbAccess.execute(c, ACT, U2.toString(), id); // a different user is fine
                // rejecting your own order is allowed
                String own = insert(c, clinic, adm, "panto", o);
                DbAccess.execute(c, REJ, U1.toString(), "WRONG_DOSE", own);
            });
        }
    }

    @Test
    void item9MissingTransitionFieldsAndAnUnknownRejectReasonAreRefused() throws Exception {
        run(true, (c, clinic, adm, o) -> {
            String id = insert(c, clinic, adm, "pcm", true);
            refused(err(c, SET + "status = 'ACTIVE', version = version + 1" + BY_ID, id), "verifying user");
            refused(err(c, SET + "status = 'REJECTED', reject_reason_key = 'WRONG_DOSE', version = version + 1" + BY_ID, id), "rejecting user");
            refused(err(c, SET + "status = 'REJECTED', rejected_by = '" + U2 + "', version = version + 1" + BY_ID, id), "rejecting user");
            refused(err(c, SET + "status = 'STOPPED', version = version + 1" + BY_ID, id), "stopping user");
            is(err(c, REJ, U2.toString(), "OTHER", id), "23514", "ck_medicine_order_reject_reason");
            assertThat(val(c, id, "status || version")).isEqualTo("PENDING0");
        });
    }

    @Test
    void item10FieldsOfAnotherTransitionAreRefused() throws Exception {
        run(true, (c, clinic, adm, o) -> {
            String id = insert(c, clinic, adm, "pcm", true);
            refused(err(c, SET + "status = 'REJECTED', rejected_by = '" + U2 + "', reject_reason_key = 'WRONG_DOSE', "
                    + "stopped_by = '" + U2 + "', version = version + 1" + BY_ID, id), "medicine order");
            refused(err(c, SET + "status = 'ACTIVE', verified_by = '" + U2 + "', rejected_by = '" + U2 + "', version = version + 1" + BY_ID, id),
                    "medicine order");
            refused(err(c, SET + "status = 'STOPPED', stopped_by = '" + U2 + "', verified_by = '" + U2 + "', version = version + 1" + BY_ID, id),
                    "medicine order");
            assertThat(val(c, id, "status || version")).isEqualTo("PENDING0");
        });
    }

    // ---------------- 11 to 15 : admission state, columns, checks, keys ----------------

    @Test
    void item11NothingIsPlacedOrChangedForAnAdmissionThatIsNotAdmitted() throws Exception {
        Clinic clinic = hospital();
        Adm live = admitWithAllergy(clinic, "None known");
        Adm gone = admitWithAllergy(clinic, "None known");
        String order = orderIdOf(place(clinic.owner(), gone.id(), "pcm"));
        String stopMe = orderIdOf(place(clinic.owner(), gone.id(), "panto"));
        String active = orderIdOf(place(clinic.as("DOCTOR"), gone.id(), "ondan"));
        discharge(clinic, gone.id());
        db().inTenant(clinic.tenantId(), c -> {
            refused(err(c, INS_APP, gone.id(), "met", U1.toString()), "only for an admitted patient");
            for (String sql : new String[] {ACT, STP}) {
                refused(err(c, sql, U2.toString(), order), "only while the patient is admitted");
            }
            refused(err(c, REJ, U2.toString(), "WRONG_DOSE", order), "only while the patient is admitted");
            refused(err(c, STP, U2.toString(), stopMe), "only while the patient is admitted");
            assertThat(val(c, order, "status || version")).isEqualTo("PENDING0");
            assertThat(val(c, active, "status")).isEqualTo("PENDING");
            insert(c, clinic, live, "met", false); // an admitted one still works
            return null;
        });
    }

    @Test
    void item12AnInsertWithAnotherStatusIsRefusedByTheOwnerAndTheApplicationCannotNameTheDatabaseColumns() throws Exception {
        run(true, (c, clinic, adm, o) -> refused(err(c, "insert into ipd.medicine_order (tenant_id, admission_id, client_key, drug_key, "
                + "frequency_key, ordered_by, status) values (?::uuid, ?::uuid, gen_random_uuid(), 'pcm', 'od', ?::uuid, 'ACTIVE')",
                clinic.tenantId().toString(), adm.id(), U1.toString()), "must start as pending"));
        run(false, (c, clinic, adm, o) -> {
            String[][] named = {{"status", "'PENDING'"}, {"ordered_at", "now()"}, {"version", "0"}, {"verified_by", "gen_random_uuid()"},
                    {"id", "gen_random_uuid()"}, {"tenant_id", "'" + clinic.tenantId() + "'::uuid"}, {"verified_at", "now()"},
                    {"rejected_by", "gen_random_uuid()"}, {"stopped_by", "gen_random_uuid()"}};
            for (String[] n : named) {
                is(err(c, "insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by, " + n[0]
                        + ") values (?::uuid, gen_random_uuid(), 'pcm', 'od', ?::uuid, " + n[1] + ")", adm.id(), U1.toString()), "42501");
            }
        });
    }

    @Test
    void item13ColumnsOutsideTheUpdateGrantAreRefusedToTheApplicationAndTheIdentityOnesToTheOwner() throws Exception {
        run(false, (c, clinic, adm, o) -> {
            String id = insert(c, clinic, adm, "pcm", false);
            String[] columns = {"verified_at = now()", "rejected_at = now()", "stopped_at = now()", "ordered_at = now()",
                    "ordered_by = gen_random_uuid()", "drug_key = 'panto'", "warnings = '{}'", "override_reason_key = null",
                    "admission_id = gen_random_uuid()", "client_key = gen_random_uuid()", "frequency_key = 'bd'", "id = gen_random_uuid()",
                    "tenant_id = gen_random_uuid()"};
            for (String column : columns) {
                is(err(c, SET + column + BY_ID, id), "42501");
            }
        });
        run(true, (c, clinic, adm, o) -> {
            String id = insert(c, clinic, adm, "pcm", true);
            String[] identity = {"drug_key = 'panto'", "warnings = array['x'], override_reason_key = 'CARDIOLOGY_STENT'",
                    "ordered_by = gen_random_uuid()", "frequency_key = 'bd'", "client_key = gen_random_uuid()",
                    "ordered_at = now() - interval '1 day'", "admission_id = gen_random_uuid()", "tenant_id = gen_random_uuid()",
                    "id = gen_random_uuid()"};
            for (String column : identity) {
                refused(err(c, SET + column + ", version = version + 1" + BY_ID, id), "identity and order fields cannot change");
            }
            // a direct verified_at on a transition that is not a verification
            refused(err(c, SET + "status = 'STOPPED', stopped_by = '" + U2 + "', verified_at = now(), version = version + 1" + BY_ID, id),
                    "medicine order");
        });
    }

    @Test
    void item14TheChecksAreTheSecondLineBehindTheTriggers() throws Exception {
        run(true, (c, clinic, adm, o) -> {
            String t = clinic.tenantId().toString();
            String base = "insert into ipd.medicine_order (tenant_id, admission_id, client_key, drug_key, frequency_key, ordered_by, "
                    + "override_reason_key, warnings) values (?::uuid, ?::uuid, gen_random_uuid(), 'pcm', ";
            String tail = ", ?::uuid, ";
            is(err(c, base + "'qd'" + tail + "null, '{}')", t, adm.id(), U1.toString()), "23514", "ck_medicine_order_frequency");
            is(err(c, base + "'od'" + tail + "'OTHER', array['x'])", t, adm.id(), U1.toString()), "23514", "ck_medicine_order_override_reason");
            is(err(c, base + "'od'" + tail + "null, array['x'])", t, adm.id(), U1.toString()), "23514", "ck_medicine_order_override_warnings");
            is(err(c, base + "'od'" + tail + "'BENEFIT_MONITORED', '{}')", t, adm.id(), U1.toString()), "23514",
                    "ck_medicine_order_override_warnings");
            is(err(c, base + "'od'" + tail + "'BENEFIT_MONITORED', array_fill('x'::text, array[11]))", t, adm.id(), U1.toString()), "23514",
                    "ck_medicine_order_warnings_count");
            for (String bad : new String[] {"array['']", "array[repeat('x', 301)]", "array[null::text]", "array[['a'],['b']]"}) {
                is(err(c, base + "'od'" + tail + "'BENEFIT_MONITORED', " + bad + ")", t, adm.id(), U1.toString()), "23514",
                        "ck_medicine_order_warnings_text");
            }
            // 300 characters and ten elements are accepted
            DbAccess.execute(c, base + "'od'" + tail + "'BENEFIT_MONITORED', array[repeat('x', 300)])", t, adm.id(), U1.toString());
            DbAccess.execute(c, base.replace("'pcm'", "'panto'") + "'od'" + tail + "'BENEFIT_MONITORED', array_fill('x'::text, array[10]))", t,
                    adm.id(), U1.toString());
        });
    }

    @Test
    void item15AnUnknownDrugIsAForeignKeyErrorAndAnAdmissionOfAnotherClinicOrNoAdmissionIsTheAdmissionForeignKeyNotAGuardError()
            throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Adm inA = admitWithAllergy(a, "None known");
        Adm inB = admitWithAllergy(b, "None known");
        db().inTenant(a.tenantId(), c -> {
            is(err(c, INS_APP, inA.id(), "zzz", U1.toString()), "23503", "fk_medicine_order_drug");
            is(err(c, INS_APP, inB.id(), "pcm", U1.toString()), "23503", "fk_medicine_order_admission");
            is(err(c, INS_APP, UUID.randomUUID().toString(), "pcm", U1.toString()), "23503", "fk_medicine_order_admission");
            return null;
        });
    }

    @Test
    void item16TheClientKeyIsUniquePerClinicAndOnlyOneLiveOrderPerDrugAndAdmissionIsAllowed() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Adm inA = admitWithAllergy(a, "None known");
        Adm inB = admitWithAllergy(b, "None known");
        UUID key = UUID.randomUUID();
        String insertKey = "insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by) "
                + "values (?::uuid, ?::uuid, ?, 'od', ?::uuid)";
        db().inTenant(a.tenantId(), c -> {
            DbAccess.execute(c, insertKey, inA.id(), key.toString(), "pcm", U1.toString());
            is(err(c, insertKey, inA.id(), key.toString(), "panto", U1.toString()), "23505", "uq_medicine_order_tenant_client_key");
            // a live order of the same drug: PENDING, then still counts after ACTIVE
            String id = DbAccess.text(c, "select id::text from ipd.medicine_order where client_key = ?::uuid", key.toString());
            is(err(c, INS_APP, inA.id(), "pcm", U1.toString()), "23505", "ux_medicine_order_tenant_admission_drug_live");
            DbAccess.execute(c, ACT, U2.toString(), id);
            is(err(c, INS_APP, inA.id(), "pcm", U1.toString()), "23505", "ux_medicine_order_tenant_admission_drug_live");
            DbAccess.execute(c, STP, U2.toString(), id);
            insert(c, a, inA, "pcm", false); // after STOPPED it is free
            return null;
        });
        db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, insertKey, inB.id(), key.toString(), "pcm", U1.toString()); // the same key in another clinic is fine
            return null;
        });
    }

    // ---------------- 17 : delete and truncate ----------------

    @Test
    void item17DeleteAndTruncateAreRefusedForTheApplicationByPrivilegeAndForTheOwnerByTheTriggerAndTheRowsStay() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        String id = orderIdOf(place(clinic.owner(), adm.id(), "pcm"));
        db().inTenant(clinic.tenantId(), c -> {
            is(err(c, "delete from ipd.medicine_order" + BY_ID, id), "42501");
            is(err(c, "truncate ipd.medicine_order"), "42501");
            return null;
        });
        asOwner(false, TABLES, c -> {
            is(err(c, "delete from ipd.medicine_order" + BY_ID, id), "42501", "append-only");
            // SAH-32: ipd.dose_administration now references the order table, so PostgreSQL refuses a TRUNCATE of the order table
            // alone before any trigger runs (0A000). Both tables in one statement (or CASCADE) reach the append-only triggers.
            is(err(c, "truncate ipd.medicine_order"), "0A000", "foreign key");
            is(err(c, "truncate ipd.medicine_order, ipd.dose_administration"), "42501", "append-only");
            is(err(c, "truncate ipd.medicine_order cascade"), "42501", "append-only");
            return null;
        });
        assertThat(state(clinic, id).status()).isEqualTo("PENDING");
    }

    // ---------------- 18 : tenants and the reference tables ----------------

    @Test
    void item18AnotherClinicsOrdersAreInvisibleCannotBeUpdatedOrInsertedAndNothingShowsWithoutATenant() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Adm inB = admitWithAllergy(b, "None known");
        String orderB = orderIdOf(place(b.owner(), inB.id(), "pcm"));
        db().inTenant(a.tenantId(), c -> {
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order" + BY_ID, orderB)).isZero();
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order where tenant_id = ?::uuid", b.tenantId().toString())).isZero();
            try (PreparedStatement ps = c.prepareStatement(ACT)) {
                ps.setString(1, U2.toString());
                ps.setString(2, orderB);
                assertThat(ps.executeUpdate()).as("rows of the other clinic updated").isZero();
            }
            is(err(c, INS_APP, inB.id(), "panto", U1.toString()), "23503"); // its admission is not visible either
            is(err(c, "insert into ipd.medicine_order (tenant_id, admission_id, client_key, drug_key, frequency_key, ordered_by) values "
                    + "(?::uuid, ?::uuid, gen_random_uuid(), 'pcm', 'od', ?::uuid)", b.tenantId().toString(), inB.id(), U1.toString()), "42501");
            return null;
        });
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order" + BY_ID, orderB)).isZero();
            SqlErr noTenant = err(c, INS_APP, inB.id(), "panto", U1.toString());
            assertThat(noTenant.state()).as(noTenant.message()).isIn("42501", "23502");
            return null;
        });
        assertThat(state(b, orderB).status()).isEqualTo("PENDING");
        // a row cannot be moved to another clinic by an update either
        asOwner(false, TABLES, c -> {
            refused(err(c, SET + "tenant_id = ?::uuid, version = version + 1" + BY_ID, a.tenantId().toString(), orderB), "identity");
            return null;
        });
    }

    @Test
    void item18bTheApplicationCannotInsertUpdateOrDeleteTheTwoReferenceTables() throws Exception {
        Clinic clinic = hospital();
        db().inTenant(clinic.tenantId(), c -> {
            is(err(c, "insert into ipd.drug (key, name, route, sort_order) values ('zz', 'Zed', 'Oral', 99)"), "42501");
            is(err(c, "update ipd.drug set name = 'x' where key = 'pcm'"), "42501");
            is(err(c, "delete from ipd.drug where key = 'pcm'"), "42501");
            is(err(c, "truncate ipd.drug"), "42501");
            is(err(c, "insert into ipd.drug_interaction (class_a, class_b, severity, text, sort_order) values ('anticoag', 'insulin', "
                    + "'MAJOR', 'x', 99)"), "42501");
            is(err(c, "update ipd.drug_interaction set text = 'x'"), "42501");
            is(err(c, "delete from ipd.drug_interaction"), "42501");
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug")).isEqualTo(14);
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug_interaction")).isEqualTo(3);
            return null;
        });
    }

    // ---------------- 19 : the catalog ----------------

    @Test
    void item19TheCatalogShowsRlsPoliciesGrantsTriggersFunctionsAndIndexesAsDesigned() throws Exception {
        db().asApp(c -> {
            // RLS enabled and forced on all three tables
            for (String table : new String[] {"medicine_order", "drug", "drug_interaction"}) {
                try (PreparedStatement ps = c.prepareStatement("select relrowsecurity, relforcerowsecurity from pg_class cl join "
                        + "pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = ?")) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1)).as(table + " enabled").isTrue();
                        assertThat(rs.getBoolean(2)).as(table + " forced").isTrue();
                    }
                }
            }
            assertThat(policies(c, "medicine_order")).containsExactlyInAnyOrder("p_medicine_order_select:r:sahaj_app",
                    "p_medicine_order_insert:a:sahaj_app", "p_medicine_order_update:w:sahaj_app");
            assertThat(policies(c, "drug")).containsExactlyInAnyOrder("p_drug_select:r:sahaj_app", "p_drug_migrator:*:sahaj_migrator");
            assertThat(policies(c, "drug_interaction")).containsExactlyInAnyOrder("p_drug_interaction_select:r:sahaj_app",
                    "p_drug_interaction_migrator:*:sahaj_migrator");
            // grants of the tenant table
            Set<String> update = Set.of("status", "verified_by", "rejected_by", "reject_reason_key", "stopped_by", "version");
            Set<String> insert = Set.of("admission_id", "client_key", "drug_key", "frequency_key", "ordered_by", "override_reason_key",
                    "warnings");
            Set<String> updatable = new HashSet<>();
            Set<String> insertable = new HashSet<>();
            List<String> all = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select column_name from information_schema.columns where table_schema = "
                    + "'ipd' and table_name = 'medicine_order'"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(rs.getString(1));
                }
            }
            for (String column : all) {
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd.medicine_order', ?, 'UPDATE')::text", column))) {
                    updatable.add(column);
                }
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd.medicine_order', ?, 'INSERT')::text", column))) {
                    insertable.add(column);
                }
            }
            assertThat(updatable).isEqualTo(update);
            assertThat(insertable).isEqualTo(insert);
            for (String table : new String[] {"ipd.medicine_order", "ipd.drug", "ipd.drug_interaction"}) {
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, 'SELECT')::text", table)).isEqualTo("true");
                for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                    assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, ?)::text", table, privilege)).as(table + " " + privilege)
                            .isEqualTo("false");
                }
            }
            for (String table : new String[] {"ipd.drug", "ipd.drug_interaction"}) {
                for (String privilege : new String[] {"INSERT", "UPDATE"}) {
                    assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, ?)::text", table, privilege)).as(table + " " + privilege)
                            .isEqualTo("false");
                }
            }
            assertThat(DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace, "
                    + "aclexplode(cl.relacl) a where n.nspname = 'ipd' and cl.relname in ('medicine_order', 'drug', 'drug_interaction') "
                    + "and a.grantee = 0")).as("grants to PUBLIC").isZero();
            // triggers and functions
            List<String> triggers = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select t.tgname || ':' || t.tgenabled::text from pg_trigger t join pg_class cl on "
                    + "cl.oid = t.tgrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = "
                    + "'medicine_order' and not t.tgisinternal"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(rs.getString(1));
                }
            }
            assertThat(triggers).contains("trg_medicine_order_no_delete:O", "trg_medicine_order_no_truncate:O",
                    "trg_medicine_order_guard_insert:O", "trg_medicine_order_guard_update:O");
            for (String fn : new String[] {"guard_medicine_order_insert", "guard_medicine_order_update"}) {
                try (PreparedStatement ps = c.prepareStatement("select prosecdef, proconfig::text, has_function_privilege('sahaj_app', p.oid, "
                        + "'EXECUTE'), (p.proacl is not null and not exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0)) from "
                        + "pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'ipd' and p.proname = ?")) {
                    ps.setString(1, fn);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1)).as(fn + " invoker").isFalse();
                        assertThat(rs.getString(2)).contains("search_path=pg_catalog");
                        assertThat(rs.getBoolean(3)).as(fn + " sahaj_app").isFalse();
                        assertThat(rs.getBoolean(4)).as(fn + " public").isTrue();
                    }
                }
            }
            // indexes and their where clauses
            assertThat(indexDef(c, "ux_medicine_order_tenant_admission_drug_live")).contains("UNIQUE").contains("tenant_id, admission_id, drug_key")
                    .contains("PENDING").contains("ACTIVE");
            assertThat(indexDef(c, "ix_medicine_order_tenant_admission_ordered")).contains("tenant_id, admission_id, ordered_at, id");
            assertThat(indexDef(c, "ix_medicine_order_tenant_pending")).contains("tenant_id, ordered_at, id").contains("PENDING");
            assertThat(indexDef(c, "uq_medicine_order_tenant_id")).contains("UNIQUE").contains("tenant_id, id");
            assertThat(indexDef(c, "uq_medicine_order_tenant_client_key")).contains("UNIQUE").contains("tenant_id, client_key");
            return null;
        });
    }

    private static List<String> policies(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select p.polname || ':' || p.polcmd::text || ':' || coalesce((select "
                + "string_agg(r.rolname, ',') from pg_roles r where r.oid = any (p.polroles)), '') from pg_policy p join pg_class cl on "
                + "cl.oid = p.polrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static String indexDef(Connection c, String name) throws SQLException {
        return DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' and indexname = ?", name);
    }

    // ---------------- 20 : query plans ----------------

    @Test
    void item20TheQueueAndTheEmrListUseTheirIndexesWithoutASortAndTheLookupsUseTheirUniqueIndexesWith100000Rows() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        asOwner(false, TABLES, c -> {
            for (String fk : new String[] {"fk_medicine_order_admission", "fk_medicine_order_tenant"}) {
                DbAccess.execute(c, "alter table ipd.medicine_order drop constraint " + fk);
            }
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger trg_medicine_order_guard_insert");
            // 100,000 rows over 20 clinics and many admissions; one in ten waits, the others are stopped (no unique clash)
            DbAccess.execute(c, "insert into ipd.medicine_order (tenant_id, admission_id, client_key, drug_key, frequency_key, status, "
                    + "ordered_by, ordered_at, stopped_by, stopped_at) select (case when g % 20 = 0 then ?::uuid else "
                    + "md5((g % 20)::text)::uuid end), md5((g / 14)::text)::uuid, gen_random_uuid(), "
                    + "(select key from ipd.drug order by sort_order offset (g % 14) limit 1), 'od', "
                    + "case when g % 10 = 0 then 'PENDING' else 'STOPPED' end, gen_random_uuid(), now() - (g || ' seconds')::interval, "
                    + "case when g % 10 = 0 then null else gen_random_uuid() end, "
                    + "case when g % 10 = 0 then null else now() - (g || ' seconds')::interval + interval '1 second' end "
                    + "from generate_series(1, 100000) g", clinic.tenantId().toString());
            DbAccess.execute(c, "analyze ipd.medicine_order");
            String t = clinic.tenantId().toString();
            String queue = explain(c, "select id, admission_id, drug_key, ordered_at from ipd.medicine_order where tenant_id = '" + t
                    + "'::uuid and status = 'PENDING' order by ordered_at, id limit 51");
            assertThat(queue).contains("ix_medicine_order_tenant_pending").doesNotContain("Sort").doesNotContain("Seq Scan");
            String waitingKeys = explain(c, "select id, version from ipd.medicine_order where tenant_id = '" + t + "'::uuid and status = 'PENDING'");
            assertThat(waitingKeys).containsAnyOf("ix_medicine_order_tenant_pending", "ux_medicine_order_tenant_admission_drug_live")
                    .doesNotContain("Seq Scan");
            String admissionId = DbAccess.text(c, "select admission_id::text from ipd.medicine_order where tenant_id = ?::uuid limit 1", t);
            String list = explain(c, "select * from ipd.medicine_order where tenant_id = '" + t + "'::uuid and admission_id = '" + admissionId
                    + "'::uuid order by ordered_at, id");
            assertThat(list).contains("ix_medicine_order_tenant_admission_ordered").doesNotContain("Sort").doesNotContain("Seq Scan");
            String live = explain(c, "select drug_key from ipd.medicine_order where tenant_id = '" + t + "'::uuid and admission_id = '"
                    + admissionId + "'::uuid and status in ('PENDING', 'ACTIVE')");
            assertThat(live).doesNotContain("Seq Scan on medicine_order");
            String key = DbAccess.text(c, "select client_key::text from ipd.medicine_order where tenant_id = ?::uuid limit 1", t);
            String byKey = explain(c, "select id from ipd.medicine_order where tenant_id = '" + t + "'::uuid and client_key = '" + key + "'::uuid");
            assertThat(byKey).contains("uq_medicine_order_tenant_client_key").doesNotContain("Seq Scan");
            String id = DbAccess.text(c, "select id::text from ipd.medicine_order where tenant_id = ?::uuid limit 1", t);
            String byId = explain(c, "select * from ipd.medicine_order where tenant_id = '" + t + "'::uuid and id = '" + id + "'::uuid");
            assertThat(byId).doesNotContain("Seq Scan");
            assertThat(adm).isNotNull();
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

    // ---------------- 21 : two connections ----------------

    @Test
    void item21aTwoInsertsOfTheSameLiveDrugWithoutTheAdmissionLockMeetTheUniqueIndexAsA23505() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        try (Connection one = tenantConnection(clinic); Connection two = tenantConnection(clinic)) {
            DbAccess.text(one, INS_APP, adm.id(), "pcm", U1.toString());
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try {
                    DbAccess.text(two, INS_APP, adm.id(), "pcm", U2.toString());
                    two.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState() + " " + e.getMessage();
                }
            });
            assertThat(awaitLockWaiters("%insert into ipd.medicine_order%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).startsWith("23505").contains("ux_medicine_order_tenant_admission_drug_live");
        }
        assertThat(orderCount(clinic, adm.id())).isOne();
    }

    @Test
    void item21bTwoVerifiesByTwoUsersTheSecondMatchesZeroRowsAndTheTriggerIsNeverReached() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        String id = orderIdOf(place(clinic.owner(), adm.id(), "pcm"));
        try (Connection one = tenantConnection(clinic); Connection two = tenantConnection(clinic)) {
            DbAccess.execute(one, ACT, U2.toString(), id);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
                try (PreparedStatement ps = two.prepareStatement(ACT)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, id);
                    int n = ps.executeUpdate();
                    two.commit();
                    return n;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(awaitLockWaiters("%update ipd.medicine_order%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).isZero();
        }
        OrderState s = state(clinic, id);
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.version()).isOne();
        assertThat(s.verifiedBy()).isEqualTo(U2.toString());
    }

    @Test
    void item21cADischargeBeforeAnInsertMakesTheInsert23514AndOrdersKeepTheirStatus() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, "None known");
        String id = orderIdOf(place(clinic.owner(), adm.id(), "pcm"));
        try (Connection one = tenantConnection(clinic); Connection two = tenantConnection(clinic)) {
            DbAccess.execute(one, "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid", adm.id());
            one.commit();
            SqlErr e = err(two, INS_APP, adm.id(), "panto", U1.toString());
            refused(e, "only for an admitted patient");
        }
        assertThat(state(clinic, id).status()).isEqualTo("PENDING");
        assertThat(orderCount(clinic, adm.id())).isOne();
    }
}
