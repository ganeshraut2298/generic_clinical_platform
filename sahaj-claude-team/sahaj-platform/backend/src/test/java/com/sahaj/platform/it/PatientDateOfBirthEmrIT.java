package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.support.LogCapture;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-73-4 and SAH-73-5 through the real EMR endpoints (docs/api/opd.yaml 0.5.3, ADR-024 D7): Paediatrics reads the
 * child's date of birth from the patient module - the header age in months, the months and days of "Growth today", the
 * weight band, the milestone band, the vaccine states with the H9 windows, the growth point's age, the date for the
 * vaccination card - and says "not recorded" without one; the dental Child tab opens by the exact age. Dates are worked
 * out from today in India because the application clock cannot move in an integration test. Test plan:
 * docs/test-plans/SAH-73.md.
 */
@OnTestDatabase
class PatientDateOfBirthEmrIT extends EmrSpecialtySupport {

    private static final String PATIENTS = "/api/v1/patient/patients";

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private UUID patient(Clinic clinic, String name, LocalDate dob, Integer age) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("sex", "M");
        body.put("mobile", uniqueMobile());
        if (dob != null) {
            body.put("dateOfBirth", dob.toString());
        }
        if (age != null) {
            body.put("age", age);
        }
        return UUID.fromString(ok(HttpMethod.POST, PATIENTS, clinic.owner(), body.toString(), 201).get("id").asText());
    }

    private Rec paeds(Clinic clinic, Staff doctor, UUID patientId) throws Exception {
        return specialtyRec(clinic, doctor, patientId, "paeds");
    }

    private static JsonNode paedsOf(Rec rec) {
        return specialtyOf(rec).get("paeds");
    }

    private static String stateOf(JsonNode paeds, String vaccineKey) {
        for (JsonNode v : paeds.get("vaccines")) {
            if (vaccineKey.equals(v.get("vaccineKey").asText())) {
                return v.get("state").asText();
            }
        }
        throw new AssertionError("no vaccine " + vaccineKey);
    }

    private static String immunization(String... keys) {
        StringBuilder given = new StringBuilder();
        for (String key : keys) {
            given.append(given.length() == 0 ? "" : ",").append("{\"vaccineKey\":\"").append(key).append("\"}");
        }
        return "{\"givenToday\":[" + given + "],\"scheduleReviewed\":false}";
    }

    // ---------------- SAH-73-4 the header, growth today, bands ----------------

    @Test
    void aChildOfEighteenMonthsHasTheHeaderAgeInMonthsTheMonthsAndDaysAndTheDateForTheCard() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(18);
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Eighteen", dob, null));
        JsonNode p = paedsOf(rec);
        assertThat(p.get("dateOfBirthRecorded").asBoolean()).isTrue();
        assertThat(p.get("dateOfBirth").asText()).as("the card prints it").isEqualTo(dob.toString());
        assertThat(p.get("ageMonths").asInt()).isEqualTo(18);
        // days after the 18 completed months (not always 0: minusMonths cuts a 31st back to a shorter month's end)
        assertThat(p.get("ageDays").asInt()).isEqualTo((int) java.time.temporal.ChronoUnit.DAYS.between(dob.plusMonths(18), today()));
        JsonNode header = rec.record().get("header");
        assertThat(header.get("ageDetail").get("unit").asText()).isEqualTo("MONTHS");
        assertThat(header.get("ageDetail").get("value").asInt()).isEqualTo(18);
        assertThat(header.get("ageDetail").get("fromDateOfBirth").asBoolean()).isTrue();
        assertThat(header.get("age").asInt()).as("the integer stays: completed years").isEqualTo(1);
        // the date is in the answer once: inside paeds.dateOfBirth, nowhere else of the record
        assertThat(rec.record().toString().split(dob.toString(), -1).length - 1).isEqualTo(1);
        assertThat(p.get("milestonesSetUp").asBoolean()).as("a milestone band exists at 18 months").isTrue();
    }

    @Test
    void theWeightNoteUsesTheBandOfTheChildsAgeAndSaysNotAvailableForAnAgeWithNoBand() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec at18 = paeds(clinic, doctor, patient(clinic, "Paeds Band", today().minusMonths(18), null));
        Rec saved = save(at18, spSection("growth", "GROWTH", "{\"weightKg\":10.0}"));
        JsonNode notes = paedsOf(saved).get("growthNotes");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("field").asText()).isEqualTo("weightKg");
        assertThat(notes.get(0).get("label").asText()).contains("centile").doesNotContain("not available");
        Rec at30 = paeds(clinic, doctor, patient(clinic, "Paeds No Band", today().minusMonths(30), null));
        JsonNode none = paedsOf(save(at30, spSection("growth", "GROWTH", "{\"weightKg\":10.0}"))).get("growthNotes");
        assertThat(none.get(0).get("label").asText()).isEqualTo("Centile not available for this age");
        assertThat(paedsOf(at30).get("milestonesSetUp").asBoolean()).as("no band at 30 months").isFalse();
    }

    @Test
    void growthTodayShowsMonthsAndDaysWorkedOutFromTheDate() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(5).minusDays(3);
        JsonNode p = paedsOf(paeds(clinic, doctor, patient(clinic, "Paeds Days", dob, null)));
        assertThat(p.get("ageMonths").asInt()).isEqualTo(5);
        // days after the completed months: the same date plus 5 months, then the days to today
        assertThat(p.get("ageDays").asInt()).isEqualTo((int) java.time.temporal.ChronoUnit.DAYS.between(dob.plusMonths(5), today()));
    }

    // ---------------- SAH-73-4 the vaccine states and adding a vaccine ----------------

    @Test
    void aNewbornHasBirthVaccinesDueTodayAndTheDoctorCanAddOneButNotAVaccineThatIsNotDue() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Newborn", today(), null));
        JsonNode p = paedsOf(rec);
        assertThat(stateOf(p, "bcg")).isEqualTo("DUE_TODAY");
        assertThat(stateOf(p, "opv_0")).isEqualTo("DUE_TODAY");
        assertThat(stateOf(p, "dtwp_1")).as("6 weeks is later").isEqualTo("LATER");
        assertThat(p.get("dueTodayCount").asInt()).isEqualTo(3);
        assertThat(p.get("overdueCount").asInt()).isZero();
        refuseSave(rec, 400, "validation", spSection("immunization", "IMMUNIZATION", immunization("dtwp_1")));
        Rec given = save(rec, spSection("immunization", "IMMUNIZATION", immunization("bcg")));
        assertThat(stateOf(paedsOf(given), "bcg")).isEqualTo("GIVEN");
        assertThat(paedsOf(given).get("dueTodayCount").asInt()).isEqualTo(2);
    }

    @Test
    void aChildOfEightWeeksHasTheBirthVaccinesOverdueAndTheSixWeekOnesDueToday() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Eight Weeks", today().minusWeeks(8), null));
        JsonNode p = paedsOf(rec);
        assertThat(stateOf(p, "bcg")).isEqualTo("OVERDUE");
        assertThat(stateOf(p, "dtwp_1")).isEqualTo("DUE_TODAY");
        assertThat(stateOf(p, "dtwp_2")).isEqualTo("LATER");
        assertThat(p.get("overdueCount").asInt()).isEqualTo(3);
        // an overdue vaccine may be given today
        Rec given = save(rec, spSection("immunization", "IMMUNIZATION", immunization("bcg", "dtwp_1")));
        assertThat(stateOf(paedsOf(given), "bcg")).isEqualTo("GIVEN");
        assertThat(stateOf(paedsOf(given), "dtwp_1")).isEqualTo("GIVEN");
        refuseSave(rec, 400, "validation", spSection("immunization", "IMMUNIZATION", immunization("dtwp_2")));
    }

    @Test
    void aWindowEndsOnItsLastDayExclusive() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        // Birth window is 0 days to 6 weeks, end exclusive: on day 41 still due, on day 42 overdue
        assertThat(stateOf(paedsOf(paeds(clinic, doctor, patient(clinic, "Paeds Day 41", today().minusDays(41), null))), "bcg")).isEqualTo("DUE_TODAY");
        assertThat(stateOf(paedsOf(paeds(clinic, doctor, patient(clinic, "Paeds Day 42", today().minusDays(42), null))), "bcg")).isEqualTo("OVERDUE");
    }

    @Test
    void aCorrectedDateRecomputesTheStatesOnTheNextReadAndGivenVaccinesStayGiven() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        UUID patientId = patient(clinic, "Paeds Corrected", today(), null);
        Rec rec = paeds(clinic, doctor, patientId);
        Rec given = save(rec, spSection("immunization", "IMMUNIZATION", immunization("bcg")));
        assertThat(stateOf(paedsOf(given), "hep_b_1")).isEqualTo("DUE_TODAY");
        ok(HttpMethod.PATCH, PATIENTS + "/" + patientId, clinic.as("FRONT_DESK"),
                "{\"dateOfBirth\":\"" + today().minusWeeks(8) + "\"}", 200);
        JsonNode reread = current(given).get("specialty").get("paeds");
        assertThat(stateOf(reread, "bcg")).as("given stays given").isEqualTo("GIVEN");
        assertThat(stateOf(reread, "hep_b_1")).as("recomputed from the new date").isEqualTo("OVERDUE");
        assertThat(stateOf(reread, "dtwp_1")).isEqualTo("DUE_TODAY");
        assertThat(reread.get("ageMonths").asInt()).isEqualTo(1);
    }

    @Test
    void anEarlierSignedRecordGivenVaccineIsNotDueAgain() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        UUID patientId = patient(clinic, "Paeds Earlier", today().minusWeeks(8), null);
        Rec first = paeds(clinic, doctor, patientId);
        Rec given = save(first, spSection("immunization", "IMMUNIZATION", immunization("bcg")));
        signRecord(clinic, given.id(), 1);
        Rec second = anotherVisit(first, "paeds");
        assertThat(stateOf(paedsOf(second), "bcg")).isEqualTo("GIVEN");
        assertThat(stateOf(paedsOf(second), "opv_0")).isEqualTo("OVERDUE");
    }

    // ---------------- SAH-73-4 growth point age ----------------

    @Test
    void theGrowthPointsAgeInMonthsComesFromTheDateAndIsNullWithoutOne() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec dated = save(paeds(clinic, doctor, patient(clinic, "Paeds Growth Dated", today().minusMonths(18), null)),
                spSection("growth", "GROWTH", "{\"weightKg\":10.0}"));
        JsonNode points = get(RECORDS + "/" + dated.id() + "/specialty-history", dated.token()).get("growthPoints");
        assertThat(points).hasSize(1);
        assertThat(points.get(0).get("ageMonths").asDouble()).isCloseTo(18.0, within(0.1));
        Rec undated = save(paeds(clinic, doctor, patient(clinic, "Paeds Growth Undated", null, 1)),
                spSection("growth", "GROWTH", "{\"weightKg\":10.0}"));
        JsonNode none = get(RECORDS + "/" + undated.id() + "/specialty-history", undated.token()).get("growthPoints");
        assertThat(none).hasSize(1);
        assertThat(none.get(0).get("ageMonths").isNull()).as("no made-up month count, the point is not plotted").isTrue();
        assertThat(none.get(0).get("weightKg").asDouble()).isEqualTo(10.0);
    }

    // ---------------- SAH-73-4 no date: the not recorded behaviour ----------------

    @Test
    void aChildWithNoDateHasNoMonthsNoDueVaccinesNoBandsAndTheNotRecordedFlag() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Undated", null, 1));
        JsonNode p = paedsOf(rec);
        assertThat(p.get("dateOfBirthRecorded").asBoolean()).isFalse();
        assertThat(p.path("dateOfBirth").isNull() || p.path("dateOfBirth").isMissingNode()).isTrue();
        assertThat(p.get("ageMonths").isNull()).isTrue();
        assertThat(p.get("ageDays").isNull()).isTrue();
        for (JsonNode v : p.get("vaccines")) {
            assertThat(v.get("state").asText()).isIn("LATER", "GIVEN");
        }
        assertThat(p.get("dueTodayCount").asInt()).isZero();
        assertThat(p.get("overdueCount").asInt()).isZero();
        assertThat(p.get("milestonesSetUp").asBoolean()).isFalse();
        JsonNode header = rec.record().get("header");
        assertThat(header.get("age").asInt()).as("the whole number, never a made-up month count").isEqualTo(1);
        assertThat(header.get("ageDetail").get("unit").asText()).isEqualTo("YEARS");
        assertThat(header.get("ageDetail").get("fromDateOfBirth").asBoolean()).isFalse();
        refuseSave(rec, 400, "validation", spSection("immunization", "IMMUNIZATION", immunization("bcg")));
        Rec weighed = save(rec, spSection("growth", "GROWTH", "{\"weightKg\":9.0}"));
        assertThat(paedsOf(weighed).get("growthNotes").get(0).get("label").asText()).isEqualTo("Centile not available for this age");
    }

    @Test
    void theDoseByWeightDoesNotUseTheAgeOrTheDate() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        Rec dated = save(paeds(clinic, doctor, patient(clinic, "Paeds Dose Dated", today().minusMonths(18), null)),
                spSection("growth", "GROWTH", "{\"weightKg\":11.0}"));
        Rec undated = save(paeds(clinic, doctor, patient(clinic, "Paeds Dose Undated", null, 1)),
                spSection("growth", "GROWTH", "{\"weightKg\":11.0}"));
        assertThat(paedsOf(dated).get("doseWeightKg").asDouble()).isEqualTo(11.0);
        assertThat(paedsOf(undated).get("doseWeightKg").asDouble()).isEqualTo(11.0);
    }

    // ---------------- SAH-73-4 case paper and who reads ----------------

    @Test
    void theCasePaperCarriesTheAgeDetailAndNeverTheDate() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(18);
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Paper", dob, null));
        JsonNode paper = get(RECORDS + "/" + rec.id() + "/case-paper", rec.token());
        assertThat(paper.get("patient").get("ageDetail").get("unit").asText()).isEqualTo("MONTHS");
        assertThat(paper.get("patient").get("ageDetail").get("value").asInt()).isEqualTo(18);
        assertThat(paper.get("patient").get("age").asInt()).isEqualTo(1);
        assertThat(paper.toString()).doesNotContain(dob.toString());
    }

    @Test
    void theEmrIsClosedToNurseFrontDeskBillingPharmacistAndCounsellorAndToAnotherClinic() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = today().minusMonths(18);
        Rec rec = paeds(clinic, doctor, patient(clinic, "Paeds Closed", dob, null));
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.GET, RECORDS + "/" + rec.id(), clinic.as(role), null), 403);
        }
        Clinic other = newClinic();
        Staff otherDoctor = addDoctor(other, "Paediatrics");
        problem(call(HttpMethod.GET, RECORDS + "/" + rec.id(), otherDoctor.token(other), null), 404);
        problem(call(HttpMethod.GET, RECORDS + "/" + rec.id() + "/specialty-history", otherDoctor.token(other), null), 404);
        call(HttpMethod.GET, RECORDS + "/" + rec.id(), anonymous(), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void theVisitingDoctorAndTheOwnerWithDoctorDetailsReadTheSameFields() throws Exception {
        Clinic clinic = newClinic();
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting", "Paediatrics", "MD", "REG-" + shortId());
        LocalDate dob = today().minusMonths(18);
        Rec rec = paeds(clinic, visiting, patient(clinic, "Paeds Visiting", dob, null));
        assertThat(paedsOf(rec).get("dateOfBirth").asText()).isEqualTo(dob.toString());
        assertThat(paedsOf(rec).get("ageMonths").asInt()).isEqualTo(18);
    }

    // ---------------- SAH-73-5 the dental Child tab ----------------

    private boolean childTab(Clinic clinic, Staff doctor, String name, LocalDate dob, Integer age) throws Exception {
        Rec rec = specialtyRec(clinic, doctor, patient(clinic, name, dob, age), "dental");
        return specialtyOf(rec).get("dental").get("childTabDefault").asBoolean();
    }

    @Test
    void theDentalChartOpensOnTheChildTabUnderThirteenByTheExactAge() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dental");
        LocalDate t = today();
        assertThat(childTab(clinic, doctor, "Dental 12y11m", t.minusYears(12).minusMonths(11), null)).as("12 years 11 months").isTrue();
        assertThat(childTab(clinic, doctor, "Dental turns 13 tomorrow", t.minusYears(13).plusDays(1), null)).isTrue();
        assertThat(childTab(clinic, doctor, "Dental 13 today", t.minusYears(13), null)).as("exactly 13 today: Adult").isFalse();
        assertThat(childTab(clinic, doctor, "Dental infant", t.minusMonths(7), null)).isTrue();
        assertThat(childTab(clinic, doctor, "Dental adult", t.minusYears(40), null)).isFalse();
    }

    @Test
    void withoutADateTheDentalTabOpensByTheWholeNumberAge() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dental");
        assertThat(childTab(clinic, doctor, "Dental undated 12", null, 12)).isTrue();
        assertThat(childTab(clinic, doctor, "Dental undated 0", null, 0)).isTrue();
        assertThat(childTab(clinic, doctor, "Dental undated 13", null, 13)).isFalse();
        assertThat(childTab(clinic, doctor, "Dental undated 45", null, 45)).isFalse();
    }

    @Test
    void theDentalTabFollowsACorrectedDateOnTheNextRead() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dental");
        UUID patientId = patient(clinic, "Dental Corrected", null, 12);
        Rec rec = specialtyRec(clinic, doctor, patientId, "dental");
        assertThat(specialtyOf(rec).get("dental").get("childTabDefault").asBoolean()).isTrue();
        ok(HttpMethod.PATCH, PATIENTS + "/" + patientId, clinic.as("FRONT_DESK"),
                "{\"dateOfBirth\":\"" + today().minusYears(13) + "\"}", 200);
        assertThat(current(rec).get("specialty").get("dental").get("childTabDefault").asBoolean()).isFalse();
        ok(HttpMethod.PATCH, PATIENTS + "/" + patientId, clinic.as("FRONT_DESK"),
                "{\"dateOfBirth\":\"" + today().minusYears(10) + "\"}", 200);
        assertThat(current(rec).get("specialty").get("dental").get("childTabDefault").asBoolean()).isTrue();
    }

    @Test
    void aDentalRecordNeverCarriesTheDateOfBirth() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dental");
        LocalDate dob = today().minusYears(9).minusDays(17);
        Rec rec = specialtyRec(clinic, doctor, patient(clinic, "Dental No Date", dob, null), "dental");
        assertThat(rec.record().toString()).doesNotContain(dob.toString());
        assertThat(get(RECORDS + "/" + rec.id() + "/case-paper", rec.token()).toString()).doesNotContain(dob.toString());
        assertThat(rec.record().get("header").get("age").asInt()).isEqualTo(9);
    }

    // ---------------- privacy ----------------

    @Test
    void noLogLineAndNoAuditValueHoldsTheDateWhileTheEmrIsOpenedAndSaved() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Paediatrics");
        LocalDate dob = LocalDate.of(2025, 6, 10);
        String logs;
        try (LogCapture capture = new LogCapture()) {
            Rec rec = paeds(clinic, doctor, patient(clinic, "Zxqemr Child", dob, null));
            Rec saved = save(rec, spSection("growth", "GROWTH", "{\"weightKg\":10.0}"));
            get(RECORDS + "/" + saved.id() + "/specialty-history", saved.token());
            get(RECORDS + "/" + saved.id() + "/case-paper", saved.token());
            current(saved);
            problem(call(HttpMethod.GET, RECORDS + "/" + saved.id(), clinic.as("NURSE"), null), 403);
            logs = logText(capture.events());
        }
        assertThat(logs).contains("/api/v1/opd/emr");
        assertThat(logs).doesNotContain("2025-06-10").doesNotContain("Zxqemr");
        assertThat(allAuditText(clinic)).doesNotContain("2025-06-10").doesNotContain("Zxqemr");
    }

    private static String logText(List<ILoggingEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : events) {
            if (event.getLoggerName().startsWith("org.springframework.test.")) {
                continue;
            }
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
            text.append(event.getMDCPropertyMap()).append('\n');
        }
        return text.toString();
    }
}
