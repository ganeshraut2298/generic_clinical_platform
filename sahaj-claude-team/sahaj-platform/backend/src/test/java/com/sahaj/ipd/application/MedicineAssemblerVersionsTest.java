package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.OrderNotices.AckState;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-76 the EMR sheet and the queue rows: the action flags by role, status and IPD state (ADR-027 D1, D12), the open
 * hold, the execution line the doctor reads (acknowledgement, next due, late), the lists of the order form and the
 * queue counts.
 */
class MedicineAssemblerVersionsTest extends MedicineTestBase {

    private static final Instant YESTERDAY = Instant.parse("2026-11-10T00:00:00Z");

    @BeforeEach
    void setUp() {
        fake.now = Instant.parse("2026-11-11T06:00:00Z");
    }

    /** Version 1 of the fixture order starts yesterday, so today's slots are all in force. */
    private void startYesterday(OrderRow order) {
        List<com.sahaj.ipd.domain.order.MedicineOrderVersion> list = new ArrayList<>();
        list.add(new com.sahaj.ipd.domain.order.MedicineOrderVersion(order.id(), 1,
                com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind.SCHEDULED, order.frequencyKey(), null, null, "ORAL",
                YESTERDAY, null, null, com.sahaj.ipd.domain.order.OrderPriority.ROUTINE, null, null, null,
                order.orderedBy(), YESTERDAY, null, com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification.REQUIRED,
                order.verifiedBy(), order.verifiedAt()));
        fake.versionRows.put(order.id(), list);
    }

    private EmrSheet sheet(String role, String state) {
        return assembler.sheet(TENANT, role, role, new ModuleEntitlement(state, false, 25), admission,
                patients.find(TENANT, PATIENT).orElseThrow(), fake.findByAdmission(TENANT, ADMISSION), fake.now);
    }

    private MedicineOrderRow row(String role, String state, OrderRow order) {
        return sheet(role, state).orders().stream().filter(o -> o.id().equals(order.id())).findFirst().orElseThrow();
    }

    @Test
    void aDoctorMayChangeStopAndHoldAnActiveOrderAndNotResumeOrReply() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);

        MedicineOrderRow row = row("DOCTOR", "on", active);

        assertThat(row.canChange()).isTrue();
        assertThat(row.canHold()).isTrue();
        assertThat(row.canStop()).isTrue();
        assertThat(row.canResume()).isFalse();
        assertThat(row.canReply()).isFalse();
    }

    @Test
    void anOrderOnHoldCanBeResumedAndStoppedButNotChangedOrHeld() {
        OrderRow held = fake.seed(ADMISSION, "pcm", "ON_HOLD", OTHER_DOCTOR);
        fake.holdRows.put(held.id(), new ArrayList<>(List.of(new OrderHoldInterval(fake.now.minusSeconds(60), DOCTOR,
                "Procedure at noon", null, null))));

        MedicineOrderRow row = row("DOCTOR", "on", held);

        assertThat(row.canResume()).isTrue();
        assertThat(row.canStop()).isTrue();
        assertThat(row.canChange()).isFalse();
        assertThat(row.canHold()).isFalse();
        assertThat(row.hold().heldByName()).isEqualTo("Dr Meera Joshi");
        assertThat(row.hold().reason()).isEqualTo("Procedure at noon");
    }

    @Test
    void aQueriedOrderCanBeRepliedToChangedAndStopped() {
        OrderRow queried = fake.seed(ADMISSION, "pcm", "QUERIED", OTHER_DOCTOR);

        MedicineOrderRow row = row("DOCTOR", "on", queried);

        assertThat(row.canReply()).isTrue();
        assertThat(row.canChange()).isTrue();
        assertThat(row.canStop()).isTrue();
        assertThat(row.canHold()).isFalse();
    }

    @Test
    void readOnlyIpdKeepsStopAndHoldAndRemovesChangeResumeAndReply() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);
        OrderRow held = fake.seed(ADMISSION, "met", "ON_HOLD", OTHER_DOCTOR);
        OrderRow queried = fake.seed(ADMISSION, "asp", "QUERIED", OTHER_DOCTOR);

        MedicineOrderRow a = row("DOCTOR", "readonly", active);

        assertThat(a.canStop()).isTrue();
        assertThat(a.canHold()).isTrue();
        assertThat(a.canChange()).isFalse();
        assertThat(row("DOCTOR", "readonly", held).canResume()).isFalse();
        assertThat(row("DOCTOR", "readonly", queried).canReply()).isFalse();
    }

    @Test
    void aNurseReadsTheSheetWithNoActionAndNoQuestion() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);

        MedicineOrderRow row = row("NURSE", "on", active);

        assertThat(row.canChange()).isFalse();
        assertThat(row.canHold()).isFalse();
        assertThat(row.canStop()).isFalse();
        assertThat(row.query()).isNull();
    }

    @Test
    void aDischargedAdmissionAllowsNoAction() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);
        admission = admissionRow("DISCHARGED");

        MedicineOrderRow row = row("DOCTOR", "on", active);

        assertThat(row.canChange()).isFalse();
        assertThat(row.canHold()).isFalse();
        assertThat(row.canStop()).isFalse();
    }

    @Test
    void theSheetCarriesTheFormListsAndTheStatWindowInForce() {
        EmrSheet sheet = sheet("DOCTOR", "on");

        assertThat(sheet.routes()).extracting(k -> k.key()).startsWith("ORAL", "IV", "IV_INFUSION");
        assertThat(sheet.doseUnits()).extracting(k -> k.key()).contains("TABLET", "MG", "UNIT");
        assertThat(sheet.priorities()).extracting(k -> k.label()).containsExactly("Routine", "Urgent", "STAT");
        assertThat(sheet.statWindowMinutes()).isEqualTo(15);
        assertThat(sheet.frequencies()).extracting(k -> k.key()).containsExactly("od", "bd", "tds", "q8", "prn");
        assertThat(sheet.drugs().get(0).routeKey()).isEqualTo("ORAL");
        assertThat(sheet.drugs().get(0).doseUnits()).isNotEmpty();
        assertThat(sheet.drugs().get(0).timeCritical()).isFalse();
    }

    @Test
    void theExecutionLineSaysNotAcknowledgedUntilTheLastOpenNoticeIsClosed() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);
        notices.acks.put(active.id(), new AckState(true, null, null));
        assertThat(row("DOCTOR", "on", active).execution().acknowledged()).isFalse();

        Instant at = fake.now.minusSeconds(300);
        notices.acks.put(active.id(), new AckState(false, DOCTOR, at));
        var execution = row("DOCTOR", "on", active).execution();

        assertThat(execution.acknowledged()).isTrue();
        assertThat(execution.acknowledgedAt()).isEqualTo(at);
        assertThat(execution.acknowledgedByName()).isEqualTo("Dr Meera Joshi");
    }

    @Test
    void anOrderWithNoNoticeCountsAsAcknowledgedWithNobodyNamed() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);

        var execution = row("DOCTOR", "on", active).execution();

        assertThat(execution.acknowledged()).isTrue();
        assertThat(execution.acknowledgedByName()).isNull();
        assertThat(execution.acknowledgedAt()).isNull();
    }

    @Test
    void theExecutionLineCountsLateSlotsAndFindsTheNextDueOneFromTheSlotRule() {
        // 8:00 AM IST is 02:30 UTC and 8:00 PM IST is 14:30 UTC; the clock is 06:00 UTC (11:30 AM IST).
        OrderRow bd = fake.seedAt(ADMISSION, "pcm", "bd", "ACTIVE", OTHER_DOCTOR, YESTERDAY);
        startYesterday(bd);

        var execution = row("DOCTOR", "on", bd).execution();

        assertThat(execution.lateCount()).isEqualTo(1);
        assertThat(execution.nextDueAt()).isEqualTo(Instant.parse("2026-11-11T14:30:00Z"));
        assertThat(execution.notGivenToday()).isZero();
    }

    @Test
    void anOrderThatIsNotActiveHasNoNextDueAndNoLateSlots() {
        OrderRow pending = fake.seedAt(ADMISSION, "pcm", "bd", "PENDING", OTHER_DOCTOR, null);
        OrderRow held = fake.seedAt(ADMISSION, "met", "bd", "ON_HOLD", OTHER_DOCTOR, YESTERDAY);
        startYesterday(held);
        fake.holdRows.put(held.id(), new ArrayList<>(List.of(new OrderHoldInterval(YESTERDAY, DOCTOR, "Hold reason", null,
                null))));

        assertThat(row("DOCTOR", "on", pending).execution().nextDueAt()).isNull();
        assertThat(row("DOCTOR", "on", pending).execution().lateCount()).isZero();
        assertThat(row("DOCTOR", "on", held).execution().nextDueAt()).isNull();
        assertThat(row("DOCTOR", "on", held).execution().lateCount()).isZero();
    }

    @Test
    void theQueueHoldsPendingAndQueriedOrdersWithTheirCountsApart() {
        fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        fake.seed(ADMISSION, "met", "QUERIED", DOCTOR);
        fake.seed(ADMISSION, "asp", "QUERIED", DOCTOR);
        reviewRepo.insert(TENANT, fake.seed(ADMISSION, "ibu", "ACTIVE", DOCTOR).id(), 1, java.util.UUID.randomUUID());

        var checks = assembler.queue(TENANT, PHARMACIST, new ModuleEntitlement("on", false, 25), 50, null);

        assertThat(checks.items()).hasSize(3);
        assertThat(checks.waiting()).isEqualTo(1);
        assertThat(checks.queried()).isEqualTo(2);
        assertThat(checks.reviewsOpen()).isEqualTo(1);
        assertThat(checks.items()).extracting(i -> i.status()).containsExactlyInAnyOrder("PENDING", "QUERIED",
                "QUERIED");
        var pendingRow = checks.items().stream().filter(i -> i.status().equals("PENDING")).findFirst().orElseThrow();
        assertThat(pendingRow.canVerify()).isTrue();
        assertThat(pendingRow.canQuery()).isTrue();
        assertThat(pendingRow.orderVersion()).isEqualTo(1);
        assertThat(pendingRow.details().kind()).isEqualTo("SCHEDULED");
        assertThat(pendingRow.changes()).isEmpty();
        assertThat(pendingRow.changeReason()).isNull();
        assertThat(pendingRow.query()).isNull();
        assertThat(checks.items().stream().filter(i -> i.status().equals("QUERIED")).allMatch(
                i -> !i.canVerify() && !i.canQuery() && i.verifyLockReason() == null)).isTrue();
    }
}
