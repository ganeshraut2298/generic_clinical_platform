package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Reproduction of the live-journey blocker of 14 Nov 2026 (docs/test-plans/live-opd-journey-2026-11-14.md): one
 * {@code PUT /api/v1/opd/emr/records/{id}/draft} carrying the sections {@code exam} (EXAM_GENERAL), {@code procedures}
 * (one SERVICES item, BILL_TODAY, the clinic service "Dressing") and {@code followup} (FOLLOW_UP interval D7) never got an
 * answer on a General Medicine visit that had been built through the booking and arrival path.
 *
 * <p>The visit is built the way the live script did: a doctor, a priced service "Dressing", the "General Medicine Live"
 * department added from the gm starter with the doctor assigned and published, a patient, an appointment, arrival with a
 * token, the EMR opened and the four required sections filled. Every save runs on its own thread and is given
 * {@value #TIMEOUT_SECONDS} seconds; on a timeout the test prints what the database and the stuck thread were doing (no
 * patient data: states, wait events, lock types, relation names, parameterised SQL text cut to 300 characters, Java frames)
 * and fails instead of hanging. The same sections are then sent alone and in pairs to name the culprit. Throwaway data
 * only. Test plan: docs/test-plans/live-opd-journey-2026-11-14.md.
 */
@OnTestDatabase
class EmrDraftSaveHangIT extends EmrPhase3Support {

    static final long TIMEOUT_SECONDS = 20;
    private static final String APPTS = "/api/v1/opd/appointments";

    private static final String COMPLAINTS = "{\"complaint\":{\"component\":\"EMR_LIST\",\"kind\":\"COMPLAINTS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000001\",\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true,"
            + "\"attr1\":\"D3\",\"attr2\":\"MODERATE\",\"active\":true}]}}";
    private static final String DIAGNOSIS = "{\"provisional_diagnosis\":{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000002\",\"itemKey\":null,\"text\":\"Viral fever\",\"newToList\":true,"
            + "\"attr1\":\"PROVISIONAL\",\"attr2\":\"ACTIVE\",\"active\":true}]}}";
    private static final String RX = "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":["
            + "{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000003\",\"medicineId\":null,\"name\":\"Paracetamol 500 mg\",\"form\":null,"
            + "\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\",\"source\":\"TYPED\"}]}}";
    private static final String RXFORMAT = "{\"header\":{\"text\":\"Header with registration number\"}}";
    private static final String EXAM = "{\"component\":\"EXAM_GENERAL\",\"schemaVersion\":1,"
            + "\"signs\":{\"pallor\":\"ABSENT\",\"icterus\":\"ABSENT\",\"cyanosis\":\"ABSENT\",\"oedema\":\"ABSENT\"},"
            + "\"systems\":{\"cvs\":{\"state\":\"NORMAL\",\"text\":\"S1 S2 heard, no murmur\"},"
            + "\"rs\":{\"state\":\"NORMAL\",\"text\":\"Air entry equal, clear\"},"
            + "\"pa\":{\"state\":\"NORMAL\",\"text\":\"Soft, non-tender\"}}}";
    private static final String FOLLOW_UP = "{\"component\":\"FOLLOW_UP\",\"schemaVersion\":1,\"interval\":\"D7\"}";

    @Autowired
    private Environment env;

    private record World(Clinic clinic, Rec rec, String dressingId) {
    }

    private record Outcome(boolean timedOut, int status, long millis, String bodyHint) {
    }

    // ---------------- the visit, built the way the live script built it ----------------

    private World world() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String dressing = ok(HttpMethod.POST, SERVICES, clinic.owner(),
                "{\"name\":\"Dressing\",\"category\":\"Nursing\",\"priceRupees\":150}", 201).get("id").asText();
        // the clinic's own gm layout shows Procedures too, so the record has the section whichever department it picks
        showProcedures(clinic);
        JsonNode added = post(DEPARTMENTS, clinic.owner(),
                createBody("General Medicine Live", starterSource("gm"), List.of(doctor.membershipId()), List.of(), true), 201);
        String key = added.get("departmentKey").asText();
        ObjectNode layout = layoutInput(added);
        setShown(layout, "procedures", true, false);
        publish(clinic, key, layout, 200);

        UUID patient = newPatient(clinic);
        String booking = "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId()
                + "\",\"apptDate\":\"" + LocalDate.now() + "\",\"apptTime\":\"06:00\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                + "\"note\":\"Fever\"}";
        String appointmentId = ok(HttpMethod.POST, APPTS, clinic.owner(), booking, 201).get("id").asText();
        JsonNode arrived = ok(HttpMethod.POST, APPTS + "/" + appointmentId + "/token", clinic.owner(), null, 200);
        String visitId = arrived.get("visitId").asText();
        JsonNode visit = null;
        for (JsonNode v : get(VISITS + "/today", clinic.owner())) {
            if (visitId.equals(v.get("id").asText())) {
                visit = v;
            }
        }
        assertThat(visit).as("the visit is on today's queue").isNotNull();
        JsonNode record = openVisit(doctor.token(clinic), visitId, 201).get("record");
        Rec rec = new Rec(clinic, doctor, patient, visit, record);
        rec = save(rec, sectionValues("complaints", COMPLAINTS), sectionValues("diagnosis", DIAGNOSIS),
                sectionValues("rx", RX), sectionValues("rxformat", RXFORMAT));
        System.out.println("HANG-REPRO setup: layout=" + rec.record().get("layout").get("departmentName").asText() + " source="
                + rec.record().get("layout").get("layoutSource").asText() + " stage=" + visit.get("stage").asText()
                + " readyToComplete=" + rec.record().get("readyToComplete").asBoolean() + " has exam/procedures/followup="
                + layoutKeys(rec.record()).containsAll(List.of("exam", "procedures", "followup")));
        assertThat(layoutKeys(rec.record())).contains("exam", "procedures", "followup");
        return new World(clinic, rec, dressing);
    }

    private ObjectNode examSection() throws Exception {
        return oneField("exam", "specialty", objectMapper.readTree(EXAM));
    }

    private ObjectNode proceduresSection(String dressingId) {
        ObjectNode item = keyedItem(dressingId, "Dressing");
        item.put("active", true);
        item.put("attr1", "1");
        item.put("attr2", "BILL_TODAY");
        return oneField("procedures", "procedure", listValue("SERVICES", item));
    }

    private ObjectNode followupSection() throws Exception {
        return oneField("followup", "next_visit", objectMapper.readTree(FOLLOW_UP));
    }

    private ObjectNode[] pick(World w, String... names) throws Exception {
        List<ObjectNode> out = new ArrayList<>();
        for (String n : names) {
            switch (n) {
                case "exam" -> out.add(examSection());
                case "procedures" -> out.add(proceduresSection(w.dressingId()));
                case "followup" -> out.add(followupSection());
                default -> throw new IllegalArgumentException(n);
            }
        }
        return out.toArray(new ObjectNode[0]);
    }

    // ---------------- the guarded request ----------------

    /** Sends the Save draft on its own thread; after the timeout the evidence is printed and the stuck session is ended. */
    private Outcome guardedSave(String label, World w, ObjectNode... sections) throws Exception {
        ObjectNode body = draftBody(w.rec().revision(), "ASSESSMENT", null, sections);
        body.put("saveKind", "MANUAL");
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hang-repro-" + label);
            t.setDaemon(true);
            return t;
        });
        AtomicReference<Thread> worker = new AtomicReference<>();
        long started = System.nanoTime();
        Future<MvcResult> future = pool.submit(() -> {
            worker.set(Thread.currentThread());
            return call(HttpMethod.PUT, RECORDS + "/" + w.rec().id() + "/draft", w.rec().token(), body.toString()).andReturn();
        });
        try {
            MvcResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - started) / 1_000_000;
            String text = result.getResponse().getContentAsString();
            Outcome outcome = new Outcome(false, result.getResponse().getStatus(), ms,
                    result.getResponse().getStatus() == 200 ? "" : text.substring(0, Math.min(300, text.length())));
            System.out.println("HANG-REPRO " + label + ": answered " + outcome.status() + " in " + ms + " ms " + outcome.bodyHint());
            return outcome;
        } catch (TimeoutException timeout) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            System.out.println("HANG-REPRO " + label + ": NO ANSWER after " + ms + " ms -- evidence follows");
            evidence(label, worker.get());
            future.cancel(true);
            endStuckSessions();
            return new Outcome(true, -1, ms, "");
        } finally {
            pool.shutdownNow();
        }
    }

    private void variant(String label, String... names) throws Exception {
        World w = world();
        Outcome o = guardedSave(label, w, pick(w, names));
        assertThat(o.timedOut()).as("Save draft with " + label + " got no answer in " + TIMEOUT_SECONDS + " s").isFalse();
        assertThat(o.status()).as("status of Save draft with " + label + " " + o.bodyHint()).isEqualTo(200);
        // when it answers, the record and the bill hold what was sent
        if (List.of(names).contains("procedures")) {
            JsonNode bill = ok(HttpMethod.GET, BILLING + "/bills/" + w.rec().visitId(), w.clinic().owner(), null, 200);
            long emr = 0;
            for (JsonNode item : bill.get("items")) {
                emr += "EMR".equals(item.get("source").asText()) ? 1 : 0;
            }
            assertThat(emr).as("one EMR bill line for the Dressing").isEqualTo(1);
        }
    }

    // ---------------- the live script's save, then each section alone and in pairs ----------------

    @Test
    void liveScriptSave_exam_procedures_followup_in_one_request() throws Exception {
        variant("exam+procedures+followup", "exam", "procedures", "followup");
    }

    @Test
    void examAlone() throws Exception {
        variant("exam", "exam");
    }

    @Test
    void proceduresAlone() throws Exception {
        variant("procedures", "procedures");
    }

    @Test
    void followupAlone() throws Exception {
        variant("followup", "followup");
    }

    @Test
    void examAndProcedures() throws Exception {
        variant("exam+procedures", "exam", "procedures");
    }

    @Test
    void examAndFollowup() throws Exception {
        variant("exam+followup", "exam", "followup");
    }

    @Test
    void proceduresAndFollowup() throws Exception {
        variant("procedures+followup", "procedures", "followup");
    }

    // ---------------- evidence ----------------

    private void evidence(String label, Thread worker) {
        StringBuilder out = new StringBuilder("\n===== HANG-REPRO evidence for ").append(label).append(" =====\n");
        if (worker != null) {
            out.append("-- stuck request thread (state ").append(worker.getState()).append("), frames of com.sahaj, top 12 frames:\n");
            StackTraceElement[] frames = worker.getStackTrace();
            for (int i = 0; i < Math.min(12, frames.length); i++) {
                out.append("   ").append(frames[i]).append('\n');
            }
            int shown = 0;
            for (StackTraceElement f : frames) {
                if (f.getClassName().startsWith("com.sahaj") && shown++ < 25) {
                    out.append("   [sahaj] ").append(f.getClassName()).append('.').append(f.getMethodName()).append(':')
                            .append(f.getLineNumber()).append('\n');
                }
            }
        }
        DbAccess db = new DbAccess(env);
        out.append("-- as sahaj_migrator (read only):\n");
        try (Connection c = db.migrator()) {
            snapshot(c, out);
        } catch (SQLException e) {
            out.append("   migrator snapshot failed: ").append(e.getSQLState()).append(' ').append(firstLine(e.getMessage())).append('\n');
        }
        out.append("-- as sahaj_app (read only; the same role as the stuck sessions, so it can read their SQL text):\n");
        try (Connection c = db.app()) {
            snapshot(c, out);
        } catch (SQLException e) {
            out.append("   app snapshot failed: ").append(e.getSQLState()).append(' ').append(firstLine(e.getMessage())).append('\n');
        }
        System.out.println(out.append("===== end evidence =====\n"));
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.split("\n")[0];
    }

    private static void snapshot(Connection c, StringBuilder out) throws SQLException {
        c.setReadOnly(true);
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(10);
            out.append("   sessions of this database (state, wait, age of transaction, blocked by, SQL):\n");
            dump(st, out, "select pid, usename, state, wait_event_type, wait_event, "
                    + "coalesce(extract(epoch from now() - xact_start)::int::text, '-') as xact_s, "
                    + "coalesce(extract(epoch from now() - state_change)::int::text, '-') as in_state_s, "
                    + "pg_blocking_pids(pid)::text as blocked_by, "
                    + "left(regexp_replace(coalesce(query, ''), '\\s+', ' ', 'g'), 300) as sql "
                    + "from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid() "
                    + "and backend_type = 'client backend' order by xact_start nulls last");
            out.append("   locks not granted, and advisory or tuple locks held:\n");
            dump(st, out, "select l.pid, l.locktype, l.mode, l.granted, "
                    + "coalesce(c.relnamespace::regnamespace::text || '.' || c.relname, '') as relation, "
                    + "pg_blocking_pids(l.pid)::text as blocked_by "
                    + "from pg_locks l left join pg_class c on c.oid = l.relation "
                    + "where l.pid <> pg_backend_pid() and (not l.granted or l.locktype in ('advisory', 'tuple')) "
                    + "and (l.database is null or l.database = (select oid from pg_database where datname = current_database())) "
                    + "order by l.pid");
            out.append("   granted relation locks per session (count and sample of relations):\n");
            dump(st, out, "select l.pid, count(*) as relation_locks, "
                    + "left(string_agg(distinct c.relnamespace::regnamespace::text || '.' || c.relname, ', ') filter "
                    + "(where c.relname in ('emr_record', 'bill', 'bill_item', 'visit', 'service')), 300) as emr_billing_tables "
                    + "from pg_locks l join pg_class c on c.oid = l.relation "
                    + "where l.pid <> pg_backend_pid() and l.granted and l.locktype = 'relation' "
                    + "and l.pid in (select pid from pg_stat_activity where datname = current_database()) group by l.pid order by l.pid");
        }
    }

    private static void dump(Statement st, StringBuilder out, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int rows = 0;
            while (rs.next()) {
                rows++;
                out.append("     ");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    out.append(md.getColumnLabel(i)).append('=').append(rs.getString(i)).append(i < md.getColumnCount() ? " | " : "");
                }
                out.append('\n');
            }
            if (rows == 0) {
                out.append("     (no rows visible)\n");
            }
        } catch (SQLException e) {
            out.append("     query failed: ").append(e.getSQLState()).append(' ').append(firstLine(e.getMessage())).append('\n');
        }
    }

    /**
     * After the evidence: ends the active or idle-in-transaction sessions of the application role in this test database,
     * so the stuck request gives its connection back and the next variant is not starved. Test database only.
     */
    private void endStuckSessions() {
        try (Connection c = new DbAccess(env).app(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity "
                    + "where datname = current_database() and usename = current_user and pid <> pg_backend_pid() "
                    + "and state in ('active', 'idle in transaction')) t")) {
                rs.next();
                System.out.println("HANG-REPRO ended " + rs.getInt(1) + " stuck session(s) of this test database");
            }
        } catch (SQLException e) {
            System.out.println("HANG-REPRO could not end stuck sessions: " + e.getSQLState() + " " + firstLine(e.getMessage()));
        }
    }
}
