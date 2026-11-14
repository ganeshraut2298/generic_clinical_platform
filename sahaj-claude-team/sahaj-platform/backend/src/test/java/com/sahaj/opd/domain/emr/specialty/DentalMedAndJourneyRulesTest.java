package com.sahaj.opd.domain.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.domain.emr.specialty.DentalRules.MedicalAlert;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SAH-75-6 and SAH-75-7 (ADR-026 D6, D7): the Dental medical alerts, the journey form limits and the plan chips. Values
 * from the EMR mockup, not clinically reviewed (ADR-026 H6, H7).
 */
class DentalMedAndJourneyRulesTest {

    private static List<String> keys(List<MedicalAlert> alerts) {
        return alerts.stream().map(MedicalAlert::key).toList();
    }

    @Test
    void noChipsGiveNoBannerAndNoneGivesNoBanner() {
        assertThat(DentalRules.medicalAlerts(Set.of(), Set.of(), Set.of(), Set.of())).isEmpty();
        assertThat(DentalRules.medicalAlerts(Set.of("NONE"), Set.of("NONE"), Set.of("NONE"), Set.of("alcohol"))).isEmpty();
    }

    @Test
    void everyBannerHasItsDrawnTitleAndSentence() {
        List<MedicalAlert> all = DentalRules.medicalAlerts(Set.of("DIABETES", "HIGH_BP", "PREGNANT"),
                Set.of("BLOOD_THINNERS", "BISPHOSPHONATES"), Set.of("PENICILLIN"), Set.of("gutkha"));

        assertThat(keys(all)).containsExactly("PENICILLIN_ALLERGY", "BLEEDING_RISK", "DIABETES", "BP_HEART", "PREGNANT",
                "BISPHOSPHONATES", "TOBACCO_USE");
        assertThat(all.get(0).title()).isEqualTo("Penicillin allergy.");
        assertThat(all.get(0).text()).isEqualTo("Avoid amoxicillin; use a non-penicillin antibiotic.");
        assertThat(all.get(1).text()).isEqualTo("Check INR or stop advice with the physician before extraction or surgery.");
        assertThat(all.get(3).title()).isEqualTo("BP / heart.");
        assertThat(all.get(5).text()).isEqualTo("Risk of jaw bone problems after extraction or implant.");
        assertThat(all.get(6).text()).isEqualTo("Screen the mouth for pre-cancer patches and counsel to quit.");
    }

    @Test
    void bleedingRiskComesFromBloodThinnersAspirinOrABleedingDisorderAndOnlyOnce() {
        assertThat(keys(DentalRules.medicalAlerts(Set.of("BLEEDING_DISORDER"), Set.of(), Set.of(), Set.of())))
                .containsExactly("BLEEDING_RISK");
        assertThat(keys(DentalRules.medicalAlerts(Set.of("BLEEDING_DISORDER"), Set.of("ASPIRIN", "BLOOD_THINNERS"),
                Set.of(), Set.of()))).containsExactly("BLEEDING_RISK");
        assertThat(keys(DentalRules.medicalAlerts(Set.of(), Set.of("STEROIDS", "THYROID_MEDICINE"), Set.of(), Set.of())))
                .isEmpty();
    }

    @Test
    void bpAndHeartIsOneBannerForEitherChipAndTobaccoReadsTheFourHabitsOnly() {
        assertThat(keys(DentalRules.medicalAlerts(Set.of("HEART_DISEASE"), Set.of(), Set.of(), Set.of())))
                .containsExactly("BP_HEART");
        for (String habit : List.of("tobacco_chewing", "gutkha", "paan_betel", "smoking")) {
            assertThat(keys(DentalRules.medicalAlerts(Set.of(), Set.of(), Set.of(), Set.of(habit))))
                    .as(habit).containsExactly("TOBACCO_USE");
        }
        assertThat(keys(DentalRules.medicalAlerts(Set.of(), Set.of(), Set.of(), Set.of("alcohol", "teeth_grinding"))))
                .isEmpty();
    }

    @Test
    void theOtherAllergyChipsGiveNoBannerAndOnlyPenicillinHasADrugClass() {
        assertThat(DentalRules.medicalAlerts(Set.of(), Set.of(), Set.of("LATEX", "NSAIDS", "LOCAL_ANAESTHETIC"), Set.of()))
                .isEmpty();
        assertThat(DentalRules.MED_ALLERGY_CLASS).containsOnlyKeys("PENICILLIN").containsEntry("PENICILLIN", "PENICILLIN");
    }

    @Test
    void theChipListsAreTheDrawnOnesWithNoneLastAndEveryChipHasAWord() {
        assertThat(DentalRules.MED_CONDITIONS).hasSize(9).endsWith("NONE");
        assertThat(DentalRules.MED_MEDICINES).hasSize(6).endsWith("NONE");
        assertThat(DentalRules.MED_ALLERGIES).containsExactly("PENICILLIN", "LOCAL_ANAESTHETIC", "LATEX", "NSAIDS", "NONE");
        for (List<String> group : List.of(DentalRules.MED_CONDITIONS, DentalRules.MED_MEDICINES,
                DentalRules.MED_ALLERGIES)) {
            group.forEach(key -> assertThat(DentalRules.MED_LABELS).containsKey(key));
        }
        assertThat(DentalRules.MED_LABELS.get("NSAIDS")).isEqualTo("Painkillers (NSAIDs)");
        assertThat(DentalRules.MED_LABELS.get("LOCAL_ANAESTHETIC")).isEqualTo("Local anaesthetic");
    }

    @Test
    void noneBesideAnotherChipIsDetectedAndNoneAloneIsNot() {
        assertThat(DentalRules.noneWithOthers(List.of("NONE", "LATEX"))).isTrue();
        assertThat(DentalRules.noneWithOthers(List.of("NONE"))).isFalse();
        assertThat(DentalRules.noneWithOthers(List.of("LATEX", "NSAIDS"))).isFalse();
        assertThat(DentalRules.noneWithOthers(List.of())).isFalse();
    }

    // ---- the journey form ----

    @Test
    void theStageGroupsAreTheSeventeenOfTheContractAndTheLimitsAreTheTablesChecks() {
        assertThat(JourneyRules.STAGE_GROUPS).hasSize(17).startsWith("0", "I", "IA").endsWith("IVA", "IVB", "IVC");
        assertThat(JourneyRules.INTENTS).containsExactly("CURATIVE", "PALLIATIVE");
        assertThat(JourneyRules.SETTINGS).containsExactly("NEOADJUVANT", "ADJUVANT", "DEFINITIVE", "ADVANCED");
        assertThat(JourneyRules.TNM_MAX).isEqualTo(40);
        assertThat(JourneyRules.MARKERS_MAX).isEqualTo(80);
        assertThat(JourneyRules.GENETICS_MAX).isEqualTo(60);
        assertThat(JourneyRules.KI67_MAX).isEqualTo(100);
        assertThat(JourneyRules.LINE_MAX).isEqualTo(10);
        assertThat(JourneyRules.DOXORUBICIN_LIMIT_MGM2).isEqualTo(450);
    }

    @Test
    void aBoardDateIsNotBefore1900AndNotMoreThan365DaysAhead() {
        LocalDate today = LocalDate.of(2026, 11, 14);

        assertThat(JourneyRules.boardDateOk(today.plusDays(365), today)).isTrue();
        assertThat(JourneyRules.boardDateOk(today.plusDays(366), today)).isFalse();
        assertThat(JourneyRules.boardDateOk(LocalDate.of(1900, 1, 1), today)).isTrue();
        assertThat(JourneyRules.boardDateOk(LocalDate.of(1899, 12, 31), today)).isFalse();
        assertThat(JourneyRules.boardDateOk(today.minusYears(1), today)).isTrue();
    }

    @Test
    void theChipLabelsCountEachRegimenSeparatelyAndACycleWithNoRegimenReadsCAndItsNumber() {
        List<String> regimens = new java.util.ArrayList<>(List.of("AC", "AC", "AC", "AC", "PACLI_WEEKLY", "PACLI_3W"));
        regimens.add(null);

        assertThat(JourneyRules.cycleLabels(regimens)).containsExactly("AC1", "AC2", "AC3", "AC4", "P1", "P2", "C7");
        assertThat(JourneyRules.cycleLabels(List.of("TC", "TC"))).containsExactly("TC1", "TC2");
        assertThat(JourneyRules.cycleLabels(List.of())).isEmpty();
    }

    @Test
    void aRegimenOutsideTheKnownThreeUsesItsOwnKeyAndALabelIsCappedAtEightCharacters() {
        assertThat(JourneyRules.chipLabel("FOLFOX")).isEqualTo("FOLFOX");
        assertThat(JourneyRules.cycleLabels(List.of("ABCDEFGHIJ"))).containsExactly("ABCDEFGH");
    }

    @Test
    void thePlanTitleIsTheRunsOfConsecutiveRegimensAndNullWhenNoCycleHasOne() {
        assertThat(JourneyRules.planTitle(List.of("AC", "AC", "AC", "AC", "PACLI_WEEKLY", "PACLI_WEEKLY", "PACLI_3W",
                "PACLI_3W"))).isEqualTo("AC ×4 → Paclitaxel ×4");
        assertThat(JourneyRules.planTitle(List.of("TC", "TC", "TC", "TC", "TC", "TC"))).isEqualTo("TC ×6");
        assertThat(JourneyRules.planTitle(java.util.Arrays.asList(null, null))).isNull();
        assertThat(JourneyRules.planTitle(List.of())).isNull();
        assertThat(JourneyRules.planTitle(java.util.Arrays.asList("AC", null, "AC"))).isEqualTo("AC ×1 → AC ×1");
    }
}
