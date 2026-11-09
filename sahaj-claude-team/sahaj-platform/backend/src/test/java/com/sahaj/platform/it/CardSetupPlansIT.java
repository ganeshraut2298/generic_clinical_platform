package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-37 the query plans of docs/database/SAH-37.md section 8, run for real: as {@code sahaj_app} with the clinic bound
 * ({@code app.tenant_id}) on a seeded {@code sahaj_test} clinic of 300 admitted patients with 5 to 20 vitals sets each
 * and two published versions plus one draft. The statements are the ones the repositories send
 * ({@code JdbcAdmissionRepository.FIND_ADMITTED_WITH_PLACEMENT}, {@code JdbcVitalsRepository.FIND_LATEST_READINGS_OF_ADMITTED},
 * {@code JdbcCardSetupRepository.FIND_LATEST}, {@code FIND_HEADS}, {@code FIND_DRAFT}) with the clinic id written in. The
 * output goes to {@code target/sah37-explain.txt} and to the test log, and the plan shape is asserted loosely: an index
 * is used for the new reads, and the readings read is one probe per admitted patient. The same clinic also times the list
 * with every card fact switched on. Test plan: docs/test-plans/SAH-37.md.
 */
@OnTestDatabase
@WithPinPepper
class CardSetupPlansIT extends CardSetupSupport {

    private static final String ADMISSION_COLUMNS = """
            select a.id, a.ip_number, a.patient_id, a.bed_id, b.code as bed_code, b.category_key,
                   w.name as ward_name, a.doctor_membership_id, a.admission_type, a.payer, a.reason, a.status,
                   a.admitted_at, a.discharged_at,
                   coalesce((select sum(d.amount_paise) from ipd.deposit d
                             where d.tenant_id = a.tenant_id and d.admission_id = a.id), 0) as deposit_total""";

    private static String r1(String tenant) {
        return ADMISSION_COLUMNS + ",\n       b.ward_id as ward_id, r.name as room_name\n"
                + "from ipd.admission a\n"
                + "join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id\n"
                + "join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id\n"
                + "left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id\n"
                + "where a.tenant_id = '" + tenant + "'::uuid and a.status = 'ADMITTED'\n"
                + "order by a.admitted_at desc, a.id";
    }

    private static String r4(String tenant) {
        return """
                select v.admission_id, v.ews_score, v.ews_risk, v.recorded_at, v.bp_systolic, v.bp_diastolic, v.pulse,
                       v.spo2, v.temp_c, v.rr, v.recorded_by
                from ipd.admission a
                cross join lateral (
                    select x.admission_id, x.ews_score, x.ews_risk, x.recorded_at, x.bp_systolic, x.bp_diastolic,
                           x.pulse, x.spo2, x.temp_c, x.rr, x.recorded_by
                    from ipd.vitals x
                    where x.tenant_id = a.tenant_id and x.admission_id = a.id
                    order by x.recorded_at desc, x.id desc
                    limit 1) v
                where a.tenant_id = '""" + tenant + "'::uuid and a.status = 'ADMITTED'";
    }

    private static String r7(String tenant) {
        return "select version, cast(setup as text) as setup, is_standard, array_to_string(changed_parts, ',') as changed_parts, "
                + "published_by, published_at\nfrom ipd.card_setup_version\nwhere tenant_id = '" + tenant
                + "'::uuid\norder by version desc\nlimit 1";
    }

    private static String heads(String tenant) {
        return "select version, is_standard, array_to_string(changed_parts, ',') as changed_parts, published_by, published_at\n"
                + "from ipd.card_setup_version\nwhere tenant_id = '" + tenant + "'::uuid\norder by version desc\nlimit 50";
    }

    private static String draft(String tenant) {
        return "select cast(setup as text) as setup, base_version, revision, saved_by, saved_at\nfrom ipd.card_setup_draft\n"
                + "where tenant_id = '" + tenant + "'::uuid";
    }

    private List<String> explain(Clinic clinic, boolean seqscanOff, String sql) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                if (seqscanOff) {
                    st.execute("set local enable_seqscan = off");
                }
                try (ResultSet rs = st.executeQuery("explain (analyze, buffers) " + sql)) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    @Test
    void theQueryPlansOfTheCardReadsOnAClinicOf300AdmittedPatientsAndTheListWithEveryFactOn() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        String tenant = clinic.tenantId().toString();
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set bed_limit = 300");
        for (int i = 0; i < 299; i++) {
            admitNamed(clinic, "Plan Patient " + i + " " + uniqueMobile().substring(6));
        }
        // 5 to 20 vitals sets for each admitted patient, written by the table owner (the app cannot back-date a record)
        int inserted = asOwner(true, new String[] {"ipd.vitals", "ipd.admission"}, c -> {
            try (var ps = c.prepareStatement("insert into ipd.vitals (tenant_id, admission_id, client_key, bp_systolic, "
                    + "bp_diastolic, pulse, spo2, temp_c, rr, ews_score, ews_risk, note_added, recorded_at, recorded_by) "
                    + "select a.tenant_id, a.id, gen_random_uuid(), 120, 80, 80, 98, 37.0, 16, 0, 'LOW', false, "
                    + "now() - (g * interval '1 hour'), ?::uuid from ipd.admission a "
                    + "cross join generate_series(1, 5 + (abs(hashtext(a.id::text)) % 16)) g where a.tenant_id = ?::uuid")) {
                ps.setString(1, clinic.ownerUserId().toString());
                ps.setString(2, tenant);
                return ps.executeUpdate();
            }
        });
        assertThat(inserted).isBetween(300 * 5, 300 * 20);
        // two published versions and one draft, with every fact switched on in the live one
        JsonNode v2 = publishNow(clinic, edit(standard(clinic.owner()), s -> {
            for (String key : new String[] {"diagnosis", "bp", "pulse", "spo2", "temp", "rr", "lastRecorded", "mobile"}) {
                fieldOn(s, key, true);
            }
        }));
        assertThat(v2.get("live").get("version").asInt()).isEqualTo(2);
        JsonNode v3 = publishNow(clinic, edit(v2.get("live").get("setup"), s -> s.put("cardsPerRow", 4)));
        assertThat(v3.get("live").get("version").asInt()).isEqualTo(3);
        saveOk(clinic.owner(), 3, null, edit(v3.get("live").get("setup"), s -> s.put("layout", "compact")));
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            for (String table : new String[] {"ipd.vitals", "ipd.admission", "ipd.bed", "ipd.ward", "ipd.room", "ipd.deposit",
                "ipd.card_setup_version", "ipd.card_setup_draft"}) {
                DbAccess.execute(c, "analyze " + table);
            }
        }

        StringBuilder report = new StringBuilder("SAH-37 query plans, run as sahaj_app with app.tenant_id set, sahaj_test,\n"
                + "clinic of 300 admitted admissions, " + inserted + " vitals rows (5 to 20 per admission), 2 versions, 1 draft.\n");
        String[][] statements = {{"R7 live card setup (latest version)", r7(tenant)}, {"Versions list (limit 50)", heads(tenant)},
            {"Draft read", draft(tenant)}, {"R4 latest readings of the admitted (ADR-013 R4 with the five readings and recorded_by)", r4(tenant)},
            {"R1 admitted rows with placement (with a.reason)", r1(tenant)}};
        List<List<String>> plain = new ArrayList<>();
        List<List<String>> noSeq = new ArrayList<>();
        for (String[] st : statements) {
            List<String> a = explain(clinic, false, st[1]);
            List<String> b = explain(clinic, true, st[1]);
            plain.add(a);
            noSeq.add(b);
            report.append("\n=== ").append(st[0]).append(" ===\n").append(st[1]).append("\n--- plan (default planner settings) ---\n")
                    .append(String.join("\n", a)).append("\n--- plan (set local enable_seqscan = off) ---\n")
                    .append(String.join("\n", b)).append('\n');
        }

        // the list: every fact on, 300 rows, one request
        long started = System.nanoTime();
        var res = call(HttpMethod.GET, ADMISSIONS, clinic.owner(), null).andReturn().getResponse();
        long millis = (System.nanoTime() - started) / 1_000_000;
        JsonNode list = objectMapper.readTree(res.getContentAsString());
        int bytes = res.getContentAsByteArray().length;
        started = System.nanoTime();
        var base = call(HttpMethod.GET, ADMISSIONS + "?cardFacts=setup", w.nurse().auth(), null).andReturn().getResponse();
        long nurseMillis = (System.nanoTime() - started) / 1_000_000;
        report.append("\n=== GET /admissions with every fact on (v3 live), owner ===\n").append("rows ").append(list.get("items").size())
                .append(", ").append(bytes).append(" bytes raw, ").append(millis).append(" ms (MockMvc, warm JVM not guaranteed)\n")
                .append("nurse (no mobile): ").append(base.getContentAsByteArray().length).append(" bytes, ").append(nurseMillis)
                .append(" ms\n");
        Path out = Path.of("target", "sah37-explain.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // shape, loosely: the new reads use their indexes (the tables are tiny, so the plain plan may be a scan)
        String r7NoSeq = String.join("\n", noSeq.get(0));
        assertThat(r7NoSeq).contains("uq_card_setup_version_tenant_version");
        assertThat(r7NoSeq).contains("Index Scan Backward");
        assertThat(String.join("\n", noSeq.get(1))).contains("uq_card_setup_version_tenant_version");
        assertThat(String.join("\n", noSeq.get(2))).contains("card_setup_draft_pkey");
        String readings = String.join("\n", plain.get(3));
        assertThat(readings).as("one index probe per admitted patient, no scan of the vitals table").doesNotContain("Seq Scan on vitals");
        assertThat(readings).contains("ix_vitals_tenant_admission_recorded");
        assertThat(String.join("\n", plain.get(4))).doesNotContain("Seq Scan on admission");
        // the answer: all 300 rows carry the new facts for the owner, and the cards of a nurse carry no mobile
        assertThat(list.get("items")).hasSize(300);
        for (JsonNode row : list.get("items")) {
            assertThat(row.get("clinical").get("latestVitals").get("bp").get("systolic").asInt()).isEqualTo(120);
            assertThat(row.get("clinical").get("diagnosis").asText()).isEqualTo("Fever");
            assertThat(row.has("mobile")).isTrue();
        }
        assertThat(base.getContentAsString()).doesNotContain("\"mobile\":\"");
        assertThat(millis).as("one answer for 300 patients with every fact (ms)").isLessThan(8000);
    }
}
