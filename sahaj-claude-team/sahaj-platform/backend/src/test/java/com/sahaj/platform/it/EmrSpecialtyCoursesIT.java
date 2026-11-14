package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The treatment course links and the OT booking of the specialty screens (SAH-58 as extended by ADR-023 D7, D8; SAH-49-3,
 * SAH-61, SAH-63, SAH-66-3, SAH-64-3, SAH-71; docs/api/opd.yaml 0.5.1), through the real endpoints on {@code sahaj_test}: the
 * dental course built from the plan ({@code fromPlan}), the ANC course stamped into the check, "Session n of m" of a
 * Physiotherapy package and the visit mode it implies, the chemotherapy course taking the order and the planning of the next
 * cycle (refused with IPD off, H13), and the surgery booking: the booking rule, the Idempotency-Key, the sitting made only
 * with IPD on or in trial, the change after booking, and who may book. Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrSpecialtyCoursesIT extends EmrSpecialtySupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String planRow(String id, int tooth, String key, String text, int sittings, long typedPaise, String status) {
        return "{\"id\":\"" + id + "\",\"tooth\":" + tooth + ",\"procedureKey\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"procedureText\":\"" + text
                + "\",\"phase\":1,\"status\":\"" + status + "\",\"sittingsPlanned\":" + sittings + ",\"currentSitting\":1,\"typedEstimatePaise\":" + typedPaise + "}";
    }

    private static String fromPlanBody(Rec rec, String kind, String more) {
        return "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"" + kind + "\",\"fromPlan\":true" + (more == null ? "" : "," + more) + "}";
    }

    private void ipdOn(Clinic clinic) throws Exception {
        ok(HttpMethod.POST, TRIAL_URL, clinic.owner(), null, 200);
    }

    // ================= dental: the course from the plan =================

    @Test
    void aDentalCourseBuiltFromThePlanHasOneSittingPerPlannedSittingOfEveryOpenRowInPlanOrderAndTheEstimateSplit() throws Exception {
        Rec rec = specialtyRec("dental");
        Rec planned = save(rec, spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[" + planRow(uuid(), 16, "ROOT_CANAL", "Root canal", 3, 150000L, "PLANNED") + ","
                + planRow(uuid(), 26, "COMPOSITE_FILLING", "Composite filling", 1, 50000L, "IN_PROGRESS") + "," + planRow(uuid(), 36, "EXTRACTION", "Extraction", 1, 30000L, "DONE")
                + "],\"signEstimate\":true}"));
        assertThat(specialtyOf(planned).get("dental").get("estimateState").asText()).isEqualTo("SIGNED");
        String key = key();
        JsonNode course = okKeyed(HttpMethod.POST, COURSES, rec.token(), fromPlanBody(rec, "DENTAL", null), key, 201);
        assertThat(course.get("kind").asText()).isEqualTo("DENTAL");
        assertThat(course.get("sittings")).as("3 root canal sittings and 1 filling; the done row is left out").hasSize(4);
        List<Long> charges = new ArrayList<>();
        course.get("sittings").forEach(s -> charges.add(s.get("chargePaise").asLong()));
        assertThat(charges).as("the estimate split: Rs 500 a root canal sitting, Rs 500 the filling").containsExactly(50000L, 50000L, 50000L, 50000L);
        assertThat(course.get("sittings").get(0).get("items").get(0).asText()).startsWith("Root canal").contains("tooth 16").contains("sitting 1 of 3");
        assertThat(course.get("sittings").get(3).get("items").get(0).asText()).startsWith("Composite filling");
        for (JsonNode sitting : course.get("sittings")) {
            assertThat(sitting.get("encounterKind").asText()).isEqualTo("OPD");
            assertThat(sitting.get("status").asText()).isEqualTo("PLANNED");
        }
        assertThat(course.get("note").asText()).as("the estimate was signed").isEqualTo("estimate signed by patient");
        // the plan remembers its course, and the same key returns the first course
        assertThat(valueOf(get(RECORDS + "/" + rec.id(), rec.token()), "tx_plan", "specialty").get("courseId").asText()).isEqualTo(course.get("id").asText());
        assertThat(okKeyed(HttpMethod.POST, COURSES, rec.token(), fromPlanBody(rec, "DENTAL", null), key, 200).get("id").asText()).isEqualTo(course.get("id").asText());
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isOne();
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting")).isEqualTo(4);
        assertThat(auditRows(rec.clinic(), "TREATMENT_COURSE_CREATED").toString()).doesNotContain("Root canal").doesNotContain("Composite");
    }

    @Test
    void fromPlanNeedsADentalRecordWithAnOpenPlanRowAndNoSittingsOfItsOwn() throws Exception {
        Rec rec = specialtyRec("dental");
        problemKeyed(HttpMethod.POST, COURSES, rec.token(), fromPlanBody(rec, "DENTAL", null), key(), 400, "validation");
        Rec planned = save(rec, spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[" + planRow(uuid(), 16, "ROOT_CANAL", "Root canal", 3, 150000L, "PLANNED") + "]}"));
        problemKeyed(HttpMethod.POST, COURSES, planned.token(), fromPlanBody(planned, "DENTAL", "\"sittings\":[" + sittingJson("opd_sitting", 100000L, null) + "]"), key(), 400, "validation");
        problemKeyed(HttpMethod.POST, COURSES, planned.token(), fromPlanBody(planned, "CHEMO", null), key(), 400, "validation");
        problemKeyed(HttpMethod.POST, COURSES, planned.token(), fromPlanBody(planned, "REHAB", null), key(), 400, "validation");
        Rec gm = newRec();
        problemKeyed(HttpMethod.POST, COURSES, gm.token(), fromPlanBody(gm, "DENTAL", null), key(), 400, "validation");
        Rec onlyDone = save(specialtyRec("dental"), spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[" + planRow(uuid(), 16, "EXTRACTION", "Extraction", 1, 50000L, "DONE") + "]}"));
        problemKeyed(HttpMethod.POST, COURSES, onlyDone.token(), fromPlanBody(onlyDone, "DENTAL", null), key(), 400, "validation");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isZero();
        Staff other = addDoctor(rec.clinic(), "General physician");
        problemKeyed(HttpMethod.POST, COURSES, other.token(rec.clinic()), fromPlanBody(planned, "DENTAL", null), key(), 403, "not-own-patient");
    }

    // ================= obstetrics: the ANC course =================

    @Test
    void anActiveAncCourseIsStampedIntoTheCheckOfEveryVisitAndTheHistoryListsThePregnancysVisitsWithTodayLast() throws Exception {
        Rec first = specialtyRec("og");
        JsonNode course = newCourse(first, "ANC", null);
        assertThat(course.get("kind").asText()).isEqualTo("ANC");
        String edd = LocalDate.now(IST).plusDays(140).toString();
        String anc = "{\"datingScanEdd\":\"" + edd + "\",\"today\":{\"weightKg\":60.5,\"bpSystolic\":118,\"bpDiastolic\":76},\"symptoms\":[],\"orders\":[],\"scans\":[]}";
        Rec saved = save(first, spSection("anc", "ANC_CHECK", anc));
        assertThat(valueOf(saved.record(), "anc", "specialty").get("pregnancyCourseId").asText()).isEqualTo(course.get("id").asText());
        // a pregnancyCourseId sent by the client is ignored
        Rec forged = save(saved, spSection("anc", "ANC_CHECK", anc.replace("{\"datingScanEdd\"", "{\"pregnancyCourseId\":\"" + uuid() + "\",\"datingScanEdd\"")));
        assertThat(valueOf(forged.record(), "anc", "specialty").get("pregnancyCourseId").asText()).isEqualTo(course.get("id").asText());
        signRecord(first.clinic(), first.id(), 14);
        Rec second = anotherVisit(first, "og");
        Rec secondSaved = save(second, spSection("anc", "ANC_CHECK", anc.replace("60.5", "61.0")));
        assertThat(valueOf(secondSaved.record(), "anc", "specialty").get("pregnancyCourseId").asText()).isEqualTo(course.get("id").asText());
        JsonNode history = get(RECORDS + "/" + second.id() + "/specialty-history", second.token());
        JsonNode visits = history.get("ancVisits");
        assertThat(visits).as("this pregnancy's earlier visit and today").hasSize(2);
        assertThat(visits.get(0).get("today").asBoolean()).isFalse();
        assertThat(visits.get(0).get("weightKg").asDouble()).isEqualTo(60.5);
        assertThat(visits.get(1).get("today").asBoolean()).as("today's row last").isTrue();
        assertThat(visits.get(1).get("weightKg").asDouble()).isEqualTo(61.0);
    }

    @Test
    void aPatientWithNoActiveAncCourseGetsNoPregnancyStampAndAnotherPatientsCourseIsNeverUsed() throws Exception {
        Rec withCourse = specialtyRec("og");
        newCourse(withCourse, "ANC", null);
        Rec other = specialtyRec(withCourse.clinic(), withCourse.doctor(), newPatient(withCourse.clinic()), "og");
        Rec saved = save(other, spSection("anc", "ANC_CHECK", "{\"datingScanEdd\":null,\"today\":{\"weightKg\":60.0},\"symptoms\":[],\"orders\":[],\"scans\":[]}"));
        JsonNode value = valueOf(saved.record(), "anc", "specialty");
        assertThat(value.path("pregnancyCourseId").isNull() || value.path("pregnancyCourseId").isMissingNode()).isTrue();
    }

    // ================= physiotherapy: session n of m =================

    private String tenSessions() {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(sittingJson("extra_session", 50000L, null));
        }
        return "[" + String.join(",", all) + "]";
    }

    @Test
    void theLinkedSessionGivesSessionNOfMAndTheSessionModeAndAStoredModeWins() throws Exception {
        Rec rec = specialtyRec("physio");
        assertThat(specialtyOf(rec).get("physio").get("sessionNo").isNull()).as("no package yet").isTrue();
        JsonNode course = newCourse(rec, "REHAB", tenSessions());
        assertThat(course.get("sittings")).hasSize(10);
        assertThat(course.get("unit").asText()).isEqualTo("SESSION");
        JsonNode second = course.get("sittings").get(1);
        String path = COURSES + "/" + course.get("id").asText() + "/sittings/" + second.get("id").asText() + "/link-visit";
        post(path, rec.token(), "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        JsonNode record = get(RECORDS + "/" + rec.id(), rec.token());
        assertThat(specialtyOf(record).get("physio").get("sessionNo").asInt()).as("S2").isEqualTo(2);
        assertThat(specialtyOf(record).get("physio").get("sessionsPlanned").asInt()).as("package of 10").isEqualTo(10);
        assertThat(specialtyOf(record).get("mode").asText()).as("effective mode: SESSION from the second session").isEqualTo("SESSION");
        assertThat(hiddenKeys(record)).containsExactlyInAnyOrder("special_tests", "posture");
        Rec first = rec.with(record);
        Rec stored = saveScreen(first, "{\"mode\":\"FIRST_VISIT\"}");
        assertThat(specialtyOf(stored).get("mode").asText()).as("the stored mode wins").isEqualTo("FIRST_VISIT");
        assertThat(hiddenKeys(stored.record())).containsExactlyInAnyOrder("modalities", "sessions");
    }

    @Test
    void theFirstSessionOfAPackageStaysInTheFirstVisitMode() throws Exception {
        Rec rec = specialtyRec("physio");
        JsonNode course = newCourse(rec, "REHAB", tenSessions());
        JsonNode one = course.get("sittings").get(0);
        post(COURSES + "/" + course.get("id").asText() + "/sittings/" + one.get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        JsonNode record = get(RECORDS + "/" + rec.id(), rec.token());
        assertThat(specialtyOf(record).get("physio").get("sessionNo").asInt()).isEqualTo(1);
        assertThat(specialtyOf(record).get("mode").asText()).isEqualTo("FIRST_VISIT");
    }

    // ================= oncology: the chemotherapy course =================

    @Test
    void theOrderTakesThePatientsActiveChemoCourseAndTheNextCycleIsPlannedOnlyWithIpdOnOrInTrial() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode course = newCourse(rec, "CHEMO", null);
        assertThat(course.get("kind").asText()).isEqualTo("CHEMO");
        assertThat(course.get("unit").asText()).isEqualTo("CYCLE");
        JsonNode order = saveOrder(rec, passingOrder(0));
        assertThat(row(rec.clinic(), "select course_id::text from opd.chemo_order where id = ?::uuid", order.get("id").asText())).containsExactly(course.get("id").asText());
        // with IPD off no chemotherapy cycle can be planned (H13)
        String addBody = "{\"revision\":" + course.get("revision").asLong() + ",\"addOptionKey\":\"chemo_cycle\"}";
        problemKeyed(HttpMethod.POST, COURSES + "/" + course.get("id").asText() + "/sittings", rec.token(), addBody, key(), 403, "module-off");
        Rec other = oncoAssessed(newOnco());
        problemKeyed(HttpMethod.POST, COURSES, other.token(), "{\"fromRecordId\":\"" + other.id() + "\",\"kind\":\"CHEMO\",\"sittings\":[" + sittingJson("chemo_cycle", 100000L, null) + "]}", key(), 403, "module-off");
        ipdOn(rec.clinic());
        JsonNode added = okKeyed(HttpMethod.POST, COURSES + "/" + course.get("id").asText() + "/sittings", rec.token(), addBody, key(), 201);
        assertThat(added.get("sittings")).hasSize(1);
        assertThat(added.get("sittings").get(0).get("encounterKind").asText()).as("a chemo cycle is a day-care sitting").isEqualTo("DAY_CARE");
        assertThat(added.get("sittings").get(0).get("sittingNo").asInt()).isOne();
    }

    @Test
    void anOrderWithNoCourseHasNoCourseAndTheCycleCountsFromItsSentOrders() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode order = saveOrder(rec, passingOrder(0));
        assertThat(row(rec.clinic(), "select course_id::text, cycle_no::text from opd.chemo_order where id = ?::uuid", order.get("id").asText())).containsExactly(null, "1");
        assertThat(specialtyOf(get(RECORDS + "/" + rec.id(), rec.token())).get("onco").get("cycleNo").asInt()).isOne();
    }

    // ================= surgery: the OT booking =================

    private static String fiveConsentItems() {
        StringBuilder consent = new StringBuilder("{\"items\":[");
        String[] keys = {"PROCEDURE_RISKS", "CONVERSION_TO_OPEN", "ANAESTHESIA", "BLOOD_TRANSFUSION", "PHOTOS_VIDEO"};
        for (int i = 0; i < keys.length; i++) {
            consent.append(i == 0 ? "" : ",").append("{\"key\":\"").append(keys[i]).append("\",\"confirmed\":true}");
        }
        return consent.append("]}").toString();
    }

    private static String plan(String admission, String otDate, String slot) {
        return "{\"procedureKey\":\"LAP_CHOLECYSTECTOMY\",\"procedureText\":\"Laparoscopic cholecystectomy\",\"anaesthesia\":\"GA\",\"admission\":\"" + admission + "\",\"otDate\":"
                + (otDate == null ? "null" : "\"" + otDate + "\"") + ",\"slotText\":" + (slot == null ? "null" : "\"" + slot + "\"") + ",\"payer\":\"SELF\",\"typedPackagePaise\":6000000}";
    }

    private Rec readyToBook(Rec rec, String admission, String otDate) throws Exception {
        return save(rec, spSection("sx_plan", "SURGERY_PLAN", plan(admission, otDate, "OT 2 · 10:00")), spSection("sx_consent", "CONSENT_CHECKLIST", fiveConsentItems()),
                oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", typedItem("Gallstones"))));
    }

    private JsonNode book(Rec rec, String key, int expected) throws Exception {
        return okKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/surgery-booking", rec.token(), "{\"revision\":" + rec.revision() + "}", key, expected);
    }

    @Test
    void theBookingRuleNamesWhatIsMissingAndWritesNothing() throws Exception {
        Rec rec = specialtyRec("surgery");
        String otDate = LocalDate.now(IST).plusDays(5).toString();
        Rec bare = save(rec, spSection("sx_plan", "SURGERY_PLAN", plan("DAY_CARE", otDate, null)));
        JsonNode problem = problemKeyed(HttpMethod.POST, RECORDS + "/" + bare.id() + "/surgery-booking", bare.token(), "{\"revision\":" + bare.revision() + "}", key(), 409, "booking-not-ready");
        assertThat(problem.get("detail").asText()).isEqualTo("Before booking: diagnosis, 5 consent item(s).");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isZero();
        assertThat(auditCount(rec.clinic(), "EMR_SURGERY_BOOKED")).isZero();
        // another payer also needs the pre-authorisation
        Rec tpa = save(bare, spSection("sx_plan", "SURGERY_PLAN", plan("DAY_CARE", otDate, null).replace("\"SELF\"", "\"INSURANCE_TPA\"")), spSection("sx_consent", "CONSENT_CHECKLIST", fiveConsentItems()));
        JsonNode partly = problemKeyed(HttpMethod.POST, RECORDS + "/" + tpa.id() + "/surgery-booking", tpa.token(), "{\"revision\":" + tpa.revision() + "}", key(), 409, "booking-not-ready");
        assertThat(partly.get("detail").asText()).isEqualTo("Before booking: diagnosis, pre-authorisation.");
        // no procedure, no OT date
        Rec noPlan = specialtyRec("surgery");
        problemKeyed(HttpMethod.POST, RECORDS + "/" + noPlan.id() + "/surgery-booking", noPlan.token(), "{\"revision\":" + noPlan.revision() + "}", key(), 400, "validation");
        Rec noDate = readyToBook(specialtyRec("surgery"), "DAY_CARE", null);
        problemKeyed(HttpMethod.POST, RECORDS + "/" + noDate.id() + "/surgery-booking", noDate.token(), "{\"revision\":" + noDate.revision() + "}", key(), 400, "validation");
    }

    @Test
    void withIpdOffTheBookingMakesTheCourseButNoSittingAndSaysSoAndTheSameKeyReturnsTheFirstResult() throws Exception {
        Rec rec = specialtyRec("surgery");
        Rec ready = readyToBook(rec, "IPD_1_2_DAYS", LocalDate.now(IST).plusDays(5).toString());
        assertThat(specialtyOf(ready).get("surgery").get("bookState").asText()).isEqualTo("READY");
        String key = key();
        JsonNode booked = book(ready, key, 200);
        assertThat(booked.get("message").asText()).isEqualTo("Booked. This clinic has no IPD, so no ward bed was requested.");
        assertThat(booked.get("sittingId").isNull()).isTrue();
        assertThat(booked.get("admissionRequested").asBoolean()).isFalse();
        assertThat(booked.get("courseId").asText()).isNotBlank();
        JsonNode record = booked.get("record");
        assertThat(specialtyOf(record).get("surgery").get("bookState").asText()).isEqualTo("BOOKED");
        assertThat(valueOf(record, "sx_plan", "specialty").get("booking").get("stamp").get("byMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(sectionStatus(record, "sx_plan")).as("Done when BOOKED").isEqualTo("DONE");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course where kind = 'SURGERY'")).isOne();
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course_sitting")).as("no sitting with IPD off (H14)").isZero();
        // the same key and body: the first result, nothing more written
        JsonNode again = book(ready, key, 200);
        assertThat(again.get("courseId").asText()).isEqualTo(booked.get("courseId").asText());
        assertThat(again.get("message").asText()).isEqualTo(booked.get("message").asText());
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).isOne();
        assertThat(auditCount(rec.clinic(), "EMR_SURGERY_BOOKED")).isOne();
        // the same key with another revision is a conflict, a new key with the old revision is a stale record
        problemKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/surgery-booking", rec.token(), "{\"revision\":" + (ready.revision() + 9) + "}", key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/surgery-booking", rec.token(), "{\"revision\":" + ready.revision() + "}", key(), 409, "emr-record-changed");
        problemOf(HttpMethod.POST, RECORDS + "/" + rec.id() + "/surgery-booking", rec.token(), "{\"revision\":" + ready.revision() + "}", 400, "validation");
        problemKeyed(HttpMethod.POST, RECORDS + "/" + rec.id() + "/surgery-booking", rec.token(), "{\"revision\":" + ready.revision() + "}", "short", 400, "validation");
        assertThat(auditRows(rec.clinic(), "EMR_SURGERY_BOOKED").get(0).get(2)).doesNotContain("cholecystectomy");
    }

    @Test
    void withIpdInTrialTheBookingAddsOneSittingTheAdmissionRequestAndAChangeAfterBookingBooksAgainWithoutASecondSitting() throws Exception {
        Rec rec = specialtyRec("surgery");
        ipdOn(rec.clinic());
        String otDate = LocalDate.now(IST).plusDays(5).toString();
        Rec ready = readyToBook(rec, "IPD_1_2_DAYS", otDate);
        JsonNode booked = book(ready, key(), 200);
        assertThat(booked.get("message").asText()).isEqualTo("Laparoscopic cholecystectomy booked for " + otDate + ", OT 2 · 10:00. Ward bed requested.");
        assertThat(booked.get("admissionRequested").asBoolean()).isTrue();
        assertThat(booked.get("sittingId").isNull()).isFalse();
        JsonNode course = get(COURSES + "/" + booked.get("courseId").asText(), rec.token());
        assertThat(course.get("kind").asText()).isEqualTo("SURGERY");
        assertThat(course.get("sittings")).hasSize(1);
        JsonNode sitting = course.get("sittings").get(0);
        assertThat(sitting.get("id").asText()).isEqualTo(booked.get("sittingId").asText());
        assertThat(sitting.get("encounterKind").asText()).as("IPD n days is an IPD sitting").isEqualTo("IPD");
        assertThat(sitting.get("chargePaise").asLong()).as("the package amount").isEqualTo(6000000L);
        assertThat(sitting.get("note").asText()).isEqualTo("OT " + otDate + ", OT 2 · 10:00");
        assertThat(sitting.get("admissionRequested").asBoolean()).isTrue();
        assertThat(sitting.get("plannedDate").asText()).isEqualTo(otDate);
        // a change of the plan after booking: the state says so, booking again updates the same sitting
        Rec changed = save(rec.with(booked.get("record")), spSection("sx_plan", "SURGERY_PLAN", plan("DAY_CARE", otDate, "OT 3 · 14:00")));
        assertThat(specialtyOf(changed).get("surgery").get("bookState").asText()).isEqualTo("CHANGED");
        JsonNode again = book(changed, key(), 200);
        assertThat(again.get("courseId").asText()).as("the same SURGERY course is used").isEqualTo(booked.get("courseId").asText());
        assertThat(again.get("sittingId").asText()).isEqualTo(booked.get("sittingId").asText());
        assertThat(specialtyOf(again.get("record")).get("surgery").get("bookState").asText()).isEqualTo("BOOKED");
        JsonNode after = get(COURSES + "/" + booked.get("courseId").asText(), rec.token());
        assertThat(after.get("sittings")).as("still one sitting").hasSize(1);
        assertThat(after.get("sittings").get(0).get("note").asText()).isEqualTo("OT " + otDate + ", OT 3 · 14:00");
        assertThat(after.get("sittings").get(0).get("encounterKind").asText()).isEqualTo("DAY_CARE");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course where kind = 'SURGERY'")).isOne();
        assertThat(auditCount(rec.clinic(), "EMR_SURGERY_BOOKED")).isEqualTo(2);
    }

    @Test
    void onlyTheRecordsOwnDoctorBooksAndOnlyOnASurgeryRecordAndAnotherClinicCannot() throws Exception {
        Rec rec = specialtyRec("surgery");
        Rec ready = readyToBook(rec, "DAY_CARE", LocalDate.now(IST).plusDays(5).toString());
        String path = RECORDS + "/" + rec.id() + "/surgery-booking";
        String body = "{\"revision\":" + ready.revision() + "}";
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemKeyed(HttpMethod.POST, path, staff.token(rec.clinic()), body, key(), 403, "forbidden");
            problemKeyed(HttpMethod.POST, path, staff.tokenClaiming(rec.clinic(), "DOCTOR"), body, key(), 403, "forbidden");
        }
        Staff otherDoctor = addDoctor(rec.clinic(), "General physician");
        problemKeyed(HttpMethod.POST, path, otherDoctor.token(rec.clinic()), body, key(), 403, "not-own-patient");
        Clinic other = newClinic();
        problemKeyed(HttpMethod.POST, path, addDoctor(other, "General physician").token(other), body, key(), 404, "record-not-found");
        problemKeyed(HttpMethod.POST, path, anonymous(), body, key(), 401, "unauthorized");
        Rec gm = newRec();
        problemKeyed(HttpMethod.POST, RECORDS + "/" + gm.id() + "/surgery-booking", gm.token(), "{\"revision\":" + gm.revision() + "}", key(), 400, "not-applicable");
        signRecord(rec.clinic(), rec.id());
        problemKeyed(HttpMethod.POST, path, rec.token(), body, key(), 409, "emr-record-signed");
        assertThat(count(rec.clinic(), "select count(*) from opd.treatment_course")).as("nothing was booked").isZero();
    }

    private static String sectionStatus(JsonNode record, String key) {
        return recordSection(record, key).get("status").asText();
    }
}
