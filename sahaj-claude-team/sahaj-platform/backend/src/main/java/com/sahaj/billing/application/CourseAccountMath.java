package com.sahaj.billing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The arithmetic of a treatment course's running account (SAH-58; ADR-022 D12; rules B-1, tagged, not reviewed). All
 * money is whole paise in {@code long}; the screen shows whole rupees. Pure: the same inputs always give the same
 * account, and the invariant {@code paid - chargesDone = credit - dueFromEarlier} holds for every input.
 *
 * <p>A sitting settled in IPD (a day-care or IPD sitting linked to an admission) is billed on the IPD bill, so its
 * charge is out of the estimate and the charges done (ADR-022 H1). Credit carried to an admission is taken off what
 * was paid. The "advance used" lines of the ledger are derived: the paid money is allocated to the DONE sittings in the
 * order they were done, so the ledger always adds up to the credit left.
 */
public final class CourseAccountMath {

    public static final long RUPEE = 100;
    public static final long MIN_PART_PAYMENT = 500 * RUPEE;
    private static final long DEPOSIT_STEP = 500 * RUPEE;

    private CourseAccountMath() {
    }

    /** {@code status} is PLANNED, TODAY or DONE. {@code doneAt} orders the DONE sittings (null sorts last). */
    public record SittingInput(int sittingNo, long chargePaise, String status, boolean settledInIpd, Instant doneAt) {
    }

    public record PaymentInput(long receiptNo, long amountPaise, Instant at) {
    }

    public record TransferInput(long amountPaise, Instant at) {
    }

    /** One ledger line: {@code kind} PAYMENT, CARRIED_TO_IPD or ADVANCE_USED; the amount is signed. */
    public record LedgerLine(String kind, long amountPaise, Instant at, Long receiptNo, Integer sittingNo) {
    }

    public record Account(long estimatePaise, long paidPaise, long chargesDonePaise, long creditPaise,
                          long dueFromEarlierPaise, long todayChargePaise, long dueTodayPaise, long balancePaise,
                          long carriedToIpdPaise, List<LedgerLine> ledger) {
    }

    public static Account compute(List<SittingInput> sittings, List<PaymentInput> payments,
                                  List<TransferInput> transfers) {
        long estimate = 0;
        long done = 0;
        long today = 0;
        for (SittingInput s : sittings) {
            if (s.settledInIpd()) {
                continue;
            }
            estimate += s.chargePaise();
            if ("DONE".equals(s.status())) {
                done += s.chargePaise();
            } else if ("TODAY".equals(s.status())) {
                today += s.chargePaise();
            }
        }
        long received = payments.stream().mapToLong(PaymentInput::amountPaise).sum();
        long carried = transfers.stream().mapToLong(TransferInput::amountPaise).sum();
        long paid = Math.max(0, received - carried);
        long credit = Math.max(0, paid - done);
        long dueFromEarlier = Math.max(0, done - paid);
        long dueToday = Math.max(0, done + today - paid);
        long balance = Math.max(0, estimate - paid);
        return new Account(estimate, paid, done, credit, dueFromEarlier, today, dueToday, balance, carried,
                ledger(sittings, payments, transfers, paid));
    }

    private static List<LedgerLine> ledger(List<SittingInput> sittings, List<PaymentInput> payments,
                                           List<TransferInput> transfers, long paid) {
        List<LedgerLine> lines = new ArrayList<>();
        for (PaymentInput p : payments) {
            lines.add(new LedgerLine("PAYMENT", p.amountPaise(), p.at(), p.receiptNo(), null));
        }
        for (TransferInput t : transfers) {
            lines.add(new LedgerLine("CARRIED_TO_IPD", -t.amountPaise(), t.at(), null, null));
        }
        long remaining = paid;
        List<SittingInput> done = sittings.stream().filter(s -> "DONE".equals(s.status()) && !s.settledInIpd())
                .sorted(Comparator.comparing(SittingInput::doneAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(SittingInput::sittingNo)).toList();
        for (SittingInput s : done) {
            long used = Math.min(s.chargePaise(), remaining);
            if (used > 0) {
                lines.add(new LedgerLine("ADVANCE_USED", -used, s.doneAt(), null, s.sittingNo()));
                remaining -= used;
            }
        }
        lines.sort(Comparator.comparing(LedgerLine::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(lines);
    }

    /** Part payment = half of today's amount to the nearest rupee hundred, at least Rs 500, at most the balance. */
    public static long suggestedPart(long dueTodayPaise, long balancePaise) {
        long half = Math.round(dueTodayPaise / 2.0 / (100 * RUPEE)) * (100 * RUPEE);
        return Math.min(balancePaise, Math.max(MIN_PART_PAYMENT, half));
    }

    /** IPD deposit = 40% of the next planned admission sitting's charge, to the nearest Rs 500 (Math.round). */
    public static long suggestedIpdDeposit(long admissionChargePaise) {
        return Math.round(admissionChargePaise * 0.4 / DEPOSIT_STEP) * DEPOSIT_STEP;
    }

    /** Whole rupees, positive, at most Rs 10 crore (paise: 10,000,000,000). */
    public static boolean validAmount(long amountPaise) {
        return amountPaise > 0 && amountPaise % RUPEE == 0 && amountPaise <= 10_000_000_000L;
    }

    /** VISIT_PAYMENT, FULL_BALANCE and PART_PAYMENT may not exceed the balance; ADVANCE and IPD_DEPOSIT may. */
    public static boolean overBalance(String type, long amountPaise, long balancePaise) {
        boolean capped = "VISIT_PAYMENT".equals(type) || "FULL_BALANCE".equals(type) || "PART_PAYMENT".equals(type);
        return capped && amountPaise > balancePaise;
    }
}
