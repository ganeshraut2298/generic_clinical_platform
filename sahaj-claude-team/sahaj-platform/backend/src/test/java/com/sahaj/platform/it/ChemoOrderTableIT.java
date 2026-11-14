package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The database rules of {@code opd.chemo_order} (SAH-66, SAH-70; cases S-89 to S-99 of docs/database/SAH-49-71.md), by SQL on the
 * real {@code sahaj_test} database as {@code sahaj_app} with a bound clinic (and as the table owner where the owner is the point).
 * Each rule of the table is one test: the composite keys (record, patient, course of another clinic), the checker and
 * confirmation checks, "a confirmation always equals the current fingerprint", "SENT without a current confirmation is refused",
 * the hold checks, the trigger that makes a SENT or HELD row immutable for every role, one order per record, column grants, tenant
 * isolation and the query plans. Writes that must be accepted prove that a constraint, not a missing fixture, is the reason for a
 * refusal; everything runs in a transaction that is rolled back (the one committed row is for the cross-clinic cases). Throwaway
 * data only. Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class ChemoOrderTableIT extends EmrPhase3Support {

    private static final String FP1 = "a".repeat(64);
    private static final String FP2 = "b".repeat(64);
    private static final String JSON1 = "'{\"schemaVersion\":1}'::jsonb";

    /** One clinic with an open record, its patient and made-up membership ids. */
    private record Fx(Rec rec, String createdAt, UUID prescriber, UUID checker, UUID other, UUID course) {

        Clinic clinic() {
            return rec.clinic();
        }

        /** An insert of one DRAFT order; {@code extraCols} and {@code extraVals} start with a comma. */
        String insert(String extraCols, String extraVals) {
            return "insert into opd.chemo_order (record_id, record_created_at, patient_id, protocol_key, level_pct, inputs, snapshot, fingerprint, "
                    + "prescriber_membership_id, last_edited_by_membership_id, created_by" + extraCols + ") values ('" + rec.id() + "', '" + createdAt
                    + "'::timestamptz, '" + rec.patientId() + "', 'PACLI_3W', 100, " + JSON1 + ", " + JSON1 + ", '" + FP1 + "', '" + prescriber + "', '"
                    + prescriber + "', '" + clinic().ownerUserId() + "'" + extraVals + ")";
        }

        String insert() {
            return insert("", "");
        }

        /** Columns and values of a named checker. */
        static String checkerCols() {
            return ", checker_membership_id, checker_role, checker_named_at, checker_named_by";
        }

        String checkerVals(UUID who) {
            return ", '" + who + "', 'NURSE', now(), '" + clinic().ownerUserId() + "'";
        }

        static String confirmCols() {
            return ", confirmed_by_membership_id, confirmed_by_user_id, confirmed_via, confirmed_at, confirmed_fingerprint";
        }

        String confirmVals(UUID who, String fingerprint) {
            return ", '" + who + "', '" + clinic().ownerUserId() + "', 'SIGN_IN', now(), '" + fingerprint + "'";
        }
    }

    private Fx fx() throws Exception {
        Rec rec = newRec();
        String createdAt = row(rec.clinic(), "select created_at::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        UUID course = UUID.fromString(threeSittingCourse(rec).get("id").asText());
        return new Fx(rec, createdAt, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), course);
    }

    /** The SQLSTATE of the statement, or ACCEPTED; always rolled back, with the clinic bound. */
    private String stateIn(Fx f, String sql, Object... params) throws SQLException {
        return db().inTenant(f.clinic().tenantId(), c -> probe(c, sql, params));
    }

    private static String probe(Connection c, String sql, Object... params) throws SQLException {
        DbAccess.execute(c, "savepoint probe");
        try {
            DbAccess.execute(c, sql, params);
        } catch (SQLException e) {
            DbAccess.execute(c, "rollback to savepoint probe");
            return e.getSQLState();
        }
        DbAccess.execute(c, "rollback to savepoint probe");
        return "ACCEPTED";
    }

    /** Inserts the DRAFT in the open transaction (kept until the transaction ends) and fails the test if it is refused. */
    private static void keep(Connection c, String insertSql) throws SQLException {
        DbAccess.execute(c, insertSql);
    }

    private static final String SENT = "update opd.chemo_order set status = 'SENT', sent_at = now(), sent_by = created_by";

    // ================= S-89 · composite keys =================

    @Test
    void anOrderMustPointAtARecordPatientAndCourseOfItsOwnClinicWithTheRightRecordTimestamp() throws Exception {
        Fx a = fx();
        Fx b = fx();
        assertThat(stateIn(a, a.insert())).as("the plain DRAFT order is accepted").isEqualTo("ACCEPTED");
        assertThat(stateIn(a, a.insert().replace(a.rec().id(), b.rec().id()))).as("the record of another clinic").isEqualTo("23503");
        assertThat(stateIn(a, a.insert().replace(a.rec().patientId().toString(), b.rec().patientId().toString()))).as("the patient of another clinic").isEqualTo("23503");
        assertThat(stateIn(b, b.insert())).isEqualTo("ACCEPTED");
        assertThat(stateIn(a, a.insert(", course_id", ", '" + b.course() + "'"))).as("the course of another clinic").isEqualTo("23503");
        assertThat(stateIn(a, a.insert(", course_id", ", '" + a.course() + "'"))).as("the clinic's own course").isEqualTo("ACCEPTED");
        assertThat(stateIn(a, a.insert().replace(a.createdAt() + "'::timestamptz", "2020-01-01 00:00:00+00'::timestamptz")))
                .as("a record id with a created_at that does not match").isEqualTo("23503");
        assertThat(stateIn(a, a.insert().replace("'PACLI_3W'", "'NO_SUCH'"))).as("an unknown protocol").isEqualTo("23503");
    }

    // ================= S-90 · tenant isolation =================

    @Test
    void anotherClinicSeesNoOrderAndCannotInsertForTheFirstAndWithNoClinicBoundNothingIsVisible() throws Exception {
        Fx a = fx();
        Fx b = fx();
        commit(a.clinic().tenantId(), a.insert());
        assertThat(count(a.clinic(), "select count(*) from opd.chemo_order")).isEqualTo(1);
        assertThat(count(b.clinic(), "select count(*) from opd.chemo_order")).as("clinic B sees none of clinic A's orders").isZero();
        assertThat(globalCount("select count(*) from opd.chemo_order")).as("no clinic bound").isZero();
        assertThat(stateIn(b, "insert into opd.chemo_order (tenant_id, record_id, record_created_at, patient_id, protocol_key, level_pct, inputs, snapshot, fingerprint, "
                + "prescriber_membership_id, last_edited_by_membership_id, created_by) values ('" + a.clinic().tenantId() + "', '" + a.rec().id() + "', '" + a.createdAt()
                + "'::timestamptz, '" + a.rec().patientId() + "', 'AC', 100, " + JSON1 + ", " + JSON1 + ", '" + FP1 + "', '" + a.prescriber() + "', '" + a.prescriber() + "', '"
                + b.clinic().ownerUserId() + "')")).as("a row of clinic A written under clinic B").isIn("42501", "23503");
        long changed = db().inTenant(b.clinic().tenantId(), c -> {
            DbAccess.execute(c, "update opd.chemo_order set revision = 5");
            return DbAccess.count(c, "select count(*) from opd.chemo_order where revision = 5");
        });
        assertThat(changed).as("clinic B cannot change clinic A's order").isZero();
        assertThat(row(a.clinic(), "select revision::text from opd.chemo_order").get(0)).as("clinic A's row is untouched").isEqualTo("0");
    }

    // ================= S-91, S-92 · checker and confirmation =================

    @Test
    void theCheckerIsNeverThePrescriberOrTheLastEditorAndTheFourCheckerColumnsComeTogether() throws Exception {
        Fx f = fx();
        assertThat(stateIn(f, f.insert(Fx.checkerCols(), f.checkerVals(f.prescriber())))).as("checker = prescriber").isEqualTo("23514");
        String lastEditor = f.insert(Fx.checkerCols(), f.checkerVals(f.other())).replace("'" + f.prescriber() + "', '" + f.prescriber() + "', '", "'" + f.prescriber() + "', '" + f.other() + "', '");
        assertThat(stateIn(f, lastEditor)).as("checker = last editor").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(", checker_membership_id", ", '" + f.checker() + "'"))).as("only the membership set").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(", checker_role", ", 'NURSE'"))).as("only the role set").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols(), f.checkerVals(f.checker()).replace("'NURSE'", "'COUNSELLOR'")))).as("a role that cannot check").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols(), f.checkerVals(f.checker())))).isEqualTo("ACCEPTED");
        for (String role : new String[] {"NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR", "OWNER"}) {
            assertThat(stateIn(f, f.insert(Fx.checkerCols(), f.checkerVals(f.checker()).replace("'NURSE'", "'" + role + "'")))).as(role).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void aConfirmationIsMadeByTheNamedCheckerWithAllFiveColumnsAndNeverWithNoCheckerNamed() throws Exception {
        Fx f = fx();
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1)))).isEqualTo("ACCEPTED");
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.other(), FP1))))
                .as("confirmed by someone other than the named checker").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.confirmCols(), f.confirmVals(f.checker(), FP1)))).as("a confirmation with no checker named").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + ", confirmed_by_membership_id", f.checkerVals(f.checker()) + ", '" + f.checker() + "'")))
                .as("confirmation columns half set").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1).replace("'SIGN_IN'", "'SMS'"))))
                .as("a way that does not exist").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1).replace("'SIGN_IN'", "'PIN'"))))
                .as("the colleague PIN way").isEqualTo("ACCEPTED");
    }

    // ================= S-93 · a confirmation is never stale =================

    @Test
    void aConfirmedFingerprintMustEqualTheFingerprintSoAChangeThatAltersItMustClearTheConfirmationInTheSameUpdate() throws Exception {
        Fx f = fx();
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP2))))
                .as("a confirmed fingerprint different from the fingerprint").isEqualTo("23514");
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1)));
            assertThat(probe(c, "update opd.chemo_order set fingerprint = '" + FP2 + "'")).as("the fingerprint alone on a confirmed DRAFT").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set fingerprint = '" + FP2 + "', confirmed_by_membership_id = null, confirmed_by_user_id = null, confirmed_via = null, "
                    + "confirmed_at = null, confirmed_fingerprint = null, confirmation_cleared_at = now()")).as("the same change that clears the confirmation").isEqualTo("ACCEPTED");
            assertThat(probe(c, "update opd.chemo_order set checker_membership_id = '" + f.other() + "'")).as("naming another checker without clearing the confirmation")
                    .isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set checker_membership_id = '" + f.other() + "', confirmed_by_membership_id = null, confirmed_by_user_id = null, "
                    + "confirmed_via = null, confirmed_at = null, confirmed_fingerprint = null, confirmation_cleared_at = now()")).isEqualTo("ACCEPTED");
            DbAccess.execute(c, "update opd.chemo_order set confirmed_by_membership_id = null, confirmed_by_user_id = null, confirmed_via = null, confirmed_at = null, "
                    + "confirmed_fingerprint = null, confirmation_cleared_at = now()");
            assertThat(probe(c, "update opd.chemo_order set confirmed_by_membership_id = '" + f.checker() + "', confirmed_by_user_id = created_by, confirmed_via = 'PIN', confirmed_at = now(), "
                    + "confirmed_fingerprint = fingerprint")).as("a confirmation cannot be recorded while the cleared stamp is still set").isEqualTo("23514");
            return null;
        });
    }

    // ================= S-94 · SENT needs a current confirmation =================

    @Test
    void sentWithoutACurrentConfirmationIsRefusedByTheDatabaseAndSentAtLevelOneHundredWithOneIsAccepted() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert(Fx.checkerCols(), f.checkerVals(f.checker())));
            assertThat(probe(c, SENT)).as("SENT with no confirmation at all").isEqualTo("23514");
            DbAccess.execute(c, "update opd.chemo_order set confirmed_by_membership_id = checker_membership_id, confirmed_by_user_id = created_by, confirmed_via = 'SIGN_IN', "
                    + "confirmed_at = now(), confirmed_fingerprint = fingerprint");
            assertThat(probe(c, "update opd.chemo_order set status = 'SENT'")).as("SENT without sent_at and sent_by").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'SENT', sent_at = now()")).as("SENT without sent_by").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set sent_at = now(), sent_by = created_by")).as("a sent stamp on a DRAFT").isEqualTo("23514");
            assertThat(probe(c, SENT + ", confirmed_fingerprint = '" + FP2 + "'")).as("SENT with a stale confirmed fingerprint").isEqualTo("23514");
            assertThat(probe(c, SENT + ", fingerprint = '" + FP2 + "'")).as("SENT with a changed fingerprint").isEqualTo("23514");
            assertThat(probe(c, SENT + ", level_pct = 90")).as("SENT at level 90 without a reason").isEqualTo("23514");
            assertThat(probe(c, SENT + ", level_pct = 90, reduction_reason_key = 'NEUROPATHY'")).as("SENT at level 90 with a reason key").isEqualTo("ACCEPTED");
            assertThat(probe(c, SENT + ", level_pct = 75, reduction_reason_text = 'Frail'")).as("a typed reason").isEqualTo("ACCEPTED");
            assertThat(probe(c, SENT)).as("DRAFT to SENT at level 100 with a current confirmation").isEqualTo("ACCEPTED");
            return null;
        });
        assertThat(stateIn(f, f.insert(", status, sent_at, sent_by", ", 'SENT', now(), '" + f.clinic().ownerUserId() + "'"))).as("a row inserted as SENT with no confirmation").isEqualTo("23514");
        assertThat(stateIn(f, f.insert(Fx.checkerCols() + Fx.confirmCols() + ", status, sent_at, sent_by", f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1)
                + ", 'SENT', now(), '" + f.clinic().ownerUserId() + "'"))).as("inserted as SENT with a current confirmation").isEqualTo("ACCEPTED");
    }

    // ================= S-95 · hold =================

    @Test
    void aHeldOrderNeedsAKindAndATimeAndEveryOtherStatusHasNoHoldColumns() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert());
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD'")).as("HELD without kind or time").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'DELAY'")).as("HELD without a time").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', held_at = now()")).as("HELD without a kind").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set hold_kind = 'DELAY'")).as("a DRAFT with a hold kind").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set hold_reason = 'Counts low'")).as("a DRAFT with a hold reason").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'PAUSE', held_at = now()")).as("a kind that does not exist").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'STOP', held_at = now(), hold_reason = '" + "r".repeat(301) + "'")).as("reason over 300").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'DELAY', held_at = now(), hold_reason = 'Counts low'")).isEqualTo("ACCEPTED");
            assertThat(probe(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'STOP', held_at = now()")).as("no reason is allowed in the table").isEqualTo("ACCEPTED");
            assertThat(probe(c, "update opd.chemo_order set status = 'DELETED'")).isEqualTo("23514");
            return null;
        });
    }

    // ================= S-96 · SENT and HELD rows are immutable =================

    @Test
    void aSentRowRefusesEveryUpdateDeleteAndTruncateByTheAppWith42501() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1)));
            DbAccess.execute(c, SENT);
            for (String sql : new String[] {"update opd.chemo_order set revision = revision + 1", "update opd.chemo_order set updated_at = now()",
                    "update opd.chemo_order set snapshot = snapshot", "update opd.chemo_order set status = 'DRAFT'", "update opd.chemo_order set level_pct = 100",
                    "delete from opd.chemo_order", "truncate opd.chemo_order"}) {
                assertThat(probe(c, sql)).as(sql).isEqualTo("42501");
            }
            assertThat(DbAccess.count(c, "select count(*) from opd.chemo_order where status = 'SENT'")).isOne();
            return null;
        });
    }

    @Test
    void aHeldRowRefusesEveryUpdateAndDeleteToo() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert());
            DbAccess.execute(c, "update opd.chemo_order set status = 'HELD', hold_kind = 'STOP', hold_reason = 'Not fit', held_at = now()");
            for (String sql : new String[] {"update opd.chemo_order set revision = revision + 1", "update opd.chemo_order set hold_reason = 'Another'",
                    "update opd.chemo_order set status = 'DRAFT', hold_kind = null, hold_reason = null, held_at = null", "delete from opd.chemo_order", "truncate opd.chemo_order"}) {
                assertThat(probe(c, sql)).as(sql).isEqualTo("42501");
            }
            return null;
        });
    }

    @Test
    void theTableOwnerAndSahajMigratorCannotChangeASentRowOrDeleteOrTruncateEither() throws Exception {
        Fx f = fx();
        db().asMigrator(c -> {
            DbAccess.execute(c, "alter table opd.chemo_order no force row level security");
            String insert = f.insert(Fx.checkerCols() + Fx.confirmCols(), f.checkerVals(f.checker()) + f.confirmVals(f.checker(), FP1))
                    .replace("(record_id,", "(tenant_id, record_id,").replace(") values ('", ") values ('" + f.clinic().tenantId() + "', '");
            keep(c, insert);
            DbAccess.execute(c, SENT + " where tenant_id = '" + f.clinic().tenantId() + "'"); // only this test's row: the table also holds other tests' SENT rows, which are immutable
            for (String sql : new String[] {"update opd.chemo_order set revision = revision + 1", "delete from opd.chemo_order", "truncate opd.chemo_order"}) {
                SqlError err = expectError(c, sql);
                assertThat(err.state()).as(sql).isEqualTo("42501");
                assertThat(err.message()).as(sql).contains("immutable");
            }
            return null;
        });
        assertThat(globalRows("select c.relforcerowsecurity::text from pg_class c where c.oid = 'opd.chemo_order'::regclass").get(0).get(0))
                .as("row level security is forced again after the rollback").isEqualTo("true");
    }

    @Test
    void theImmutabilityTriggersAreThereByNameAndEnabled() throws Exception {
        for (String name : new String[] {"trg_chemo_order_immutable", "trg_chemo_order_no_truncate"}) {
            assertThat(globalRows("select tgenabled::text from pg_trigger where tgrelid = 'opd.chemo_order'::regclass and tgname = '" + name + "'").stream().map(r -> r.get(0)).toList())
                    .as(name).containsExactly("O");
        }
    }

    // ================= S-97 · one order per record =================

    @Test
    void aSecondOrderForTheSameRecordIsRefusedUnlessItSupersedesAnOrderOfTheSameClinicAndNeverItself() throws Exception {
        Fx a = fx();
        Fx b = fx();
        UUID first = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        commit(b.clinic().tenantId(), b.insert(", id", ", '" + foreign + "'"));
        db().inTenant(a.clinic().tenantId(), c -> {
            keep(c, a.insert(", id", ", '" + first + "'"));
            assertThat(probe(c, a.insert())).as("a second order, no supersedes").isEqualTo("23505");
            assertThat(probe(c, a.insert(", supersedes_order_id", ", '" + first + "'"))).as("a correction of an order of the same clinic").isEqualTo("ACCEPTED");
            assertThat(probe(c, a.insert(", supersedes_order_id", ", '" + foreign + "'"))).as("a correction of another clinic's order").isEqualTo("23503");
            UUID self = UUID.randomUUID();
            assertThat(probe(c, a.insert(", id, supersedes_order_id", ", '" + self + "', '" + self + "'"))).as("pointing at itself").isIn("23514", "23503");
            return null;
        });
    }

    // ================= S-98 · column grants =================

    @Test
    void theAppMayNotChangeTheKeysTheRecordThePatientThePrescriberOrCreatedAtOrDeleteButMayRewriteTheSnapshotOfADraft() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            keep(c, f.insert());
            for (String sql : new String[] {"update opd.chemo_order set record_id = gen_random_uuid()", "update opd.chemo_order set patient_id = gen_random_uuid()",
                    "update opd.chemo_order set prescriber_membership_id = gen_random_uuid()", "update opd.chemo_order set created_at = now()",
                    "update opd.chemo_order set created_by = gen_random_uuid()", "update opd.chemo_order set id = gen_random_uuid()",
                    "update opd.chemo_order set tenant_id = gen_random_uuid()", "update opd.chemo_order set supersedes_order_id = id",
                    "update opd.chemo_order set record_created_at = now()", "delete from opd.chemo_order", "truncate opd.chemo_order"}) {
                assertThat(probe(c, sql)).as(sql).isEqualTo("42501");
            }
            assertThat(probe(c, "update opd.chemo_order set snapshot = '{\"schemaVersion\":1,\"x\":1}'::jsonb, revision = revision + 1, updated_at = now(), updated_by = created_by"))
                    .isEqualTo("ACCEPTED");
            assertThat(probe(c, "update opd.chemo_order set snapshot = '[]'::jsonb")).as("a snapshot is an object with a schema version").isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set inputs = '{}'::jsonb")).isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set fingerprint = 'XYZ'")).isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set level_pct = 85")).isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set doxorubicin_mg_m2 = -1")).isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set prior_outside_mg_m2 = 2001")).isEqualTo("23514");
            assertThat(probe(c, "update opd.chemo_order set cycle_no = 0")).isEqualTo("23514");
            return null;
        });
        for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd.chemo_order', '" + privilege + "')").get(0).get(0)).as(privilege).isEqualTo("f");
        }
        assertThat(globalCount("select count(*) from information_schema.role_table_grants where table_schema = 'opd' and table_name = 'chemo_order' and grantee = 'PUBLIC'")).isZero();
    }

    // ================= S-99 · query plans =================

    @Test
    void theCheckerListThePatientSentReadAndTheByRecordReadUseTheirIndexes() throws Exception {
        Fx f = fx();
        db().inTenant(f.clinic().tenantId(), c -> {
            DbAccess.execute(c, "set local enable_seqscan = off");
            assertThat(plan(c, "select id from opd.chemo_order where checker_membership_id = '" + f.checker() + "' and status = 'DRAFT' and confirmed_at is null order by checker_named_at limit 20"))
                    .contains("ix_chemo_order_tenant_checker_waiting");
            assertThat(plan(c, "select id from opd.chemo_order where patient_id = '" + f.rec().patientId() + "' and status = 'SENT' order by sent_at"))
                    .contains("ix_chemo_order_tenant_patient_sent");
            assertThat(plan(c, "select id from opd.chemo_order where record_id = '" + f.rec().id() + "' and supersedes_order_id is null"))
                    .contains("uq_chemo_order_tenant_record");
            return null;
        });
    }

    private static String plan(Connection c, String sql) throws SQLException {
        return String.join("\n", allRows(c, "explain " + sql).stream().map(r -> r.get(0)).toList());
    }

    // ================= clinic columns and policy =================

    @Test
    void theTableHasTheClinicColumnForcedRowLevelSecurityAndOnePolicyForTheApp() throws Exception {
        assertThat(globalRows("select c.relrowsecurity::text, c.relforcerowsecurity::text, (select count(*) from pg_policy p where p.polrelid = c.oid)::text "
                + "from pg_class c where c.oid = 'opd.chemo_order'::regclass").get(0)).containsExactly("true", "true", "1");
        assertThat(globalRows("select string_agg(r.rolname, ',') from pg_policy p join pg_roles r on r.oid = any (p.polroles) where p.polrelid = 'opd.chemo_order'::regclass").get(0).get(0))
                .isEqualTo("sahaj_app");
        assertThat(globalCount("select count(*) from information_schema.columns where table_schema = 'opd' and table_name = 'chemo_order' and column_name = 'tenant_id' and is_nullable = 'NO'")).isOne();
        assertThat(globalCount("select count(*) from pg_constraint where conrelid = 'opd.chemo_order'::regclass and contype = 'f' and confrelid <> 'platform.tenant'::regclass "
                + "and confrelid <> 'opd.emr_chemo_protocol'::regclass and array_length(conkey, 1) < 2")).as("every reference inside the module carries the clinic column").isZero();
        List<List<String>> names = globalRows("select conname from pg_constraint where conrelid = 'opd.chemo_order'::regclass and contype = 'c' order by conname");
        assertThat(names.stream().map(r -> r.get(0)).toList()).contains("ck_chemo_order_checker_pair", "ck_chemo_order_checker_independent", "ck_chemo_order_confirmation_pair",
                "ck_chemo_order_confirmer_is_checker", "ck_chemo_order_confirmation_current", "ck_chemo_order_cleared_has_no_confirmation", "ck_chemo_order_sent",
                "ck_chemo_order_sent_stamp", "ck_chemo_order_sent_reason", "ck_chemo_order_held");
    }
}
