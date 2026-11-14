package com.sahaj.opd.domain.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.domain.emr.specialty.DentalRules.PlanRow;
import com.sahaj.opd.domain.emr.specialty.VitalsRules.Reading;
import com.sahaj.patient.api.PatientAge;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C-1, C-8 vitals flags and C-3 dental rules; every number is tagged (DEC-039, not clinically reviewed). */
class VitalsAndDentalRulesTest {

    private static Reading gm(Integer sys, Integer dia, Integer pulse, Integer spo2, Double temp, Integer rr) {
        return new Reading(sys, dia, pulse, spo2, temp, rr);
    }

    private static List<String> labels(List<Flag> flags) {
        return flags.stream().map(f -> f.field() + "=" + f.label()).toList();
    }

    @Test
    void tagWordingIsTheAcceptedOne() {
        assertThat(RuleTag.TAG).isEqualTo(
                "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed");
    }

    @Test
    void gmBpHighAt140Or90Tagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(140, 80, null, null, null, null)))).containsExactly("bp=High");
        assertThat(labels(VitalsRules.generalMedicine(gm(120, 90, null, null, null, null)))).containsExactly("bp=High");
        assertThat(VitalsRules.generalMedicine(gm(139, 89, null, null, null, null))).isEmpty();
    }

    @Test
    void gmBpLowBelow90SystolicTagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(89, 60, null, null, null, null)))).containsExactly("bp=Low");
        assertThat(VitalsRules.generalMedicine(gm(90, 60, null, null, null, null))).isEmpty();
    }

    @Test
    void gmPulseAbove100BelowSixtyTagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, 101, null, null, null)))).containsExactly("pulse=High");
        assertThat(VitalsRules.generalMedicine(gm(null, null, 100, null, null, null))).isEmpty();
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, 59, null, null, null)))).containsExactly("pulse=Low");
        assertThat(VitalsRules.generalMedicine(gm(null, null, 60, null, null, null))).isEmpty();
    }

    @Test
    void gmSpo2Below95IsLowNotBelow94Tagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, null, 94, null, null)))).containsExactly("spo2=Low");
        assertThat(VitalsRules.generalMedicine(gm(null, null, null, 95, null, null))).isEmpty();
    }

    @Test
    void gmTemperatureFeverAt99Point5FLowBelow96Tagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, null, null, 99.5, null))))
                .containsExactly("temperature=Fever");
        assertThat(VitalsRules.generalMedicine(gm(null, null, null, null, 99.4, null))).isEmpty();
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, null, null, 95.9, null))))
                .containsExactly("temperature=Low");
        assertThat(VitalsRules.generalMedicine(gm(null, null, null, null, 96.0, null))).isEmpty();
    }

    @Test
    void gmRespRateAbove20Tagged() {
        assertThat(labels(VitalsRules.generalMedicine(gm(null, null, null, null, null, 21)))).containsExactly("respRate=High");
        assertThat(VitalsRules.generalMedicine(gm(null, null, null, null, null, 20))).isEmpty();
    }

    @Test
    void oncSystolicCheckAgainAt160OrBelow90Tagged() {
        assertThat(labels(VitalsRules.oncology(gm(160, 70, null, null, null, null)))).containsExactly("bp=Check again");
        assertThat(labels(VitalsRules.oncology(gm(89, 70, null, null, null, null)))).containsExactly("bp=Check again");
        assertThat(VitalsRules.oncology(gm(159, 70, null, null, null, null))).isEmpty();
    }

    @Test
    void oncDiastolicHighAt100Tagged() {
        assertThat(labels(VitalsRules.oncology(gm(120, 100, null, null, null, null)))).containsExactly("bp=High");
        assertThat(VitalsRules.oncology(gm(120, 99, null, null, null, null))).isEmpty();
    }

    @Test
    void oncPulseFastAbove100Tagged() {
        assertThat(labels(VitalsRules.oncology(gm(null, null, 101, null, null, null))))
                .containsExactly("pulse=Fast · check for fever, anaemia, heart");
        assertThat(VitalsRules.oncology(gm(null, null, 100, null, null, null))).isEmpty();
    }

    @Test
    void oncFeverAt38CelsiusAndSpo2Below94Tagged() {
        assertThat(labels(VitalsRules.oncology(gm(null, null, null, null, 38.0, null))))
                .containsExactly("temperature=Fever · rule out infection before chemo");
        assertThat(VitalsRules.oncology(gm(null, null, null, null, 37.9, null))).isEmpty();
        assertThat(VitalsRules.oncologyFever(38.0)).isTrue();
        assertThat(VitalsRules.oncologyFever(37.9)).isFalse();
        assertThat(VitalsRules.oncologyFever(null)).isFalse();
        assertThat(labels(VitalsRules.oncology(gm(null, null, null, 93, null, null)))).containsExactly("spo2=Low · examine lungs");
        assertThat(VitalsRules.oncology(gm(null, null, null, 94, null, null))).isEmpty();
    }

    // ---- dental (C-3) ----

    @Test
    void dentalSuggestionsByConditionTaggedC3() {
        assertThat(DentalRules.suggestions("CARIES")).extracting(DentalRules.Suggestion::procedureKey)
                .containsExactly("COMPOSITE_FILLING", "ROOT_CANAL");
        assertThat(DentalRules.suggestions("RCT")).extracting(s -> s.procedureKey() + s.phase())
                .containsExactly("ROOT_CANAL1", "PFM_CROWN2");
        assertThat(DentalRules.suggestions("FILLED")).extracting(DentalRules.Suggestion::procedureKey)
                .containsExactly("REPLACE_FILLING");
        assertThat(DentalRules.suggestions("CROWN")).extracting(DentalRules.Suggestion::procedureKey)
                .containsExactly("CROWN_RECEMENTATION");
        assertThat(DentalRules.suggestions("MISSING")).extracting(s -> s.procedureKey() + s.phase())
                .containsExactly("IMPLANT_WITH_CROWN3", "BRIDGE2");
        assertThat(DentalRules.suggestions("TO_EXTRACT")).extracting(DentalRules.Suggestion::procedureKey)
                .containsExactly("EXTRACTION");
        assertThat(DentalRules.suggestions("IMPLANT")).extracting(DentalRules.Suggestion::procedureKey)
                .containsExactly("IMPLANT_REVIEW");
        assertThat(DentalRules.suggestions("HEALTHY")).isEmpty();
    }

    @Test
    void rootCanalIsThreeSittingsOthersOneTaggedC3() {
        assertThat(DentalRules.defaultSittings("ROOT_CANAL")).isEqualTo(3);
        assertThat(DentalRules.defaultSittings("COMPOSITE_FILLING")).isEqualTo(1);
        assertThat(DentalRules.NEXT_SITTING_DAYS).containsExactly(3, 7, 14);
        assertThat(DentalRules.MIN_SITTINGS).isEqualTo(1);
        assertThat(DentalRules.MAX_SITTINGS).isEqualTo(6);
    }

    @Test
    void surfacesOnlyForCariesFilledCrownTaggedC3() {
        assertThat(DentalRules.SURFACE_CONDITIONS).containsExactlyInAnyOrder("CARIES", "FILLED", "CROWN");
        assertThat(DentalRules.SURFACES).containsExactly("M", "O", "D", "B", "L");
        assertThat(DentalRules.SHADES).containsExactly("A1", "B1", "A2", "A3", "C2");
        assertThat(DentalRules.GUMS).containsExactly("PINK", "LIGHT_BROWN", "DARK_BROWN");
        assertThat(DentalRules.MOBILITY_MAX).isEqualTo(3);
    }

    @Test
    void fdiNumbersAdultAndPrimaryTaggedC3() {
        assertThat(DentalRules.validTooth(11)).isTrue();
        assertThat(DentalRules.validTooth(48)).isTrue();
        assertThat(DentalRules.validTooth(19)).isFalse();
        assertThat(DentalRules.validTooth(10)).isFalse();
        assertThat(DentalRules.validTooth(51)).isTrue();
        assertThat(DentalRules.validTooth(85)).isTrue();
        assertThat(DentalRules.validTooth(86)).isFalse();
        assertThat(DentalRules.validTooth(91)).isFalse();
        assertThat(DentalRules.isPrimary(55)).isTrue();
        assertThat(DentalRules.isPrimary(18)).isFalse();
    }

    @Test
    void estimateSplitEqualPartsRoundedToFiftyLastTakesRestTaggedB2() {
        assertThat(DentalRules.splitEstimate(450_000, 3)).containsExactly(150_000L, 150_000L, 150_000L);
        // 10,000 rupees in 3 sittings: 3,333.33 rounds to 3,350; the last takes 3,300.
        assertThat(DentalRules.splitEstimate(1_000_000, 3)).containsExactly(335_000L, 335_000L, 330_000L);
        assertThat(DentalRules.splitEstimate(150_000, 1)).containsExactly(150_000L);
        assertThat(DentalRules.splitEstimate(0, 3)).containsExactly(0L, 0L, 0L);
        List<Long> parts = DentalRules.splitEstimate(7_000, 3);
        assertThat(parts.stream().mapToLong(Long::longValue).sum()).isEqualTo(7_000L);
        assertThat(parts).allMatch(p -> p >= 0);
    }

    @Test
    void duplicateKeyIgnoresCaseAndSpaces() {
        assertThat(DentalRules.dedupeKey(16, "Composite filling (O)"))
                .isEqualTo(DentalRules.dedupeKey(16, "  composite   FILLING (o) "));
        assertThat(DentalRules.dedupeKey(16, "Extraction")).isNotEqualTo(DentalRules.dedupeKey(17, "Extraction"));
    }

    @Test
    void planFingerprintChangesWithRowsTeethProceduresAndAmounts() {
        List<PlanRow> base = List.of(new PlanRow(16, "Composite filling", 150_000, 1));
        String fingerprint = DentalRules.planFingerprint(base);
        assertThat(fingerprint).hasSize(64).isEqualTo(DentalRules.planFingerprint(
                List.of(new PlanRow(16, "composite filling", 150_000, 1))));
        assertThat(DentalRules.planFingerprint(List.of(new PlanRow(17, "Composite filling", 150_000, 1))))
                .isNotEqualTo(fingerprint);
        assertThat(DentalRules.planFingerprint(List.of(new PlanRow(16, "Composite filling", 160_000, 1))))
                .isNotEqualTo(fingerprint);
        assertThat(DentalRules.planFingerprint(List.of(new PlanRow(16, "Root canal", 150_000, 1))))
                .isNotEqualTo(fingerprint);
        assertThat(DentalRules.planFingerprint(List.of())).isNotEqualTo(fingerprint);
        assertThat(DentalRules.total(base)).isEqualTo(150_000L);
    }

    @Test
    void childTabUnderThirteenYearsTaggedH15() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        // from a date of birth through PatientAge: 12 years 11 months 30 days is under 13; exactly 13 years is not
        assertThat(DentalRules.childTabDefault(PatientAge.completedYears(LocalDate.of(2013, 11, 13), today))).isTrue();
        assertThat(DentalRules.childTabDefault(PatientAge.completedYears(LocalDate.of(2013, 11, 12), today))).isFalse();
        // no date of birth: the whole-number age decides (SAH-73-5), and no patient opens the Adult tab
        assertThat(DentalRules.childTabDefault(12)).isTrue();
        assertThat(DentalRules.childTabDefault(13)).isFalse();
        assertThat(DentalRules.childTabDefault(0)).isTrue();
        assertThat(DentalRules.childTabDefault(null)).isFalse();
    }
}
