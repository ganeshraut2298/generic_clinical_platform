package com.sahaj.opd.domain.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.domain.emr.specialty.ChemoRules.Check;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.CheckFacts;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Lifetime;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Facts;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.LabFail;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Labs;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Lesion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** C-8 oncology assessment and chemotherapy order rules; every number is tagged (DEC-039, not clinically reviewed). */
class OncologyAndChemoRulesTest {

    private static final Drug DOX = new Drug("DOXORUBICIN", "Doxorubicin", 60, 15, 5, true, true, null);
    private static final Drug CYC = new Drug("CYCLOPHOSPHAMIDE", "Cyclophosphamide", 600, 30, 20, false, false, null);
    private static final Protocol AC = new Protocol("AC", "AC", "Doxorubicin + cyclophosphamide · every 21 days", 21,
            "HIGH", false, List.of(DOX, CYC), List.of());

    // ---- BSA and weight ----

    @Test
    void bsaIsSquareRootOfHeightTimesWeightOver3600TwoDecimalsTaggedC8() {
        assertThat(OncologyRules.bsa(160.0, 60.0)).isEqualTo(1.63);
        assertThat(OncologyRules.bsa(170.0, 70.0)).isEqualTo(1.82);
        assertThat(OncologyRules.bsa(null, 70.0)).isNull();
        assertThat(OncologyRules.bsa(170.0, null)).isNull();
    }

    @Test
    void bsaCapAtTwoPointZeroTaggedC8() {
        Double big = OncologyRules.bsa(190.0, 120.0);
        assertThat(big).isGreaterThan(2.0);
        assertThat(OncologyRules.bsaUsed(big, true)).isEqualTo(2.0);
        assertThat(OncologyRules.bsaUsed(big, false)).isEqualTo(big);
        assertThat(OncologyRules.capped(big, true)).isTrue();
        assertThat(OncologyRules.capped(1.8, true)).isFalse();
        assertThat(OncologyRules.bsaUsed(null, true)).isNull();
    }

    @Test
    void weightChangeTenPercentRecalculatesTaggedC8() {
        assertThat(OncologyRules.weightChangePct(54.0, 60.0)).isEqualTo(-10.0);
        assertThat(OncologyRules.recalculateDoses(-10.0)).isTrue();
        assertThat(OncologyRules.recalculateDoses(10.0)).isTrue();
        assertThat(OncologyRules.recalculateDoses(9.9)).isFalse();
        assertThat(OncologyRules.recalculateDoses(null)).isFalse();
        assertThat(OncologyRules.weightChangePct(60.0, null)).isNull();
    }

    // ---- response ----

    private static Lesion lesion(String id, double baseline, Double previous, Double today) {
        return new Lesion(id, baseline, previous, today);
    }

    @Test
    void responsePartialAtMinus30PercentTaggedC8() {
        var r = OncologyRules.response(List.of(lesion("a", 5.0, 4.0, 3.5)), false);
        assertThat(r.category()).isEqualTo("PARTIAL_RESPONSE");
        assertThat(r.sums().pctFromBaseline()).isEqualTo(-30.0);
        assertThat(OncologyRules.response(List.of(lesion("a", 5.0, 4.0, 3.6)), false).category())
                .isEqualTo("STABLE_DISEASE");
    }

    @Test
    void responseCompleteWhenLesionsExistAndSumIsZeroTaggedC8() {
        assertThat(OncologyRules.response(List.of(lesion("a", 3.0, 1.0, 0.0), lesion("b", 2.0, 0.0, 0.0)), false)
                .category()).isEqualTo("COMPLETE_RESPONSE");
    }

    @Test
    void responseProgressiveByNewLesionOrRiseFromNadirTaggedC8() {
        assertThat(OncologyRules.response(List.of(lesion("a", 5.0, 5.0, 5.0)), true).category())
                .isEqualTo("PROGRESSIVE_DISEASE");
        assertThat(OncologyRules.response(List.of(), true).category()).isEqualTo("PROGRESSIVE_DISEASE");
        // nadir 4.0 -> 4.8: +20 % and +0.8 cm
        var rise = OncologyRules.response(List.of(lesion("a", 5.0, 4.0, 4.8)), false);
        assertThat(rise.category()).isEqualTo("PROGRESSIVE_DISEASE");
        assertThat(rise.sums().nadir()).isEqualTo(4.0);
        assertThat(rise.sums().pctFromNadir()).isEqualTo(20.0);
        // +20 % but only +0.4 cm is not progressive
        assertThat(OncologyRules.response(List.of(lesion("a", 2.0, 2.0, 2.4)), false).category())
                .isEqualTo("STABLE_DISEASE");
        // +0.5 cm but under 20 %
        assertThat(OncologyRules.response(List.of(lesion("a", 10.0, 10.0, 10.5)), false).category())
                .isEqualTo("STABLE_DISEASE");
    }

    @Test
    void responseNullWhenSizesMissingTaggedC8() {
        assertThat(OncologyRules.response(List.of(lesion("a", 5.0, null, null)), false).category()).isNull();
        assertThat(OncologyRules.response(List.of(), false).category()).isNull();
    }

    @Test
    void lesionColoursGreenAtMinus30RedAtPlus20TaggedC8() {
        var changes = OncologyRules.lesionChanges(List.of(lesion("g", 10.0, null, 7.0), lesion("r", 10.0, null, 12.0),
                lesion("n", 10.0, null, 11.9), lesion("x", 10.0, null, null)));
        assertThat(changes).extracting(c -> c.colour()).containsExactly("GREEN", "RED", "NONE", "NONE");
        assertThat(changes.get(0).pctFromBaseline()).isEqualTo(-30.0);
        assertThat(changes.get(3).pctFromBaseline()).isNull();
    }

    // ---- labs ----

    @Test
    void labGatesTaggedC8() {
        assertThat(OncologyRules.labFails(new Labs(8.0, 1500, 100, 1.5, 1.5, 100, 50))).isEmpty();
        assertThat(OncologyRules.labFails(new Labs(7.9, null, null, null, null, null, null)))
                .containsExactly(new LabFail("hb", "BELOW_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, 1499, null, null, null, null, null)))
                .containsExactly(new LabFail("anc", "BELOW_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, null, 99, null, null, null, null)))
                .containsExactly(new LabFail("plt", "BELOW_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, null, null, 1.6, null, null, null)))
                .containsExactly(new LabFail("cr", "ABOVE_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, null, null, null, 1.6, null, null)))
                .containsExactly(new LabFail("bili", "ABOVE_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, null, null, null, null, 101, null)))
                .containsExactly(new LabFail("alt", "ABOVE_NEEDED"));
        assertThat(OncologyRules.labFails(new Labs(null, null, null, null, null, null, 49)))
                .containsExactly(new LabFail("lvef", "BELOW_NEEDED"));
    }

    @Test
    void countsAndOrganExcludeLvefTaggedC8() {
        var onlyHeart = OncologyRules.labFails(new Labs(null, null, null, null, null, null, 45));
        assertThat(OncologyRules.countOrOrganFail(onlyHeart)).isFalse();
        assertThat(OncologyRules.lvefLow(onlyHeart)).isTrue();
        var anc = OncologyRules.labFails(new Labs(null, 1000, null, null, null, null, 60));
        assertThat(OncologyRules.countOrOrganFail(anc)).isTrue();
        assertThat(OncologyRules.labsBadge(anc)).isEqualTo("1 out of range");
        assertThat(OncologyRules.labsBadge(List.of())).isEqualTo("Within limits");
    }

    @Test
    void labsFreshWithin72HoursTaggedC8() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        assertThat(OncologyRules.labsFresh(now.minusSeconds(72 * 3600), now)).isTrue();
        assertThat(OncologyRules.labsFresh(now.minusSeconds(72 * 3600 + 1), now)).isFalse();
        assertThat(OncologyRules.labsFresh(now.minusSeconds(3600), now)).isTrue();
        assertThat(OncologyRules.labsFresh(null, now)).isNull();
    }

    // ---- grades, ECOG, line ----

    @Test
    void feverOrAdmissionEventExcludesErVisitTaggedC8H3() {
        assertThat(OncologyRules.feverOrAdmissionEvent(1, Set.of())).isTrue();
        assertThat(OncologyRules.feverOrAdmissionEvent(0, Set.of("FEVER_38_AT_HOME"))).isTrue();
        assertThat(OncologyRules.feverOrAdmissionEvent(null, Set.of("ADMITTED"))).isTrue();
        assertThat(OncologyRules.feverOrAdmissionEvent(0, Set.of("ER_VISIT"))).isFalse();
        assertThat(OncologyRules.feverOrAdmissionEvent(null, Set.of())).isFalse();
    }

    @Test
    void ecogFitAtTwoOrLowerTaggedC8() {
        assertThat(OncologyRules.ecogText(2)).isEqualTo("ECOG 2 · fit for chemo");
        assertThat(OncologyRules.ecogText(3)).isEqualTo("ECOG 3 · usually not fit for chemo");
        assertThat(OncologyRules.ecogText(null)).isNull();
        assertThat(OncologyRules.ECOG_TEXTS).hasSize(5);
    }

    @Test
    void lineReadyNeedsPortWithThreeChecksTaggedC8() {
        Set<String> three = Set.of("FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING");
        assertThat(OncologyRules.lineReady("PORT", three)).isTrue();
        assertThat(OncologyRules.lineReady("PORT", Set.of("FLUSHES_FREELY", "NO_REDNESS_SWELLING"))).isFalse();
        assertThat(OncologyRules.lineReady("PICC", three)).isFalse();
        assertThat(OncologyRules.lineReady(null, three)).isNull();
    }

    // ---- suggestion ----

    private static Facts facts(Integer ecog, boolean countFail, boolean fever, String response, boolean lvefLow,
                               Integer worst, boolean feverEvent) {
        return new Facts(ecog, countFail, fever, response, lvefLow, worst, feverEvent, Boolean.TRUE, false, 60,
                List.of());
    }

    @Test
    void suggestionOrderStopDelayChangeReduceGiveTaggedC8() {
        assertThat(OncologyRules.suggestion(facts(null, false, false, null, false, null, false))).isNull();
        assertThat(OncologyRules.suggestion(facts(3, true, true, "PROGRESSIVE_DISEASE", true, 4, true))).isEqualTo("STOP");
        assertThat(OncologyRules.suggestion(facts(1, true, false, "PROGRESSIVE_DISEASE", true, 4, true)))
                .isEqualTo("DELAY_1_WEEK");
        assertThat(OncologyRules.suggestion(facts(1, false, true, null, false, null, false))).isEqualTo("DELAY_1_WEEK");
        assertThat(OncologyRules.suggestion(facts(1, false, false, "PROGRESSIVE_DISEASE", false, 4, true)))
                .isEqualTo("CHANGE_REGIMEN");
        assertThat(OncologyRules.suggestion(facts(1, false, false, null, true, 4, true))).isEqualTo("CHANGE_REGIMEN");
        assertThat(OncologyRules.suggestion(facts(2, false, false, null, false, 3, false))).isEqualTo("GIVE_REDUCED");
        assertThat(OncologyRules.suggestion(facts(2, false, false, null, false, 1, true))).isEqualTo("GIVE_REDUCED");
        assertThat(OncologyRules.suggestion(facts(2, false, false, "STABLE_DISEASE", false, 2, false)))
                .isEqualTo("GIVE_AS_PLANNED");
        assertThat(OncologyRules.suggestion(facts(0, false, false, null, false, null, false))).isEqualTo("GIVE_AS_PLANNED");
    }

    @Test
    void reasonsListTicksAndCrossesTaggedC8() {
        var reasons = OncologyRules.reasons(new Facts(2, false, false, "STABLE_DISEASE", false, 1, false,
                Boolean.TRUE, false, 62, List.of()));
        assertThat(reasons).extracting(OncologyRules.Reason::key).containsExactly("ECOG", "COUNTS_FEVER", "WORST_GRADE",
                "RESPONSE", "HEART", "LINE");
        assertThat(reasons).allMatch(OncologyRules.Reason::ok);
        var bad = OncologyRules.reasons(new Facts(3, true, true, "PROGRESSIVE_DISEASE", true, 3, true, Boolean.FALSE,
                true, 45, List.of(new LabFail("anc", "BELOW_NEEDED"), new LabFail("lvef", "BELOW_NEEDED"))));
        assertThat(bad).noneMatch(OncologyRules.Reason::ok);
        assertThat(bad.get(1).text()).contains("Neutrophils (ANC)").doesNotContain("Heart pumping");
        assertThat(bad.get(5).text()).isEqualTo("Port: no blood return yet");
    }

    @Test
    void patientAlertsTaggedC8() {
        assertThat(OncologyRules.alerts(3, true, true, true, 401.0, "PORT", Set.of("FLUSHES_FREELY"))).containsExactly(
                "GRADE_3_PLUS", "LAB_OUT_OF_RANGE", "FEVER", "WEIGHT_CHANGE_10", "CUMULATIVE_OVER_400",
                "PORT_NO_BLOOD_RETURN");
        assertThat(OncologyRules.alerts(2, false, false, false, 400.0, "PORT", Set.of("BLOOD_RETURN"))).isEmpty();
        assertThat(OncologyRules.alerts(null, false, false, false, null, null, Set.of())).isEmpty();
    }

    @Test
    void nextCycleDefaultsToIntervalOrSevenDaysWhenDelayedTaggedC8() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        var normal = OncologyRules.nextCycle(today, 21, false, 62);
        assertThat(normal.defaultDate()).isEqualTo(today.plusDays(21));
        assertThat(normal.label()).isEqualTo("NEXT_CHEMO");
        assertThat(normal.bloodTestDate()).isEqualTo(today.plusDays(20));
        assertThat(normal.echo()).isEqualTo("Before paclitaxel");
        var delayed = OncologyRules.nextCycle(today, 21, true, 54);
        assertThat(delayed.defaultDate()).isEqualTo(today.plusDays(7));
        assertThat(delayed.label()).isEqualTo("REBOOKED_CHEMO");
        assertThat(delayed.echo()).isEqualTo("Before next cycle");
    }

    // ---- chemo doses ----

    @Test
    void calculatedDoseIsDosePerM2TimesBsaTimesLevelTaggedC8() {
        assertThat(ChemoRules.calculatedMg(DOX, 1.8, 100)).isEqualTo(108.0);
        assertThat(ChemoRules.calculatedMg(DOX, 1.8, 90)).isEqualTo(97.2);
        assertThat(ChemoRules.calculatedMg(CYC, 1.63, 75)).isEqualTo(733.5);
        assertThat(ChemoRules.calculatedMg(DOX, null, 100)).isNull();
    }

    @Test
    void vialRoundingToTheNearestStepTaggedC8() {
        assertThat(ChemoRules.roundedMg(108.0, 5)).isEqualTo(110.0);
        assertThat(ChemoRules.roundedMg(97.2, 5)).isEqualTo(95.0);
        assertThat(ChemoRules.roundedMg(733.5, 20)).isEqualTo(740.0);
        assertThat(ChemoRules.roundedMg(102.5, 5)).isEqualTo(105.0);
    }

    @Test
    void finalDoseIsRoundedUnlessEditedAndFivePercentRuleFlagsTagged() {
        var plain = ChemoRules.doseLine(DOX, 1.8, 100, null);
        assertThat(plain.calculatedMg()).isEqualTo(108.0);
        assertThat(plain.roundedMg()).isEqualTo(110.0);
        assertThat(plain.finalMg()).isEqualTo(110.0);
        assertThat(plain.edited()).isFalse();
        assertThat(plain.moreThan5Pct()).isFalse();
        var edited = ChemoRules.doseLine(DOX, 1.8, 100, 100.0);
        assertThat(edited.edited()).isTrue();
        assertThat(edited.finalMg()).isEqualTo(100.0);
        assertThat(edited.moreThan5Pct()).isTrue();
        var near = ChemoRules.doseLine(DOX, 1.8, 100, 112.0);
        assertThat(near.edited()).isTrue();
        assertThat(near.moreThan5Pct()).isFalse();
        assertThat(ChemoRules.doseLine(DOX, null, 100, null)).isNull();
        assertThat(ChemoRules.moreThan5Pct(113.0, 108.0)).isFalse();
        assertThat(ChemoRules.moreThan5Pct(114.0, 108.0)).isTrue();
        assertThat(ChemoRules.moreThan5Pct(102.0, 108.0)).isTrue();
    }

    @Test
    void levelsAndNextLevelDownTaggedC8() {
        assertThat(ChemoRules.LEVELS).containsExactly(100, 90, 80, 75);
        assertThat(ChemoRules.nextLevelDown(100)).isEqualTo(90);
        assertThat(ChemoRules.nextLevelDown(80)).isEqualTo(75);
        assertThat(ChemoRules.nextLevelDown(75)).isEqualTo(75);
        assertThat(ChemoRules.REDUCTION_REASONS).hasSize(6);
    }

    @Test
    void lifetimeDoxorubicinRedAbove400PassesAt450Tagged() {
        assertThat(ChemoRules.thisCycleMgM2(AC, 100)).isEqualTo(60.0);
        assertThat(ChemoRules.thisCycleMgM2(AC, 90)).isEqualTo(54.0);
        Lifetime ok = ChemoRules.lifetime(390.0, 60.0);
        assertThat(ok.totalMgM2()).isEqualTo(450.0);
        assertThat(ok.withinLimit()).isTrue();
        assertThat(ok.red()).isTrue();
        Lifetime over = ChemoRules.lifetime(390.0, 60.01);
        assertThat(over.withinLimit()).isFalse();
        Lifetime low = ChemoRules.lifetime(340.0, 60.0);
        assertThat(low.totalMgM2()).isEqualTo(400.0);
        assertThat(low.red()).isFalse();
        assertThat(ChemoRules.LIFETIME_LIMIT_MG_M2).isEqualTo(450.0);
        assertThat(ChemoRules.LIFETIME_RED_ABOVE_MG_M2).isEqualTo(400.0);
        assertThat(ChemoRules.DEVIATION_PCT).isEqualTo(5.0);
    }

    // ---- ten checks ----

    private static CheckFacts goodFacts() {
        return new CheckFacts("GIVE_AS_PLANNED", false, false, Boolean.TRUE, true, true,
                ChemoRules.lifetime(0, 60), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE);
    }

    private static Check check(List<Check> checks, String key) {
        return checks.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void tenChecksPassWhenEverythingIsInOrderTaggedC8() {
        List<Check> checks = ChemoRules.checks(goodFacts());
        assertThat(checks).hasSize(10);
        assertThat(ChemoRules.allPassed(checks)).isTrue();
        assertThat(ChemoRules.passedCount(checks)).isEqualTo(10);
    }

    @Test
    void lineCheckOnlyForProtocolsWithAVesicantTaggedH4() {
        CheckFacts f = goodFacts();
        List<Check> without = ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, false,
                f.lifetime(), 100, false, true, true, false, "APPROVED", false, null));
        assertThat(without).hasSize(9);
        assertThat(without).noneMatch(c -> c.key().equals("LINE_VESICANT"));
    }

    @Test
    void decisionMustBeGiveTaggedC8() {
        CheckFacts f = goodFacts();
        for (String decision : new String[] {"DELAY_1_WEEK", "STOP", "CHANGE_REGIMEN", null}) {
            List<Check> checks = ChemoRules.checks(new CheckFacts(decision, false, false, Boolean.TRUE, true, true,
                    f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE));
            assertThat(check(checks, "DECISION_RECORDED").passed()).isFalse();
        }
        assertThat(ChemoRules.isGive("GIVE_REDUCED")).isTrue();
    }

    @Test
    void fitnessLabsBsaAndLineChecksFailTaggedC8() {
        CheckFacts f = goodFacts();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), true, false, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE)), "FIT_TO_TREAT").passed())
                .isFalse();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, true, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE)), "FIT_TO_TREAT").detail())
                .startsWith("Fever");
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.FALSE, true, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE)), "LABS_72H").passed())
                .isFalse();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, null, true, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE)), "LABS_72H").detail())
                .isEqualTo("Labs not recorded");
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, false, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE)), "BSA_TODAY").passed())
                .isFalse();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, true, true, false, "APPROVED", true, Boolean.FALSE)), "LINE_VESICANT")
                .passed()).isFalse();
    }

    @Test
    void cumulativeReasonAllergyConsentAndSchemeChecksTaggedC8H5() {
        CheckFacts f = goodFacts();
        List<Check> over = ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                ChemoRules.lifetime(400, 60.1), 100, false, true, true, false, "APPROVED", true, Boolean.TRUE));
        assertThat(check(over, "CUMULATIVE_DOSE").passed()).isFalse();
        assertThat(check(over, "CUMULATIVE_DOSE").detail()).isEqualTo("Doxorubicin above 450 mg/m²");
        assertThat(check(ChemoRules.checks(f), "CUMULATIVE_DOSE").detail()).isEqualTo("Doxorubicin under 450 mg/m²");
        List<Check> reduced = ChemoRules.checks(new CheckFacts("GIVE_REDUCED", false, false, Boolean.TRUE, true, true,
                f.lifetime(), 90, false, true, true, false, "APPROVED", true, Boolean.TRUE));
        assertThat(check(reduced, "REDUCTION_REASON").passed()).isFalse();
        assertThat(check(reduced, "REDUCTION_REASON").detail()).isEqualTo("Pick a reason above");
        List<Check> reasoned = ChemoRules.checks(new CheckFacts("GIVE_REDUCED", false, false, Boolean.TRUE, true, true,
                f.lifetime(), 90, true, true, true, false, "APPROVED", true, Boolean.TRUE));
        assertThat(check(reasoned, "REDUCTION_REASON").passed()).isTrue();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, false, true, false, "APPROVED", true, Boolean.TRUE)), "ALLERGIES_REVIEWED")
                .passed()).isFalse();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, true, false, false, "APPROVED", true, Boolean.TRUE)), "CONSENT_REGIMEN")
                .passed()).isFalse();
        assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                f.lifetime(), 100, false, true, false, true, "APPROVED", true, Boolean.TRUE)), "CONSENT_REGIMEN")
                .passed()).isTrue();
        for (String scheme : new String[] {"APPROVED", "PREAUTH_RAISED", "SELF_PAY"}) {
            assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                    f.lifetime(), 100, false, true, true, false, scheme, true, Boolean.TRUE)), "SCHEME_APPROVAL")
                    .passed()).isTrue();
        }
        for (String scheme : new String[] {"NOT_IN_PACKAGE", null}) {
            assertThat(check(ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, true,
                    f.lifetime(), 100, false, true, true, false, scheme, true, Boolean.TRUE)), "SCHEME_APPROVAL")
                    .passed()).isFalse();
        }
    }

    @Test
    void nonAnthracyclineProtocolHasNoLifetimeLimitTagged() {
        CheckFacts f = goodFacts();
        List<Check> checks = ChemoRules.checks(new CheckFacts(f.decision(), false, false, Boolean.TRUE, true, false,
                null, 100, false, true, true, false, "APPROVED", false, null));
        assertThat(check(checks, "CUMULATIVE_DOSE").passed()).isTrue();
        assertThat(check(checks, "CUMULATIVE_DOSE").detail()).isEqualTo("No lifetime limit");
    }


    // ---- fingerprint (ADR-023 D14 item 2: chemo-fp-v2) ----

    private static final UUID CHECKER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** The canonical text inputs, changed one at a time by the tests below. */
    private static final class C {
        String reasonKey;
        String reasonText;
        Map<String, Double> doses = Map.of("doxorubicin", 95.0);
        Set<String> premeds = Set.of();
        boolean allergy = true;
        boolean consent = true;
        String scheme = "APPROVED";
        Double prior = null;
        Double weight = 60.0;
        Double bsaUsed = 1.63;
        List<ChemoRules.Check> checks = ChemoRules.checks(goodFacts());
        UUID checker;
        Instant drawn = Instant.parse("2026-11-12T04:00:00Z");
        Labs labs = new Labs(10.5, 1800, 200, 0.8, 0.7, 30, 62);

        ChemoRules.Canon build() {
            return new ChemoRules.Canon("AC", 100, reasonKey, reasonText, doses, premeds, allergy, consent, scheme, prior,
                    weight, 160.0, 1.63, bsaUsed, false, true, "TODAYS_WEIGHT",
                    List.of(new ChemoRules.CanonDrug("doxorubicin", 97.8, 100.0, 95.0),
                            new ChemoRules.CanonDrug("cyclophosphamide", 978.0, 980.0, 980.0)),
                    0.0, 60.0, 60.0, labs, drawn, "GIVE_AS_PLANNED", checks, checker);
        }

        String fp() {
            return ChemoRules.fingerprint(build());
        }
    }

    @Test
    void fingerprintIsSha256OfTheV2CanonicalTextTagged() {
        C base = new C();
        assertThat(base.fp()).matches("^[0-9a-f]{64}$").isEqualTo(new C().fp());
        assertThat(base.fp()).isEqualTo(RuleTag.sha256(ChemoRules.canonicalText(base.build())));
    }

    @Test
    void theCanonicalTextHasTheTagAFixedOrderOneNameValuePerLineAndNullAsNull() {
        C c = new C();
        c.doses = Map.of("doxorubicin", 95.0, "cyclophosphamide", 980.50);
        c.premeds = Set.of("zeta", "alpha");
        c.prior = 0.0;
        String text = ChemoRules.canonicalText(c.build());
        assertThat(text).startsWith("chemo-fp-v2\nprotocol=AC\nlevel=100\nreductionReasonKey=null\n"
                + "reductionReasonText=null\neditedFinalDose.cyclophosphamide=980.5\neditedFinalDose.doxorubicin=95\n"
                + "premedNotGiven=alpha\npremedNotGiven=zeta\nallergyConfirmed=true\nconsentNewSigned=true\n"
                + "scheme=APPROVED\npriorOutsideDoxorubicinMgM2=0\nweightKg=60\nheightCm=160\nbsaRaw=1.63\n");
        assertThat(text).contains("drug.doxorubicin.calculatedMg=97.8\n", "drug.doxorubicin.roundedMg=100\n",
                "drug.cyclophosphamide.finalMg=980\n", "lifetime.totalMgM2=60\n", "labs.hb=10.5\n", "labs.anc=1800\n",
                "labs.drawnAt=2026-11-12T04:00:00Z\n", "decision=GIVE_AS_PLANNED\n", "check.DECISION_RECORDED=true\n")
                .endsWith("checker=null\n");
        assertThat(text.lines().allMatch(l -> l.equals("chemo-fp-v2") || l.matches("^[A-Za-z0-9_.]+=[^=]*$"))).isTrue();
        assertThat(ChemoRules.canonicalText(new C().build())).contains("labs.alt=30\n");
        C noLabs = new C();
        noLabs.labs = null;
        noLabs.drawn = null;
        assertThat(ChemoRules.canonicalText(noLabs.build())).contains("labs.hb=null\n", "labs.drawnAt=null\n");
    }

    @Test
    void aTextWithALineBreakCannotAddALineToTheCanonicalText() {
        C c = new C();
        c.reasonText = "Frail\ndecision=GIVE_AS_PLANNED";
        String text = ChemoRules.canonicalText(c.build());
        assertThat(text).contains("reductionReasonText=Frail\\ndecision=GIVE_AS_PLANNED\n");
        assertThat(text.lines().filter(l -> l.startsWith("decision=")).count()).isEqualTo(1);
    }

    @Test
    void everyDoctorInputComputedValueCheckStateAndTheCheckerChangesTheFingerprintTagged() {
        String base = new C().fp();
        List<java.util.function.Consumer<C>> changes = List.of(
                c -> c.reasonKey = "FRAILTY", c -> c.reasonText = "Frail", c -> c.doses = Map.of("doxorubicin", 90.0),
                c -> c.premeds = Set.of("ondansetron_iv"), c -> c.allergy = false, c -> c.consent = false,
                c -> c.scheme = "PREAUTH_RAISED", c -> c.prior = 100.0, c -> c.weight = 61.0, c -> c.bsaUsed = 1.7,
                c -> c.drawn = Instant.parse("2026-11-12T05:00:00Z"), c -> c.labs = new Labs(10.4, 1800, 200, 0.8, 0.7,
                        30, 62), c -> c.checker = CHECKER, c -> c.checks = ChemoRules.checks(new CheckFacts(
                        "GIVE_AS_PLANNED", true, false, Boolean.TRUE, true, true, ChemoRules.lifetime(0, 60), 100, false,
                        true, true, false, "APPROVED", true, Boolean.TRUE)));
        for (var change : changes) {
            C c = new C();
            change.accept(c);
            assertThat(c.fp()).isNotEqualTo(base);
        }
    }

    @Test
    void aChangeThatKeepsEveryCheckGreenStillChangesTheFingerprintTagged() {
        C base = new C();
        C preauth = new C();
        preauth.scheme = "PREAUTH_RAISED";
        preauth.checks = ChemoRules.checks(new CheckFacts("GIVE_AS_PLANNED", false, false, Boolean.TRUE, true, true,
                ChemoRules.lifetime(0, 60), 100, false, true, true, false, "PREAUTH_RAISED", true, Boolean.TRUE));
        assertThat(ChemoRules.allPassed(base.checks)).isTrue();
        assertThat(ChemoRules.allPassed(preauth.checks)).isTrue();
        assertThat(preauth.fp()).isNotEqualTo(base.fp());
    }

    @Test
    void displayTextAndTheOrderOfSetsDoNotChangeTheFingerprint() {
        C base = new C();
        base.premeds = new java.util.LinkedHashSet<>(List.of("b", "a"));
        C other = new C();
        other.premeds = new java.util.LinkedHashSet<>(List.of("a", "b"));
        other.checks = base.checks.stream().map(k -> new ChemoRules.Check(k.key(), k.passed(), "other words",
                "OTHER_ACTION")).toList();
        assertThat(other.fp()).isEqualTo(base.fp());
    }

    @Test
    void numbersArePlainDecimalsWithoutTrailingZeros() {
        C c = new C();
        c.doses = Map.of("doxorubicin", 1.0E-4, "cyclophosphamide", 12000.0);
        String text = ChemoRules.canonicalText(c.build());
        assertThat(text).contains("editedFinalDose.cyclophosphamide=12000\n", "editedFinalDose.doxorubicin=0.0001\n");
    }
}
