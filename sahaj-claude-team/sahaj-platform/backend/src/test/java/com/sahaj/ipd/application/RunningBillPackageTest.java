package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sahaj.ipd.application.AdmissionRepository.PackageCopy;
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

/**
 * SAH-29 (AC3 to AC5) the running bill of an admission with a package, line by line as the mockup {@code ipLines}:
 * the package line, the bed days beyond the package, the extra charges "not in package"; no doctor visits, no nursing,
 * no bed-transfer segments.
 */
class RunningBillPackageTest {

    private static final UUID ADMISSION = UUID.randomUUID();
    /** 24 Oct 2026, 11:00 AM IST. */
    private static final Instant ADMITTED = Instant.parse("2026-10-24T05:30:00Z");
    private static final ConfigRow MIDNIGHT = new ConfigRow(true, "midnight", 30_000L, 50_000L);
    private static final ConfigRow H24 = new ConfigRow(true, "24h", 30_000L, 50_000L);
    private static final CategoryRow GEN = new CategoryRow("gen", 80_000L, new BigDecimal("1.00"));
    private static final CategoryRow PVT = new CategoryRow("pvt", 250_000L, new BigDecimal("1.50"));
    private static final Map<String, CategoryRow> ALL = Map.of("gen", GEN, "pvt", PVT);
    private static final PackageCopy APPENDICECTOMY = new PackageCopy("Appendicectomy", 4_500_000L, 3);
    private static final PackageCopy DAY_CARE = new PackageCopy("Cataract", 2_500_000L, 0);

    /** Bill day n (midnight cut) is reached on 24 Oct + (n - 1) days, at 11:00 AM IST. */
    private static Instant onDay(int billDay) {
        return ADMITTED.plusSeconds((billDay - 1) * 86_400L);
    }

    private static ChargeRow charge(String name, long unit, int qty) {
        return new ChargeRow(ADMISSION, "svc", name, unit, qty);
    }

    private static RunningBill bill(PackageCopy pkg, String key, ConfigRow config, Instant now,
                                    List<ChargeRow> charges, List<TransferRow> transfers, long paid) {
        return RunningBill.compute(ADMITTED, now, config, key, ALL, "Anil Kumar Rao", charges, transfers, paid, 80,
                pkg);
    }

    private static RunningBill bill(PackageCopy pkg, String key, Instant now, List<ChargeRow> charges) {
        return bill(pkg, key, MIDNIGHT, now, charges, List.of(), 0L);
    }

    @Test
    void theFirstDayIsTheOnePackageLineAtThePackagePriceWithNoVisitOrNursingLine() {
        RunningBill bill = bill(APPENDICECTOMY, "gen", onDay(1), List.of());

        assertThat(bill.billDays()).isEqualTo(1);
        assertThat(bill.lines()).containsExactly(new BillLine(
                "Package · Appendicectomy (bed, nursing and surgeon for 3 days)", 4_500_000L, 1, 4_500_000L));
        assertThat(bill.totalPaise()).isEqualTo(4_500_000L);
    }

    @Test
    void noBedLineWhileTheBillDaysAreBelowOrEqualToTheIncludedDays() {
        assertThat(bill(APPENDICECTOMY, "gen", onDay(2), List.of()).lines()).hasSize(1);
        assertThat(bill(APPENDICECTOMY, "gen", onDay(3), List.of()).lines()).hasSize(1);
    }

    @Test
    void theDaysBeyondThePackageAreBilledAtTheBedChargeOfTheCurrentCategoryWithoutTheMultiplier() {
        // Day 5, 3 days included: 2 days beyond, at the Private bed charge (₹2,500), not 1.5 times that.
        RunningBill bill = bill(APPENDICECTOMY, "pvt", onDay(5), List.of());

        assertThat(bill.billDays()).isEqualTo(5);
        assertThat(bill.categoryName()).isEqualTo("Private");
        assertThat(bill.lines()).containsExactly(
                new BillLine("Package · Appendicectomy (bed, nursing and surgeon for 3 days)", 4_500_000L, 1,
                        4_500_000L),
                new BillLine("Bed · Private beyond the package", 250_000L, 2, 500_000L));
        assertThat(bill.totalPaise()).isEqualTo(5_000_000L);
    }

    @Test
    void aDayCarePackageIncludesOneBedDayAndKeepsTheMockupWordingForZeroDays() {
        RunningBill first = bill(DAY_CARE, "gen", onDay(1), List.of());
        RunningBill third = bill(DAY_CARE, "gen", onDay(3), List.of());

        assertThat(first.lines()).extracting(BillLine::name)
                .containsExactly("Package · Cataract (bed, nursing and surgeon for 0 days)");
        assertThat(third.lines()).extracting(BillLine::name, BillLine::qty).containsExactly(
                tuple("Package · Cataract (bed, nursing and surgeon for 0 days)", 1),
                tuple("Bed · General beyond the package", 2));
    }

    @Test
    void aOneDayPackageKeepsTheMockupWordingForOneDays() {
        RunningBill bill = bill(new PackageCopy("Dressing", 100_000L, 1), "gen", onDay(2), List.of());

        assertThat(bill.lines()).extracting(BillLine::name).containsExactly(
                "Package · Dressing (bed, nursing and surgeon for 1 days)", "Bed · General beyond the package");
        assertThat(bill.lines().get(1).qty()).isEqualTo(1);
    }

    @Test
    void extraChargesAreNamedNotInPackageAndRepricedAtTheCurrentMultiplierRoundedHalfUp() {
        // Day 1. CBC ₹300 x 1.5 = ₹450 each, twice; Physiotherapy 401 paise x 1.5 = 601.5 -> 602.
        RunningBill bill = bill(APPENDICECTOMY, "pvt", onDay(1), List.of(charge("CBC", 30_000L, 1),
                charge("Physiotherapy session", 401L, 1), charge("CBC", 30_000L, 1)));

        assertThat(bill.lines()).containsExactly(
                new BillLine("Package · Appendicectomy (bed, nursing and surgeon for 3 days)", 4_500_000L, 1,
                        4_500_000L),
                new BillLine("CBC · not in package", 45_000L, 2, 90_000L),
                new BillLine("Physiotherapy session · not in package", 602L, 1, 602L));
        assertThat(bill.totalPaise()).isEqualTo(4_590_602L);
    }

    @Test
    void theLinesComeInTheOrderPackageThenBedBeyondThenExtraCharges() {
        RunningBill bill = bill(APPENDICECTOMY, "gen", onDay(4), List.of(charge("X-ray", 50_000L, 1)));

        assertThat(bill.lines()).extracting(BillLine::name).containsExactly(
                "Package · Appendicectomy (bed, nursing and surgeon for 3 days)",
                "Bed · General beyond the package", "X-ray · not in package");
    }

    @Test
    void aChargeOfQuantityZeroIsDropped() {
        RunningBill bill = bill(APPENDICECTOMY, "gen", onDay(1), List.of(charge("CBC", 30_000L, 0)));

        assertThat(bill.lines()).hasSize(1);
    }

    @Test
    void aTransferNeverAddsSegmentLinesAndTheDaysBeyondAreBilledAtTheBedThePatientIsInNow() {
        // Moved from General to Private after 2 days; the stored segment_days is ignored by the package bill.
        List<TransferRow> transfers = List.of(new TransferRow(ADMISSION, "gen", 2));
        RunningBill bill = bill(APPENDICECTOMY, "pvt", MIDNIGHT, onDay(5),
                List.of(charge("CBC", 30_000L, 1)), transfers, 0L);

        assertThat(bill.lines()).containsExactly(
                new BillLine("Package · Appendicectomy (bed, nursing and surgeon for 3 days)", 4_500_000L, 1,
                        4_500_000L),
                new BillLine("Bed · Private beyond the package", 250_000L, 2, 500_000L),
                new BillLine("CBC · not in package", 45_000L, 1, 45_000L));
        assertThat(bill.lines()).noneMatch(BillLine::segment);
        assertThat(bill.lines()).extracting(BillLine::name)
                .noneMatch(n -> n.startsWith("Doctor visits") || n.equals("Nursing care")
                        || n.contains("before transfer"));
    }

    @Test
    void theTwentyFourHourCutCountsTheBillDaysForTheBedBeyondTheIncludedDays() {
        // 4 x 24 hours since admission: the 24-hour cut says 4 days, so 1 day beyond a 3-day package.
        RunningBill bill = bill(APPENDICECTOMY, "gen", H24, ADMITTED.plusSeconds(4 * 86_400L + 5), List.of(),
                List.of(), 0L);

        assertThat(bill.bedDayCut()).isEqualTo("24h");
        assertThat(bill.billDays()).isEqualTo(4);
        assertThat(bill.lines().get(1)).isEqualTo(new BillLine("Bed · General beyond the package", 80_000L, 1,
                80_000L));
    }

    @Test
    void theMidnightCutMovesToBillDay2AtIstMidnight() {
        // Admitted 24 Oct 11:00 IST; at 25 Oct 00:05 IST it is already bill day 2 under the midnight cut.
        Instant justAfterMidnight = Instant.parse("2026-10-24T18:35:00Z");
        RunningBill bill = bill(DAY_CARE, "gen", justAfterMidnight, List.of());

        assertThat(bill.billDays()).isEqualTo(2);
        assertThat(bill.lines()).extracting(BillLine::name).contains("Bed · General beyond the package");
    }

    @Test
    void thePackagePriceCountsFromTheFirstDayInTheDepositPercentAndTheLowDepositCheck() {
        RunningBill bill = bill(APPENDICECTOMY, "gen", MIDNIGHT, onDay(1), List.of(), List.of(), 5_000_000L);

        assertThat(bill.totalPaise()).isEqualTo(4_500_000L);
        assertThat(bill.depositPercent()).isEqualTo(90);
        assertThat(bill.depositLow("Self")).isTrue();
        assertThat(bill.depositLow("Insurance")).isFalse();
    }

    @Test
    void aFreePackageStillShowsItsLineAtZero() {
        RunningBill bill = bill(new PackageCopy("Camp", 0L, 2), "gen", onDay(1), List.of());

        assertThat(bill.lines()).containsExactly(
                new BillLine("Package · Camp (bed, nursing and surgeon for 2 days)", 0L, 1, 0L));
        assertThat(bill.totalPaise()).isZero();
    }

    @Test
    void withoutAPackageTheBillIsTheOneOfSah25AndSah26() {
        RunningBill withNull = bill(null, "gen", onDay(2), List.of(charge("CBC", 30_000L, 1)));
        RunningBill old = RunningBill.compute(ADMITTED, onDay(2), MIDNIGHT, "gen", ALL, "Anil Kumar Rao",
                List.of(charge("CBC", 30_000L, 1)), List.of(), 0L, 80);

        assertThat(withNull).isEqualTo(old);
        assertThat(withNull.lines()).extracting(BillLine::name)
                .containsExactly("Bed · General", "Doctor visits · Anil Kumar", "Nursing care", "CBC");
    }

    @Test
    void theHeadingFactsOfTheBillAreUnchangedByAPackage() {
        RunningBill bill = bill(APPENDICECTOMY, "pvt", onDay(2), List.of());

        assertThat(bill.categoryName()).isEqualTo("Private");
        assertThat(bill.multiplier()).isEqualByComparingTo("1.50");
        assertThat(bill.bedDayCut()).isEqualTo("midnight");
        assertThat(bill.alertPercent()).isEqualTo(80);
    }
}
