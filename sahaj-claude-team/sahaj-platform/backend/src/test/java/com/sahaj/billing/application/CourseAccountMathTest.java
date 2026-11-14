package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.billing.application.CourseAccountMath.Account;
import com.sahaj.billing.application.CourseAccountMath.LedgerLine;
import com.sahaj.billing.application.CourseAccountMath.PaymentInput;
import com.sahaj.billing.application.CourseAccountMath.SittingInput;
import com.sahaj.billing.application.CourseAccountMath.TransferInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CourseAccountMathTest {

    private static final long R = 100;
    private static final Instant T0 = Instant.parse("2026-11-01T06:00:00Z");

    private static Instant day(int n) {
        return T0.plusSeconds(n * 86_400L);
    }

    private static SittingInput sitting(int no, long rupees, String status, int doneDay) {
        return new SittingInput(no, rupees * R, status, false, "DONE".equals(status) ? day(doneDay) : null);
    }

    private static PaymentInput pay(long no, long rupees, int d) {
        return new PaymentInput(no, rupees * R, day(d));
    }

    @Test
    void theMockupRootCanalCourseWorksOutToTheRupee() {
        List<SittingInput> sittings = List.of(sitting(1, 1500, "DONE", 1), sitting(2, 4500, "DONE", 5),
                sitting(3, 2000, "TODAY", 0), sitting(4, 8000, "PLANNED", 0));

        Account account = CourseAccountMath.compute(sittings, List.of(pay(1, 5000, 1), pay(2, 1000, 2)), List.of());

        assertThat(account.estimatePaise()).isEqualTo(16_000 * R);
        assertThat(account.paidPaise()).isEqualTo(6_000 * R);
        assertThat(account.chargesDonePaise()).isEqualTo(6_000 * R);
        assertThat(account.creditPaise()).isZero();
        assertThat(account.dueFromEarlierPaise()).isZero();
        assertThat(account.todayChargePaise()).isEqualTo(2_000 * R);
        assertThat(account.dueTodayPaise()).isEqualTo(2_000 * R);
        assertThat(account.balancePaise()).isEqualTo(10_000 * R);
    }

    @Test
    void advancePaidAheadShowsAsCreditAndUnpaidDoneChargesShowAsDueFromEarlier() {
        List<SittingInput> ahead = List.of(sitting(1, 1500, "DONE", 1), sitting(2, 2000, "TODAY", 0));
        Account credit = CourseAccountMath.compute(ahead, List.of(pay(1, 5000, 0)), List.of());
        assertThat(credit.creditPaise()).isEqualTo(3_500 * R);
        assertThat(credit.dueTodayPaise()).isZero();

        Account due = CourseAccountMath.compute(ahead, List.of(pay(1, 500, 0)), List.of());
        assertThat(due.dueFromEarlierPaise()).isEqualTo(1_000 * R);
        assertThat(due.dueTodayPaise()).isEqualTo(3_000 * R);
        assertThat(due.creditPaise()).isZero();
    }

    @Test
    void aSittingSettledInIpdLeavesTheEstimateAndTheChargesDone() {
        List<SittingInput> sittings = List.of(sitting(1, 1500, "DONE", 1),
                new SittingInput(2, 40_000 * R, "DONE", true, day(3)), sitting(3, 1000, "PLANNED", 0));

        Account account = CourseAccountMath.compute(sittings, List.of(pay(1, 1500, 1)), List.of());

        assertThat(account.estimatePaise()).isEqualTo(2_500 * R);
        assertThat(account.chargesDonePaise()).isEqualTo(1_500 * R);
        assertThat(account.balancePaise()).isEqualTo(1_000 * R);
    }

    @Test
    void creditCarriedToIpdIsTakenOffWhatWasPaid() {
        List<SittingInput> sittings = List.of(sitting(1, 1000, "DONE", 1), sitting(2, 50_000, "PLANNED", 0));

        Account account = CourseAccountMath.compute(sittings,
                List.of(pay(1, 1000, 1), pay(2, 20_000, 2)), List.of(new TransferInput(20_000 * R, day(4))));

        assertThat(account.paidPaise()).isEqualTo(1_000 * R);
        assertThat(account.carriedToIpdPaise()).isEqualTo(20_000 * R);
        assertThat(account.creditPaise()).isZero();
        assertThat(account.ledger()).extracting(LedgerLine::kind).contains("CARRIED_TO_IPD");
    }

    @Test
    void theLedgerAddsUpToTheCreditAndTheInvariantHoldsForManyShapes() {
        Random random = new Random(58);
        for (int round = 0; round < 2_000; round++) {
            List<SittingInput> sittings = new ArrayList<>();
            int count = 1 + random.nextInt(6);
            for (int i = 1; i <= count; i++) {
                String status = new String[] {"DONE", "DONE", "TODAY", "PLANNED"}[random.nextInt(4)];
                boolean ipd = random.nextInt(6) == 0;
                sittings.add(new SittingInput(i, (1 + random.nextInt(500)) * R, status, ipd, day(random.nextInt(30))));
            }
            List<PaymentInput> payments = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < random.nextInt(5); i++) {
                long amount = (1 + random.nextInt(800)) * R;
                total += amount;
                payments.add(new PaymentInput(i + 1, amount, day(random.nextInt(30))));
            }
            List<TransferInput> transfers = new ArrayList<>();
            if (total > 0 && random.nextBoolean()) {
                transfers.add(new TransferInput(Math.max(R, (total / R / 2) * R), day(31)));
            }

            Account a = CourseAccountMath.compute(sittings, payments, transfers);

            assertThat(a.paidPaise() - a.chargesDonePaise()).as("round %d", round)
                    .isEqualTo(a.creditPaise() - a.dueFromEarlierPaise());
            assertThat(a.ledger().stream().mapToLong(LedgerLine::amountPaise).sum()).as("ledger %d", round)
                    .isEqualTo(a.creditPaise());
            assertThat(a.balancePaise()).isEqualTo(Math.max(0, a.estimatePaise() - a.paidPaise()));
            assertThat(a.creditPaise() == 0 || a.dueFromEarlierPaise() == 0).isTrue();
        }
    }

    @Test
    void suggestionsFollowTheTaggedRules() {
        assertThat(CourseAccountMath.suggestedPart(200_000, 1_000_000)).isEqualTo(100_000);
        assertThat(CourseAccountMath.suggestedPart(30_000, 1_000_000)).isEqualTo(50_000);
        assertThat(CourseAccountMath.suggestedPart(200_000, 70_000)).isEqualTo(70_000);
        assertThat(CourseAccountMath.suggestedPart(455_000, 1_000_000)).isEqualTo(230_000);
        assertThat(CourseAccountMath.suggestedIpdDeposit(4_000_000)).isEqualTo(1_600_000);
        assertThat(CourseAccountMath.suggestedIpdDeposit(3_500_000)).isEqualTo(1_400_000);
        assertThat(CourseAccountMath.suggestedIpdDeposit(3_700_000)).isEqualTo(1_500_000);
    }

    @Test
    void amountsAreWholePositiveRupeesUpToTenCroreAndOnlyThreeTypesAreCappedByTheBalance() {
        assertThat(CourseAccountMath.validAmount(150_000)).isTrue();
        assertThat(CourseAccountMath.validAmount(150)).isFalse();
        assertThat(CourseAccountMath.validAmount(0)).isFalse();
        assertThat(CourseAccountMath.validAmount(-100)).isFalse();
        assertThat(CourseAccountMath.validAmount(10_000_000_100L)).isFalse();
        assertThat(CourseAccountMath.overBalance("PART_PAYMENT", 200_000, 150_000)).isTrue();
        assertThat(CourseAccountMath.overBalance("FULL_BALANCE", 150_000, 150_000)).isFalse();
        assertThat(CourseAccountMath.overBalance("ADVANCE", 900_000, 150_000)).isFalse();
        assertThat(CourseAccountMath.overBalance("IPD_DEPOSIT", 900_000, 0)).isFalse();
    }
}
