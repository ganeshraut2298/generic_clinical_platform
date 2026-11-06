package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistCheckRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecksSummary;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-31 the pharmacist check: the queue and its summary, Verify order with the PIN checked between two transactions,
 * Reject order, the self-verification rule, the hard stop and changed-findings refusals, the replay rules and the
 * guard against a query per row. No database.
 */
class PharmacistCheckServiceTest extends MedicineTestBase {

    private static final String MIDDOT = "·";

    private StaffPinPort staffPin;
    /** The service under test, with the real order service behind it. */
    private MedicineOrderService orders;
    private PharmacistCheckService service;

    @BeforeEach
    void setUp() {
        staffPin = mock(StaffPinPort.class);
        orders = new MedicineOrderService(fake, admissions, events, patients, patientAccess, actors, team, auditLog,
                gate, catalogue, assembler);
        service = new PharmacistCheckService(orders, fake, assembler, staffPin, team, gate);
        when(staffPin.check(eq(TENANT), any(), anyString())).thenReturn(new PinCheck(Outcome.OK, 5, null, 0));
    }

    private PharmacistChecks list(UUID actor, String role) {
        return service.list(TENANT, actor, role, null, null);
    }

    private OrderRow pending(String drug) {
        return fake.seed(ADMISSION, drug, "PENDING", DOCTOR);
    }

    private String tokenOf(OrderRow order) {
        PharmacistCheckRow row = list(PHARMACIST, "PHARMACIST").items().stream()
                .filter(r -> r.orderId().equals(order.id())).findFirst().orElseThrow();
        return row.check().checkToken();
    }

    private PharmacistActionResult verifyAs(UUID actor, String role, OrderRow order, String token) {
        return service.verify(TENANT, actor, role, order.id(), new VerifyOrderInput("4821", token));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), isNull(), after.capture());
        return after.getValue();
    }

    // ---- the queue ----

    @Test
    void theQueueListsWaitingOrdersOldestFirstWithPatientBedAndTheCheckRunNow() {
        allergyText = "Penicillin (rash)";
        OrderRow older = pending("pcm");
        OrderRow newer = pending("amox");

        PharmacistChecks checks = list(PHARMACIST, "PHARMACIST");

        assertThat(checks.state()).isEqualTo("on");
        assertThat(checks.asOf()).isEqualTo(fake.now);
        assertThat(checks.waiting()).isEqualTo(2);
        assertThat(checks.verifierName()).isEqualTo("Ph. Ravi Kulkarni");
        assertThat(checks.rejectReasons()).extracting(k -> k.key()).containsExactly("ALLERGY_CONFLICT",
                "INTERACTION_RISK", "WRONG_DOSE", "DUPLICATE_THERAPY");
        assertThat(checks.nextCursor()).isNull();
        assertThat(checks.items()).extracting(PharmacistCheckRow::orderId).containsExactly(older.id(), newer.id());
        PharmacistCheckRow row = checks.items().get(1);
        assertThat(row.drugName()).isEqualTo("Tab. Amoxicillin + clavulanate 625 mg");
        assertThat(row.frequencyLabel()).isEqualTo("Once daily");
        assertThat(row.patient().name()).isEqualTo("Test Patient");
        assertThat(row.patient().bedCode()).isEqualTo("202");
        assertThat(row.patient().allergyStatus()).isEqualTo("RECORDED");
        assertThat(row.orderedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(row.check().hardStops()).hasSize(1);
        assertThat(row.canVerify()).isFalse();
        assertThat(row.verifyLockReason()).isEqualTo("HARD_STOP");
        assertThat(row.canReject()).isTrue();
        assertThat(checks.items().get(0).canVerify()).isTrue();
        assertThat(checks.items().get(0).verifyLockReason()).isNull();
    }

    @Test
    void theOwnerSeesTheSameQueueAndCanVerifyOthersOrders() {
        memberRoles.put(OTHER_DOCTOR, "OWNER");
        pending("pcm");

        PharmacistChecks checks = list(OTHER_DOCTOR, "OWNER");

        assertThat(checks.items().get(0).canVerify()).isTrue();
    }

    @Test
    void theOrderingPersonSeesVerifyLockedWithSelf() {
        memberRoles.put(DOCTOR, "OWNER");
        pending("pcm");

        PharmacistChecks checks = list(DOCTOR, "OWNER");

        assertThat(checks.items().get(0).canVerify()).isFalse();
        assertThat(checks.items().get(0).verifyLockReason()).isEqualTo("SELF");
        assertThat(checks.items().get(0).canReject()).isTrue();
    }

    @Test
    void theLockReasonOrderIsHardStopThenSelfThenReadOnly() {
        memberRoles.put(DOCTOR, "OWNER");
        fake.seed(ADMISSION, "pcm", "ACTIVE", OTHER_DOCTOR);
        pending("pcm");
        ipdState("readonly");

        PharmacistChecks hardStopAndSelf = list(DOCTOR, "OWNER");
        assertThat(hardStopAndSelf.items().get(0).verifyLockReason()).isEqualTo("HARD_STOP");

        fake.rows.values().removeIf(o -> "ACTIVE".equals(o.status()));
        assertThat(list(DOCTOR, "OWNER").items().get(0).verifyLockReason()).isEqualTo("SELF");
        assertThat(list(PHARMACIST, "PHARMACIST").items().get(0).verifyLockReason()).isEqualTo("IPD_READONLY");
        assertThat(list(PHARMACIST, "PHARMACIST").items().get(0).canReject()).isTrue();
    }

    @Test
    void anOrderIsCheckedAgainstTheOtherLiveOrdersButNotAgainstItself() {
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);
        OrderRow aspirin = pending("asp");

        PharmacistCheckRow row = list(PHARMACIST, "PHARMACIST").items().get(0);

        assertThat(row.orderId()).isEqualTo(aspirin.id());
        assertThat(row.check().hardStops()).isEmpty();
        assertThat(row.check().warnings()).hasSize(1);
        assertThat(row.canVerify()).isTrue();
    }

    @Test
    void theDoctorsReasonIsShownOnTheRow() {
        OrderRow order = pending("asp");
        fake.rows.put(order.id(), new OrderRow(order.id(), ADMISSION, order.clientKey(), "asp", "od", "PENDING",
                DOCTOR, order.orderedAt(), "CARDIOLOGY_STENT", List.of("Major: x"), null, null, null, null, null,
                null, null, 0));

        PharmacistCheckRow row = list(PHARMACIST, "PHARMACIST").items().get(0);

        assertThat(row.overrideReason().label()).isEqualTo("Recent coronary stent, cardiology advised");
    }

    @Test
    void aDischargedAdmissionsOrdersLeaveTheQueueAndTheCount() {
        pending("pcm");
        fake.discharged.add(ADMISSION);

        PharmacistChecks checks = list(PHARMACIST, "PHARMACIST");

        assertThat(checks.waiting()).isZero();
        assertThat(checks.items()).isEmpty();
    }

    @Test
    void activeRejectedAndStoppedOrdersAreNotWaiting() {
        fake.seed(ADMISSION, "pcm", "ACTIVE", DOCTOR);
        fake.seed(ADMISSION, "panto", "REJECTED", DOCTOR);
        fake.seed(ADMISSION, "ondan", "STOPPED", DOCTOR);

        assertThat(list(PHARMACIST, "PHARMACIST").waiting()).isZero();
    }

    @Test
    void aPatientWhoCannotBeFoundKeepsTheRowSoItCanBeRejected() {
        pending("pcm");
        patientFound = false;

        PharmacistCheckRow row = list(PHARMACIST, "PHARMACIST").items().get(0);

        assertThat(row.patient().name()).isEqualTo("Patient");
        assertThat(row.patient().allergyStatus()).isEqualTo("NOT_RECORDED");
        assertThat(row.check().warnings()).extracting(w -> w.code()).containsExactly("ALLERGIES_NOT_RECORDED");
    }

    @Test
    void thePageHoldsTheLimitAndGivesACursorThatContinuesWithoutRepeats() {
        List<UUID> ids = new ArrayList<>();
        for (String drug : new String[] {"pcm", "panto", "ondan", "ns", "ceft"}) {
            ids.add(pending(drug).id());
        }

        PharmacistChecks first = service.list(TENANT, PHARMACIST, "PHARMACIST", "2", null);
        PharmacistChecks second = service.list(TENANT, PHARMACIST, "PHARMACIST", "2", first.nextCursor());
        PharmacistChecks third = service.list(TENANT, PHARMACIST, "PHARMACIST", "2", second.nextCursor());

        assertThat(first.waiting()).isEqualTo(5);
        assertThat(first.items()).extracting(PharmacistCheckRow::orderId).containsExactly(ids.get(0), ids.get(1));
        assertThat(first.nextCursor()).isNotNull().hasSize(32);
        assertThat(second.items()).extracting(PharmacistCheckRow::orderId).containsExactly(ids.get(2), ids.get(3));
        assertThat(second.waiting()).isEqualTo(5);
        assertThat(third.items()).extracting(PharmacistCheckRow::orderId).containsExactly(ids.get(4));
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void aFullPageThatIsTheLastHasNoCursor() {
        pending("pcm");
        pending("panto");

        PharmacistChecks page = service.list(TENANT, PHARMACIST, "PHARMACIST", "2", null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void aBadLimitOrCursorIs400() {
        for (String limit : new String[] {"0", "101", "abc", "", "-1"}) {
            assertThatThrownBy(() -> service.list(TENANT, PHARMACIST, "PHARMACIST", limit, null))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).field()).isEqualTo("limit"));
        }
        for (String cursor : new String[] {"", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!"}) {
            assertThatThrownBy(() -> service.list(TENANT, PHARMACIST, "PHARMACIST", null, cursor))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).field()).isEqualTo("cursor"));
        }
    }

    @Test
    void theQueueCostsAFixedNumberOfLookupsWhateverTheNumberOfRows() {
        for (int i = 0; i < 6; i++) {
            fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        }
        // the fake does not enforce the one-live-order index, so six rows exercise the batch lookups

        list(PHARMACIST, "PHARMACIST");

        verify(patients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(actors, times(1)).displayNames(eq(TENANT), anyCollection());
        verify(patients, never()).find(any(), any());
    }

    @Test
    void theQueueWritesNoEventNoAuditAndNoOrderChangeButOneRecordAccessRow() {
        pending("pcm");

        list(PHARMACIST, "PHARMACIST");

        verifyNoInteractions(events, auditLog);
        assertThat(fake.writes).isZero();
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, PHARMACIST);
    }

    // ---- S3: the list is a record access, once per distinct patient ----

    private static final UUID PATIENT_TWO = UUID.randomUUID();
    private static final UUID ADMISSION_TWO = UUID.randomUUID();

    private void secondPatientWithAnOrder() {
        fake.patientOfAdmission.put(ADMISSION_TWO, PATIENT_TWO);
        fake.bedOfAdmission.put(ADMISSION_TWO, "203");
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(
                PATIENT, new PatientClinical(PATIENT, "UH-000123", "Test Patient", 54, "F", allergyText),
                PATIENT_TWO, new PatientClinical(PATIENT_TWO, "UH-000124", "Second Patient", 61, "M", "None known")));
        fake.seed(ADMISSION_TWO, "pcm", "PENDING", DOCTOR);
    }

    @Test
    void theListWritesOneRecordAccessRowForEachDistinctPatientOfThePageForTheCaller() {
        pending("pcm");
        fake.seed(ADMISSION, "panto", "PENDING", DOCTOR);
        secondPatientWithAnOrder();

        PharmacistChecks checks = list(PHARMACIST, "PHARMACIST");

        assertThat(checks.items()).hasSize(3);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, PHARMACIST);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT_TWO, PHARMACIST);
        verifyNoMoreInteractions(patientAccess);
    }

    @Test
    void theOwnerWhoReadsTheListIsTheOneRecordedAsHavingOpenedTheRecords() {
        memberRoles.put(DOCTOR, "OWNER");
        pending("pcm");

        service.list(TENANT, DOCTOR, "OWNER", null, null);

        verify(patientAccess).recordOpened(TENANT, PATIENT, DOCTOR);
    }

    @Test
    void onlyThePatientsOfTheReturnedPageAreRecorded() {
        pending("pcm");
        secondPatientWithAnOrder();

        PharmacistChecks firstPage = service.list(TENANT, PHARMACIST, "PHARMACIST", "1", null);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotNull();
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, PHARMACIST);
        verify(patientAccess, never()).recordOpened(TENANT, PATIENT_TWO, PHARMACIST);
    }

    @Test
    void anEmptyQueueWritesNoRecordAccessRow() {
        PharmacistChecks checks = list(PHARMACIST, "PHARMACIST");

        assertThat(checks.items()).isEmpty();
        verifyNoInteractions(patientAccess);
    }

    @Test
    void aPatientWhoCannotBeFoundGetsNoRecordAccessRowAndTheListStillAnswers() {
        pending("pcm");
        patientFound = false;

        assertThat(list(PHARMACIST, "PHARMACIST").items()).hasSize(1);

        verifyNoInteractions(patientAccess);
    }

    @Test
    void aRefusedListWritesNoRecordAccessRow() {
        pending("pcm");

        assertThatThrownBy(() -> list(DOCTOR, "DOCTOR")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list(TENANT, PHARMACIST, "PHARMACIST", "0", null))
                .isInstanceOf(InvalidInputException.class);
        memberRoles.put(PHARMACIST, "NURSE");
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ForbiddenException.class);
        memberRoles.put(PHARMACIST, "PHARMACIST");
        ipdState("off");
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ModuleOffException.class);

        verifyNoInteractions(patientAccess);
    }

    @Test
    void theListIsReadWriteBecauseItWritesButTheSummaryStaysReadOnly() throws NoSuchMethodException {
        Transactional list = PharmacistCheckService.class.getMethod("list", UUID.class, UUID.class, String.class,
                String.class, String.class).getAnnotation(Transactional.class);
        Transactional summary = PharmacistCheckService.class.getMethod("summary", UUID.class, UUID.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(list.readOnly()).isFalse();
        assertThat(list.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(summary.readOnly()).isTrue();
    }

    @Test
    void theAnswerOfVerifyAndRejectCountsAsAListReadOncePerDistinctPatient() {
        OrderRow first = pending("pcm");
        OrderRow toReject = fake.seed(ADMISSION, "panto", "PENDING", DOCTOR);
        fake.seed(ADMISSION, "ondan", "PENDING", DOCTOR);
        secondPatientWithAnOrder();
        String token = tokenOf(first);
        org.mockito.Mockito.clearInvocations(patientAccess);

        PharmacistActionResult verified = verifyAs(PHARMACIST, "PHARMACIST", first, token);

        assertThat(verified.checks().items()).hasSize(3);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, PHARMACIST);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT_TWO, PHARMACIST);
        verifyNoMoreInteractions(patientAccess);

        org.mockito.Mockito.clearInvocations(patientAccess);

        PharmacistActionResult rejected = orders.reject(TENANT, PHARMACIST, "PHARMACIST", toReject.id(),
                new RejectOrderInput("WRONG_DOSE"));

        assertThat(rejected.checks().items()).hasSize(2);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, PHARMACIST);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT_TWO, PHARMACIST);
        verifyNoMoreInteractions(patientAccess);
    }

    @Test
    void aRefusedVerifyOrRejectWritesNoRecordAccessRow() {
        OrderRow order = pending("pcm");
        org.mockito.Mockito.clearInvocations(patientAccess);

        assertThatThrownBy(() -> verifyAs(DOCTOR, "DOCTOR", order, "x")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> orders.reject(TENANT, DOCTOR, "DOCTOR", order.id(), new RejectOrderInput("WRONG_DOSE")))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(patientAccess);
    }

    // ---- R3: the count is a count query ----

    @Test
    void theQueueCountsWithTheCountQueryAndTheSummaryKeepsTheKeysForTheToken() {
        pending("pcm");
        fake.seed(ADMISSION, "panto", "PENDING", DOCTOR);

        assertThat(list(PHARMACIST, "PHARMACIST").waiting()).isEqualTo(2);
        assertThat(fake.countWaitingCalls).isEqualTo(1);
        assertThat(fake.findWaitingKeysCalls).isZero();

        assertThat(service.summary(TENANT, PHARMACIST, "PHARMACIST").waiting()).isEqualTo(2);
        assertThat(fake.findWaitingKeysCalls).isEqualTo(1);
        assertThat(fake.countWaitingCalls).isEqualTo(1);
    }

    @Test
    void theQueueIsForThePharmacistAndTheOwnerOnlyReadAgainFromTheMembership() {
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "COUNSELLOR",
            null}) {
            assertThatThrownBy(() -> list(DOCTOR, role)).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only the pharmacist or the clinic owner can check orders.");
        }
        memberRoles.put(PHARMACIST, "NURSE");
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ForbiddenException.class);
        memberRoles.remove(PHARMACIST);
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ForbiddenException.class);
        memberRoles.put(PHARMACIST, "PHARMACIST");
        memberActive = false;
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ForbiddenException.class);
        ipdState("off");
        memberActive = true;
        assertThatThrownBy(() -> list(PHARMACIST, "PHARMACIST")).isInstanceOf(ModuleOffException.class);
    }

    // ---- the summary ----

    @Test
    void theSummaryHoldsCountsAndATokenOnlyAndChangesWhenTheQueueChanges() {
        OrderRow a = pending("pcm");
        pending("panto");

        PharmacistChecksSummary one = service.summary(TENANT, PHARMACIST, "PHARMACIST");
        PharmacistChecksSummary same = service.summary(TENANT, PHARMACIST, "PHARMACIST");
        PharmacistChecksSummary beforeReject = service.summary(TENANT, PHARMACIST, "PHARMACIST");
        assertThat(beforeReject.waiting()).isEqualTo(2);
        verifyNoInteractions(patients, patientAccess);
        orders.reject(TENANT, PHARMACIST, "PHARMACIST", a.id(), new RejectOrderInput("WRONG_DOSE"));
        PharmacistChecksSummary after = service.summary(TENANT, PHARMACIST, "PHARMACIST");

        assertThat(one.waiting()).isEqualTo(2);
        assertThat(one.changeToken()).hasSize(22).isEqualTo(same.changeToken());
        assertThat(one.asOf()).isEqualTo(fake.now);
        assertThat(after.waiting()).isEqualTo(1);
        assertThat(after.changeToken()).isNotEqualTo(one.changeToken());
    }

    @Test
    void theSummaryOfNoOrdersHasAStableToken() {
        PharmacistChecksSummary empty = service.summary(TENANT, PHARMACIST, "PHARMACIST");

        assertThat(empty.waiting()).isZero();
        assertThat(empty.changeToken()).isEqualTo("47DEQpj8HBSa-_TImW-5JA");
    }

    @Test
    void theSummaryUsesTheSameRolesAndIsAllowedInReadOnly() {
        ipdState("readonly");
        assertThat(service.summary(TENANT, PHARMACIST, "PHARMACIST").waiting()).isZero();
        assertThatThrownBy(() -> service.summary(TENANT, DOCTOR, "DOCTOR")).isInstanceOf(ForbiddenException.class);
        memberRoles.put(PHARMACIST, "DOCTOR");
        assertThatThrownBy(() -> service.summary(TENANT, PHARMACIST, "PHARMACIST"))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- verify ----

    @Test
    void verifyChecksThePinOnceBetweenTheTwoTransactionsAndActivatesTheOrder() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        MedicineOrderService spyOrders = org.mockito.Mockito.spy(orders);
        PharmacistCheckService s = new PharmacistCheckService(spyOrders, fake, assembler, staffPin, team, gate);

        PharmacistActionResult result = s.verify(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new VerifyOrderInput("4821", token));

        InOrder sequence = inOrder(spyOrders, staffPin);
        sequence.verify(spyOrders).prepareVerify(eq(TENANT), eq(PHARMACIST), eq("PHARMACIST"), eq(order.id()), any());
        sequence.verify(staffPin).check(TENANT, PHARMACIST, "4821");
        sequence.verify(spyOrders).verifyLocked(eq(TENANT), eq(PHARMACIST), eq("PHARMACIST"), eq(order.id()), any());
        OrderRow stored = fake.rows.get(order.id());
        assertThat(stored.status()).isEqualTo("ACTIVE");
        assertThat(stored.verifiedBy()).isEqualTo(PHARMACIST);
        assertThat(result.result().outcome()).isEqualTo("VERIFIED");
        assertThat(result.result().replayed()).isFalse();
        assertThat(result.result().drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(result.result().actorName()).isEqualTo("Ph. Ravi Kulkarni");
        assertThat(result.result().rejectReason()).isNull();
        assertThat(result.checks().waiting()).isZero();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY, "Verified Tab. Paracetamol 650 mg",
                PHARMACIST);
        assertThat(auditAfter("IPD_MED_VERIFIED")).containsEntry("orderId", order.id())
                .containsEntry("status", "ACTIVE").containsEntry("overrideReasonKey", null);
    }

    @Test
    void theEventNamesTheDoctorsReasonWhenTheOrderHadOne() {
        OrderRow order = pending("pcm");
        fake.rows.put(order.id(), new OrderRow(order.id(), ADMISSION, order.clientKey(), "pcm", "od", "PENDING",
                DOCTOR, order.orderedAt(), "SPECIALIST_ADVISED", List.of("Moderate: x"), null, null, null, null, null,
                null, null, 0));
        String token = tokenOf(order);

        verifyAs(PHARMACIST, "PHARMACIST", order, token);

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY,
                "Verified Tab. Paracetamol 650 mg " + MIDDOT + " accepted doctor's reason", PHARMACIST);
        assertThat(auditAfter("IPD_MED_VERIFIED")).containsEntry("overrideReasonKey", "SPECIALIST_ADVISED");
    }

    @Test
    void aWrongPinVerifiesNothingAndTheIpdTransactionNeverStarts() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        when(staffPin.check(TENANT, PHARMACIST, "4821")).thenReturn(new PinCheck(Outcome.WRONG, 2, null, 0));

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token))
                .isInstanceOf(StaffPinException.class).hasMessage("That PIN is not right. 2 tries left.");

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void everyPinOutcomeThatIsNotOkRefusesWithThePlatformsProblem() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        Map<Outcome, StaffPinException.Kind> expected = Map.of(Outcome.LOCKED, StaffPinException.Kind.PIN_LOCKED,
                Outcome.NOT_SET, StaffPinException.Kind.PIN_NOT_SET, Outcome.RATE_LIMITED,
                StaffPinException.Kind.RATE_LIMIT, Outcome.UNAVAILABLE, StaffPinException.Kind.PIN_UNAVAILABLE);
        for (Map.Entry<Outcome, StaffPinException.Kind> entry : expected.entrySet()) {
            when(staffPin.check(TENANT, PHARMACIST, "4821")).thenReturn(new PinCheck(entry.getKey(), 0, null, 60));

            assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token))
                    .isInstanceOf(StaffPinException.class)
                    .satisfies(e -> assertThat(((StaffPinException) e).kind()).isEqualTo(entry.getValue()));
        }
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
        assertThat(fake.writes).isZero();
    }

    @Test
    void thePinIsTheCallersOwnAndNeverReachesTheSecondTransactionTheEventsOrTheAudit() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);

        verifyAs(PHARMACIST, "PHARMACIST", order, token);

        verify(staffPin).check(TENANT, PHARMACIST, "4821");
        verify(staffPin, times(1)).check(any(), any(), anyString());
        assertThat(auditAfter("IPD_MED_VERIFIED").toString()).doesNotContain("4821");
    }

    @Test
    void theOrderingPersonCanNeverVerifyAndNeverSpendsPinTries() {
        memberRoles.put(DOCTOR, "OWNER");
        OrderRow order = pending("pcm");
        String token = tokenOf(order);

        assertThatThrownBy(() -> verifyAs(DOCTOR, "OWNER", order, token)).isInstanceOf(ForbiddenException.class)
                .hasMessage("The doctor who ordered this cannot verify it. Ask the pharmacist.")
                .satisfies(e -> assertThat(((ForbiddenException) e).problemType()).isEqualTo("self-verification"));

        verifyNoInteractions(staffPin);
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void verifyChecksTheBodyBeforeAnythingElseIsRead() {
        OrderRow order = pending("pcm");

        for (VerifyOrderInput bad : new VerifyOrderInput[] {new VerifyOrderInput("12", "x"),
            new VerifyOrderInput(null, null), null}) {
            assertThatThrownBy(() -> service.verify(TENANT, PHARMACIST, "PHARMACIST", order.id(), bad))
                    .isInstanceOf(InvalidFieldsException.class);
        }
        verifyNoInteractions(staffPin);
        verify(team, never()).findActiveByUserId(any(), any());
    }

    @Test
    void verifyRefusesOtherRolesTheModuleOffAndReadOnly() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> verifyAs(PHARMACIST, role, order, token)).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only the pharmacist or the clinic owner can check orders.");
        }
        memberRoles.put(PHARMACIST, "DOCTOR");
        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ForbiddenException.class);
        memberRoles.put(PHARMACIST, "PHARMACIST");
        ipdState("readonly");
        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));
        ipdState("off");
        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ModuleOffException.class);
        verifyNoInteractions(staffPin);
    }

    @Test
    void verifyOfAMissingOrderIs404BeforeThePinIsSpent() {
        assertThatThrownBy(() -> service.verify(TENANT, PHARMACIST, "PHARMACIST", UUID.randomUUID(),
                new VerifyOrderInput("4821", "AAAAAAAAAAAAAAAAAAAAAA"))).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(staffPin);
    }

    @Test
    void aHardStopFoundNowRefusesVerifyWithOrderBlocked() {
        OrderRow order = pending("amox");
        allergyText = "None known";
        String token = tokenOf(order);
        allergyText = "Penicillin";

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ConflictException.class)
                .hasMessage("Hard stop on this order. Reject it.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-blocked"));

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void findingsThatChangedSinceTheScreenLoadedRefuseVerifyWithChecksChanged() {
        OrderRow order = pending("asp");
        String token = tokenOf(order);
        fake.seed(ADMISSION, "hep", "ACTIVE", OTHER_DOCTOR);

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ConflictException.class)
                .hasMessage("The checks on this order changed. Review them and try again.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-checks-changed"));

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
        assertThat(fake.writes).isZero();
    }

    @Test
    void aTokenFromAnotherCheckIsRefused() {
        OrderRow order = pending("pcm");

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, "AAAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-checks-changed"));
    }

    @Test
    void theOrderItselfIsLeftOutOfItsOwnCheck() {
        // A single pending order of a drug that would be a duplicate of itself must still verify.
        OrderRow order = pending("pcm");

        verifyAs(PHARMACIST, "PHARMACIST", order, tokenOf(order));

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aRepeatedVerifyByTheSameUserAnswers200WithReplayedAndWritesNothing() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        verifyAs(PHARMACIST, "PHARMACIST", order, token);
        int writes = fake.writes;

        PharmacistActionResult again = verifyAs(PHARMACIST, "PHARMACIST", order, token);

        assertThat(again.result().replayed()).isTrue();
        assertThat(again.result().outcome()).isEqualTo("VERIFIED");
        assertThat(fake.writes).isEqualTo(writes);
        verify(events, times(1)).insert(any(), any(), anyString(), anyString(), any());
        verify(auditLog, times(1)).record(anyString(), anyString(), any(), any(), anyMap());
    }

    @Test
    void verifyOfAnOrderThatIsNoLongerWaitingIs409OrderStateWithTheRightText() {
        OrderRow verifiedByOther = pending("pcm");
        verifyAs(OTHER_PHARMACIST, "PHARMACIST", verifiedByOther, tokenOf(verifiedByOther));
        OrderRow rejected = pending("panto");
        orders.reject(TENANT, PHARMACIST, "PHARMACIST", rejected.id(), new RejectOrderInput("WRONG_DOSE"));
        OrderRow stopped = pending("ondan");
        orders.stop(TENANT, DOCTOR, "DOCTOR", stopped.id());

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", verifiedByOther, "AAAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(ConflictException.class).hasMessage("Ph. Sunita Patil already verified this order.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", rejected, "AAAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(ConflictException.class).hasMessage("This order was already rejected.");
        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", stopped, "AAAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(ConflictException.class).hasMessage("The doctor stopped this order.");
    }

    @Test
    void verifyOnADischargedAdmissionIs409NotAdmittedAndTheOrderKeepsItsStatus() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("not-admitted"));
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void verifyTakesOnlyTheAdmissionRowLockAndNoSecondLock() {
        OrderRow order = pending("pcm");

        verifyAs(PHARMACIST, "PHARMACIST", order, tokenOf(order));

        verify(admissions, times(1)).findForNoKeyUpdate(TENANT, ADMISSION);
        verify(admissions, never()).findForUpdate(any(), any());
    }

    @Test
    void aVerifyWhoseOrderCannotFindItsPatientIs404() {
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        patientFound = false;

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(NotFoundException.class);
    }

    // ---- reject ----

    @Test
    void rejectingAWaitingOrderRecordsTheReasonTheEventAndTheAudit() {
        OrderRow order = pending("pcm");

        PharmacistActionResult result = orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("INTERACTION_RISK"));

        OrderRow stored = fake.rows.get(order.id());
        assertThat(stored.status()).isEqualTo("REJECTED");
        assertThat(stored.rejectedBy()).isEqualTo(PHARMACIST);
        assertThat(stored.rejectReasonKey()).isEqualTo("INTERACTION_RISK");
        assertThat(result.result().outcome()).isEqualTo("REJECTED");
        assertThat(result.result().rejectReason().label()).isEqualTo("Interaction risk too high");
        assertThat(result.result().drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(result.checks().waiting()).isZero();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY,
                "Rejected Tab. Paracetamol 650 mg " + MIDDOT + " Interaction risk too high", PHARMACIST);
        assertThat(auditAfter("IPD_MED_REJECTED")).containsEntry("rejectReasonKey", "INTERACTION_RISK")
                .containsEntry("status", "REJECTED").containsEntry("drugKey", "pcm");
        verifyNoInteractions(staffPin);
    }

    @Test
    void anOrderWithAHardStopCanBeRejectedAndTheOrdererMayRejectItsOwnOrder() {
        allergyText = "Penicillin";
        OrderRow blocked = pending("amox");
        memberRoles.put(DOCTOR, "OWNER");
        OrderRow own = pending("pcm");

        orders.reject(TENANT, PHARMACIST, "PHARMACIST", blocked.id(), new RejectOrderInput("ALLERGY_CONFLICT"));
        orders.reject(TENANT, DOCTOR, "OWNER", own.id(), new RejectOrderInput("WRONG_DOSE"));

        assertThat(fake.rows.get(blocked.id()).status()).isEqualTo("REJECTED");
        assertThat(fake.rows.get(own.id()).status()).isEqualTo("REJECTED");
    }

    @Test
    void rejectIsAllowedInReadOnlyState() {
        ipdState("readonly");
        OrderRow order = pending("pcm");

        orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(), new RejectOrderInput("WRONG_DOSE"));

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("REJECTED");
    }

    @Test
    void aRepeatedRejectWithTheSameReasonIsAReplayAndAnotherReasonOrUserIs409() {
        OrderRow order = pending("pcm");
        orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(), new RejectOrderInput("WRONG_DOSE"));
        int writes = fake.writes;

        PharmacistActionResult again = orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("WRONG_DOSE"));

        assertThat(again.result().replayed()).isTrue();
        assertThat(again.result().rejectReason().key()).isEqualTo("WRONG_DOSE");
        assertThat(fake.writes).isEqualTo(writes);
        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("INTERACTION_RISK"))).isInstanceOf(ConflictException.class)
                .hasMessage("This order was already rejected.");
        assertThatThrownBy(() -> orders.reject(TENANT, OTHER_PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ConflictException.class)
                .hasMessage("This order was already rejected.");
        verify(events, times(1)).insert(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void rejectOfAVerifiedOrStoppedOrderIs409OrderState() {
        OrderRow active = pending("pcm");
        verifyAs(OTHER_PHARMACIST, "PHARMACIST", active, tokenOf(active));
        OrderRow stopped = pending("panto");
        orders.stop(TENANT, DOCTOR, "DOCTOR", stopped.id());

        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", active.id(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ConflictException.class)
                .hasMessage("Ph. Sunita Patil already verified this order.");
        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", stopped.id(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ConflictException.class)
                .hasMessage("The doctor stopped this order.");
    }

    @Test
    void rejectChecksTheReasonAndTheRoles() {
        OrderRow order = pending("pcm");

        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("OTHER"))).isInstanceOf(InvalidFieldsException.class);
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, role, order.id(),
                    new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ForbiddenException.class);
        }
        memberRoles.put(PHARMACIST, "NURSE");
        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ForbiddenException.class);
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void rejectOnADischargedAdmissionOrAMissingOrderIsRefused() {
        OrderRow order = pending("pcm");
        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", UUID.randomUUID(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(NotFoundException.class);
        admission = admissionRow("DISCHARGED");

        assertThatThrownBy(() -> orders.reject(TENANT, PHARMACIST, "PHARMACIST", order.id(),
                new RejectOrderInput("WRONG_DOSE"))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("not-admitted"));
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void aRaceBetweenStopAndVerifyLeavesExactlyOneWinner() {
        // The admission lock serialises them: whichever runs second reads the other's result.
        OrderRow order = pending("pcm");
        String token = tokenOf(order);
        orders.stop(TENANT, DOCTOR, "DOCTOR", order.id());

        assertThatThrownBy(() -> verifyAs(PHARMACIST, "PHARMACIST", order, token)).isInstanceOf(ConflictException.class)
                .hasMessage("The doctor stopped this order.");
        assertThat(fake.rows.get(order.id()).status()).isEqualTo("STOPPED");
    }

    @Test
    void afterAVerifyAnActiveOrderCanStillBeStoppedByADoctor() {
        OrderRow order = pending("pcm");
        verifyAs(PHARMACIST, "PHARMACIST", order, tokenOf(order));

        orders.stop(TENANT, OTHER_DOCTOR, "DOCTOR", order.id());

        assertThat(fake.rows.get(order.id()).status()).isEqualTo("STOPPED");
        assertThat(fake.rows.get(order.id()).verifiedBy()).isEqualTo(PHARMACIST);
    }
}
