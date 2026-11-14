package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * DEC-052 item 2 (docs/decisions/DEC-052-emr-design-alignment.md): is the "Prescription print format" section
 * ({@code rxformat}, header text) a required section of the General Medicine starter? The doctor screen draws only
 * Complaints, Diagnosis and Prescription as required; the decision says rxformat stays available but is not required, and an
 * empty header prints the clinic letterhead default. This test fills the drawn required sections on a fresh General Medicine
 * visit and leaves the print format empty, then asks the server whether the record is ready to finish and signs it. The first
 * block is the DEC-052 question (a failure there means a seed change is needed); the second block adds Vitals, which
 * V2026_12_14_1010 (SAH-75) makes a required General Medicine section, and proves the sign works with the header empty.
 */
@OnTestDatabase
class EmrRxFormatNotRequiredIT extends EmrSignSupport {

    @Test
    void theEmptyPrescriptionPrintFormatNeverBlocksFinishingOrSigningAGeneralMedicineVisit() throws Exception {
        Rec rec = newRec();
        assertThat(rec.record().get("layout").get("starterKey").asText()).isEqualTo("gm");
        Rec filled = save(rec, sectionValues("complaints", COMPLAINTS_JSON), sectionValues("diagnosis", DIAGNOSIS_JSON),
                sectionValues("rx", RX_JSON));
        JsonNode rxformat = recordSection(filled.record(), "rxformat");
        JsonNode check = check(filled);
        String facts = "rxformat section status=" + rxformat.get("status").asText()
                + "; record.stillNeeded=" + filled.record().get("stillNeeded")
                + "; finish-check.ready=" + check.get("ready").asBoolean()
                + "; finish-check.blockers=" + check.get("blockers");
        System.out.println("RXFORMAT-RESULT " + facts);
        assertThat(rxformat.get("status").asText()).as("the rxformat section is not required. " + facts).isNotEqualTo("REQUIRED");
        assertThat(texts(filled.record().get("stillNeeded"), null)).as("rxformat is not still needed. " + facts).doesNotContain("rxformat");
        assertThat(check.get("blockers").toString()).as("rxformat blocks nothing. " + facts).doesNotContain("rxformat");
        // with the other required section (Vitals, SAH-75) filled the visit is ready and signs with the print format left empty
        Rec ready = save(filled, spSection("vitals", "VITALS_LOG", VITALS_LOG_JSON.replace("ENTRY_ID", uuid())));
        JsonNode readyCheck = check(ready);
        assertThat(readyCheck.get("ready").asBoolean()).as("ready. stillNeeded=" + ready.record().get("stillNeeded") + " blockers=" + readyCheck.get("blockers")).isTrue();
        signOk(ready);
        assertThat(statusOf(ready.clinic(), ready.id())).as("signed with the header left empty").isEqualTo("SIGNED");
    }
}
