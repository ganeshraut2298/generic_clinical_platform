package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SAH-34 D-3a: the three fixed reasons, in the order of the select. */
class EmergencyAccessReasonTest {

    @Test
    void threeReasonsWithTheMockupLabelsInSelectOrder() {
        assertThat(EmergencyAccessReason.values()).extracting(Enum::name)
                .containsExactly("DETERIORATING", "COVERING_BREAK", "DUTY_DOCTOR_ASKED");
        assertThat(EmergencyAccessReason.values()).extracting(EmergencyAccessReason::label).containsExactly(
                "Patient deteriorating, rapid response", "Covering for a colleague on break",
                "Asked to help by the duty doctor");
    }

    @Test
    void aKeyIsMatchedExactly() {
        assertThat(EmergencyAccessReason.ofKey("DETERIORATING")).contains(EmergencyAccessReason.DETERIORATING);
        assertThat(EmergencyAccessReason.ofKey("deteriorating")).isEmpty();
        assertThat(EmergencyAccessReason.ofKey("OTHER")).isEmpty();
        assertThat(EmergencyAccessReason.ofKey(null)).isEmpty();
        assertThat(EmergencyAccessReason.labelOf("COVERING_BREAK")).isEqualTo("Covering for a colleague on break");
    }

    @Test
    void theOpenedEntryTextIsFixedAndHoldsNoName() {
        assertThat(EmergencyAccessReader.openedText(EmergencyAccessReason.DUTY_DOCTOR_ASKED))
                .isEqualTo("Emergency access opened · Asked to help by the duty doctor");
        assertThat(EmergencyAccessReader.REVIEWED_TEXT).isEqualTo("Reviewed emergency access");
    }
}
