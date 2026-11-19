package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-79 WP-D migration 1340: the 41 monthly partitions of {@code pharmacy.stock_ledger} (Nov 2026 to Mar 2030), the owner-only
 * {@code create_ledger_partitions()}, the IST month bounds, no default partition, and the upkeep check (twelve or more months
 * must remain, docs/database/SAH-79-inventory.md section 3.3). Test plan: docs/test-plans/SAH-79.md.
 */
@OnTestDatabase
class PharmacyLedgerPartitionsIT extends Sah79Support {

    private static final String PARTITION_NAMES = "select cl.relname from pg_inherits i join pg_class cl on cl.oid = i.inhrelid "
            + "where i.inhparent = 'pharmacy.stock_ledger'::regclass order by cl.relname";

    private static List<String> expectedNames() {
        List<String> names = new ArrayList<>();
        YearMonth m = YearMonth.of(2026, 11);
        while (!m.isAfter(YearMonth.of(2030, 3))) {
            names.add(String.format("stock_ledger_y%04dm%02d", m.getYear(), m.getMonthValue()));
            m = m.plusMonths(1);
        }
        return names;
    }

    @Test
    void thereAreFortyOnePartitionsNamedFromNovember2026ToMarch2030AndNoDefaultPartition() throws Exception {
        db().asApp(c -> {
            List<String> names = new ArrayList<>();
            try (var st = c.createStatement(); var rs = st.executeQuery(PARTITION_NAMES)) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            assertThat(names).hasSize(41).containsExactlyElementsOf(expectedNames());
            assertThat(names.get(0)).isEqualTo("stock_ledger_y2026m11");
            assertThat(names.get(40)).isEqualTo("stock_ledger_y2030m03");
            assertThat(names).noneMatch(n -> n.contains("default"));
            assertThat(DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'pharmacy' "
                    + "and cl.relname like 'stock_ledger%default%'")).isZero();
            assertThat(DbAccess.text(c, "select pg_get_partkeydef('pharmacy.stock_ledger'::regclass)")).isEqualTo("RANGE (occurred_at)");
            return null;
        });
    }

    @Test
    void everyPartitionHasForcedRowLevelSecurityTwoPoliciesNoGrantAndTheTruncateTriggerAndTheClonedRowTrigger() throws Exception {
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            for (String name : expectedNames()) {
                String rel = "pharmacy." + name;
                if (!DbAccess.text(c, "select (relrowsecurity and relforcerowsecurity)::text from pg_class where oid = ?::regclass", rel).equals("true")) {
                    problems.add(name + " RLS not enabled and forced");
                }
                long policies = DbAccess.count(c, "select count(*) from pg_policy where polrelid = ?::regclass", rel);
                if (policies != 2) {
                    problems.add(name + " has " + policies + " policies");
                }
                String cmds = DbAccess.text(c, "select string_agg(polcmd::text, ',' order by polcmd::text) from pg_policy where polrelid = ?::regclass", rel);
                if (!"a,r".equals(cmds)) {
                    problems.add(name + " policy commands " + cmds);
                }
                if (DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee <> cl.relowner", rel) != 0) {
                    problems.add(name + " has a grant");
                }
                if (DbAccess.count(c, "select count(*) from pg_trigger t where t.tgrelid = ?::regclass and t.tgname = 'trg_stock_ledger_no_truncate' and not t.tgisinternal", rel) != 1) {
                    problems.add(name + " has no truncate trigger");
                }
                if (DbAccess.count(c, "select count(*) from pg_trigger t where t.tgrelid = ?::regclass and t.tgname = 'trg_stock_ledger_no_update_delete'", rel) != 1) {
                    problems.add(name + " has no cloned update and delete trigger");
                }
                if (DbAccess.count(c, "select count(*) from pg_attribute where attrelid = ?::regclass and attname = 'tenant_id' and not attisdropped", rel) != 1) {
                    problems.add(name + " has no tenant_id");
                }
            }
            return null;
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void aLineAt0030IstOnTheFirstOfDecemberLandsInDecemberAndTheBoundsOfTheFirstAndLastMonthAreInIst() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "PART1", "2031-03-31");
            String[][] cases = {
                {"2026-11-30 19:00:00+00", "stock_ledger_y2026m12"},   // 00:30 IST on 1 Dec
                {"2026-11-30 18:29:59+00", "stock_ledger_y2026m11"},   // 23:59:59 IST on 30 Nov
                {"2026-11-30 18:30:00+00", "stock_ledger_y2026m12"},   // 00:00:00 IST on 1 Dec
                {"2026-10-31 18:30:00+00", "stock_ledger_y2026m11"},   // 00:00:00 IST on 1 Nov 2026: the first moment of the first partition
                {"2030-03-31 18:29:59+00", "stock_ledger_y2030m03"},   // 23:59:59 IST on 31 Mar 2030: the last moment of the last partition
            };
            for (String[] t : cases) {
                String id = newId();
                DbAccess.execute(c, "insert into pharmacy.stock_ledger (id, unit_id, occurred_at, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, "
                        + "qty_out, balance_after, actor_kind, actor_membership_id) values (?::uuid, ?::uuid, ?::timestamptz, ?::uuid, 'OPENING_STOCK', ?::uuid, 'AVAILABLE', "
                        + "?::uuid, ?::uuid, 1, 0, 1, 'USER', ?::uuid)", id, w.unit(), t[0], newId(), w.central(), w.product(), batch, w.actor());
                assertThat(str(c, "select (select relname::text from pg_class where oid = l.tableoid) from pharmacy.stock_ledger l where l.id = ?::uuid", id))
                        .as(t[0]).isEqualTo(t[1]);
            }
        });
    }

    @Test
    void aLineOutsideTheCreatedMonthsFailsLoudlyBecauseThereIsNoDefaultPartition() throws Exception {
        Db w = dbWorld();
        asTenant(w, c -> {
            String batch = batch(c, w, "PART2", "2031-03-31");
            String sql = "insert into pharmacy.stock_ledger (unit_id, occurred_at, movement_id, txn_type, store_id, state, product_id, batch_id, qty_in, qty_out, balance_after, "
                    + "actor_kind, actor_membership_id) values (?::uuid, ?::timestamptz, ?::uuid, 'OPENING_STOCK', ?::uuid, 'AVAILABLE', ?::uuid, ?::uuid, 1, 0, 1, 'USER', ?::uuid)";
            for (String at : new String[] {"2030-12-01 00:00:00+05:30", "2030-03-31 18:30:00+00", "2026-10-31 18:29:59+00", "2026-10-15 10:00:00+00", "2040-01-01 00:00:00+00"}) {
                mustRefuse(c, "23514", "no partition of relation", sql, w.unit(), at, newId(), w.central(), w.product(), batch, w.actor());
            }
        });
    }

    @Test
    void createLedgerPartitionsIsIdempotentForTheOwnerRefusedForTheApplicationAndMakesOneMonthInATransactionThatIsRolledBack() throws Exception {
        asOwner(c -> {
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2026-11-01', 41)")).as("every month exists: nothing to make").isZero();
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2026-11-17', 3)")).as("a day inside the month is read as its month").isZero();
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2030-04-15', 1)")).isOne();
            assertThat(number(c, "select count(*) from pg_inherits where inhparent = 'pharmacy.stock_ledger'::regclass")).isEqualTo(42);
            String partition = "pharmacy.stock_ledger_y2030m04";
            assertThat(str(c, "select (relrowsecurity and relforcerowsecurity)::text from pg_class where oid = ?::regclass", partition)).isEqualTo("true");
            assertThat(number(c, "select count(*) from pg_policy where polrelid = ?::regclass", partition)).isEqualTo(2);
            assertThat(number(c, "select count(*) from pg_trigger t where t.tgrelid = ?::regclass and t.tgname = 'trg_stock_ledger_no_truncate'", partition)).isOne();
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2030-04-01', 1)")).as("a second call makes nothing").isZero();
            mustRefuse(c, "42501", "append-only", "truncate " + partition);
        });
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from pg_inherits where inhparent = 'pharmacy.stock_ledger'::regclass")).as("rolled back").isEqualTo(41);
            return null;
        });
        asOwner(c -> {
            mustRefuse(c, "22023", "p_months must be between 1 and 120", "select pharmacy.create_ledger_partitions(date '2030-04-01', 0)");
            mustRefuse(c, "22023", "p_months must be between 1 and 120", "select pharmacy.create_ledger_partitions(date '2030-04-01', 121)");
            mustRefuse(c, "22023", "p_from_month is required", "select pharmacy.create_ledger_partitions(null, 1)");
            assertThat(number(c, "select pharmacy.create_ledger_partitions(date '2030-04-01', 120)")).isEqualTo(120);
        });
        db().asApp(c -> {
            SQLExceptionHolder held = new SQLExceptionHolder();
            try (var st = c.createStatement()) {
                st.execute("select pharmacy.create_ledger_partitions(date '2030-04-01', 1)");
            } catch (java.sql.SQLException e) {
                held.error = e;
            }
            thatRefusal(held.error).isNotNull();
            assertThat(held.error.getSQLState()).isEqualTo("42501");
            return null;
        });
    }

    private static final class SQLExceptionHolder {
        java.sql.SQLException error;
    }

    /**
     * The upkeep check of the design note (section 3.3): a build fails when fewer than twelve months of partitions remain after
     * today, so the forward migration {@code create_ledger_partitions(date '2030-04-01', 12)} is not forgotten.
     */
    @Test
    void atLeastTwelveMonthsOfPartitionsRemainAfterToday() throws Exception {
        db().asApp(c -> {
            LocalDate last = LocalDate.parse(DbAccess.text(c, "select max(pg_catalog.to_date(substr(c.relname, 15, 4) || substr(c.relname, 20, 2), 'YYYYMM'))::text "
                    + "from pg_catalog.pg_inherits i join pg_catalog.pg_class c on c.oid = i.inhrelid where i.inhparent = 'pharmacy.stock_ledger'::regclass "
                    + "and c.relname ~ '^stock_ledger_y[0-9]{4}m(0[1-9]|1[0-2])$'"));
            assertThat(last).isEqualTo(LocalDate.of(2030, 3, 1));
            LocalDate today = todayIst().withDayOfMonth(1);
            long monthsAfterToday = java.time.temporal.ChronoUnit.MONTHS.between(today, last);
            assertThat(monthsAfterToday).as("months of partitions left after this month (a forward migration is due before April 2030)")
                    .isGreaterThanOrEqualTo(12);
            assertThat(DbAccess.count(c, "select count(*) from pg_inherits i join pg_class c on c.oid = i.inhrelid where i.inhparent = 'pharmacy.stock_ledger'::regclass "
                    + "and c.relname ~ '^stock_ledger_y[0-9]{4}m(0[1-9]|1[0-2])$'")).isEqualTo(41);
            return null;
        });
    }
}
