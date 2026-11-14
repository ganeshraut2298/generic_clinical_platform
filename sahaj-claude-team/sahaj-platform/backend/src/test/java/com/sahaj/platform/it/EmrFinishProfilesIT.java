package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * SAH-69-3, SAH-61-4, SAH-62-4, SAH-63-3, SAH-70-2, SAH-71-3 (docs/api/opd.yaml 0.6.1, ADR-025 D7 "Profile effects", D19): the
 * finish check of each of the seven specialty profiles carries its own named effects block ({@code effects.profile.profile}
 * is the profile key with that profile's keys) and the list of documents the client prints after the sign; a department with
 * no profile answers no block. The check shows the effects whether or not the record is ready, so no specialty record has to be
 * filled in here. The blocks of the Emergency assessment and the IPD round are null (EmrEmergencyFinishIT,
 * EmrIpdRoundFinishIT). Test plan: docs/test-plans/SAH-51-68.md (T-69-3.x, T-70-2.x, T-71-3.x).
 */
@OnTestDatabase
class EmrFinishProfilesIT extends EmrSignSupport {

    private JsonNode effectsOf(Rec rec, String choices) throws Exception {
        return post(checkPath(rec.id()), rec.token(), choices, 200).get("effects");
    }

    private static JsonNode profileOf(JsonNode effects) {
        return effects.get("profile");
    }

    private static List<String> prints(JsonNode effects) {
        return texts(effects.get("prints"), null);
    }

    @Test
    void generalMedicineHasTheBareBlockAndPrintsThePrescription() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("gm"), "{}");
        assertThat(profileOf(effects).get("profile").asText()).isEqualTo("gm");
        assertThat(prints(effects)).containsExactly("PRESCRIPTION");
    }

    @Test
    void dentalHasTheDoneRowsTheEstimateTheCarriedRowsAndNoNextSittingUntilARowIsDone() throws Exception {
        Rec rec = specialtyRec("dental");
        JsonNode effects = effectsOf(rec, "{}");
        JsonNode dental = profileOf(effects);
        assertThat(dental.get("profile").asText()).isEqualTo("dental");
        assertThat(dental.get("doneRows").isArray()).isTrue();
        assertThat(dental.get("estimateTotalPaise").isNumber()).isTrue();
        assertThat(dental.get("allRowsDone").isBoolean()).isTrue();
        assertThat(dental.get("carriedRowCount").isNumber()).isTrue();
        assertThat(prints(effects)).containsExactly("PRESCRIPTION");
        // the dental choice of the next sitting is accepted for a dental visit and refused when it is not on the list
        effectsOf(rec, "{\"dental\":{\"nextInterval\":\"D7\"}}");
        call(HttpMethod.POST, checkPath(rec.id()), rec.token(), "{\"dental\":{\"nextInterval\":\"D5\"}}")
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void obstetricsAndGynaecologyHasItsModeAndTheOrderedLabels() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("og"), "{}");
        JsonNode og = profileOf(effects);
        assertThat(og.get("profile").asText()).isEqualTo("og");
        assertThat(og.get("orderedLabels").isArray()).isTrue();
        assertThat(prints(effects)).containsExactly("PRESCRIPTION");
    }

    @Test
    void paediatricsHasTheVaccinesGivenToday() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("paeds"), "{}");
        JsonNode paeds = profileOf(effects);
        assertThat(paeds.get("profile").asText()).isEqualTo("paeds");
        assertThat(paeds.get("vaccinesGivenToday").isArray()).isTrue();
        assertThat(prints(effects)).as("a vaccination card is a button, not a print at the sign").containsExactly("PRESCRIPTION");
    }

    @Test
    void physiotherapyHasThePackageFactsAndTheSlotChoiceIsAcceptedOnlyForAPhysiotherapyVisit() throws Exception {
        Rec rec = specialtyRec("physio");
        JsonNode effects = effectsOf(rec, "{}");
        JsonNode physio = profileOf(effects);
        assertThat(physio.get("profile").asText()).isEqualTo("physio");
        assertThat(physio.get("packageComplete").isBoolean()).isTrue();
        assertThat(prints(effects)).containsExactly("PRESCRIPTION");
        effectsOf(rec, "{\"physio\":{\"nextSlot\":null}}");
        Rec gm = specialtyRec("gm");
        call(HttpMethod.POST, checkPath(gm.id()), gm.token(), "{\"physio\":{\"nextSlot\":null}}").andExpect(MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void oncologyHasTheCycleFactsAndPrintsThePrescriptionAndTheCycleRecordButNoCalendarWithoutANextCycleDate() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("onco"), "{}");
        JsonNode onco = profileOf(effects);
        assertThat(onco.get("profile").asText()).isEqualTo("onco");
        assertThat(onco.get("followUpFromNextCycle").isBoolean()).isTrue();
        assertThat(prints(effects)).containsExactly("PRESCRIPTION", "CYCLE_RECORD");
    }

    @Test
    void surgeryHasTheBookingFactsAndPrintsOnlyThePrescriptionUntilTheOtIsBooked() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("surgery"), "{}");
        JsonNode surgery = profileOf(effects);
        assertThat(surgery.get("profile").asText()).isEqualTo("surgery");
        assertThat(surgery.get("admissionRequested").isBoolean()).isTrue();
        assertThat(prints(effects)).as("the admission letter and the pre-op sheet wait for a booked OT").containsExactly("PRESCRIPTION");
    }

    @Test
    void aDepartmentWithNoProfileHasNoProfileBlockAndStillPrintsThePrescription() throws Exception {
        JsonNode effects = effectsOf(specialtyRec("ortho"), "{}");
        JsonNode profile = effects.path("profile");
        assertThat(profile.isNull() || profile.isMissingNode()).isTrue();
        assertThat(prints(effects)).containsExactly("PRESCRIPTION");
    }

    @Test
    void theProfileBlockNeverHoldsTheCompleteRecordOrAClinicalNoteAndTheSameBlockIsAskedForTwiceWithoutWritingAnything() throws Exception {
        Rec rec = specialtyRec("dental");
        long audits = count(rec.clinic(), "select count(*) from platform.audit_event");
        JsonNode first = effectsOf(rec, "{}");
        JsonNode second = effectsOf(rec, "{}");
        assertThat(second).isEqualTo(first);
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event")).isEqualTo(audits);
        assertThat(first.toString()).doesNotContain("Viral fever").doesNotContain("Fever");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }
}
