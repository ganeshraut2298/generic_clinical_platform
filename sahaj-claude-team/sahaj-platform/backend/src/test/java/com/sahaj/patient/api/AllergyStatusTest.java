package com.sahaj.patient.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllergyStatusTest {

    private static final String[] NONE_WORDS = {"none", "nil", "nka", "nkda", "none known", "no known allergy",
        "no known allergies", "no known drug allergy", "no known drug allergies"};

    @Test
    void everyNoneWordOfTheSharedListIsNoneRecorded() {
        for (String word : NONE_WORDS) {
            assertThat(AllergyStatus.of(word)).as(word).isEqualTo(AllergyStatus.NONE_RECORDED);
            assertThat(AllergyStatus.isNoneWord(word)).as(word).isTrue();
        }
    }

    @Test
    void caseOuterSpaceAndTrailingFullStopsAreIgnored() {
        for (String text : new String[] {"None", "NKDA", "  No Known Allergy  ", "No known allergy.", "nil..",
            "NONE KNOWN. ", "No Known Drug Allergies"}) {
            assertThat(AllergyStatus.of(text)).as(text).isEqualTo(AllergyStatus.NONE_RECORDED);
        }
    }

    @Test
    void nullAndBlankTextIsNotRecorded() {
        assertThat(AllergyStatus.of(null)).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(AllergyStatus.of("")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(AllergyStatus.of("   ")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(AllergyStatus.of(" \t")).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(AllergyStatus.isNoneWord(null)).isFalse();
    }

    @Test
    void anyOtherTextIsRecordedEvenWhenItMentionsANoneWord() {
        for (String text : new String[] {"Penicillin", "Penicillin; none known otherwise", "None of the usual drugs",
            "no known allergy to penicillin", "Dust, no known drug allergy", "Not recorded", "Unknown", "?"}) {
            assertThat(AllergyStatus.of(text)).as(text).isEqualTo(AllergyStatus.RECORDED);
        }
    }

    @Test
    void thePatientClinicalRecordWorksOutItsStatusFromTheStoredTextEveryConstructorShape() {
        UUID id = UUID.randomUUID();
        assertThat(new PatientClinical(id, "UH-1", "A B", 30, "F", "No known allergy").allergyStatus())
                .isEqualTo(AllergyStatus.NONE_RECORDED);
        assertThat(new PatientClinical(id, "UH-1", "A B", 30, "F", "Peanuts", "Asthma").allergyStatus())
                .isEqualTo(AllergyStatus.RECORDED);
        assertThat(new PatientClinical(id, "UH-1", "A B", 30, "F", null).allergyStatus())
                .isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(new PatientClinical(id, "UH-1", "A B", 30, "F", "  ", null, null).allergyStatus())
                .isEqualTo(AllergyStatus.NOT_RECORDED);
    }
}
