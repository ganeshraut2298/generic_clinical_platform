package com.sahaj.opd.application.emr.finish;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-75-6 (OQ-75-16, ADR-026 D6, ADR-025 D13 extended): the allergy text the visit would write to the patient record is the
 * Allergies section's items and the allergy chips of the Dental "Medical history and alerts" card, de-duplicated ignoring
 * case; the card's None chip is the token "None", so the H25 rule decides whether it is an explicit None. Allergy words are
 * clinical data: the test uses sample words only.
 */
class DentalAllergyWriteBackTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EmrRecordDocument document(String allergySection, String dentalAllergies) {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        if (allergySection != null) {
            document.put("allergy", object(allergySection), false, Instant.EPOCH, UUID.randomUUID());
        }
        if (dentalAllergies != null) {
            document.put("dental_medhx", object("{'specialty':{'component':'DENTAL_MED_HISTORY','schemaVersion':1,"
                    + "'conditions':[],'medicines':[],'allergies':" + dentalAllergies + ",'reviewedWithPatient':true}}"),
                    false, Instant.EPOCH, UUID.randomUUID());
        }
        return document;
    }

    @Test
    void theChipsAreWrittenAsTheirDrawnWordsInTheDrawnOrder() {
        EmrRecordDocument document = document(null, "['PENICILLIN','LOCAL_ANAESTHETIC','LATEX','NSAIDS']");

        assertThat(EmrSignService.allergyTexts(document)).containsExactly("Penicillin", "Local anaesthetic", "Latex",
                "Painkillers (NSAIDs)");
    }

    @Test
    void anAllergyInBothPlacesIsWrittenOnceIgnoringCaseAndTheSectionsWordsComeFirst() {
        EmrRecordDocument document = document("{'drug_allergy':[{'itemKey':'PENICILLIN','text':'penicillin',"
                + "'newToList':false}],'food_other':'Peanuts'}", "['PENICILLIN','LATEX']");

        assertThat(EmrSignService.allergyTexts(document)).containsExactly("penicillin", "Peanuts", "Latex");
    }

    @Test
    void theNoneChipIsTheNoneTokenSoItIsAnExplicitNoneOnlyWhenNothingElseIsRecorded() {
        List<String> alone = EmrSignService.allergyTexts(document(null, "['NONE']"));
        List<String> withTyped = EmrSignService.allergyTexts(document("{'food_other':'Peanuts'}", "['NONE']"));

        assertThat(alone).containsExactly("None");
        assertThat(alone).allMatch(EmrSignService::isNoneToken);
        assertThat(withTyped).containsExactly("Peanuts", "None");
        assertThat(withTyped.stream().filter(t -> !EmrSignService.isNoneToken(t)).toList()).containsExactly("Peanuts");
    }

    @Test
    void noneInTheCardAndNoneInTheSectionAreBothNoneTokensSoNothingRealIsWritten() {
        EmrRecordDocument document = document("{'drug_allergy':[{'itemKey':null,'text':'NKDA','newToList':true}]}",
                "['NONE']");

        List<String> texts = EmrSignService.allergyTexts(document);

        assertThat(texts).hasSize(2).first().isEqualTo("NKDA");
        assertThat(texts.stream().filter(t -> !EmrSignService.isNoneToken(t)).toList()).isEmpty();
    }

    @Test
    void aRecordWithNeitherWritesNothingAndAnotherComponentInTheSectionIsIgnored() {
        assertThat(EmrSignService.allergyTexts(document(null, null))).isEmpty();
        assertThat(EmrSignService.allergyTexts(document(null, "[]"))).isEmpty();
        EmrRecordDocument odd = EmrRecordDocument.empty(MAPPER);
        odd.put("dental_medhx", object("{'specialty':{'component':'GOALS','allergies':['LATEX']}}"), false, Instant.EPOCH,
                UUID.randomUUID());
        assertThat(EmrSignService.allergyTexts(odd)).isEmpty();
    }
}
