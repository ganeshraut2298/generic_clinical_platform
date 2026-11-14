package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-58 (ADR-022 D12) the plan of a treatment course in opd: start a course from an open EMR, plan and add sittings (the add
 * options of each kind, IPD off or on), change a planned one, link today's OPD visit, the status computed on read, who may
 * read and who may change, the Idempotency-Key and the course revision, two adds at the same moment, and the other clinic.
 * Real database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrCoursePlanIT extends EmrPhase3Support {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String sitting(String option, String date, String itemsJson, Long charge) {
        return "{\"addOptionKey\":\"" + option + "\"" + (date == null ? "" : ",\"plannedDate\":\"" + date + "\"")
                + (itemsJson == null ? "" : ",\"items\":" + itemsJson) + (charge == null ? "" : ",\"chargePaise\":" + charge) + "}";
    }

    private static String createBody(Rec rec, String kind, String name, String sittingsJson) {
        return "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"" + kind + "\"" + (name == null ? "" : ",\"name\":\"" + name + "\"")
                + (sittingsJson == null ? "" : ",\"sittings\":" + sittingsJson) + "}";
    }

    private JsonNode createCourse(Rec rec, String kind, String sittingsJson) throws Exception {
        return okKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, kind, null, sittingsJson), key(), 201);
    }

    private JsonNode course(Rec rec, JsonNode course) throws Exception {
        return get(COURSES + "/" + course.get("id").asText(), rec.token());
    }

    private static String addBody(JsonNode course, String option, String date) {
        return "{\"revision\":" + course.get("revision").asLong() + ",\"addOptionKey\":\"" + option + "\""
                + (date == null ? "" : ",\"plannedDate\":\"" + date + "\"") + "}";
    }

    private JsonNode add(Rec rec, JsonNode course, String option, int expected) throws Exception {
        return okKeyed(HttpMethod.POST, COURSES + "/" + course.get("id").asText() + "/sittings", rec.token(), addBody(course, option, null), key(), expected);
    }

    private static String changeBody(JsonNode course, String kind, String date, String itemsJson, long charge, String note) {
        return "{\"revision\":" + course.get("revision").asLong() + ",\"encounterKind\":\"" + kind + "\",\"plannedDate\":"
                + (date == null ? "null" : "\"" + date + "\"") + ",\"items\":" + itemsJson + ",\"chargePaise\":" + charge + ",\"note\":"
                + (note == null ? "null" : "\"" + note + "\"") + "}";
    }

    private JsonNode change(Rec rec, JsonNode course, int sittingIndex, String body, int expected) throws Exception {
        return put(COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(sittingIndex).get("id").asText(), rec.token(), body, expected);
    }

    private void ipdOn(Clinic clinic) throws Exception {
        ok(HttpMethod.POST, TRIAL_URL, clinic.owner(), null, 200);
    }

    // ================= start a course =================

    @Test
    void aCourseStartedFromAnOpenEmrTakesTheKindsUnitDefaultNameAndTheClinicsNextNumberAndNumbersAreOnePerClinic() throws Exception {
        Rec rec = newRec();
        String[][] kinds = {{"DENTAL", "SITTING", "Dental treatment course"}, {"ANC", "VISIT", "Pregnancy care · ANC course"},
                {"CHEMO", "CYCLE", "Chemotherapy course"}, {"SURGERY", "STEP", "Surgery episode"}, {"REHAB", "SESSION", "Rehab package"}};
        for (int i = 0; i < kinds.length; i++) {
            JsonNode course = createCourse(rec, kinds[i][0], null);
            assertThat(course.get("courseNo").asText()).isEqualTo(String.format("TC-%04d", i + 1));
            assertThat(course.get("kind").asText()).isEqualTo(kinds[i][0]);
            assertThat(course.get("unit").asText()).isEqualTo(kinds[i][1]);
            assertThat(course.get("name").asText()).isEqualTo(kinds[i][2]);
            assertThat(course.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(course.get("startedOn").asText()).isEqualTo(LocalDate.now(IST).toString());
            assertThat(course.get("sittingCount").asInt()).isZero();
            assertThat(course.get("doneCount").asInt()).isZero();
            assertThat(course.get("patientId").asText()).isEqualTo(rec.patientId().toString());
            assertThat(course.get("doctorMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
            assertThat(course.get("doctorName").asText()).isEqualTo("Dr Test Doctor");
            assertThat(course.get("note").isNull()).isTrue();
            assertThat(course.get("sittings")).isEmpty();
            assertThat(course.get("canPlan").asBoolean()).isTrue();
            assertThat(course.get("canAdmit").asBoolean()).isFalse();
            assertThat(course.get("accountNote").asText()).isEqualTo("One running account: advances and part payments carry across visits.");
        }
        assertThat(count(rec.clinic(), "select last_no from opd.treatment_course_counter")).isEqualTo(5);
        // another clinic starts at 1 again
        Rec other = newRec();
        assertThat(createCourse(other, "DENTAL", null).get("courseNo").asText()).isEqualTo("TC-0001");
        // the name and the note the doctor typed, the clinic id in the body ignored
        JsonNode named = okKeyed(HttpMethod.POST, COURSES, rec.token(), "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"DENTAL\",\"name\":\"Root canal and crown\","
                + "\"note\":\"estimate signed by patient\",\"tenantId\":\"" + UUID.randomUUID() + "\",\"clinicId\":\"" + UUID.randomUUID() + "\"}", key(), 201);
        assertThat(named.get("name").asText()).isEqualTo("Root canal and crown");
        assertThat(named.get("note").asText()).isEqualTo("estimate signed by patient");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isEqualTo(6);
    }

    @Test
    void thePlannedSittingsAreNumberedInOrderWithTheirOwnChargeItemsAndDateAndAnAdmissionOptionIsARequest() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", "2026-12-01", "[\"Scaling\",\"Polishing\"]", 150000L) + ","
                + sitting("day_care_admission", null, null, null) + "," + sitting("opd_sitting", null, "[]", 200000L) + "]");
        JsonNode sittings = course.get("sittings");
        assertThat(sittings).hasSize(3);
        assertThat(texts(sittings, "sittingNo")).containsExactly("1", "2", "3");
        JsonNode first = sittings.get(0);
        assertThat(first.get("encounterKind").asText()).isEqualTo("OPD");
        assertThat(first.get("encounterLabel").asText()).isEqualTo("OPD sitting");
        assertThat(first.get("plannedDate").asText()).isEqualTo("2026-12-01");
        assertThat(first.get("status").asText()).isEqualTo("PLANNED");
        assertThat(first.get("items").toString()).isEqualTo("[\"Scaling\",\"Polishing\"]");
        assertThat(first.get("chargePaise").asLong()).isEqualTo(150000L);
        assertThat(first.get("admissionRequested").asBoolean()).isFalse();
        assertThat(first.get("settledInIpd").asBoolean()).isFalse();
        assertThat(first.get("encounterRef").isNull()).isTrue();
        assertThat(first.get("opdVisitId").isNull()).isTrue();
        JsonNode dayCare = sittings.get(1);
        assertThat(dayCare.get("encounterKind").asText()).isEqualTo("DAY_CARE");
        assertThat(dayCare.get("encounterLabel").asText()).isEqualTo("Day-care admission");
        assertThat(dayCare.get("admissionRequested").asBoolean()).isTrue();
        assertThat(dayCare.get("note").asText()).isEqualTo("Admission request. Rate set at admission.");
        assertThat(dayCare.get("chargePaise").asLong()).isZero();
        assertThat(course.get("sittingCount").asInt()).isEqualTo(3);
        assertThat(course.get("addOptions")).hasSize(3);
        assertThat(texts(course.get("addOptions"), "key")).containsExactly("opd_sitting", "day_care_admission", "ipd_admission");
        // the same fixed options for every kind, as drawn
        String[][] options = {{"ANC", "anc_visit,emergency_admission,delivery_admission"}, {"CHEMO", "chemo_cycle,review_opd,side_effects_admission"},
                {"SURGERY", "opd_review,re_admission,day_care_procedure"}, {"REHAB", "extra_session,bedside_session"}};
        for (String[] o : options) {
            assertThat(String.join(",", texts(createCourse(rec, o[0], null).get("addOptions"), "key"))).isEqualTo(o[1]);
        }
        assertThat(course.get("canAdmit").asBoolean()).as("a doctor does not admit: only the owner and the front desk do").isFalse();
    }

    @Test
    void aPlanOutsideTheLimitsOrTheFixedListsIs400ValidationNamingTheFieldAndNothingIsCreated() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        String[] items21 = new String[21];
        java.util.Arrays.fill(items21, "\"x\"");
        String[][] bad = {
                {"[" + sitting("opd_sitting", null, null, 150L) + "]", "sittings[0].chargePaise"},
                {"[" + sitting("opd_sitting", null, null, -100L) + "]", "sittings[0].chargePaise"},
                {"[" + sitting("opd_sitting", null, null, 10_000_000_100L) + "]", "sittings[0].chargePaise"},
                {"[" + sitting("opd_sitting", null, "[" + String.join(",", items21) + "]", null) + "]", "sittings[0].items"},
                {"[" + sitting("opd_sitting", null, "[\"\"]", null) + "]", "sittings[0].items"},
                {"[" + sitting("opd_sitting", null, "[\"" + "i".repeat(201) + "\"]", null) + "]", "sittings[0].items"},
                {"[" + sitting("opd_sitting", null, "[\"<b>x</b>\"]", null) + "]", "sittings[0].items"},
                {"[" + sitting("anc_visit", null, null, null) + "]", "sittings[0].addOptionKey"},
                {"[" + sitting("nothing", null, null, null) + "]", "sittings[0].addOptionKey"}};
        for (String[] b : bad) {
            JsonNode problem = problem(callKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, "DENTAL", null, b[0]), key()), 400);
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).as(b[0]).isEqualTo(b[1]);
            assertThat(problem.toString()).doesNotContain("iiiiiiiiii").doesNotContain("<b>");
        }
        StringBuilder fiftyOne = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            fiftyOne.append(i == 0 ? "" : ",").append(sitting("opd_sitting", null, null, null));
        }
        assertThat(callKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, "DENTAL", null, fiftyOne + "]"), key()).andReturn().getResponse().getStatus()).isEqualTo(400);
        for (String body : new String[] {createBody(rec, "DENTAL", "n".repeat(121), null), createBody(rec, "DENTAL", "", null),
                createBody(rec, "DENTAL", "<b>x</b>", null), createBody(rec, "TEETH", null, null), createBody(rec, null, null, null),
                createBody(rec, "DENTAL", null, null).replace("}", ",\"zxqextra\":1}"),
                createBody(rec, "DENTAL", null, null).replace("}", ",\"note\":\"" + "n".repeat(301) + "\"}"),
                "{\"kind\":\"DENTAL\"}", "{\"fromRecordId\":\"not-a-uuid\",\"kind\":\"DENTAL\"}"}) {
            assertThat(callKeyed(HttpMethod.POST, COURSES, rec.token(), body, key()).andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        // fifty is accepted
        StringBuilder fifty = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            fifty.append(i == 0 ? "" : ",").append(sitting("opd_sitting", null, null, 100L));
        }
        assertThat(createCourse(rec, "DENTAL", fifty + "]").get("sittingCount").asInt()).isEqualTo(50);
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeyReturnsTheFirstCourseAndAnotherBodyWithItIs409AndAMissingKeyIs400() throws Exception {
        Rec rec = newRec();
        String key = key();
        String body = createBody(rec, "DENTAL", "One course", null);
        JsonNode first = okKeyed(HttpMethod.POST, COURSES, rec.token(), body, key, 201);
        JsonNode again = okKeyed(HttpMethod.POST, COURSES, rec.token(), body, key, 200);
        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(again.get("courseNo").asText()).isEqualTo("TC-0001");
        problemKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, "DENTAL", "Another name", null), key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, "ANC", "One course", null), key, 409, "idempotency-conflict");
        for (String bad : new String[] {null, "short", "has space in it 12345", "a".repeat(65)}) {
            problemKeyed(HttpMethod.POST, COURSES, rec.token(), body, bad, 400, "validation");
        }
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isEqualTo(1);
        assertThat(auditCount(rec.clinic(), "TREATMENT_COURSE_CREATED")).isEqualTo(1);
        // a clinic's key is not another clinic's: the same key in another clinic makes its own course
        Rec other = newRec();
        okKeyed(HttpMethod.POST, COURSES, other.token(), createBody(other, "DENTAL", "One course", null), key, 201);
    }

    @Test
    void aClinicWithoutIpdRefusesAnAdmissionOptionWith403ModuleOffAndOffersOnlyOpdOptionsAndTheTrialOffersAll() throws Exception {
        Rec rec = newRec();
        problemKeyed(HttpMethod.POST, COURSES, rec.token(), createBody(rec, "DENTAL", null, "[" + sitting("day_care_admission", null, null, null) + "]"), key(), 403, "module-off");
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", null, null, 100000L) + "]");
        assertThat(texts(course.get("addOptions"), "key")).containsExactly("opd_sitting");
        add(rec, course, "ipd_admission", 403);
        add(rec, course, "day_care_admission", 403);
        problemOf(HttpMethod.PUT, COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText(), rec.token(),
                changeBody(course, "DAY_CARE", null, "[]", 0, null), 403, "module-off");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting")).isEqualTo(1);
        ipdOn(rec.clinic());
        JsonNode on = course(rec, course);
        assertThat(on.get("addOptions")).hasSize(3);
        assertThat(add(rec, on, "day_care_admission", 201).get("sittings")).hasSize(2);
    }

    // ================= add and change a sitting =================

    @Test
    void anAddedSittingGetsTheNextNumberAnOpdOptionCopiesTheLastCharge() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", null, null, 120000L) + "]");
        JsonNode second = okKeyed(HttpMethod.POST, COURSES + "/" + course.get("id").asText() + "/sittings", rec.token(), addBody(course, "opd_sitting", "2026-12-05"), key(), 201);
        assertThat(second.get("sittings")).hasSize(2);
        JsonNode added = second.get("sittings").get(1);
        assertThat(added.get("sittingNo").asInt()).isEqualTo(2);
        assertThat(added.get("chargePaise").asLong()).as("an OPD option copies the last sitting's charge").isEqualTo(120000L);
        assertThat(added.get("plannedDate").asText()).isEqualTo("2026-12-05");
        assertThat(added.get("admissionRequested").asBoolean()).isFalse();
        assertThat(second.get("revision").asLong()).isGreaterThan(course.get("revision").asLong());
        JsonNode admission = add(rec, second, "ipd_admission", 201).get("sittings").get(2);
        assertThat(admission.get("encounterKind").asText()).isEqualTo("IPD");
        assertThat(admission.get("chargePaise").asLong()).as("rate is set at admission").isZero();
        assertThat(admission.get("admissionRequested").asBoolean()).isTrue();
        assertThat(admission.get("note").asText()).isEqualTo("Admission request. Rate set at admission.");
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_ADDED")).isEqualTo(2);
        // an added sitting on an empty course starts at charge zero
        JsonNode empty = createCourse(rec, "ANC", null);
        assertThat(add(rec, empty, "anc_visit", 201).get("sittings").get(0).get("chargePaise").asLong()).isZero();
    }

    @Test
    void theSameKeyAddsOnceAnotherBodyWithItIs409AndTwoDifferentAddsAtOnceAreBothKeptInOrder() throws Exception {
        Rec rec = newRec();
        JsonNode course = createCourse(rec, "DENTAL", null);
        String path = COURSES + "/" + course.get("id").asText() + "/sittings";
        String key = key();
        JsonNode first = okKeyed(HttpMethod.POST, path, rec.token(), addBody(course, "opd_sitting", null), key, 201);
        JsonNode again = okKeyed(HttpMethod.POST, path, rec.token(), addBody(course, "opd_sitting", null), key, 200);
        assertThat(first.get("sittings")).hasSize(1);
        assertThat(again.get("sittings")).as("a double tap makes one sitting").hasSize(1);
        problemKeyed(HttpMethod.POST, path, rec.token(), addBody(course, "opd_sitting", "2026-12-24"), key, 409, "idempotency-conflict");
        for (String bad : new String[] {null, "short", "has space in it 12345"}) {
            problemKeyed(HttpMethod.POST, path, rec.token(), addBody(course, "opd_sitting", null), bad, 400, "validation");
        }
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting")).isEqualTo(1);
        // two different adds at the same moment
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> tap = () -> {
                go.await();
                return callKeyed(HttpMethod.POST, path, rec.token(), addBody(first, "opd_sitting", null), key()).andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Future<Integer> one = pool.submit(tap);
            java.util.concurrent.Future<Integer> two = pool.submit(tap);
            go.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactly(201, 201);
        } finally {
            pool.shutdownNow();
        }
        JsonNode after = course(rec, course);
        assertThat(texts(after.get("sittings"), "sittingNo")).containsExactly("1", "2", "3");
        assertThat(count(rec.clinic(), "select count(distinct sitting_no) from opd.treatment_course_sitting")).isEqualTo(3);
        // the 51st sitting is refused
        JsonNode many = createCourse(rec, "DENTAL", "[" + String.join(",", java.util.Collections.nCopies(50, sitting("opd_sitting", null, null, 100L))) + "]");
        add(rec, many, "opd_sitting", 400);
    }

    @Test
    void aPlannedSittingChangesItsDateItemsChargeNoteAndKindWithTheCourseRevisionAndAStaleRevisionIs409() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", "2026-12-01", "[\"Scaling\"]", 100000L) + "]");
        JsonNode changed = change(rec, course, 0, changeBody(course, "OPD", "2026-12-09", "[\"Filling\",\"X-ray\"]", 250000, "Bring the old X-ray"), 200);
        JsonNode s = changed.get("sittings").get(0);
        assertThat(s.get("plannedDate").asText()).isEqualTo("2026-12-09");
        assertThat(s.get("items").toString()).isEqualTo("[\"Filling\",\"X-ray\"]");
        assertThat(s.get("chargePaise").asLong()).isEqualTo(250000L);
        assertThat(s.get("note").asText()).isEqualTo("Bring the old X-ray");
        assertThat(changed.get("revision").asLong()).isEqualTo(course.get("revision").asLong() + 1);
        // the old revision is refused and nothing is applied
        JsonNode stale = problemOf(HttpMethod.PUT, COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText(), rec.token(),
                changeBody(course, "OPD", null, "[]", 0, null), 409, "course-changed");
        assertThat(stale.toString()).doesNotContain("Filling");
        assertThat(course(rec, course).get("sittings").get(0).get("chargePaise").asLong()).isEqualTo(250000L);
        // OPD to day care sets the request flag and back to OPD clears it, before any link
        JsonNode toDayCare = change(rec, changed, 0, changeBody(changed, "DAY_CARE", null, "[]", 0, null), 200);
        assertThat(toDayCare.get("sittings").get(0).get("admissionRequested").asBoolean()).isTrue();
        assertThat(toDayCare.get("sittings").get(0).get("encounterKind").asText()).isEqualTo("DAY_CARE");
        JsonNode back = change(rec, toDayCare, 0, changeBody(toDayCare, "OPD", null, "[]", 100000, null), 200);
        assertThat(back.get("sittings").get(0).get("admissionRequested").asBoolean()).isFalse();
        // the input is checked
        for (String body : new String[] {changeBody(back, "OPD", null, "[]", 150, null), changeBody(back, "OPD", null, "[]", -100, null),
                changeBody(back, "HOSPITAL", null, "[]", 100, null), changeBody(back, "OPD", null, "[\"\"]", 100, null),
                changeBody(back, "OPD", null, "[]", 100, "n".repeat(301)), changeBody(back, "OPD", null, "[]", 100, null).replace("{", "{\"zxqextra\":1,"),
                "{\"revision\":" + back.get("revision").asLong() + "}"}) {
            assertThat(call(HttpMethod.PUT, COURSES + "/" + back.get("id").asText() + "/sittings/" + back.get("sittings").get(0).get("id").asText(), rec.token(), body)
                    .andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        problemOf(HttpMethod.PUT, COURSES + "/" + back.get("id").asText() + "/sittings/" + newId(), rec.token(), changeBody(back, "OPD", null, "[]", 0, null), 404, "sitting-not-found");
        // the audit holds dates, kind, charge and status only: never an item or a note
        List<List<String>> audits = auditRows(rec.clinic(), "COURSE_SITTING_CHANGED");
        assertThat(audits).hasSize(3);
        JsonNode before = objectMapper.readTree(audits.get(0).get(1));
        JsonNode after = objectMapper.readTree(audits.get(0).get(2));
        assertThat(before.get("chargePaise").asLong()).isEqualTo(100000L);
        assertThat(after.get("chargePaise").asLong()).isEqualTo(250000L);
        assertThat(before.get("plannedDate").asText()).isEqualTo("2026-12-01");
        assertThat(after.get("plannedDate").asText()).isEqualTo("2026-12-09");
        for (List<String> row : audits) {
            assertThat(row.get(1) + row.get(2)).doesNotContain("Filling").doesNotContain("X-ray").doesNotContain("Bring the old");
        }
    }

    // ================= link today's visit, the computed status =================

    @Test
    void linkingTodaysVisitMakesTheSittingTodayAndItIsDoneOnlyWhenItsRecordIsSignedOrItIsMarkedDone() throws Exception {
        Rec rec = newRec();
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", null, null, 100000L) + "," + sitting("opd_sitting", null, null, 100000L) + "]");
        JsonNode visit = visitFor(rec.clinic(), rec.patientId(), rec.doctor());
        String path = COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/link-visit";
        String body = "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + visit.get("id").asText() + "\"}";
        JsonNode linked = post(path, rec.token(), body, 200);
        JsonNode s = linked.get("sittings").get(0);
        assertThat(s.get("status").asText()).isEqualTo("TODAY");
        assertThat(s.get("opdVisitId").asText()).isEqualTo(visit.get("id").asText());
        assertThat(s.get("encounterRef").asText()).startsWith("Token ");
        assertThat(linked.get("sittings").get(1).get("status").asText()).isEqualTo("PLANNED");
        assertThat(linked.get("doneCount").asInt()).isZero();
        // repeating the same link is a 200 that writes nothing
        post(path, rec.token(), body, 200);
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_VISIT_LINKED")).isEqualTo(1);
        // the visit's record signed: the sitting is done and counted
        String recordId = openVisit(rec.token(), visit.get("id").asText(), 201).get("record").get("id").asText();
        signRecord(rec.clinic(), recordId);
        JsonNode done = course(rec, course);
        assertThat(done.get("sittings").get(0).get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("doneCount").asInt()).isEqualTo(1);
        // a done sitting cannot be changed (a correction is an addendum on its visit)
        JsonNode problem = problemOf(HttpMethod.PUT, COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText(), rec.token(),
                changeBody(done, "OPD", null, "[]", 100, null), 409, "sitting-done");
        assertThat(problem.get("type").asText()).endsWith("sitting-done");
        // the second sitting is marked done directly (what SAH-51 will do in the sign transaction)
        commit(rec.clinic().tenantId(), "update opd.treatment_course_sitting set done_at = now() where id = ?::uuid", course.get("sittings").get(1).get("id").asText());
        assertThat(course(rec, course).get("doneCount").asInt()).isEqualTo(2);
        JsonNode list = get(COURSES + "?patientId=" + rec.patientId(), rec.token());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("sittingCount").asInt()).isEqualTo(2);
        assertThat(list.get(0).get("doneCount").asInt()).isEqualTo(2);
        assertThat(list.get(0).has("sittings")).as("the list is the summary").isFalse();
    }

    @Test
    void aVisitCanBeLinkedToOneSittingOnlyAndOnlyToAPlannedUnlinkedOpdSittingOfThePatientAndTheRevisionMustMatch() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", null, null, 100000L) + "," + sitting("opd_sitting", null, null, 100000L) + "," + sitting("day_care_admission", null, null, null) + "]");
        JsonNode visit = visitFor(rec.clinic(), rec.patientId(), rec.doctor());
        String base = COURSES + "/" + course.get("id").asText() + "/sittings/";
        String body = "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + visit.get("id").asText() + "\"}";
        problemOf(HttpMethod.POST, base + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + (course.get("revision").asLong() + 5) + ",\"visitId\":\"" + visit.get("id").asText() + "\"}", 409, "course-changed");
        JsonNode linked = post(base + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.token(), body, 200);
        String next = "{\"revision\":" + linked.get("revision").asLong() + ",\"visitId\":\"" + visit.get("id").asText() + "\"}";
        problemOf(HttpMethod.POST, base + course.get("sittings").get(1).get("id").asText() + "/link-visit", rec.token(), next, 409, "visit-already-linked");
        // the sitting that already has a visit takes no other
        JsonNode another = visitFor(rec.clinic(), rec.patientId(), rec.doctor());
        problemOf(HttpMethod.POST, base + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + linked.get("revision").asLong() + ",\"visitId\":\"" + another.get("id").asText() + "\"}", 409, "sitting-not-available");
        // a day-care sitting takes no OPD visit
        problemOf(HttpMethod.POST, base + course.get("sittings").get(2).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + linked.get("revision").asLong() + ",\"visitId\":\"" + another.get("id").asText() + "\"}", 409, "sitting-not-available");
        // another patient's visit, an unknown visit, an unknown sitting
        JsonNode strangerVisit = issueVisit(rec.clinic(), rec.doctor().membershipId());
        problemOf(HttpMethod.POST, base + course.get("sittings").get(1).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + linked.get("revision").asLong() + ",\"visitId\":\"" + strangerVisit.get("id").asText() + "\"}", 404, "encounter-not-found");
        problemOf(HttpMethod.POST, base + course.get("sittings").get(1).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + linked.get("revision").asLong() + ",\"visitId\":\"" + UUID.randomUUID() + "\"}", 404, "encounter-not-found");
        problemOf(HttpMethod.POST, base + UUID.randomUUID() + "/link-visit", rec.token(), next, 404, "sitting-not-found");
        assertThat(call(HttpMethod.POST, base + course.get("sittings").get(1).get("id").asText() + "/link-visit", rec.token(), "{\"revision\":1}").andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting where opd_visit_id is not null")).isEqualTo(1);
        // the database refuses the same visit on two sittings and an admission on an OPD sitting too
        String state = db().inTenant(rec.clinic().tenantId(), c -> expectError(c, "update opd.treatment_course_sitting set opd_visit_id = ?::uuid where id = ?::uuid",
                visit.get("id").asText(), course.get("sittings").get(1).get("id").asText()).state());
        assertThat(state).isEqualTo("23505");
    }

    // ================= who may read and who may change =================

    @Test
    void ownerDoctorVisitingDoctorFrontDeskAndBillingReadACourseAndNobodyElseAndOnlyTheCoursesDoctorOrAnOwnerWithDoctorDetailsChangesIt() throws Exception {
        Rec rec = newRec();
        ipdOn(rec.clinic());
        JsonNode course = createCourse(rec, "DENTAL", "[" + sitting("opd_sitting", null, null, 100000L) + "]");
        String path = COURSES + "/" + course.get("id").asText();
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING"}) {
            JsonNode view = get(path, rec.clinic().as(role));
            assertThat(view.get("id").asText()).as(role).isEqualTo(course.get("id").asText());
            assertThat(get(COURSES + "?patientId=" + rec.patientId(), rec.clinic().as(role))).as(role).hasSize(1);
            boolean admit = "OWNER".equals(role) || "FRONT_DESK".equals(role);
            assertThat(view.get("canAdmit").asBoolean()).as(role + " canAdmit").isEqualTo(admit);
        }
        for (String role : new String[] {"NURSE", "PHARMACIST", "COUNSELLOR"}) {
            problemOf(HttpMethod.GET, path, rec.clinic().as(role), null, 403, "forbidden");
            problemOf(HttpMethod.GET, COURSES + "?patientId=" + rec.patientId(), rec.clinic().as(role), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
        // writes: every role but the doctors and the owner is refused before anything else
        String add = addBody(course, "opd_sitting", null);
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problemKeyed(HttpMethod.POST, COURSES, rec.clinic().as(role), createBody(rec, "DENTAL", null, null), key(), 403, "forbidden");
            problemKeyed(HttpMethod.POST, path + "/sittings", rec.clinic().as(role), add, key(), 403, "forbidden");
            problemOf(HttpMethod.PUT, path + "/sittings/" + course.get("sittings").get(0).get("id").asText(), rec.clinic().as(role),
                    changeBody(course, "OPD", null, "[]", 0, null), 403, "forbidden");
            problemOf(HttpMethod.POST, path + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.clinic().as(role),
                    "{\"revision\":0,\"visitId\":\"" + UUID.randomUUID() + "\"}", 403, "forbidden");
        }
        // another doctor of the clinic: reads, but is not the course's doctor and not the patient's doctor
        Staff other = addDoctor(rec.clinic(), "General physician");
        assertThat(get(path, other.token(rec.clinic())).get("canPlan").asBoolean()).isFalse();
        problemKeyed(HttpMethod.POST, path + "/sittings", other.token(rec.clinic()), add, key(), 403, "not-own-patient");
        problemOf(HttpMethod.PUT, path + "/sittings/" + course.get("sittings").get(0).get("id").asText(), other.token(rec.clinic()),
                changeBody(course, "OPD", null, "[]", 0, null), 403, "not-own-patient");
        problemKeyed(HttpMethod.POST, COURSES, other.token(rec.clinic()), createBody(rec, "DENTAL", null, null), key(), 403, "not-own-patient");
        // an owner with doctor details changes any course
        ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-OWNER");
        assertThat(get(path, rec.clinic().owner()).get("canPlan").asBoolean()).isTrue();
        assertThat(okKeyed(HttpMethod.POST, path + "/sittings", rec.clinic().owner(), add, key(), 201).get("sittings")).hasSize(2);
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting")).isEqualTo(2);
    }

    @Test
    void anotherClinicNeverSeesAChangesOrLinksACourseAndStartsNoneFromAForeignRecordAndAPatientOfAnotherClinicGivesAnEmptyList() throws Exception {
        Rec a = newRec();
        JsonNode course = createCourse(a, "DENTAL", "[" + sitting("opd_sitting", null, null, 100000L) + "]");
        Rec b = newRec();
        String path = COURSES + "/" + course.get("id").asText();
        problemOf(HttpMethod.GET, path, b.clinic().owner(), null, 404, "course-not-found");
        problemKeyed(HttpMethod.POST, path + "/sittings", b.token(), addBody(course, "opd_sitting", null), key(), 404, "course-not-found");
        problemOf(HttpMethod.PUT, path + "/sittings/" + course.get("sittings").get(0).get("id").asText(), b.token(), changeBody(course, "OPD", null, "[]", 0, null), 404, "course-not-found");
        problemOf(HttpMethod.POST, path + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/link-visit", b.token(),
                "{\"revision\":0,\"visitId\":\"" + b.visit().get("id").asText() + "\"}", 404, "course-not-found");
        assertThat(get(COURSES + "?patientId=" + a.patientId(), b.token())).isEmpty();
        problemKeyed(HttpMethod.POST, COURSES, b.token(), createBody(a, "DENTAL", null, null), key(), 404, "record-not-found");
        assertThat(call(HttpMethod.GET, COURSES, b.token(), null).andReturn().getResponse().getStatus()).as("the patient is required").isEqualTo(400);
        assertThat(count(b.clinic(), "select count(*) from opd.treatment_course")).isZero();
        assertThat(count(a.clinic(), "select count(*) from opd.treatment_course")).isEqualTo(1);
    }

    // ================= audit and privacy =================

    @Test
    void theCourseAuditsCarryIdsKindsAndMoneyOnlyNeverTheItemsOrTheNote() throws Exception {
        Rec rec = newRec();
        JsonNode course = okKeyed(HttpMethod.POST, COURSES, rec.token(), "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"DENTAL\",\"name\":\"Secret course name\",\"note\":\"Secret note ZXQ\","
                + "\"sittings\":[" + sitting("opd_sitting", null, "[\"Secret item ZXQ\"]", 150000L) + "]}", key(), 201);
        add(rec, course, "opd_sitting", 201);
        List<List<String>> created = auditRows(rec.clinic(), "TREATMENT_COURSE_CREATED");
        assertThat(created).hasSize(1);
        JsonNode after = objectMapper.readTree(created.get(0).get(2));
        assertThat(after.get("courseNo").asText()).isEqualTo("TC-0001");
        assertThat(after.get("kind").asText()).isEqualTo("DENTAL");
        assertThat(after.get("sittingCount").asInt()).isEqualTo(1);
        assertThat(after.get("estimatePaise").asLong()).isEqualTo(150000L);
        assertThat(created.get(0).get(0)).isEqualTo("TREATMENT_COURSE");
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event where coalesce(before_json::text, '') || coalesce(after_json::text, '') like '%ZXQ%'")).isZero();
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event where coalesce(before_json::text, '') || coalesce(after_json::text, '') like '%Secret%'")).isZero();
        List<String> numbers = new ArrayList<>();
        for (List<String> row : rows(rec.clinic(), "select course_no::text from opd.treatment_course order by course_no")) {
            numbers.add(row.get(0));
        }
        assertThat(numbers).containsExactly("1");
    }
}
