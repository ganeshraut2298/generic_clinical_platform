package com.sahaj.opd.domain.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.domain.emr.specialty.AncRules.Dating;
import com.sahaj.opd.domain.emr.specialty.AncRules.Ga;
import com.sahaj.opd.domain.emr.specialty.AncRules.Today;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Dose;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules.TestState;
import com.sahaj.patient.api.PatientAge;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C-4 obstetrics, C-5 paediatrics, C-6 physiotherapy and C-7, B-1, B-3 surgery rules; every number is tagged. */
class AncPaedsPhysioSurgeryRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    // ---- C-4 ----

    @Test
    void naegeleIsLmpPlus280DaysTaggedC4() {
        assertThat(AncRules.naegele(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(AncRules.naegele(null)).isNull();
    }

    @Test
    void datingScanEddWinsOverLmpTaggedC4() {
        LocalDate lmp = TODAY.minusDays(100);
        Dating byLmp = AncRules.dating(lmp, null, TODAY);
        assertThat(byLmp.source()).isEqualTo("LMP");
        assertThat(byLmp.edd()).isEqualTo(lmp.plusDays(280));
        assertThat(byLmp.ga()).isEqualTo(new Ga(14, 2));
        LocalDate scanEdd = TODAY.plusDays(140);
        Dating byScan = AncRules.dating(lmp, scanEdd, TODAY);
        assertThat(byScan.source()).isEqualTo("DATING_SCAN");
        assertThat(byScan.ga()).isEqualTo(new Ga(20, 0));
        assertThat(byScan.daysToEdd()).isEqualTo(140);
        assertThat(AncRules.dating(null, null, TODAY).source()).isEqualTo("NONE");
        assertThat(AncRules.dating(null, null, TODAY).ga()).isNull();
    }

    @Test
    void gestationalAgeNullWhenEddFarPastOrNegativeTaggedC4() {
        assertThat(AncRules.dating(TODAY.minusDays(400), null, TODAY).ga()).isNull();
        assertThat(AncRules.dating(TODAY.plusDays(5), null, TODAY).ga()).isNull();
    }

    @Test
    void bpHighAt140Or90IsPossiblePreEclampsiaTaggedC4() {
        assertThat(AncRules.highBp(140, 80)).isTrue();
        assertThat(AncRules.highBp(120, 90)).isTrue();
        assertThat(AncRules.highBp(139, 89)).isFalse();
        assertThat(AncRules.alerts(new Today(null, 150, 95, null, null, null), List.of()))
                .containsExactly("POSSIBLE_PRE_ECLAMPSIA");
    }

    @Test
    void anaemiaGradesBelow11Below10Below7TaggedC4() {
        assertThat(AncRules.anaemiaGrade(10.9)).isEqualTo("MILD");
        assertThat(AncRules.anaemiaGrade(11.0)).isNull();
        assertThat(AncRules.anaemiaGrade(9.9)).isEqualTo("MODERATE");
        assertThat(AncRules.anaemiaGrade(10.0)).isEqualTo("MILD");
        assertThat(AncRules.anaemiaGrade(6.9)).isEqualTo("SEVERE");
        assertThat(AncRules.anaemiaGrade(7.0)).isEqualTo("MODERATE");
        assertThat(AncRules.alerts(new Today(null, null, null, null, null, 6.0), List.of())).containsExactly("ANAEMIA_SEVERE");
        assertThat(AncRules.ANAEMIA_ADVICE.get("ANAEMIA_MILD")).isEqualTo("Continue oral iron");
        assertThat(AncRules.ANAEMIA_ADVICE.get("ANAEMIA_MODERATE")).isEqualTo("Consider IV iron");
        assertThat(AncRules.ANAEMIA_ADVICE.get("ANAEMIA_SEVERE")).isEqualTo("Needs urgent evaluation");
    }

    @Test
    void fundalMismatchWhenMoreThanTwoWeeksApartTaggedC4() {
        Ga ga = new Ga(27, 0);
        assertThat(AncRules.fundalMismatch(30, ga)).isTrue();
        assertThat(AncRules.fundalMismatch(29, ga)).isFalse();
        assertThat(AncRules.fundalMismatch(25, ga)).isFalse();
        assertThat(AncRules.fundalMismatch(24, ga)).isTrue();
        assertThat(AncRules.fundalMismatch(23, ga)).isTrue();
        assertThat(AncRules.fundalMismatch(27, null)).isFalse();
    }

    @Test
    void fetalHeartOutside110To160TaggedC4() {
        assertThat(AncRules.fhrOutside(109)).isTrue();
        assertThat(AncRules.fhrOutside(110)).isFalse();
        assertThat(AncRules.fhrOutside(160)).isFalse();
        assertThat(AncRules.fhrOutside(161)).isTrue();
    }

    @Test
    void notesCarryTheDrawnWordingTaggedC4() {
        List<Flag> notes = AncRules.notes(new Today(60.0, 142, 92, 30, 170, 9.5), new Ga(27, 1));
        assertThat(notes).extracting(f -> f.field() + "=" + f.label()).containsExactly("bp=High: 140/90 or above",
                "fundal=Does not match 27 weeks", "fhr=Outside 110–160", "hb=Moderate anaemia");
        List<Flag> good = AncRules.notes(new Today(60.0, 118, 76, 27, 140, 12.0), new Ga(27, 1));
        assertThat(good).extracting(Flag::label).containsExactly("Normal", "Matches gestational age",
                "Normal (110–160)", "Normal");
        assertThat(AncRules.notes(new Today(null, null, null, 27, null, null), null)).extracting(Flag::label)
                .containsExactly("Needs LMP or dating scan");
    }

    @Test
    void warningSignFromBleedingOrReducedMovementsTaggedC4() {
        Today none = new Today(null, null, null, null, null, null);
        assertThat(AncRules.alerts(none, List.of("BLEEDING_LEAKING"))).containsExactly("WARNING_SIGN");
        assertThat(AncRules.alerts(none, List.of("REDUCED_MOVEMENTS"))).containsExactly("WARNING_SIGN");
        assertThat(AncRules.alerts(none, List.of("HEADACHE"))).isEmpty();
    }

    @Test
    void scanWindowsAndStatesTaggedC4() {
        assertThat(AncRules.SCAN_WINDOWS).extracting(w -> w.key() + ":" + w.fromDays() / 7 + "-" + w.toDays() / 7)
                .containsExactly("DATING:6-9", "NT:11-13", "ANOMALY:18-22", "GROWTH:28-32",
                        "DOPPLER_NST:36-" + Integer.MAX_VALUE / 7);
        assertThat(AncRules.scanWindow("NT").toDays()).isEqualTo(13 * 7 + 6);
        AncRules.ScanWindow growth = AncRules.scanWindow("GROWTH");
        assertThat(AncRules.scanState(growth, false, new Ga(27, 6))).isEqualTo("LATER");
        assertThat(AncRules.scanState(growth, false, new Ga(28, 0))).isEqualTo("DUE");
        assertThat(AncRules.scanState(growth, false, new Ga(35, 0))).isEqualTo("DUE");
        assertThat(AncRules.scanState(growth, true, new Ga(10, 0))).isEqualTo("DONE");
        assertThat(AncRules.scanState(growth, false, null)).isEqualTo("LATER");
    }

    @Test
    void dueOrdersAndRiskChipTaggedC4() {
        assertThat(AncRules.DUE_ORDERS).extracting(AncRules.DueOrder::window).containsExactly("24–28 w", "give today",
                "28–32 w");
        assertThat(AncRules.riskChips(true)).extracting(Flag::label).containsExactly("High risk: previous LSCS");
        assertThat(AncRules.riskChips(false)).isEmpty();
    }

    // ---- C-5 ----

    @Test
    void doseByWeightParacetamol15MgPerKgMax500TaggedC5() {
        PaedsRules.DoseRule p120 = PaedsRules.doseRule("PARACETAMOL_120_5");
        assertThat(PaedsRules.dose(p120, 10.0)).isEqualTo(new Dose(150, 6.3, false));
        assertThat(PaedsRules.dose(p120, 12.3)).isEqualTo(new Dose(185, 7.7, false));
        assertThat(PaedsRules.dose(p120, 40.0)).isEqualTo(new Dose(500, 20.8, true));
        PaedsRules.DoseRule p250 = PaedsRules.doseRule("PARACETAMOL_250_5");
        assertThat(PaedsRules.dose(p250, 10.0)).isEqualTo(new Dose(150, 3.0, false));
        assertThat(p120.frequencyText()).isEqualTo("every 6 hours if needed, at most 4 doses a day");
    }

    @Test
    void doseByWeightIbuprofen10MgPerKgMax400TaggedC5() {
        PaedsRules.DoseRule ibu = PaedsRules.doseRule("IBUPROFEN_100_5");
        assertThat(PaedsRules.dose(ibu, 11.0)).isEqualTo(new Dose(110, 5.5, false));
        assertThat(PaedsRules.dose(ibu, 50.0)).isEqualTo(new Dose(400, 20.0, true));
        assertThat(ibu.frequencyText()).isEqualTo("every 8 hours with food, at most 3 doses a day");
    }

    @Test
    void noWeightGivesNoDoseNeverAnEstimateFromAgeTaggedC5() {
        assertThat(PaedsRules.dose(PaedsRules.doseRule("PARACETAMOL_120_5"), null)).isNull();
        assertThat(PaedsRules.dose(PaedsRules.doseRule("PARACETAMOL_120_5"), 0.0)).isNull();
        assertThat(PaedsRules.dose(null, 10.0)).isNull();
        assertThat(PaedsRules.doseRule("NOPE")).isNull();
    }

    private static final List<GrowthBand> BANDS = List.of(
            new GrowthBand("WEIGHT", null, 18, 18, 8.6, "Below 3rd centile", true, 1),
            new GrowthBand("WEIGHT", null, 18, 18, 9.8, "~15th centile", false, 2),
            new GrowthBand("WEIGHT", null, 18, 18, 11.0, "~25–50th centile", false, 3),
            new GrowthBand("WEIGHT", null, 18, 18, 13.7, "~50–97th centile", false, 4),
            new GrowthBand("WEIGHT", null, 18, 18, null, "Above 97th centile", true, 5),
            new GrowthBand("LENGTH", null, 18, 18, null, "~40th centile", false, 1),
            new GrowthBand("HEAD_CIRC", null, 18, 18, null, "~50th centile", false, 1));

    @Test
    void weightCentileFirstBandAboveTheValueTaggedC5() {
        assertThat(PaedsRules.growthNote("WEIGHT", "F", 18, 8.5, BANDS).orElseThrow().label()).isEqualTo("Below 3rd centile");
        assertThat(PaedsRules.growthNote("WEIGHT", "F", 18, 8.5, BANDS).orElseThrow().alert()).isTrue();
        assertThat(PaedsRules.growthNote("WEIGHT", "F", 18, 8.6, BANDS).orElseThrow().label()).isEqualTo("~15th centile");
        assertThat(PaedsRules.growthNote("WEIGHT", "M", 18, 10.4, BANDS).orElseThrow().label()).isEqualTo("~25–50th centile");
        assertThat(PaedsRules.growthNote("WEIGHT", "M", 18, 12.0, BANDS).orElseThrow().label()).isEqualTo("~50–97th centile");
        assertThat(PaedsRules.growthNote("WEIGHT", "M", 18, 20.0, BANDS).orElseThrow().label()).isEqualTo("Above 97th centile");
    }

    @Test
    void weightCentileNotAvailableOutsideTheDrawnAgeTaggedC5() {
        assertThat(PaedsRules.growthNote("WEIGHT", "M", 30, 12.0, BANDS).orElseThrow().label())
                .isEqualTo("Centile not available for this age");
        assertThat(PaedsRules.growthNote("WEIGHT", "M", null, 12.0, BANDS).orElseThrow().label())
                .isEqualTo("Centile not available for this age");
        assertThat(PaedsRules.growthNote("WEIGHT", "M", 18, null, BANDS)).isEmpty();
    }

    @Test
    void lengthAndHeadCircumferenceCentileNotesAreHiddenPerDec044H8() {
        assertThat(PaedsRules.growthNote("LENGTH", "F", 18, 80.0, BANDS)).isEmpty();
        assertThat(PaedsRules.growthNote("HEAD_CIRC", "F", 18, 47.0, BANDS)).isEmpty();
        assertThat(PaedsRules.CENTILE_MEASURES).containsExactly("WEIGHT");
    }

    private static final List<Group> GROUPS = List.of(
            new Group("birth", "Birth", 1, 0, "DAYS", 6, "WEEKS"),
            new Group("w6", "6 weeks", 2, 6, "WEEKS", 10, "WEEKS"),
            new Group("m16", "16–18 months", 3, 16, "MONTHS", 19, "MONTHS"));
    private static final List<Vaccine> VACCINES = List.of(new Vaccine("bcg", "birth", "BCG", 1),
            new Vaccine("dtwp_1", "w6", "DTwP 1", 1), new Vaccine("dtwp_booster_1", "m16", "DTwP booster 1", 1));

    @Test
    void immunisationStatesGivenDueOverdueLaterTaggedC5H9() {
        LocalDate dob = TODAY.minusMonths(17);
        var states = PaedsRules.vaccineStates(GROUPS, VACCINES, Set.of("bcg"), dob, TODAY);
        assertThat(states).extracting(s -> s.vaccineKey() + ":" + s.state())
                .containsExactly("bcg:GIVEN", "dtwp_1:OVERDUE", "dtwp_booster_1:DUE_TODAY");
        var young = PaedsRules.vaccineStates(GROUPS, VACCINES, Set.of(), TODAY.minusWeeks(2), TODAY);
        assertThat(young).extracting(PaedsRules.VaccineState::state).containsExactly("DUE_TODAY", "LATER", "LATER");
    }

    @Test
    void immunisationWindowEndIsExclusiveTaggedH9() {
        LocalDate dob = TODAY.minusWeeks(6);
        var states = PaedsRules.vaccineStates(GROUPS, VACCINES, Set.of(), dob, TODAY);
        assertThat(states).extracting(s -> s.vaccineKey() + ":" + s.state())
                .containsExactly("bcg:OVERDUE", "dtwp_1:DUE_TODAY", "dtwp_booster_1:LATER");
        assertThat(PaedsRules.vaccineStates(GROUPS, VACCINES, Set.of(), null, TODAY)).extracting(
                PaedsRules.VaccineState::state).containsOnly("LATER");
        assertThat(PaedsRules.mayGive("DUE_TODAY")).isTrue();
        assertThat(PaedsRules.mayGive("OVERDUE")).isTrue();
        assertThat(PaedsRules.mayGive("LATER")).isFalse();
        assertThat(PaedsRules.mayGive("GIVEN")).isFalse();
    }

    @Test
    void ageInMonthsAndDaysComeFromPatientAgeNotFromRuleTag() {
        assertThat(PatientAge.completedMonths(LocalDate.of(2025, 5, 9), LocalDate.of(2026, 11, 12))).isEqualTo(18);
        assertThat(PatientAge.daysAfterMonths(LocalDate.of(2025, 5, 9), LocalDate.of(2026, 11, 12))).isEqualTo(3);
        assertThat(PatientAge.totalDays(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 12))).isEqualTo(9);
    }

    // ---- C-6 ----

    @Test
    void romBarRightOverNormalMaximumTimes100TaggedC6() {
        assertThat(PhysioRules.romPercent(105, 135)).isEqualTo(78);
        assertThat(PhysioRules.romPercent(135, 135)).isEqualTo(100);
        assertThat(PhysioRules.romPercent(150, 135)).isEqualTo(100);
        assertThat(PhysioRules.romPercent(-5, 135)).isEqualTo(0);
        assertThat(PhysioRules.romPercent(0, 0)).isEqualTo(100);
        assertThat(PhysioRules.romPercent(-5, 0)).isEqualTo(90);
        assertThat(PhysioRules.romPercent(null, 135)).isNull();
        assertThat(PhysioRules.romPercent(90, null)).isNull();
    }

    @Test
    void painMmtAndPackageNumbersTaggedC6() {
        assertThat(PhysioRules.NPRS_MIN).isEqualTo(0);
        assertThat(PhysioRules.NPRS_MAX).isEqualTo(10);
        assertThat(PhysioRules.MMT_MIN).isEqualTo(0);
        assertThat(PhysioRules.MMT_MAX).isEqualTo(5);
        assertThat(PhysioRules.PACKAGE_SESSIONS).isEqualTo(10);
        assertThat(PhysioRules.MODALITY_DEFAULTS).containsEntry("IFT", "15 min · knee, 4-pole")
                .containsEntry("TAPING", "Patella, unloading").hasSize(6);
        assertThat(PhysioRules.nextTestResult("NOT_DONE")).isEqualTo("POSITIVE");
        assertThat(PhysioRules.nextTestResult("POSITIVE")).isEqualTo("NEGATIVE");
        assertThat(PhysioRules.nextTestResult("NEGATIVE")).isEqualTo("NOT_DONE");
    }

    // ---- C-7, B-1, B-3 ----

    @Test
    void fitnessSummaryTaggedC7() {
        List<TestState> allNormal = List.of(new TestState("CBC", "NORMAL"), new TestState("ECG", "NORMAL"));
        assertThat(SurgeryRules.fitness("FIT", allNormal).text()).isEqualTo("Fit for surgery");
        assertThat(SurgeryRules.fitness("FIT_WITH_CAUTION", allNormal).state()).isEqualTo("FIT");
        List<TestState> pending = List.of(new TestState("PT_INR", "PENDING"), new TestState("CHEST_XRAY", "PENDING"),
                new TestState("CBC", "NORMAL"));
        assertThat(SurgeryRules.fitness("FIT", pending).text()).isEqualTo("2 test(s) pending: PT/INR, Chest X-ray");
        assertThat(SurgeryRules.fitness("FIT", pending).state()).isEqualTo("TESTS_PENDING");
        assertThat(SurgeryRules.fitness("NOT_DONE", allNormal).text()).isEqualTo("PAC not done");
        assertThat(SurgeryRules.fitness("FIT", List.of(new TestState("KFT", "ABNORMAL"))).text())
                .isEqualTo("Not fit yet: KFT abnormal");
        assertThat(SurgeryRules.fitness("UNFIT", allNormal).text()).isEqualTo("Not fit yet: PAC unfit");
        assertThat(SurgeryRules.hasWarning("ASPIRIN", List.of("NONE"), "FIT")).isTrue();
        assertThat(SurgeryRules.hasWarning("NONE", List.of("DIABETES"), "FIT")).isTrue();
        assertThat(SurgeryRules.hasWarning("NONE", List.of("NONE"), "UNFIT")).isTrue();
        assertThat(SurgeryRules.hasWarning("NONE", List.of("NONE"), "FIT")).isFalse();
    }

    @Test
    void depositHalfRoundedToThousandForSelfElseTenThousandTaggedB1B3() {
        // Rs 32,500 is exactly half way: Math.round goes up to Rs 33,000.
        assertThat(SurgeryRules.depositPaise("SELF", 6_500_000L)).isEqualTo(3_300_000L);
        assertThat(SurgeryRules.depositPaise("SELF", 6_400_000L)).isEqualTo(3_200_000L);
        assertThat(SurgeryRules.depositPaise("SELF", 4_500_100L)).isEqualTo(2_300_000L);
        assertThat(SurgeryRules.depositPaise("SELF", 1_200_000L)).isEqualTo(600_000L);
        assertThat(SurgeryRules.depositPaise("SELF", null)).isNull();
        assertThat(SurgeryRules.depositPaise("INSURANCE_TPA", 6_500_000L)).isEqualTo(1_000_000L);
        assertThat(SurgeryRules.depositPaise("MJPJAY_PMJAY", null)).isEqualTo(1_000_000L);
        assertThat(SurgeryRules.depositPaise("COMPANY", 100L)).isEqualTo(1_000_000L);
    }

    @Test
    void bookingRuleNeedsDiagnosisConsentAndPreauthUnlessSelfTaggedC7L3B3() {
        assertThat(SurgeryRules.bookMissing(true, 5, 5, "SELF", false)).isEmpty();
        assertThat(SurgeryRules.bookMissing(false, 3, 5, "SELF", false)).containsExactly("diagnosis", "2 consent item(s)");
        assertThat(SurgeryRules.bookMissing(true, 5, 5, "INSURANCE_TPA", false)).containsExactly("pre-authorisation");
        assertThat(SurgeryRules.bookMissing(true, 5, 5, "INSURANCE_TPA", true)).isEmpty();
        assertThat(SurgeryRules.bookMissing(true, 0, 0, "SELF", false)).containsExactly("5 consent item(s)");
        assertThat(SurgeryRules.bookMissingDetail(List.of("diagnosis", "2 consent item(s)")))
                .isEqualTo("Before booking: diagnosis, 2 consent item(s).");
    }

    @Test
    void payerChipAndBookedMessages() {
        assertThat(SurgeryRules.payerChip("SELF", false)).isEqualTo("Self-pay");
        assertThat(SurgeryRules.payerChip("INSURANCE_TPA", false)).endsWith("· pre-auth pending");
        assertThat(SurgeryRules.payerChip("MJPJAY_PMJAY", true)).endsWith("· pre-auth sent");
        assertThat(SurgeryRules.bookedMessage("Lap cholecystectomy", "24 Nov 2026", "OT 2 · 10:00", true))
                .isEqualTo("Lap cholecystectomy booked for 24 Nov 2026, OT 2 · 10:00. Ward bed requested.");
        assertThat(SurgeryRules.bookedMessage("x", "d", null, false))
                .isEqualTo("Booked. This clinic has no IPD, so no ward bed was requested.");
    }

    @Test
    void procedureDefaultsAndConsentItemsTaggedC7L3() {
        assertThat(SurgeryRules.PROCEDURES).hasSize(8);
        assertThat(SurgeryRules.procedure("LAP_CHOLECYSTECTOMY").admission()).isEqualTo("IPD_1_2_DAYS");
        assertThat(SurgeryRules.procedure("OPEN_MESH_HERNIOPLASTY").anaesthesia()).isEqualTo("SPINAL");
        assertThat(SurgeryRules.procedure("INCISION_DRAINAGE").admission()).isEqualTo("DAY_CARE");
        assertThat(SurgeryRules.CONSENT_ITEMS).hasSize(5);
        assertThat(SurgeryRules.PREOP_ORDERS).hasSize(6);
        assertThat(SurgeryRules.TESTS).hasSize(11);
    }
}
