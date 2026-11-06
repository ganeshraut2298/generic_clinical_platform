package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-33 the response actions of the select "What was done" (ADR-007 D1). */
class ResponseActionTest {

    @Test
    void theFiveDoctorChoicesAreInTheOrderOfTheMockupSelectWithTheirLabels() {
        List<ResponseAction> choices = ResponseAction.doctorChoices();

        assertThat(choices).extracting(Enum::name).containsExactly("BEDSIDE_REVIEW", "TREATMENT_CHANGED",
                "MOVED_ICU_HDU", "RAPID_RESPONSE", "NO_CHANGE");
        assertThat(choices).extracting(ResponseAction::label).containsExactly("Reviewed at the bedside",
                "Treatment changed", "Moved to ICU or HDU", "Rapid response team called",
                "Reviewed, no change needed");
    }

    @Test
    void closedAtDischargeIsNeverADoctorChoiceAndIsNeverAcceptedFromACode() {
        assertThat(ResponseAction.CLOSED_AT_DISCHARGE.doctorChoice()).isFalse();
        assertThat(ResponseAction.doctorChoices()).doesNotContain(ResponseAction.CLOSED_AT_DISCHARGE);
        assertThat(ResponseAction.doctorChoice("CLOSED_AT_DISCHARGE")).isEmpty();
        assertThat(ResponseAction.CLOSED_AT_DISCHARGE.label()).isEqualTo("Closed at discharge");
    }

    @Test
    void aDoctorCodeIsMatchedExactly() {
        assertThat(ResponseAction.doctorChoice("NO_CHANGE")).contains(ResponseAction.NO_CHANGE);
        assertThat(ResponseAction.doctorChoice("no_change")).isEmpty();
        assertThat(ResponseAction.doctorChoice(" NO_CHANGE")).isEmpty();
        assertThat(ResponseAction.doctorChoice(null)).isEmpty();
        assertThat(ResponseAction.doctorChoice("")).isEmpty();
        assertThat(ResponseAction.doctorChoice("RAPID")).isEmpty();
    }

    @Test
    void aStoredCodeIncludingClosedAtDischargeIsFoundForDisplay() {
        assertThat(ResponseAction.ofStored("CLOSED_AT_DISCHARGE")).contains(ResponseAction.CLOSED_AT_DISCHARGE);
        assertThat(ResponseAction.ofStored("BEDSIDE_REVIEW")).contains(ResponseAction.BEDSIDE_REVIEW);
        assertThat(ResponseAction.ofStored("OTHER")).isEmpty();
        assertThat(ResponseAction.ofStored(null)).isEmpty();
    }
}
