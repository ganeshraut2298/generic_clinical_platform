package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseRepository.OpenNotice;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.MarOrderView;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SAH-76-4 the MAR answer of the nursing integration (ADR-027 D2, D7): the fold order and its counters, the PRN line, the
 * hold and stop texts, the order version and its details, the medicine code flag and the badge of the Orders tab. In-memory
 * fakes, no database.
 */
class MarNursingViewTest extends DoseTestBase {

    private Mar mar() {
        return marService.mar(TENANT, NURSE, "NURSE", ADMISSION);
    }

    private static MarOrderView block(Mar mar, OrderRow order) {
        return mar.orders().stream().filter(o -> o.orderId().equals(order.id())).findFirst().orElseThrow();
    }

    // ---- the fold order and the counters ----

    @Test
    void blocksWithADueOrLateSlotComeFirstMostLateFirstThenPrnThenLaterThenFolded() {
        fake.now = Instant.parse("2026-11-11T03:30:00Z"); // 9:00 AM IST
        OrderRow due = active("pcm", "od");              // 8 AM: 60 minutes after, still DUE
        OrderRow late = active("ns", "q8");               // 6 AM: 180 minutes late
        OrderRow later = active("hep", "bd");             // 8 AM due... changed below to a PM-only order
        timelineOverrides.put(later.id(), timelineOf(later, OrderStatus.ACTIVE,
                new MedicineOrderVersion(later.id(), 1, Kind.SCHEDULED, "bd", null, null, "SC",
                        Instant.parse("2026-11-11T05:00:00Z"), null, null, OrderPriority.ROUTINE, null, null, null,
                        DOCTOR, CHECKED_LONG_AGO, null, Verification.NOT_REQUIRED, null, null)));
        OrderRow prn = active("panto", "od");
        timelineOverrides.put(prn.id(), timelineOf(prn, OrderStatus.ACTIVE, new MedicineOrderVersion(prn.id(), 1, Kind.PRN,
                null, null, null, "IV", CHECKED_LONG_AGO, null, "Nausea", OrderPriority.ROUTINE, 240, 3, null, DOCTOR,
                CHECKED_LONG_AGO, null, Verification.NOT_REQUIRED, null, null)));
        OrderRow folded = ofStatus("PENDING", null);

        Mar mar = mar();

        assertThat(mar.orders()).extracting(MarOrderView::orderId).containsExactly(late.id(), due.id(), prn.id(),
                later.id(), folded.id());
        assertThat(mar.orders()).extracting(MarOrderView::folded).containsExactly(false, false, false, false, true);
        assertThat(mar.foldedCount()).isEqualTo(1);
        assertThat(mar.counters().late()).isEqualTo(1);
        assertThat(mar.counters().dueNow()).isEqualTo(1);
        assertThat(mar.doseCount()).as("the tab count is late plus due now").isEqualTo(2);
        assertThat(marAssembler.doseCount(TENANT, ADMISSION, true, fake.now)).isEqualTo(mar.doseCount());
        MarOrderView lateBlock = block(mar, late);
        assertThat(lateBlock.slots().get(0).state()).isEqualTo("LATE");
        assertThat(lateBlock.slots().get(0).lateHours()).isEqualTo(3);
        assertThat(lateBlock.slots().get(0).lateMinutes()).isEqualTo(180);
        assertThat(lateBlock.slots().get(0).canGive()).isTrue();
        assertThat(lateBlock.slots().get(0).orderVersion()).isEqualTo(1);
    }

    @Test
    void theCountsAreZeroForADischargedAdmissionAndNothingIsGivable() {
        active("pcm", "od");
        admission = admissionRow("DISCHARGED");

        Mar mar = mar();

        assertThat(mar.doseCount()).isZero();
        assertThat(mar.canGive()).isFalse();
        assertThat(mar.orders().get(0).slots()).extracting(s -> s.state()).containsOnly("NOT_RECORDED");
        assertThat(marAssembler.doseCount(TENANT, ADMISSION, false, fake.now)).isZero();
    }

    // ---- hold, stop, change ----

    @Test
    void anOrderOnHoldShowsTheOpenHoldWithoutItsReasonAndItsSlotsAreNeverDue() {
        OrderRow order = ofStatus("ON_HOLD", CHECKED_LONG_AGO);
        Instant held = Instant.parse("2026-11-11T02:00:00Z");
        timelineOverrides.put(order.id(), new OrderTimeline(order.id(), ADMISSION, OrderStatus.ON_HOLD,
                List.of(version(order, 1, Kind.SCHEDULED, "od", OrderPriority.ROUTINE)),
                List.of(new OrderHoldInterval(held, DOCTOR, "Patient nil by mouth", null, null)), null));
        MarOrderView block = block(mar(), order);

        assertThat(block.hold().heldAt()).isEqualTo(held);
        assertThat(block.hold().heldByName()).isEqualTo("Dr Meera Joshi");
        assertThat(block.hold().reason()).as("reason text is not shown on the MAR list").isNull();
        assertThat(block.slots().get(0).state()).isEqualTo("ON_HOLD");
        assertThat(block.slots().get(0).stateBy()).isEqualTo("Dr Meera Joshi");
        assertThat(block.slots().get(0).stateAt()).isEqualTo(held);
        assertThat(block.slots().get(0).canGive()).isFalse();
        assertThat(block.folded()).isTrue();
    }

    @Test
    void anOrderStoppedTodayStaysListedWithItsSlotsStoppedFromTheStopTime() {
        OrderRow order = fake.seedAt(ADMISSION, "pcm", "tds", MedicineOrderRepository.STOPPED, DOCTOR, CHECKED_LONG_AGO);

        MarOrderView block = block(mar(), order);

        assertThat(block.status()).isEqualTo("STOPPED");
        assertThat(block.slots()).extracting(s -> s.state()).containsExactly("DUE", "STOPPED", "STOPPED");
        assertThat(block.slots().get(1).stateBy()).isEqualTo("Team member");
        assertThat(block.slots().get(1).stateAt()).isEqualTo(order.stoppedAt());
        assertThat(block.slots().get(1).canGive()).isFalse();
        assertThat(block.slots().get(0).canGive()).as("a stopped order is never givable").isFalse();
    }

    @Test
    void aChangedOrderShowsTheVersionMarkerTheDetailsAndTheReplacedSlotsOfTheOlderVersion() {
        OrderRow order = active("pcm", "bd");
        Instant change = Instant.parse("2026-11-11T02:35:00Z");
        MedicineOrderVersion v1 = new MedicineOrderVersion(order.id(), 1, Kind.SCHEDULED, "bd", null, null, "ORAL",
                CHECKED_LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, DOCTOR, CHECKED_LONG_AGO, change,
                Verification.REQUIRED, DOCTOR, CHECKED_LONG_AGO);
        MedicineOrderVersion v2 = new MedicineOrderVersion(order.id(), 2, Kind.SCHEDULED, "od", new BigDecimal("2.000"),
                "TABLET", "ORAL", change, change.plus(Duration.ofDays(5)), "Fever", OrderPriority.URGENT, null, null,
                "Dose changed", DOCTOR, change, null, Verification.REQUIRED, DOCTOR, change);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, v1, v2));

        MarOrderView block = block(mar(), order);

        assertThat(block.orderVersion()).isEqualTo(2);
        assertThat(block.changed()).isTrue();
        assertThat(block.frequencyKey()).isEqualTo("od");
        assertThat(block.details().doseAmount()).isEqualByComparingTo("2");
        assertThat(block.details().doseUnit()).isEqualTo("TABLET");
        assertThat(block.details().routeLabel()).isEqualTo("Oral");
        assertThat(block.details().durationDays()).isEqualTo(5);
        assertThat(block.details().priority()).isEqualTo("URGENT");
        assertThat(block.details().indication()).isEqualTo("Fever");
        assertThat(block.details().pharmacistChecked()).isTrue();
        assertThat(block.slots()).extracting(s -> s.state() + ":" + s.orderVersion()).containsExactly("DUE:1",
                "REPLACED:1");
        assertThat(block.slots().get(0).canGive()).as("a slot of an older version is never givable").isFalse();
        assertThat(block.slots().get(1).stateBy()).isEqualTo("Dr Meera Joshi");
    }

    @Test
    void aVersionThatNeededNoVerificationIsNotPharmacistChecked() {
        OrderRow order = active("pcm", "od");
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, new MedicineOrderVersion(order.id(), 1,
                Kind.SCHEDULED, "od", null, null, "ORAL", CHECKED_LONG_AGO, null, null, OrderPriority.ROUTINE, null, null,
                null, DOCTOR, CHECKED_LONG_AGO, null, Verification.NOT_REQUIRED, null, null)));

        assertThat(block(mar(), order).details().pharmacistChecked()).isFalse();
    }

    // ---- PRN ----

    private OrderRow prn(String status) {
        OrderRow order = ofStatus(status, CHECKED_LONG_AGO);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.valueOf(status), new MedicineOrderVersion(order.id(),
                1, Kind.PRN, null, null, null, "ORAL", CHECKED_LONG_AGO, null, "Pain", OrderPriority.ROUTINE, 240, 3, null,
                DOCTOR, CHECKED_LONG_AGO, null, Verification.NOT_REQUIRED, null, null)));
        return order;
    }

    @Test
    void aPrnOrderHasNoSlotsAndAPrnLineThatIsGivableWhenNothingLocksIt() {
        OrderRow order = prn("ACTIVE");

        MarOrderView block = block(mar(), order);

        assertThat(block.slots()).isEmpty();
        assertThat(block.frequencyKey()).isEqualTo("prn");
        assertThat(block.frequencyLabel()).isEqualTo("As needed (PRN)");
        assertThat(block.prn().indication()).isEqualTo("Pain");
        assertThat(block.prn().minIntervalMinutes()).isEqualTo(240);
        assertThat(block.prn().maxPer24h()).isEqualTo(3);
        assertThat(block.prn().givenLast24h()).isZero();
        assertThat(block.prn().lockReason()).isNull();
        assertThat(block.prn().canGive()).isTrue();
        assertThat(block.folded()).as("PRN lines are never folded").isFalse();
    }

    @Test
    void aPrnLineIsLockedTooSoonWithTheNextTime() {
        OrderRow order = prn("ACTIVE");
        doses.seed(ADMISSION, order.id(), CLOCK.minusSeconds(100 * 60), "GIVEN", null, NURSE, null, false);

        var prn = block(mar(), order).prn();

        assertThat(prn.lockReason()).isEqualTo("TOO_SOON");
        assertThat(prn.nextAllowedAt()).isEqualTo(CLOCK.minusSeconds(96 * 60).plusSeconds(240 * 60));
        assertThat(prn.lastGivenAt()).isEqualTo(CLOCK.minusSeconds(96 * 60));
        assertThat(prn.givenLast24h()).isEqualTo(1);
        assertThat(prn.canGive()).isFalse();
    }

    @Test
    void aPrnLineOfAnOrderOnHoldOrWaitingNamesTheLock() {
        OrderRow onHold = prn("ON_HOLD");
        OrderRow waiting = prn("PENDING");
        OrderRow stopped = prn("STOPPED");

        Mar mar = mar();

        assertThat(block(mar, onHold).prn().lockReason()).isEqualTo("ON_HOLD");
        assertThat(block(mar, waiting).prn().lockReason()).isEqualTo("NOT_VERIFIED");
        assertThat(block(mar, stopped).prn().lockReason()).isEqualTo("STOPPED");
    }

    // ---- code flag, notices, last given ----

    @Test
    void theMedicineCodeFlagFollowsTheDrugMaster() {
        OrderRow withCode = active("pcm", "od");
        OrderRow without = active("hep", "bd");
        when(drugCodes.drugKeysWithCodes(anyCollection())).thenReturn(Set.of("pcm"));

        Mar mar = mar();

        assertThat(block(mar, withCode).hasMedicineCode()).isTrue();
        assertThat(block(mar, without).hasMedicineCode()).isFalse();
    }

    @Test
    void theBadgeAndTheAcknowledgedFlagFollowTheOpenNotices() {
        OrderRow noticed = active("pcm", "od");
        OrderRow quiet = active("ns", "od");
        doses.notices.add(new OpenNotice(noticed.id(), "STAT", CLOCK.minus(Duration.ofMinutes(9))));
        doses.notices.add(new OpenNotice(noticed.id(), "ROUTINE", CLOCK.minus(Duration.ofMinutes(31))));
        doses.notices.add(new OpenNotice(noticed.id(), "ROUTINE", CLOCK.minus(Duration.ofMinutes(5))));

        Mar mar = mar();

        assertThat(block(mar, noticed).acknowledged()).isFalse();
        assertThat(block(mar, quiet).acknowledged()).isTrue();
        assertThat(mar.ordersToAcknowledge().count()).isEqualTo(3);
        assertThat(mar.ordersToAcknowledge().stat()).isEqualTo(1);
        assertThat(mar.ordersToAcknowledge().overdue()).as("STAT over 5 minutes and routine over 30").isEqualTo(2);
    }

    @Test
    void theLastGivenTimeIsTheLatestLiveGivenDoseOfTheOrder() {
        OrderRow order = active("pcm", "bd");
        doses.seed(ADMISSION, order.id(), Instant.parse("2026-11-10T14:30:00Z"), "GIVEN", null, NURSE, null, false);

        MarOrderView block = block(mar(), order);

        assertThat(block.lastGivenAt()).isEqualTo(Instant.parse("2026-11-10T14:34:00Z"));
    }
    // ---- one test per order state that is never givable (GO-LIVE test gaps, java P0) ----

    /** A control order whose 8 AM slot is DUE and givable: the count of the tab is exactly this one. */
    private OrderRow dueControl() {
        return active("ns", "od");
    }

    private void assertNoneGivableAndNotCounted(Mar mar, MarOrderView block) {
        assertThat(block.slots()).as("the order has slots to show").isNotEmpty();
        assertThat(block.slots()).extracting(s -> s.canGive()).containsOnly(false);
        assertThat(mar.doseCount()).as("only the control order counts").isEqualTo(1);
        assertThat(mar.counters().dueNow() + mar.counters().late()).isEqualTo(1);
        assertThat(marAssembler.doseCount(TENANT, ADMISSION, true, fake.now)).isEqualTo(1);
    }

    @Test
    void anOnHoldScheduledOrderShowsOnHoldSlotsThatCannotBeGivenAndAreNotCounted() {
        dueControl();
        OrderRow order = ofStatus("ON_HOLD", CHECKED_LONG_AGO);
        Instant held = Instant.parse("2026-11-11T02:00:00Z");
        timelineOverrides.put(order.id(), new OrderTimeline(order.id(), ADMISSION, OrderStatus.ON_HOLD,
                List.of(version(order, 1, Kind.SCHEDULED, "od", OrderPriority.ROUTINE)),
                List.of(new OrderHoldInterval(held, DOCTOR, "Patient nil by mouth", null, null)), null));

        Mar mar = mar();
        MarOrderView block = block(mar, order);

        assertThat(block.status()).isEqualTo("ON_HOLD");
        assertThat(block.slots()).extracting(s -> s.state()).containsExactly("ON_HOLD");
        assertNoneGivableAndNotCounted(mar, block);
    }

    @Test
    void aQueriedScheduledOrderWaitsForTheDoctorAndItsSlotsCannotBeGivenOrCounted() {
        dueControl();
        OrderRow order = ofStatus("QUERIED", null);

        Mar mar = mar();
        MarOrderView block = block(mar, order);

        assertThat(block.status()).isEqualTo("QUERIED");
        assertThat(block.slots()).extracting(s -> s.state()).containsExactly("WAITING");
        assertNoneGivableAndNotCounted(mar, block);
    }

    @Test
    void aReplacedSlotOfAnOlderVersionCannotBeGivenAndIsNotCounted() {
        dueControl();
        OrderRow order = active("pcm", "bd");
        Instant change = Instant.parse("2026-11-11T02:35:00Z");
        MedicineOrderVersion v1 = new MedicineOrderVersion(order.id(), 1, Kind.SCHEDULED, "bd", null, null, "ORAL",
                CHECKED_LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, DOCTOR, CHECKED_LONG_AGO, change,
                Verification.REQUIRED, DOCTOR, CHECKED_LONG_AGO);
        MedicineOrderVersion v2 = new MedicineOrderVersion(order.id(), 2, Kind.SCHEDULED, "od", null, null, "ORAL", change,
                null, null, OrderPriority.ROUTINE, null, null, "Dose changed", DOCTOR, change, null,
                Verification.REQUIRED, DOCTOR, change);
        timelineOverrides.put(order.id(), timelineOf(order, OrderStatus.ACTIVE, v1, v2));

        Mar mar = mar();
        MarOrderView block = block(mar, order);

        assertThat(block.slots()).extracting(s -> s.state()).containsExactly("DUE", "REPLACED");
        assertThat(block.slots()).extracting(s -> s.orderVersion()).containsOnly(1);
        assertNoneGivableAndNotCounted(mar, block);
    }

    @Test
    void aStoppedScheduledOrderShowsStoppedSlotsThatCannotBeGivenAndAreNotCounted() {
        dueControl();
        OrderRow order = fake.seedAt(ADMISSION, "pcm", "tds", MedicineOrderRepository.STOPPED, DOCTOR, CHECKED_LONG_AGO);

        Mar mar = mar();
        MarOrderView block = block(mar, order);

        assertThat(block.status()).isEqualTo("STOPPED");
        assertThat(block.slots()).extracting(s -> s.state()).contains("STOPPED");
        assertNoneGivableAndNotCounted(mar, block);
    }
}
