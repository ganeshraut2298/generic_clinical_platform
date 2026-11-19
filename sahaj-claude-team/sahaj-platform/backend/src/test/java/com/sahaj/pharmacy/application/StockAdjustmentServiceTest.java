package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.application.StockAdjustmentRepository.NewRow;
import com.sahaj.pharmacy.application.StockAdjustmentRepository.Row;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-5 (ADR-029 P5, P7): a stock adjustment is saved with an ADJ number and posted at once when no rule applies, or left
 * WAITING_APPROVAL for the engine; the order of the checks follows the contract; Schedule X needs a pharmacist and a second
 * signer; the approval posts the stored difference with the approver on the ledger lines.
 */
class StockAdjustmentServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String REASON = "stock-adjustment.count-mismatch";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final StockAdjustmentRepository adjustments = mock(StockAdjustmentRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final StockAdjustmentService service = new StockAdjustmentService(gate, guard, stores, products, stock, posting,
            adjustments, engine, approvals, series, numbers, clients, events, reasons, signature, team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final Store store = new Store(STORE_ID, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, UUID.randomUUID(), false, true,
            true, true, 0);
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist One", "PHARMACIST", List.of());
    private String schedule = "NONE";

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.ADJUST_STOCK))).thenReturn(store);
        when(reasons.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, REASON)).thenReturn(true);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.of(new BatchRow(BATCH, PRODUCT, "B1", LocalDate.of(2028, 3, 31),
                new BigDecimal("10.00"), new BigDecimal("4.5000"), null, NOW)));
        when(stock.availableQty(TENANT, STORE_ID, BATCH)).thenReturn(Optional.of(40));
        when(stock.lockBalances(eq(TENANT), eq(STORE_ID), any())).thenReturn(List.of(new Bal(UUID.randomUUID(), STORE_ID, PRODUCT,
                BATCH, StockState.AVAILABLE, 40, "B1", LocalDate.of(2028, 3, 31), NOW, new BigDecimal("10.00"),
                new BigDecimal("4.5000"))));
        when(products.find(TENANT, PRODUCT)).thenAnswer(i -> Optional.of(full()));
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(store));
        when(numbers.next(TENANT, DocType.STOCK_ADJUSTMENT)).thenReturn(1L);
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(stores.list(TENANT, true)).thenReturn(List.of(store));
        when(adjustments.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(savedRow("POSTED", null)));
    }

    private Full full() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "P-1", "Test Paracetamol 500 mg (fake)", null,
                false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new Full(row, true, true, null, true, null, true, 0, ME);
    }

    private Row savedRow(String status, UUID requestId) {
        return new Row(UUID.randomUUID(), UNIT, 1, "ADJ-0001", STORE_ID, PRODUCT, BATCH, 40, 38, -2, 900, REASON, null, null,
                null, status, requestId, null, NOW, ME, 0);
    }

    private PharmacyInputs.StockAdjustmentCreate input(int seen, int physical, PharmacyInputs.SecondSignerInput signer) {
        return new PharmacyInputs.StockAdjustmentCreate(KEY, BATCH, BigDecimal.valueOf(seen), BigDecimal.valueOf(physical),
                REASON, null, null, signer);
    }

    private PharmacyViews.Outcome<StockViews.StockAdjustment> create(PharmacyInputs.StockAdjustmentCreate in, UUID signer) {
        return service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(in), signer);
    }

    @Test
    void whenNoRuleAppliesTheAdjustmentIsPostedAtOnceWithItsNumberValueAndLedgerLine() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        PharmacyViews.Outcome<StockViews.StockAdjustment> outcome = create(input(40, 38, null), null);

        assertThat(outcome.created()).isTrue();
        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        InOrder order = Mockito.inOrder(clients, stores, numbers, stock, adjustments, posting);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateStockAdjustment"), any());
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(numbers).next(TENANT, DocType.STOCK_ADJUSTMENT);
        order.verify(stock).lockBalances(eq(TENANT), eq(STORE_ID), any());
        order.verify(adjustments).insert(eq(TENANT), row.capture());
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        order.verify(posting).post(any(StockPosting.Actor.class), lines.capture());
        assertThat(row.getValue().status()).isEqualTo("POSTED");
        assertThat(row.getValue().docNo()).isEqualTo("ADJ-0001");
        assertThat(row.getValue().bookQty()).isEqualTo(40);
        assertThat(row.getValue().physicalQty()).isEqualTo(38);
        assertThat(row.getValue().valuePaise()).as("2 x 4.50 rupees").isEqualTo(900L);
        StockPosting.Line line = lines.getValue().get(0);
        assertThat(line.delta()).isEqualTo(-2);
        assertThat(line.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(line.txnType()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(line.reasonKey()).isEqualTo(REASON);
        verify(engine, never()).submit(any(), any(), any(), any(), any());
        verify(audit).record(eq("PHARMACY_STOCK_ADJUSTMENT_RAISED"), any(), any(), any(), any());
        verify(audit).record(eq("PHARMACY_STOCK_ADJUSTMENT_POSTED"), any(), any(), any(), any());
    }

    @Test
    void whenARuleAppliesItIsSavedWaitingTheRequestIsSubmittedAndNothingIsPosted() {
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        UUID requestId = UUID.randomUUID();
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(requestId);

        create(input(40, 60, null), null);

        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        InOrder order = Mockito.inOrder(adjustments, engine);
        order.verify(adjustments).insert(eq(TENANT), row.capture());
        order.verify(engine).submit(eq(TENANT), eq(UNIT), eq(ME), any(ApprovalEngine.Subject.class), eq(plan));
        order.verify(adjustments).attachRequest(eq(TENANT), any(), eq(requestId), eq(ME));
        assertThat(row.getValue().status()).isEqualTo("WAITING_APPROVAL");
        verify(posting, never()).post(any(), any());
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.STOCK_ADJUSTMENT);
        assertThat(subject.getValue().amountPaise()).as("20 x 4.50 rupees, without GST").isEqualTo(9_000L);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(STORE_ID);
    }

    @Test
    void theValidationErrorsComeTogetherBeforeAnyLockOrNumber() {
        when(reasons.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, REASON)).thenReturn(false);
        PharmacyInputs.StockAdjustmentCreate bad = new PharmacyInputs.StockAdjustmentCreate(KEY, UUID.randomUUID(),
                new BigDecimal("1.5"), BigDecimal.valueOf(-1), REASON, null, null, null);

        assertThatThrownBy(() -> create(bad, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("physicalQty", "bookQtySeen", "reasonKey",
                        "batchId"));
        verify(numbers, never()).next(any(), any());
        verify(clients, never()).take(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPhysicalQuantityEqualToTheBookQuantityIsNoChangeToPost() {
        assertThatThrownBy(() -> create(input(40, 40, null), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message()).containsExactly("physicalQty:No change to post"));
    }

    @Test
    void aReasonEndingInOtherNeedsANoteOfFourCharacters() {
        when(reasons.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, "stock-adjustment.other")).thenReturn(true);
        PharmacyInputs.StockAdjustmentCreate in = new PharmacyInputs.StockAdjustmentCreate(KEY, BATCH, BigDecimal.valueOf(40),
                BigDecimal.valueOf(38), "stock-adjustment.other", "ab", null, null);

        assertThatThrownBy(() -> create(in, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("note"));
    }

    @Test
    void aBookQuantityThatMovedIs409ChangedWithTheCurrentQuantityAndNothingIsSaved() {
        assertThatThrownBy(() -> create(input(35, 30, null), null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("changed");
            assertThat(e.extensions().get("current")).isEqualTo(Map.of("batchId", BATCH.toString(), "availableQty", 40));
        });
        verify(adjustments, never()).insert(any(), any());
    }

    @Test
    void aSecondTapOfTheSameKeyThatLostTheRaceAnswersAsTheReplayOfTheFirstNotAnError() {
        UUID first = UUID.randomUUID();
        ClientRequestRepository.Taken won = new ClientRequestRepository.Taken(ME, "pharmacyCreateStockAdjustment", first);
        when(clients.take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateStockAdjustment"), any())).thenReturn(false);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty(), Optional.of(won));
        when(adjustments.find(TENANT, STORE_ID, first)).thenReturn(Optional.of(savedRow("POSTED", null)));

        PharmacyViews.Outcome<StockViews.StockAdjustment> outcome = create(input(40, 38, null), null);

        assertThat(outcome.created()).isFalse();
        verify(adjustments, never()).insert(any(), any());
        verify(numbers, never()).next(any(), any());
    }

    @Test
    void aSecondTapOfTheKeyThatLostTheRaceForAnotherOperationIs409ClientKeyReused() {
        ClientRequestRepository.Taken other = new ClientRequestRepository.Taken(ME, "pharmacySomethingElse", UUID.randomUUID());
        when(clients.take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), any(), any())).thenReturn(false);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty(), Optional.of(other));

        assertThatThrownBy(() -> create(input(40, 38, null), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
        verify(adjustments, never()).insert(any(), any());
    }

    @Test
    void anotherAdjustmentOfTheBatchThatIsWaitingIs409() {
        when(adjustments.waitingFor(TENANT, STORE_ID, BATCH)).thenReturn(true);

        assertThatThrownBy(() -> create(input(40, 38, null), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("adjustment-waiting"));
    }

    @Test
    void aScheduleXBatchNeedsAPharmacistAndAVerifiedSecondSignerWhoIsRecheckedAndStored() {
        schedule = "X";
        when(engine.plan(eq(TENANT), any())).thenReturn(null);
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");

        assertThatThrownBy(() -> create(input(40, 38, typed), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message())
                        .containsExactly("secondSigner:A second signature is needed for Schedule X items."));

        create(input(40, 38, typed), SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        verify(adjustments).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().secondSignerMembershipId()).isEqualTo(SIGNER);
        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(posting).post(actor.capture(), any());
        assertThat(actor.getValue().secondSignerMembershipId()).isEqualTo(SIGNER);
    }

    @Test
    void anOwnerCannotAdjustAScheduleXBatchEvenWithASigner() {
        schedule = "X";
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Owner One", "OWNER", List.of());

        assertThatThrownBy(() -> create(input(40, 38, null), SIGNER)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.problemType()).isEqualTo("schedule-x-pharmacist"));
        verify(adjustments, never()).insert(any(), any());
    }

    @Test
    void aSecondSignerIsNotStoredForABatchThatIsNotScheduleX() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        create(input(40, 38, new PharmacyInputs.SecondSignerInput(SIGNER, "1234")), SIGNER);

        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        verify(adjustments).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().secondSignerMembershipId()).isNull();
        verify(signature, never()).recheck(any(), any(), any());
    }

    @Test
    void theSameKeyAnswersTheFirstResultWithNoNewWrite() {
        UUID first = UUID.randomUUID();
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyCreateStockAdjustment", first)));

        PharmacyViews.Outcome<StockViews.StockAdjustment> outcome = create(input(40, 38, null), null);

        assertThat(outcome.created()).isFalse();
        verify(adjustments, never()).insert(any(), any());
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theApprovalPostsTheStoredDifferenceWithTheApproverOnTheLedgerAndMarksItPosted() {
        UUID approver = UUID.randomUUID();
        Row waiting = savedRow("WAITING_APPROVAL", UUID.randomUUID());
        when(adjustments.findForUpdate(TENANT, waiting.id())).thenReturn(Optional.of(waiting));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(waiting.id());
        PharmacyCaller approverCaller = new PharmacyCaller(TENANT, USER, approver, "Test HOD", "NURSE", List.of());

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "HOD", null));

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(posting).post(actor.capture(), lines.capture());
        assertThat(actor.getValue().membershipId()).as("the raiser acts").isEqualTo(ME);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(approver);
        assertThat(lines.getValue().get(0).delta()).isEqualTo(-2);
        verify(adjustments).setStatus(TENANT, waiting.id(), "POSTED", approver);
    }

    @Test
    void theApprovalRechecksTheStoredSignerOfAScheduleXRowAndARefusalPostsNothing() {
        UUID approver = UUID.randomUUID();
        Row waiting = new Row(UUID.randomUUID(), UNIT, 1, "ADJ-0001", STORE_ID, PRODUCT, BATCH, 40, 38, -2, 900, REASON, null, null,
                SIGNER, "WAITING_APPROVAL", UUID.randomUUID(), null, NOW, ME, 0);
        when(adjustments.findForUpdate(TENANT, waiting.id())).thenReturn(Optional.of(waiting));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(waiting.id());
        PharmacyCaller approverCaller = new PharmacyCaller(TENANT, USER, approver, "Test HOD", "NURSE", List.of());
        ApprovalSubjectHandler.Decision decision = new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "HOD", null);

        service.onApproved(decision);
        verify(signature).recheck(TENANT, ME, SIGNER);
        verify(posting).post(any(), any());

        org.mockito.Mockito.doThrow(ScheduleXSignature.invalid()).when(signature).recheck(TENANT, ME, SIGNER);
        assertThatThrownBy(() -> service.onApproved(decision)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.problemType()).isEqualTo("second-signer-invalid"));
        verify(posting, Mockito.times(1)).post(any(), any());
        verify(adjustments, Mockito.times(1)).setStatus(any(), any(), eq("POSTED"), any());
    }

    @Test
    void aRejectionMarksItRejectedAndMovesNoStock() {
        UUID approver = UUID.randomUUID();
        Row waiting = savedRow("WAITING_APPROVAL", UUID.randomUUID());
        when(adjustments.findForUpdate(TENANT, waiting.id())).thenReturn(Optional.of(waiting));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(waiting.id());

        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, new PharmacyCaller(TENANT, USER, approver, "Test HOD",
                "NURSE", List.of()), request, "HOD", null));

        verify(adjustments).setStatus(TENANT, waiting.id(), "REJECTED", approver);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theInboxSummaryShowsTheNumberTheStoreAndTheSignedValue() {
        Row waiting = new Row(UUID.randomUUID(), UNIT, 93, "ADJ-0093", STORE_ID, PRODUCT, BATCH, 40, 30, -10, 41_200, REASON, null,
                null, null, "WAITING_APPROVAL", UUID.randomUUID(), null, NOW, ME, 0);
        when(adjustments.findById(TENANT, waiting.id())).thenReturn(Optional.of(waiting));
        when(stores.find(TENANT, STORE_ID)).thenReturn(Optional.of(store));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(waiting.id());

        ApprovalSubjectHandler.Summary summary = service.summary(TENANT, request);

        assertThat(summary.what()).isEqualTo("ADJ-0093 · Test Ward 3B · − ₹ 412");
        assertThat(summary.chip()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(summary.subjectStoreId()).isEqualTo(STORE_ID);
        assertThat(service.subjectType()).isEqualTo("STOCK_ADJUSTMENT");
    }

    @Test
    void theGateAndTheStoreGuardRunBeforeAnythingInThePrecheckToo() {
        schedule = "X";
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");

        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(input(40, 38, typed)))).isEqualTo(typed);

        InOrder order = Mockito.inOrder(gate, guard);
        order.verify(gate).require(TENANT, USER, AccessLevel.PHARMACY_ACCESS);
        order.verify(guard).require(any(), eq(STORE_ID), eq(StoreCapability.ADJUST_STOCK));
        verify(signature, never()).verify(any(), any(), any(), any());
    }

    @Test
    void theSignerIsOnlyReturnedForAPharmacistOnAScheduleXBatchSoNoOtherCallerCanMakeAPinAttempt() {
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.Body<PharmacyInputs.StockAdjustmentCreate> body = PharmacyInputs.Body.of(input(40, 38, typed));

        // a batch that is not Schedule X: no signer, so the web layer makes no checkColleague call
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), body)).isNull();

        // an invented batch: no signer
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.empty());
        schedule = "X";
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), body)).isNull();
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.of(new BatchRow(BATCH, PRODUCT, "B1", LocalDate.of(2028, 3, 31),
                new BigDecimal("10.00"), new BigDecimal("4.5000"), null, NOW)));

        // an owner, a Store user or anyone who is not a pharmacist on a Schedule X batch: no signer
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Owner One", "OWNER", List.of());
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), body)).isNull();
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Store Staff One", "STORE_STAFF", List.of());
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), body)).isNull();

        // a body with no signer, or no batch: nothing to verify
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist One", "PHARMACIST", List.of());
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(input(40, 38, null)))).isNull();

        // the one case that needs a PIN
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), body)).isEqualTo(typed);
        verify(signature, never()).verify(any(), any(), any(), any());
    }

    @Test
    void theUserIdOfTheCallerNeverReachesAWriterOnRaiseApproveOrReject() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);
        create(input(40, 38, null), null);
        UserIdNeverWritten.assertNone(USER, stores, products, stock, posting, adjustments, approvals, series, numbers,
                clients, events, signature, audit);

        UUID approver = UUID.randomUUID();
        UUID approverUser = UUID.randomUUID();
        Row waiting = savedRow("WAITING_APPROVAL", UUID.randomUUID());
        when(adjustments.findForUpdate(TENANT, waiting.id())).thenReturn(Optional.of(waiting));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(waiting.id());
        PharmacyCaller approverCaller = new PharmacyCaller(TENANT, approverUser, approver, "Test HOD", "NURSE", List.of());
        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "HOD", null));
        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "HOD", null));
        UserIdNeverWritten.assertNone(approverUser, stores, products, stock, posting, adjustments, approvals, series,
                numbers, clients, events, signature, audit);
    }

    /**
     * Review m-5 (migration 1720, DEC-077 L9): the posting locks the store row {@code FOR UPDATE} and reads its status again,
     * as a switch-off does, so a store switched off between the guard and the lock answers 404 and nothing is numbered or posted.
     */
    @Test
    void aStoreSwitchedOffBetweenTheGuardAndTheLockIsNotFoundAndNothingIsNumberedOrPosted() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);
        Store off = new Store(STORE_ID, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, UUID.randomUUID(), false, true, true, false, 1);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(off));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> create(input(40, 38, null), null))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);

        Mockito.verifyNoInteractions(numbers, posting);
        Mockito.verify(adjustments, Mockito.never()).insert(any(), any());
    }
}
