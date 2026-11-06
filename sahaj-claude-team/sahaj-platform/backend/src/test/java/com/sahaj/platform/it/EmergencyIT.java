package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-28 the emergency queue on the real {@code sahaj_test} database: quick registration (stored values, unknown
 * patients, bays, the client key), the order of the queue, waiting minutes and "over target" with arrivals moved into
 * the past, Doctor has seen, re-triage, Treat and release and Refer out. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyIT extends EmergencySupport {

    // ---------------- registration ----------------

    @Test
    void aKnownPatientIsStoredWaitingAtTheChosenLevelAndAnsweredWith201AndTheQueue() throws Exception {
        Clinic clinic = hospital();
        long patientsBefore = count(clinic, "select count(*) from patient.patient");
        Map<String, Object> body = caseBody("Asha Patil", 2);
        body.put("mobile", "9876543210");
        body.put("complaint", "Chest pain for 1 hour");
        JsonNode answer = register(clinic, body);

        JsonNode acted = answer.get("actedCase");
        assertThat(acted.get("displayName").asText()).isEqualTo("Asha Patil");
        assertThat(acted.get("age").asInt()).isEqualTo(40);
        assertThat(acted.get("sex").asText()).isEqualTo("F");
        assertThat(acted.get("complaint").asText()).isEqualTo("Chest pain for 1 hour");
        assertThat(acted.get("identityPending").asBoolean()).isFalse();
        assertThat(acted.get("level").asInt()).isEqualTo(2);
        assertThat(acted.get("levelName").asText()).isEqualTo("Yellow");
        assertThat(acted.get("bay").isNull()).isTrue();
        assertThat(acted.get("status").asText()).isEqualTo("WAITING");
        assertThat(acted.get("waitingMinutes").asInt()).isZero();
        assertThat(acted.get("targetMinutes").asInt()).isEqualTo(30);
        assertThat(acted.get("overTarget").asBoolean()).isFalse();
        assertThat(acted.get("seenAt").isNull()).isTrue();
        assertThat(acted.has("mobile")).as("the card does not carry the mobile").isFalse();

        assertThat(answer.get("state").asText()).isEqualTo("trial");
        assertThat(answer.get("scale").asInt()).isEqualTo(3);
        assertThat(answer.get("defaultLevel").asInt()).isEqualTo(2);
        assertThat(answer.get("unknownPatientRegistration").asBoolean()).isTrue();
        assertThat(answer.get("baysTotal").asInt()).isEqualTo(2);
        assertThat(answer.get("baysFree").asInt()).isEqualTo(2);
        assertThat(answer.get("counts").get("writable").asBoolean()).isTrue();
        assertThat(answer.get("counts").get("queue").asInt()).isOne();
        assertThat(answer.get("counts").get("overTarget").asInt()).isZero();
        for (String flag : new String[] {"canRegister", "canRetriage", "canAct", "canAdmit"}) {
            assertThat(answer.get(flag).asBoolean()).as(flag + " for the owner").isTrue();
        }
        assertThat(ids(answer)).containsExactly(acted.get("id").asText());
        assertThat(answer.get("asOf").asText()).isNotBlank();

        // the three colours with their names, descriptions, tones and default targets
        JsonNode levels = answer.get("levels");
        assertThat(levels).hasSize(3);
        String[][] want = {{"Red", "Immediate", "crit", "5"}, {"Yellow", "Urgent", "warn", "30"}, {"Green", "Can wait", "good", "120"}};
        for (int i = 0; i < 3; i++) {
            assertThat(levels.get(i).get("level").asInt()).isEqualTo(i + 1);
            assertThat(levels.get(i).get("name").asText()).isEqualTo(want[i][0]);
            assertThat(levels.get(i).get("description").asText()).isEqualTo(want[i][1]);
            assertThat(levels.get(i).get("tone").asText()).isEqualTo(want[i][2]);
            assertThat(levels.get(i).get("targetMinutes").asInt()).isEqualTo(Integer.parseInt(want[i][3]));
        }

        // what was stored: no patient record is made, the mobile and the typed values are kept on the case
        String id = acted.get("id").asText();
        assertThat(col(clinic, id, "patient_id")).isNull();
        assertThat(col(clinic, id, "mobile")).isEqualTo("9876543210");
        assertThat(col(clinic, id, "registered_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(col(clinic, id, "status")).isEqualTo("WAITING");
        assertThat(col(clinic, id, "version")).isEqualTo("0");
        assertThat(col(clinic, id, "identity_pending")).isEqualTo("false");
        assertThat(count(clinic, "select count(*) from patient.patient")).isEqualTo(patientsBefore);
        assertThat(count(clinic, "select count(*) from ipd.emergency_case where arrived_at > now() - interval '1 minute'"))
                .as("the server clock is the arrival time").isOne();
        assertThat(events(clinic, id)).containsExactly("REGISTERED ->2 s3");
        assertThat(values(clinic, "select recorded_by::text from ipd.emergency_triage_event where case_id = ?::uuid", id))
                .containsExactly(clinic.ownerUserId().toString());
        List<String[]> audit = audit(clinic, "IPD_ER_REGISTERED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_EMERGENCY_CASE");
        assertThat(audit.get(0)[1]).isEqualTo(id);
        assertThat(audit.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
    }

    @Test
    void anUnknownPatientIsNamedFromTheSexAndTheAgeWithAQuestionMarkWhenTheAgeIsEmpty() throws Exception {
        Clinic clinic = hospital();
        Object[][] cases = {{"M", 40, "Unknown male, about 40"}, {"F", 30, "Unknown female, about 30"},
                {"O", 5, "Unknown person, about 5"}, {"M", null, "Unknown male, about ?"}, {"F", null, "Unknown female, about ?"},
                {"O", null, "Unknown person, about ?"}, {"M", 0, "Unknown male, about 0"}, {"F", 120, "Unknown female, about 120"}};
        for (Object[] c : cases) {
            Map<String, Object> body = unknownBody((String) c[0], (Integer) c[1], 3);
            body.put("name", "Typed Name Is Ignored");
            JsonNode acted = register(clinic, body).get("actedCase");
            assertThat(acted.get("displayName").asText()).isEqualTo(c[2]);
            assertThat(acted.get("identityPending").asBoolean()).isTrue();
            String id = acted.get("id").asText();
            assertThat(col(clinic, id, "display_name")).isEqualTo(c[2]);
            assertThat(col(clinic, id, "identity_pending")).isEqualTo("true");
        }
        assertThat(count(clinic, "select count(*) from ipd.emergency_case where display_name like '%Ignored%'")).isZero();
    }

    @Test
    void theNameIsTrimmedAnEmptyMobileIsStoredAsNothingAndTheAgeMayBeEmpty() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> body = caseBody("   Ravi Joshi   ", 3);
        body.put("mobile", "   ");
        body.put("age", null);
        body.put("complaint", "  Fever  ");
        JsonNode acted = register(clinic, body).get("actedCase");
        String id = acted.get("id").asText();
        assertThat(acted.get("displayName").asText()).isEqualTo("Ravi Joshi");
        assertThat(acted.get("complaint").asText()).isEqualTo("Fever");
        assertThat(acted.get("age").isNull()).isTrue();
        assertThat(col(clinic, id, "mobile")).isNull();
        assertThat(col(clinic, id, "age")).isNull();
        Map<String, Object> second = caseBody("Sita Rane", 3);
        second.put("mobile", "");
        String sid = register(clinic, second).get("actedCase").get("id").asText();
        assertThat(col(clinic, sid, "mobile")).isNull();
    }

    @Test
    void theDefaultLevelIsTheSecondLevelOfEitherScaleAndFiveLevelsHaveTheirOwnNamesAndTargets() throws Exception {
        Clinic clinic = hospital();
        assertThat(queue(clinic).get("defaultLevel").asInt()).isEqualTo(2);
        JsonNode five = setScale(clinic, 5);
        assertThat(five.get("scale").asInt()).isEqualTo(5);
        JsonNode q = queue(clinic);
        assertThat(q.get("scale").asInt()).isEqualTo(5);
        assertThat(q.get("defaultLevel").asInt()).isEqualTo(2);
        String[][] want = {{"Level 1", "Resuscitation", "crit", "0"}, {"Level 2", "Emergent", "crit", "10"},
                {"Level 3", "Urgent", "warn", "30"}, {"Level 4", "Less urgent", "good", "60"}, {"Level 5", "Non-urgent", "good", "120"}};
        assertThat(q.get("levels")).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(q.get("levels").get(i).get("name").asText()).isEqualTo(want[i][0]);
            assertThat(q.get("levels").get(i).get("description").asText()).isEqualTo(want[i][1]);
            assertThat(q.get("levels").get(i).get("tone").asText()).isEqualTo(want[i][2]);
            assertThat(q.get("levels").get(i).get("targetMinutes").asInt()).isEqualTo(Integer.parseInt(want[i][3]));
        }
        JsonNode level5 = reg(clinic, "Mina Shah", 5);
        assertThat(level5.get("levelName").asText()).isEqualTo("Level 5");
        assertThat(level5.get("targetMinutes").asInt()).isEqualTo(120);
    }

    // ---------------- bays ----------------

    @Test
    void onlyTheMostUrgentLevelGetsTheLowestFreeBayAndNoneWhenNoBayIsFree() throws Exception {
        Clinic clinic = hospital();                       // 2 bays
        assertThat(reg(clinic, "Yellow one", 2).get("bay").isNull()).isTrue();
        assertThat(reg(clinic, "Green one", 3).get("bay").isNull()).isTrue();
        JsonNode red1 = reg(clinic, "Red one", 1);
        assertThat(red1.get("bay").asText()).isEqualTo("ER-1");
        JsonNode red2 = register(clinic, caseBody("Red two", 1));
        assertThat(red2.get("actedCase").get("bay").asText()).isEqualTo("ER-2");
        assertThat(red2.get("baysFree").asInt()).isZero();
        JsonNode red3 = register(clinic, caseBody("Red three", 1));
        assertThat(red3.get("actedCase").get("bay").isNull()).as("no bay is free").isTrue();
        assertThat(red3.get("actedCase").get("status").asText()).isEqualTo("WAITING");
        assertThat(red3.get("baysFree").asInt()).isZero();
        assertThat(red3.get("baysTotal").asInt()).isEqualTo(2);

        // a closed case frees its lowest bay; the next most urgent arrival takes it, and ER-2 is still held
        release(clinic, red1.get("id").asText());
        assertThat(queue(clinic).get("baysFree").asInt()).isOne();
        JsonNode red4 = reg(clinic, "Red four", 1);
        assertThat(red4.get("bay").asText()).isEqualTo("ER-1");
        // free ER-2 only: the lowest free bay is then ER-2
        refer(clinic, red2.get("actedCase").get("id").asText());
        assertThat(reg(clinic, "Red five", 1).get("bay").asText()).isEqualTo("ER-2");
        // the bay stays on the closed row as history
        assertThat(col(clinic, red1.get("id").asText(), "bay")).isEqualTo("ER-1");
        assertThat(col(clinic, red1.get("id").asText(), "status")).isEqualTo("RELEASED");
    }

    @Test
    void noBayIsGivenWhenTheClinicHasZeroBaysAndInTheFiveLevelScaleOnlyLevelOneGetsOne() throws Exception {
        Clinic none = hospital();
        setBays(none, 0);
        JsonNode answer = register(none, caseBody("No bay", 1));
        assertThat(answer.get("actedCase").get("bay").isNull()).isTrue();
        assertThat(answer.get("baysTotal").asInt()).isZero();
        assertThat(answer.get("baysFree").asInt()).isZero();

        Clinic five = hospital();
        setScale(five, 5);
        assertThat(reg(five, "Level two", 2).get("bay").isNull()).isTrue();
        assertThat(reg(five, "Level three", 3).get("bay").isNull()).isTrue();
        assertThat(reg(five, "Level one", 1).get("bay").asText()).isEqualTo("ER-1");
        assertThat(queue(five).get("baysFree").asInt()).isOne();
    }

    // ---------------- the client key ----------------

    @Test
    void aRepeatOfTheClientKeyAnswers200WithTheFirstCaseAndWritesNothingWhateverTheBodySays() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> body = caseBody("Asha Patil", 1);
        JsonNode first = register(clinic, body);
        String id = first.get("actedCase").get("id").asText();
        Written before = written(clinic);
        assertThat(before.cases()).isOne();
        assertThat(before.events()).isOne();

        JsonNode again = ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(body), 200);
        assertThat(again.get("actedCase").get("id").asText()).isEqualTo(id);
        assertThat(again.get("actedCase").get("bay").asText()).isEqualTo("ER-1");
        assertThat(again.get("cases")).hasSize(1);

        // a different body with the same key still answers the first case (the key is checked before the body)
        Map<String, Object> other = caseBody("Someone Else", 3);
        other.put("clientKey", body.get("clientKey"));
        other.put("complaint", "Something else");
        JsonNode different = ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(other), 200);
        assertThat(different.get("actedCase").get("id").asText()).isEqualTo(id);
        assertThat(different.get("actedCase").get("displayName").asText()).isEqualTo("Asha Patil");
        assertThat(different.get("actedCase").get("level").asInt()).isEqualTo(1);
        Map<String, Object> invalid = caseBody("", 9);
        invalid.put("clientKey", body.get("clientKey"));
        assertThat(ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(invalid), 200).get("actedCase").get("id").asText())
                .isEqualTo(id);
        // another allowed role repeating the key gets the same case
        assertThat(ok(HttpMethod.POST, ER_CASES, clinic.as("NURSE"), js(body), 200).get("actedCase").get("id").asText())
                .isEqualTo(id);

        assertThat(written(clinic)).as("no case, event or audit row, no bay taken").isEqualTo(before);
        assertThat(audits(clinic, "IPD_ER_REGISTERED")).isOne();
        assertThat(queue(clinic).get("baysFree").asInt()).isOne();
    }

    @Test
    void aRepeatAfterTheCaseWasClosedStillAnswersTheFirstCaseInItsCurrentStateAndAnotherClinicHasItsOwnKeys() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Map<String, Object> body = caseBody("Asha Patil", 2);
        String id = register(a, body).get("actedCase").get("id").asText();
        release(a, id);
        JsonNode again = ok(HttpMethod.POST, ER_CASES, a.owner(), js(body), 200);
        assertThat(again.get("actedCase").get("id").asText()).isEqualTo(id);
        assertThat(again.get("actedCase").get("status").asText()).isEqualTo("RELEASED");
        assertThat(again.get("cases")).isEmpty();
        assertThat(caseRows(a)).isOne();

        // the same key in another clinic is a new case there
        JsonNode other = ok(HttpMethod.POST, ER_CASES, b.owner(), js(body), 201);
        assertThat(other.get("actedCase").get("id").asText()).isNotEqualTo(id);
        assertThat(caseRows(b)).isOne();
        assertThat(caseRows(a)).isOne();
    }

    // ---------------- order of the queue, waiting minutes, over target ----------------

    @Test
    void theQueueIsOrderedByLevelThenLongestWaitingAndOnlyOpenCasesAreInIt() throws Exception {
        Clinic clinic = hospital();
        String a = regWaited(clinic, "A yellow 50", 2, 50).get("id").asText();
        String b = regWaited(clinic, "B red 5", 1, 5).get("id").asText();
        String c = regWaited(clinic, "C yellow 70", 2, 70).get("id").asText();
        String d = regWaited(clinic, "D green 200", 3, 200).get("id").asText();
        String e = regWaited(clinic, "E red 10", 1, 10).get("id").asText();
        String gone = regWaited(clinic, "F closed", 1, 300).get("id").asText();
        release(clinic, gone);

        JsonNode q = queue(clinic);
        assertThat(ids(q)).containsExactly(e, b, c, a, d);
        assertThat(q.get("counts").get("queue").asInt()).isEqualTo(5);

        // a re-triage keeps the arrival time, so the longest wait comes first inside the new level
        retriage(clinic, a, 1);
        assertThat(ids(queue(clinic))).containsExactly(a, e, b, c, d);
        // Doctor has seen keeps the place in the queue
        seen(clinic, e);
        assertThat(ids(queue(clinic))).containsExactly(a, e, b, c, d);
    }

    @Test
    void casesOfTheSameLevelThatArrivedAtTheSameMomentKeepAFixedOrder() throws Exception {
        Clinic clinic = hospital();
        String x = reg(clinic, "Tie one", 3).get("id").asText();
        String y = reg(clinic, "Tie two", 3).get("id").asText();
        String z = reg(clinic, "Tie three", 3).get("id").asText();
        java.time.Instant same = java.time.Instant.now().minusSeconds(40 * 60);
        for (String id : new String[] {x, y, z}) {
            arrivedAt(clinic, id, same);
        }
        List<String> first = ids(queue(clinic));
        assertThat(first).containsExactlyInAnyOrder(x, y, z);
        for (int i = 0; i < 3; i++) {
            assertThat(ids(queue(clinic))).as("the same order on every read").isEqualTo(first);
        }
    }

    /**
     * QA finding F4 / code review 6 (contract: "then id (as a UUID compare: the server's java.util.UUID order, which
     * is not the PostgreSQL uuid order)"). Cases of one level that arrived at the same instant are ordered by
     * {@code java.util.UUID.compareTo}, which compares the two halves as SIGNED numbers: an id that starts with 8 to f
     * comes before an id that starts with 0 to 7. PostgreSQL puts those ids last. The test registers cases until both
     * kinds of id are present, so the two orders cannot be the same, and then fixes the Java one.
     */
    @Test
    void casesOfOneLevelAndOneInstantAreOrderedByTheJavaUuidCompareNotByThePostgresqlUuidOrder() throws Exception {
        Clinic clinic = hospital();
        List<String> ids = new java.util.ArrayList<>();
        boolean high = false;
        boolean low = false;
        for (int i = 0; i < 60 && !(high && low && ids.size() >= 8); i++) {
            String id = reg(clinic, "Tie " + i, 3).get("id").asText();
            ids.add(id);
            high |= "89abcdef".indexOf(id.charAt(0)) >= 0;
            low |= "01234567".indexOf(id.charAt(0)) >= 0;
        }
        assertThat(high && low).as("ids of both kinds were made").isTrue();
        java.time.Instant same = java.time.Instant.now().minusSeconds(45 * 60);
        for (String id : ids) {
            arrivedAt(clinic, id, same);
        }
        List<String> expected = ids.stream().sorted(java.util.Comparator.comparing(UUID::fromString)).toList();
        String postgres = values(clinic, "select string_agg(id::text, ',' order by id) from ipd.emergency_case").get(0);
        assertThat(String.join(",", expected)).as("the two orders differ, so this test tells them apart").isNotEqualTo(postgres);
        for (int i = 0; i < 3; i++) {
            assertThat(ids(queue(clinic))).as("read " + (i + 1)).isEqualTo(expected);
        }
        // the first two keys still win: a case that waited longer comes first, and a more urgent level beats both
        String longer = reg(clinic, "Waited longer", 3).get("id").asText();
        arrivedAt(clinic, longer, same.minusSeconds(60));
        String urgent = reg(clinic, "Urgent", 2).get("id").asText();
        arrivedAt(clinic, urgent, same.plusSeconds(600));
        List<String> all = new java.util.ArrayList<>();
        all.add(urgent);
        all.add(longer);
        all.addAll(expected);
        assertThat(ids(queue(clinic))).isEqualTo(all);
    }

    @Test
    void waitingMinutesAreWholeMinutesRoundedDownFromTheArrivalAndOverTargetNeedsMoreThanTheTarget() throws Exception {
        Clinic clinic = hospital();                       // Red 5, Yellow 30, Green 120
        String shortWait = reg(clinic, "Just now", 2).get("id").asText();
        arrivedAt(clinic, shortWait, java.time.Instant.now().minusSeconds(50));
        String oneMinute = reg(clinic, "Ninety seconds", 2).get("id").asText();
        arrivedAt(clinic, oneMinute, java.time.Instant.now().minusSeconds(90));
        JsonNode q = queue(clinic);
        assertThat(caseIn(q, shortWait).get("waitingMinutes").asInt()).as("50 seconds rounds down to 0").isZero();
        assertThat(caseIn(q, oneMinute).get("waitingMinutes").asInt()).as("90 seconds rounds down to 1").isOne();

        // red target 5, yellow 30, green 120: at the target is not over, one minute more is
        int[][] checks = {{1, 4, 0}, {1, 5, 0}, {1, 6, 1}, {2, 29, 0}, {2, 30, 0}, {2, 31, 1}, {3, 119, 0}, {3, 120, 0}, {3, 121, 1}};
        int over = 0;
        String[] ids = new String[checks.length];
        for (int i = 0; i < checks.length; i++) {
            ids[i] = regWaited(clinic, "Case " + i, checks[i][0], checks[i][1]).get("id").asText();
            over += checks[i][2];
        }
        JsonNode after = queue(clinic);
        for (int i = 0; i < checks.length; i++) {
            JsonNode c = caseIn(after, ids[i]);
            assertThat(c.get("waitingMinutes").asInt()).as("waited " + checks[i][1]).isEqualTo(checks[i][1]);
            assertThat(c.get("overTarget").asBoolean()).as("level " + checks[i][0] + " waited " + checks[i][1])
                    .isEqualTo(checks[i][2] == 1);
        }
        assertThat(after.get("counts").get("overTarget").asInt()).isEqualTo(over);
        assertThat(after.get("counts").get("queue").asInt()).isEqualTo(checks.length + 2);
        assertThat(ok(HttpMethod.GET, ER_SUMMARY, clinic.owner(), null, 200).get("overTarget").asInt()).isEqualTo(over);
    }

    @Test
    void aTargetOfZeroMeansAnyWaitOfOneMinuteOrMoreIsOverTarget() throws Exception {
        Clinic clinic = hospital();
        setScale(clinic, 5);                              // Level 1 target is Now (0)
        JsonNode fresh = reg(clinic, "Level one fresh", 1);
        assertThat(fresh.get("targetMinutes").asInt()).isZero();
        assertThat(fresh.get("overTarget").asBoolean()).as("0 minutes is not over a target of 0").isFalse();
        String under = reg(clinic, "Level one 50 seconds", 1).get("id").asText();
        arrivedAt(clinic, under, java.time.Instant.now().minusSeconds(50));
        String over = reg(clinic, "Level one 90 seconds", 1).get("id").asText();
        arrivedAt(clinic, over, java.time.Instant.now().minusSeconds(90));
        JsonNode q = queue(clinic);
        assertThat(caseIn(q, fresh.get("id").asText()).get("overTarget").asBoolean()).isFalse();
        assertThat(caseIn(q, under).get("overTarget").asBoolean()).as("50 seconds is 0 whole minutes").isFalse();
        assertThat(caseIn(q, over).get("waitingMinutes").asInt()).isOne();
        assertThat(caseIn(q, over).get("overTarget").asBoolean()).as("1 minute over a target of 0").isTrue();
        assertThat(q.get("counts").get("overTarget").asInt()).isOne();
    }

    @Test
    void aSeenCaseIsNeverOverTargetAndKeepsItsWait() throws Exception {
        Clinic clinic = hospital();
        String id = regWaited(clinic, "Waited long", 1, 40).get("id").asText();   // red target 5
        assertThat(caseIn(queue(clinic), id).get("overTarget").asBoolean()).isTrue();
        JsonNode answer = seen(clinic, id);
        JsonNode acted = answer.get("actedCase");
        assertThat(acted.get("status").asText()).isEqualTo("SEEN");
        assertThat(acted.get("overTarget").asBoolean()).isFalse();
        assertThat(acted.get("waitingMinutes").asInt()).isEqualTo(40);
        assertThat(acted.get("seenAt").asText()).isNotBlank();
        assertThat(answer.get("counts").get("overTarget").asInt()).isZero();
        assertThat(answer.get("counts").get("queue").asInt()).isOne();
        assertThat(caseIn(queue(clinic), id).get("overTarget").asBoolean()).isFalse();
    }

    // ---------------- Doctor has seen ----------------

    @Test
    void doctorHasSeenMarksTheCaseSeenOnceWithTheTimeAndTheUserAndAnotherTapChangesNothing() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Seen soon", 1).get("id").asText();
        String bay = col(clinic, id, "bay");
        String arrived = col(clinic, id, "arrived_at");

        JsonNode answer = ok(HttpMethod.POST, caseUrl(id, "seen"), clinic.as("DOCTOR"), null, 200);
        assertThat(answer.get("actedCase").get("status").asText()).isEqualTo("SEEN");
        assertThat(col(clinic, id, "status")).isEqualTo("SEEN");
        assertThat(col(clinic, id, "seen_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(col(clinic, id, "seen_at")).isNotNull();
        assertThat(col(clinic, id, "version")).isEqualTo("1");
        assertThat(col(clinic, id, "bay")).as("the bay is kept").isEqualTo(bay);
        assertThat(col(clinic, id, "arrived_at")).as("the arrival time is kept").isEqualTo(arrived);
        List<String[]> audit = audit(clinic, "IPD_ER_SEEN");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[1]).isEqualTo(id);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after.get("caseId").asText()).isEqualTo(id);
        assertThat(after.get("level").asInt()).isOne();
        assertThat(after.get("seenAt").asText()).isNotBlank();
        assertThat(eventRows(clinic)).as("seen is not a triage event").isOne();

        Written before = written(clinic);
        JsonNode again = ok(HttpMethod.POST, caseUrl(id, "seen"), clinic.as("NURSE"), null, 200);
        assertThat(again.get("actedCase").get("status").asText()).isEqualTo("SEEN");
        assertThat(again.get("actedCase").get("seenAt").asText()).isEqualTo(answer.get("actedCase").get("seenAt").asText());
        assertThat(written(clinic)).as("a second tap writes nothing").isEqualTo(before);
        assertThat(audits(clinic, "IPD_ER_SEEN")).isOne();
    }

    // ---------------- re-triage ----------------

    @Test
    void aRetriageChangesOnlyTheLevelAndWritesAnEventAndAnAuditRowAndNeverMovesTheBay() throws Exception {
        Clinic clinic = hospital();
        String id = regWaited(clinic, "Retriage me", 2, 40).get("id").asText();
        String arrived = col(clinic, id, "arrived_at");

        JsonNode up = retriage(clinic, id, 1);
        JsonNode acted = up.get("actedCase");
        assertThat(acted.get("level").asInt()).isOne();
        assertThat(acted.get("levelName").asText()).isEqualTo("Red");
        assertThat(acted.get("bay").isNull()).as("no bay is given by a re-triage").isTrue();
        assertThat(acted.get("waitingMinutes").asInt()).as("the wait is kept").isEqualTo(40);
        assertThat(acted.get("targetMinutes").asInt()).isEqualTo(5);
        assertThat(acted.get("overTarget").asBoolean()).isTrue();
        assertThat(acted.get("status").asText()).isEqualTo("WAITING");
        assertThat(up.get("baysFree").asInt()).isEqualTo(2);
        assertThat(col(clinic, id, "arrived_at")).isEqualTo(arrived);
        assertThat(col(clinic, id, "version")).isEqualTo("1");
        assertThat(events(clinic, id)).containsExactly("REGISTERED ->2 s3", "RETRIAGED 2>1 s3");
        assertThat(values(clinic, "select recorded_by::text from ipd.emergency_triage_event where reason = 'RETRIAGED'"))
                .containsExactly(clinic.ownerUserId().toString());
        List<String[]> audit = audit(clinic, "IPD_ER_RETRIAGED");
        assertThat(audit).hasSize(1);
        assertThat(objectMapper.readTree(audit.get(0)[2]).get("level").asInt()).isEqualTo(2);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after.get("level").asInt()).isOne();
        assertThat(after.get("caseId").asText()).isEqualTo(id);
        assertThat(after.get("scale").asInt()).isEqualTo(3);

        // the same level again writes nothing
        Written before = written(clinic);
        JsonNode same = retriage(clinic, id, 1);
        assertThat(same.get("actedCase").get("level").asInt()).isOne();
        assertThat(written(clinic)).isEqualTo(before);

        // a case that holds a bay keeps it when it is moved down, and nobody else can take it
        String red = reg(clinic, "Holds a bay", 1).get("id").asText();
        assertThat(col(clinic, red, "bay")).isEqualTo("ER-1");
        JsonNode down = retriage(clinic, red, 3);
        assertThat(down.get("actedCase").get("bay").asText()).isEqualTo("ER-1");
        assertThat(down.get("baysFree").asInt()).isOne();
        assertThat(reg(clinic, "Next red", 1).get("bay").asText()).isEqualTo("ER-2");
    }

    @Test
    void aSeenCaseCanBeRetriagedAndStaysSeenAndTheScaleIsRespected() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Seen then moved", 3).get("id").asText();
        seen(clinic, id);
        JsonNode moved = retriage(clinic, id, 2);
        assertThat(moved.get("actedCase").get("status").asText()).isEqualTo("SEEN");
        assertThat(moved.get("actedCase").get("level").asInt()).isEqualTo(2);
        assertThat(moved.get("actedCase").get("seenAt").asText()).isNotBlank();

        // level 4 and 5 do not exist in the 3-colour scale; they do in the 5-level scale
        Written before = written(clinic);
        assertFieldError(problem(retriageCall(clinic.owner(), id, "{\"level\":4}"), 400), "level");
        assertFieldError(problem(retriageCall(clinic.owner(), id, "{\"level\":5}"), 400), "level");
        assertThat(written(clinic)).isEqualTo(before);
        setScale(clinic, 5);
        assertThat(retriage(clinic, id, 4).get("actedCase").get("levelName").asText()).isEqualTo("Level 4");
        assertThat(retriage(clinic, id, 5).get("actedCase").get("level").asInt()).isEqualTo(5);
        assertFieldError(problem(retriageCall(clinic.owner(), id, "{\"level\":6}"), 400), "level");
        // a re-triage at the new scale writes an event of that scale
        assertThat(events(clinic, id)).endsWith("RETRIAGED 4>5 s5");
    }

    // ---------------- Treat and release, Refer out ----------------

    @Test
    void releaseAndReferCloseTheCaseFreeTheBayKeepSeenTimeAndCreateNoVisitOrBill() throws Exception {
        Clinic clinic = hospital();
        setBays(clinic, 1);
        long visits = count(clinic, "select count(*) from opd.visit");
        long bills = count(clinic, "select count(*) from billing.bill");
        String red = reg(clinic, "Release me", 1).get("id").asText();
        assertThat(queue(clinic).get("baysFree").asInt()).isZero();
        seen(clinic, red);
        String seenAt = col(clinic, red, "seen_at");

        JsonNode released = release(clinic, red);
        assertThat(released.get("actedCase").get("status").asText()).isEqualTo("RELEASED");
        assertThat(released.get("cases")).isEmpty();
        assertThat(released.get("baysFree").asInt()).isOne();
        assertThat(released.get("counts").get("queue").asInt()).isZero();
        assertThat(col(clinic, red, "closed_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(col(clinic, red, "closed_at")).isNotNull();
        assertThat(col(clinic, red, "seen_at")).as("a seen case keeps its seen time").isEqualTo(seenAt);
        assertThat(col(clinic, red, "admission_id")).isNull();
        List<String[]> audit = audit(clinic, "IPD_ER_RELEASED");
        assertThat(audit).hasSize(1);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after.get("caseId").asText()).isEqualTo(red);
        assertThat(after.get("level").asInt()).isOne();
        assertThat(after.get("bay").asText()).isEqualTo("ER-1");
        assertThat(after.get("closedAt").asText()).isNotBlank();

        String second = reg(clinic, "Refer me", 1).get("id").asText();
        assertThat(col(clinic, second, "bay")).as("the freed bay is given again").isEqualTo("ER-1");
        JsonNode referred = refer(clinic, second);
        assertThat(referred.get("actedCase").get("status").asText()).isEqualTo("REFERRED");
        assertThat(referred.get("baysFree").asInt()).isOne();
        assertThat(col(clinic, second, "status")).isEqualTo("REFERRED");
        assertThat(audits(clinic, "IPD_ER_REFERRED")).isOne();
        assertThat(col(clinic, second, "closed_at")).isNotNull();

        // a waiting case that was never seen can be closed too
        String third = reg(clinic, "Never seen", 3).get("id").asText();
        assertThat(release(clinic, third).get("actedCase").get("seenAt").isNull()).isTrue();

        assertThat(count(clinic, "select count(*) from opd.visit")).as("no OPD visit (OQ-28-3)").isEqualTo(visits);
        assertThat(count(clinic, "select count(*) from billing.bill")).as("no bill (OQ-28-3)").isEqualTo(bills);
        assertThat(count(clinic, "select count(*) from patient.patient where tenant_id = ?", clinic.tenantId()))
                .as("nobody is registered in Patients by an emergency case").isZero();
    }

    @Test
    void anActionOnAClosedCaseAnswers409CaseClosedWithTheExactMessageAndWritesNothing() throws Exception {
        Clinic clinic = hospital();
        String released = reg(clinic, "Released", 2).get("id").asText();
        String referred = reg(clinic, "Referred", 2).get("id").asText();
        release(clinic, released);
        refer(clinic, referred);
        Written before = written(clinic);
        for (String id : new String[] {released, referred}) {
            for (String action : new String[] {"seen", "level", "release", "refer"}) {
                JsonNode p = problem(act(clinic.owner(), id, action), 409);
                assertCaseClosed(p);
                assertThat(p.get("title").asText()).as(action).isEqualTo("Patient is no longer in the queue");
            }
        }
        assertThat(written(clinic)).isEqualTo(before);
    }

    // ---------------- reads ----------------

    @Test
    void theSummaryHoldsOnlyCountsAndMatchesTheQueueAndTheReadsWriteNothing() throws Exception {
        Clinic clinic = hospital();
        regWaited(clinic, "Late red", 1, 30);
        reg(clinic, "Fresh green", 3);
        Written before = written(clinic);
        JsonNode summary = ok(HttpMethod.GET, ER_SUMMARY, clinic.as("DOCTOR"), null, 200);
        assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrder("writable", "queue", "overTarget");
        assertThat(summary.get("writable").asBoolean()).isTrue();
        assertThat(summary.get("queue").asInt()).isEqualTo(2);
        assertThat(summary.get("overTarget").asInt()).isOne();
        assertThat(summary).isEqualTo(queue(clinic).get("counts"));
        assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).get("emergency")).isEqualTo(summary);
        for (String role : ER_ROLES) {
            queueAs(clinic, role);
        }
        assertThat(written(clinic)).as("reads write no row, no audit and no record access").isEqualTo(before);
        // an empty clinic has an empty queue and no case ids
        Clinic empty = hospital();
        JsonNode q = queue(empty);
        assertThat(q.get("cases")).isEmpty();
        assertThat(q.get("counts").get("queue").asInt()).isZero();
        assertThat(q.get("baysFree").asInt()).isEqualTo(2);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
