package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.MedicineRound.Entry;
import com.sahaj.ipd.domain.MedicineRound.Recorded;
import com.sahaj.ipd.domain.MedicineRound.Result;
import com.sahaj.ipd.domain.MedicineRound.RoundOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-35 the pure Medicine round (ADR-012 D1 to D3): one rule (DoseSlots), the edges of the 60-minute window, midnight in
 * IST, a mid-day verification, recorded slots, order and tie-break, the 200-row cap with the true counts, and the change
 * token lines. No database, no Spring.
 */
class MedicineRoundTest {

    /** 8:00 AM IST on 11 Nov 2026. */
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant TWO_PM = Instant.parse("2026-11-11T08:30:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");

    private static RoundOrder order(Frequency frequency, String bed, String drug, Instant verifiedAt) {
        return new RoundOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), bed, drug, null, frequency,
                verifiedAt);
    }

    private static RoundOrder od(String bed, String drug) {
        return order(Frequency.OD, bed, drug, LONG_AGO);
    }

    private static Result at(RoundOrder order, Instant asOf) {
        return MedicineRound.compute(List.of(order), List.of(), asOf);
    }

    // ---- the 60-minute window, whole minutes rounded down (ADR-011 D2) ----

    @Test
    void exactlySixtyMinutesAfterTheSlotIsStillDueAndSixtyOneIsLate() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");

        Result exact = at(o, EIGHT_AM.plus(Duration.ofMinutes(60)));
        Result over = at(o, EIGHT_AM.plus(Duration.ofMinutes(61)));

        assertThat(exact.rows()).extracting(Entry::state).containsExactly(State.DUE);
        assertThat(exact.rows().get(0).lateHours()).isNull();
        assertThat(exact.due()).isEqualTo(1);
        assertThat(exact.late()).isZero();
        assertThat(over.rows()).extracting(Entry::state).containsExactly(State.LATE);
        assertThat(over.rows().get(0).lateHours()).isEqualTo(1);
        assertThat(over.late()).isEqualTo(1);
        assertThat(over.due()).isZero();
    }

    @Test
    void sixtyMinutesFiftyNineSecondsIsStillWholeSixtyMinutesSoDueOnBothSides() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");

        assertThat(at(o, EIGHT_AM.plusSeconds(60 * 60 + 59)).rows()).extracting(Entry::state).containsExactly(State.DUE);
        assertThat(at(o, EIGHT_AM.minusSeconds(60 * 60 + 59)).rows()).extracting(Entry::state).containsExactly(State.DUE);
        assertThat(at(o, EIGHT_AM.plusSeconds(61 * 60)).rows()).extracting(Entry::state).containsExactly(State.LATE);
    }

    @Test
    void sixtyMinutesAheadIsDueAndSixtyOneMinutesAheadIsNotListed() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");

        assertThat(at(o, EIGHT_AM.minus(Duration.ofMinutes(60))).rows()).extracting(Entry::state)
                .containsExactly(State.DUE);
        Result ahead = at(o, EIGHT_AM.minus(Duration.ofMinutes(61)));
        assertThat(ahead.rows()).isEmpty();
        assertThat(ahead.due()).isZero();
        assertThat(ahead.late()).isZero();
        assertThat(ahead.tokenLines()).isEmpty();
    }

    @Test
    void lateHoursAreRoundedDownAndAtLeastOne() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");

        assertThat(at(o, EIGHT_AM.plus(Duration.ofMinutes(119))).rows().get(0).lateHours()).isEqualTo(1);
        assertThat(at(o, EIGHT_AM.plus(Duration.ofMinutes(120))).rows().get(0).lateHours()).isEqualTo(2);
        assertThat(at(o, EIGHT_AM.plus(Duration.ofMinutes(185))).rows().get(0).lateHours()).isEqualTo(3);
    }

    // ---- today in IST ----

    @Test
    void aDoseLateAtOneSecondBeforeIstMidnightLeavesTheListAtMidnight() {
        RoundOrder bd = order(Frequency.BD, "101", "Tab. Paracetamol 650 mg", LONG_AGO);
        Instant lastSecond = Instant.parse("2026-11-11T18:29:59Z");
        Instant midnight = Instant.parse("2026-11-11T18:30:00Z");

        Result before = at(bd, lastSecond);
        Result after = at(bd, midnight);

        assertThat(before.rows()).extracting(Entry::state).containsExactly(State.LATE, State.LATE);
        assertThat(before.rows()).extracting(Entry::slotAt).containsExactly(EIGHT_AM,
                Instant.parse("2026-11-11T14:30:00Z"));
        assertThat(before.rows()).extracting(Entry::lateHours).containsExactly(15, 3);
        assertThat(after.rows()).isEmpty();
        assertThat(after.late()).isZero();
    }

    @Test
    void theIstDateDecidesTodaysSlotsNotTheUtcDate() {
        // 11 Nov 2026 01:30 AM IST is 10 Nov 20:00 UTC: today's slots are those of 11 Nov in IST
        RoundOrder q8 = order(Frequency.Q8, "101", "Tab. Paracetamol 650 mg", LONG_AGO);
        Instant asOf = Instant.parse("2026-11-10T20:00:00Z");

        Result result = at(q8, asOf);

        assertThat(result.rows()).isEmpty();
        // 5:30 AM IST on 11 Nov is 30 minutes before the 6 AM slot of 11 Nov
        Result fiveThirty = at(q8, Instant.parse("2026-11-11T00:00:00Z"));
        assertThat(fiveThirty.rows()).extracting(Entry::slotAt)
                .containsExactly(Instant.parse("2026-11-11T00:30:00Z"));
    }

    // ---- an order checked mid-day ----

    @Test
    void aSlotMoreThanSixtyMinutesBeforeTheCheckIsNotListedButOneExactlySixtyBeforeIs() {
        // checked at 9:00:00 AM IST: the 8 AM slot is exactly 60 minutes before, still listed (LATE: it is 3.5 h ago)
        RoundOrder exact = order(Frequency.BD, "101", "Tab. Paracetamol 650 mg", EIGHT_AM.plus(Duration.ofMinutes(60)));
        RoundOrder oneSecond = order(Frequency.BD, "102", "Tab. Paracetamol 650 mg",
                EIGHT_AM.plus(Duration.ofMinutes(60)).plusSeconds(1));
        Instant asOf = Instant.parse("2026-11-11T06:00:00Z");

        assertThat(MedicineRound.compute(List.of(exact), List.of(), asOf).rows()).extracting(Entry::slotAt)
                .containsExactly(EIGHT_AM);
        assertThat(MedicineRound.compute(List.of(oneSecond), List.of(), asOf).rows()).isEmpty();
    }

    @Test
    void anOrderCheckedYesterdayHasAllOfTodaysSlots() {
        RoundOrder tds = order(Frequency.TDS, "101", "Tab. Paracetamol 650 mg", LONG_AGO);

        Result result = at(tds, Instant.parse("2026-11-11T08:45:00Z"));

        assertThat(result.rows()).extracting(Entry::slotAt).containsExactly(EIGHT_AM, TWO_PM);
        assertThat(result.rows()).extracting(Entry::state).containsExactly(State.LATE, State.DUE);
    }

    // ---- recorded slots ----

    @Test
    void aSlotWithADoseRecordIsNotListedWhateverTheOutcome() {
        for (DoseOutcome outcome : DoseOutcome.values()) {
            RoundOrder o = od("101", "Tab. Paracetamol 650 mg");
            Result result = MedicineRound.compute(List.of(o), List.of(new Recorded(o.orderId(), EIGHT_AM, outcome)),
                    EIGHT_AM.plus(Duration.ofMinutes(90)));

            assertThat(result.rows()).as(outcome.name()).isEmpty();
            assertThat(result.late()).isZero();
            assertThat(result.due()).isZero();
        }
    }

    @Test
    void aRecordOfAnotherOrderOrAnotherSlotDoesNotHideTheSlot() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");
        RoundOrder other = od("102", "Tab. Paracetamol 650 mg");
        List<Recorded> records = List.of(new Recorded(other.orderId(), EIGHT_AM, DoseOutcome.GIVEN),
                new Recorded(o.orderId(), EIGHT_AM.plus(Duration.ofMinutes(1)), DoseOutcome.GIVEN));

        Result result = MedicineRound.compute(List.of(o), records, EIGHT_AM.plus(Duration.ofMinutes(10)));

        assertThat(result.rows()).hasSize(1);
    }

    @Test
    void onlyTheRecordedSlotOfAnOrderLeavesTheListTheOtherSlotStays() {
        RoundOrder tds = order(Frequency.TDS, "101", "Tab. Paracetamol 650 mg", LONG_AGO);
        List<Recorded> records = List.of(new Recorded(tds.orderId(), EIGHT_AM, DoseOutcome.HELD));

        Result result = MedicineRound.compute(List.of(tds), records, Instant.parse("2026-11-11T08:40:00Z"));

        assertThat(result.rows()).extracting(Entry::slotAt).containsExactly(TWO_PM);
    }

    // ---- two admissions, order and tie-break ----

    @Test
    void lateRowsComeBeforeDueRowsAndTheEarlierSlotComesFirstInsideEachGroup() {
        RoundOrder lateEight = od("201", "Tab. Paracetamol 650 mg");
        RoundOrder tds = order(Frequency.TDS, "101", "Inj. Pantoprazole 40 mg", LONG_AGO);
        Instant asOf = Instant.parse("2026-11-11T08:45:00Z");

        Result result = MedicineRound.compute(List.of(tds, lateEight), List.of(), asOf);

        // at 2:15 PM IST both 8 AM slots are late and the 2 PM slot of the TDS order is due
        assertThat(result.rows()).extracting(Entry::state).containsExactly(State.LATE, State.LATE, State.DUE);
        assertThat(result.rows().get(2).slotAt()).isEqualTo(TWO_PM);
        assertThat(result.rows().get(0).slotAt()).isEqualTo(EIGHT_AM);
        assertThat(result.late()).isEqualTo(2);
        assertThat(result.due()).isEqualTo(1);
    }

    @Test
    void anEarlierLateSlotComesBeforeALaterLateSlot() {
        RoundOrder q8 = order(Frequency.Q8, "301", "Inj. Pantoprazole 40 mg", LONG_AGO);
        RoundOrder bd = order(Frequency.BD, "302", "Inj. Ondansetron 4 mg", LONG_AGO);
        // 3:30 PM IST: q8 6 AM (late), q8 2 PM (late), bd 8 AM (late)
        Instant asOf = Instant.parse("2026-11-11T10:00:00Z");

        Result result = MedicineRound.compute(List.of(bd, q8), List.of(), asOf);

        assertThat(result.rows()).extracting(Entry::slotAt).containsExactly(Instant.parse("2026-11-11T00:30:00Z"),
                EIGHT_AM, TWO_PM);
    }

    @Test
    void equalSlotsAreOrderedByBedThenMedicineThenOrderId() {
        RoundOrder bedB = od("B2", "Inj. Ondansetron 4 mg");
        RoundOrder bedAZ = od("A1", "Tab. Zinc");
        RoundOrder bedAA = od("A1", "Tab. Aspirin 75 mg");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("7fffffff-0000-0000-0000-000000000001");
        RoundOrder sameLow = new RoundOrder(low, UUID.randomUUID(), UUID.randomUUID(), "C3", "Tab. Same", null,
                Frequency.OD, LONG_AGO);
        RoundOrder sameHigh = new RoundOrder(high, UUID.randomUUID(), UUID.randomUUID(), "C3", "Tab. Same", null,
                Frequency.OD, LONG_AGO);

        Result result = MedicineRound.compute(List.of(sameHigh, bedB, sameLow, bedAZ, bedAA), List.of(),
                EIGHT_AM.plus(Duration.ofMinutes(90)));

        assertThat(result.rows()).extracting(Entry::orderId).containsExactly(bedAA.orderId(), bedAZ.orderId(),
                bedB.orderId(), low, high);
    }

    @Test
    void theBedCodeIsComparedAsTextSoTenComesBeforeTwo() {
        RoundOrder ten = od("10", "Tab. Paracetamol 650 mg");
        RoundOrder two = od("2", "Tab. Paracetamol 650 mg");

        Result result = MedicineRound.compute(List.of(two, ten), List.of(), EIGHT_AM.plus(Duration.ofMinutes(90)));

        assertThat(result.rows()).extracting(Entry::orderId).containsExactly(ten.orderId(), two.orderId());
    }

    @Test
    void theOrderIsTheSameWhateverTheInputOrder() {
        List<RoundOrder> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(od("W" + (i % 4), "Drug " + (i % 3)));
        }
        Instant asOf = EIGHT_AM.plus(Duration.ofMinutes(75));
        List<UUID> first = MedicineRound.compute(orders, List.of(), asOf).rows().stream().map(Entry::orderId).toList();
        java.util.Collections.reverse(orders);
        List<UUID> second = MedicineRound.compute(orders, List.of(), asOf).rows().stream().map(Entry::orderId).toList();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theRowCarriesWhatThePanelNeeds() {
        UUID order = UUID.randomUUID();
        UUID admission = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        RoundOrder o = new RoundOrder(order, admission, patient, "101-A", "Inj. Heparin 5,000 IU", "Anticoagulant",
                Frequency.OD, LONG_AGO);

        Entry e = at(o, EIGHT_AM.plus(Duration.ofMinutes(130))).rows().get(0);

        assertThat(e.orderId()).isEqualTo(order);
        assertThat(e.admissionId()).isEqualTo(admission);
        assertThat(e.patientId()).isEqualTo(patient);
        assertThat(e.bedCode()).isEqualTo("101-A");
        assertThat(e.drugName()).isEqualTo("Inj. Heparin 5,000 IU");
        assertThat(e.highAlert()).isEqualTo("Anticoagulant");
        assertThat(e.slotAt()).isEqualTo(EIGHT_AM);
        assertThat(e.state()).isEqualTo(State.LATE);
        assertThat(e.lateHours()).isEqualTo(2);
    }

    // ---- the 200-row cap ----

    @Test
    void twoHundredAndOneSlotsGiveTwoHundredRowsTruncatedAndTheTrueCounts() {
        List<RoundOrder> orders = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            orders.add(od(String.format("B%03d", i), "Tab. Paracetamol 650 mg"));
        }

        Result result = MedicineRound.compute(orders, List.of(), EIGHT_AM.plus(Duration.ofMinutes(90)));

        assertThat(result.rows()).hasSize(MedicineRound.MAX_ROWS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.late()).isEqualTo(201);
        assertThat(result.due()).isZero();
        assertThat(result.tokenLines()).hasSize(201);
        // the kept rows are the first 200 in order: bed B000 to B199, never B200
        assertThat(result.rows()).extracting(Entry::bedCode).doesNotContain("B200").startsWith("B000", "B001");
    }

    @Test
    void exactlyTwoHundredSlotsAreNotTruncated() {
        List<RoundOrder> orders = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            orders.add(od(String.format("B%03d", i), "Tab. Paracetamol 650 mg"));
        }

        Result result = MedicineRound.compute(orders, List.of(), EIGHT_AM.plus(Duration.ofMinutes(90)));

        assertThat(result.rows()).hasSize(200);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void theCapKeepsTheLateRowsAndCountsTheDroppedDueRow() {
        List<RoundOrder> orders = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            orders.add(od(String.format("D%03d", i), "Tab. Paracetamol 650 mg"));
        }
        orders.add(order(Frequency.TDS, "Z999", "Tab. Paracetamol 650 mg", LONG_AGO));
        // 1:30 PM IST: every 8 AM slot is late; the 2 PM slot of the TDS order is due
        Instant asOf = TWO_PM.minus(Duration.ofMinutes(30));

        Result result = MedicineRound.compute(orders, List.of(), asOf);

        assertThat(result.late()).isEqualTo(201);
        assertThat(result.due()).isEqualTo(1);
        assertThat(result.rows()).hasSize(200);
        assertThat(result.rows()).extracting(Entry::state).containsOnly(State.LATE);
        assertThat(result.truncated()).isTrue();
        assertThat(result.tokenLines()).hasSize(202);
    }

    // ---- empty ----

    @Test
    void noOrdersGiveAnEmptyRound() {
        Result result = MedicineRound.compute(List.of(), List.of(), EIGHT_AM);

        assertThat(result.rows()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.due()).isZero();
        assertThat(result.late()).isZero();
        assertThat(result.tokenLines()).isEmpty();
        assertThat(result).isSameAs(MedicineRound.empty());
    }

    // ---- the token lines ----

    @Test
    void oneTokenLinePerListedSlotWithTheOrderIdInLowerCaseTheEpochSecondsAndTheState() {
        UUID id = UUID.fromString("ABCDEF00-0000-0000-0000-000000000001");
        RoundOrder o = new RoundOrder(id, UUID.randomUUID(), UUID.randomUUID(), "101", "Tab. X", null, Frequency.TDS,
                LONG_AGO);

        Result result = at(o, Instant.parse("2026-11-11T08:45:00Z"));

        assertThat(result.tokenLines()).containsExactlyInAnyOrder(
                "dose:abcdef00-0000-0000-0000-000000000001:" + EIGHT_AM.getEpochSecond() + ":LATE",
                "dose:abcdef00-0000-0000-0000-000000000001:" + TWO_PM.getEpochSecond() + ":DUE");
    }

    @Test
    void theTokenLinesHoldNoNameDrugOrBedAndTheToStringsHideThem() {
        RoundOrder o = new RoundOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "BED-777",
                "Inj. Secret Drug", "Insulin", Frequency.OD, LONG_AGO);

        Result result = at(o, EIGHT_AM.plus(Duration.ofMinutes(90)));

        assertThat(String.join("\n", result.tokenLines())).doesNotContain("Secret").doesNotContain("BED-777");
        assertThat(o.toString()).doesNotContain("Secret").doesNotContain("BED-777");
        assertThat(result.rows().get(0).toString()).doesNotContain("Secret").doesNotContain("BED-777");
        assertThat(result.toString()).doesNotContain("Secret").doesNotContain("BED-777");
    }

    @Test
    void theLineChangesWhenTheSlotTurnsFromDueToLateAndNotWhenOnlyLateHoursGrow() {
        RoundOrder o = od("101", "Tab. Paracetamol 650 mg");

        String due = at(o, EIGHT_AM.plus(Duration.ofMinutes(60))).tokenLines().get(0);
        String late1 = at(o, EIGHT_AM.plus(Duration.ofMinutes(61))).tokenLines().get(0);
        String late2 = at(o, EIGHT_AM.plus(Duration.ofMinutes(130))).tokenLines().get(0);

        assertThat(late1).isNotEqualTo(due);
        assertThat(late2).isEqualTo(late1);
    }
}
