package com.sahaj.patient.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Security review L5: the default record text would print the name and the mobile number. */
class PatientSummaryTest {

    @Test
    void toStringPrintsTheIdAndMergedFlagOnly() {
        UUID id = UUID.randomUUID();

        String text = new PatientSummary(id, "Asha Rao", 30, "F", "9000000001", true).toString();

        assertThat(text).isEqualTo("PatientSummary[id=" + id + ", merged=true]");
        assertThat(text).doesNotContain("Asha").doesNotContain("9000000001");
        assertThat(new PatientSummary(id, "Asha Rao", 30, "F", "9000000001").toString())
                .doesNotContain("Asha").doesNotContain("9000000001").contains("merged=false");
    }

    @Test
    void toStringNeverPrintsTheUhid() {
        UUID id = UUID.randomUUID();

        String text = new PatientSummary(id, "Asha Rao", 30, "F", null, false, "UH-000042").toString();

        assertThat(text).isEqualTo("PatientSummary[id=" + id + ", merged=false]");
        assertThat(text).doesNotContain("UH-000042").doesNotContain("Asha");
    }

    @Test
    void theConstructorsWithoutAUhidLeaveItNullAndTheFullOneKeepsIt() {
        UUID id = UUID.randomUUID();

        assertThat(new PatientSummary(id, "Asha Rao", 30, "F", "9000000001").uhid()).isNull();
        assertThat(new PatientSummary(id, "Asha Rao", 30, "F", "9000000001", true).uhid()).isNull();
        assertThat(new PatientSummary(id, "Asha Rao", 30, "F", "9000000001", true, "UH-000042").uhid())
                .isEqualTo("UH-000042");
    }
}
