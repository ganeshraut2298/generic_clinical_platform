package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-28 things that happen at the same moment, on the real {@code sahaj_test} database: simultaneous red arrivals
 * (never the same bay, exactly one gets the last bay), the same client key sent several times, two taps on Doctor has
 * seen, release against refer, release against admit, two admits of one case, re-triage against re-triage, and a scale
 * change against registrations. One side wins and the other gets the answer the contract names; no answer is a 500;
 * nothing is doubled and the tables agree with each other. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyConcurrencyIT extends EmergencySupport {

    private record Resp(int status, String body) {
    }

    private record Req(HttpMethod method, String url, RequestPostProcessor auth, String body) {
    }

    private Callable<Resp> task(CountDownLatch start, Req r) {
        return () -> {
            start.await();
            var builder = request(r.method(), URI.create(r.url())).with(r.auth());
            if (r.body() != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(r.body());
            }
            var res = mvc.perform(builder).andReturn().getResponse();
            return new Resp(res.getStatus(), res.getContentAsString());
        };
    }

    /** Starts all requests at the same moment; no answer may be a server error. */
    private List<Resp> parallel(Req... requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Req r : requests) {
                futures.add(pool.submit(task(start, r)));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                Resp resp = f.get(60, TimeUnit.SECONDS);
                assertThat(resp.status()).as("no answer is a server error: " + resp.body()).isLessThan(500);
                out.add(resp);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode body(Resp r) throws Exception {
        return objectMapper.readTree(r.body());
    }

    private Req register(Clinic clinic, RequestPostProcessor auth, Map<String, Object> body) throws Exception {
        return new Req(HttpMethod.POST, ER_CASES, auth, js(body));
    }

    private Req post(String caseId, String action, RequestPostProcessor auth) {
        return new Req(HttpMethod.POST, caseUrl(caseId, action), auth, null);
    }

    private long duplicateOpenBays(Clinic clinic) throws Exception {
        return count(clinic, "select count(bay) - count(distinct bay) from ipd.emergency_case where status in ('WAITING','SEEN')");
    }

    /**
     * Cases whose level differs from the level of their latest event, "latest" by the stored {@code recorded_at} (the
     * database clock read when the event is written, after the clinic lock; fix pass for QA finding F3 and code
     * review 2). Before the fix this had to compare the writing transaction ({@code xmin}) because {@code recorded_at}
     * was the transaction start and could be out of order.
     */
    private long casesDisagreeingWithTheirLastEvent(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from ipd.emergency_case c where c.level <> (select e.level from "
                + "ipd.emergency_triage_event e where e.case_id = c.id order by e.recorded_at desc, e.id desc limit 1)");
    }

    /**
     * Events that do not continue the history when read in {@code recorded_at} order: a REGISTERED event must come
     * first, and every other event must start from the level the previous event ended at. Two racing re-triages that
     * were stored in the order opposite to their {@code recorded_at} would break this chain.
     */
    private long eventsBreakingTheChain(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from (select e.reason, e.from_level, lag(e.level) over (partition by e.case_id "
                + "order by e.recorded_at, e.id) as previous, lag(e.recorded_at) over (partition by e.case_id "
                + "order by e.recorded_at, e.id) as previous_at, e.recorded_at from ipd.emergency_triage_event e) x "
                + "where (x.reason = 'REGISTERED' and x.previous is not null) "
                + "or (x.reason <> 'REGISTERED' and x.from_level is distinct from x.previous) "
                + "or x.previous_at = x.recorded_at");
    }

    // ---------------- bays ----------------

    @Test
    void eightSimultaneousRedArrivalsGetFiveDifferentBaysAndThreeGetNone() throws Exception {
        Clinic clinic = hospital();
        putSetup(clinic, 3, T3, T5, 5, true);
        Req[] reqs = new Req[8];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = register(clinic, clinic.owner(), caseBody("Red " + i, 1));
        }
        List<Resp> answers = parallel(reqs);
        TreeSet<String> bays = new TreeSet<>();
        int none = 0;
        for (Resp r : answers) {
            assertThat(r.status()).isEqualTo(201);
            JsonNode bay = body(r).get("actedCase").get("bay");
            if (bay.isNull()) {
                none++;
            } else {
                assertThat(bays.add(bay.asText())).as("bay " + bay.asText() + " was given twice").isTrue();
            }
        }
        assertThat(bays).containsExactly("ER-1", "ER-2", "ER-3", "ER-4", "ER-5");
        assertThat(none).isEqualTo(3);
        assertThat(caseRows(clinic)).isEqualTo(8);
        assertThat(eventRows(clinic)).isEqualTo(8);
        assertThat(duplicateOpenBays(clinic)).isZero();
        assertThat(queue(clinic).get("baysFree").asInt()).isZero();
        assertThat(audits(clinic, "IPD_ER_REGISTERED")).isEqualTo(8);
    }

    @Test
    void whenOneBayIsLeftExactlyOneOfTwoSimultaneousRedArrivalsGetsIt() throws Exception {
        for (int round = 1; round <= 3; round++) {
            Clinic clinic = hospital();
            setBays(clinic, 1);
            List<Resp> answers = parallel(register(clinic, clinic.owner(), caseBody("Red A", 1)),
                    register(clinic, clinic.as("NURSE"), caseBody("Red B", 1)));
            List<String> bays = new ArrayList<>();
            for (Resp r : answers) {
                assertThat(r.status()).isEqualTo(201);
                bays.add(body(r).get("actedCase").get("bay").asText("none"));
            }
            assertThat(bays).as("round " + round).containsExactlyInAnyOrder("ER-1", "none");
            assertThat(duplicateOpenBays(clinic)).isZero();
            assertThat(caseRows(clinic)).isEqualTo(2);
        }
    }

    @Test
    void twoRedArrivalsWithTwoFreeBaysGetER1AndER2() throws Exception {
        for (int round = 1; round <= 3; round++) {
            Clinic clinic = hospital();                          // 2 bays
            List<Resp> answers = parallel(register(clinic, clinic.owner(), caseBody("Red A", 1)),
                    register(clinic, clinic.as("FRONT_DESK"), caseBody("Red B", 1)));
            List<String> bays = new ArrayList<>();
            answers.forEach(r -> {
                try {
                    bays.add(body(r).get("actedCase").get("bay").asText());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertThat(bays).as("round " + round).containsExactlyInAnyOrder("ER-1", "ER-2");
        }
    }

    // ---------------- the client key ----------------

    @Test
    void theSameClientKeySentFourTimesAtOnceMakesOneCaseOneEventOneAuditRow() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> body = caseBody("Asha Patil", 1);
        List<Resp> answers = parallel(register(clinic, clinic.owner(), body), register(clinic, clinic.owner(), body),
                register(clinic, clinic.as("NURSE"), body), register(clinic, clinic.as("FRONT_DESK"), body));
        List<Integer> statuses = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>();
        for (Resp r : answers) {
            statuses.add(r.status());
            ids.add(body(r).get("actedCase").get("id").asText());
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200, 200);
        assertThat(ids).hasSize(1);
        assertThat(caseRows(clinic)).isOne();
        assertThat(eventRows(clinic)).isOne();
        assertThat(audits(clinic, "IPD_ER_REGISTERED")).isOne();
        assertThat(queue(clinic).get("baysFree").asInt()).as("one bay taken, not four").isOne();
    }

    // ---------------- seen, release, refer ----------------

    @Test
    void fourTapsOnDoctorHasSeenMakeOneSeenRowAndOneAuditRow() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        List<Resp> answers = parallel(post(id, "seen", clinic.owner()), post(id, "seen", clinic.as("NURSE")),
                post(id, "seen", clinic.as("DOCTOR")), post(id, "seen", clinic.owner()));
        for (Resp r : answers) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(body(r).get("actedCase").get("status").asText()).isEqualTo("SEEN");
        }
        assertThat(audits(clinic, "IPD_ER_SEEN")).isOne();
        assertThat(col(clinic, id, "version")).isEqualTo("1");
    }

    @Test
    void releaseAgainstReferGivesOne200AndOne409CaseClosedAndOneClosingRow() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 3; round++) {
            String id = reg(clinic, "Case " + round, 1).get("id").asText();
            long closings = audits(clinic, "IPD_ER_RELEASED") + audits(clinic, "IPD_ER_REFERRED");
            List<Resp> answers = parallel(post(id, "release", clinic.owner()), post(id, "refer", clinic.as("DOCTOR")));
            assertThat(List.of(answers.get(0).status(), answers.get(1).status())).as("round " + round).containsExactlyInAnyOrder(200, 409);
            Resp loser = answers.get(0).status() == 409 ? answers.get(0) : answers.get(1);
            assertThat(body(loser).get("type").asText()).endsWith("case-closed");
            assertThat(body(loser).get("detail").asText()).isEqualTo(CLOSED);
            assertThat(audits(clinic, "IPD_ER_RELEASED") + audits(clinic, "IPD_ER_REFERRED")).isEqualTo(closings + 1);
            String winner = answers.get(0).status() == 200 ? body(answers.get(0)).get("actedCase").get("status").asText()
                    : body(answers.get(1)).get("actedCase").get("status").asText();
            assertThat(col(clinic, id, "status")).isEqualTo(winner);
            assertThat(col(clinic, id, "version")).isEqualTo("1");
        }
        assertThat(duplicateOpenBays(clinic)).isZero();
        assertThat(queue(clinic).get("baysFree").asInt()).isEqualTo(2);
    }

    // ---------------- admit ----------------

    @Test
    void releaseAgainstAdmitLeavesOneConsistentOutcomeAndNeverAnAdmittedPatientWhoWasReleased() throws Exception {
        Clinic clinic = hospital();
        int admitted = 0;
        int released = 0;
        for (int round = 1; round <= 5; round++) {
            String id = reg(clinic, "Case " + round, 1).get("id").asText();
            UUID bed = newBed(clinic, "gen");
            UUID patient = newPatient(clinic);
            long admissions = rows(clinic, "admission");
            List<Resp> answers = parallel(post(id, "release", clinic.owner()),
                    new Req(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, patient, bed, id))));
            Resp release = answers.get(0);
            Resp admit = answers.get(1);
            if (release.status() == 200) {
                released++;
                assertThat(admit.status()).as("round " + round).isEqualTo(409);
                assertThat(body(admit).get("type").asText()).endsWith("case-closed");
                assertThat(col(clinic, id, "status")).isEqualTo("RELEASED");
                assertThat(rows(clinic, "admission")).isEqualTo(admissions);
                assertThat(bedStatus(clinic, bed)).isEqualTo("free");
                assertThat(col(clinic, id, "admission_id")).isNull();
            } else {
                admitted++;
                assertThat(release.status()).as("round " + round).isEqualTo(409);
                assertThat(body(release).get("type").asText()).endsWith("case-closed");
                assertThat(admit.status()).isEqualTo(201);
                assertThat(col(clinic, id, "status")).isEqualTo("ADMITTED");
                assertThat(rows(clinic, "admission")).isEqualTo(admissions + 1);
                assertThat(bedStatus(clinic, bed)).isEqualTo("occ");
                assertThat(col(clinic, id, "admission_id")).isEqualTo(body(admit).get("id").asText());
            }
        }
        assertThat(admitted + released).isEqualTo(5);
        assertThat(duplicateOpenBays(clinic)).isZero();
        assertThat(audits(clinic, "IPD_ER_ADMITTED")).isEqualTo(admitted);
        assertThat(audits(clinic, "IPD_ER_RELEASED")).isEqualTo(released);
    }

    @Test
    void twoAdmitsOfOneCaseGiveOneAdmissionAndOne409CaseClosedAndOnlyOneBedIsTaken() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 3; round++) {
            String id = reg(clinic, "Case " + round, 2).get("id").asText();
            UUID bedA = newBed(clinic, "gen");
            UUID bedB = newBed(clinic, "gen");
            UUID patientA = newPatient(clinic);
            UUID patientB = newPatient(clinic);
            long admissions = rows(clinic, "admission");
            List<Resp> answers = parallel(
                    new Req(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, patientA, bedA, id))),
                    new Req(HttpMethod.POST, ADMISSIONS, clinic.as("NURSE"), js(admitBody(clinic, patientB, bedB, id))));
            assertThat(List.of(answers.get(0).status(), answers.get(1).status())).as("round " + round).containsExactlyInAnyOrder(201, 409);
            Resp loser = answers.get(0).status() == 409 ? answers.get(0) : answers.get(1);
            assertThat(body(loser).get("type").asText()).endsWith("case-closed");
            assertThat(rows(clinic, "admission")).isEqualTo(admissions + 1);
            assertThat(List.of(bedStatus(clinic, bedA), bedStatus(clinic, bedB))).containsExactlyInAnyOrder("occ", "free");
            assertThat(count(clinic, "select count(*) from ipd.emergency_case where admission_id is not null and id = ?::uuid", id)).isOne();
            assertThat(audits(clinic, "IPD_ER_ADMITTED")).isEqualTo(round);
        }
    }

    // ---------------- re-triage and scale ----------------

    @Test
    void simultaneousRetriagesEndAtTheLevelOfTheirLastEventAndEveryChangeIsLogged() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 3; round++) {
            String id = reg(clinic, "Case " + round, 2).get("id").asText();
            List<Resp> answers = parallel(
                    new Req(HttpMethod.PUT, caseUrl(id, "level"), clinic.owner(), "{\"level\":1}"),
                    new Req(HttpMethod.PUT, caseUrl(id, "level"), clinic.as("NURSE"), "{\"level\":3}"),
                    new Req(HttpMethod.PUT, caseUrl(id, "level"), clinic.as("DOCTOR"), "{\"level\":1}"),
                    post(id, "seen", clinic.as("DOCTOR")));
            answers.forEach(r -> assertThat(r.status()).isEqualTo(200));
            List<String> events = events(clinic, id);
            assertThat(events.get(0)).isEqualTo("REGISTERED ->2 s3");
            assertThat(events.size() - 1).as("one RETRIAGED event per real change").isBetween(2, 3);
            assertThat(casesDisagreeingWithTheirLastEvent(clinic)).as("round " + round).isZero();
            assertThat(eventsBreakingTheChain(clinic)).as("round " + round + ": recorded_at order is the write order").isZero();
            assertThat(col(clinic, id, "status")).isEqualTo("SEEN");
        }
        assertThat(duplicateOpenBays(clinic)).isZero();
    }

    /**
     * Code review 1 (fix: the reads use one REPEATABLE READ snapshot): while the owner flips the scale between 3 colours
     * and 5 levels (every flip remaps the open cases and saves the setup in one transaction), readers of the queue, the
     * counts, the tile numbers and the Admit hand-off never get a 500, and every card they see is inside the scale
     * of the same answer.
     */
    @Test
    void readsDuringScaleFlipsNeverFailAndEveryCardIsInsideTheScaleOfItsOwnAnswer() throws Exception {
        Clinic clinic = hospital();
        setScale(clinic, 5);
        String hand = null;
        for (int level = 1; level <= 5; level++) {
            String id = reg(clinic, "Level " + level, level).get("id").asText();
            hand = level == 5 ? id : hand;
        }
        reg(clinic, "Level 4 again", 4);
        reg(clinic, "Level 5 again", 5);
        final String handOffCase = hand;
        java.util.concurrent.atomic.AtomicBoolean flipping = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<List<String>>> readers = new ArrayList<>();
            String[] urls = {ER, ER_SUMMARY, ADMISSIONS, OPTIONS + "?emergencyCaseId=" + handOffCase};
            for (String url : urls) {
                readers.add(pool.submit(() -> {
                    List<String> failures = new ArrayList<>();
                    while (flipping.get() || reads.get() < 20) {
                        var res = mvc.perform(request(HttpMethod.GET, URI.create(url)).with(clinic.owner())).andReturn().getResponse();
                        reads.incrementAndGet();
                        if (res.getStatus() != 200) {
                            failures.add(url + " -> " + res.getStatus());
                        } else if (url.equals(ER)) {
                            JsonNode q = objectMapper.readTree(res.getContentAsString());
                            int scale = q.get("scale").asInt();
                            if (q.get("levels").size() != scale) {
                                failures.add("levels " + q.get("levels").size() + " for scale " + scale);
                            }
                            for (JsonNode c : q.get("cases")) {
                                if (c.get("level").asInt() > scale) {
                                    failures.add("level " + c.get("level").asInt() + " in scale " + scale);
                                }
                            }
                        }
                    }
                    return failures;
                }));
            }
            Future<Integer> flipper = pool.submit(() -> {
                for (int i = 0; i < 24; i++) {
                    int scale = i % 2 == 0 ? 3 : 5;
                    var res = mvc.perform(request(HttpMethod.PUT, URI.create(ER_SETTINGS)).with(clinic.owner())
                            .contentType(MediaType.APPLICATION_JSON).content(settingsJson(scale, T3, T5, 2, true))).andReturn().getResponse();
                    assertThat(res.getStatus()).as("flip " + i).isEqualTo(200);
                }
                return 24;
            });
            assertThat(flipper.get(120, TimeUnit.SECONDS)).isEqualTo(24);
            flipping.set(false);
            for (Future<List<String>> reader : readers) {
                assertThat(reader.get(60, TimeUnit.SECONDS)).as("no failed or out-of-scale read").isEmpty();
            }
        } finally {
            flipping.set(false);
            pool.shutdownNow();
        }
        assertThat(reads.get()).isGreaterThanOrEqualTo(80);
        assertThat(eventsBreakingTheChain(clinic)).isZero();
    }

    @Test
    void aScaleChangeAtTheSameMomentAsRegistrationsLeavesEveryCaseWithAValidLevelAndAMatchingLastEvent() throws Exception {
        Clinic clinic = hospital();
        reg(clinic, "Early red", 1);
        reg(clinic, "Early yellow", 2);
        reg(clinic, "Early green", 3);
        List<Resp> answers = parallel(
                new Req(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), settingsJson(5, T3, T5, 2, true)),
                register(clinic, clinic.owner(), caseBody("Race one", 1)), register(clinic, clinic.as("NURSE"), caseBody("Race two", 2)),
                register(clinic, clinic.as("FRONT_DESK"), caseBody("Race three", 3)));
        answers.forEach(r -> assertThat(r.status()).isIn(200, 201));
        assertThat(settings(clinic).get("scale").asInt()).isEqualTo(5);
        assertThat(caseRows(clinic)).isEqualTo(6);
        assertThat(count(clinic, "select count(*) from ipd.emergency_case where level > 5 or level < 1")).isZero();
        assertThat(casesDisagreeingWithTheirLastEvent(clinic)).isZero();
        assertThat(eventsBreakingTheChain(clinic)).as("recorded_at order is the write order").isZero();
        assertThat(duplicateOpenBays(clinic)).isZero();
        // the three early cases were mapped; a race case was either mapped or registered after the switch
        assertThat(count(clinic, "select count(*) from ipd.emergency_triage_event where reason = 'SCALE_CHANGED'")).isBetween(3L, 6L);
        // the audit row counts exactly the cases the settings call mapped
        JsonNode after = objectMapper.readTree(audit(clinic, "IPD_ER_SETTINGS_UPDATED").get(0)[3]);
        assertThat((long) after.get("remappedCases").asInt())
                .isEqualTo(count(clinic, "select count(*) from ipd.emergency_triage_event where reason = 'SCALE_CHANGED'"));
        // the queue is still ordered by level, then by arrival
        JsonNode q = queue(clinic);
        int last = 0;
        for (JsonNode c : q.get("cases")) {
            assertThat(c.get("level").asInt()).isGreaterThanOrEqualTo(last);
            last = c.get("level").asInt();
        }
    }
}
