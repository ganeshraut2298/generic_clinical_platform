package com.sahaj.ipd.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderVersionDiff.Field;
import com.sahaj.ipd.domain.order.OrderVersionDiff.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderVersionDiffTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-15T04:00:00Z");

    private static MedicineOrderVersion v(int no, String dose, String route, String indication, OrderPriority p) {
        return new MedicineOrderVersion(ORDER, no, Kind.SCHEDULED, "bd", new BigDecimal(dose), "mg", route, START, null,
                indication, p, null, null, null, UUID.randomUUID(), START, null, Verification.REQUIRED, null, null);
    }

    @Test
    void equalVersionsGiveNoLinesAndAreUnchanged() {
        MedicineOrderVersion a = v(1, "500", "oral", "pain", OrderPriority.ROUTINE);
        MedicineOrderVersion b = v(2, "500.00", "oral", "pain", OrderPriority.ROUTINE);
        assertThat(OrderVersionDiff.diff(a, b)).isEmpty();
        assertThat(OrderVersionDiff.isUnchanged(a, b)).isTrue();
    }

    @Test
    void changedFieldsAreListedInOrderWithOldAndNew() {
        MedicineOrderVersion a = v(1, "500", "oral", "pain", OrderPriority.ROUTINE);
        MedicineOrderVersion b = v(2, "650", "iv", "pain", OrderPriority.URGENT);
        assertThat(OrderVersionDiff.diff(a, b)).containsExactly(
                new Line(Field.DOSE_AMOUNT, "500", "650"),
                new Line(Field.ROUTE, "oral", "iv"),
                new Line(Field.PRIORITY, "ROUTINE", "URGENT"));
    }

    @Test
    void indicationChangeNeverCopiesTheText() {
        List<Line> lines = OrderVersionDiff.diff(v(1, "500", "oral", "old text", OrderPriority.ROUTINE),
                v(2, "500", "oral", "new text", OrderPriority.ROUTINE));
        assertThat(lines).containsExactly(new Line(Field.INDICATION, null, null));
    }

    @Test
    void anEndDateAddedIsALine() {
        MedicineOrderVersion a = v(1, "500", "oral", "pain", OrderPriority.ROUTINE);
        MedicineOrderVersion b = new MedicineOrderVersion(ORDER, 2, Kind.SCHEDULED, "bd", new BigDecimal("500"), "mg",
                "oral", START, START.plusSeconds(86400), "pain", OrderPriority.ROUTINE, null, null, null, null, START,
                null, Verification.REQUIRED, null, null);
        assertThat(OrderVersionDiff.diff(a, b)).extracting(Line::field).containsExactly(Field.END);
    }
}
