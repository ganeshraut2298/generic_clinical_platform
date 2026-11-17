package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.MedicineViews.OrderDetails;
import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderVersionDiff;
import com.sahaj.ipd.domain.order.OrderVersionDiff.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * SAH-76 (ADR-027 D1, D6): the display shape of an order version, shared by the EMR sheet, the pharmacist queue, the
 * review list and the nurse notices. Pure: version in, view out. The labels come from fixed lists; the indication is the
 * only free text (clinical, on screen only, never logged).
 */
public final class OrderViews {

    static final String DOSE_AS_NAMED = "As in the medicine name";
    static final String UNTIL_STOPPED = "Until stopped";
    static final String NONE = "None";
    static final String STAT_LABEL = "STAT";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
            .withZone(IST);

    private OrderViews() {
    }

    /** {@code pharmacistChecked}: false when the version needs no check, true when verified, null while waiting. */
    public static OrderDetails details(MedicineOrderVersion v) {
        Boolean checked = v.verification() == Verification.NOT_REQUIRED ? Boolean.FALSE
                : v.isVerified() ? Boolean.TRUE : null;
        return new OrderDetails(v.kind().name(), v.doseAmount(), v.doseUnit(), v.routeKey(), routeLabel(v.routeKey()),
                frequencyKey(v), frequencyLabel(v), v.startAt(), v.endAt(), durationDays(v), v.indication(),
                v.priority().name(), v.minIntervalMin(), v.maxPer24h(), checked);
    }

    /** The key sent back on a change: the frequency, "prn" for as needed, null for a STAT (ONCE) version. */
    public static String frequencyKey(MedicineOrderVersion v) {
        if (v.kind() == Kind.PRN) {
            return Frequency.PRN.key();
        }
        return v.kind() == Kind.ONCE ? null : v.frequencyKey();
    }

    public static String frequencyLabel(MedicineOrderVersion v) {
        if (v.kind() == Kind.PRN) {
            return Frequency.PRN.label();
        }
        if (v.kind() == Kind.ONCE) {
            return STAT_LABEL;
        }
        return Frequency.ofKey(v.frequencyKey()).map(Frequency::label).orElse(v.frequencyKey());
    }

    public static String routeLabel(String routeKey) {
        return RouteKey.ofKey(routeKey).map(RouteKey::label).orElse(routeKey);
    }

    /** "1 tablet", or "As in the medicine name" for a version with no dose (built orders, port orders). */
    public static String doseText(MedicineOrderVersion v) {
        if (v.doseAmount() == null || v.doseUnit() == null) {
            return DOSE_AS_NAMED;
        }
        return DoseUnit.ofKey(v.doseUnit()).map(u -> u.display(v.doseAmount()))
                .orElse(v.doseAmount().stripTrailingZeros().toPlainString() + " " + v.doseUnit());
    }

    /** Whole days from start to end; null when the order runs until stopped (or for a one-time order). */
    static Integer durationDays(MedicineOrderVersion v) {
        if (v.endAt() == null) {
            return null;
        }
        long days = Duration.between(v.startAt(), v.endAt()).toDays();
        return days < 1 ? null : (int) days;
    }

    /** "24 Oct, 8:00 PM" in IST. */
    public static String when(Instant instant) {
        return WHEN.format(instant);
    }

    /**
     * The "Changed: label: old to new" lines between two versions, in the order of the contract. The dose amount and unit
     * make one "dose" line, the frequency and the kind one "frequency" line.
     */
    public static List<OrderChangeLine> changes(MedicineOrderVersion before, MedicineOrderVersion after) {
        Set<Field> changed = EnumSet.noneOf(Field.class);
        for (OrderVersionDiff.Line line : OrderVersionDiff.diff(before, after)) {
            changed.add(line.field());
        }
        List<OrderChangeLine> lines = new ArrayList<>();
        if (changed.contains(Field.DOSE_AMOUNT) || changed.contains(Field.DOSE_UNIT)) {
            lines.add(new OrderChangeLine("dose", "Dose", doseText(before), doseText(after)));
        }
        if (changed.contains(Field.ROUTE)) {
            lines.add(new OrderChangeLine("route", "Route", routeLabel(before.routeKey()),
                    routeLabel(after.routeKey())));
        }
        if (changed.contains(Field.FREQUENCY) || changed.contains(Field.KIND)) {
            lines.add(new OrderChangeLine("frequency", "How often", frequencyLabel(before), frequencyLabel(after)));
        }
        // A new version starts when it is made, so its start always differs from the earlier one: the start is listed
        // only when the doctor chose a later one. The end moves with the start, so it is listed only when the duration
        // differs ("for 5 days" kept stays unlisted).
        if (changed.contains(Field.START) && after.startAt().isAfter(after.createdAt())) {
            lines.add(new OrderChangeLine("start", "Start", when(before.startAt()), when(after.startAt())));
        }
        if (changed.contains(Field.END) && !Objects.equals(durationDays(before), durationDays(after))) {
            lines.add(new OrderChangeLine("end", "End", endText(before), endText(after)));
        }
        if (changed.contains(Field.INDICATION)) {
            lines.add(new OrderChangeLine("indication", "Indication", textOrNone(before.indication()),
                    textOrNone(after.indication())));
        }
        if (changed.contains(Field.PRIORITY)) {
            lines.add(new OrderChangeLine("priority", "Priority", priorityLabel(before.priority()),
                    priorityLabel(after.priority())));
        }
        if (changed.contains(Field.MIN_INTERVAL)) {
            lines.add(new OrderChangeLine("prnInterval", "Minimum interval", minutesText(before.minIntervalMin()),
                    minutesText(after.minIntervalMin())));
        }
        if (changed.contains(Field.MAX_PER_24H)) {
            lines.add(new OrderChangeLine("prnMax", "Most doses in 24 hours", numberText(before.maxPer24h()),
                    numberText(after.maxPer24h())));
        }
        return List.copyOf(lines);
    }

    static String priorityLabel(OrderPriority priority) {
        switch (priority) {
            case STAT:
                return STAT_LABEL;
            case URGENT:
                return "Urgent";
            default:
                return "Routine";
        }
    }

    private static String endText(MedicineOrderVersion v) {
        return v.endAt() == null ? UNTIL_STOPPED : when(v.endAt());
    }

    private static String textOrNone(String text) {
        return text == null ? NONE : text;
    }

    private static String numberText(Integer value) {
        return value == null ? NONE : value.toString();
    }

    /** "6 h", "1 h 30 min", "45 min". */
    static String minutesText(Integer minutes) {
        if (minutes == null) {
            return NONE;
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " min";
        }
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }
}
