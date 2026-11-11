package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import static com.sahaj.platform.it.RealSql.ADMISSION_REPO;
import static com.sahaj.platform.it.RealSql.EVENT_REPO;
import static com.sahaj.platform.it.RealSql.ROUND_REPO;
import static com.sahaj.platform.it.RealSql.WARD_REPO;
import static com.sahaj.platform.it.RealSql.arrayLiteral;
import static com.sahaj.platform.it.RealSql.bind;
import static com.sahaj.platform.it.RealSql.constant;
import static com.sahaj.platform.it.RealSql.pageSql;
import static com.sahaj.platform.it.RealSql.params;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.it.RealSql.Stmt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-34 the query plans of docs/database/SAH-34.md section 10 and design note 1.5, run for real: as {@code sahaj_app} with the
 * clinic bound ({@code app.tenant_id}) on a seeded {@code sahaj_test} hospital of 26 wards, 300 admitted patients, about 36,000
 * clinical events, 90,000 doses, 3,650 old reviewed grants and 30 unreviewed ones.
 *
 * <p>The statements are the repositories' own: the constants of {@code JdbcWardScopeRepository}, {@code JdbcClinicalEventRepository},
 * {@code JdbcAdmissionRepository} and {@code JdbcMedicineRoundRepository} are read by reflection (so a changed statement is the
 * statement measured, no copy can drift), the named parameters become real JDBC parameters bound like {@code JdbcClient} binds
 * them (an array is a text literal cast in the statement, the case of the 200 ms of the review), and each one runs under
 * {@code explain (analyze, buffers)}.
 *
 * <p>Code review finding 6: the assertions are WORK COUNTS (rows read by the scan nodes, rows removed by a filter), not plan
 * words, so they do not flip with the PostgreSQL version or autovacuum; a budget is set only where the statement reaches the
 * rows through an index whatever the statistics say. The reads whose plan the planner may choose differently on a given day (the
 * scoped clinical-events page, R4, R4b, R4d) are recorded as evidence and their answers are asserted, and the request that
 * stands for them (GET /clinical-events as a scoped nurse) has a generous time ceiling, which the old 200 ms broke. The whole plan
 * output goes to {@code target/sah34-explain.txt} and to the test log. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class ScopePlansIT extends ScopeSupport {

    private static final int WARDS = 26;
    private static final int ADMITTED = 300;
    private static final int DOSES_PER_ORDER = 300;
    private static final Pattern SCAN = Pattern.compile("(?:Index Only Scan|Index Scan|Bitmap Heap Scan|Bitmap Index Scan|Seq Scan)"
            + ".*actual time=[\\d.]+\\.\\.[\\d.]+ rows=([\\d.]+) loops=(\\d+)");
    private static final Pattern REMOVED = Pattern.compile("Rows Removed by (?:Filter|Index Recheck): (\\d+)");


    private List<String> explain(Clinic clinic, boolean seqscanOff, boolean rollback, Stmt stmt) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            Savepoint sp = c.setSavepoint();
            if (seqscanOff) {
                try (Statement st = c.createStatement()) {
                    st.execute("set local enable_seqscan = off");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("explain (analyze, buffers) " + stmt.sql())) {
                for (int i = 0; i < stmt.values().size(); i++) {
                    ps.setObject(i + 1, stmt.values().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            if (rollback) {
                c.rollback(sp);
            }
            return out;
        });
    }

    /**
     * Rows read by the scan nodes of a plan: the rows each scan returned (times its loops) plus the rows a filter or an index
     * recheck threw away. This is the work of the statement; a plan word is only how the planner chose to do it.
     */
    static double rowsRead(List<String> plan) {
        double total = 0;
        double loops = 1;
        for (String line : plan) {
            Matcher scan = SCAN.matcher(line);
            if (scan.find()) {
                loops = Double.parseDouble(scan.group(2));
                total += Double.parseDouble(scan.group(1)) * loops;
            }
            Matcher removed = REMOVED.matcher(line);
            if (removed.find()) {
                total += Double.parseDouble(removed.group(1)) * loops;
            }
        }
        return total;
    }

    private static String executionMs(List<String> plan) {
        for (String line : plan) {
            if (line.startsWith("Execution Time:")) {
                return line.substring("Execution Time:".length()).trim();
            }
        }
        return "?";
    }

    @Test
    void theQueryPlansOfTheScopeReadsOnAHospitalOf26WardsAnd300AdmittedPatients() throws Exception {
        Clinic clinic = hospital();
        String tenant = clinic.tenantId().toString();
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set bed_limit = 300");
        List<String> wardIds = new ArrayList<>();
        wardIds.add(wardIdByName(clinic, "General ward"));
        for (int i = 2; i <= WARDS; i++) {
            wardIds.add(newWard(clinic, "Ward " + i));
        }
        Pn nurse = newPn(clinic, "NURSE");
        Pn other = newPn(clinic, "NURSE");
        List<Adm> admissions = new ArrayList<>();
        for (int i = 0; i < ADMITTED; i++) {
            // the first one has "None known" allergy so that the real order endpoint places the template order without a warning
            admissions.add(i == 0 ? admitWithAllergy(clinic, "None known") : admitNamed(clinic, "Plan Patient " + i + " " + uniqueMobile().substring(6)));
        }
        // spread the beds over the 26 wards (the table owner; the application never moves a bed's ward)
        asOwner(true, new String[] {"ipd.bed", "ipd.ward"}, c -> {
            DbAccess.execute(c, "with ws as (select id, row_number() over (order by id) - 1 as rn from ipd.ward where tenant_id = '" + tenant
                    + "'::uuid), bs as (select id, row_number() over (order by id) - 1 as rn from ipd.bed where tenant_id = '" + tenant
                    + "'::uuid) update ipd.bed b set ward_id = ws.id, room_id = null from bs, ws where b.tenant_id = '" + tenant
                    + "'::uuid and b.id = bs.id and ws.rn = bs.rn % " + WARDS);
            return null;
        });
        List<String> nurseWards = new ArrayList<>(List.of(wardIds.get(0), wardIds.get(1)));
        ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + nurseWards.get(0), clinic.owner(), idsJson(nurse.membershipId(), other.membershipId()), 200);
        ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + nurseWards.get(1), clinic.owner(), idsJson(nurse.membershipId()), 200);
        switchScope(clinic, true);
        Pn lone = newPn(clinic, "NURSE"); // added after the switch: no ward and no grant, the fail-closed state of every new nurse (R4d)

        // one real active order, copied to every other admission, and 300 doses on each (90,000 rows) for DOSES_BETWEEN
        Staff doctor = staff(clinic, "DOCTOR");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String pin = goodPin(pharmacist);
        setPin(pharmacist, pin);
        String firstUhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", admissions.get(0).patientId()).get(0);
        String template = freshActiveOrder(new Ward(clinic, doctor, nurse, other, "0000", pharmacist, pin, admissions.get(0), firstUhid),
                "pcm", "od");
        int doses = asOwner(true, new String[] {"ipd.medicine_order", "ipd.admission", "ipd.dose_administration"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger user");
            DbAccess.execute(c, "insert into ipd.medicine_order select (jsonb_populate_record(null::ipd.medicine_order, to_jsonb(m) "
                    + "|| jsonb_build_object('id', gen_random_uuid(), 'client_key', gen_random_uuid(), 'admission_id', a.id))).* "
                    + "from ipd.medicine_order m cross join ipd.admission a where m.id = '" + template + "'::uuid and a.tenant_id = '"
                    + tenant + "'::uuid and a.id <> m.admission_id");
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger user");
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger trg_dose_administration_guard_insert");
            int n;
            try (var ps = c.prepareStatement("insert into ipd.dose_administration (tenant_id, admission_id, order_id, client_key, slot_at, "
                    + "outcome, reason_key, recorded_by, high_alert) select o.tenant_id, o.admission_id, o.id, gen_random_uuid(), "
                    + "date_trunc('minute', now()) - (g * interval '6 hours'), 'HELD', 'HELD_DOCTOR', ?::uuid, false "
                    + "from ipd.medicine_order o cross join generate_series(0, " + (DOSES_PER_ORDER - 1) + ") g where o.tenant_id = ?::uuid")) {
                ps.setString(1, doctor.userId().toString());
                ps.setString(2, tenant);
                n = ps.executeUpdate();
            }
            DbAccess.execute(c, "alter table ipd.dose_administration enable trigger trg_dose_administration_guard_insert");
            return n;
        });
        assertThat(doses).isEqualTo(ADMITTED * DOSES_PER_ORDER);

        // about 36,000 clinical events, 120 per admission over 30 days, by 40 actors, and the grants
        int events = asOwner(true, new String[] {"ipd.clinical_event", "ipd.admission"}, c -> {
            int n;
            try (var ps = c.prepareStatement("insert into ipd.clinical_event (tenant_id, admission_id, kind, text, actor, at) "
                    + "select a.tenant_id, a.id, 'info', 'Seeded entry', "
                    + "(array[?::uuid, ?::uuid, gen_random_uuid(), gen_random_uuid()])[1 + (g % 4)], "
                    + "now() - (g * interval '17 minutes') - (abs(hashtext(a.id::text)) % 600) * interval '1 second' "
                    + "from ipd.admission a cross join generate_series(1, 120) g where a.tenant_id = ?::uuid")) {
                ps.setString(1, nurse.userId().toString());
                ps.setString(2, other.userId().toString());
                ps.setString(3, tenant);
                n = ps.executeUpdate();
            }
            // the lone nurse wrote 10 entries, long ago: fewer than a page, so her page must look through the whole log
            DbAccess.execute(c, "insert into ipd.clinical_event (tenant_id, admission_id, kind, text, actor, at) select a.tenant_id, a.id, "
                    + "'info', 'Seeded entry', '" + lone.userId() + "'::uuid, now() - interval '60 days' - g * interval '1 hour' "
                    + "from (select * from ipd.admission where tenant_id = '" + tenant + "'::uuid limit 1) a cross join generate_series(1, 10) g");
            return n;
        });
        assertThat(events).isEqualTo(ADMITTED * 120);
        asOwner(true, GRANT_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.emergency_access_grant disable trigger trg_emergency_access_grant_stamp");
            DbAccess.execute(c, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                    + "reason_key, opened_at, expires_at, reviewed_by, reviewed_at) select a.tenant_id, a.id, a.bed_id, ?::uuid, ?::uuid, "
                    + "'NURSE', 'COVERING_BREAK', t, t + interval '30 minutes', ?::uuid, t + interval '1 day' from ("
                    + "select x.*, now() - (g * interval '2 hours') as t, row_number() over () as rn from ipd.admission x "
                    + "cross join generate_series(1, 13) g where x.tenant_id = ?::uuid limit 3650) a",
                    other.userId().toString(), other.membershipId().toString(), clinic.ownerUserId().toString(), tenant);
            // 25 more unreviewed grants of another member, long expired (30 unreviewed in all with the five below)
            DbAccess.execute(c, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                    + "reason_key, opened_at, expires_at) select a.tenant_id, a.id, a.bed_id, ?::uuid, ?::uuid, 'NURSE', 'COVERING_BREAK', "
                    + "t, t + interval '30 minutes' from (select x.*, now() - interval '2 days' - (row_number() over () * interval '1 hour') "
                    + "as t from (select * from ipd.admission where tenant_id = ?::uuid limit 25) x) a",
                    other.userId().toString(), other.membershipId().toString(), tenant);
            DbAccess.execute(c, "alter table ipd.emergency_access_grant enable trigger trg_emergency_access_grant_stamp");
            return null;
        });
        // 5 unreviewed grants (4 expired, 1 live) of the nurse
        for (int i = 0; i < 4; i++) {
            insertGrantAged(clinic, nurse, admissions.get(10 + i).id(), "DETERIORATING", (40 + i) * 60, null);
        }
        insertGrantEndingIn(clinic, nurse, admissions.get(20).id(), 1500);
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            for (String table : new String[] {"ipd.clinical_event", "ipd.emergency_access_grant", "ipd.ward_assignment", "ipd.admission",
                "ipd.bed", "ipd.ward", "ipd.ews_escalation", "ipd.dose_administration", "ipd.medicine_order"}) {
                DbAccess.execute(c, "analyze " + table);
            }
        }
        assertThat(count(clinic, "select count(*) from ipd.admission where status = 'ADMITTED'")).isEqualTo(ADMITTED);
        assertThat(count(clinic, "select count(*) from ipd.emergency_access_grant")).isEqualTo(3680);
        assertThat(count(clinic, "select count(*) from ipd.emergency_access_grant where reviewed_at is null")).isEqualTo(30);

        UUID t = clinic.tenantId();
        String wardLiteral = arrayLiteral(nurseWards);
        String grantLiteral = arrayLiteral(List.of(admissions.get(20).id()));
        String allAdmissions = arrayLiteral(admissions.stream().map(Adm::id).toList());
        String tenWards = arrayLiteral(admissions.subList(0, 12).stream().map(Adm::id).toList());
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);

        String liveGrants = constant(WARD_REPO, "LIVE_GRANTS");
        String scopedNurse = constant(EVENT_REPO, "SCOPED_NURSE");
        String oldCast = scopedNurse.replace("in (select unnest(cast(:wards as uuid[])))", "= any(cast(:wards as uuid[]))")
                .replace("in (select unnest(cast(:grants as uuid[])))", "= any(cast(:grants as uuid[]))");
        assertThat(oldCast).as("the comparison text really is the old cast pattern").isNotEqualTo(scopedNurse);
        String pageHead = constant(EVENT_REPO, "PAGE_HEAD");
        String pageTail = constant(EVENT_REPO, "PAGE_TAIL");
        String doses2 = constant(ROUND_REPO, "DOSES_BETWEEN");
        String dosesUnnest = doses2.replace("= any(cast(:admissionIds as uuid[]))", "in (select unnest(cast(:admissionIds as uuid[])))");
        assertThat(dosesUnnest).isNotEqualTo(doses2);

        Map<String, Object> nursePage = params("tenantId", t, "nurse", nurse.userId(), "wards", wardLiteral, "grants", grantLiteral, "limit", 51);
        // name, statement, work budget (rows read) or -1 for "evidence only", write?
        List<Object[]> statements = new ArrayList<>();
        statements.add(new Object[] {"S1 scope build, wards (LIVE_WARDS)", bind(constant(WARD_REPO, "LIVE_WARDS"),
                params("tenantId", t, "membershipId", nurse.membershipId())), 10, false});
        statements.add(new Object[] {"S2 scope build, grants (LIVE_GRANTS), 5 unreviewed of her own, 1 live", bind(liveGrants,
                params("tenantId", t, "membershipId", nurse.membershipId(), "role", "NURSE")), 30, false});
        statements.add(new Object[] {"S2b LIVE_GRANTS for a member who opened 3,650 grants over the year (all old; after F-1 a live-range scan)",
                bind(liveGrants, params("tenantId", t, "membershipId", other.membershipId(), "role", "NURSE")), 50, false});
        statements.add(new Object[] {"S2c HAS_LIVE_GRANT (the liveness check under the lock) for the same member",
                bind(constant(WARD_REPO, "HAS_LIVE_GRANT"), params("tenantId", t, "membershipId", other.membershipId(), "role", "NURSE",
                        "admissionId", UUID.fromString(admissions.get(5).id()))), 50, false});
        statements.add(new Object[] {"Narrow admitted-set read (FIND_ADMITTED_FACTS, ScopeGuard.visibleAdmissionIds)",
                bind(constant(WARD_REPO, "FIND_ADMITTED_FACTS"), params("tenantId", t)), 2000, false});
        statements.add(new Object[] {"C1 admitted count (COUNT_ADMITTED)", bind(constant(ADMISSION_REPO, "COUNT_ADMITTED"),
                params("tenantId", t)), 400, false});
        statements.add(new Object[] {"R4 clinical events, scoped nurse with 2 wards and 1 grant (the real statement, limit 51)",
                bind(pageSql(false, false), nursePage), -1, false});
        statements.add(new Object[] {"R4x the same with the OLD text (= any(cast(:wards as uuid[]))), for the comparison",
                bind(pageHead + oldCast + pageTail, nursePage), -1, false});
        statements.add(new Object[] {"R4b clinical events, scoped nurse with no ward and no grant, many own entries (ACTOR_ONLY)",
                bind(pageSql(true, false), params("tenantId", t, "nurse", nurse.userId(), "limit", 51)), -1, false});
        statements.add(new Object[] {"R4d clinical events, scoped nurse with no ward, no grant and 10 own entries (ACTOR_ONLY; the page must "
                + "look through the whole log)", bind(pageSql(true, false), params("tenantId", t, "nurse", lone.userId(), "limit", 51)), -1, false});
        statements.add(new Object[] {"R4c clinical events, OWNER (unscoped, as before SAH-34)",
                bind(pageHead + pageTail, params("tenantId", t, "limit", 51)), -1, false});
        statements.add(new Object[] {"U1 unreviewed count (COUNT_UNREVIEWED)", bind(constant(WARD_REPO, "COUNT_UNREVIEWED"),
                params("tenantId", t)), 100, false});
        statements.add(new Object[] {"U2 unreviewed mark for the change token (UNREVIEWED_MARK: count and newest id)",
                bind(constant(WARD_REPO, "UNREVIEWED_MARK"), params("tenantId", t)), 100, false});
        statements.add(new Object[] {"U3 review (MARK_REVIEWED), rolled back after the plan", bind(constant(WARD_REPO, "MARK_REVIEWED"),
                params("tenantId", t, "me", UUID.randomUUID(), "id", UUID.fromString(values(clinic, "select id::text from "
                        + "ipd.emergency_access_grant where reviewed_at is null order by opened_at limit 1").get(0)))), 30, true});
        statements.add(new Object[] {"D1 JdbcMedicineRoundRepository.DOSES_BETWEEN, 300 admissions, one day, 90,000 doses (the same cast pattern)",
                bind(doses2, params("tenantId", t, "admissionIds", allAdmissions, "from", from, "to", to)), -1, false});
        statements.add(new Object[] {"D2 DOSES_BETWEEN, 12 admissions (a scoped nurse), one day",
                bind(doses2, params("tenantId", t, "admissionIds", tenWards, "from", from, "to", to)), -1, false});
        statements.add(new Object[] {"D2x the same with in (select unnest(...)), for the comparison",
                bind(dosesUnnest, params("tenantId", t, "admissionIds", tenWards, "from", from, "to", to)), -1, false});

        StringBuilder report = new StringBuilder("SAH-34 query plans, run as sahaj_app with app.tenant_id set, sahaj_test,\n"
                + "hospital of " + WARDS + " wards, " + ADMITTED + " admitted admissions, " + events + " clinical events, " + doses
                + " doses, 3,680 grants of which 30\nunreviewed, 1 nurse with 2 live wards. Tables analysed before the run. The statements are "
                + "the repositories' own\n(read by reflection) with real JDBC parameters. 'rows read' = rows returned by the scan nodes plus "
                + "rows removed by a filter.\n");
        StringBuilder summary = new StringBuilder("\n=== work counts ===\n");
        List<String> overBudget = new ArrayList<>(); // asserted after the plan output is written, so the evidence is never lost
        for (Object[] st : statements) {
            String name = (String) st[0];
            Stmt stmt = (Stmt) st[1];
            int budget = (Integer) st[2];
            boolean write = (Boolean) st[3];
            List<String> plain = explain(clinic, false, write, stmt);
            List<String> noSeq = explain(clinic, true, write, stmt);
            report.append("\n=== ").append(name).append(" ===\n").append(stmt.sql()).append("\n--- plan (default planner settings) ---\n")
                    .append(String.join("\n", plain)).append("\n--- plan (set local enable_seqscan = off) ---\n")
                    .append(String.join("\n", noSeq)).append('\n');
            summary.append(String.format("%-120s rows read %8.0f  execution %s%s%n", name, rowsRead(plain), executionMs(plain),
                    budget < 0 ? "  (evidence only)" : "  (budget " + budget + ")"));
            if (budget >= 0) {
                if (rowsRead(plain) > budget) {
                    overBudget.add(name + ": rows read " + rowsRead(plain) + " is over the budget of " + budget);
                }
            }
        }
        report.append(summary);

        // answers, not plans: the statements return what the scope rule says
        List<String> r4 = values(clinic, "select count(*)::text from ipd.admission a join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id "
                + "where a.status = 'ADMITTED' and (b.ward_id in (?::uuid, ?::uuid) or a.id = ?::uuid)", nurseWards.get(0), nurseWards.get(1),
                admissions.get(20).id());
        long expected = Long.parseLong(r4.get(0));

        // the requests: a scoped nurse and the owner against the same hospital (MockMvc, one warm JVM)
        StringBuilder timing = new StringBuilder("\n=== requests (MockMvc, 300 admitted, 26 wards, 36,000 events, 90,000 doses) ===\n");
        long scopedEventsBest = Long.MAX_VALUE;
        for (String[] t2 : new String[][] {{"GET /admissions, scoped nurse", ADMISSIONS, "n"}, {"GET /admissions, owner", ADMISSIONS, "o"},
            {"GET /safety, scoped nurse", SAFETY, "n"}, {"GET /safety, owner", SAFETY, "o"}, {"GET /board, scoped nurse", BOARD, "n"},
            {"GET /clinical-events?limit=50, scoped nurse", IPD + "/clinical-events?limit=50", "n"},
            {"GET /clinical-events?limit=50, scoped nurse with no ward and no grant", IPD + "/clinical-events?limit=50", "l"},
            {"GET /clinical-events?limit=50, owner", IPD + "/clinical-events?limit=50", "o"}}) {
            var auth = "n".equals(t2[2]) ? nurse.auth() : "l".equals(t2[2]) ? lone.auth() : clinic.owner();
            call(HttpMethod.GET, t2[1], auth, null);
            long best = Long.MAX_VALUE;
            int bytes = 0;
            for (int i = 0; i < 5; i++) {
                long started = System.nanoTime();
                var res = call(HttpMethod.GET, t2[1], auth, null).andReturn().getResponse();
                best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
                bytes = res.getContentAsByteArray().length;
                assertThat(res.getStatus()).isEqualTo(200);
            }
            if ("GET /clinical-events?limit=50, scoped nurse".equals(t2[0])) {
                scopedEventsBest = best;
            }
            timing.append(t2[0]).append(": best of 5 ").append(best).append(" ms, ").append(bytes).append(" bytes\n");
        }
        JsonNode nurseList = listOf(nurse.auth());
        timing.append("scoped nurse sees ").append(nurseList.get("items").size()).append(" of ").append(nurseList.get("scope").get("admitted")
                .asInt()).append(" admitted\n");
        report.append(timing);
        Path out = Path.of("target", "sah34-explain.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(overBudget).as("work budgets (rows read)").isEmpty();
        assertThat(nurseList.get("items").size()).isEqualTo((int) expected);
        JsonNode loneEvents = ok(HttpMethod.GET, IPD + "/clinical-events?limit=50", lone.auth(), null, 200);
        assertThat(loneEvents.get("items")).as("R4d: her 10 own entries, nothing else").hasSize(10);
        // the 200 ms of the review (the cast re-run for every row of the log) would break this; the fix runs in about 10 ms
        assertThat(scopedEventsBest).as("GET /clinical-events as a scoped nurse, best of 5, ms").isLessThan(150);
    }
}
