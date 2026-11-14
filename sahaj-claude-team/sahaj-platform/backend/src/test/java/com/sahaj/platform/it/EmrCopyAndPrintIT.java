package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-48-1 and SAH-50-4 (ADR-022 D6): "Repeat last Rx", "Copy medicines to today" and "Repeat these tests" copy the items of a
 * signed record of the same patient and are idempotent by content (a double tap adds once); SAH-48-3 and SAH-55-2 (D9): the
 * visit's paper size and print language; SAH-55 (D9): the case paper context (letterhead, doctor, patient, headings in three
 * languages, share never available). A signed record is made by the one {@code update} the column grants allow (SAH-51 does
 * not exist yet). Real database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrCopyAndPrintIT extends EmrPhase3Support {

    private static final String LAST_RX = "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"what\":\"MEDICINES\"}";

    /** A patient with a signed earlier record (three medicines, two tests) and an open record today. */
    private record Pair(Clinic clinic, Staff doctor, UUID patientId, Rec earlier, Rec today) {
    }

    private Pair pair() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        Rec first = startRecord(clinic, doctor, patient);
        Rec filled = save(first, rxSection(rxRow(medicineId(PLAIN_MEDICINE), PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD"),
                typedRx("Zinc syrup"), rxRow(medicineId(SULFA_MEDICINE), SULFA_MEDICINE, "1-1-1", 7, "BEFORE_FOOD")),
                oneField("investigations", "lab_tests", listValue("INVESTIGATIONS", typedItem("CBC"), typedItem("Blood sugar"))));
        Rec today = startRecord(clinic, doctor, patient);
        signRecord(clinic, filled.id(), 3);
        return new Pair(clinic, doctor, patient, filled, today);
    }

    private JsonNode copy(Rec target, String body, int expected) throws Exception {
        return post(RECORDS + "/" + target.id() + "/copy-items", target.token(), body, expected);
    }

    private static String pastRecord(String sourceId, String what) {
        return "{\"source\":\"PAST_RECORD\",\"sourceRecordId\":\"" + sourceId + "\",\"what\":\"" + what + "\"}";
    }

    // ================= Repeat last Rx / Copy medicines to today =================

    @Test
    void repeatLastRxCopiesEveryMedicineWithItsFrequencyDaysAndWhenAsNewRowsAndTheLastSignedPrescriptionIsOfferedOnlyOnceSigned() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        Rec first = startRecord(clinic, doctor, patient);
        Rec filled = save(first, rxSection(rxRow(medicineId(PLAIN_MEDICINE), PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD"), typedRx("Zinc syrup")));
        Rec today = startRecord(clinic, doctor, patient);
        assertThat(today.record().get("lastSignedPrescription").isNull()).as("an unsigned record is not offered").isTrue();
        copyExpecting404(today, LAST_RX);
        signRecord(clinic, filled.id(), 2);
        JsonNode offered = current(today).get("lastSignedPrescription");
        assertThat(offered.get("recordId").asText()).isEqualTo(filled.id());
        assertThat(offered.get("date").asText()).matches("^\\d{4}-\\d{2}-\\d{2}$");
        JsonNode result = copy(today, LAST_RX, 200);
        assertThat(result.get("added").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").asInt()).isZero();
        JsonNode rows = valueOf(result.get("record"), "rx", "drug").get("rows");
        assertThat(rows).hasSize(2);
        JsonNode old = valueOf(filled.record(), "rx", "drug").get("rows");
        assertThat(rows.get(0).get("name").asText()).isEqualTo(PLAIN_MEDICINE);
        assertThat(rows.get(0).get("frequency").asText()).isEqualTo("1-0-1");
        assertThat(rows.get(0).get("days").asInt()).isEqualTo(3);
        assertThat(rows.get(0).get("when").asText()).isEqualTo("AFTER_FOOD");
        assertThat(rows.get(0).get("source").asText()).isEqualTo("REPEAT");
        assertThat(rows.get(0).get("id").asText()).as("a new row, not the old one").isNotEqualTo(old.get(0).get("id").asText());
        assertThat(rows.get(0).get("allergyReview").isNull()).isTrue();
        assertThat(rows.get(1).get("source").asText()).as("a typed medicine stays typed").isEqualTo("TYPED");
        assertThat(rows.get(1).get("name").asText()).isEqualTo("Zinc syrup");
        assertThat(result.get("record").get("revision").asLong()).isEqualTo(today.revision() + 1);
        // the old record is not touched
        assertThat(valueOf(current(filled), "rx", "drug").get("rows").get(0).get("id").asText()).isEqualTo(old.get(0).get("id").asText());
    }

    private void copyExpecting404(Rec rec, String body) throws Exception {
        problemOf(HttpMethod.POST, RECORDS + "/" + rec.id() + "/copy-items", rec.token(), body, 404, "past-visit-not-found");
    }

    @Test
    void aSecondTapAddsNothingAndChangesNothingAndANameAlreadyOnTodaysListIgnoringCaseIsSkipped() throws Exception {
        Pair p = pair();
        Rec withOne = save(p.today(), rxSection(typedRx("ZINC SYRUP")));
        JsonNode first = copy(withOne, LAST_RX, 200);
        assertThat(first.get("added").asInt()).isEqualTo(2);
        assertThat(first.get("skipped").asInt()).as("the zinc syrup was already there").isEqualTo(1);
        long revision = first.get("record").get("revision").asLong();
        JsonNode second = copy(withOne, LAST_RX, 200);
        assertThat(second.get("added").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(3);
        assertThat(second.get("record").get("revision").asLong()).as("nothing was written").isEqualTo(revision);
        assertThat(valueOf(second.get("record"), "rx", "drug").get("rows")).hasSize(3);
        // the same tap twice in a row adds once
        Pair q = pair();
        JsonNode a = copy(q.today(), LAST_RX, 200);
        JsonNode b = copy(q.today(), LAST_RX, 200);
        assertThat(a.get("added").asInt() + b.get("added").asInt()).isEqualTo(3);
        assertThat(valueOf(current(q.today()), "rx", "drug").get("rows")).hasSize(3);
        // one audit entry per call: the source record, what, added and skipped; never a medicine name
        List<List<String>> audits = auditRows(q.clinic(), "EMR_ITEMS_COPIED");
        assertThat(audits).hasSize(2);
        JsonNode after = objectMapper.readTree(audits.get(0).get(2));
        assertThat(after.get("sourceRecordId").asText()).isEqualTo(q.earlier().id());
        assertThat(after.get("what").asText()).isEqualTo("MEDICINES");
        assertThat(after.get("added").asInt()).isEqualTo(3);
        assertThat(after.get("skipped").asInt()).isZero();
        assertThat(after.size()).isEqualTo(4);
        assertThat(audits.get(0).get(2) + audits.get(1).get(2)).doesNotContain("Zinc").doesNotContain("Paracetamol").doesNotContain("Co-trimoxazole");
    }

    @Test
    void twoTapsAtTheSameMomentAddEachMedicineOnce() throws Exception {
        Pair p = pair();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> tap = () -> {
                go.await();
                return copy(p.today(), LAST_RX, 200).get("added").asInt();
            };
            java.util.concurrent.Future<Integer> one = pool.submit(tap);
            java.util.concurrent.Future<Integer> two = pool.submit(tap);
            go.countDown();
            assertThat(one.get() + two.get()).as("each medicine was added by exactly one of the taps").isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(valueOf(current(p.today()), "rx", "drug").get("rows")).hasSize(3);
        assertThat(texts(valueOf(current(p.today()), "rx", "drug").get("rows"), "name")).doesNotHaveDuplicates();
    }

    @Test
    void copyMedicinesFromAPastVisitMarksTheRowsCopyAndRunsTheAllergyCheckOnTheResult() throws Exception {
        Pair p = pair();
        Rec allergic = save(p.today(), allergySection("SULFA"));
        JsonNode result = copy(allergic, pastRecord(p.earlier().id(), "MEDICINES"), 200);
        assertThat(result.get("added").asInt()).isEqualTo(3);
        JsonNode rows = valueOf(result.get("record"), "rx", "drug").get("rows");
        assertThat(rows.get(0).get("source").asText()).isEqualTo("COPY");
        assertThat(rows.get(1).get("source").asText()).isEqualTo("TYPED");
        JsonNode alerts = result.get("record").get("allergyAlerts");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("medicineName").asText()).isEqualTo(SULFA_MEDICINE);
        assertThat(alerts.get(0).get("reviewed").asBoolean()).as("a copied row is never copied with its review").isFalse();
        assertThat(result.get("record").get("readyToComplete").asBoolean()).isFalse();
    }

    @Test
    void aSourceReviewThatWasConfirmedIsNotCarriedToTheCopy() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        Rec first = startRecord(clinic, doctor, patient);
        ObjectNode reviewed = rxRow(medicineId(SULFA_MEDICINE), SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        reviewed.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", true);
        Rec filled = save(first, allergySection("SULFA"), rxSection(reviewed));
        Rec today = save(startRecord(clinic, doctor, patient), allergySection("SULFA"));
        signRecord(clinic, filled.id(), 1);
        JsonNode result = copy(today, LAST_RX, 200);
        assertThat(valueOf(result.get("record"), "rx", "drug").get("rows").get(0).get("allergyReview").isNull()).isTrue();
        assertThat(result.get("record").get("allergyAlerts").get(0).get("reviewed").asBoolean()).isFalse();
    }

    // ================= Repeat these tests =================

    @Test
    void repeatTheseTestsListsTheTestsInTodaysInvestigationsWithDefaultAttributesAndSendsNothingAnywhere() throws Exception {
        Pair p = pair();
        JsonNode result = copy(p.today(), pastRecord(p.earlier().id(), "TESTS"), 200);
        assertThat(result.get("added").asInt()).isEqualTo(2);
        JsonNode items = valueOf(result.get("record"), "investigations", "lab_tests").get("items");
        assertThat(texts(items, "text")).containsExactly("CBC", "Blood sugar");
        assertThat(items.get(0).get("attr1").asText()).isEqualTo("ROUTINE");
        assertThat(items.get(0).get("attr2").asText()).isEqualTo("CLINIC_LAB");
        assertThat(items.get(0).get("active").asBoolean()).isTrue();
        assertThat(items.get(0).get("addedAt").asText()).isNotBlank();
        assertThat(valueOf(result.get("record"), "rx", "drug")).as("tests do not touch the prescription").isNull();
        JsonNode again = copy(p.today(), pastRecord(p.earlier().id(), "TESTS"), 200);
        assertThat(again.get("added").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(2);
        assertThat(valueOf(again.get("record"), "investigations", "lab_tests").get("items")).hasSize(2);
        // nothing other than the record and its audit entry was written
        assertThat(count(p.clinic(), "select count(*) from billing.bill_item where source_kind = 'EMR'")).isZero();
        assertThat(auditCount(p.clinic(), "EMR_ITEMS_COPIED")).isEqualTo(2);
    }

    // ================= the refusals =================

    @Test
    void theInputIsChecked400AndASourceThatIsNotASignedRecordOfThisPatientInThisClinicIs404PastVisitNotFound() throws Exception {
        Pair p = pair();
        String[] bad = {
                "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"what\":\"TESTS\"}",
                "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"sourceRecordId\":\"" + p.earlier().id() + "\",\"what\":\"MEDICINES\"}",
                "{\"source\":\"PAST_RECORD\",\"what\":\"MEDICINES\"}", "{\"source\":\"LAST\",\"what\":\"MEDICINES\"}",
                "{\"source\":\"PAST_RECORD\",\"sourceRecordId\":\"" + p.earlier().id() + "\",\"what\":\"ALL\"}", "{\"what\":\"MEDICINES\"}",
                "{\"source\":\"LAST_SIGNED_PRESCRIPTION\"}", "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"what\":\"MEDICINES\",\"zxqextra\":1}",
                "{\"source\":\"PAST_RECORD\",\"sourceRecordId\":\"not-a-uuid\",\"what\":\"MEDICINES\"}"};
        for (String body : bad) {
            JsonNode problem = problem(call(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", p.today().token(), body), 400);
            assertThat(problem.toString()).doesNotContain("zxqextra");
        }
        assertThat(current(p.today()).get("revision").asLong()).isEqualTo(p.today().revision());
        // another record of the patient that is not signed, the record itself, a random id
        Rec draft = startRecord(p.clinic(), p.doctor(), p.patientId());
        copyExpecting404(p.today(), pastRecord(draft.id(), "MEDICINES"));
        copyExpecting404(p.today(), pastRecord(p.today().id(), "MEDICINES"));
        copyExpecting404(p.today(), pastRecord(newId(), "MEDICINES"));
        // another patient's signed record and another clinic's signed record
        Rec stranger = startRecord(p.clinic(), p.doctor());
        Rec strangerFilled = save(stranger, rxSection(typedRx("Stranger medicine")));
        signRecord(p.clinic(), strangerFilled.id());
        copyExpecting404(p.today(), pastRecord(strangerFilled.id(), "MEDICINES"));
        Clinic other = newClinic();
        Staff otherDoctor = addDoctor(other, "General physician");
        Rec foreign = save(startRecord(other, otherDoctor), rxSection(typedRx("Foreign medicine")));
        signRecord(other, foreign.id());
        copyExpecting404(p.today(), pastRecord(foreign.id(), "MEDICINES"));
        // a patient with no signed record at all
        Rec none = startRecord(p.clinic(), p.doctor());
        copyExpecting404(none, LAST_RX);
        assertThat(valueOf(current(p.today()), "rx", "drug")).isNull();
    }

    @Test
    void aSignedTargetIs409AndATargetWhoseLayoutHasNoPrescriptionIs409SectionNotInLayout() throws Exception {
        Pair p = pair();
        signRecord(p.clinic(), p.today().id());
        problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", p.today().token(), LAST_RX, 409, "emr-record-signed");
        // the owner hides the prescription in General Medicine; a record opened later has no Rx section
        ObjectNode layout = layoutInput(department(p.clinic(), "gm"));
        setShown(layout, "rx", false, false);
        publish(p.clinic(), "gm", layout, 200);
        Rec later = startRecord(p.clinic(), p.doctor(), p.patientId());
        assertThat(layoutKeys(later.record())).doesNotContain("rx");
        problemOf(HttpMethod.POST, RECORDS + "/" + later.id() + "/copy-items", later.token(), LAST_RX, 409, "section-not-in-layout");
        assertThat(copy(later, pastRecord(p.earlier().id(), "TESTS"), 200).get("added").asInt()).as("the tests still copy").isEqualTo(2);
    }

    @Test
    void onlyTheOwnDoctorCopiesAndEveryOtherRoleAnotherClinicAndNoLoginAreRefused() throws Exception {
        Pair p = pair();
        Staff other = addDoctor(p.clinic(), "General physician");
        problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", other.token(p.clinic()), LAST_RX, 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(p.clinic(), role);
            problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", staff.token(p.clinic()), LAST_RX, 403, "forbidden");
            problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", staff.tokenClaiming(p.clinic(), "DOCTOR"), LAST_RX, 403, "forbidden");
        }
        problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", anonymous(), LAST_RX, 401, "unauthorized");
        Clinic clinic = newClinic();
        Staff outsider = addDoctor(clinic, "General physician");
        problemOf(HttpMethod.POST, RECORDS + "/" + p.today().id() + "/copy-items", outsider.token(clinic), LAST_RX, 404, "record-not-found");
        assertThat(valueOf(current(p.today()), "rx", "drug")).isNull();
        assertThat(auditCount(p.clinic(), "EMR_ITEMS_COPIED")).isZero();
    }

    // ================= print preferences =================

    @Test
    void thePaperSizeAndLanguageOfThisVisitAreStoredOnTheRecordOnlyAndNullMeansTheClinicDefault() throws Exception {
        Rec rec = newRec();
        String settingsBefore = ok(HttpMethod.GET, "/api/v1/opd/case-paper-settings", rec.clinic().owner(), null, 200).toString();
        long audits = emrAudits(rec.clinic());
        JsonNode saved = put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"MR\",\"paperSize\":\"A5\"}", 200);
        assertThat(saved.get("language").asText()).isEqualTo("MR");
        assertThat(saved.get("paperSize").asText()).isEqualTo("A5");
        assertThat(current(rec).get("printPreferences").get("language").asText()).isEqualTo("MR");
        assertThat(current(rec).get("printPreferences").get("paperSize").asText()).isEqualTo("A5");
        assertThat(row(rec.clinic(), "select print_language, paper_size from opd.emr_record where id = ?::uuid", rec.id())).containsExactly("MR", "A5");
        assertThat(ok(HttpMethod.GET, "/api/v1/opd/case-paper-settings", rec.clinic().owner(), null, 200).toString())
                .as("the clinic's Case paper and Rx setting is never changed").isEqualTo(settingsBefore);
        assertThat(emrAudits(rec.clinic())).as("a print choice is not clinical: no audit").isEqualTo(audits);
        // last write wins, no revision needed; null goes back to the clinic default
        put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"HI\",\"paperSize\":null}", 200);
        assertThat(row(rec.clinic(), "select print_language, paper_size from opd.emr_record where id = ?::uuid", rec.id())).containsExactly("HI", null);
        put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":null,\"paperSize\":null}", 200);
        assertThat(current(rec).get("printPreferences").get("language").isNull()).isTrue();
        // the record row of another clinic is never reachable
        Clinic other = newClinic();
        Staff outsider = addDoctor(other, "General physician");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/print-preferences", outsider.token(other), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 404, "record-not-found");
    }

    @Test
    void aValueOutsideTheListsOrAnUnknownFieldIs400ASignedRecordStillTakesAPrintChoiceAndOnlyTheOwnDoctorMayChooseIt() throws Exception {
        Rec rec = newRec();
        for (String body : new String[] {"{\"language\":\"FR\",\"paperSize\":\"A4\"}", "{\"language\":\"mr\",\"paperSize\":\"A4\"}",
                "{\"language\":\"EN\",\"paperSize\":\"A3\"}", "{\"language\":\"EN\",\"paperSize\":\"a4\"}",
                "{\"language\":\"EN\",\"paperSize\":\"A4\",\"zxqextra\":1}", "{\"language\":5,\"paperSize\":\"A4\"}"}) {
            JsonNode problem = problem(call(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), body), 400);
            assertThat(problem.toString()).doesNotContain("zxqextra");
        }
        assertThat(row(rec.clinic(), "select print_language, paper_size from opd.emr_record where id = ?::uuid", rec.id())).containsExactly(null, null);
        signRecord(rec.clinic(), rec.id());
        put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 200);
        Staff other = addDoctor(rec.clinic(), "General physician");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/print-preferences", other.token(rec.clinic()), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/print-preferences", staff.token(rec.clinic()), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 403, "forbidden");
        }
        problemOf(HttpMethod.PUT, RECORDS + "/" + newId() + "/print-preferences", rec.token(), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 404, "record-not-found");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/print-preferences", anonymous(), "{\"language\":\"EN\",\"paperSize\":\"A4\"}", 401, "unauthorized");
    }

    // ================= the live case paper context =================

    @Test
    void theCasePaperContextHasTheLetterheadDoctorPatientVisitLineClinicDefaultsAndTheTenHeadingsInThreeLanguages() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        ok(HttpMethod.PUT, "/api/v1/opd/case-paper-settings", clinic.owner(), "{\"size\":\"A5\",\"secondLanguage\":\"hi\",\"preprinted\":false,\"caps\":false,"
                + "\"sections\":{\"complaints\":true,\"vitals\":false,\"history\":true,\"exam\":true,\"diagnosis\":true,"
                + "\"inv\":false,\"rx\":true,\"advice\":true,\"next\":false}}", 200);
        Rec rec = startRecord(clinic, doctor);
        put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"MR\",\"paperSize\":null}", 200);
        JsonNode paper = get(RECORDS + "/" + rec.id() + "/case-paper", rec.token());
        assertThat(paper.get("letterhead").get("clinicName").asText()).isEqualTo("Sahaj test clinic");
        assertThat(paper.get("departmentName").asText()).isEqualTo("General Medicine");
        assertThat(paper.get("doctor").get("name").asText()).isEqualTo("Dr Test Doctor");
        assertThat(paper.get("doctor").get("qualification").asText()).isEqualTo("MBBS");
        assertThat(paper.get("doctor").get("registrationNo").asText()).startsWith("REG-");
        assertThat(paper.get("patient").get("name").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(paper.get("patient").get("age").asInt()).isEqualTo(45);
        assertThat(paper.get("patient").get("sex").asText()).isEqualTo("M");
        assertThat(paper.get("patient").has("uhid")).isTrue();
        assertThat(paper.get("visitLine").asText()).isEqualTo("OPD · new visit");
        assertThat(paper.get("clinicDefaults").get("paperSize").asText()).isEqualTo("A5");
        assertThat(paper.get("clinicDefaults").get("language").asText()).isEqualTo("HI");
        assertThat(paper.get("clinicDefaults").get("capitals").asBoolean()).isFalse();
        assertThat(paper.get("chosen").get("language").asText()).isEqualTo("MR");
        assertThat(paper.get("chosen").get("paperSize").isNull()).isTrue();
        JsonNode headings = paper.get("headings");
        assertThat(texts(headings, "heading")).containsExactly("CHIEF_COMPLAINTS", "ALLERGIES", "MEDICAL_HISTORY", "EXAMINATION", "DIAGNOSIS",
                "INVESTIGATIONS", "MEDICINES", "PROCEDURES", "ADVICE", "FOLLOW_UP");
        assertThat(headings.get(0).get("en").asText()).isEqualTo("Chief complaints");
        assertThat(headings.get(6).get("en").asText()).isEqualTo("Medicines ℞");
        for (JsonNode heading : headings) {
            assertThat(heading.get("mr").asText()).isNotBlank();
            assertThat(heading.get("hi").asText()).isNotBlank();
        }
        assertThat(paper.get("signature").get("signed").asBoolean()).isFalse();
        assertThat(paper.get("printBlocked").isNull()).isTrue();
        // ADR-021 and DEC-040: sharing is never available in phase 3
        assertThat(paper.get("share").get("available").asBoolean()).isFalse();
        assertThat(paper.get("share").get("reason").asText()).isEqualTo("Needs the WhatsApp and ABHA services");
    }

    @Test
    void everySectionOfTheRecordsLayoutGetsTheHeadingItsValuesGoUnderAndANoRegistrationNumberIsNull() throws Exception {
        Clinic clinic = newClinic();
        Staff noRegistration = addStaff(clinic, "DOCTOR", "Dr No Registration", "General physician", "MBBS", null);
        Rec rec = startRecord(clinic, noRegistration);
        JsonNode paper = get(RECORDS + "/" + rec.id() + "/case-paper", rec.token());
        assertThat(paper.get("doctor").get("registrationNo").isNull()).as("the page prints 'Reg. no. not set'").isTrue();
        JsonNode sections = paper.get("sectionHeadings");
        assertThat(texts(sections, "sectionKey")).isEqualTo(layoutKeys(rec.record()));
        String[][] expected = {{"complaints", "CHIEF_COMPLAINTS"}, {"hpi", "CHIEF_COMPLAINTS"}, {"allergy", "ALLERGIES"},
                {"history", "MEDICAL_HISTORY"}, {"chronic", "MEDICAL_HISTORY"}, {"vitals", "VITALS_LINE"}, {"exam", "EXAMINATION"},
                {"diagnosis", "DIAGNOSIS"}, {"investigations", "INVESTIGATIONS"}, {"rx", "MEDICINES"}, {"advice", "ADVICE"},
                {"followup", "FOLLOW_UP"}, {"abha", "NONE"}, {"rxformat", "NONE"}, {"attachments", "NONE"}, {"adult_vax", "EXAMINATION"}};
        for (String[] e : expected) {
            String found = null;
            for (JsonNode s : sections) {
                if (e[0].equals(s.get("sectionKey").asText())) {
                    found = s.get("heading").asText();
                }
            }
            assertThat(found).as(e[0]).isEqualTo(e[1]);
        }
    }

    @Test
    void theCasePaperContextIsReadOnlyByTheOwnDoctorAndRefusedToEveryOtherRoleAnotherDoctorAnotherClinicAndNoLogin() throws Exception {
        Rec rec = newRec();
        String path = RECORDS + "/" + rec.id() + "/case-paper";
        get(path, rec.token());
        Staff other = addDoctor(rec.clinic(), "General physician");
        problemOf(HttpMethod.GET, path, other.token(rec.clinic()), null, 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.GET, path, staff.token(rec.clinic()), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
        Clinic clinic = newClinic();
        Staff outsider = addDoctor(clinic, "General physician");
        problemOf(HttpMethod.GET, path, outsider.token(clinic), null, 404, "record-not-found");
        problemOf(HttpMethod.GET, RECORDS + "/" + newId() + "/case-paper", rec.token(), null, 404, "record-not-found");
        // nothing is stored for a print: the record has no copy of the paper
        assertThat(current(rec).get("revision").asLong()).isEqualTo(rec.revision());
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
            assertThat(call(method, path, rec.token(), "{}").andReturn().getResponse().getStatus()).as(method.name()).isIn(403, 404, 405);
        }
    }
}
