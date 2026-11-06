package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The seven-item QA list of {@code docs/database/SAH-33.md} ("Fix pass 2") for {@code ipd.ews_senior_call} (ADR-007 R4)
 * by plain SQL on the real {@code sahaj_test} database, as {@code sahaj_app} and as the table owner: append-only, the
 * insert grant, the composite foreign key, row level security, the checks and the unique count, the throttle read path
 * and the catalog. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SeniorCallTableIT extends VitalsSupport {

    private static final String INSERT = "insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no) "
            + "values (?::uuid, ?::uuid, ?, ?)";

    private record World(Clinic clinic, Adm adm, String esc) {
    }

    private World world() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), medium3());
        return new World(clinic, adm, openEscalationId(clinic, adm.id()));
    }

    private long rowsOf(Clinic clinic, String escalationId) throws SQLException {
        return count(clinic, "select count(*) from ipd.ews_senior_call where escalation_id = '" + escalationId + "'::uuid");
    }

    // ---------------- 1 append-only ----------------

    @Test
    void sahajAppCannotUpdateDeleteOrTruncateAndTheTableOwnerIsStoppedByTheTriggers() throws Exception {
        World w = world();
        insertSeniorCall(w.clinic(), w.esc(), w.clinic().ownerUserId(), "CALLED", 1, Instant.now());
        assertThat(rowsOf(w.clinic(), w.esc())).isOne();

        for (String sql : new String[] {"update ipd.ews_senior_call set outcome = 'ALREADY_CALLED'",
                "update ipd.ews_senior_call set called_at = now()", "update ipd.ews_senior_call set call_no = 9",
                "delete from ipd.ews_senior_call", "truncate ipd.ews_senior_call"}) {
            SqlErr e = appErr(w.clinic(), sql);
            assertThat(e.state()).as(sql).isEqualTo("42501");
            assertThat(e.message()).as(sql).contains("permission denied");
        }
        for (String sql : new String[] {"update ipd.ews_senior_call set outcome = 'ALREADY_CALLED', call_no = null",
                "update ipd.ews_senior_call set called_at = now()", "update ipd.ews_senior_call set called_by = gen_random_uuid()",
                "delete from ipd.ews_senior_call", "truncate ipd.ews_senior_call"}) {
            SqlErr e = ownerErr("ipd.ews_senior_call", sql);
            assertThat(e.state()).as(sql).isEqualTo("42501");
            assertThat(e.message()).as(sql).contains("append-only");
        }
        assertThat(rowsOf(w.clinic(), w.esc())).as("the row is unchanged").isOne();
        assertThat(values(w.clinic(), "select outcome || '|' || call_no from ipd.ews_senior_call where escalation_id = ?::uuid",
                w.esc())).containsExactly("CALLED|1");
    }

    // ---------------- 2 insert grant ----------------

    @Test
    void sahajAppInsertsTheFourGrantedColumnsAndTheDatabaseFillsIdTenantAndTime() throws Exception {
        World w = world();
        UUID user = UUID.randomUUID();
        List<String> row = db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, INSERT, w.esc(), user.toString(), "CALLED", 1);
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select (tenant_id = platform.current_tenant())::text, (id is not null)::text, "
                    + "(abs(extract(epoch from (clock_timestamp() - called_at))) < 1)::text, called_by::text, outcome, "
                    + "call_no::text from ipd.ews_senior_call where escalation_id = ?::uuid")) {
                ps.setString(1, w.esc());
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    for (int i = 1; i <= 6; i++) {
                        out.add(rs.getString(i));
                    }
                }
            }
            return out;
        });
        assertThat(row).containsExactly("true", "true", "true", user.toString(), "CALLED", "1");

        // id, tenant_id and called_at are not granted: the database cannot be told a time, a clinic or an id
        String esc = w.esc();
        for (String column : new String[] {"called_at", "tenant_id", "id"}) {
            String value = "called_at".equals(column) ? "now()" : "tenant_id".equals(column)
                    ? "'" + w.clinic().tenantId() + "'::uuid" : "gen_random_uuid()";
            SqlErr e = appErr(w.clinic(), "insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no, " + column
                    + ") values ('" + esc + "'::uuid, gen_random_uuid(), 'CALLED', 2, " + value + ")");
            assertThat(e.state()).as(column).isEqualTo("42501");
        }
        assertThat(rowsOf(w.clinic(), w.esc())).as("rolled back or refused").isZero();
    }

    // ---------------- 3 composite foreign key ----------------

    @Test
    void anEscalationOfAnotherClinicOrAMissingOneIs23503ForTheAppAndForTheOwner() throws Exception {
        World a = world();
        World b = world();
        UUID user = UUID.randomUUID();
        // session of A, escalation of B: the composite key (tenant A, escalation B) does not exist
        assertThat(appErr(a.clinic(), INSERT, b.esc(), user.toString(), "CALLED", 1).state()).isEqualTo("23503");
        // an escalation that does not exist
        assertThat(appErr(a.clinic(), INSERT, UUID.randomUUID().toString(), user.toString(), "CALLED", 1).state()).isEqualTo("23503");
        // the owner with an explicit tenant that does not match the escalation
        SqlErr owner = ownerErr("ipd.ews_senior_call", "insert into ipd.ews_senior_call (tenant_id, escalation_id, called_by, "
                + "outcome, call_no) values (?::uuid, ?::uuid, gen_random_uuid(), 'CALLED', 1)", a.clinic().tenantId().toString(), b.esc());
        assertThat(owner.state()).isEqualTo("23503");
        assertThat(owner.message()).contains("fk_ews_senior_call_escalation");
        assertThat(rowsOf(a.clinic(), a.esc()) + rowsOf(b.clinic(), b.esc())).isZero();
        // the same owner insert with the right tenant is accepted: the key is the tenant and the escalation together
        insertSeniorCall(b.clinic(), b.esc(), user, "CALLED", 1, Instant.now());
        assertThat(rowsOf(b.clinic(), b.esc())).isOne();
    }

    // ---------------- 4 row level security ----------------

    @Test
    void oneClinicNeverSeesAnotherClinicsRowsAndWithNoClinicBoundNothingIsVisibleOrWritable() throws Exception {
        World a = world();
        World b = world();
        insertSeniorCall(a.clinic(), a.esc(), UUID.randomUUID(), "CALLED", 1, Instant.now());
        insertSeniorCall(b.clinic(), b.esc(), UUID.randomUUID(), "ALREADY_CALLED", null, Instant.now());
        assertThat(count(a.clinic(), "select count(*) from ipd.ews_senior_call")).isOne();
        assertThat(count(b.clinic(), "select count(*) from ipd.ews_senior_call")).isOne();
        assertThat(count(a.clinic(), "select count(*) from ipd.ews_senior_call where escalation_id = '" + b.esc() + "'::uuid")).isZero();
        assertThat(db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from ipd.ews_senior_call"))).as("no clinic bound").isZero();
        // an insert for A's escalation with B's tenant in the session is refused by the foreign key or the policy
        SqlErr cross = appErr(b.clinic(), INSERT, a.esc(), UUID.randomUUID().toString(), "CALLED", 1);
        assertThat(cross.state()).isIn("23503", "42501");
        // with no clinic bound the policy and the tenant default refuse the insert
        SqlErr unbound = appErrNoTenant(INSERT, a.esc(), UUID.randomUUID().toString(), "CALLED", 1);
        assertThat(unbound.state()).isNotNull();
    }

    // ---------------- 5 checks and the unique count ----------------

    @Test
    void theChecksAndTheUniqueCallNumberAreEnforced() throws Exception {
        World w = world();
        UUID user = UUID.randomUUID();
        String u = user.toString();
        // outcome
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "OTHER", 1).state()).isEqualTo("23514");
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "called", 1).state()).as("case matters").isEqualTo("23514");
        // CALLED needs a number, ALREADY_CALLED must not have one
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "CALLED", null).state()).isEqualTo("23514");
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "ALREADY_CALLED", 1).state()).isEqualTo("23514");
        // the number is 1 to 32,767
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "CALLED", 0).state()).isEqualTo("23514");
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "CALLED", -1).state()).isEqualTo("23514");
        // 32768 does not fit a smallint: the driver or the database refuses it (22003 numeric out of range, or the check)
        assertThat(appErr(w.clinic(), INSERT, w.esc(), u, "CALLED", 32_768).state()).isIn("22003", "23514");
        // accepted: 1, 32,767, and ALREADY_CALLED with no number
        appOk(w.clinic(), INSERT, w.esc(), u, "CALLED", 1);
        appOk(w.clinic(), INSERT, w.esc(), u, "CALLED", 32_767);
        appOk(w.clinic(), INSERT, w.esc(), u, "ALREADY_CALLED", null);

        // the same number twice on one escalation is the unique index (committed first row, then the duplicate)
        insertSeniorCall(w.clinic(), w.esc(), user, "CALLED", 5, Instant.now());
        SqlErr duplicate = appErr(w.clinic(), INSERT, w.esc(), UUID.randomUUID().toString(), "CALLED", 5);
        assertThat(duplicate.state()).isEqualTo("23505");
        assertThat(duplicate.message()).contains("ux_ews_senior_call_tenant_escalation_call_no");
        // another number, the same number on another escalation, and any number of ALREADY_CALLED rows are fine
        appOk(w.clinic(), INSERT, w.esc(), UUID.randomUUID().toString(), "CALLED", 6);
        World other = world();
        insertSeniorCall(other.clinic(), other.esc(), user, "CALLED", 5, Instant.now());
        insertSeniorCall(w.clinic(), w.esc(), user, "ALREADY_CALLED", null, Instant.now());
        insertSeniorCall(w.clinic(), w.esc(), user, "ALREADY_CALLED", null, Instant.now());
        assertThat(rowsOf(w.clinic(), w.esc())).isEqualTo(3);
    }

    // ---------------- 6 the read path of the throttle ----------------

    @Test
    void theThrottleAndReplayReadsUseTheIndexOnALargeTable() throws Exception {
        World w = world();
        // 20,000 rows over 2,000 escalation ids is not possible (the key is composite and checked), so the rows are spread
        // over this clinic's one escalation with many users: the planner must still choose the index for one user
        db().asMigrator(c -> {
            c.setAutoCommit(false);
            DbAccess.execute(c, "alter table ipd.ews_senior_call no force row level security");
            try (var ps = c.prepareStatement("insert into ipd.ews_senior_call (tenant_id, escalation_id, called_by, outcome, "
                    + "call_no, called_at) select ?::uuid, ?::uuid, gen_random_uuid(), 'CALLED', g, now() - (g || ' seconds')::interval "
                    + "from generate_series(1, 20000) g")) {
                ps.setString(1, w.clinic().tenantId().toString());
                ps.setString(2, w.esc());
                assertThat(ps.executeUpdate()).isEqualTo(20_000);
            }
            DbAccess.execute(c, "alter table ipd.ews_senior_call force row level security");
            c.commit();
            return null;
        });
        db().asMigrator(c -> {
            DbAccess.execute(c, "analyze ipd.ews_senior_call");
            return null;
        });
        UUID user = UUID.fromString(values(w.clinic(), "select called_by::text from ipd.ews_senior_call limit 1").get(0));
        String throttle = "select 1 from ipd.ews_senior_call where tenant_id = '" + w.clinic().tenantId() + "'::uuid and escalation_id = '"
                + w.esc() + "'::uuid and called_by = '" + user + "'::uuid and called_at > clock_timestamp() - interval '60 seconds' limit 1";
        String replay = "select 1 from ipd.ews_senior_call where tenant_id = '" + w.clinic().tenantId() + "'::uuid and escalation_id = '"
                + w.esc() + "'::uuid and called_by = '" + user + "'::uuid and outcome = 'CALLED' and call_no > 5 limit 1";
        for (String query : new String[] {throttle, replay}) {
            String plan = db().asMigrator(c -> {
                StringBuilder sb = new StringBuilder();
                try (var ps = c.prepareStatement("explain " + query); var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                    }
                }
                return sb.toString();
            });
            // never a sequential scan. The throttle read uses the (tenant, escalation, user, time) index. The replay read may
            // use either index of the table: with LIMIT 1 on 20,000 synthetic rows of one escalation the planner can prefer
            // the call_no index; a real escalation has a handful of rows (docs/database/SAH-33.md), so this is not a defect
            assertThat(plan).as(query).doesNotContain("Seq Scan").containsAnyOf("ix_ews_senior_call_tenant_escalation_user",
                    "ux_ews_senior_call_tenant_escalation_call_no");
            if (query == throttle) {
                assertThat(plan).as("the throttle read").contains("ix_ews_senior_call_tenant_escalation_user");
            }
        }
    }

    // ---------------- 7 the catalog ----------------

    @Test
    void theCatalogHasRlsEnabledAndForcedTheTwoPoliciesTheGrantsAndTheTriggers() throws Exception {
        List<String> facts = db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            out.add(DbAccess.text(c, "select relrowsecurity::text || '/' || relforcerowsecurity::text from pg_class where oid = "
                    + "'ipd.ews_senior_call'::regclass"));
            out.add(DbAccess.text(c, "select string_agg(policyname || ':' || cmd || ':' || roles::text, ',' order by policyname) "
                    + "from pg_policies where schemaname = 'ipd' and tablename = 'ews_senior_call'"));
            out.add(DbAccess.text(c, "select string_agg(tgname || '=' || tgenabled::text, ',' order by tgname) from pg_trigger "
                    + "where tgrelid = 'ipd.ews_senior_call'::regclass and not tgisinternal"));
            out.add(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'ipd.ews_senior_call', 'SELECT')::text || "
                    + "has_table_privilege('sahaj_app', 'ipd.ews_senior_call', 'INSERT')::text || "
                    + "has_table_privilege('sahaj_app', 'ipd.ews_senior_call', 'UPDATE')::text || "
                    + "has_table_privilege('sahaj_app', 'ipd.ews_senior_call', 'DELETE')::text || "
                    + "has_table_privilege('sahaj_app', 'ipd.ews_senior_call', 'TRUNCATE')::text"));
            out.add(DbAccess.text(c, "select string_agg(a.attname, ',' order by a.attnum) from pg_attribute a where a.attrelid = "
                    + "'ipd.ews_senior_call'::regclass and a.attnum > 0 and not a.attisdropped "
                    + "and has_column_privilege('sahaj_app', a.attrelid, a.attnum, 'INSERT')"));
            out.add(DbAccess.text(c, "select string_agg(a.attname, ',' order by a.attnum) from pg_attribute a where a.attrelid = "
                    + "'ipd.ews_senior_call'::regclass and a.attnum > 0 and not a.attisdropped "
                    + "and has_column_privilege('sahaj_app', a.attrelid, a.attnum, 'UPDATE')"));
            out.add(DbAccess.text(c, "select count(*)::text from pg_constraint where conrelid = 'ipd.ews_escalation'::regclass "
                    + "and conname = 'uq_ews_escalation_tenant_id' and contype = 'u'"));
            out.add(DbAccess.text(c, "select count(*)::text from pg_indexes where schemaname = 'ipd' and tablename = 'ews_senior_call' "
                    + "and indexname in ('ix_ews_senior_call_tenant_escalation_user', 'ux_ews_senior_call_tenant_escalation_call_no')"));
            out.add(DbAccess.text(c, "select count(*)::text from pg_constraint where conrelid = 'ipd.ews_senior_call'::regclass "
                    + "and conname in ('fk_ews_senior_call_escalation', 'ck_ews_senior_call_outcome', 'ck_ews_senior_call_call_no', "
                    + "'ck_ews_senior_call_call_no_outcome')"));
            return out;
        });
        assertThat(facts.get(0)).as("row level security enabled and forced").isEqualTo("true/true");
        assertThat(facts.get(1)).as("policies").startsWith("p_ews_senior_call_insert:INSERT:").contains(",p_ews_senior_call_select:SELECT:")
                .doesNotContain("ALL").doesNotContain("UPDATE").doesNotContain("DELETE");
        assertThat(facts.get(2)).as("both append-only triggers enabled").isEqualTo(
                "trg_ews_senior_call_no_truncate=O,trg_ews_senior_call_no_update_delete=O");
        // select on the table; insert only per column (so no table-level insert), no update, delete or truncate at all
        assertThat(facts.get(3)).as("select; no table-level insert, update, delete or truncate").isEqualTo("truefalsefalsefalsefalse");
        assertThat(facts.get(4)).as("insert on exactly four columns").isEqualTo("escalation_id,called_by,outcome,call_no");
        assertThat(facts.get(5)).as("no column may be updated").isNullOrEmpty();
        assertThat(facts.get(6)).as("the key the composite foreign key points at").isEqualTo("1");
        assertThat(facts.get(7)).as("both indexes").isEqualTo("2");
        assertThat(facts.get(8)).as("foreign key and three checks").isEqualTo("4");
    }
}
