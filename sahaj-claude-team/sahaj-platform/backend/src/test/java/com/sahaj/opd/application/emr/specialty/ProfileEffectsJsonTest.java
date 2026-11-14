package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.finish.FinishModels.Effects;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBillLine;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishCheck;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import com.sahaj.opd.application.emr.finish.FinishModels.Shares;
import com.sahaj.opd.application.emr.finish.FinishModels.SignOutcome;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * docs/api/opd.yaml 0.6.1 (EmrFinishProfileEffects): the profile block is written flat with a {@code profile} discriminator
 * (oneOf, one record per profile), carries the fields of its schema (also when null) and nothing the sign step uses privately.
 * Values are made-up test data.
 */
class ProfileEffectsJsonTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private static JsonNode json(Object value) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void generalMedicineIsJustTheDiscriminator() throws Exception {
        JsonNode node = json(new ProfileEffects.Gm());

        assertThat(names(node)).containsExactly("profile");
        assertThat(node.get("profile").asText()).isEqualTo("gm");
    }

    @Test
    void theDentalBlockIsFlatWithItsSchemaFieldsAndNoBillLinesOrFollowUpDate() throws Exception {
        UUID rowId = UUID.randomUUID();
        ProfileEffects.Dental dental = new ProfileEffects.Dental(
                List.of(new ProfileEffects.DentalRow(rowId, "36 · Root canal treatment", new ProfileEffects.Sitting(2, 3),
                        null)), 125000, false, 1, "D7", LocalDate.of(2026, 11, 20), new FreeSlot(LocalDate.of(2026, 11, 21),
                "17:00"), List.of(new FinishBillLine("x", 1, 100, 100, "NEW", "DENTAL_PLAN")));

        JsonNode node = json(dental);

        assertThat(names(node)).containsExactlyInAnyOrder("profile", "doneRows", "estimateTotalPaise", "allRowsDone",
                "carriedRowCount", "nextSittingInterval", "nextSittingDate", "nextSittingSlot");
        assertThat(node.get("profile").asText()).isEqualTo("dental");
        assertThat(node.get("doneRows").get(0).get("rowId").asText()).isEqualTo(rowId.toString());
        assertThat(node.get("doneRows").get(0).get("sitting").get("no").asInt()).isEqualTo(2);
        assertThat(node.get("doneRows").get(0).get("sitting").get("of").asInt()).isEqualTo(3);
        assertThat(node.get("doneRows").get(0).get("amountPaise").isNull()).isTrue();
        assertThat(node.get("nextSittingDate").asText()).isEqualTo("2026-11-20");
        assertThat(node.get("nextSittingSlot").get("time").asText()).isEqualTo("17:00");
    }

    @Test
    void everyOtherBlockHasItsDiscriminatorAndAllItsFieldsEvenWhenNull() throws Exception {
        JsonNode og = json(new ProfileEffects.Og("GYNAE", null, null, null, null, null, List.of(), null));
        assertThat(names(og)).containsExactlyInAnyOrder("profile", "mode", "ancVisitNo", "gestationalAge", "bpSystolic",
                "bpDiastolic", "hbGdl", "orderedLabels", "nextAncWeeks");
        assertThat(og.get("profile").asText()).isEqualTo("og");
        assertThat(og.get("ancVisitNo").isNull()).isTrue();

        JsonNode paeds = json(new ProfileEffects.Paeds(null, null, List.of(), null));
        assertThat(names(paeds)).containsExactlyInAnyOrder("profile", "weightKg", "weightNote", "vaccinesGivenToday",
                "nextDue");
        assertThat(paeds.get("profile").asText()).isEqualTo("paeds");

        JsonNode physio = json(new ProfileEffects.Physio(null, null, null, null, false, null, null));
        assertThat(names(physio)).containsExactlyInAnyOrder("profile", "courseId", "sessionNo", "packageSize",
                "nextSessionNo", "packageComplete", "painScore", "previousPainScore");
        assertThat(physio.get("profile").asText()).isEqualTo("physio");

        JsonNode onco = json(new ProfileEffects.Onco(2, "STOP", "NONE", null, null, null, null, null, false));
        assertThat(names(onco)).containsExactlyInAnyOrder("profile", "cycleNo", "decision", "orderStatus", "nextCycleNo",
                "nextCycleLabel", "nextCycleDate", "nextCycleSittingId", "cycleChargePaise", "followUpFromNextCycle");
        assertThat(onco.get("profile").asText()).isEqualTo("onco");

        JsonNode surgery = json(new ProfileEffects.Surgery("BOOKED", "Test procedure", LocalDate.of(2026, 11, 18),
                "OT 2 · 10:00", "DAY_CARE", false, null));
        assertThat(names(surgery)).containsExactlyInAnyOrder("profile", "bookState", "procedureText", "otDate", "slotText",
                "admission", "admissionRequested", "fitnessText");
        assertThat(surgery.get("profile").asText()).isEqualTo("surgery");
        assertThat(surgery.get("otDate").asText()).isEqualTo("2026-11-18");
    }

    @Test
    void theFinishCheckCarriesTheBlockUnderEffectsProfileAndThePrintsAsKeys() throws Exception {
        Effects effects = new Effects(null, null, List.of(), 0, null, List.of(), null, null,
                new ProfileEffects.Surgery("BOOKED", null, null, null, null, true, null),
                List.of(FinishPrint.PRESCRIPTION, FinishPrint.ADMISSION_LETTER, FinishPrint.PREOP_SHEET));
        FinishCheck check = new FinishCheck(true, List.of(), "OPD_VISIT", effects, List.of(), Shares.unavailable());

        JsonNode node = json(check);

        assertThat(node.get("effects").get("profile").get("profile").asText()).isEqualTo("surgery");
        assertThat(node.get("effects").get("profile").get("admissionRequested").asBoolean()).isTrue();
        assertThat(node.get("effects").get("prints")).hasSize(3);
        assertThat(node.get("effects").get("prints").get(0).asText()).isEqualTo("PRESCRIPTION");
        assertThat(node.get("effects").get("prints").get(2).asText()).isEqualTo("PREOP_SHEET");
    }

    @Test
    void aRecordWithoutAProfileHasANullBlock() throws Exception {
        Effects effects = new Effects(null, null, List.of(), 0, null, List.of(), null, null, null, List.of());

        JsonNode node = json(effects);

        assertThat(node.has("profile")).isTrue();
        assertThat(node.get("profile").isNull()).isTrue();
        assertThat(node.get("prints")).isEmpty();
    }

    @Test
    void theSignOutcomeAnswerHasPrintsAndTheStoredCopyHasNone() throws Exception {
        SignOutcome stored = new SignOutcome(null, null, 0, 0, List.of(), null, null, "NOT_CHANGED", null);

        assertThat(json(stored).has("prints")).isFalse();
        JsonNode answered = json(stored.withPrints(List.of(FinishPrint.PRESCRIPTION, FinishPrint.CYCLE_RECORD)));
        assertThat(answered.get("prints")).hasSize(2);
        assertThat(answered.get("prints").get(1).asText()).isEqualTo("CYCLE_RECORD");
        assertThat(answered.get("stage").isNull()).isTrue();
        // an older stored result (no prints) still reads
        SignOutcome read = MAPPER.readValue(MAPPER.writeValueAsString(stored), SignOutcome.class);
        assertThat(read.prints()).isNull();
        assertThat(read.patientRecord()).isEqualTo("NOT_CHANGED");
    }

    @Test
    void thePrivateFactsOfTheSignStepAreMethodsNotFields() {
        ProfileEffects.Dental dental = new ProfileEffects.Dental(List.of(), 0, false, 1, "D7", LocalDate.of(2026, 11, 20),
                null, List.of(new FinishBillLine("x", 1, 100, 100, "NEW", "DENTAL_PLAN")));
        ProfileEffects.Onco onco = new ProfileEffects.Onco(1, null, "NONE", 2, "NEXT_CHEMO", LocalDate.of(2026, 12, 4), null,
                null, true);

        assertThat(dental.billLines()).hasSize(1);
        assertThat(dental.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(onco.followUpDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(onco.prints()).containsExactly(FinishPrint.CYCLE_RECORD, FinishPrint.CHEMO_CALENDAR);
        assertThat(new ProfileEffects.Gm().prints()).isEmpty();
        assertThat(dental.toString()).doesNotContain("Root").isEqualTo("ProfileEffects[dental]");
    }
}
