package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * SAH-38 (HARD-3) the new append-only table {@code platform.staff_pin_colleague_event} on the real {@code sahaj_test}
 * database (docs/database/SAH-38.md section 5, items 17 to 24 and 26 to 28): the four kinds with the statements the
 * application sends, the insert guard (database clock, "Got it" never in the future), the row checks, the composite foreign
 * keys that keep both ids in the clinic, row level security (enabled and forced, select and insert only), append-only for
 * every role including the owner, the grants (select and five insert columns), the catalog, the restart-proof pair count
 * (answered by the table alone), the 7-day and the right-PIN limits of that count, two connections for the row-lock cases
 * and a forced lock timeout. Rows are made by the application role with the clinic bound; committed rows are only the ones
 * a test needs from a second connection. Query plans are in {@code QueryPlansIT}. Test plan: docs/test-plans/SAH-38.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinColleagueEventTableIT extends PinTestSupport {

    private static final String T = "platform.staff_pin_colleague_event";
    private static final String[] TABLES = {T};

    private static final String INS_WRONG = "insert into " + T + " (membership_id, by_membership_id, kind, created_by) "
            + "values (?::uuid, ?::uuid, 'WRONG', ?::uuid)";
    private static final String INS_LOCKED = "insert into " + T + " (membership_id, kind, created_by) "
            + "values (?::uuid, 'LOCKED', ?::uuid)";
    private static final String INS_RIGHT = "insert into " + T + " (membership_id, by_membership_id, kind, created_by) "
            + "values (?::uuid, ?::uuid, 'RIGHT_AFTER_WRONG', ?::uuid)";
    private static final String INS_SEEN = "insert into " + T + " (membership_id, kind, seen_through, created_by) "
            + "values (?::uuid, 'NOTICE_SEEN', ?, ?::uuid)";

    /** Clinic A with owner N1 (PIN set), colleague N2 (PIN set), N3 (no PIN); clinic B with M1 (PIN set). */
    private record Set5(Clinic a, Clinic b, Pn n1, Pn n2, Pn n3, Pn m1) {
    }

    private Set5 setup() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn n1 = newPn(a, "NURSE");
        Pn n2 = newPn(a, "NURSE");
        Pn n3 = newPn(a, "NURSE");
        Pn m1 = newPn(b, "NURSE");
        setPin(n1, goodPin(n1));
        setPin(n2, goodPin(n2));
        setPin(m1, goodPin(m1));
        return new Set5(a, b, n1, n2, n3, m1);
    }

    private static String id(Pn p) {
        return p.membershipId().toString();
    }

    private static String user(Pn p) {
        return p.userId().toString();
    }

    /** Commits one statement as sahaj_app with the clinic bound. */
    private void commit(Clinic clinic, String sql, Object... params) throws SQLException {
        try (Connection c = tenantConnection(clinic)) {
            DbAccess.execute(c, sql, params);
            c.commit();
        }
    }

    private long eventRows(Clinic clinic, String where, Object... params) throws SQLException {
        return count(clinic, "select count(*) from " + T + " " + where, params);
    }

    // ---------------- 17 : the four kinds ----------------

    @Test
    void item17EachKindIsAcceptedWithTheDefaultsAndTwoRowsOfOneTransactionGetIncreasingTimes() throws Exception {
        Set5 s = setup();
        db().inTenant(s.a().tenantId(), c -> {
            DbAccess.execute(c, INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
            DbAccess.execute(c, INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
            DbAccess.execute(c, INS_LOCKED, id(s.n1()), user(s.n2()));
            DbAccess.execute(c, INS_RIGHT, id(s.n1()), id(s.n2()), user(s.n2()));
            DbAccess.execute(c, INS_SEEN, id(s.n1()), Timestamp.from(Instant.now().minusSeconds(60)), user(s.n1()));
            assertThat(DbAccess.count(c, "select count(*) from " + T)).isEqualTo(5);
            assertThat(DbAccess.count(c, "select count(*) from " + T + " where id is not null and tenant_id = ?::uuid "
                    + "and abs(extract(epoch from (clock_timestamp() - at))) < 5", s.a().tenantId().toString())).isEqualTo(5);
            assertThat(DbAccess.text(c, "select (min(at) < max(at))::text from " + T)).as("increasing times").isEqualTo("true");
            assertThat(DbAccess.text(c, "select string_agg(kind, ',' order by at, kind) from " + T))
                    .contains("WRONG", "LOCKED", "RIGHT_AFTER_WRONG", "NOTICE_SEEN");
            return null;
        });
    }

    // ---------------- 18 : at, seen_through ----------------

    @Test
    void item18TheTimeCannotBeSentAndAGotItInTheFutureIsStoredAtMostAsTheDatabaseClock() throws Exception {
        Set5 s = setup();
        assertThat(appErr(s.a(), "insert into " + T + " (membership_id, by_membership_id, kind, created_by, at) "
                + "values (?::uuid, ?::uuid, 'WRONG', ?::uuid, now())", id(s.n1()), id(s.n2()), user(s.n2())).state())
                .isEqualTo("42501");
        db().inTenant(s.a().tenantId(), c -> {
            DbAccess.execute(c, INS_SEEN, id(s.n1()), Timestamp.from(Instant.now().plusSeconds(3600)), user(s.n1()));
            assertThat(DbAccess.text(c, "select (seen_through <= at and seen_through <= clock_timestamp())::text from " + T
                    + " where kind = 'NOTICE_SEEN'")).as("the future is capped").isEqualTo("true");
            assertThat(DbAccess.text(c, "select (seen_through > clock_timestamp() - interval '1 minute')::text from " + T
                    + " where kind = 'NOTICE_SEEN'")).isEqualTo("true");
            return null;
        });
        db().inTenant(s.a().tenantId(), c -> {
            Timestamp past = Timestamp.from(Instant.parse("2026-11-01T08:30:15.123456Z"));
            DbAccess.execute(c, INS_SEEN, id(s.n1()), past, user(s.n1()));
            assertThat(DbAccess.text(c, "select (seen_through = ?)::text from " + T, past)).as("a past value stays").isEqualTo("true");
            return null;
        });
    }

    // ---------------- 19 : row checks ----------------

    @Test
    void item19TheRowChecksRefuseEveryWrongCombinationWithTheMatchingConstraintName() throws Exception {
        Set5 s = setup();
        Timestamp t = Timestamp.from(Instant.now().minusSeconds(5));
        String by = "ck_staff_pin_colleague_event_by_kind";
        String seen = "ck_staff_pin_colleague_event_seen_kind";
        String[][] cases = {
            {"WRONG without a caller", "insert into " + T + " (membership_id, kind, created_by) values (?::uuid, 'WRONG', ?::uuid)", by},
            {"RIGHT_AFTER_WRONG without a caller", "insert into " + T + " (membership_id, kind, created_by) values "
                    + "(?::uuid, 'RIGHT_AFTER_WRONG', ?::uuid)", by},
            {"LOCKED with a caller", "insert into " + T + " (membership_id, by_membership_id, kind, created_by) values "
                    + "(?::uuid, '" + id(s.n2()) + "'::uuid, 'LOCKED', ?::uuid)", by},
            {"NOTICE_SEEN with a caller", "insert into " + T + " (membership_id, by_membership_id, kind, seen_through, created_by) "
                    + "values (?::uuid, '" + id(s.n2()) + "'::uuid, 'NOTICE_SEEN', now(), ?::uuid)", by},
            {"NOTICE_SEEN without seen_through", "insert into " + T + " (membership_id, kind, created_by) values "
                    + "(?::uuid, 'NOTICE_SEEN', ?::uuid)", "colleague event"},
            {"WRONG with seen_through", "insert into " + T + " (membership_id, by_membership_id, kind, seen_through, created_by) "
                    + "values (?::uuid, '" + id(s.n2()) + "'::uuid, 'WRONG', now(), ?::uuid)", seen},
            {"LOCKED with seen_through", "insert into " + T + " (membership_id, kind, seen_through, created_by) values "
                    + "(?::uuid, 'LOCKED', now(), ?::uuid)", seen},
            {"the caller is the owner", "insert into " + T + " (membership_id, by_membership_id, kind, created_by) values "
                    + "(?::uuid, ?::uuid, 'WRONG', ?::uuid)", "ck_staff_pin_colleague_event_by_not_self"},
            {"kind OTHER", "insert into " + T + " (membership_id, kind, created_by) values (?::uuid, 'OTHER', ?::uuid)",
                    "ck_staff_pin_colleague_event_kind"},
        };
        for (String[] c : cases) {
            SqlErr e;
            if (c[0].equals("the caller is the owner")) {
                e = appErr(s.a(), c[1], id(s.n1()), id(s.n1()), user(s.n1()));
            } else {
                e = appErr(s.a(), c[1], id(s.n1()), user(s.n1()));
            }
            assertThat(e.state()).as(c[0] + ": " + e.message()).isEqualTo("23514");
            assertThat(e.message()).as(c[0]).contains(c[2]);
        }
        assertThat(t).isNotNull();
        assertThat(eventRows(s.a(), "")).as("nothing was kept").isZero();
    }

    // ---------------- 20 : composite foreign keys ----------------

    @Test
    void item20AnIdOfAnotherClinicAnUnknownIdOrAnOwnerWithNoPinIsRefusedByTheCompositeForeignKeys() throws Exception {
        Set5 s = setup();
        UUID random = UUID.randomUUID();
        SqlErr ownerOfB = appErr(s.a(), INS_WRONG, id(s.m1()), id(s.n2()), user(s.n2()));
        assertThat(ownerOfB.state()).isEqualTo("23503");
        assertThat(ownerOfB.message()).contains("fk_staff_pin_colleague_event_pin");
        SqlErr callerOfB = appErr(s.a(), INS_WRONG, id(s.n1()), id(s.m1()), user(s.n2()));
        assertThat(callerOfB.state()).isEqualTo("23503");
        assertThat(callerOfB.message()).contains("fk_staff_pin_colleague_event_by");
        assertThat(appErr(s.a(), INS_WRONG, id(s.n1()), random.toString(), user(s.n2())).state()).isEqualTo("23503");
        assertThat(appErr(s.a(), INS_WRONG, random.toString(), id(s.n2()), user(s.n2())).state()).isEqualTo("23503");
        SqlErr noPin = appErr(s.a(), INS_WRONG, id(s.n3()), id(s.n2()), user(s.n2()));
        assertThat(noPin.state()).as("a membership with no staff_pin row").isEqualTo("23503");
        assertThat(noPin.message()).contains("fk_staff_pin_colleague_event_pin");
        // as the table owner with the tenant named: A's ids under clinic B
        SqlErr named = ownerErr(T, "insert into " + T + " (tenant_id, membership_id, by_membership_id, kind, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 'WRONG', ?::uuid)", s.b().tenantId().toString(), id(s.n1()), id(s.n2()),
                user(s.n2()));
        assertThat(named.state()).isEqualTo("23503");
        // the application role may not name the tenant at all
        assertThat(appErr(s.a(), "insert into " + T + " (tenant_id, membership_id, by_membership_id, kind, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 'WRONG', ?::uuid)", s.a().tenantId().toString(), id(s.n1()), id(s.n2()),
                user(s.n2())).state()).isEqualTo("42501");
    }

    // ---------------- 21 : row level security ----------------

    @Test
    void item21AClinicNeverSeesAnotherClinicsRowsNothingShowsWithoutAClinicAndTheOwnerIsRefusedByForce() throws Exception {
        Set5 s = setup();
        commit(s.a(), INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        commit(s.b(), INS_LOCKED, id(s.m1()), user(s.m1()));
        assertThat(eventRows(s.a(), "")).isOne();
        assertThat(eventRows(s.b(), "")).isOne();
        assertThat(eventRows(s.a(), "where membership_id = ?::uuid", id(s.m1()))).as("B's row from A").isZero();
        assertThat(eventRows(s.b(), "where membership_id = ?::uuid", id(s.n1()))).as("A's row from B").isZero();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from " + T));
        assertThat(unbound).as("no clinic bound").isZero();
        SqlErr noTenant = appErrNoTenant(INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        assertThat(noTenant.state()).isIn("23502", "42501");
        // the table owner is not exempt (FORCE) and no policy names it
        SqlErr owner = null;
        try {
            db().asMigrator(c -> {
                DbAccess.execute(c, "insert into " + T + " (tenant_id, membership_id, by_membership_id, kind, created_by) "
                        + "values (?::uuid, ?::uuid, ?::uuid, 'WRONG', ?::uuid)", s.a().tenantId().toString(), id(s.n1()),
                        id(s.n2()), user(s.n2()));
                return null;
            });
        } catch (SQLException e) {
            owner = new SqlErr(e.getSQLState(), e.getMessage());
        }
        assertThat(owner).as("the owner under FORCE is refused").isNotNull();
        assertThat(owner.state()).isEqualTo("42501");
        assertThat(eventRows(s.a(), "")).isOne();
    }

    // ---------------- 22 : append-only ----------------

    @Test
    void item22UpdateDeleteAndTruncateAreRefusedForTheApplicationRoleByPrivilegeAndForTheOwnerByTheTrigger() throws Exception {
        Set5 s = setup();
        commit(s.a(), INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        commit(s.a(), INS_LOCKED, id(s.n1()), user(s.n2()));
        String before = values(s.a(), "select string_agg(e::text, ',' order by id) from " + T + " e").get(0);
        assertThat(appErr(s.a(), "update " + T + " set kind = kind").state()).isEqualTo("42501");
        assertThat(appErr(s.a(), "delete from " + T).state()).isEqualTo("42501");
        assertThat(appErr(s.a(), "truncate " + T).state()).isEqualTo("42501");
        for (String sql : new String[] {"update " + T + " set kind = kind", "update " + T + " set kind = 'LOCKED'",
            "delete from " + T, "truncate " + T, "truncate platform.staff_pin cascade"}) {
            SqlErr e = ownerErr(T, sql);
            assertThat(e.state()).as(sql + ": " + e.message()).isEqualTo("42501");
            assertThat(e.message()).as(sql).contains("append-only");
        }
        assertThat(values(s.a(), "select string_agg(e::text, ',' order by id) from " + T + " e").get(0))
                .as("rows unchanged, including update set kind = kind").isEqualTo(before);
        assertThat(eventRows(s.a(), "")).isEqualTo(2);
    }

    // ---------------- 23 : grants ----------------

    @Test
    void item23TheApplicationRoleHasSelectAndInsertOfExactlyFiveColumnsAndNothingElseAndNothingIsOpenToPublic() throws Exception {
        Set<String> insert = Set.of("membership_id", "by_membership_id", "kind", "seen_through", "created_by");
        db().asApp(c -> {
            List<String> all = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select column_name from information_schema.columns where "
                    + "table_schema = 'platform' and table_name = 'staff_pin_colleague_event'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(rs.getString(1));
                }
            }
            assertThat(all).containsExactlyInAnyOrder("id", "tenant_id", "membership_id", "by_membership_id", "kind",
                    "seen_through", "at", "created_by");
            Set<String> insertable = new HashSet<>();
            for (String column : all) {
                if (priv(c, "has_column_privilege('sahaj_app', '" + T + "', '" + column + "', 'INSERT')")) {
                    insertable.add(column);
                }
                assertThat(priv(c, "has_column_privilege('sahaj_app', '" + T + "', '" + column + "', 'UPDATE')"))
                        .as("update of " + column).isFalse();
                assertThat(priv(c, "has_column_privilege('sahaj_app', '" + T + "', '" + column + "', 'SELECT')"))
                        .as("select of " + column).isTrue();
            }
            assertThat(insertable).isEqualTo(insert);
            assertThat(insertable).doesNotContain("at", "id", "tenant_id");
            assertThat(priv(c, "has_table_privilege('sahaj_app', '" + T + "', 'SELECT')")).isTrue();
            for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(priv(c, "has_table_privilege('sahaj_app', '" + T + "', '" + privilege + "')")).as(privilege).isFalse();
            }
            assertThat(DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace, "
                    + "aclexplode(cl.relacl) a where n.nspname = 'platform' and cl.relname = 'staff_pin_colleague_event' "
                    + "and a.grantee = 0")).as("grants to PUBLIC").isZero();
            assertThat(DbAccess.count(c, "select count(*) from pg_attribute att join pg_class cl on cl.oid = att.attrelid "
                    + "join pg_namespace n on n.oid = cl.relnamespace, aclexplode(att.attacl) a where n.nspname = 'platform' "
                    + "and cl.relname = 'staff_pin_colleague_event' and a.grantee = 0")).as("column grants to PUBLIC").isZero();
            // the staff_pin update list is 15 columns now (StaffPinTableIT item 20 holds the exact list)
            assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = 'platform' "
                    + "and table_name = 'staff_pin' and has_column_privilege('sahaj_app', 'platform.staff_pin', column_name, "
                    + "'UPDATE')")).isEqualTo(15);
            return null;
        });
    }

    private static boolean priv(Connection c, String expression) throws SQLException {
        return Boolean.parseBoolean(DbAccess.text(c, "select " + expression + "::text"));
    }

    // ---------------- 24 : catalog ----------------

    @Test
    void item24TheCatalogShowsForcedRlsTwoPoliciesThreeTriggersFixedSearchPathGuardsThreeIndexesAndCleanComments() throws Exception {
        db().asApp(c -> {
            try (PreparedStatement ps = c.prepareStatement("select relrowsecurity, relforcerowsecurity from pg_class cl join "
                    + "pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'platform' and cl.relname = "
                    + "'staff_pin_colleague_event'"); ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isTrue();
            }
            List<String> policies = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select p.polcmd::text, coalesce((select string_agg(r.rolname, ',') "
                    + "from pg_roles r where r.oid = any (p.polroles)), '') as roles, lower(coalesce(pg_get_expr(p.polqual, "
                    + "p.polrelid), '')) || ' ' || lower(coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')) as expr from "
                    + "pg_policy p join pg_class cl on cl.oid = p.polrelid join pg_namespace n on n.oid = cl.relnamespace "
                    + "where n.nspname = 'platform' and cl.relname = 'staff_pin_colleague_event' order by 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    policies.add(rs.getString(1));
                    assertThat(rs.getString(2)).isEqualTo("sahaj_app");
                    assertThat(rs.getString(3)).contains("current_tenant()").doesNotContain("true").doesNotContain(" or ")
                            .doesNotContain("coalesce").doesNotContain("is null");
                }
            }
            assertThat(policies).containsExactlyInAnyOrder("r", "a");
            List<String> triggers = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select t.tgname || ':' || t.tgenabled::text from pg_trigger t join "
                    + "pg_class cl on cl.oid = t.tgrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = "
                    + "'platform' and cl.relname = 'staff_pin_colleague_event' and not t.tgisinternal order by 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(rs.getString(1));
                }
            }
            assertThat(triggers).containsExactly("trg_staff_pin_colleague_event_guard_insert:O",
                    "trg_staff_pin_colleague_event_no_truncate:O", "trg_staff_pin_colleague_event_no_update_delete:O");
            for (String function : new String[] {"guard_staff_pin_colleague_event_insert", "guard_staff_pin_update"}) {
                assertThat(DbAccess.text(c, "select (not prosecdef)::text || ':' || coalesce(array_to_string(proconfig, ','), '') "
                        + "|| ':' || (select count(*) from aclexplode(coalesce(proacl, acldefault('f', proowner))) a "
                        + "where a.grantee = 0 and a.privilege_type = 'EXECUTE') from pg_proc p join pg_namespace n on "
                        + "n.oid = p.pronamespace where n.nspname = 'platform' and p.proname = '" + function + "'"))
                        .as(function).isEqualTo("true:search_path=pg_catalog:0");
                assertThat(priv(c, "has_function_privilege('sahaj_app', 'platform." + function + "()', 'EXECUTE')"))
                        .as("sahaj_app execute on " + function).isFalse();
            }
            List<String> indexes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select indexname || ' ' || indexdef from pg_indexes where schemaname = "
                    + "'platform' and tablename = 'staff_pin_colleague_event' order by indexname");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
            }
            assertThat(String.join("\n", indexes))
                    .contains("ix_staff_pin_colleague_event_pair")
                    .contains("(tenant_id, membership_id, by_membership_id, at)")
                    .contains("ix_staff_pin_colleague_event_member")
                    .contains("(tenant_id, membership_id, at)")
                    .contains("ix_staff_pin_colleague_event_wrong_7d")
                    .contains("(tenant_id, at) WHERE (kind = 'WRONG'::text)")
                    .contains("pk_staff_pin_colleague_event")
                    // V2026_12_02_1000 (finding B3): the two indexes that carry kind
                    .contains("ix_staff_pin_colleague_event_pair_kind")
                    .contains("(tenant_id, membership_id, by_membership_id, kind, at)")
                    .contains("ix_staff_pin_colleague_event_member_kind")
                    .contains("(tenant_id, membership_id, kind, at)");
            assertThat(indexes).as("pk + pair + member + wrong_7d + pair_kind + member_kind").hasSize(6);
            assertThat(DbAccess.count(c, "select count(*) from pg_index i join pg_class ic on ic.oid = i.indexrelid join pg_namespace n "
                    + "on n.oid = ic.relnamespace where n.nspname = 'platform' and ic.relname in "
                    + "('ix_staff_pin_colleague_event_pair_kind', 'ix_staff_pin_colleague_event_member_kind') and i.indisvalid "
                    + "and i.indisready and obj_description(ic.oid, 'pg_class') like 'SAH-38:%'"))
                    .as("both new indexes are valid and carry a SAH-38 comment").isEqualTo(2);
            String comments = DbAccess.text(c, "select coalesce(string_agg(d.description, E'\\n'), '') from pg_description d "
                    + "join pg_class cl on cl.oid = d.objoid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = "
                    + "'platform' and cl.relname = 'staff_pin_colleague_event'");
            assertThat(comments).isNotBlank().contains("append-only").doesNotContain("$2a$").doesNotContain("bcrypt");
            assertThat(DbAccess.count(c, "select count(*) from pg_description d join pg_class cl on cl.oid = d.objoid join "
                    + "pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'platform' and cl.relname = "
                    + "'staff_pin_colleague_event' and d.objsubid > 0")).as("one comment per described column").isEqualTo(5);
            return null;
        });
    }

    // ---------------- 26 : the restart-proof cap ----------------

    /** The pair count of docs/database/SAH-38.md section 4 (the statement the service sends), from a fresh connection. */
    private long pairCount(Clinic clinic, Pn owner, Pn caller, Timestamp setAt) throws SQLException {
        try (Connection c = tenantConnection(clinic)) {
            long n = DbAccess.count(c, "select count(*) from " + T + " where tenant_id = ?::uuid and membership_id = ?::uuid "
                    + "and by_membership_id = ?::uuid and kind = 'WRONG' and at > greatest(clock_timestamp() - interval "
                    + "'7 days', ?, coalesce((select max(at) from " + T + " where tenant_id = ?::uuid and membership_id = "
                    + "?::uuid and by_membership_id = ?::uuid and kind = 'RIGHT_AFTER_WRONG'), '-infinity'::timestamptz))",
                    clinic.tenantId().toString(), id(owner), id(caller), setAt, clinic.tenantId().toString(), id(owner),
                    id(caller));
            c.rollback();
            return n;
        }
    }

    @Test
    void item26TheCountComesFromTheTableAloneAndIgnoresRowsOlderThanSevenDaysOlderThanTheLastRightPinOrOlderThanThePinItself()
            throws Exception {
        Set5 s = setup();
        Timestamp longAgo = Timestamp.from(Instant.now().minusSeconds(30L * 24 * 3600));
        try (Connection c = tenantConnection(s.a())) {
            for (int i = 0; i < 5; i++) {
                DbAccess.execute(c, INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
            }
            c.commit();
        }
        assertThat(pairCount(s.a(), s.n1(), s.n2(), longAgo)).as("a fresh connection sees the five committed rows").isEqualTo(5);
        // 8-day old rows (written by the owner with the insert guard off) are not counted
        asOwner(true, TABLES, c -> {
            DbAccess.execute(c, "alter table " + T + " disable trigger trg_staff_pin_colleague_event_guard_insert");
            DbAccess.execute(c, "insert into " + T + " (tenant_id, membership_id, by_membership_id, kind, created_by, at) "
                    + "select ?::uuid, ?::uuid, ?::uuid, 'WRONG', ?::uuid, clock_timestamp() - interval '8 days' "
                    + "from generate_series(1, 3)", s.a().tenantId().toString(), id(s.n1()), id(s.n2()), user(s.n2()));
            DbAccess.execute(c, "alter table " + T + " enable trigger trg_staff_pin_colleague_event_guard_insert");
            return null;
        });
        assertThat(pairCount(s.a(), s.n1(), s.n2(), longAgo)).as("rows 8 days old are outside the window").isEqualTo(5);
        // another pair is not counted
        assertThat(pairCount(s.a(), s.n1(), s.n3(), longAgo)).isZero();
        assertThat(pairCount(s.a(), s.n2(), s.n1(), longAgo)).as("the pair is ordered").isZero();
        // the PIN itself is newer than the rows: the pair starts again
        assertThat(pairCount(s.a(), s.n1(), s.n2(), Timestamp.from(Instant.now().plusSeconds(60)))).isZero();
        // a right PIN clears the pair; rows written after it count again
        commit(s.a(), INS_RIGHT, id(s.n1()), id(s.n2()), user(s.n2()));
        assertThat(pairCount(s.a(), s.n1(), s.n2(), longAgo)).isZero();
        commit(s.a(), INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        commit(s.a(), INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        assertThat(pairCount(s.a(), s.n1(), s.n2(), longAgo)).isEqualTo(2);
    }

    // ---------------- 27 and 28 : two connections ----------------

    private void ownerRow(Connection c, Pn owner, Clinic clinic) throws SQLException {
        DbAccess.execute(c, "select 1 from platform.staff_pin where tenant_id = ?::uuid and membership_id = ?::uuid for update",
                clinic.tenantId().toString(), id(owner));
    }

    private static final String COLLEAGUE_STEP = "update platform.staff_pin set failed_count = failed_count + 1, "
            + "run_has_colleague_try = true, updated_by = ?::uuid, version = version + 1 where membership_id = ?::uuid";

    @Test
    void item27TwoParallelColleagueTriesOnOneRowWaitForEachOtherAndBothAreCounted() throws Exception {
        Set5 s = setup();
        try (Connection one = tenantConnection(s.a()); Connection two = tenantConnection(s.a())) {
            ownerRow(one, s.n1(), s.a());
            CompletableFuture<Long> second = CompletableFuture.supplyAsync(() -> {
                try {
                    ownerRow(two, s.n1(), s.a());
                    long seen = DbAccess.count(two, "select count(*) from " + T + " where membership_id = ?::uuid and kind = 'WRONG'",
                            id(s.n1()));
                    DbAccess.execute(two, INS_WRONG, id(s.n1()), id(s.n3()), user(s.n3()));
                    DbAccess.execute(two, COLLEAGUE_STEP, user(s.n3()), id(s.n1()));
                    two.commit();
                    return seen;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(second.isDone()).as("the second try waits for the first row lock").isFalse();
            DbAccess.execute(one, INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
            DbAccess.execute(one, COLLEAGUE_STEP, user(s.n2()), id(s.n1()));
            one.commit();
            assertThat(second.get(10, TimeUnit.SECONDS)).as("the second sees the first one's committed row").isOne();
        }
        assertThat(eventRows(s.a(), "where membership_id = ?::uuid and kind = 'WRONG'", id(s.n1()))).isEqualTo(2);
        assertThat(values(s.a(), "select failed_count::text || run_has_colleague_try::text from platform.staff_pin "
                + "where membership_id = ?::uuid", id(s.n1())).get(0)).as("both tries counted").isEqualTo("2true");
    }

    @Test
    void item27bAGotItInsertWhileAColleagueCheckHoldsTheRowLockWaitsWithoutErrorAndSucceedsAfterwards() throws Exception {
        Set5 s = setup();
        commit(s.a(), INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
        try (Connection holder = tenantConnection(s.a()); Connection owner = tenantConnection(s.a())) {
            ownerRow(holder, s.n1(), s.a());
            CompletableFuture<Void> got = CompletableFuture.runAsync(() -> {
                try {
                    DbAccess.execute(owner, INS_SEEN, id(s.n1()), Timestamp.from(Instant.now()), user(s.n1()));
                    owner.commit();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(got.isDone()).as("the key-share lock on the PIN row waits for the colleague check").isFalse();
            holder.commit();
            got.get(10, TimeUnit.SECONDS);
        }
        assertThat(eventRows(s.a(), "where kind = 'NOTICE_SEEN' and membership_id = ?::uuid", id(s.n1()))).isOne();
    }

    @Test
    void item28AForcedLockTimeoutGives55P03AndTheTransactionRollsBackWithNoEventRowAndNoPinChange() throws Exception {
        Set5 s = setup();
        String pinBefore = values(s.a(), "select to_jsonb(p)::text from platform.staff_pin p where membership_id = ?::uuid",
                id(s.n1())).get(0);
        long rowsBefore = eventRows(s.a(), "");
        try (Connection holder = tenantConnection(s.a()); Connection other = tenantConnection(s.a())) {
            ownerRow(holder, s.n1(), s.a());
            DbAccess.execute(other, "set local lock_timeout = '1ms'");
            DbAccess.execute(other, "set local statement_timeout = '5s'");
            SQLException failure = null;
            try {
                DbAccess.execute(other, INS_WRONG, id(s.n1()), id(s.n2()), user(s.n2()));
            } catch (SQLException e) {
                failure = e;
            }
            assertThat(failure != null).as("the insert must wait and fail").isTrue();
            assertThat(failure.getSQLState()).isEqualTo("55P03");
            other.rollback();
            holder.rollback();
        }
        assertThat(eventRows(s.a(), "")).isEqualTo(rowsBefore);
        assertThat(values(s.a(), "select to_jsonb(p)::text from platform.staff_pin p where membership_id = ?::uuid", id(s.n1()))
                .get(0)).isEqualTo(pinBefore);
    }
}
