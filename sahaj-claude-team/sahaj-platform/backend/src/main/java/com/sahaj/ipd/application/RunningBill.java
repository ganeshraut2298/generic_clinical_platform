package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.PackageCopy;
import com.sahaj.ipd.application.ChargeRepository.ChargeRow;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAH-25 running bill (contract {@code RunningBill}). The one place where the bill money is worked out, so that the
 * Admitted list, the bed board, the tile and the admission sheet always agree. Computed on read, never stored.
 * Money is integer paise; the category multiplier is rounded half-up to whole paise per unit price.
 */
public record RunningBill(int billDays, String bedDayCut, String categoryName, BigDecimal multiplier,
                          List<BillLine> lines, long totalPaise, long paidPaise, Integer depositPercent,
                          int alertPercent) {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The charges of one service at one base price, added up while the bill is worked out. */
    private static final class ChargeGroup {
        private final String name;
        private final long unitPaise;
        private long qty;

        private ChargeGroup(String name, long unitPaise) {
            this.name = name;
            this.unitPaise = unitPaise;
        }
    }

    /**
     * One line of the bill; {@code amountPaise} is {@code unitPaise} times {@code qty}. {@code segment} (SAH-26) is
     * true for the lines of a bed the patient was moved away from.
     */
    public record BillLine(String name, long unitPaise, int qty, long amountPaise, boolean segment) {

        public BillLine(String name, long unitPaise, int qty, long amountPaise) {
            this(name, unitPaise, qty, amountPaise, false);
        }
    }

    /**
     * Works out the bill.
     *
     * @param admittedAt   the admission time kept by the database
     * @param now          the current instant
     * @param config       the clinic's IPD settings (bed day cut, nursing and visit charges)
     * @param categoryKey  the category of the bed the patient is in
     * @param categories   the current rate and multiplier of every category (the one of {@code categoryKey} and the
     *                     ones the patient was moved away from)
     * @param doctorName   the admitting doctor's name; the line shows the first two words
     * @param charges      the extra charges added so far
     * @param transfers    the bed transfers of the admission, oldest first (SAH-26)
     * @param paidPaise    the total of the deposits
     * @param alertPercent the deposit alert percent
     */
    static RunningBill compute(Instant admittedAt, Instant now, ConfigRow config, String categoryKey,
                               Map<String, CategoryRow> categories, String doctorName, List<ChargeRow> charges,
                               List<TransferRow> transfers, long paidPaise, int alertPercent) {
        return compute(admittedAt, now, config, categoryKey, categories, doctorName, charges, transfers, paidPaise,
                alertPercent, null);
    }

    /**
     * As above, for an admission that may have a package (SAH-29, mockup {@code ipLines}). With a package the bill is
     * the package line, the bed days beyond the package at the rate of the bed the patient is in now, and the extra
     * charges "not in package"; there are no doctor visit, nursing or bed-transfer lines, and {@code transfers} is
     * never read. With {@code null} the bill is the one of SAH-25/26.
     *
     * @param packageCopy the package copy kept on the admission, or null
     */
    static RunningBill compute(Instant admittedAt, Instant now, ConfigRow config, String categoryKey,
                               Map<String, CategoryRow> categories, String doctorName, List<ChargeRow> charges,
                               List<TransferRow> transfers, long paidPaise, int alertPercent,
                               PackageCopy packageCopy) {
        String cut = cutOf(config);
        int days = billDays(admittedAt, now, cut);
        CategoryRow category = categories.get(categoryKey);
        String categoryName = IpdService.NAMES.get(categoryKey);
        BigDecimal multiplier = category.multiplier();

        List<BillLine> lines = new ArrayList<>();
        if (packageCopy != null) {
            packageLines(lines, packageCopy, days, categoryName, category, multiplier, charges);
            return finish(days, cut, categoryName, multiplier, lines, paidPaise, alertPercent);
        }
        // SAH-26: the days fixed on earlier beds at transfer time come first, one pair of lines per transfer.
        long segmentTotal = 0L;
        for (TransferRow transfer : transfers) {
            segmentTotal += transfer.segmentDays();
            CategoryRow old = categories.get(transfer.fromCategoryKey());
            String oldName = IpdService.NAMES.get(transfer.fromCategoryKey());
            addLine(lines, "Bed · " + oldName + " (before transfer)", old.ratePaise(), transfer.segmentDays(), true);
            addLine(lines, "Doctor visits · " + oldName + " rate", scaled(config.visitPaise(), old.multiplier()),
                    transfer.segmentDays(), true);
        }
        int currentDays = (int) Math.max(0L, days - segmentTotal);
        addLine(lines, "Bed · " + categoryName, category.ratePaise(), currentDays);
        addLine(lines, "Doctor visits · " + BedBoardService.shortName(doctorName),
                scaled(config.visitPaise(), multiplier), currentDays);
        addLine(lines, "Nursing care", config.nursingPaise(), days);

        addChargeLines(lines, charges, multiplier, "");
        return finish(days, cut, categoryName, multiplier, lines, paidPaise, alertPercent);
    }

    /**
     * SAH-29 (AC3): the lines of an admission with a package, in order: the package at its price; the bed days beyond the
     * package (a day-care package includes 1 bed day) at the bed charge of the category the patient is in now, no
     * multiplier; then the extra charges named "... · not in package", priced as without a package. A line of
     * quantity 0 is dropped. The wording is the mockup's, also for 0 and 1 days (OQ-29-4).
     */
    private static void packageLines(List<BillLine> lines, PackageCopy packageCopy, int days, String categoryName,
                                     CategoryRow category, BigDecimal multiplier, List<ChargeRow> charges) {
        addLine(lines, "Package · " + packageCopy.name() + " (bed, nursing and surgeon for "
                + packageCopy.includedDays() + " days)", packageCopy.pricePaise(), 1);
        int beyond = Math.max(0, days - Math.max(1, packageCopy.includedDays()));
        addLine(lines, "Bed · " + categoryName + " beyond the package", category.ratePaise(), beyond);
        addChargeLines(lines, charges, multiplier, " · not in package");
    }

    /** One line per service name (and base price, in case a price was ever changed), in order of first charge. */
    private static void addChargeLines(List<BillLine> lines, List<ChargeRow> charges, BigDecimal multiplier,
                                       String nameSuffix) {
        Map<String, ChargeGroup> grouped = new LinkedHashMap<>();
        for (ChargeRow charge : charges) {
            grouped.computeIfAbsent(charge.serviceName() + "|" + charge.unitPaise(),
                    k -> new ChargeGroup(charge.serviceName(), charge.unitPaise())).qty += charge.qty();
        }
        for (ChargeGroup group : grouped.values()) {
            addLine(lines, group.name + nameSuffix, scaled(group.unitPaise, multiplier), Math.toIntExact(group.qty));
        }
    }

    private static RunningBill finish(int days, String cut, String categoryName, BigDecimal multiplier,
                                      List<BillLine> lines, long paidPaise, int alertPercent) {
        long total = 0L;
        for (BillLine line : lines) {
            total += line.amountPaise();
        }
        return new RunningBill(days, cut, categoryName, multiplier, List.copyOf(lines), total, paidPaise,
                depositPercent(total, paidPaise), alertPercent);
    }

    /** The bed day cut of the clinic: {@code "24h"} or, for anything else, {@code "midnight"}. */
    static String cutOf(ConfigRow config) {
        return "24h".equals(config.bedDayCut()) ? "24h" : "midnight";
    }

    /** The bill days at {@code now} under the clinic's bed day cut (the days the running bill would show). */
    static int billDaysNow(Instant admittedAt, Instant now, ConfigRow config) {
        return billDays(admittedAt, now, cutOf(config));
    }

    /**
     * Midnight cut: Asia/Kolkata calendar days from the admission day to today, inclusive (day 1 on the admission
     * day). 24-hour cut: completed 24-hour periods since admission, at least 1.
     */
    static int billDays(Instant admittedAt, Instant now, String cut) {
        if ("24h".equals(cut)) {
            long periods = Duration.between(admittedAt, now).toHours() / 24;
            return (int) Math.max(1L, periods);
        }
        long days = ChronoUnit.DAYS.between(admittedAt.atZone(IST).toLocalDate(), now.atZone(IST).toLocalDate());
        return (int) Math.max(0L, days) + 1;
    }

    /** Bill as a share of the deposit, rounded half-up; null when nothing was paid. */
    static Integer depositPercent(long totalPaise, long paidPaise) {
        if (paidPaise <= 0) {
            return null;
        }
        long percent = (totalPaise * 200 + paidPaise) / (2 * paidPaise);
        return (int) Math.min(percent, Integer.MAX_VALUE);
    }

    /** True when the payer is Self and {@code total x 100 >= deposit x alertPercent}. With no deposit that holds. */
    public boolean depositLow(String payer) {
        return "Self".equals(payer) && totalPaise * 100 >= paidPaise * alertPercent;
    }

    /** Unit price times the multiplier, half-up to whole paise. */
    static long scaled(long paise, BigDecimal multiplier) {
        return BigDecimal.valueOf(paise).multiply(multiplier).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void addLine(List<BillLine> lines, String name, long unitPaise, int qty) {
        addLine(lines, name, unitPaise, qty, false);
    }

    private static void addLine(List<BillLine> lines, String name, long unitPaise, int qty, boolean segment) {
        if (qty > 0) {
            lines.add(new BillLine(name, unitPaise, qty, unitPaise * qty, segment));
        }
    }
}
