package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SAH-79 WP-D, the new tables of migrations V2026_12_17_1300 to 1390 (docs/database/SAH-79-inventory.md sections 4 and 5):
 * row level security enabled and forced, one policy per command, the grants (no delete, no truncate), isolation between two
 * clinics, and the three append-only tables (the triggers of the owner role). The inventory note says "ten new tables"; the ten
 * are the ten migrations: the tables are nine (client_request, document_event, batch, stock_balance, stock_ledger,
 * approval_request, approval_request_level, approval_decision, stock_adjustment). Test plan: docs/test-plans/SAH-79.md.
 */
@OnTestDatabase
class PharmacyWpDTablesIT extends Sah79Support {

    /** table -> policy commands (a = insert, r = select, w = update) and the privileges sahaj_app must hold. */
    private static Map<String, String[]> tables() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("client_request", new String[] {"a,r", "SELECT,INSERT"});
        m.put("document_event", new String[] {"a,r", "SELECT,INSERT"});
        m.put("batch", new String[] {"a,r,w", "SELECT,INSERT,UPDATE"});
        m.put("stock_balance", new String[] {"a,r,w", "SELECT,INSERT,UPDATE"});
        m.put("stock_ledger", new String[] {"a,r", "SELECT,INSERT"});
        m.put("approval_request", new String[] {"a,r,w", "SELECT,INSERT,UPDATE"});
        m.put("approval_request_level", new String[] {"a,r", "SELECT,INSERT"});
        m.put("approval_decision", new String[] {"a,r", "SELECT,INSERT"});
        m.put("stock_adjustment", new String[] {"a,r,w", "SELECT,INSERT,UPDATE"});
        return m;
    }

    private static final List<String> PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    @Test
    void theNineNewTablesHaveForcedRowLevelSecurityABoundTenantIdAndTheDocumentedPolicies() throws Exception {
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            for (var e : tables().entrySet()) {
                String table = e.getKey();
                try (var st = c.createStatement(); var rs = st.executeQuery("""
                        select cl.relkind::text, cl.relrowsecurity, cl.relforcerowsecurity,
                               coalesce((select string_agg(p.polcmd::text, ',' order by p.polcmd::text) from pg_policy p where p.polrelid = cl.oid), '') as cmds,
                               (select a.attnotnull from pg_attribute a where a.attrelid = cl.oid and a.attname = 'tenant_id' and not a.attisdropped) as not_null,
                               (select pg_get_expr(d.adbin, d.adrelid) from pg_attrdef d join pg_attribute a on a.attrelid = d.adrelid and a.attnum = d.adnum
                                 where d.adrelid = cl.oid and a.attname = 'tenant_id') as def,
                               (select count(*) from pg_policy p where p.polrelid = cl.oid and 'sahaj_app' <> all (select r.rolname from pg_roles r where r.oid = any (p.polroles))) as other_roles
                          from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
                         where n.nspname = 'pharmacy' and cl.relname = '""" + table + "'")) {
                    if (!rs.next()) {
                        problems.add(table + " does not exist");
                        continue;
                    }
                    if (!rs.getBoolean("relrowsecurity") || !rs.getBoolean("relforcerowsecurity")) {
                        problems.add(table + " is not enabled and forced");
                    }
                    if (!e.getValue()[0].equals(rs.getString("cmds"))) {
                        problems.add(table + " policy commands " + rs.getString("cmds") + " expected " + e.getValue()[0]);
                    }
                    if (!rs.getBoolean("not_null")) {
                        problems.add(table + ".tenant_id is nullable");
                    }
                    String def = rs.getString("def");
                    if (def == null || !def.contains("current_tenant")) {
                        problems.add(table + ".tenant_id default is " + def);
                    }
                    if (rs.getInt("other_roles") != 0) {
                        problems.add(table + " has a policy for a role other than sahaj_app");
                    }
                    if (table.equals("stock_ledger") && !"p".equals(rs.getString("relkind"))) {
                        problems.add("stock_ledger is not a partitioned table");
                    }
                }
            }
            return null;
        });
        assertThat(problems).isEmpty();
        assertThat(tables()).hasSize(9);
    }

    @Test
    void everyPolicyOfTheNewTablesIsForSahajAppAndTiedToTheBoundTenantWithNoEscape() throws Exception {
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select cl.relname, p.polname, p.polcmd::text as cmd,
                           lower(coalesce(pg_get_expr(p.polqual, p.polrelid), '')) as qual,
                           lower(coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')) as chk
                      from pg_policy p join pg_class cl on cl.oid = p.polrelid join pg_namespace n on n.oid = cl.relnamespace
                     where n.nspname = 'pharmacy'
                       and (cl.relname in ('client_request', 'document_event', 'batch', 'stock_balance', 'stock_ledger', 'approval_request',
                            'approval_request_level', 'approval_decision', 'stock_adjustment') or cl.relname ~ '^stock_ledger_y[0-9]{4}m[0-9]{2}$')
                    """)) {
                while (rs.next()) {
                    String cmd = rs.getString("cmd");
                    String name = rs.getString("relname") + "." + rs.getString("polname");
                    String qual = rs.getString("qual");
                    String chk = rs.getString("chk");
                    boolean usesTenant = (cmd.equals("a") ? chk : qual).contains("current_tenant");
                    if (!usesTenant) {
                        problems.add(name + " is not built on the bound tenant");
                    }
                    if ((qual + chk).contains(" or ") || (qual + chk).contains("true")) {
                        problems.add(name + " has an escape: " + qual + " / " + chk);
                    }
                }
            }
            return null;
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void sahajAppHoldsOnlyTheDocumentedPrivilegesOnEachNewTableAndNothingOnAnyLedgerPartition() throws Exception {
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            for (var e : tables().entrySet()) {
                List<String> allowed = List.of(e.getValue()[1].split(","));
                for (String privilege : PRIVILEGES) {
                    boolean has = Boolean.parseBoolean(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?::text, ?::text)::text",
                            "pharmacy." + e.getKey(), privilege));
                    if (has != allowed.contains(privilege)) {
                        problems.add(e.getKey() + " " + privilege + " has=" + has);
                    }
                }
                assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee = 0",
                        "pharmacy." + e.getKey())).as(e.getKey() + " grants to PUBLIC").isZero();
            }
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select cl.relname from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
                     where n.nspname = 'pharmacy' and cl.relname ~ '^stock_ledger_y[0-9]{4}m(0[1-9]|1[0-2])$' and cl.relkind = 'r'
                    """)) {
                int partitions = 0;
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                for (String name : names) {
                    partitions++;
                    for (String privilege : PRIVILEGES) {
                        boolean has = Boolean.parseBoolean(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?::text, ?::text)::text",
                                "pharmacy." + name, privilege));
                        if (has) {
                            problems.add(name + " " + privilege + " is granted to sahaj_app");
                        }
                    }
                }
                assertThat(partitions).as("partitions seen").isGreaterThanOrEqualTo(41);
            }
            return null;
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void noNewTableCanBeDeletedFromOrTruncatedByTheApplicationAndTheErrorIsPermissionDenied() throws Exception {
        Db w = dbWorld();
        commitRows(w);
        for (String table : tables().keySet()) {
            asTenant(w, c -> {
                mustRefuse(c, "42501", "permission denied", "delete from pharmacy." + table);
                mustRefuse(c, "42501", "permission denied", "truncate pharmacy." + table);
            });
        }
    }

    @Test
    void oneClinicsRowsAreInvisibleToAnotherAndTheOtherCannotInsertForItOrChangeIt() throws Exception {
        Db mine = dbWorld();
        Db theirs = dbWorld();
        Rows rows = commitRows(mine);
        for (String table : tables().keySet()) {
            assertThat(count(mine.clinic(), "select count(*) from pharmacy." + table)).as("mine " + table).isPositive();
            assertThat(count(theirs.clinic(), "select count(*) from pharmacy." + table)).as("theirs " + table).isZero();
        }
        asTenant(theirs, c -> {
            // the rows exist, but not for this tenant
            assertThat(number(c, "select count(*) from pharmacy.batch where id = ?::uuid", rows.batch())).isZero();
            // an update touches nothing (the row is not visible)
            assertThat(number(c, "with u as (update pharmacy.batch set purchase_rate = 1 where id = ?::uuid returning 1) select count(*) from u", rows.batch())).isZero();
            assertThat(number(c, "with u as (update pharmacy.stock_balance set qty = 99 where id = ?::uuid returning 1) select count(*) from u", rows.balance())).isZero();
            assertThat(number(c, "with u as (update pharmacy.approval_request set urgent = true where id = ?::uuid returning 1) select count(*) from u", rows.request())).isZero();
            assertThat(number(c, "with u as (update pharmacy.stock_adjustment set status = 'POSTED', posted_at = now() where id = ?::uuid returning 1) select count(*) from u", rows.adjustment())).isZero();
            // an insert naming the other tenant is refused by the policy
            mustRefuse(c, "42501", "row-level security", "insert into pharmacy.batch (tenant_id, unit_id, product_id, batch_no, expiry_date, mrp, source, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, 'X1', '2031-01-01', 10, 'FOUND', ?::uuid)", mine.tenant().toString(), mine.unit(), mine.product(), mine.actor());
            mustRefuse(c, "42501", "row-level security", "insert into pharmacy.document_event (tenant_id, unit_id, doc_type, doc_id, event, store_id, actor_membership_id) "
                    + "values (?::uuid, ?::uuid, 'STOCK_ADJUSTMENT', ?::uuid, 'CREATED', ?::uuid, ?::uuid)", mine.tenant().toString(), mine.unit(), newId(), mine.central(), mine.actor());
            mustRefuse(c, "42501", "row-level security", "insert into pharmacy.client_request (tenant_id, unit_id, membership_id, client_key, operation, subject_id) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'op', ?::uuid)", mine.tenant().toString(), mine.unit(), newId(), newId(), newId());
        });
        // the row of mine is unchanged
        assertThat(count(mine.clinic(), "select count(*) from pharmacy.batch where purchase_rate is null")).isOne();
        assertThat(count(mine.clinic(), "select qty from pharmacy.stock_balance where id = ?::uuid", rows.balance())).isEqualTo(10);
    }

    @Test
    void aTenantOfAnotherClinicCannotReferenceMyRowsThroughTheCompositeForeignKeys() throws Exception {
        Db mine = dbWorld();
        Db theirs = dbWorld();
        Rows rows = commitRows(mine);
        asTenant(theirs, c -> {
            // their tenant, but my batch: the composite key (tenant_id, batch_id, product_id) finds no row
            mustRefuse(c, "23503", "violates foreign key", "insert into pharmacy.stock_balance (unit_id, store_id, product_id, batch_id, state, qty, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'AVAILABLE', 1, ?::uuid)", theirs.unit(), theirs.central(), mine.product(), rows.batch(), theirs.actor());
            mustRefuse(c, "23503", "violates foreign key", "insert into pharmacy.document_event (unit_id, doc_type, doc_id, event, store_id, actor_membership_id) "
                    + "values (?::uuid, 'STOCK_ADJUSTMENT', ?::uuid, 'CREATED', ?::uuid, ?::uuid)", theirs.unit(), newId(), mine.central(), theirs.actor());
            mustRefuse(c, "23503", "violates foreign key", "insert into pharmacy.approval_request_level (unit_id, request_id, level_no, approver_kind, condition_kind, applies, rule_text, created_by) "
                    + "values (?::uuid, ?::uuid, 1, 'OWNER', 'ALWAYS', true, 't', ?::uuid)", theirs.unit(), rows.request(), theirs.actor());
        });
    }

    // ---------------- append only ----------------

    @Test
    void theApplicationCannotUpdateOrDeleteTheThreeAppendOnlyTablesAndTheLevelTable() throws Exception {
        Db w = dbWorld();
        Rows rows = commitRows(w);
        asTenant(w, c -> {
            mustRefuse(c, "42501", "permission denied", "update pharmacy.document_event set note = 'x' where id = ?::uuid", rows.documentEvent());
            mustRefuse(c, "42501", "permission denied", "delete from pharmacy.document_event where id = ?::uuid", rows.documentEvent());
            mustRefuse(c, "42501", "permission denied", "update pharmacy.stock_ledger set qty_in = 99 where id = ?::uuid", rows.ledger());
            mustRefuse(c, "42501", "permission denied", "delete from pharmacy.stock_ledger where id = ?::uuid", rows.ledger());
            mustRefuse(c, "42501", "permission denied", "update pharmacy.approval_decision set decision = 'REJECTED' where id = ?::uuid", rows.decision());
            mustRefuse(c, "42501", "permission denied", "delete from pharmacy.approval_decision where id = ?::uuid", rows.decision());
            mustRefuse(c, "42501", "permission denied", "update pharmacy.client_request set operation = 'x' where id = ?::uuid", rows.clientRequest());
            mustRefuse(c, "42501", "permission denied", "update pharmacy.approval_request_level set rule_text = 'x' where id = ?::uuid", rows.level());
        });
    }

    @Test
    void theTriggersRefuseUpdateDeleteAndTruncateOfTheLedgerEventsAndDecisionsEvenForTheOwnerRole() throws Exception {
        Db w = dbWorld();
        Rows rows = commitRows(w);
        asOwner(c -> {
            for (String table : new String[] {"document_event", "stock_ledger", "approval_decision"}) {
                openToOwner(c, "pharmacy." + table);
            }
            mustRefuse(c, "42501", "document_event is append-only (UPDATE refused)", "update pharmacy.document_event set note = 'x' where id = ?::uuid", rows.documentEvent());
            mustRefuse(c, "42501", "document_event is append-only (DELETE refused)", "delete from pharmacy.document_event where id = ?::uuid", rows.documentEvent());
            mustRefuse(c, "42501", "is append-only (UPDATE refused)", "update pharmacy.stock_ledger set reason_key = 'x.y' where id = ?::uuid", rows.ledger());
            mustRefuse(c, "42501", "is append-only (DELETE refused)", "delete from pharmacy.stock_ledger where id = ?::uuid", rows.ledger());
            mustRefuse(c, "42501", "approval_decision is append-only (UPDATE refused)", "update pharmacy.approval_decision set note = 'x' where id = ?::uuid", rows.decision());
            mustRefuse(c, "42501", "approval_decision is append-only (DELETE refused)", "delete from pharmacy.approval_decision where id = ?::uuid", rows.decision());
        });
        asOwner(c -> {
            mustRefuse(c, "42501", "document_event is append-only (TRUNCATE refused)", "truncate pharmacy.document_event");
            mustRefuse(c, "42501", "stock_ledger is append-only (TRUNCATE refused)", "truncate pharmacy.stock_ledger");
            mustRefuse(c, "42501", "approval_decision is append-only (TRUNCATE refused)", "truncate pharmacy.approval_decision");
        });
    }

    @Test
    void theTableTheOwnerNamesInATruncateIsTheTableInTheMessageSoAPartitionNameShowsForAPartition() throws Exception {
        asOwner(c -> {
            mustRefuse(c, "42501", "stock_ledger_y2026m11 is append-only (TRUNCATE refused)", "truncate pharmacy.stock_ledger_y2026m11");
            mustRefuse(c, "42501", "stock_ledger_y2030m03 is append-only (TRUNCATE refused)", "truncate pharmacy.stock_ledger_y2030m03");
            // a partition of the current month also refuses a delete and an update of a row, by the row trigger cloned from the parent
            long cloned = number(c, "select count(*) from pg_trigger t where t.tgrelid = 'pharmacy.stock_ledger_y2026m11'::regclass "
                    + "and t.tgname = 'trg_stock_ledger_no_update_delete' and not t.tgisinternal");
            assertThat(cloned).isOne();
        });
    }

    @Test
    void aRowCannotBeUpdatedThroughThePartitionNameEitherAndTheLedgerRowStaysAsWritten() throws Exception {
        Db w = dbWorld();
        Rows rows = commitRows(w);
        asOwner(c -> {
            openToOwner(c, "pharmacy.stock_ledger");
            String partition = "pharmacy." + str(c, "select (select relname::text from pg_class where oid = l.tableoid) from pharmacy.stock_ledger l "
                    + "where l.id = ?::uuid", rows.ledger());
            assertThat(partition).startsWith("pharmacy.stock_ledger_y");
            openToOwner(c, partition);
            mustRefuse(c, "42501", "is append-only (UPDATE refused)", "update " + partition + " set qty_in = 5 where id = ?::uuid", rows.ledger());
            mustRefuse(c, "42501", "is append-only (DELETE refused)", "delete from " + partition + " where id = ?::uuid", rows.ledger());
        });
        assertThat(count(w.clinic(), "select qty_in from pharmacy.stock_ledger where id = ?::uuid", rows.ledger())).isEqualTo(10);
    }

    @Test
    void theNewDatabaseFunctionsCannotBeCalledByTheApplicationExceptNextDocumentNo() throws Exception {
        db().asApp(c -> {
            for (String fn : new String[] {"pharmacy.deny_append_only_mutation()", "pharmacy.guard_batch_update()", "pharmacy.guard_stock_balance_update()",
                "pharmacy.guard_approval_request_update()", "pharmacy.guard_approval_decision_insert()", "pharmacy.guard_stock_adjustment_write()",
                "pharmacy.guard_opening_stock_import_update()", "pharmacy.create_ledger_partitions(date, integer)"}) {
                assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', ?::text, 'EXECUTE')::text", fn)).as(fn).isEqualTo("false");
            }
            assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', 'pharmacy.next_document_no(text)', 'EXECUTE')::text")).isEqualTo("true");
            assertThat(DbAccess.count(c, "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace, aclexplode(p.proacl) a "
                    + "where n.nspname = 'pharmacy' and a.grantee = 0")).as("no pharmacy function is executable by PUBLIC").isZero();
            // every new function pins the search path
            assertThat(DbAccess.count(c, "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname = 'pharmacy' and p.proname in ('deny_append_only_mutation', 'guard_batch_update', 'guard_stock_balance_update', "
                    + "'guard_approval_request_update', 'guard_approval_decision_insert', 'guard_stock_adjustment_write', 'guard_opening_stock_import_update', "
                    + "'create_ledger_partitions', 'next_document_no') and (p.proconfig is null or not exists (select 1 from unnest(p.proconfig) x where x like 'search_path=%'))"))
                    .as("functions without a pinned search_path").isZero();
            return null;
        });
        SQLException ignored = null;
        thatRefusal(ignored).isNull();
    }
}
