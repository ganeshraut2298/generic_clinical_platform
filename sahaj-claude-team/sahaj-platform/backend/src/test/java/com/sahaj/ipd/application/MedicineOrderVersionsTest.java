package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-1 and 76-2 (ADR-027 D1, D4, D6, D7): an order is written with its first version, the ward profile decides the
 * pharmacist check, a STAT order has its notice at signing, stop needs a reason and the version seen, verify stamps the
 * version, a reject closes a query and a review item. No clinical text reaches an event or an audit row.
 */
class MedicineOrderVersionsTest extends MedicineTestBase {

    private static final String KEY = "3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11";

    private MedicineOrderService service;

    @BeforeEach
    void setUp() {
        service = newOrderService();
    }

    private static MedicineOrderInput input(String key, String drug, String frequency, String priority, String dose,
                                            String unit, String route, Integer days, String indication, Integer min,
                                            Integer max) {
        return new MedicineOrderInput(key, drug, frequency, null, null, dose == null ? null : new BigDecimal(dose),
                unit, route, null, days, indication, priority, min, max);
    }

    private MedicineOrderAnswer place(MedicineOrderInput input) {
        return service.order(TENANT, DOCTOR, "DOCTOR", ADMISSION, input);
    }

    private MedicineOrderAnswer placeScheduled() {
        return place(input(KEY, "pcm", "bd", "ROUTINE", "650", "MG", "ORAL", 5, "Pain after surgery", null, null));
    }

    private MedicineOrderVersion current(UUID orderId) {
        List<MedicineOrderVersion> versions = fake.versionsOf(orderId);
        return versions.get(versions.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), any(), after.capture());
        return after.getValue();
    }

    // ---- placing ----

    @Test
    void anOrderIsStoredWithItsFirstVersionAndTheHeadIsPending() {
        MedicineOrderAnswer answer = placeScheduled();

        OrderRow head = fake.rows.get(answer.order().id());
        MedicineOrderVersion v1 = current(head.id());
        assertThat(head.status()).isEqualTo("PENDING");
        assertThat(head.currentVersion()).isEqualTo(1);
        assertThat(head.priority()).isEqualTo("ROUTINE");
        assertThat(head.frequencyKey()).isEqualTo("bd");
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.kind()).isEqualTo(Kind.SCHEDULED);
        assertThat(v1.doseAmount()).isEqualByComparingTo("650");
        assertThat(v1.doseUnit()).isEqualTo("MG");
        assertThat(v1.routeKey()).isEqualTo("ORAL");
        assertThat(v1.verification()).isEqualTo(Verification.REQUIRED);
        assertThat(v1.indication()).isEqualTo("Pain after surgery");
        assertThat(v1.endAt()).isEqualTo(v1.startAt().plus(Duration.ofDays(5)));
        assertThat(v1.createdBy()).isEqualTo(DOCTOR);
        assertThat(notices.recorded).isEmpty();
        assertThat(answer.order().orderVersion()).isEqualTo(1);
        assertThat(answer.order().details().doseAmount()).isEqualByComparingTo("650");
        assertThat(answer.order().details().durationDays()).isEqualTo(5);
        assertThat(answer.order().changes()).isEmpty();
        assertThat(answer.sheet().statWindowMinutes()).isEqualTo(15);
    }

    @Test
    void theAuditRowAndTheEventCarryTheVersionFieldsAndNeverTheIndication() {
        MedicineOrderAnswer answer = placeScheduled();

        Map<String, Object> after = auditAfter("IPD_MED_ORDERED");
        assertThat(after).containsEntry("orderId", answer.order().id()).containsEntry("orderVersion", 1)
                .containsEntry("doseAmount", "650").containsEntry("doseUnit", "MG").containsEntry("routeKey", "ORAL")
                .containsEntry("kind", "SCHEDULED").containsEntry("priority", "ROUTINE")
                .containsEntry("verification", "REQUIRED").containsKey("startAt").containsKey("endAt")
                .containsKey("device");
        assertThat(after.toString()).doesNotContain("Pain after surgery");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Tab. Paracetamol 650 mg twice daily", DOCTOR);
    }

    @Test
    void withTheWardProfileOffTheOrderIsActiveNotPharmacistCheckedAndNoticedAtOnce() {
        settingsInForce = new EffectiveNursingSettings(false, 60, 60, 15, 30, 5, 10, 240);

        MedicineOrderAnswer answer = placeScheduled();

        OrderRow head = fake.rows.get(answer.order().id());
        assertThat(head.status()).isEqualTo("ACTIVE");
        assertThat(current(head.id()).verification()).isEqualTo(Verification.NOT_REQUIRED);
        assertThat(answer.order().details().pharmacistChecked()).isFalse();
        assertThat(notices.kinds()).containsExactly(NoticeKind.NEW);
        assertThat(notices.recorded.get(0).order().id()).isEqualTo(head.id());
        assertThat(notices.recorded.get(0).order().version()).isEqualTo(1);
    }

    @Test
    void aStatOrderIsOneDoseDueNowAndNoticedAtSigningWhileStillWaitingForThePharmacist() {
        MedicineOrderAnswer answer = place(input(KEY, "ondan", null, "STAT", "4", "MG", "IV", null, null, null, null));

        OrderRow head = fake.rows.get(answer.order().id());
        MedicineOrderVersion v1 = current(head.id());
        assertThat(head.status()).isEqualTo("PENDING");
        assertThat(head.priority()).isEqualTo("STAT");
        assertThat(head.frequencyKey()).isNull();
        assertThat(v1.kind()).isEqualTo(Kind.ONCE);
        assertThat(v1.frequencyKey()).isNull();
        assertThat(v1.startAt()).isEqualTo(fake.now);
        assertThat(v1.endAt()).isNull();
        assertThat(notices.recorded).hasSize(1);
        assertThat(notices.recorded.get(0).kind()).isEqualTo(NoticeKind.NEW);
        assertThat(notices.recorded.get(0).priority()).isEqualTo(OrderPriority.STAT);
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Inj. Ondansetron 4 mg " + "·" + " STAT", DOCTOR);
    }

    @Test
    void aPrnOrderHasNoSlotsOnTheHeadAndKeepsItsLimitsOnTheVersion() {
        MedicineOrderAnswer answer = place(input(KEY, "pcm", "prn", "ROUTINE", "650", "MG", "ORAL", null, "Fever", 360,
                4));

        OrderRow head = fake.rows.get(answer.order().id());
        MedicineOrderVersion v1 = current(head.id());
        assertThat(head.frequencyKey()).isNull();
        assertThat(v1.kind()).isEqualTo(Kind.PRN);
        assertThat(v1.minIntervalMin()).isEqualTo(360);
        assertThat(v1.maxPer24h()).isEqualTo(4);
        assertThat(answer.order().frequencyKey()).isEqualTo("prn");
        assertThat(answer.order().frequencyLabel()).isEqualTo("As needed (PRN)");
        assertThat(notices.recorded).isEmpty();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Tab. Paracetamol 650 mg " + "·" + " as needed", DOCTOR);
    }

    @Test
    void aGivenStartIsStoredAndANullStartIsTheDatabaseClock() {
        MedicineOrderInput later = new MedicineOrderInput(KEY, "pcm", "od", null, null, BigDecimal.ONE, "MG", "ORAL",
                gate.now().plusSeconds(7200).toString(), null, null, "ROUTINE", null, null);

        MedicineOrderAnswer withStart = place(later);
        UUID second = UUID.randomUUID();
        MedicineOrderAnswer without = service.order(TENANT, DOCTOR, "DOCTOR", ADMISSION,
                input(second.toString(), "met", "od", "ROUTINE", "1", "MG", "ORAL", null, null, null, null));

        assertThat(current(withStart.order().id()).startAt()).isEqualTo(gate.now().plusSeconds(7200));
        assertThat(current(without.order().id()).startAt()).isEqualTo(fake.now);
    }

    @Test
    void everyFieldErrorComesTogetherAndNothingIsSaved() {
        assertThatThrownBy(() -> place(input(KEY, "pcm", "bd", "ROUTINE", null, null, null, 0, null, null, null)))
                .isInstanceOf(InvalidFieldsException.class).satisfies(e -> assertThat(
                        ((InvalidFieldsException) e).errors()).extracting(m -> m.field())
                        .containsExactly("doseAmount", "doseUnit", "routeKey", "durationDays"));

        assertThat(fake.writes).isZero();
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void aRepeatWithTheSameValuesIsAReplayAndADifferentDoseIs409() {
        MedicineOrderAnswer first = placeScheduled();
        fake.writes = 0;

        MedicineOrderAnswer again = placeScheduled();

        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.order().id());
        assertThat(fake.writes).isZero();
        MedicineOrderInput otherDose = input(KEY, "pcm", "bd", "ROUTINE", "500", "MG", "ORAL", 5, "Pain after surgery",
                null, null);
        assertThatThrownBy(() -> place(otherDose)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("client-key-reused"));
        MedicineOrderInput otherPriority = input(KEY, "pcm", "bd", "URGENT", "650", "MG", "ORAL", 5,
                "Pain after surgery", null, null);
        assertThatThrownBy(() -> place(otherPriority)).isInstanceOf(ConflictException.class);
        assertThat(fake.rows).hasSize(1);
    }

    @Test
    void anOrderFromTheEmrRoundHasNoDoseTheDrugsRouteAndRoutinePriority() {
        MedicineOrderAnswer answer = service.orderForPort(TENANT, DOCTOR, "DOCTOR", ADMISSION,
                new MedicineOrderInput(KEY, "ceft", "tds", null, null));

        MedicineOrderVersion v1 = current(answer.order().id());
        assertThat(v1.doseAmount()).isNull();
        assertThat(v1.doseUnit()).isNull();
        assertThat(v1.routeKey()).isEqualTo("IV");
        assertThat(v1.kind()).isEqualTo(Kind.SCHEDULED);
        assertThat(v1.priority()).isEqualTo(OrderPriority.ROUTINE);
        assertThat(v1.endAt()).isNull();
        assertThat(answer.order().details().doseAmount()).isNull();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Ordered Inj. Ceftriaxone 1 g three times daily", DOCTOR);
    }

    @Test
    void theEmrRoundCannotOrderAsNeeded() {
        assertThatThrownBy(() -> service.orderForPort(TENANT, DOCTOR, "DOCTOR", ADMISSION,
                new MedicineOrderInput(KEY, "pcm", "prn", null, null))).isInstanceOf(InvalidFieldsException.class);
        assertThat(fake.rows).isEmpty();
    }

    @Test
    void aQueriedOrderAndAnOrderOnHoldStillCountForTheDuplicateCheck() {
        fake.seed(ADMISSION, "pcm", "QUERIED", OTHER_DOCTOR);
        fake.seed(ADMISSION, "met", "ON_HOLD", OTHER_DOCTOR);

        MedicineOrderAnswer pcm = place(input(KEY, "pcm", "od", "ROUTINE", "1", "MG", "ORAL", null, null, null, null));
        MedicineOrderAnswer met = service.order(TENANT, DOCTOR, "DOCTOR", ADMISSION,
                input(UUID.randomUUID().toString(), "met", "od", "ROUTINE", "1", "MG", "ORAL", null, null, null, null));

        assertThat(pcm.outcome()).isEqualTo("BLOCKED");
        assertThat(met.outcome()).isEqualTo("BLOCKED");
    }

    // ---- stop ----

    @Test
    void stopStoresTheReasonWritesTheNoticeAndLeavesTheReasonOutOfTheAudit() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        service.stop(TENANT, DOCTOR, "DOCTOR", active.id(),
                new MedicineViews.StopOrderInput(KEY, 1, "Rash after the first dose"));

        OrderRow stopped = fake.rows.get(active.id());
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(stopped.stopReason()).isEqualTo("Rash after the first dose");
        assertThat(notices.kinds()).containsExactly(NoticeKind.STOPPED);
        Map<String, Object> after = auditAfter("IPD_MED_STOPPED");
        assertThat(after).containsEntry("orderVersion", 1).containsEntry("reasonPresent", true)
                .containsEntry("statusBefore", "ACTIVE");
        assertThat(after.toString()).doesNotContain("Rash");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_STOP, "Stopped Tab. Paracetamol 650 mg",
                DOCTOR);
    }

    @Test
    void stopClosesAnOpenQueryOfAQueriedOrder() {
        OrderRow queried = fake.seed(ADMISSION, "pcm", "QUERIED", OTHER_DOCTOR);
        fake.insert(TENANT, queried.id(), 1, "Is this right?", PHARMACIST);

        service.stop(TENANT, DOCTOR, "DOCTOR", queried.id(), stopV1());

        assertThat(fake.findOpen(TENANT, queried.id())).isEmpty();
        assertThat(fake.findLatest(TENANT, List.of(queried.id())).get(queried.id()).closedBy()).isEqualTo("STOP");
    }

    @Test
    void anOrderOnHoldCanBeStopped() {
        OrderRow held = fake.seed(ADMISSION, "pcm", "ON_HOLD", OTHER_DOCTOR);

        service.stop(TENANT, DOCTOR, "DOCTOR", held.id(), stopV1());

        assertThat(fake.rows.get(held.id()).status()).isEqualTo("STOPPED");
    }

    @Test
    void stopOfAVersionTheDoctorDidNotSeeIs409OrderChangedAndWritesNothing() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", active.id(),
                new MedicineViews.StopOrderInput(KEY, 2, "No longer needed"))).isInstanceOf(ConflictException.class)
                .hasMessage("This order was changed by someone else. Reload and check the new order.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
        assertThat(fake.writes).isZero();
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void stopWithoutAReasonIsA400BeforeAnyLock() {
        OrderRow active = fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);

        assertThatThrownBy(() -> service.stop(TENANT, DOCTOR, "DOCTOR", active.id(),
                new MedicineViews.StopOrderInput(KEY, 1, "no"))).isInstanceOf(InvalidFieldsException.class);

        verifyNoInteractions(admissions);
    }

    // ---- verify ----

    private String tokenFor(String drugKey) {
        return assembler.safety().check(assembler.drug(drugKey), allergyText, List.of()).checkToken();
    }

    private PharmacistActionResult verifyAsPharmacist(OrderRow order, int version) {
        return service.verifyLocked(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new MedicineViews.VerifyOrderInput("4821", tokenFor(order.drugKey()), version));
    }

    @Test
    void verifyStampsTheVersionActivatesTheOrderAndNoticesTheNurses() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);

        PharmacistActionResult result = verifyAsPharmacist(pending, 1);

        assertThat(result.result().outcome()).isEqualTo("VERIFIED");
        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("ACTIVE");
        assertThat(current(pending.id()).verifiedBy()).isEqualTo(PHARMACIST);
        assertThat(notices.kinds()).containsExactly(NoticeKind.NEW);
        assertThat(auditAfter("IPD_MED_VERIFIED")).containsEntry("orderVersion", 1);
    }

    @Test
    void aStatOrderIsNotNoticedAgainAtVerify() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        fake.put(new OrderRow(pending.id(), pending.admissionId(), pending.clientKey(), pending.drugKey(), null,
                "PENDING", pending.orderedBy(), pending.orderedAt(), null, List.of(), null, null, null, null, null, null,
                null, 0, 1, "STAT", null));

        verifyAsPharmacist(pending, 1);

        assertThat(notices.recorded).isEmpty();
    }

    @Test
    void verifyOfAnOtherVersionIs409OrderChangedAndOfAQueriedOrderIsOrderState() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        assertThatThrownBy(() -> verifyAsPharmacist(pending, 2)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));

        OrderRow queried = fake.seed(ADMISSION, "met", "QUERIED", DOCTOR);
        assertThatThrownBy(() -> verifyAsPharmacist(queried, 1)).isInstanceOf(ConflictException.class)
                .hasMessage("This order is waiting for the doctor's reply.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void verifyClosesTheReviewItemsOfTheOrderWithOutcomeVerified() {
        OrderRow pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        UUID dose = UUID.randomUUID();
        reviewRepo.insert(TENANT, pending.id(), 1, dose);

        verifyAsPharmacist(pending, 1);

        assertThat(reviewRepo.findByDose(TENANT, dose).orElseThrow().outcome()).isEqualTo(ReviewOutcome.VERIFIED);
    }

    // ---- reject ----

    @Test
    void rejectOfAQueriedOrderClosesTheQueryAndTheReviewItems() {
        OrderRow queried = fake.seed(ADMISSION, "pcm", "QUERIED", DOCTOR);
        fake.insert(TENANT, queried.id(), 1, "Is this right?", PHARMACIST);
        UUID dose = UUID.randomUUID();
        reviewRepo.insert(TENANT, queried.id(), 1, dose);

        service.reject(TENANT, PHARMACIST, "PHARMACIST", queried.id(), new MedicineViews.RejectOrderInput("WRONG_DOSE"));

        assertThat(fake.rows.get(queried.id()).status()).isEqualTo("REJECTED");
        assertThat(fake.findLatest(TENANT, List.of(queried.id())).get(queried.id()).closedBy()).isEqualTo("REJECT");
        assertThat(reviewRepo.findByDose(TENANT, dose).orElseThrow().outcome()).isEqualTo(ReviewOutcome.REJECTED);
        assertThat(auditAfter("IPD_MED_REJECTED")).containsEntry("orderVersion", 1);
    }

    @Test
    void anOrderOnHoldIsAlreadyVerifiedForTheOtherPharmacist() {
        OrderRow held = fake.seed(ADMISSION, "pcm", "ON_HOLD", DOCTOR);

        assertThatThrownBy(() -> service.reject(TENANT, OTHER_PHARMACIST, "PHARMACIST", held.id(),
                new MedicineViews.RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
    }
}
