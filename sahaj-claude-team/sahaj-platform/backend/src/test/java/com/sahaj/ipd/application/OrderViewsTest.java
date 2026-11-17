package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.MedicineViews.OrderDetails;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76 the display shape of an order version: details, the dose line and the "Changed" lines. */
class OrderViewsTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    /** 24 Oct 2026, 8:00 AM IST. */
    private static final Instant START = Instant.parse("2026-10-24T02:30:00Z");

    private static MedicineOrderVersion scheduled(int no, String dose, String unit, String route, String frequency,
                                                  Instant end, String indication, OrderPriority priority) {
        return new MedicineOrderVersion(ORDER, no, Kind.SCHEDULED, frequency, dose == null ? null : new BigDecimal(dose),
                unit, route, START, end, indication, priority, null, null, no == 1 ? null : "Because", DOCTOR, START,
                null, Verification.REQUIRED, null, null);
    }

    private static MedicineOrderVersion prn() {
        return new MedicineOrderVersion(ORDER, 1, Kind.PRN, null, new BigDecimal("650"), "MG", "ORAL", START, null,
                "Fever", OrderPriority.ROUTINE, 360, 4, null, DOCTOR, START, null, Verification.REQUIRED, null, null);
    }

    private static MedicineOrderVersion stat() {
        return new MedicineOrderVersion(ORDER, 1, Kind.ONCE, null, new BigDecimal("4"), "MG", "IV", START, null, null,
                OrderPriority.STAT, null, null, null, DOCTOR, START, null, Verification.NOT_REQUIRED, null, null);
    }

    @Test
    void theDetailsOfAScheduledVersionCarryTheLabelsAndTheDuration() {
        MedicineOrderVersion v = scheduled(1, "1.000", "TABLET", "ORAL", "bd", START.plusSeconds(5 * 86_400L), "Pain",
                OrderPriority.ROUTINE);

        OrderDetails d = OrderViews.details(v);

        assertThat(d.kind()).isEqualTo("SCHEDULED");
        assertThat(d.routeLabel()).isEqualTo("Oral");
        assertThat(d.frequencyKey()).isEqualTo("bd");
        assertThat(d.frequencyLabel()).isEqualTo("Twice daily");
        assertThat(d.durationDays()).isEqualTo(5);
        assertThat(d.priority()).isEqualTo("ROUTINE");
        assertThat(d.pharmacistChecked()).isNull();
        assertThat(d.toString()).doesNotContain("Pain");
    }

    @Test
    void aVersionWithNoEndRunsUntilStoppedAndPrnAndStatHaveTheirOwnLabels() {
        assertThat(OrderViews.details(scheduled(1, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE))
                .durationDays()).isNull();
        OrderDetails p = OrderViews.details(prn());
        assertThat(p.frequencyKey()).isEqualTo("prn");
        assertThat(p.frequencyLabel()).isEqualTo("As needed (PRN)");
        assertThat(p.prnMinIntervalMinutes()).isEqualTo(360);
        OrderDetails s = OrderViews.details(stat());
        assertThat(s.frequencyKey()).isNull();
        assertThat(s.frequencyLabel()).isEqualTo("STAT");
        assertThat(s.pharmacistChecked()).isFalse();
    }

    @Test
    void aVerifiedVersionIsPharmacistChecked() {
        MedicineOrderVersion v = new MedicineOrderVersion(ORDER, 1, Kind.SCHEDULED, "od", null, null, "ORAL", START,
                null, null, OrderPriority.ROUTINE, null, null, null, DOCTOR, START, null, Verification.REQUIRED,
                UUID.randomUUID(), START.plusSeconds(60));

        assertThat(OrderViews.details(v).pharmacistChecked()).isTrue();
    }

    @Test
    void theDoseLineIsAsInTheMedicineNameWhenThereIsNoDose() {
        assertThat(OrderViews.doseText(scheduled(1, null, null, "ORAL", "od", null, null, OrderPriority.ROUTINE)))
                .isEqualTo("As in the medicine name");
        assertThat(OrderViews.doseText(scheduled(1, "2.50", "TABLET", "ORAL", "od", null, null,
                OrderPriority.ROUTINE))).isEqualTo("2.5 tablet");
    }

    @Test
    void aChangeListsDoseRouteFrequencyAndPriorityLines() {
        MedicineOrderVersion before = scheduled(1, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE);
        MedicineOrderVersion after = scheduled(2, "2", "TABLET", "IV", "bd", null, null, OrderPriority.URGENT);

        List<OrderChangeLine> lines = OrderViews.changes(before, after);

        assertThat(lines).extracting(OrderChangeLine::field).containsExactly("dose", "route", "frequency", "priority");
        assertThat(lines.get(0).before()).isEqualTo("1 tablet");
        assertThat(lines.get(0).after()).isEqualTo("2 tablet");
        assertThat(lines.get(1).before()).isEqualTo("Oral");
        assertThat(lines.get(1).after()).isEqualTo("IV");
        assertThat(lines.get(2).before()).isEqualTo("Once daily");
        assertThat(lines.get(2).after()).isEqualTo("Twice daily");
        assertThat(lines.get(3).after()).isEqualTo("Urgent");
        assertThat(lines.get(0).label()).isEqualTo("Dose");
        assertThat(lines.get(2).label()).isEqualTo("How often");
    }

    @Test
    void aDoseAmountAndAUnitChangeTogetherMakeOneLine() {
        MedicineOrderVersion before = scheduled(1, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE);
        MedicineOrderVersion after = scheduled(2, "500", "MG", "ORAL", "od", null, null, OrderPriority.ROUTINE);

        assertThat(OrderViews.changes(before, after)).extracting(OrderChangeLine::field).containsExactly("dose");
    }

    @Test
    void theEndLineSaysUntilStoppedAndTheStartLineIsInIst() {
        MedicineOrderVersion before = scheduled(1, "1", "TABLET", "ORAL", "od", START.plusSeconds(86_400L), null,
                OrderPriority.ROUTINE);
        MedicineOrderVersion after = scheduled(2, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE);

        List<OrderChangeLine> lines = OrderViews.changes(before, after);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).field()).isEqualTo("end");
        assertThat(lines.get(0).before()).isEqualTo("25 Oct, 8:00 AM");
        assertThat(lines.get(0).after()).isEqualTo("Until stopped");
        assertThat(OrderViews.when(START)).isEqualTo("24 Oct, 8:00 AM");
    }

    @Test
    void anIndicationLineShowsTheTextsAndNoneWhenEmpty() {
        MedicineOrderVersion before = scheduled(1, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE);
        MedicineOrderVersion after = scheduled(2, "1", "TABLET", "ORAL", "od", null, "Pain", OrderPriority.ROUTINE);

        List<OrderChangeLine> lines = OrderViews.changes(before, after);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).before()).isEqualTo("None");
        assertThat(lines.get(0).after()).isEqualTo("Pain");
        assertThat(lines.get(0).toString()).doesNotContain("Pain");
    }

    @Test
    void thePrnLimitLinesShowHoursAndMinutes() {
        MedicineOrderVersion before = prn();
        MedicineOrderVersion after = new MedicineOrderVersion(ORDER, 2, Kind.PRN, null, new BigDecimal("650"), "MG",
                "ORAL", START, null, "Fever", OrderPriority.ROUTINE, 90, 6, "Because", DOCTOR, START, null,
                Verification.REQUIRED, null, null);

        List<OrderChangeLine> lines = OrderViews.changes(before, after);

        assertThat(lines).extracting(OrderChangeLine::field).containsExactly("prnInterval", "prnMax");
        assertThat(lines.get(0).before()).isEqualTo("6 h");
        assertThat(lines.get(0).after()).isEqualTo("1 h 30 min");
        assertThat(lines.get(1).after()).isEqualTo("6");
        assertThat(OrderViews.minutesText(45)).isEqualTo("45 min");
    }

    @Test
    void aVersionEqualToItsPredecessorHasNoLines() {
        MedicineOrderVersion v = scheduled(1, "1", "TABLET", "ORAL", "od", null, null, OrderPriority.ROUTINE);

        assertThat(OrderViews.changes(v, scheduled(2, "1", "TABLET", "ORAL", "od", null, null,
                OrderPriority.ROUTINE))).isEmpty();
    }
}
