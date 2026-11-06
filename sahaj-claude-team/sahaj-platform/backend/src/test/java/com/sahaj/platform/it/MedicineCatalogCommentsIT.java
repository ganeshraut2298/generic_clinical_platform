package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-31 review R7: the comment-only migration {@code V2026_11_28_1200__ipd_drug_comments_rls.sql} was applied by Flyway
 * (as {@code sahaj_migrator}, successfully, after the medicine migration and before the staff PIN migration), and the catalog
 * comments of {@code ipd.drug} and {@code ipd.drug_interaction} now tell the truth: row level security is enabled and
 * forced with a select policy for {@code sahaj_app}, and no longer say that there is none. The policies, grants and the
 * absence of a tenant column are proved by {@code RowLevelSecurityCatalogIT} and {@code MedicineOrderTableIT}; this class
 * proves only that the migration ran and that the text matches the catalog. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
class MedicineCatalogCommentsIT extends BaseIntegrationTest {

    private static final String[] TABLES = {"drug", "drug_interaction"};

    @Test
    void flywayAppliedTheCommentMigrationAsTheMigratorInTheRightOrder() throws Exception {
        List<String> rows = new ArrayList<>();
        db().asMigrator(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "select version || '|' || installed_by || '|' || success || '|' || (checksum is not null) || '|' || script "
                            + "from platform.flyway_schema_history where script like '%ipd_drug_comments_rls%'")) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return null;
        });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).startsWith("2026.11.28.1200|sahaj_migrator|true|true|")
                .endsWith("V2026_11_28_1200__ipd_drug_comments_rls.sql");
        // after the migration it restates and before the staff PIN migration; the history has no failed row
        String order = db().asMigrator(c -> DbAccess.text(c, "select string_agg(version, ',' order by installed_rank) from "
                + "platform.flyway_schema_history where version in ('2026.11.28.1000', '2026.11.28.1100', '2026.11.28.1200')"));
        assertThat(order).isEqualTo("2026.11.28.1000,2026.11.28.1100,2026.11.28.1200");
        String failed = db().asMigrator(c -> DbAccess.text(c, "select count(*)::text from platform.flyway_schema_history where "
                + "success = false"));
        assertThat(failed).isEqualTo("0");
    }

    @Test
    void theCatalogCommentsOfTheTwoReferenceTablesDescribeRowLevelSecurityAndNoLongerSayThereIsNone() throws Exception {
        for (String table : TABLES) {
            String comment = db().asApp(c -> DbAccess.text(c, "select obj_description(('ipd.' || ?)::regclass, 'pg_class')", table));
            assertThat(comment).as("comment of ipd." + table).isNotBlank()
                    .containsIgnoringCase("Row level security is enabled and forced")
                    .contains("p_" + table + "_select")
                    .contains("p_" + table + "_migrator")
                    .contains("select only for sahaj_app")
                    .contains("no tenant_id column")
                    .doesNotContainIgnoringCase("no row level security")
                    .doesNotContainIgnoringCase("without row level security")
                    .doesNotContainIgnoringCase("no rls");
        }
    }

    @Test
    void theCommentsMatchTheCatalogTheyDescribe() throws Exception {
        db().asApp(c -> {
            for (String table : TABLES) {
                // enabled and forced, exactly the two policies the comment names
                assertThat(DbAccess.text(c, "select relrowsecurity::text || ':' || relforcerowsecurity::text from pg_class cl join "
                        + "pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'ipd' and cl.relname = ?", table))
                        .isEqualTo("true:true");
                assertThat(DbAccess.text(c, "select string_agg(policyname, ',' order by policyname) from pg_policies where "
                        + "schemaname = 'ipd' and tablename = ?", table))
                        .isEqualTo("p_" + table + "_migrator,p_" + table + "_select");
                // "no tenant_id column"
                assertThat(DbAccess.text(c, "select count(*)::text from information_schema.columns where table_schema = 'ipd' "
                        + "and table_name = ? and column_name = 'tenant_id'", table)).isEqualTo("0");
                // "sahaj_app has no insert, update or delete privilege"
                for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                    assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ('ipd.' || ?)::regclass, ?)::text", table,
                            privilege)).as(table + " " + privilege).isEqualTo("false");
                }
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ('ipd.' || ?)::regclass, 'SELECT')::text",
                        table)).isEqualTo("true");
            }
            return null;
        });
    }

    @Test
    void theEarlierMedicineMigrationIsUntouchedSoItsChecksumStillMatchesAndTheContextStarts() throws Exception {
        // a changed old migration would fail validation at start-up; this test running at all proves it. Its stored row is
        // still the one applied in its own version, and the comment migration is a separate row (not a repair).
        String applied = db().asMigrator(c -> DbAccess.text(c, "select count(*)::text from platform.flyway_schema_history where "
                + "script like '%ipd_medicine_orders%' and success"));
        assertThat(applied).isEqualTo("1");
    }
}
