package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SAH-78 (ADR-028 D2, D9, design note section 4): the shape of the SQL of the pharmacy repositories, checked without a
 * database (the statements themselves run against PostgreSQL in the integration tests of the qa-engineer). The
 * database grants no delete on any pharmacy tenant table, every read and update names the tenant besides the row level
 * security policy, and only tables of the schemas pharmacy and platform are touched.
 */
class PharmacySqlGuardrailsTest {

    private static final List<Class<?>> REPOSITORIES = List.of(JdbcUnitRepository.class, JdbcStoreRepository.class,
            JdbcStoreStaffRepository.class, JdbcStaffPreferenceRepository.class, JdbcSetupCountRepository.class,
            JdbcProductRepository.class, JdbcOpeningStockRepository.class, JdbcVendorRepository.class,
            JdbcApprovalRuleRepository.class, JdbcDocumentSeriesRepository.class, JdbcReasonRepository.class,
            JdbcPurchaseOrderRepository.class,
            JdbcGoodsReceiptRepository.class, JdbcDebitNoteRepository.class, JdbcVendorPayableRepository.class,
            JdbcPrintTemplateRepository.class);

    /** Every {@code private static final String} of the repositories that holds SQL, by "Class.NAME". */
    private static Map<String, String> statements() throws IllegalAccessException {
        Map<String, String> found = new java.util.LinkedHashMap<>();
        for (Class<?> type : REPOSITORIES) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String sql = (String) field.get(null);
                    String lower = sql.toLowerCase(Locale.ROOT).trim();
                    if (lower.startsWith("select") || lower.startsWith("insert") || lower.startsWith("update")
                            || lower.startsWith("delete")) {
                        found.put(type.getSimpleName() + "." + field.getName(), lower.replaceAll("\\s+", " "));
                    }
                }
            }
        }
        return found;
    }

    @Test
    void thereAreStatementsToCheck() throws IllegalAccessException {
        assertThat(statements()).hasSizeGreaterThan(25);
    }

    @Test
    void noStatementDeletesOrTruncatesBecauseNoPharmacyTenantTableGrantsIt() throws IllegalAccessException {
        statements().forEach((name, sql) -> assertThat(sql).as(name).doesNotStartWith("delete").doesNotContain(" delete ")
                .doesNotContain("truncate").doesNotContain("drop "));
    }

    @Test
    void everyReadAndUpdateNamesTheTenantAndAnInsertSetsIt() throws IllegalAccessException {
        List<String> missing = new ArrayList<>();
        statements().forEach((name, sql) -> {
            if (name.contains(".GLOBAL_")) {
                return; // the global catalogue has no tenant column; see globalStatementsReadOnlyCatalogueTables
            }
            boolean insert = sql.startsWith("insert");
            boolean named = insert ? sql.contains("tenant_id") : sql.contains("tenant_id = :tenantid")
                    || sql.contains("tenant_id = ss.tenant_id") && sql.contains("ss.tenant_id = :tenantid");
            if (!named) {
                missing.add(name);
            }
        });
        assertThat(missing).as("statements that do not name the tenant").isEmpty();
    }

    @Test
    void onlyPharmacyAndPlatformTablesAreTouched() throws IllegalAccessException {
        List<String> foreign = new ArrayList<>();
        statements().forEach((name, sql) -> {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:from|join|into|update) ([a-z_]+\\.[a-z_]+)")
                    .matcher(sql);
            while (m.find()) {
                if (!m.group(1).startsWith("pharmacy.")) {
                    foreign.add(name + " -> " + m.group(1));
                }
            }
        });
        assertThat(foreign).isEmpty();
    }

    /** ADR-028 D1 (c), code review F-15: every table of a pharmacy statement is written with its schema, never bare. */
    @Test
    void everyTableOfAStatementIsWrittenWithItsSchema() throws IllegalAccessException {
        List<String> bare = new ArrayList<>();
        java.util.regex.Pattern table = java.util.regex.Pattern
                .compile("(?<!for )(?:from|join|into|update) ([a-z_][a-z_0-9]*)(\\.[a-z_]+)?( |\\(|$)");
        statements().forEach((name, sql) -> {
            java.util.regex.Matcher m = table.matcher(sql);
            while (m.find()) {
                boolean function = "(".equals(m.group(3));
                boolean keyword = java.util.Set.of("as", "set", "only").contains(m.group(1));
                if (m.group(2) == null && !function && !keyword) {
                    bare.add(name + " -> " + m.group(1));
                }
            }
        });
        assertThat(bare).as("tables named without a schema").isEmpty();
    }

    @Test
    void everyUpdateOfAChangeableRowBumpsTheVersionAndTheOptimisticOnesCheckIt() throws IllegalAccessException {
        statements().forEach((name, sql) -> {
            if (sql.startsWith("update") && !name.endsWith(".SUPERSEDE_ISSUES")) {
                assertThat(sql).as(name).contains("version = version + 1");
            }
        });
        assertThat(statements().get("JdbcUnitRepository.UPDATE_SETTINGS")).contains("and version = :version");
        assertThat(statements().get("JdbcStoreRepository.UPDATE")).contains("and version = :version");
        assertThat(statements().get("JdbcStoreStaffRepository.UPDATE")).contains("and version = :version");
        for (String optimistic : List.of("JdbcProductRepository.UPDATE_OWN", "JdbcProductRepository.UPDATE_LOCAL",
                "JdbcOpeningStockRepository.MARK_REPLACED", "JdbcOpeningStockRepository.MARK_SUBMITTED",
                "JdbcOpeningStockRepository.MARK_APPROVED", "JdbcOpeningStockRepository.MARK_REJECTED")) {
            assertThat(statements().get(optimistic)).as(optimistic).contains("and version = :version");
        }
    }

    /** The only issue update is false to true on superseded (the table has no version and the trigger allows nothing else). */
    @Test
    void anIssueIsNeverChangedExceptToMarkItSuperseded() throws IllegalAccessException {
        String sql = statements().get("JdbcOpeningStockRepository.SUPERSEDE_ISSUES");
        assertThat(sql).startsWith("update pharmacy.opening_stock_line_issue set superseded = true where");
        statements().forEach((name, text) -> {
            if (text.contains("opening_stock_line_issue") && text.startsWith("update")) {
                assertThat(name).isEqualTo("JdbcOpeningStockRepository.SUPERSEDE_ISSUES");
            }
        });
    }

    /**
     * DEC-077 I7 and L8(b): the rows-written counter is read and raised (only upwards) only on a DRAFT import of the bound
     * tenant, and the old request counter is no longer read or written by any statement.
     */
    @Test
    void theLineRowsWrittenCounterOnlyGoesUpAndOnlyOnADraftImportAndTheOldCounterIsUnused() throws IllegalAccessException {
        String add = statements().get("JdbcOpeningStockRepository.ADD_LINE_ROWS");
        assertThat(add).contains("set line_rows_written = line_rows_written + :rows").contains("and status = 'draft'")
                .contains("tenant_id = :tenantid");
        assertThat(statements().get("JdbcOpeningStockRepository.LINE_ROWS_WRITTEN")).contains("i.line_rows_written")
                .contains("tenant_id = :tenantid");
        statements().forEach((name, sql) -> {
            assertThat(sql).as(name).doesNotContain("line_request_count");
            if (sql.startsWith("update") && sql.contains("line_rows_written")) {
                assertThat(name).isEqualTo("JdbcOpeningStockRepository.ADD_LINE_ROWS");
            }
        });
    }

    /** DEC-077 I10 and L10: the drafts, the product and the named products are read with the lock the design needs. */
    @Test
    void theLockingReadsTakeTheirRowLocks() throws IllegalAccessException {
        assertThat(statements().get("JdbcOpeningStockRepository.DRAFTS_OF")).endsWith("for update of i")
                .contains("i.status = 'draft'");
        assertThat(statements().get("JdbcProductRepository.FIND_FOR_UPDATE")).endsWith("for update of p");
        assertThat(statements().get("JdbcProductRepository.ACTIVE_BY_CODES")).endsWith("for share of p");
        assertThat(statements().get("JdbcDocumentSeriesRepository.FIND_FOR_UPDATE")).endsWith("for update");
        assertThat(statements().get("JdbcDocumentSeriesRepository.ISSUED_SINCE"))
                .contains("coalesce(last_issued_at, updated_at) >= :since");
    }

    /** Statements named GLOBAL_ read the select-only catalogue and nothing else. */
    @Test
    void globalStatementsReadOnlyCatalogueTables() throws IllegalAccessException {
        Map<String, String> global = new java.util.LinkedHashMap<>();
        statements().forEach((name, sql) -> {
            if (name.contains(".GLOBAL_")) {
                global.put(name, sql);
            }
        });
        assertThat(global).isNotEmpty();
        global.forEach((name, sql) -> {
            assertThat(sql).as(name).startsWith("select").doesNotContain("pharmacy.product").doesNotContain("pharmacy.unit");
            assertThat(sql).as(name).contains("pharmacy.catalogue_");
        });
    }

    @Test
    void noStatementBuildsSqlFromAValue() throws IllegalAccessException {
        statements().forEach((name, sql) -> assertThat(sql).as(name).doesNotContain("' +").doesNotContain("+ '")
                .doesNotContain("\" +"));
    }
}
