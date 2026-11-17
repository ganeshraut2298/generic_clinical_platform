package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.OrderValues.Raw;
import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76-1 the checks of an order's values: the texts and the order of the contract (MedicineOrderInput). */
class OrderValuesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
    private static final Drug PCM = SeedCatalogue.drug("pcm");
    private static final Drug INSULIN = new Drug("ins", "Inj. Regular insulin", "SC", Set.of(), "Insulin", RouteKey.SC,
            List.of(DoseUnit.UNIT), false);

    private final List<FieldMessage> errors = new ArrayList<>();

    private static Raw scheduled() {
        return new Raw("bd", new BigDecimal("650"), "MG", "ORAL", null, null, null, "ROUTINE", null, null);
    }

    private OrderValues check(Raw raw) {
        return OrderValues.check(PCM, raw, NOW, true, errors);
    }

    private static Raw raw(String frequency, BigDecimal dose, String unit, String route, String start, Integer days,
                           String indication, String priority, Integer min, Integer max) {
        return new Raw(frequency, dose, unit, route, start, days, indication, priority, min, max);
    }

    private List<String> fields() {
        return errors.stream().map(FieldMessage::field).toList();
    }

    @Test
    void aPlainScheduledOrderIsChecked() {
        OrderValues values = check(scheduled());

        assertThat(errors).isEmpty();
        assertThat(values.kind()).isEqualTo(Kind.SCHEDULED);
        assertThat(values.frequency()).isEqualTo(Frequency.BD);
        assertThat(values.doseUnit()).isEqualTo(DoseUnit.MG);
        assertThat(values.route()).isEqualTo(RouteKey.ORAL);
        assertThat(values.priority()).isEqualTo(OrderPriority.ROUTINE);
        assertThat(values.minIntervalMin()).isNull();
    }

    @Test
    void theDoseMustBeAboveZeroAndNotAboveAHundredThousandWithThreeDecimals() {
        for (BigDecimal bad : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("100001"),
                new BigDecimal("0.0001")}) {
            errors.clear();
            check(raw("bd", bad, "MG", "ORAL", null, null, null, "ROUTINE", null, null));
            assertThat(errors).as("dose %s", bad).containsExactly(new FieldMessage("doseAmount", "Enter the dose."));
        }
        errors.clear();
        assertThat(check(raw("bd", new BigDecimal("0.001"), "MG", "ORAL", null, null, null, "ROUTINE", null, null)))
                .isNotNull();
        assertThat(check(raw("bd", new BigDecimal("1.5000"), "MG", "ORAL", null, null, null, "ROUTINE", null, null)))
                .isNotNull();
        assertThat(errors).isEmpty();
    }

    @Test
    void theUnitMustBeOneOfTheMedicinesUnits() {
        OrderValues values = OrderValues.check(INSULIN, raw("od", BigDecimal.TEN, "MG", "SC", null, null, null,
                "ROUTINE", null, null), NOW, true, errors);

        assertThat(values).isNull();
        assertThat(errors).containsExactly(new FieldMessage("doseUnit", "Choose the unit."));
        errors.clear();
        assertThat(OrderValues.check(INSULIN, raw("od", BigDecimal.TEN, "UNIT", "SC", null, null, null, "ROUTINE",
                null, null), NOW, true, errors)).isNotNull();
    }

    @Test
    void anUnknownUnitOrRouteIsRefusedWithTheContractText() {
        check(raw("bd", BigDecimal.ONE, "KG", "MOUTH", null, null, null, "ROUTINE", null, null));

        assertThat(errors).containsExactly(new FieldMessage("doseUnit", "Choose the unit."),
                new FieldMessage("routeKey", "Choose the route."));
    }

    @Test
    void theStartCannotBeMoreThanFiveMinutesInThePast() {
        check(raw("bd", BigDecimal.ONE, "MG", "ORAL", NOW.minusSeconds(301).toString(), null, null, "ROUTINE", null,
                null));
        assertThat(errors).containsExactly(new FieldMessage("startAt", "Start cannot be in the past."));

        errors.clear();
        OrderValues ok = check(raw("bd", BigDecimal.ONE, "MG", "ORAL", NOW.minusSeconds(300).toString(), null, null,
                "ROUTINE", null, null));
        assertThat(errors).isEmpty();
        assertThat(ok.startAt()).isEqualTo(NOW.minusSeconds(300));
    }

    @Test
    void aStartThatIsNotATimeIsAReloadError() {
        check(raw("bd", BigDecimal.ONE, "MG", "ORAL", "tomorrow", null, null, "ROUTINE", null, null));

        assertThat(errors).containsExactly(new FieldMessage("startAt", "Reload the page and try again."));
    }

    @Test
    void forAChangeAPastStartIsNotAnError() {
        OrderValues values = OrderValues.check(PCM, raw("bd", BigDecimal.ONE, "MG", "ORAL",
                NOW.minusSeconds(3600).toString(), null, null, "ROUTINE", null, null), NOW, false, errors);

        assertThat(errors).isEmpty();
        assertThat(values.startAt()).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    void theDurationIsWholeDaysOneTo365OrUntilStopped() {
        for (Integer bad : new Integer[] {0, -1, 366}) {
            errors.clear();
            check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, bad, null, "ROUTINE", null, null));
            assertThat(errors).as("days %s", bad)
                    .containsExactly(new FieldMessage("durationDays", "Enter whole days, or choose Until stopped."));
        }
        errors.clear();
        assertThat(check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, 365, null, "ROUTINE", null, null))
                .durationDays()).isEqualTo(365);
        assertThat(check(scheduled()).durationDays()).isNull();
        assertThat(errors).isEmpty();
    }

    @Test
    void aPrnOrderNeedsTheIndicationTheIntervalAndTheMaximum() {
        check(raw("prn", BigDecimal.ONE, "MG", "ORAL", null, null, null, "ROUTINE", null, null));

        assertThat(errors).containsExactly(new FieldMessage("indication", "Enter what it is for."),
                new FieldMessage("prnMinIntervalMinutes", "Enter the minimum time between doses."),
                new FieldMessage("prnMaxPer24h", "Enter the most doses in 24 hours."));
    }

    @Test
    void thePrnLimitsHaveTheirRanges() {
        for (int[] bad : new int[][] {{14, 4}, {4321, 4}, {60, 0}, {60, 25}}) {
            errors.clear();
            check(raw("prn", BigDecimal.ONE, "MG", "ORAL", null, null, "Fever", "ROUTINE", bad[0], bad[1]));
            assertThat(errors).as("limits %s", List.of(bad[0], bad[1])).hasSize(1);
        }
        errors.clear();
        OrderValues edge = check(raw("prn", BigDecimal.ONE, "MG", "ORAL", null, null, "Fever", "ROUTINE", 15, 24));
        assertThat(errors).isEmpty();
        assertThat(edge.kind()).isEqualTo(Kind.PRN);
        assertThat(edge.frequency()).isNull();
        assertThat(edge.minIntervalMin()).isEqualTo(15);
        assertThat(edge.maxPer24h()).isEqualTo(24);
    }

    @Test
    void theLimitsOfAScheduledOrderAreNotKept() {
        OrderValues values = check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, null, "ROUTINE", 60, 4));

        assertThat(values.minIntervalMin()).isNull();
        assertThat(values.maxPer24h()).isNull();
    }

    @Test
    void aStatOrderIsOneDoseDueNowWithNoFrequencyOrDuration() {
        OrderValues values = check(raw(null, BigDecimal.ONE, "MG", "IV", "2030-01-01T00:00:00Z", null, null, "STAT",
                null, null));

        assertThat(errors).isEmpty();
        assertThat(values.kind()).isEqualTo(Kind.ONCE);
        assertThat(values.frequency()).isNull();
        assertThat(values.startAt()).isNull();
    }

    @Test
    void aStatOrderWithAFrequencyOrADurationIsRefused() {
        check(raw("od", BigDecimal.ONE, "MG", "IV", null, null, null, "STAT", null, null));
        assertThat(errors).containsExactly(new FieldMessage("priority", "A STAT order is one dose, due now."));

        errors.clear();
        check(raw(null, BigDecimal.ONE, "MG", "IV", null, 3, null, "STAT", null, null));
        assertThat(errors).containsExactly(new FieldMessage("priority", "A STAT order is one dose, due now."));
    }

    @Test
    void aScheduledOrderNeedsAFrequencyAndAPriority() {
        check(raw(null, BigDecimal.ONE, "MG", "ORAL", null, null, null, null, null, null));

        assertThat(fields()).containsExactly("frequencyKey", "priority");
        assertThat(errors.get(0).message()).isEqualTo("Choose how often");
    }

    @Test
    void theIndicationIsTrimmedAndLimitedTo200Characters() {
        OrderValues kept = check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "  Fever above 38.5 C  ",
                "ROUTINE", null, null));
        assertThat(kept.indication()).isEqualTo("Fever above 38.5 C");

        assertThat(check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "   ", "ROUTINE", null, null))
                .indication()).isNull();

        errors.clear();
        check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "x".repeat(201), "ROUTINE", null, null));
        assertThat(errors).containsExactly(new FieldMessage("indication", "Keep it to 200 characters."));

        errors.clear();
        check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "x".repeat(200), "ROUTINE", null, null));
        assertThat(errors).isEmpty();
    }

    @Test
    void aTextWithAControlOrTextDirectionCharacterIsRefused() {
        check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "fever‮fi", "ROUTINE", null, null));

        assertThat(errors).containsExactly(new FieldMessage("indication", OrderValues.TEXT_CHARACTERS));
    }

    @Test
    void everyFieldErrorComesTogether() {
        check(raw("bd", null, "KG", "MOUTH", "x", 0, "y".repeat(201), "ROUTINE", null, null));

        assertThat(fields()).containsExactly("doseAmount", "doseUnit", "routeKey", "startAt", "durationDays",
                "indication");
    }

    @Test
    void anOrderPlacedThroughThePortHasNoDoseTheDrugsRouteAndRoutinePriority() {
        OrderValues values = OrderValues.ofPort(SeedCatalogue.drug("ceft"), Frequency.TDS);

        assertThat(values.doseAmount()).isNull();
        assertThat(values.doseUnit()).isNull();
        assertThat(values.route()).isEqualTo(RouteKey.IV);
        assertThat(values.priority()).isEqualTo(OrderPriority.ROUTINE);
        assertThat(values.kind()).isEqualTo(Kind.SCHEDULED);
        assertThat(values.durationDays()).isNull();
    }

    @Test
    void theVersionToWriteCarriesTheValuesTheEndAndTheVerification() {
        OrderValues values = check(raw("tds", new BigDecimal("2"), "MG", "ORAL", null, 5, "Pain", "URGENT", null, null));
        UUID orderId = UUID.randomUUID();
        UUID by = UUID.randomUUID();

        OrderVersionRepository.NewVersion v = values.toVersion(orderId, 2, NOW, "Dose raised", by,
                Verification.REQUIRED);

        assertThat(v.versionNo()).isEqualTo(2);
        assertThat(v.kind()).isEqualTo(Kind.SCHEDULED);
        assertThat(v.frequencyKey()).isEqualTo("tds");
        assertThat(v.doseUnit()).isEqualTo("MG");
        assertThat(v.routeKey()).isEqualTo("ORAL");
        assertThat(v.endAt()).isEqualTo(NOW.plusSeconds(5 * 86_400L));
        assertThat(v.priority()).isEqualTo(OrderPriority.URGENT);
        assertThat(v.changeReason()).isEqualTo("Dose raised");
        assertThat(v.toString()).doesNotContain("Pain").doesNotContain("Dose raised");
    }

    @Test
    void theValuesAndTheRawFieldsHideTheIndicationFromToString() {
        OrderValues values = check(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "Secret reason", "ROUTINE", null,
                null));

        assertThat(values.toString()).doesNotContain("Secret");
        assertThat(raw("bd", BigDecimal.ONE, "MG", "ORAL", null, null, "Secret reason", "ROUTINE", null, null)
                .toString()).doesNotContain("Secret");
    }
}
