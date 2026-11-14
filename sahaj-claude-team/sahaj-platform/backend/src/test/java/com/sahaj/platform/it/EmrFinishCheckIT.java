package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-1 "Check before completing" on the real database (docs/api/opd.yaml 0.6.1, ADR-025 D2): what is missing, what
 * the sign will do (workflow move, follow-up, bill lines, patient record, prints, share), who may ask, and that asking
 * writes nothing for an OPD visit. Test plan: docs/test-plans/SAH-51-68.md (T-51-1.x).
 */
@OnTestDatabase
class EmrFinishCheckIT extends EmrSignSupport {

    private static List<String> blockerKeys(JsonNode check) {
        return texts(check.get("blockers"), "key");
    }

    private static JsonNode blocker(JsonNode check, String key) {
        for (JsonNode b : check.get("blockers")) {
            if (key.equals(b.get("key").asText())) {
                return b;
            }
        }
        throw new AssertionError("no blocker " + key + " in " + blockerKeys(check));
    }

    // ---------------- readiness ----------------

    @Test
    void aRecordWithMissingRequiredSectionsIsNotReadyAndNamesTheSectionsTheClientOpensFirst() throws Exception {
        Rec rec = newRec();
        JsonNode check = check(rec);
        assertThat(check.get("ready").asBoolean()).isFalse();
        assertThat(check.get("encounterType").asText()).isEqualTo("OPD_VISIT");
        JsonNode needed = blocker(check, "STILL_NEEDED");
        assertThat(needed.get("message").asText()).startsWith("Still needed:");
        assertThat(texts(needed.get("sectionKeys"), null)).contains("complaints", "diagnosis", "rx");
    }

    @Test
    void aRecordWithEveryRequiredSectionDoneIsReadyAndShowsTheWorkflowMoveAndThePrescriptionPrint() throws Exception {
        Rec rec = readyRec();
        JsonNode check = check(rec);
        assertThat(check.get("ready").asBoolean()).isTrue();
        assertThat(check.get("blockers")).isEmpty();
        JsonNode stage = check.get("effects").get("stage");
        assertThat(stage.get("outcome").asText()).isEqualTo("WILL_MOVE");
        assertThat(stage.get("to").asText()).isNotBlank().isNotEqualTo("WITH_DOCTOR");
        assertThat(texts(check.get("effects").get("prints"), null)).containsExactly("PRESCRIPTION");
        assertThat(check.get("effects").get("followUp").isNull()).as("no interval chosen").isTrue();
        assertThat(check.get("effects").get("billLines")).isEmpty();
    }

    @Test
    void theShareOptionsAreSwitchedOffWithTheWordsOfTheContract() throws Exception {
        JsonNode share = check(readyRec()).get("share");
        assertThat(share.get("whatsapp").get("available").asBoolean()).isFalse();
        assertThat(share.get("whatsapp").get("reason").asText()).isEqualTo("Needs the WhatsApp service");
        assertThat(share.get("abha").get("available").asBoolean()).isFalse();
        assertThat(share.get("abha").get("reason").asText()).isEqualTo("Needs the ABDM service");
    }

    @Test
    void askingWritesNothingNotTheRecordNotTheQueueCardNotAFollowUpAndNoAuditEntry() throws Exception {
        Rec rec = readyRec();
        Rec withInterval = chooseInterval(rec, "D7");
        long audits = count(rec.clinic(), "select count(*) from platform.audit_event");
        String stageBefore = boardVisit(rec.clinic(), rec.visitId()).get("stage").asText();
        List<String> before = signColumns(rec.clinic(), rec.id());
        for (int i = 0; i < 3; i++) {
            assertThat(check(withInterval).get("ready").asBoolean()).isTrue();
        }
        assertThat(signColumns(rec.clinic(), rec.id())).isEqualTo(before);
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo(stageBefore);
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isZero();
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event")).isEqualTo(audits);
        assertThat(count(rec.clinic(), "select count(*) from opd.appointment")).isZero();
    }

    // ---------------- effects ----------------

    @Test
    void aChosenFollowUpIntervalShowsTheDueDateAndTheFirstFreeSlotOrNone() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        JsonNode followUp = check(rec).get("effects").get("followUp");
        assertThat(followUp.get("interval").asText()).isEqualTo("D7");
        assertThat(followUp.get("dueDate").asText()).isEqualTo(today().plusDays(7).toString());
        JsonNode slot = followUp.get("firstFreeSlot");
        if (!slot.isNull()) {
            assertThat(slot.get("date").asText()).isGreaterThanOrEqualTo(today().plusDays(7).toString());
            assertThat(slot.get("time").asText()).matches("\\d{2}:\\d{2}(:\\d{2})?");
        }
    }

    @Test
    void everyIntervalOfTheMockupGivesItsDate() throws Exception {
        Rec rec = readyRec();
        String[][] cases = {{"D3", today().plusDays(3).toString()}, {"D15", today().plusDays(15).toString()},
                {"M1", today().plusMonths(1).toString()}, {"M3", today().plusMonths(3).toString()}};
        for (String[] c : cases) {
            rec = chooseInterval(rec, c[0]);
            assertThat(check(rec).get("effects").get("followUp").get("dueDate").asText()).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void anAllergyTypedInTheVisitIsShownAsAPatientRecordUpdateWithoutTheText() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = fillRequired(startRecord(clinic, doctor, patientWith(clinic, "Test Patient One", null, null)));
        rec = save(rec, allergySection("typed:Dust allergy"));
        JsonNode check = check(rec);
        JsonNode effect = check.get("effects").get("patientRecord");
        assertThat(texts(effect.get("fields"), null)).containsExactly("ALLERGIES");
        assertThat(effect.get("conflict").asBoolean()).isFalse();
        assertThat(effect.get("removals")).isEmpty();
        assertThat(check.toString()).doesNotContain("Dust allergy");
    }

    @Test
    void aVisitThatChangesNothingOnThePatientHasNoPatientRecordEffect() throws Exception {
        assertThat(check(readyRec()).get("effects").get("patientRecord").isNull()).isTrue();
    }

    @Test
    void anUnreviewedDrugAllergyAlertBlocksWithTheWordsOfTheStory() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = fillRequired(startRecord(clinic, doctor, patientWith(clinic, "Test Patient Two", "Sulfa drugs", null)));
        rec = save(rec, allergySection("SULFA"), rxSection(rxRow(medicineId(SULFA_MEDICINE), SULFA_MEDICINE, "1-0-1", 3, "AFTER_FOOD")));
        JsonNode check = check(rec);
        assertThat(check.get("ready").asBoolean()).isFalse();
        assertThat(blocker(check, "ALLERGY_ALERT").get("message").asText())
                .isEqualTo("Remove the drug or confirm the allergy alert before completing.");
    }

    @Test
    void aBillTodayServiceIsAFinishBillLineAlreadyOnTheBillWithItsTariffPriceAndTheTotal() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        showProcedures(clinic);
        Rec rec = fillRequired(startRecord(clinic, doctor));
        String service = serviceId(clinic, "Dressing check", 150);
        com.fasterxml.jackson.databind.node.ObjectNode item = keyedItem(service, "Dressing check");
        item.put("attr1", "2");
        item.put("attr2", "BILL_TODAY");
        rec = save(rec, oneField("procedures", "procedure", listValue("SERVICES", item)));
        JsonNode effects = check(rec).get("effects");
        assertThat(effects.get("billLines")).hasSize(1);
        JsonNode line = effects.get("billLines").get(0);
        assertThat(line.get("qty").asInt()).isEqualTo(2);
        assertThat(line.get("unitPaise").asLong()).as("the price of one").isEqualTo(15000L);
        assertThat(line.get("amountPaise").asLong()).as("qty x unit price (DEC-051 D4)").isEqualTo(30000L);
        assertThat(line.get("state").asText()).as("the line was made when the service was ticked").isEqualTo("ON_BILL");
        assertThat(effects.get("billTotalPaise").asLong()).isEqualTo(30000L);
    }

    // ---------------- who may ask ----------------

    @Test
    void nurseFrontDeskBillingPharmacistAndCounsellorAreRefusedWith403() throws Exception {
        Rec rec = readyRec();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.POST, checkPath(rec.id()), staff.token(rec.clinic()), "{}", 403, "forbidden");
        }
    }

    @Test
    void anotherDoctorAndAVisitingDoctorAreRefusedOnSomeoneElsesPatientAndTheOwnerWithDoctorDetailsIsNot() throws Exception {
        Clinic bare = newClinic(true, false);
        Rec rec = fillRequired(startRecord(bare, addDoctor(bare, "General physician")));
        Staff other = addDoctor(rec.clinic(), "Dentist");
        Staff visiting = addStaff(rec.clinic(), "VISITING_DOCTOR", "Dr Visiting Person", "Dentist", "BDS", "REG-" + shortId());
        problemOf(HttpMethod.POST, checkPath(rec.id()), other.token(rec.clinic()), "{}", 403, "not-own-patient");
        problemOf(HttpMethod.POST, checkPath(rec.id()), visiting.token(rec.clinic()), "{}", 403, "not-own-patient");
        problemOf(HttpMethod.POST, checkPath(rec.id()), rec.clinic().owner(), "{}", 403, "doctor-details-missing");
        ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
        JsonNode ownerCheck = post(checkPath(rec.id()), rec.clinic().owner(), "{}", 200); // not 403: the owner with doctor details may ask
        assertThat(texts(ownerCheck.get("blockers"), "key")).as("the record is open for editing by its doctor, so the owner is told so")
                .doesNotContain("STILL_NEEDED").contains("HELD_BY_OTHER");
    }

    @Test
    void anotherClinicsRecordIs404AndNoSessionIs401() throws Exception {
        Rec a = readyRec();
        Rec b = newRec();
        problemOf(HttpMethod.POST, checkPath(a.id()), b.token(), "{}", 404, "record-not-found");
        problemOf(HttpMethod.POST, checkPath(java.util.UUID.randomUUID()), a.token(), "{}", 404, "record-not-found");
        problemOf(HttpMethod.POST, checkPath(a.id()), anonymous(), "{}", 401, "unauthorized");
    }

    // ---------------- closed bodies and other refusals ----------------

    @Test
    void choicesThatDoNotApplyToTheRecordOrWithUnknownFieldsAreRefusedWith400() throws Exception {
        Rec rec = readyRec();
        for (String body : new String[] {"{\"dental\":{\"nextInterval\":\"D7\"}}", "{\"physio\":{\"nextSlot\":null}}",
                "{\"erOutcome\":{\"kind\":\"DISCHARGE\"}}", "{\"ipdOrderReasons\":[{\"rowId\":\"" + newId() + "\"}]}",
                "{\"bogus\":1}"}) {
            call(HttpMethod.POST, checkPath(rec.id()), rec.token(), body).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }

    @Test
    void aSignedRecordCannotBeChecked() throws Exception {
        Rec rec = readyRec();
        signOk(rec);
        JsonNode problem = problemOf(HttpMethod.POST, checkPath(rec.id()), rec.token(), "{}", 409, "emr-record-signed");
        assertThat(problem.get("detail").asText()).startsWith("Already signed by Dr.");
    }

    @Test
    void aRecordHeldByAnotherDoctorShowsHeldByOtherAndNamesNoClinicalText() throws Exception {
        Rec rec = readyRec();
        ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
        JsonNode check = post(checkPath(rec.id()), rec.clinic().owner(), "{}", 200);
        assertThat(check.get("ready").asBoolean()).isFalse();
        assertThat(blockerKeys(check)).contains("HELD_BY_OTHER");
        assertThat(blocker(check, "HELD_BY_OTHER").get("message").asText()).startsWith("Open for editing by Dr.");
        List<String> keys = new ArrayList<>(blockerKeys(check));
        assertThat(keys).doesNotContain("STILL_NEEDED");
    }
}
