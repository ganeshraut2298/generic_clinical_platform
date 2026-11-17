package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.ChangeOrderInput;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.HoldOrderInput;
import com.sahaj.ipd.application.MedicineViews.ResumeOrderInput;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-1 (ADR-027 D1, D4): change, hold and resume. Version n + 1 is written with the head, the old version is stamped
 * replaced, the ward profile decides the pharmacist check, first writer wins, a repeat writes nothing and no clinical
 * text (reason, indication) reaches an event, an audit row or a message.
 */
class OrderChangeServiceTest extends MedicineTestBase {

    private static final String KEY = "3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11";
    private static final String REASON = "Dose too low for the weight";

    private OrderChangeService service;
    private OrderRow active;

    @BeforeEach
    void setUp() {
        service = newChangeService();
        active = fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);
        setVersionOne(active, "1", "MG", null);
    }

    /** Version 1 of the order with a dose, so a change can be compared with it. */
    private void setVersionOne(OrderRow order, String dose, String unit, String indication) {
        List<MedicineOrderVersion> list = new ArrayList<>();
        list.add(new MedicineOrderVersion(order.id(), 1, Kind.SCHEDULED, "od", new BigDecimal(dose), unit, "ORAL",
                order.orderedAt(), null, indication, OrderPriority.ROUTINE, null, null, null, order.orderedBy(),
                order.orderedAt(), null, Verification.REQUIRED, order.verifiedBy(), order.verifiedAt()));
        fake.versionRows.put(order.id(), list);
    }

    private ChangeOrderInput change(int version, String reason, String dose, String frequency, String priority) {
        return new ChangeOrderInput(KEY, version, reason, new BigDecimal(dose), "MG", "ORAL", frequency, null, null, null,
                priority, null, null);
    }

    private EmrSheet doChange(ChangeOrderInput input) {
        return service.change(TENANT, DOCTOR, "DOCTOR", active.id(), input);
    }

    private MedicineOrderVersion current(UUID orderId) {
        List<MedicineOrderVersion> versions = fake.versionsOf(orderId);
        return versions.get(versions.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditArg(String action, int index) {
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), before.capture(), after.capture());
        return index == 0 ? before.getValue() : after.getValue();
    }

    // ---- change ----

    @Test
    void aChangeWritesVersionTwoSupersedesTheOldOneAndWaitsForThePharmacist() {
        EmrSheet sheet = doChange(change(1, REASON, "2", "od", "ROUTINE"));

        OrderRow head = fake.rows.get(active.id());
        MedicineOrderVersion v1 = fake.versionsOf(active.id()).get(0);
        MedicineOrderVersion v2 = current(active.id());
        assertThat(head.currentVersion()).isEqualTo(2);
        assertThat(head.status()).isEqualTo("PENDING");
        assertThat(v1.supersededAt()).isEqualTo(fake.now);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.doseAmount()).isEqualByComparingTo("2");
        assertThat(v2.changeReason()).isEqualTo(REASON);
        assertThat(v2.verification()).isEqualTo(Verification.REQUIRED);
        assertThat(v2.verifiedBy()).isNull();
        assertThat(v2.createdBy()).isEqualTo(DOCTOR);
        assertThat(v2.startAt()).isEqualTo(fake.now);
        assertThat(notices.kinds()).containsExactly(NoticeKind.CHANGED);
        assertThat(notices.recorded.get(0).order().version()).isEqualTo(2);
        assertThat(sheet.orders().get(0).orderVersion()).isEqualTo(2);
        assertThat(sheet.orders().get(0).changes()).extracting(l -> l.field()).containsExactly("dose");
        assertThat(sheet.orders().get(0).changeReason()).isEqualTo(REASON);
    }

    @Test
    void theEventAndTheAuditRowCarryNoReasonAndNoIndication() {
        setVersionOne(active, "1", "MG", "Secret old indication");
        ChangeOrderInput input = new ChangeOrderInput(KEY, 1, REASON, new BigDecimal("2"), "MG", "ORAL", "od", null, null,
                "Secret new indication", "ROUTINE", null, null);

        doChange(input);

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Changed Tab. Paracetamol 650 mg (v2)", DOCTOR);
        Map<String, Object> before = auditArg("IPD_MED_CHANGED", 0);
        Map<String, Object> after = auditArg("IPD_MED_CHANGED", 1);
        assertThat(before).containsEntry("orderVersion", 1).containsEntry("doseAmount", "1");
        assertThat(after).containsEntry("orderVersion", 2).containsEntry("doseAmount", "2")
                .containsEntry("reasonPresent", true).containsEntry("statusBefore", "ACTIVE")
                .containsEntry("status", "PENDING").containsEntry("verification", "REQUIRED").containsKey("device");
        assertThat(before.toString() + after).doesNotContain("Secret").doesNotContain("too low");
    }

    @Test
    void withTheWardProfileOffTheChangeIsActiveAtOnceAndNotPharmacistChecked() {
        settingsInForce = new EffectiveNursingSettings(false, 60, 60, 15, 30, 5, 10, 240);

        doChange(change(1, REASON, "2", "od", "ROUTINE"));

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
        assertThat(current(active.id()).verification()).isEqualTo(Verification.NOT_REQUIRED);
    }

    @Test
    void aChangeToStatIsAOneTimeVersionDueNow() {
        doChange(change(1, REASON, "4", null, "STAT"));

        MedicineOrderVersion v2 = current(active.id());
        assertThat(v2.kind()).isEqualTo(Kind.ONCE);
        assertThat(v2.frequencyKey()).isNull();
        assertThat(v2.priority()).isEqualTo(OrderPriority.STAT);
        assertThat(v2.startAt()).isEqualTo(fake.now);
        assertThat(fake.rows.get(active.id()).priority()).isEqualTo("STAT");
        assertThat(notices.recorded.get(0).priority()).isEqualTo(OrderPriority.STAT);
    }

    @Test
    void theStartIsTheLaterOfTheSentStartAndTheChangeTime() {
        Instant future = gate.now().plusSeconds(7200);
        ChangeOrderInput later = new ChangeOrderInput(KEY, 1, REASON, new BigDecimal("2"), "MG", "ORAL", "od",
                future.toString(), null, null, "ROUTINE", null, null);
        doChange(later);
        assertThat(current(active.id()).startAt()).isEqualTo(future);

        OrderRow other = fake.seed(ADMISSION, "met", "ACTIVE", OTHER_DOCTOR);
        setVersionOne(other, "1", "MG", null);
        ChangeOrderInput earlier = new ChangeOrderInput(UUID.randomUUID().toString(), 1, REASON, new BigDecimal("2"),
                "MG", "ORAL", "od", gate.now().minusSeconds(7200).toString(), null, null, "ROUTINE", null, null);
        service.change(TENANT, DOCTOR, "DOCTOR", other.id(), earlier);
        assertThat(current(other.id()).startAt()).isEqualTo(fake.now);
    }

    @Test
    void aChangeOfAQueriedOrderClosesTheQueryAndGoesBackToThePharmacist() {
        OrderRow queried = fake.seed(ADMISSION, "met", "QUERIED", OTHER_DOCTOR);
        setVersionOne(queried, "1", "MG", null);
        fake.insert(TENANT, queried.id(), 1, "Is this right?", PHARMACIST);

        service.change(TENANT, DOCTOR, "DOCTOR", queried.id(), change(1, REASON, "2", "od", "ROUTINE"));

        assertThat(fake.rows.get(queried.id()).status()).isEqualTo("PENDING");
        assertThat(fake.findLatest(TENANT, List.of(queried.id())).get(queried.id()).closedBy()).isEqualTo("CHANGE");
    }

    @Test
    void aChangeOfAPendingOrderIsAllowed() {
        OrderRow pending = fake.seed(ADMISSION, "met", "PENDING", OTHER_DOCTOR);
        setVersionOne(pending, "1", "MG", null);

        service.change(TENANT, DOCTOR, "DOCTOR", pending.id(), change(1, REASON, "2", "od", "ROUTINE"));

        assertThat(fake.rows.get(pending.id()).currentVersion()).isEqualTo(2);
    }

    @Test
    void aChangeWithNothingChangedIs400AndWritesNothing() {
        assertThatThrownBy(() -> doChange(change(1, REASON, "1", "od", "ROUTINE")))
                .isInstanceOf(InvalidFieldsException.class).satisfies(e -> assertThat(
                        ((InvalidFieldsException) e).errors().get(0).message()).isEqualTo("Nothing was changed."));

        assertThat(fake.writes).isZero();
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void aChangeOfAVersionTheDoctorDidNotSeeIs409OrderChanged() {
        assertThatThrownBy(() -> doChange(change(3, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ConflictException.class)
                .hasMessage("This order was changed by someone else. Reload and check the new order.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));

        assertThat(fake.writes).isZero();
    }

    @Test
    void aChangeOfAnOrderOnHoldMustWaitForResume() {
        OrderRow held = fake.seed(ADMISSION, "met", "ON_HOLD", OTHER_DOCTOR);

        assertThatThrownBy(() -> service.change(TENANT, DOCTOR, "DOCTOR", held.id(),
                change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ConflictException.class)
                .hasMessage("Resume this order before changing it.");
    }

    @Test
    void aChangeOfAStoppedOrRejectedOrderIs409() {
        for (String status : new String[] {"STOPPED", "REJECTED"}) {
            OrderRow closed = fake.seed(ADMISSION, "met", status, OTHER_DOCTOR);
            assertThatThrownBy(() -> service.change(TENANT, DOCTOR, "DOCTOR", closed.id(),
                    change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ConflictException.class)
                    .hasMessage("This order is stopped. Order the medicine again.")
                    .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
        }
    }

    @Test
    void aRepeatBySameDoctorWithTheSameValuesAnswers200AndWritesNothing() {
        ChangeOrderInput input = change(1, REASON, "2", "od", "ROUTINE");
        doChange(input);
        fake.writes = 0;

        EmrSheet again = doChange(input);

        assertThat(again.orders().get(0).orderVersion()).isEqualTo(2);
        assertThat(fake.writes).isZero();
        assertThat(fake.versionsOf(active.id())).hasSize(2);
        verify(patientAccess).recordOpened(TENANT, PATIENT, DOCTOR);
    }

    @Test
    void aRepeatWithOtherValuesIsAStaleVersion() {
        doChange(change(1, REASON, "2", "od", "ROUTINE"));

        assertThatThrownBy(() -> doChange(change(1, REASON, "3", "od", "ROUTINE"))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));
    }

    @Test
    void aNurseCannotChangeAndReadOnlyRefusesBeforeTheLock() {
        assertThatThrownBy(() -> service.change(TENANT, NURSE, "NURSE", active.id(),
                change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only a doctor can do this.");
        ipdState("readonly");
        assertThatThrownBy(() -> doChange(change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));

        verifyNoInteractions(admissions, events, auditLog);
    }

    @Test
    void aMembershipThatIsNotADoctorAnyMoreIsRefused() {
        memberRoles.put(DOCTOR, "NURSE");

        assertThatThrownBy(() -> doChange(change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(ForbiddenException.class);
        assertThat(fake.writes).isZero();
    }

    @Test
    void aChangeOfAnotherClinicsOrderIs404AndNeverReachesTheGuard() {
        assertThatThrownBy(() -> service.change(TENANT, DOCTOR, "DOCTOR", UUID.randomUUID(),
                change(1, REASON, "2", "od", "ROUTINE"))).isInstanceOf(NotFoundException.class);

        verify(guard, never()).requireAdmissionLocked(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBodyErrorIs400BeforeAnyLock() {
        assertThatThrownBy(() -> doChange(change(1, "no", "2", "od", "ROUTINE"))).isInstanceOf(InvalidFieldsException.class);

        verifyNoInteractions(admissions);
    }

    @Test
    void changeOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(DOCTOR), eq("DOCTOR"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> doChange(change(1, REASON, "2", "od", "ROUTINE")))
                .isInstanceOf(OutsideScopeException.class);

        assertThat(fake.writes).isZero();
        verifyNoInteractions(patientAccess, events, auditLog);
    }

    // ---- hold ----

    private HoldOrderInput hold(int version) {
        return new HoldOrderInput(KEY, version, "Procedure at noon");
    }

    @Test
    void holdOpensAHoldMovesTheOrderOnHoldAndNoticesTheNurses() {
        EmrSheet sheet = service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));

        OrderRow head = fake.rows.get(active.id());
        assertThat(head.status()).isEqualTo("ON_HOLD");
        assertThat(fake.holdRows.get(active.id())).hasSize(1);
        assertThat(fake.holdRows.get(active.id()).get(0).holdReason()).isEqualTo("Procedure at noon");
        assertThat(notices.kinds()).containsExactly(NoticeKind.HELD);
        assertThat(sheet.orders().get(0).hold().reason()).isEqualTo("Procedure at noon");
        assertThat(sheet.orders().get(0).hold().heldByName()).isEqualTo("Dr Meera Joshi");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_STOP, "Held Tab. Paracetamol 650 mg",
                DOCTOR);
        Map<String, Object> after = auditArg("IPD_MED_HELD", 1);
        assertThat(after).containsEntry("status", "ON_HOLD").containsEntry("reasonPresent", true)
                .containsEntry("orderVersion", 1);
        assertThat(after.toString()).doesNotContain("Procedure");
    }

    @Test
    void holdIsAllowedInReadOnlyIpd() {
        ipdState("readonly");

        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ON_HOLD");
    }

    @Test
    void holdOfAnOrderThatIsNotActiveIs409() {
        OrderRow pending = fake.seed(ADMISSION, "met", "PENDING", OTHER_DOCTOR);

        assertThatThrownBy(() -> service.hold(TENANT, DOCTOR, "DOCTOR", pending.id(), hold(1)))
                .isInstanceOf(ConflictException.class).hasMessage("This order is not active.");
    }

    @Test
    void holdOfAnOtherVersionIs409OrderChanged() {
        assertThatThrownBy(() -> service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(2)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));
    }

    @Test
    void aRepeatedHoldByTheSameDoctorAnswers200AndWritesNothing() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));
        fake.writes = 0;

        EmrSheet again = service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));

        assertThat(again.orders().get(0).status()).isEqualTo("ON_HOLD");
        assertThat(fake.writes).isZero();
        assertThat(fake.holdRows.get(active.id())).hasSize(1);
    }

    @Test
    void aHoldByAnotherDoctorOnAnOrderAlreadyOnHoldIs409() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));

        assertThatThrownBy(() -> service.hold(TENANT, OTHER_DOCTOR, "DOCTOR", active.id(), hold(1)))
                .isInstanceOf(ConflictException.class).hasMessage("This order is not active.");
    }

    @Test
    void holdNeedsAReasonOfFourCharacters() {
        assertThatThrownBy(() -> service.hold(TENANT, DOCTOR, "DOCTOR", active.id(),
                new HoldOrderInput(KEY, 1, "no"))).isInstanceOf(InvalidFieldsException.class);
        assertThat(fake.writes).isZero();
    }

    @Test
    void holdOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(DOCTOR), eq("DOCTOR"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1)))
                .isInstanceOf(OutsideScopeException.class);

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
        verifyNoInteractions(patientAccess, events, auditLog);
    }

    // ---- resume ----

    @Test
    void resumeClosesTheHoldAndTheOrderIsActiveWithNoNewCheck() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));
        notices.recorded.clear();

        EmrSheet sheet = service.resume(TENANT, OTHER_DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1));

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ACTIVE");
        assertThat(fake.holdRows.get(active.id()).get(0).isOpen()).isFalse();
        assertThat(fake.holdRows.get(active.id()).get(0).resumedBy()).isEqualTo(OTHER_DOCTOR);
        assertThat(notices.kinds()).containsExactly(NoticeKind.RESUMED);
        assertThat(sheet.orders().get(0).hold()).isNull();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ORDER,
                "Resumed Tab. Paracetamol 650 mg", OTHER_DOCTOR);
    }

    @Test
    void resumeOfAnOrderNotOnHoldIs409() {
        assertThatThrownBy(() -> service.resume(TENANT, DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1)))
                .isInstanceOf(ConflictException.class).hasMessage("This order is not on hold.");
    }

    @Test
    void aRepeatedResumeBySameDoctorAnswers200AndWritesNothing() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));
        service.resume(TENANT, DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1));
        fake.writes = 0;

        EmrSheet again = service.resume(TENANT, DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1));

        assertThat(again.orders().get(0).status()).isEqualTo("ACTIVE");
        assertThat(fake.writes).isZero();
    }

    @Test
    void resumeIsRefusedInReadOnlyIpd() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));
        ipdState("readonly");

        assertThatThrownBy(() -> service.resume(TENANT, DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));
        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ON_HOLD");
    }

    @Test
    void resumeOutsideScopeWritesNothing() {
        service.hold(TENANT, DOCTOR, "DOCTOR", active.id(), hold(1));
        org.mockito.Mockito.clearInvocations(patientAccess, events, auditLog);
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(DOCTOR), eq("DOCTOR"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.resume(TENANT, DOCTOR, "DOCTOR", active.id(), new ResumeOrderInput(KEY, 1)))
                .isInstanceOf(OutsideScopeException.class);

        assertThat(fake.rows.get(active.id()).status()).isEqualTo("ON_HOLD");
        verifyNoInteractions(patientAccess, events, auditLog);
    }
}
