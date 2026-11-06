package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.ChargeRepository.ChargeRow;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.RunningBill.BillLine;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-25 the running bill money: lines, rounding, bill days in both modes and the deposit alert. */
class RunningBillTest {

    private static final UUID ADMISSION = UUID.randomUUID();
    /** 24 Oct 2026, 11:00 AM IST. */
    private static final Instant ADMITTED = Instant.parse("2026-10-24T05:30:00Z");
    private static final ConfigRow MIDNIGHT = new ConfigRow(true, "midnight", 30_000L, 50_000L);
    private static final ConfigRow H24 = new ConfigRow(true, "24h", 30_000L, 50_000L);
    private static final CategoryRow GEN = new CategoryRow("gen", 80_000L, new BigDecimal("1.00"));
    private static final CategoryRow PVT = new CategoryRow("pvt", 250_000L, new BigDecimal("1.50"));

    private static ChargeRow charge(String key, String name, long unit, int qty) {
        return new ChargeRow(ADMISSION, key, name, unit, qty);
    }

    private static RunningBill bill(ConfigRow config, String key, CategoryRow category, Instant now,
                                    List<ChargeRow> charges, long paid, int alert) {
        return RunningBill.compute(ADMITTED, now, config, key, Map.of(key, category), "Anil Kumar Rao", charges,
                List.of(), paid, alert);
    }

    // ---- lines ----

    @Test
    void firstDayHasBedDoctorVisitAndNursingLines() {
        RunningBill bill = bill(MIDNIGHT, "gen", GEN, ADMITTED.plusSeconds(3600), List.of(), 1_000_000L, 80);

        assertThat(bill.billDays()).isEqualTo(1);
        assertThat(bill.bedDayCut()).isEqualTo("midnight");
        assertThat(bill.categoryName()).isEqualTo("General");
        assertThat(bill.lines()).containsExactly(
                new BillLine("Bed · General", 80_000L, 1, 80_000L),
                new BillLine("Doctor visits · Anil Kumar", 50_000L, 1, 50_000L),
                new BillLine("Nursing care", 30_000L, 1, 30_000L));
        assertThat(bill.totalPaise()).isEqualTo(160_000L);
        assertThat(bill.paidPaise()).isEqualTo(1_000_000L);
        assertThat(bill.alertPercent()).isEqualTo(80);
    }

    @Test
    void multiplierAppliesToDoctorVisitsAndExtraChargesButNotToBedOrNursing() {
        // Day 3 (24, 25, 26 Oct). Visit 500 x 1.5 = 750; CBC 300 x 1.5 = 450 x 2.
        RunningBill bill = bill(MIDNIGHT, "pvt", PVT, Instant.parse("2026-10-26T05:30:00Z"),
                List.of(charge("cbc", "CBC", 30_000L, 1), charge("cbc", "CBC", 30_000L, 1)), 0L, 80);

        assertThat(bill.billDays()).isEqualTo(3);
        assertThat(bill.multiplier()).isEqualByComparingTo("1.5");
        assertThat(bill.lines()).containsExactly(
                new BillLine("Bed · Private", 250_000L, 3, 750_000L),
                new BillLine("Doctor visits · Anil Kumar", 75_000L, 3, 225_000L),
                new BillLine("Nursing care", 30_000L, 3, 90_000L),
                new BillLine("CBC", 45_000L, 2, 90_000L));
        assertThat(bill.totalPaise()).isEqualTo(1_155_000L);
    }

    @Test
    void multiplierIsRoundedHalfUpToWholePaisePerUnit() {
        CategoryRow odd = new CategoryRow("semi", 100_000L, new BigDecimal("1.25"));
        // 150 paise x 1.25 = 187.5 -> 188 (half up); 10 paise x 1.25 = 12.5 -> 13; 10 paise x 1.05 = 10.5 -> 11.
        assertThat(RunningBill.scaled(150, odd.multiplier())).isEqualTo(188L);
        assertThat(RunningBill.scaled(10, odd.multiplier())).isEqualTo(13L);
        assertThat(RunningBill.scaled(10, new BigDecimal("1.05"))).isEqualTo(11L);
        assertThat(RunningBill.scaled(10, new BigDecimal("1.04"))).isEqualTo(10L);
        assertThat(RunningBill.scaled(0, odd.multiplier())).isZero();

        // The unit is rounded first, then multiplied by the quantity (the line amount is unit x qty).
        RunningBill bill = bill(new ConfigRow(true, "midnight", 0L, 150L), "semi", odd, ADMITTED.plusSeconds(60),
                List.of(charge("injection", "Injection (IV/IM)", 150L, 3)), 0L, 80);
        assertThat(bill.lines()).contains(new BillLine("Doctor visits · Anil Kumar", 188L, 1, 188L),
                new BillLine("Injection (IV/IM)", 188L, 3, 564L));
    }

    @Test
    void extraChargesAreGroupedByServiceNameInOrderOfFirstCharge() {
        RunningBill bill = bill(MIDNIGHT, "gen", GEN, ADMITTED.plusSeconds(60), List.of(
                charge("ivfluid", "IV fluids (per bottle)", 35_000L, 1),
                charge("cbc", "CBC", 30_000L, 1),
                charge("ivfluid", "IV fluids (per bottle)", 35_000L, 1),
                charge("ivfluid", "IV fluids (per bottle)", 35_000L, 1)), 0L, 80);

        assertThat(bill.lines()).hasSize(5);
        assertThat(bill.lines().get(3)).isEqualTo(new BillLine("IV fluids (per bottle)", 35_000L, 3, 105_000L));
        assertThat(bill.lines().get(4)).isEqualTo(new BillLine("CBC", 30_000L, 1, 30_000L));
        assertThat(bill.totalPaise()).isEqualTo(160_000L + 105_000L + 30_000L);
    }

    @Test
    void linesWithZeroQuantityAreDropped() {
        RunningBill bill = bill(MIDNIGHT, "gen", GEN, ADMITTED.plusSeconds(60),
                List.of(charge("cbc", "CBC", 30_000L, 0)), 0L, 80);

        assertThat(bill.lines()).extracting(BillLine::name)
                .containsExactly("Bed · General", "Doctor visits · Anil Kumar", "Nursing care");
    }

    @Test
    void doctorShortNameIsTheFirstTwoWords() {
        RunningBill bill = RunningBill.compute(ADMITTED, ADMITTED, MIDNIGHT, "gen", Map.of("gen", GEN), "Dr Test",
                List.of(), List.of(), 0L, 80);

        assertThat(bill.lines().get(1).name()).isEqualTo("Doctor visits · Dr Test");
    }

    @Test
    void theBedDayCutAndTheBillDaysNowComeFromOnePlace() {
        ConfigRow every24 = new ConfigRow(false, "24h", 0L, 0L);
        ConfigRow odd = new ConfigRow(false, "weekly", 0L, 0L);
        Instant later = ADMITTED.plusSeconds(72 * 3600 + 5);

        assertThat(RunningBill.cutOf(MIDNIGHT)).isEqualTo("midnight");
        assertThat(RunningBill.cutOf(every24)).isEqualTo("24h");
        assertThat(RunningBill.cutOf(odd)).isEqualTo("midnight");
        assertThat(RunningBill.billDaysNow(ADMITTED, later, every24)).isEqualTo(3);
        assertThat(RunningBill.billDaysNow(ADMITTED, later, every24))
                .isEqualTo(RunningBill.billDays(ADMITTED, later, "24h"));
        assertThat(RunningBill.billDaysNow(ADMITTED, later, odd))
                .isEqualTo(RunningBill.billDays(ADMITTED, later, "midnight"));
    }

    // ---- SAH-26 segments ----

    private static final CategoryRow ICU = new CategoryRow("icu", 500_000L, new BigDecimal("2.00"));
    private static final Map<String, CategoryRow> ALL = Map.of("gen", GEN, "pvt", PVT, "icu", ICU);

    private static TransferRow transfer(String from, String to, int segmentDays) {
        return new TransferRow(ADMISSION, from, segmentDays);
    }

    private static RunningBill billWithTransfers(String currentKey, Instant now, List<ChargeRow> charges,
                                                 TransferRow... transfers) {
        return RunningBill.compute(ADMITTED, now, MIDNIGHT, currentKey, ALL, "Anil Kumar Rao", charges,
                List.of(transfers), 0L, 80);
    }

    @Test
    void aTransferGivesTheOldBedLinesFirstMarkedAsSegmentAndNursingForAllBillDays() {
        // Day 3 (26 Oct). Moved General to Private on day 3 with "Same day": General 2 days, Private 1 day.
        RunningBill bill = billWithTransfers("pvt", Instant.parse("2026-10-26T05:30:00Z"), List.of(),
                transfer("gen", "pvt", 2));

        assertThat(bill.billDays()).isEqualTo(3);
        assertThat(bill.categoryName()).isEqualTo("Private");
        assertThat(bill.lines()).containsExactly(
                new BillLine("Bed · General (before transfer)", 80_000L, 2, 160_000L, true),
                new BillLine("Doctor visits · General rate", 50_000L, 2, 100_000L, true),
                new BillLine("Bed · Private", 250_000L, 1, 250_000L, false),
                new BillLine("Doctor visits · Anil Kumar", 75_000L, 1, 75_000L, false),
                new BillLine("Nursing care", 30_000L, 3, 90_000L, false));
        assertThat(bill.totalPaise()).isEqualTo(675_000L);
    }

    @Test
    void oldBedVisitChargeUsesTheOldCategoryMultiplierRoundedHalfUp() {
        // Private to General after 2 days at Private: visit 150 paise x 1.5 = 225 per day.
        ConfigRow config = new ConfigRow(true, "midnight", 0L, 150L);
        RunningBill bill = RunningBill.compute(ADMITTED, Instant.parse("2026-10-26T05:30:00Z"), config, "gen", ALL,
                "Anil Kumar Rao", List.of(), List.of(transfer("pvt", "gen", 2)), 0L, 80);

        assertThat(bill.lines()).contains(
                new BillLine("Bed · Private (before transfer)", 250_000L, 2, 500_000L, true),
                new BillLine("Doctor visits · Private rate", 225L, 2, 450L, true),
                new BillLine("Doctor visits · Anil Kumar", 150L, 1, 150L, false));
    }

    @Test
    void aTransferWithNoSegmentDaysAddsNothingForTheOldBed() {
        // Moved on day 1 with "Same day": the whole first day is billed at the new category.
        RunningBill bill = billWithTransfers("pvt", ADMITTED.plusSeconds(3600), List.of(), transfer("gen", "pvt", 0));

        assertThat(bill.lines()).extracting(BillLine::name)
                .containsExactly("Bed · Private", "Doctor visits · Anil Kumar", "Nursing care");
        assertThat(bill.lines()).noneMatch(BillLine::segment);
    }

    @Test
    void withNextDayTheCurrentBedHasNoLinesUntilTheNextBillDay() {
        // Day 3, moved with "Next day": General 3 days, Private from day 4. Nursing still for all 3 days.
        RunningBill bill = billWithTransfers("pvt", Instant.parse("2026-10-26T05:30:00Z"), List.of(),
                transfer("gen", "pvt", 3));

        assertThat(bill.lines()).extracting(BillLine::name).containsExactly("Bed · General (before transfer)",
                "Doctor visits · General rate", "Nursing care");
        assertThat(bill.lines().get(0).qty()).isEqualTo(3);
        assertThat(bill.lines().get(2).qty()).isEqualTo(3);
        // Day 4: one day on Private.
        RunningBill next = billWithTransfers("pvt", Instant.parse("2026-10-27T05:30:00Z"), List.of(),
                transfer("gen", "pvt", 3));
        assertThat(next.lines()).extracting(BillLine::name).containsExactly("Bed · General (before transfer)",
                "Doctor visits · General rate", "Bed · Private", "Doctor visits · Anil Kumar", "Nursing care");
    }

    @Test
    void severalTransfersGiveOnePairOfLinesEachInOrderAndTheCurrentDaysAreWhatIsLeft() {
        // Day 5 (28 Oct): General 2 days (moved on day 3), then Private 1 day (moved on day 4, p=2, c=2, seg=1),
        // then ICU for the remaining 2 days.
        RunningBill bill = billWithTransfers("icu", Instant.parse("2026-10-28T05:30:00Z"), List.of(),
                transfer("gen", "pvt", 2), transfer("pvt", "icu", 1));

        assertThat(bill.billDays()).isEqualTo(5);
        assertThat(bill.lines()).extracting(BillLine::name).containsExactly("Bed · General (before transfer)",
                "Doctor visits · General rate", "Bed · Private (before transfer)", "Doctor visits · Private rate",
                "Bed · ICU", "Doctor visits · Anil Kumar", "Nursing care");
        assertThat(bill.lines()).extracting(BillLine::qty).containsExactly(2, 2, 1, 1, 2, 2, 5);
        assertThat(bill.lines()).extracting(BillLine::segment)
                .containsExactly(true, true, true, true, false, false, false);
    }

    @Test
    void extraChargesFollowNursingAndUseTheCurrentCategoryMultiplier() {
        RunningBill bill = billWithTransfers("pvt", Instant.parse("2026-10-26T05:30:00Z"),
                List.of(charge("cbc", "CBC", 30_000L, 1)), transfer("gen", "pvt", 2));

        assertThat(bill.lines().get(bill.lines().size() - 1)).isEqualTo(new BillLine("CBC", 45_000L, 1, 45_000L));
        assertThat(bill.lines().get(bill.lines().size() - 2).name()).isEqualTo("Nursing care");
    }

    @Test
    void segmentDaysBeyondTheBillDaysNeverMakeTheCurrentBedNegative() {
        // A clock that went back or a changed bed-day cut: the current bed is billed 0 days, not minus.
        RunningBill bill = billWithTransfers("pvt", ADMITTED.plusSeconds(60), List.of(), transfer("gen", "pvt", 3));

        assertThat(bill.billDays()).isEqualTo(1);
        assertThat(bill.lines()).extracting(BillLine::name).doesNotContain("Bed · Private");
    }

    // ---- bill days ----

    @Test
    void midnightCutCountsIstCalendarDaysInclusive() {
        // Admitted 24 Oct 11:00 IST.
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED, "midnight")).isEqualTo(1);
        // 24 Oct 23:59 IST is still day 1.
        assertThat(RunningBill.billDays(ADMITTED, Instant.parse("2026-10-24T18:29:59Z"), "midnight")).isEqualTo(1);
        // 25 Oct 00:00 IST is day 2, although only 13 hours have passed.
        assertThat(RunningBill.billDays(ADMITTED, Instant.parse("2026-10-24T18:30:00Z"), "midnight")).isEqualTo(2);
        assertThat(RunningBill.billDays(ADMITTED, Instant.parse("2026-10-27T05:30:00Z"), "midnight")).isEqualTo(4);
    }

    @Test
    void midnightCutUsesTheIstDateNotTheUtcDate() {
        // 23:45 IST on 24 Oct is 18:15 UTC on 24 Oct; 00:15 IST on 25 Oct is 18:45 UTC on 24 Oct (same UTC date).
        Instant lateEvening = Instant.parse("2026-10-24T18:15:00Z");
        Instant justAfterMidnight = Instant.parse("2026-10-24T18:45:00Z");

        assertThat(RunningBill.billDays(lateEvening, justAfterMidnight, "midnight")).isEqualTo(2);
    }

    @Test
    void twentyFourHourCutCountsCompletedPeriodsWithAMinimumOfOne() {
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED, "24h")).isEqualTo(1);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.plusSeconds(24 * 3600 - 1), "24h")).isEqualTo(1);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.plusSeconds(24 * 3600), "24h")).isEqualTo(1);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.plusSeconds(48 * 3600 - 1), "24h")).isEqualTo(1);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.plusSeconds(48 * 3600), "24h")).isEqualTo(2);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.plusSeconds(72 * 3600 + 5), "24h")).isEqualTo(3);
    }

    @Test
    void aClockBehindTheAdmissionStillBillsOneDay() {
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.minusSeconds(3600), "24h")).isEqualTo(1);
        assertThat(RunningBill.billDays(ADMITTED, ADMITTED.minusSeconds(3 * 86_400L), "midnight")).isEqualTo(1);
    }

    @Test
    void theConfiguredCutDrivesTheBill() {
        Instant now = ADMITTED.plusSeconds(13 * 3600); // 25 Oct 00:00 IST
        assertThat(bill(MIDNIGHT, "gen", GEN, now, List.of(), 0L, 80).billDays()).isEqualTo(2);
        RunningBill every24 = bill(H24, "gen", GEN, now, List.of(), 0L, 80);
        assertThat(every24.billDays()).isEqualTo(1);
        assertThat(every24.bedDayCut()).isEqualTo("24h");
    }

    @Test
    void anUnknownCutValueIsTreatedAsMidnight() {
        RunningBill bill = bill(new ConfigRow(true, "12:00", 0L, 0L), "gen", GEN,
                ADMITTED.plusSeconds(13 * 3600), List.of(), 0L, 80);

        assertThat(bill.bedDayCut()).isEqualTo("midnight");
        assertThat(bill.billDays()).isEqualTo(2);
    }

    // ---- deposit share and alert ----

    @Test
    void depositPercentIsRoundedHalfUpAndNullWithNoDeposit() {
        assertThat(RunningBill.depositPercent(160_000L, 1_000_000L)).isEqualTo(16);
        assertThat(RunningBill.depositPercent(165_000L, 1_000_000L)).isEqualTo(17); // 16.5 -> 17
        assertThat(RunningBill.depositPercent(164_999L, 1_000_000L)).isEqualTo(16);
        assertThat(RunningBill.depositPercent(2_000_000L, 1_000_000L)).isEqualTo(200);
        assertThat(RunningBill.depositPercent(0L, 1_000_000L)).isZero();
        assertThat(RunningBill.depositPercent(160_000L, 0L)).isNull();
    }

    @Test
    void depositLowAtExactlyTheAlertPercentIsLow() {
        // Total 160,000; deposit 200,000 -> exactly 80 percent.
        RunningBill atBoundary = bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 200_000L, 80);
        assertThat(atBoundary.totalPaise() * 100).isEqualTo(200_000L * 80);
        assertThat(atBoundary.depositLow("Self")).isTrue();
        assertThat(atBoundary.depositPercent()).isEqualTo(80);

        // One paisa more deposit: just under 80 percent.
        RunningBill justUnder = bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 200_001L, 80);
        assertThat(justUnder.depositLow("Self")).isFalse();

        // One paisa less: over.
        RunningBill justOver = bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 199_999L, 80);
        assertThat(justOver.depositLow("Self")).isTrue();
    }

    @Test
    void depositLowUsesTheAlertPercentOfTheClinic() {
        // Total 160,000 against deposit 160,000: 100 percent.
        assertThat(bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 160_000L, 100).depositLow("Self")).isTrue();
        assertThat(bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 160_001L, 100).depositLow("Self")).isFalse();
        assertThat(bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 320_000L, 50).depositLow("Self")).isTrue();
        assertThat(bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 320_001L, 50).depositLow("Self")).isFalse();
    }

    @Test
    void withNoDepositASelfPatientIsLowAsSoonAsTheBillIsAboveZero() {
        RunningBill bill = bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 0L, 80);

        assertThat(bill.totalPaise()).isPositive();
        assertThat(bill.depositLow("Self")).isTrue();
        assertThat(bill.depositPercent()).isNull();
    }

    @Test
    void insuranceAndCorporatePatientsAreNeverLow() {
        RunningBill bill = bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 0L, 80);

        assertThat(bill.depositLow("Insurance")).isFalse();
        assertThat(bill.depositLow("Corporate")).isFalse();
        assertThat(bill.depositLow(null)).isFalse();
    }

    @Test
    void depositIsNotLowWhenTheDepositIsFarAboveTheBill() {
        assertThat(bill(MIDNIGHT, "gen", GEN, ADMITTED, List.of(), 1_000_000L, 80).depositLow("Self")).isFalse();
    }
}
