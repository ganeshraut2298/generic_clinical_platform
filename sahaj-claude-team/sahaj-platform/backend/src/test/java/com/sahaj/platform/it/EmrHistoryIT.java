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
 * SAH-50 (ADR-022 D10): the patient's history beside the EMR (OPD part; the IPD scope is in {@link EmrHistoryIpdIT}): the
 * list of earlier records newest first, 8 a page, the type filter and the server-side search, the details of a signed
 * record only, "Open full visit" read-only, and the four key facts of the department. A signed record is made by the one
 * {@code update} the grants allow. Real database as {@code sahaj_app}; throwaway names. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrHistoryIT extends EmrPhase3Support {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** A patient with a signed record 10 days ago (fever, viral fever, three medicines, two tests), an unsigned one and today's. */
    private record Story(Clinic clinic, Staff doctor, UUID patient, Rec signed, Rec draft, Rec current) {
    }

    private Story story() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        Rec first = startRecord(clinic, doctor, patient);
        Rec filled = save(first, complaintsSection(typedItem("Fever")),
                oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", typedItem("Viral fever"))),
                rxSection(rxRow(medicineId(PLAIN_MEDICINE), PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD"), typedRx("Zinc syrup")),
                sectionValues("investigations", "{\"lab_tests\":" + listValue("INVESTIGATIONS", typedItem("CBC"), typedItem("Blood sugar"))
                        + ",\"results\":[{\"value\":\"13\",\"unit\":\"g/dL\",\"reference_range\":\"12-16\"}]}"));
        Rec draft = save(startRecord(clinic, doctor, patient), complaintsSection(typedItem("Cough")),
                rxSection(typedRx("Hiddendrug tablet")));
        Rec current = startRecord(clinic, doctor, patient);
        signRecord(clinic, filled.id(), 10);
        return new Story(clinic, doctor, patient, filled, draft, current);
    }

    private JsonNode list(Rec rec, String query) throws Exception {
        return get(RECORDS + "/" + rec.id() + "/past-visits" + (query == null ? "" : "?" + query), rec.token());
    }

    private static List<String> ids(JsonNode page) {
        return texts(page.get("items"), "id");
    }

    // ================= the list =================

    @Test
    void theListHoldsTheEarlierRecordsNewestFirstWithTitlePlaceAndCountsAndLeavesTheCurrentRecordOut() throws Exception {
        Story s = story();
        JsonNode page = list(s.current(), null);
        assertThat(page.get("pageSize").asInt()).isEqualTo(8);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("hasMore").asBoolean()).isFalse();
        assertThat(page.get("someHidden").asBoolean()).isFalse();
        assertThat(page.get("counts").get("opd").asInt()).isEqualTo(2);
        assertThat(page.get("counts").get("ipd").asInt()).isZero();
        assertThat(ids(page)).containsExactly(s.draft().id(), s.signed().id());
        JsonNode newest = page.get("items").get(0);
        assertThat(newest.get("kind").asText()).isEqualTo("OPD");
        assertThat(newest.get("title").asText()).as("no diagnosis: the first complaint").isEqualTo("Cough");
        assertThat(newest.get("place").asText()).as("contract: Dr. <name> · <department>; the test name already starts with Dr").isEqualTo("Dr. Dr Test Doctor · General Medicine");
        assertThat(newest.get("signed").asBoolean()).isFalse();
        assertThat(newest.get("date").asText()).isEqualTo(LocalDate.now(IST).toString());
        JsonNode old = page.get("items").get(1);
        assertThat(old.get("title").asText()).as("the first active diagnosis").isEqualTo("Viral fever");
        assertThat(old.get("signed").asBoolean()).isTrue();
        assertThat(old.get("date").asText()).as("a signed record is dated by its signing").isEqualTo(LocalDate.now(IST).minusDays(10).toString());
        // a record with nothing in it is titled by its visit
        Rec empty = startRecord(s.clinic(), s.doctor(), s.patient());
        JsonNode again = list(empty, null);
        assertThat(again.get("counts").get("opd").asInt()).isEqualTo(3);
        JsonNode untitled = null;
        for (JsonNode item : again.get("items")) {
            if (item.get("id").asText().equals(s.current().id())) {
                untitled = item;
            }
        }
        assertThat(untitled.get("title").asText()).isEqualTo("OPD visit");
    }

    @Test
    void aFirstVisitHasAnEmptyPageAndAnotherPatientsRecordsNeverAppear() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec first = startRecord(clinic, doctor);
        JsonNode page = list(first, null);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("counts").get("opd").asInt()).isZero();
        assertThat(page.get("counts").get("ipd").asInt()).isZero();
        assertThat(page.get("hasMore").asBoolean()).isFalse();
        Rec other = startRecord(clinic, doctor);
        save(other, complaintsSection(typedItem("Somebody else's complaint")));
        assertThat(list(first, null).get("items")).as("another patient of the same clinic").isEmpty();
        // another clinic's records: the same name, nothing shows
        Clinic b = newClinic();
        Staff doctorB = addDoctor(b, "General physician");
        save(startRecord(b, doctorB), complaintsSection(typedItem("Foreign complaint")));
        assertThat(list(first, null).toString()).doesNotContain("Foreign complaint").doesNotContain("Somebody else");
    }

    @Test
    void theTypeFilterSelectsOpdOrIpdAndAnythingElseIs400() throws Exception {
        Story s = story();
        assertThat(ids(list(s.current(), "type=ALL"))).hasSize(2);
        assertThat(ids(list(s.current(), "type=OPD"))).hasSize(2);
        JsonNode ipd = list(s.current(), "type=IPD");
        assertThat(ipd.get("items")).isEmpty();
        assertThat(ipd.get("counts").get("opd").asInt()).as("the counts are the patient's totals, not the page").isEqualTo(2);
        for (String type : new String[] {"opd", "ALL%20", "XYZ", "OPD,IPD", ""}) {
            assertThat(call(HttpMethod.GET, RECORDS + "/" + s.current().id() + "/past-visits?type=" + type, s.current().token(), null)
                    .andReturn().getResponse().getStatus()).as(type).isEqualTo(400);
        }
    }

    @Test
    void theListIsPagedByEightWithLoadMoreAndAPageOutsideTheRangeIs400() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        List<String> earlier = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            earlier.add(save(startRecord(clinic, doctor, patient), complaintsSection(typedItem("Complaint " + i))).id());
        }
        Rec current = startRecord(clinic, doctor, patient);
        JsonNode first = list(current, null);
        assertThat(first.get("items")).hasSize(8);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("counts").get("opd").asInt()).isEqualTo(9);
        JsonNode second = list(current, "page=1");
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(second.get("page").asInt()).isEqualTo(1);
        List<String> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).containsExactlyInAnyOrderElementsOf(earlier);
        assertThat(all).doesNotHaveDuplicates();
        assertThat(list(current, "page=200").get("items")).isEmpty();
        for (String page : new String[] {"-1", "201", "abc"}) {
            assertThat(call(HttpMethod.GET, RECORDS + "/" + current.id() + "/past-visits?page=" + page, current.token(), null)
                    .andReturn().getResponse().getStatus()).as(page).isEqualTo(400);
        }
    }

    // ================= search =================

    @Test
    void theSearchMatchesTitlePlaceAndTheContentOfSignedRecordsInAnyLetterCaseAndNeverLooksInsideAnUnsignedOne() throws Exception {
        Story s = story();
        assertThat(ids(list(s.current(), "q=viral"))).containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=" + enc("VIRAL FEVER")))).containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=" + enc("  paracetamol  ")))).as("a medicine of the signed record").containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=zinc"))).containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=cbc"))).as("a test of the signed record").containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=fever"))).as("the complaint of the signed record").containsExactly(s.signed().id());
        assertThat(ids(list(s.current(), "q=cough"))).as("the title of the unsigned record").containsExactly(s.draft().id());
        assertThat(ids(list(s.current(), "q=hiddendrug"))).as("an unsigned record is searched by title and place only").isEmpty();
        assertThat(ids(list(s.current(), "q=" + enc("dr test doctor")))).as("the place").hasSize(2);
        JsonNode none = list(s.current(), "q=" + enc("zzzznothingmatches"));
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("counts").get("opd").asInt()).as("counts stay the totals").isEqualTo(2);
        // % and _ are plain characters
        assertThat(list(s.current(), "q=" + enc("%")).get("items")).isEmpty();
        assertThat(list(s.current(), "q=" + enc("vir_l")).get("items")).isEmpty();
        // text over 100 characters is refused and never echoed; exactly 100 is a normal (empty) search
        String tooLong = "q".repeat(101);
        JsonNode problem = problemOf(HttpMethod.GET, RECORDS + "/" + s.current().id() + "/past-visits?q=" + tooLong, s.current().token(), null, 400, "validation");
        assertThat(problem.toString()).doesNotContain(tooLong);
        assertThat(list(s.current(), "q=" + "q".repeat(100)).get("items")).isEmpty();
    }

    // ================= details =================

    @Test
    void theDetailsOfASignedRecordHaveLabelledLinesMedicinesTestsAndResultsAndTheCopyButtonsFollowTheCurrentRecord() throws Exception {
        Story s = story();
        JsonNode detail = get(RECORDS + "/" + s.current().id() + "/past-visits/OPD/" + s.signed().id(), s.current().token());
        assertThat(detail.get("kind").asText()).isEqualTo("OPD");
        assertThat(detail.get("id").asText()).isEqualTo(s.signed().id());
        assertThat(detail.get("signed").asBoolean()).isTrue();
        List<String> labels = texts(detail.get("lines"), "label");
        assertThat(labels).contains("Chief complaints", "Diagnosis", "Investigations advised");
        for (JsonNode line : detail.get("lines")) {
            if ("Chief complaints".equals(line.get("label").asText())) {
                assertThat(line.get("value").asText()).isEqualTo("Fever");
            }
            if ("Diagnosis".equals(line.get("label").asText())) {
                assertThat(line.get("value").asText()).isEqualTo("Viral fever");
            }
        }
        JsonNode medicines = detail.get("medicines");
        assertThat(texts(medicines, "name")).containsExactly(PLAIN_MEDICINE, "Zinc syrup");
        assertThat(medicines.get(0).get("frequency").asText()).isEqualTo("1-0-1");
        assertThat(medicines.get(0).get("days").asInt()).isEqualTo(3);
        assertThat(medicines.get(0).get("when").asText()).isEqualTo("AFTER_FOOD");
        assertThat(detail.get("tests").toString()).contains("CBC").contains("Blood sugar");
        assertThat(detail.get("results")).hasSize(1);
        assertThat(detail.get("results").get(0).get("value").asText()).isEqualTo("13 g/dL");
        assertThat(detail.get("results").get(0).get("abnormal").asBoolean()).as("Abnormal only when the stored row says so").isFalse();
        assertThat(detail.get("canCopyMedicines").asBoolean()).isTrue();
        assertThat(detail.get("canRepeatTests").asBoolean()).isTrue();
        assertThat(detail.get("copyDisabledReason").isNull()).isTrue();
        assertThat(detail.get("open").get("target").asText()).isEqualTo("RECORD");
        assertThat(detail.get("open").get("id").asText()).isEqualTo(s.signed().id());
        // when today's record is signed the copy buttons go and say why
        signRecord(s.clinic(), s.current().id());
        JsonNode closed = get(RECORDS + "/" + s.current().id() + "/past-visits/OPD/" + s.signed().id(), s.current().token());
        assertThat(closed.get("canCopyMedicines").asBoolean()).isFalse();
        assertThat(closed.get("canRepeatTests").asBoolean()).isFalse();
        assertThat(closed.get("copyDisabledReason").asText()).isEqualTo("RECORD_SIGNED");
    }

    @Test
    void anUnsignedRecordHasNoDetailsAndSaysSo() throws Exception {
        Story s = story();
        JsonNode detail = get(RECORDS + "/" + s.current().id() + "/past-visits/OPD/" + s.draft().id(), s.current().token());
        assertThat(detail.get("signed").asBoolean()).isFalse();
        assertThat(detail.get("lines")).isEmpty();
        assertThat(detail.get("medicines")).isEmpty();
        assertThat(detail.get("tests")).isEmpty();
        assertThat(detail.get("canCopyMedicines").asBoolean()).isFalse();
        assertThat(detail.toString()).doesNotContain("Hiddendrug").doesNotContain("Cough");
        assertThat(detail.get("open").get("target").asText()).isEqualTo("RECORD");
    }

    @Test
    void aDetailOfTheWrongKindTheRecordItselfAnotherPatientOrAnotherClinicOrAnUnknownIdIs404PastVisitNotFound() throws Exception {
        Story s = story();
        String base = RECORDS + "/" + s.current().id() + "/past-visits/";
        problemOf(HttpMethod.GET, base + "IPD/" + s.signed().id(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, base + "OPD/" + s.current().id(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, base + "OPD/" + newId(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, base + "XYZ/" + s.signed().id(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, base + "IPD_ROUND/" + s.signed().id(), s.current().token(), null, 404, "past-visit-not-found");
        Rec stranger = save(startRecord(s.clinic(), s.doctor()), complaintsSection(typedItem("Stranger complaint")));
        signRecord(s.clinic(), stranger.id());
        problemOf(HttpMethod.GET, base + "OPD/" + stranger.id(), s.current().token(), null, 404, "past-visit-not-found");
        Clinic other = newClinic();
        Staff otherDoctor = addDoctor(other, "General physician");
        Rec foreign = save(startRecord(other, otherDoctor), complaintsSection(typedItem("Foreign complaint")));
        signRecord(other, foreign.id());
        problemOf(HttpMethod.GET, base + "OPD/" + foreign.id(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, base + "OPD/not-a-uuid", s.current().token(), null, 400, "bad-request");
    }

    // ================= open full visit =================

    @Test
    void openFullVisitReturnsTheSignedRecordReadOnlyWithReasonHistoryWhoeverWroteItAndNeverAnUnsignedOrForeignOne() throws Exception {
        Story s = story();
        JsonNode full = get(RECORDS + "/" + s.current().id() + "/past-records/" + s.signed().id(), s.current().token());
        assertThat(full.get("id").asText()).isEqualTo(s.signed().id());
        assertThat(full.get("status").asText()).isEqualTo("SIGNED");
        assertThat(full.get("readOnly").asBoolean()).isTrue();
        assertThat(full.get("readOnlyReason").asText()).isEqualTo("HISTORY");
        assertThat(valueOf(full, "complaints", "complaint").get("items").get(0).get("text").asText()).isEqualTo("Fever");
        // the record the page is on is not changed by looking at the past one
        assertThat(current(s.current()).get("revision").asLong()).isEqualTo(s.current().revision());
        problemOf(HttpMethod.GET, RECORDS + "/" + s.current().id() + "/past-records/" + s.draft().id(), s.current().token(), null, 404, "past-visit-not-found");
        problemOf(HttpMethod.GET, RECORDS + "/" + s.current().id() + "/past-records/" + newId(), s.current().token(), null, 404, "past-visit-not-found");
        Rec stranger = save(startRecord(s.clinic(), s.doctor()), complaintsSection(typedItem("Stranger complaint")));
        signRecord(s.clinic(), stranger.id());
        problemOf(HttpMethod.GET, RECORDS + "/" + s.current().id() + "/past-records/" + stranger.id(), s.current().token(), null, 404, "past-visit-not-found");
        // a draft of the patient is saved through its own path only: the read-only past record has no write
        assertThat(call(HttpMethod.PUT, RECORDS + "/" + s.current().id() + "/past-records/" + s.signed().id(), s.current().token(), "{}")
                .andReturn().getResponse().getStatus()).isIn(403, 404, 405);
    }

    // ================= key facts =================

    @Test
    void theFourKeyFactsOfGeneralMedicineComeFromThePatientRecordAndTheLatestSignedRecordAndAnEmptyOneIsNullNeverNoneKnown() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = patientWith(clinic, "Sita Patil", "Penicillin", "Diabetes");
        Rec first = startRecord(clinic, doctor, patient);
        Rec today = startRecord(clinic, doctor, patient);
        JsonNode before = get(RECORDS + "/" + today.id() + "/key-facts", today.token());
        assertThat(before.get("starterKey").asText()).isEqualTo("gm");
        assertThat(texts(before.get("facts"), "key")).containsExactly("allergies", "long_term", "surgeries", "regular_meds");
        assertThat(texts(before.get("facts"), "label")).containsExactly("Allergies", "Long-term", "Surgeries", "Regular meds");
        JsonNode allergies = before.get("facts").get(0);
        assertThat(allergies.get("value").asText()).contains("Penicillin");
        assertThat(allergies.get("important").asBoolean()).as("an allergy is flagged important").isTrue();
        assertThat(before.get("facts").get(1).get("value").asText()).contains("Diabetes");
        assertThat(before.get("facts").get(1).get("important").asBoolean()).isFalse();
        assertThat(before.get("facts").get(2).get("value").isNull()).as("nothing recorded is null, never 'None known'").isTrue();
        assertThat(before.get("facts").get(3).get("value").isNull()).isTrue();
        assertThat(before.get("facts").get(3).get("important").asBoolean()).isFalse();
        // the regular medicines are those of the last signed record
        save(first, rxSection(rxRow(medicineId(PLAIN_MEDICINE), PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD"), typedRx("Zinc syrup")));
        JsonNode stillNull = get(RECORDS + "/" + today.id() + "/key-facts", today.token());
        assertThat(stillNull.get("facts").get(3).get("value").isNull()).as("an unsigned record is not a source").isTrue();
        signRecord(clinic, first.id(), 1);
        JsonNode after = get(RECORDS + "/" + today.id() + "/key-facts", today.token());
        assertThat(after.get("facts").get(3).get("value").asText()).contains(PLAIN_MEDICINE).contains("Zinc syrup");
    }

    @Test
    void theKeyFactLabelsFollowTheDepartmentAndTheDepartmentsWithoutOwnLabelsUseGeneralMedicine() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = startRecord(clinic, doctor);
        String[][] expected = {
                {"dental", "Allergies,Medical,Habits,Past dental"}, {"og", "Allergies,Obstetric,Blood group,Risk"},
                {"paeds", "Allergies,Birth,Vaccines,Past illness"}, {"physio", "Diagnosis,Referred by,Medical,Package"},
                {"onco", "Diagnosis,Receptors,Allergies,Scheme"}, {"surgery", "Allergies,Past surgery,Medical,Blood thinners"},
                {"ortho", "Allergies,Long-term,Surgeries,Regular meds"}, {"psych", "Allergies,Long-term,Surgeries,Regular meds"}};
        Rec current = rec;
        for (String[] e : expected) {
            current = switchDepartment(current, e[0]);
            JsonNode facts = get(RECORDS + "/" + current.id() + "/key-facts", current.token());
            assertThat(facts.get("starterKey").asText()).isEqualTo(e[0]);
            assertThat(String.join(",", texts(facts.get("facts"), "label"))).as(e[0]).isEqualTo(e[1]);
            assertThat(facts.get("facts")).hasSize(4);
        }
    }

    // ================= who may read =================

    @Test
    void onlyTheOwnDoctorReadsTheHistoryAndEveryOtherRoleAnotherDoctorAnotherClinicAndNoLoginIsRefused() throws Exception {
        Story s = story();
        String id = s.current().id();
        String[] paths = {RECORDS + "/" + id + "/past-visits", RECORDS + "/" + id + "/past-visits/OPD/" + s.signed().id(),
                RECORDS + "/" + id + "/past-records/" + s.signed().id(), RECORDS + "/" + id + "/key-facts"};
        Staff other = addDoctor(s.clinic(), "General physician");
        Clinic foreign = newClinic();
        Staff outsider = addDoctor(foreign, "General physician");
        for (String path : paths) {
            get(path, s.current().token());
            problemOf(HttpMethod.GET, path, other.token(s.clinic()), null, 403, "not-own-patient");
            for (String role : NOT_DOCTOR_ROLES) {
                Staff staff = addNonDoctor(s.clinic(), role);
                problemOf(HttpMethod.GET, path, staff.token(s.clinic()), null, 403, "forbidden");
                problemOf(HttpMethod.GET, path, staff.tokenClaiming(s.clinic(), "DOCTOR"), null, 403, "forbidden");
            }
            problemOf(HttpMethod.GET, path, outsider.token(foreign), null, 404, "record-not-found");
            problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
        }
        // an owner with doctor details reads the history of any visit
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = startRecord(clinic, doctor);
        ok(HttpMethod.GET, RECORDS + "/" + rec.id() + "/past-visits", clinic.owner(), null, 200);
        problemOf(HttpMethod.GET, RECORDS + "/" + newId() + "/past-visits", s.current().token(), null, 404, "record-not-found");
        // reading writes nothing: no audit entry for any of the four reads
        assertThat(count(s.clinic(), "select count(*) from platform.audit_event where action like 'EMR\\_%' and action not in "
                + "('EMR_RECORD_OPENED', 'EMR_RECORD_DRAFT_SAVED')")).isZero();
    }
}
