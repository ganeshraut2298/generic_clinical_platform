package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reads the PostgreSQL catalog (as sahaj_app, on sahaj_test after Flyway) to prove, table by table,
 * that row level security is enabled and forced with a policy on every tenant table, and that the
 * grants of {@code sahaj_app} are exactly what the database design says. Also covers the first real
 * run of the SAH-1 and SAH-2 migrations: if Flyway failed, the context behind these tests does not start.
 */
@OnTestDatabase
class RowLevelSecurityCatalogIT extends BaseIntegrationTest {

    private static final String SCHEMAS = "('platform','patient','opd','ipd','hra','billing')";

    /**
     * Tables that are on purpose without row level security: no tenant_id, global/reference data or
     * person-keyed data recorded before any tenant exists. Documented in docs/database/SAH-1.md
     * (app_user, sales_enquiry, flyway_schema_history), SAH-3.md (otp_challenge, consent_record),
     * SAH-4.md (tenant_slug, the RLS-free mirror of tenant.slug for cross-tenant lookups), SAH-6.md
     * (role_permission_default, a global reference grid) and SAH-7.md (specialty, specialty_template,
     * opd.medicine, opd.diagnosis, all global reference/catalog data, SELECT only for sahaj_app) and
     * SAH-43-44.md and SAH-53.md (ADR-019 D2, ADR-020 D2: the global EMR tables (twelve before SAH-49-71, eighteen now), no tenant_id, changed by migration
     * only, SELECT only for sahaj_app, like opd.medicine: the three masters opd.emr_complaint, emr_investigation and
     * emr_advice, and the section library, the starter departments, the starter layouts and the department aliases
     * opd.emr_section, emr_section_field, emr_section_field_option, emr_starter_department, emr_starter_department_section,
     * emr_starter_layout and emr_department_alias; and from SAH-48-59.md (ADR-022 D5, D11) the allergy classes
     * opd.emr_allergy_class and the starter forms opd.emr_form_starter; the starter layout and starter form tables are also
     * immutable by trigger); and from SAH-49-71.md (ADR-023 D4) the six specialty reference tables opd.emr_immunization_group,
     * emr_immunization_vaccine, emr_growth_band, emr_milestone, emr_joint_item and emr_chemo_protocol (the chemotherapy protocols are
     * immutable by trigger), 18 global EMR tables in all. The tables of the
     * withdrawn SAH-44 (emr_template, emr_template_section, emr_template_option, emr_specialty_alias) no longer exist.
     * The partitions of opd.emr_record are NOT on this list: each one has row level security and a policy.
     */
    private static final Set<String> NO_RLS_BY_DESIGN = Set.of(
            "platform.app_user", "platform.sales_enquiry", "platform.flyway_schema_history",
            "platform.otp_challenge", "platform.consent_record", "platform.tenant_slug",
            "platform.role_permission_default", "platform.specialty", "platform.specialty_template",
            "opd.medicine", "opd.diagnosis",
            "opd.emr_complaint", "opd.emr_investigation", "opd.emr_advice",
            "opd.emr_section", "opd.emr_section_field", "opd.emr_section_field_option", "opd.emr_starter_department",
            "opd.emr_starter_department_section", "opd.emr_starter_layout", "opd.emr_department_alias",
            "opd.emr_allergy_class", "opd.emr_form_starter",
            "opd.emr_immunization_group", "opd.emr_immunization_vaccine", "opd.emr_growth_band", "opd.emr_milestone",
            "opd.emr_joint_item", "opd.emr_chemo_protocol");

    // ---- row level security on every tenant table ----

    @Test
    void everyTableWithTenantIdAndTheTenantTableHaveEnabledAndForcedRowLevelSecurityAndAPolicy() throws Exception {
        List<String> report = new ArrayList<>();
        Set<String> found = new HashSet<>();
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select n.nspname || '.' || c.relname as tbl, c.relrowsecurity, c.relforcerowsecurity,
                           (select count(*) from pg_policy p where p.polrelid = c.oid) as policies
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where c.relkind in ('r','p') and n.nspname in %s
                      and (exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id'
                                   and a.attnum > 0 and not a.attisdropped)
                           or (n.nspname = 'platform' and c.relname = 'tenant'))
                    order by 1
                    """.formatted(SCHEMAS))) {
                while (rs.next()) {
                    String table = rs.getString("tbl");
                    found.add(table);
                    if (!rs.getBoolean("relrowsecurity")) {
                        report.add(table + " has no ENABLE ROW LEVEL SECURITY");
                    }
                    if (!rs.getBoolean("relforcerowsecurity")) {
                        report.add(table + " has no FORCE ROW LEVEL SECURITY");
                    }
                    if (rs.getInt("policies") < 1) {
                        report.add(table + " has no policy");
                    }
                }
            }
            return null;
        });
        assertThat(found).as("the check must find the known tenant tables, otherwise it checks nothing")
                .contains("platform.tenant", "platform.membership", "platform.audit_event");
        assertThat(report).isEmpty();
    }

    /**
     * SAH-53 (docs/database/SAH-53.md "Tenant scope and RLS"): the record table is range-partitioned by month and a partition
     * does not inherit row level security, so each monthly partition (at least the 24 of the migration, more as the DEC-049
     * upkeep adds months) and the default one must have it enabled, forced and one policy of its own; none is on the no-RLS
     * list; and the retired SAH-44 tables are not there.
     */
    @Test
    void theEmrRecordTableAndEveryOneOfItsPartitionsAreProtectedAndNoRetiredEmrTableRemains() throws Exception {
        List<String> weak = new ArrayList<>();
        Set<String> names = new HashSet<>();
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select c.relname, c.relrowsecurity, c.relforcerowsecurity,
                           (select count(*) from pg_policy p where p.polrelid = c.oid) as policies
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'opd' and c.relname ~ '^emr_record(_default|_[0-9]{4}_[0-9]{2})?$' and c.relkind in ('r','p')
                    order by 1
                    """)) {
                while (rs.next()) {
                    names.add(rs.getString("relname"));
                    if (!rs.getBoolean("relrowsecurity") || !rs.getBoolean("relforcerowsecurity") || rs.getInt("policies") != 1) {
                        weak.add(rs.getString("relname"));
                    }
                }
            }
            return null;
        });
        // DEC-049 item 3: the afterMigrate upkeep adds a month as the clock moves, so the count is never fixed. The 24 months
        // of the SAH-53 migration (Nov 2026 to Oct 2028) are always there, and nothing else may be named emr_record*.
        List<String> monthly = names.stream().filter(n -> n.matches("emr_record_\\d{4}_(0[1-9]|1[0-2])")).sorted().toList();
        assertThat(monthly).as("at least the 24 monthly partitions of the SAH-53 migration").hasSizeGreaterThanOrEqualTo(24)
                .contains("emr_record_2026_11", "emr_record_2028_10");
        assertThat(names).as("the parent, the monthly partitions and the default partition, no other emr_record table")
                .hasSize(monthly.size() + 2).contains("emr_record", "emr_record_default");
        assertThat(weak).as("record tables without enabled and forced row level security and one policy").isEmpty();
        assertThat(NO_RLS_BY_DESIGN).noneMatch(t -> t.startsWith("opd.emr_record"));
        assertThat(NO_RLS_BY_DESIGN).doesNotContain("opd.emr_template", "opd.emr_template_section", "opd.emr_template_option",
                "opd.emr_specialty_alias");
        assertThat(NO_RLS_BY_DESIGN.stream().filter(t -> t.startsWith("opd.emr_")).count()).as("the eighteen global EMR tables").isEqualTo(18);
    }

    @Test
    void noTableOutsideTheDocumentedExceptionsIsWithoutRowLevelSecurity() throws Exception {
        List<String> unprotected = new ArrayList<>();
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select n.nspname || '.' || c.relname as tbl
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where c.relkind in ('r','p') and n.nspname in %s
                      and not (c.relrowsecurity and c.relforcerowsecurity)
                    order by 1
                    """.formatted(SCHEMAS))) {
                while (rs.next()) {
                    unprotected.add(rs.getString("tbl"));
                }
            }
            return null;
        });
        assertThat(unprotected).as("tables without enabled and forced RLS").isSubsetOf(NO_RLS_BY_DESIGN);
        assertThat(unprotected).as("app_user and sales_enquiry are the documented exceptions")
                .contains("platform.app_user", "platform.sales_enquiry");
    }

    /**
     * Policies that, by design (DEC-007, docs/database/SAH-3.md "The login-lookup default"), are
     * keyed on {@code platform.current_user_id()} instead of {@code platform.current_tenant()}.
     * They let a signed-in-but-not-yet-tenant-bound user find their own memberships and their own
     * refresh tokens across every tenant, so clinic selection and sign-out-everywhere work before
     * (or across) any one tenant is bound. Each is an exact {@code user_id = current_user_id()}
     * match with no "unset means all" branch, so it is not an escape hatch.
     */
    private static final Set<String> USER_ID_SCOPED_BY_DESIGN = Set.of(
            "platform.membership.p_membership_self_read",
            "platform.refresh_token.p_refresh_token_self_read",
            "platform.refresh_token.p_refresh_token_self_revoke");

    /**
     * SAH-31 (docs/database/SAH-31.md "RLS on the two reference tables"): the global medicine catalogue. No tenant_id; row level
     * security is enabled and forced only to satisfy the repository gates; the policies are not tenant policies on purpose:
     * select for sahaj_app using (true) and all for sahaj_migrator using (true) with check (true), so later content migrations
     * still work. {@link #globalCatalogueTablesHaveExactlyTheSelectAndMigratorPoliciesAndNoTenantData} proves exactly that, so
     * the tenant rule below skips them.
     */
    private static final Set<String> GLOBAL_CATALOGUE_TABLES = Set.of("ipd.drug", "ipd.drug_interaction");

    @Test
    void everyPolicyAppliesToSahajAppOnlyAndIsBuiltOnTheBoundTenantWithNoEscape() throws Exception {
        Map<String, List<String>> commandsByTable = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("""
                    select n.nspname || '.' || cl.relname as tbl, p.polname, p.polcmd::text as cmd,
                           lower(coalesce(pg_get_expr(p.polqual, p.polrelid), '')) as qual,
                           lower(coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')) as chk,
                           coalesce((select string_agg(r.rolname, ',' order by r.rolname) from pg_roles r where r.oid = any (p.polroles)), '') as roles,
                           ('0'::oid = any (p.polroles)) as for_public
                    from pg_policy p
                    join pg_class cl on cl.oid = p.polrelid
                    join pg_namespace n on n.oid = cl.relnamespace
                    where n.nspname in %s
                    order by 1, 2
                    """.formatted(SCHEMAS))) {
                while (rs.next()) {
                    String table = rs.getString("tbl");
                    if (GLOBAL_CATALOGUE_TABLES.contains(table)) {
                        continue; // exact policies asserted in globalCatalogueTablesHaveExactlyTheSelectAndMigratorPoliciesAndNoTenantData
                    }
                    String name = table + "." + rs.getString("polname");
                    commandsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(rs.getString("cmd"));
                    if (rs.getBoolean("for_public") || !"sahaj_app".equals(rs.getString("roles"))) {
                        problems.add(name + " is not restricted to sahaj_app (roles: " + rs.getString("roles") + ")");
                    }
                    String qual = rs.getString("qual");
                    String chk = rs.getString("chk");
                    String cmd = rs.getString("cmd");
                    boolean readsRows = !cmd.equals("a"); // a = INSERT has no USING expression
                    boolean writesRows = !cmd.equals("r") && !cmd.equals("d"); // r = SELECT, d = DELETE have no CHECK
                    boolean userIdScopedByDesign = USER_ID_SCOPED_BY_DESIGN.contains(name);
                    if (userIdScopedByDesign) {
                        if (!qual.contains("current_user_id()") && !chk.contains("current_user_id()")) {
                            problems.add(name + " is documented as user-id scoped but uses neither USING nor WITH CHECK on platform.current_user_id(): qual=" + qual + " chk=" + chk);
                        }
                    } else {
                        if (readsRows && !qual.contains("current_tenant()")) {
                            problems.add(name + " USING does not use platform.current_tenant(): " + qual);
                        }
                        if (writesRows && !chk.contains("current_tenant()")) {
                            problems.add(name + " WITH CHECK does not use platform.current_tenant(): " + chk);
                        }
                    }
                    for (String expression : List.of(qual, chk)) {
                        if (expression.contains("coalesce") || expression.contains("true") || expression.contains(" or ")
                                || expression.contains("is null") || expression.contains("current_setting")) {
                            problems.add(name + " has an escape or a direct setting read: " + expression);
                        }
                    }
                }
            }
            return null;
        });
        assertThat(problems).isEmpty();
        assertThat(commandsByTable).containsKeys("platform.tenant", "platform.membership", "platform.audit_event");
        // audit_event: one SELECT policy and one INSERT policy and nothing else (no UPDATE, DELETE or ALL policy).
        assertThat(commandsByTable.get("platform.audit_event")).containsExactlyInAnyOrder("r", "a");
    }

    @Test
    void globalCatalogueTablesHaveExactlyTheSelectAndMigratorPoliciesAndNoTenantData() throws Exception {
        Map<String, Set<String>> expectedPolicies = new LinkedHashMap<>();
        expectedPolicies.put("ipd.drug", Set.of("p_drug_select|r|sahaj_app|true|", "p_drug_migrator|*|sahaj_migrator|true|true"));
        expectedPolicies.put("ipd.drug_interaction", Set.of("p_drug_interaction_select|r|sahaj_app|true|",
                "p_drug_interaction_migrator|*|sahaj_migrator|true|true"));
        assertThat(expectedPolicies.keySet()).containsExactlyInAnyOrderElementsOf(GLOBAL_CATALOGUE_TABLES);
        db().asApp(c -> {
            for (var entry : expectedPolicies.entrySet()) {
                String table = entry.getKey();
                // enabled and forced
                assertThat(DbAccess.text(c, "select (relrowsecurity and relforcerowsecurity)::text from pg_class where oid = ?::regclass", table))
                        .as(table + " enabled and forced").isEqualTo("true");
                // exactly these policies
                Set<String> found = new HashSet<>();
                try (var st = c.prepareStatement("""
                        select p.polname || '|' || p.polcmd::text || '|' ||
                               coalesce((select string_agg(r.rolname, ',' order by r.rolname) from pg_roles r where r.oid = any (p.polroles)), '')
                               || '|' || lower(coalesce(pg_get_expr(p.polqual, p.polrelid), '')) || '|' ||
                               lower(coalesce(pg_get_expr(p.polwithcheck, p.polrelid), ''))
                        from pg_policy p where p.polrelid = ?::regclass
                        """)) {
                    st.setString(1, table);
                    try (var rs = st.executeQuery()) {
                        while (rs.next()) {
                            found.add(rs.getString(1));
                        }
                    }
                }
                assertThat(found).as(table + " policies").isEqualTo(entry.getValue());
                // sahaj_app: select only, at table and column level
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, 'SELECT')::text", table)).isEqualTo("true");
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, ?)::text", table, privilege))
                            .as(table + " " + privilege).isEqualTo("false");
                }
                String schema = table.substring(0, table.indexOf('.'));
                String name = table.substring(table.indexOf('.') + 1);
                assertThat(DbAccess.count(c, """
                        select count(*) from information_schema.columns k where k.table_schema = ? and k.table_name = ?
                          and (has_column_privilege('sahaj_app', ?::regclass, k.column_name, 'INSERT')
                               or has_column_privilege('sahaj_app', ?::regclass, k.column_name, 'UPDATE'))
                        """, schema, name, table, table)).as(table + " column-level write privileges").isZero();
                // no tenant data: no tenant_id column, and nothing granted to PUBLIC
                assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = ? and table_name = ? "
                        + "and column_name = 'tenant_id'", schema, name)).as(table + " has no tenant_id").isZero();
                assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee = 0",
                        table)).as(table + " grants to PUBLIC").isZero();
                // readable without a bound tenant (policy using (true))
                assertThat(DbAccess.count(c, "select count(*) from " + table)).as(table + " rows without a tenant").isPositive();
            }
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug")).isEqualTo(14);
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug_interaction")).isEqualTo(3);
            return null;
        });
    }

    // ---- grants ----

    @Test
    void sahajAppHasExactlyTheDocumentedPrivilegesOnEachPlatformTable() throws Exception {
        // audit_event's INSERT is column-level only (V2026_11_01_1007, docs/database/SAH-1.md
        // "Column-level INSERT on audit_event"): revoke-all then
        // `grant insert (actor_user_id, action, entity_type, entity_id, before_json, after_json)`.
        // has_table_privilege('sahaj_app','platform.audit_event','INSERT') is therefore false even
        // though the app can insert rows; the table-level check below only covers SELECT/UPDATE/etc,
        // and the column-level check further down covers INSERT on audit_event specifically.
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("platform.tenant", Set.of("SELECT", "INSERT", "UPDATE"));
        expected.put("platform.app_user", Set.of("SELECT", "INSERT", "UPDATE"));
        expected.put("platform.membership", Set.of("SELECT", "INSERT", "UPDATE"));
        expected.put("platform.audit_event", Set.of("SELECT"));
        expected.put("platform.sales_enquiry", Set.of("INSERT"));
        List<String> all = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            expected.forEach((table, allowed) -> all.forEach(privilege -> {
                try {
                    boolean has = Boolean.parseBoolean(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?::text, ?::text)::text", table, privilege));
                    if (has != allowed.contains(privilege)) {
                        problems.add(table + " " + privilege + ": sahaj_app has=" + has + " expected=" + allowed.contains(privilege));
                    }
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }));
            return null;
        });

        // audit_event's INSERT: column-level, exactly the six columns JdbcAuditLog writes.
        Set<String> insertableColumns = Set.of("actor_user_id", "action", "entity_type", "entity_id", "before_json", "after_json");
        Set<String> notInsertableColumns = Set.of("id", "tenant_id", "at");
        db().asApp(c -> {
            for (String column : insertableColumns) {
                boolean has = Boolean.parseBoolean(DbAccess.text(c,
                        "select has_column_privilege('sahaj_app', 'platform.audit_event', ?::text, 'INSERT')::text", column));
                if (!has) {
                    problems.add("platform.audit_event." + column + " INSERT: sahaj_app has=false expected=true");
                }
            }
            for (String column : notInsertableColumns) {
                boolean has = Boolean.parseBoolean(DbAccess.text(c,
                        "select has_column_privilege('sahaj_app', 'platform.audit_event', ?::text, 'INSERT')::text", column));
                if (has) {
                    problems.add("platform.audit_event." + column + " INSERT: sahaj_app has=true expected=false");
                }
            }
            return null;
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void salesEnquiryIsInsertOnlyAtColumnLevelToo() throws Exception {
        db().asApp(c -> {
            for (String column : List.of("id", "name", "clinic_city", "mobile", "best_time", "source", "created_at")) {
                assertThat(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'platform.sales_enquiry', ?::text, 'SELECT')::text", column))
                        .as("SELECT on " + column).isEqualTo("false");
                assertThat(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'platform.sales_enquiry', ?::text, 'UPDATE')::text", column))
                        .as("UPDATE on " + column).isEqualTo("false");
            }
            return null;
        });
    }

    @Test
    void noPlatformTableIsOpenToPublic() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c, """
                    select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace,
                           aclexplode(c.relacl) a
                    where n.nspname = 'platform' and c.relkind = 'r' and a.grantee = 0
                    """)).as("grants to PUBLIC on platform tables").isZero();
            return null;
        });
    }

    @Test
    void salesEnquiryAcceptsAPlainInsertButRefusesReturningAndAnyRead() throws Exception {
        String insert = "insert into platform.sales_enquiry (id, name, mobile, source) values (gen_random_uuid(), 'IT probe', '9000000001', 'CONTACT_SALES')";
        // A plain insert works (rolled back, so no row stays).
        db().asApp(c -> {
            DbAccess.execute(c, insert);
            return null;
        });
        // RETURNING reads the row back and needs SELECT: refused (this is why the app generates the id itself).
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, insert + " returning id");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains("permission denied for table sales_enquiry");
        });
        for (String sql : List.of("select count(*) from platform.sales_enquiry",
                "update platform.sales_enquiry set name = 'x'",
                "delete from platform.sales_enquiry")) {
            assertThatThrownBy(() -> db().asApp(c -> {
                DbAccess.execute(c, sql);
                return null;
            })).as(sql).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void salesEnquiryHasNoTenantColumn() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = 'platform' and table_name = 'sales_enquiry' and column_name = 'tenant_id'"))
                    .isZero();
            return null;
        });
    }

    @Test
    void currentTenantFunctionIsExecutableBySahajAppAndReturnsNullWhenNothingIsBound() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', 'platform.current_tenant()', 'EXECUTE')::text")).isEqualTo("true");
            assertThat(DbAccess.text(c, "select (platform.current_tenant() is null)::text")).isEqualTo("true");
            return null;
        });
    }

    @Test
    void anEmptyTenantSettingStillFailsClosedAndDoesNotRaiseACastError() throws Exception {
        // After SET LOCAL, PostgreSQL leaves '' behind on a pooled session; the function must treat it as "no tenant".
        db().asApp(c -> {
            DbAccess.execute(c, "select set_config('app.tenant_id', '', false)");
            assertThat(DbAccess.text(c, "select (platform.current_tenant() is null)::text")).isEqualTo("true");
            assertThat(DbAccess.count(c, "select count(*) from platform.tenant")).isZero();
            return null;
        });
    }

    @Test
    void aMalformedTenantSettingIsAnErrorNotAnOpenDoor() {
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "select set_config('app.tenant_id', 'not-a-uuid', false)");
            return DbAccess.count(c, "select count(*) from platform.tenant");
        })).isInstanceOf(SQLException.class);
    }

    /**
     * SAH-51-68 (docs/database/SAH-51-68.md, ADR-025): the three tenant tables of the finish and sign are NOT on the no-RLS list,
     * each has a tenant_id column, enabled and forced row level security and its policies (the append-only addendum and round
     * note tables have a select and an insert policy and no other; the admission request has one policy for all commands). The
     * global count of the no-RLS list is unchanged (still the eighteen global EMR tables).
     */
    @Test
    void theThreeTablesOfTheFinishAndSignAreTenantTablesWithForcedRowLevelSecurityAndTheirOwnPolicies() throws Exception {
        assertThat(NO_RLS_BY_DESIGN).doesNotContain("opd.emr_record_addendum", "opd.emr_admission_request", "ipd.round_note");
        assertThat(NO_RLS_BY_DESIGN.stream().filter(t -> t.startsWith("opd.emr_")).count()).isEqualTo(18);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("opd.emr_record_addendum", "a,r");
        expected.put("opd.emr_admission_request", "*");
        expected.put("ipd.round_note", "a,r");
        db().asApp(c -> {
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String schema = e.getKey().substring(0, e.getKey().indexOf('.'));
                String table = e.getKey().substring(e.getKey().indexOf('.') + 1);
                String state = DbAccess.text(c, "select c.relrowsecurity::text || '/' || c.relforcerowsecurity::text || '/' || "
                        + "coalesce((select string_agg(p.polcmd::text, ',' order by p.polcmd::text) from pg_policy p where p.polrelid = c.oid), '') "
                        + "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = '" + schema + "' and c.relname = '" + table + "'");
                assertThat(state).as(e.getKey()).isEqualTo("true/true/" + e.getValue());
                assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = '" + schema + "' and table_name = '" + table
                        + "' and column_name = 'tenant_id' and is_nullable = 'NO'")).as(e.getKey() + " tenant_id").isOne();
            }
            return null;
        });
    }
}
