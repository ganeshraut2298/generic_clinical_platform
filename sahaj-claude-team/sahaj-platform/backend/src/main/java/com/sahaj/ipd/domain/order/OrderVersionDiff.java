package com.sahaj.ipd.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SAH-76 (ADR-027 D1, D6): the "Changed: old to new" lines between two versions of one order, and the "nothing was
 * changed" test. Only the fields a change may edit are compared. The client turns a {@link Field} into its label; old
 * and new values are plain text (instants in ISO form). The indication is clinical text: its line says only that it
 * changed (no old or new text), so the diff can be listed without copying it.
 */
public final class OrderVersionDiff {

    /** The compared fields, in the order the lines are listed. */
    public enum Field {
        DOSE_AMOUNT, DOSE_UNIT, ROUTE, FREQUENCY, KIND, MIN_INTERVAL, MAX_PER_24H, PRIORITY, START, END, INDICATION
    }

    /** One changed field. {@code before} and {@code after} are null for INDICATION. */
    public record Line(Field field, String before, String after) {
    }

    private OrderVersionDiff() {
    }

    public static List<Line> diff(MedicineOrderVersion before, MedicineOrderVersion after) {
        List<Line> lines = new ArrayList<>();
        if (!sameNumber(before.doseAmount(), after.doseAmount())) {
            lines.add(new Line(Field.DOSE_AMOUNT, text(before.doseAmount()), text(after.doseAmount())));
        }
        add(lines, Field.DOSE_UNIT, before.doseUnit(), after.doseUnit());
        add(lines, Field.ROUTE, before.routeKey(), after.routeKey());
        add(lines, Field.FREQUENCY, before.frequencyKey(), after.frequencyKey());
        add(lines, Field.KIND, name(before.kind()), name(after.kind()));
        add(lines, Field.MIN_INTERVAL, text(before.minIntervalMin()), text(after.minIntervalMin()));
        add(lines, Field.MAX_PER_24H, text(before.maxPer24h()), text(after.maxPer24h()));
        add(lines, Field.PRIORITY, name(before.priority()), name(after.priority()));
        add(lines, Field.START, text(before.startAt()), text(after.startAt()));
        add(lines, Field.END, text(before.endAt()), text(after.endAt()));
        if (!Objects.equals(before.indication(), after.indication())) {
            lines.add(new Line(Field.INDICATION, null, null));
        }
        return List.copyOf(lines);
    }

    /** True when every compared field is equal: the change would write nothing new (400 "Nothing was changed"). */
    public static boolean isUnchanged(MedicineOrderVersion before, MedicineOrderVersion after) {
        return diff(before, after).isEmpty();
    }

    private static void add(List<Line> lines, Field field, String before, String after) {
        if (!Objects.equals(before, after)) {
            lines.add(new Line(field, before, after));
        }
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String text(Integer value) {
        return value == null ? null : value.toString();
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
