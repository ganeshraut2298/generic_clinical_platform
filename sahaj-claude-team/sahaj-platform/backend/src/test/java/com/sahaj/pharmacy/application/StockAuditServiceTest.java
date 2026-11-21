package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.StockAuditRepository.Audit;
import com.sahaj.pharmacy.application.StockAuditRepository.Line;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ApproverResolver;
import com.sahaj.pharmacy.domain.ConditionKind;
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
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-17 (ADR-029 P12, BR-30, rule 24): the stock audit. The count sheet has no system quantity for anyone; submit fixes the
 * book quantity; "Approve and post" refuses the submitter, a line whose stock moved and a missing reason, posts one
 * STOCK_AUDIT_ADJUSTMENT line per difference at level 0 or after the reviewer's own approval, and sends a decrease above what the
 * batch holds back to recount; every audit write goes through the repository that raises the version.
 */
class StockAuditServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID COUNTER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID AUDIT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2028, 3, 31);
    private static final String REASON = "stock-adjustment.count-mismatch";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final StockAuditRepository audits = mock(StockAuditRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final DocumentViews documentViews = mock(DocumentViews.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog auditLog = mock(AuditLog.class);
    private final StockAuditService service = new StockAuditService(gate, guard, stores, products, stock, posting, audits, engine,
            approvals, series, numbers, clients, events, documentViews, reasons, signature, team, auditLog,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final Store store = new Store(STORE_ID, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, UUID.randomUUID(), false, true, true,
            true, 0);
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Reviewer One", "PHARMACIST", List.of());
    private Audit current = audit("WAITING_REVIEW", COUNTER, 7);
    private List<Line> lines = List.of(line(38, 40, REASON));
    private String schedule = "NONE";

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(STORE_ID), any(StoreCapability.class))).thenReturn(store);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(store));
        when(stores.find(TENANT, STORE_ID)).thenReturn(Optional.of(store));
        when(audits.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(current));
        when(audits.findById(TENANT, AUDIT)).thenAnswer(i -> Optional.of(current));
        when(audits.findForUpdate(TENANT, AUDIT)).thenAnswer(i -> Optional.of(current));
        when(audits.allLines(TENANT, AUDIT)).thenAnswer(i -> lines);
        when(audits.bump(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
        when(products.find(TENANT, PRODUCT)).thenAnswer(i -> Optional.of(full()));
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.of(new BatchRow(BATCH, PRODUCT, "B1", EXPIRY,
                new BigDecimal("10.00"), new BigDecimal("4.5000"), null, NOW)));
        when(stock.lockBalances(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> List.of(balance(40)));
        when(documentViews.load(any(), any(), any(), any())).thenReturn(new DocumentViews.Loaded(List.of(), Map.of()));
        when(documentViews.history(any(), any())).thenReturn(List.of());
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(numbers.next(TENANT, DocType.STOCK_AUDIT)).thenReturn(93L);
        when(reasons.isActive(TENANT, ReasonList.STOCK_ADJUSTMENT, REASON)).thenReturn(true);
    }

    private Bal balance(int qty) {
        return new Bal(UUID.randomUUID(), STORE_ID, PRODUCT, BATCH, StockState.AVAILABLE, qty, "B1", EXPIRY, NOW,
                new BigDecimal("10.00"), new BigDecimal("4.5000"));
    }

    private ProductRepository.Full full() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "P-1", "Test Paracetamol 500 mg (fake)", null,
                false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, true, 0, ME);
    }

    private static Audit audit(String status, UUID submittedBy, long version) {
        return new Audit(AUDIT, UNIT, 93, "AUD-0093", STORE_ID, "ALL_AVAILABLE", status, submittedBy, NOW, null, null, NOW, ME,
                version);
    }

    private static Line line(Integer counted, Integer book, String reason) {
        return new Line(LINE, AUDIT, PRODUCT, BATCH, counted, false, null, COUNTER, NOW, false, book, book == null ? null : NOW,
                reason, null, null);
    }

    private PharmacyInputs.PostWithSigner post(long version) {
        return new PharmacyInputs.PostWithSigner(KEY, BigDecimal.valueOf(version), null);
    }

    private StockAuditViews.StockAudit approve(UUID signer) {
        return service.approveAndPost(TENANT, USER, STORE_ID.toString(), AUDIT.toString(), PharmacyInputs.Body.of(post(7)), signer);
    }

    // ---- start ----

    @Test
    void aSecondOpenAuditOfTheStoreIsRefusedWithAuditOpen() {
        when(audits.openOf(TENANT, STORE_ID)).thenReturn(Optional.of(audit("COUNTING", null, 0)));

        assertThatThrownBy(() -> service.start(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.StockAuditCreate(KEY, "ALL_AVAILABLE", null))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("audit-open");
                    assertThat(e.getMessage()).isEqualTo("A stock count is already open for this store.");
                });
        verify(audits, never()).insert(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void startingWithNoAvailableBatchIsNothingToCount() {
        when(audits.availableBatches(TENANT, STORE_ID, null)).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.StockAuditCreate(KEY, "ALL_AVAILABLE", null))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).anyMatch(f -> f.message().contains("Nothing to count")));
    }

    @Test
    void startingAnAuditLocksTheStoreBeforeTheNumberAndWritesTheLinesCountingWithNoQuantityAnswered() {
        when(audits.availableBatches(TENANT, STORE_ID, null)).thenReturn(List.of(new StockAuditRepository.Seed(PRODUCT, BATCH, 40)));
        current = audit("COUNTING", null, 0);

        PharmacyViews.Outcome<StockAuditViews.StockAudit> outcome = service.start(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.StockAuditCreate(KEY, "ALL_AVAILABLE", null)));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.view().status()).isEqualTo("COUNTING");
        InOrder order = Mockito.inOrder(clients, stores, numbers, audits);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyStartStockAudit"), any());
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(numbers).next(TENANT, DocType.STOCK_AUDIT);
        order.verify(audits).insert(eq(TENANT), any(), eq(UNIT), eq(93L), eq("AUD-0093"), eq(STORE_ID), eq("ALL_AVAILABLE"), eq(ME));
        order.verify(audits).insertLines(eq(TENANT), eq(UNIT), any(), any(), eq(ME));
        verify(events).insert(eq(TENANT), eq(UNIT), eq("STOCK_AUDIT"), any(), eq("STARTED"), eq(STORE_ID), eq(ME), any(), any(),
                any(), any(), any());
    }

    // ---- the blind count sheet ----

    @Test
    void theCountSheetLineHasNoSystemBookDifferenceOrValueField() {
        List<String> names = Arrays.stream(StockAuditViews.CountSheetLine.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(names).containsExactly("auditLineId", "product", "batch", "rack", "barcode", "counted", "notCounted",
                "notCountedReason", "recount");
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("book") || n.toLowerCase().contains("system")
                || n.toLowerCase().contains("diff") || n.toLowerCase().contains("value") || n.toLowerCase().contains("available"));
    }

    @Test
    void theCountSheetOfARecountAnswersOnlyTheLinesSentBackAndTheAuditVersion() {
        current = audit("RECOUNT", COUNTER, 12);
        when(audits.lines(TENANT, AUDIT, true, 200, 0)).thenReturn(List.of(line(null, null, null)));
        when(audits.countLines(TENANT, AUDIT, true)).thenReturn(1L);
        when(audits.countLines(TENANT, AUDIT, false)).thenReturn(5L);
        when(audits.countAnswered(TENANT, AUDIT)).thenReturn(4L);
        when(products.storeSettings(TENANT, PRODUCT)).thenReturn(List.of());

        StockAuditViews.CountSheet sheet = service.countSheet(TENANT, USER, STORE_ID.toString(), AUDIT.toString(), null, null);

        assertThat(sheet.items()).hasSize(1);
        assertThat(sheet.version()).isEqualTo(12);
        assertThat(sheet.itemsInCount()).isEqualTo(5);
        assertThat(sheet.counted()).isEqualTo(4);
        verify(audits).lines(TENANT, AUDIT, true, 200, 0);
    }

    @Test
    void savingCountsWithAnOldVersionIs409Changed() {
        current = audit("COUNTING", null, 9);
        PharmacyInputs.CountsInput in = new PharmacyInputs.CountsInput(BigDecimal.valueOf(8), List.of(
                new PharmacyInputs.CountLineInput(LINE, BigDecimal.valueOf(38), false, null)));

        assertThatThrownBy(() -> service.saveCounts(TENANT, USER, STORE_ID.toString(), AUDIT.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(audits, never()).saveCount(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void savingCountsOnALineNotSentBackWhileRecountIsADocStateConflict() {
        current = audit("RECOUNT", COUNTER, 3);
        when(audits.linesForUpdate(eq(TENANT), eq(AUDIT), any())).thenReturn(List.of(line(null, null, null)));
        PharmacyInputs.CountsInput in = new PharmacyInputs.CountsInput(BigDecimal.valueOf(3), List.of(
                new PharmacyInputs.CountLineInput(LINE, BigDecimal.valueOf(38), false, null)));

        assertThatThrownBy(() -> service.saveCounts(TENANT, USER, STORE_ID.toString(), AUDIT.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void savingCountsWritesTheValuesAndRaisesTheAuditVersionOnce() {
        current = audit("COUNTING", null, 3);
        Line open = line(null, null, null);
        when(audits.linesForUpdate(eq(TENANT), eq(AUDIT), any())).thenReturn(List.of(open));
        when(audits.findLine(TENANT, LINE)).thenReturn(Optional.of(line(38, null, null)));
        when(audits.bump(TENANT, AUDIT, 3L, ME)).thenReturn(true);
        PharmacyInputs.CountsInput in = new PharmacyInputs.CountsInput(BigDecimal.valueOf(3), List.of(
                new PharmacyInputs.CountLineInput(LINE, BigDecimal.valueOf(38), false, null)));

        StockAuditViews.CountSheet sheet = service.saveCounts(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(in));

        verify(audits).saveCount(TENANT, LINE, 38, false, null, ME);
        verify(audits).bump(TENANT, AUDIT, 3L, ME);
        assertThat(sheet.items()).extracting(StockAuditViews.CountSheetLine::counted).containsExactly(38);
    }

    // ---- submit ----

    @Test
    void submitWithAnUncountedLineIsA400ThatSaysHowManyAreLeft() {
        current = audit("COUNTING", null, 4);
        lines = List.of(line(null, null, null));

        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(post(4)), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).anyMatch(f -> f.message().contains("last item")));
        verify(audits, never()).moveTo(any(), any(), any(), any(), any());
    }

    @Test
    void submitFixesTheBookQuantityOfTheCountedLinesToTheAvailableBalanceThenWaitsForReview() {
        current = audit("COUNTING", null, 4);
        lines = List.of(line(38, null, null));
        UUID ledger = UUID.randomUUID();
        when(audits.lastLedger(TENANT, STORE_ID, BATCH)).thenReturn(Optional.of(new StockAuditRepository.LedgerMark(ledger, NOW)));

        service.submit(TENANT, USER, STORE_ID.toString(), AUDIT.toString(), PharmacyInputs.Body.of(post(4)), null);

        InOrder order = Mockito.inOrder(stores, audits);
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(audits).findForUpdate(TENANT, AUDIT);
        order.verify(audits).fixBook(TENANT, LINE, 40, ledger, NOW, ME);
        order.verify(audits).moveTo(TENANT, AUDIT, "WAITING_REVIEW", ME, ME);
        verify(auditLog).record(eq("PHARMACY_STOCK_AUDIT_SUBMITTED"), any(), eq(AUDIT), any(), any());
    }

    @Test
    void submitOfAScheduleXCountNeedsAPharmacistAndASecondSigner() {
        schedule = "X";
        current = audit("COUNTING", null, 4);
        lines = List.of(line(38, null, null));

        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(post(4)), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).anyMatch(f -> f.message().contains("second signature")));

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Store User", "STORE_STAFF", List.of());
        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(post(4)), SIGNER)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getMessage()).contains("Only a pharmacist"));
    }

    // ---- approve and post ----

    @Test
    void theSubmitterOfTheCountCannotApproveIt() {
        current = audit("WAITING_REVIEW", ME, 7);

        assertThatThrownBy(() -> approve(null)).isInstanceOf(ForbiddenException.class);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aDifferenceWithoutAReasonIsA400AndNothingIsPosted() {
        lines = List.of(line(38, 40, null));

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).anyMatch(f -> f.message().contains("reason")));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aLineWhoseStockMovedAfterTheCountIs409StockMovedAndNothingIsPosted() {
        when(audits.movedSince(TENANT, STORE_ID, BATCH, NOW, LINE)).thenReturn(true);

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("stock-moved");
            assertThat(e.getMessage()).isEqualTo("Stock moved since the count. Recount.");
        });
        verify(posting, never()).post(any(), any());
    }

    @Test
    void atLevelZeroEachDifferenceIsPostedAsOneAuditAdjustmentWithTheSubmitterAsActorAndTheReviewerAsApprover() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        StockAuditViews.StockAudit view = approve(null);

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        ArgumentCaptor<List<StockPosting.Line>> posted = ArgumentCaptor.forClass(List.class);
        verify(posting).post(actor.capture(), posted.capture());
        assertThat(actor.getValue().membershipId()).as("the person who handled the stock").isEqualTo(COUNTER);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(ME);
        StockPosting.Line moved = posted.getValue().get(0);
        assertThat(moved.txnType()).isEqualTo("STOCK_AUDIT_ADJUSTMENT");
        assertThat(moved.delta()).as("counted 38 against a book of 40").isEqualTo(-2);
        assertThat(moved.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(moved.reasonKey()).isEqualTo(REASON);
        assertThat(moved.doc().no()).isEqualTo("AUD-0093");
        verify(audits).markPosted(TENANT, LINE, ME);
        verify(audits).moveTo(TENANT, AUDIT, "CLOSED", null, ME);
        verify(auditLog).record(eq("PHARMACY_STOCK_AUDIT_POSTED"), any(), eq(AUDIT), any(), any());
        assertThat(view).isNotNull();
    }

    @Test
    void theRulesSubjectIsTheAuditWithTheSumOfTheAbsoluteDifferencesTimesTheRate() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        approve(null);

        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.STOCK_ADJUSTMENT);
        assertThat(subject.getValue().subjectType()).isEqualTo("STOCK_AUDIT");
        assertThat(subject.getValue().amountPaise()).as("2 x 4.50 rupees, no GST").isEqualTo(900L);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(STORE_ID);
    }

    @Test
    void whenTheReviewerIsTheFirstLevelApproverTheirApprovalIsRecordedInTheSameCallAndTheAuditPosts() {
        UUID requestId = UUID.randomUUID();
        ApprovalEngine.Plan plan = new ApprovalEngine.Plan(null, List.of(), 1, null, false);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        when(engine.submit(eq(TENANT), eq(UNIT), eq(COUNTER), any(), eq(plan))).thenReturn(requestId);
        ApprovalRepository.Request request = new ApprovalRepository.Request(requestId, UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "PENDING", 0);
        when(approvals.find(TENANT, requestId)).thenReturn(Optional.of(request));
        when(approvals.levels(TENANT, requestId)).thenReturn(List.of(new ApprovalRepository.Level(1, ApproverKind.STORE_APPROVER,
                ConditionKind.ALWAYS, true, "Audit variances need HOD approval")));
        when(approvals.decisions(TENANT, requestId)).thenReturn(List.of());
        when(engine.approvers(eq(TENANT), eq(request), any(), any())).thenReturn(new ApproverResolver.Result(Map.of(ME, "HOD"),
                false, false));

        approve(null);

        InOrder order = Mockito.inOrder(engine, approvals, posting, audits);
        order.verify(engine).submit(eq(TENANT), eq(UNIT), eq(COUNTER), any(), eq(plan));
        order.verify(audits).attachRequest(TENANT, AUDIT, requestId, null, ME);
        order.verify(approvals).insertDecision(TENANT, UNIT, requestId, 1, "APPROVED", ME, COUNTER, "HOD", null, null);
        order.verify(approvals).finish(TENANT, requestId, 0L, "APPROVED", ME);
        order.verify(posting).post(any(StockPosting.Actor.class), any());
        order.verify(audits).moveTo(TENANT, AUDIT, "CLOSED", null, ME);
    }

    @Test
    void aRetryOfAPostThatWasSentBackToRecountIsNotAnsweredAsIfItWorked() {
        // the claim of the key stays committed with the recount (the app role cannot delete a key), so the replay must say 409 changed
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, StockAuditService.OP_POST,
                AUDIT)));
        current = audit("RECOUNT", COUNTER, 8);

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aRetryOfAPostThatClosedOrWentToTheApproverIsAReplayOfTheStoredOutcome() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, StockAuditService.OP_POST,
                AUDIT)));
        current = audit("CLOSED", COUNTER, 8);

        assertThat(approve(null)).isNotNull();
        current = audit("WAITING_APPROVAL", COUNTER, 8);
        assertThat(approve(null)).isNotNull();
        verify(posting, never()).post(any(), any());
    }

    @Test
    void whenTheReviewerIsNotAnApproverTheAuditWaitsForApprovalAndPostsNothing() {
        UUID requestId = UUID.randomUUID();
        ApprovalEngine.Plan plan = new ApprovalEngine.Plan(null, List.of(), 1, null, false);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        when(engine.submit(eq(TENANT), eq(UNIT), eq(COUNTER), any(), eq(plan))).thenReturn(requestId);
        ApprovalRepository.Request request = new ApprovalRepository.Request(requestId, UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "PENDING", 0);
        when(approvals.find(TENANT, requestId)).thenReturn(Optional.of(request));
        when(approvals.levels(TENANT, requestId)).thenReturn(List.of());
        when(approvals.decisions(TENANT, requestId)).thenReturn(List.of());
        when(engine.approvers(eq(TENANT), eq(request), any(), any())).thenReturn(new ApproverResolver.Result(Map.of(), false, true));

        approve(null);

        verify(approvals, never()).insertDecision(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(posting, never()).post(any(), any());
        verify(audits).moveTo(TENANT, AUDIT, "WAITING_APPROVAL", null, ME);
    }

    @Test
    void aDecreaseAboveWhatTheBatchHoldsNowGoesBackToRecountAndIs409InsufficientStockThatIsKept() {
        when(stock.lockBalances(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> List.of(balance(1)));

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(PharmacyProblemException.StockShort.class, e -> {
            assertThat(e.slug()).isEqualTo("insufficient-stock");
            assertThat(e.extensions()).containsEntry("available", 1);
        });

        InOrder order = Mockito.inOrder(audits);
        order.verify(audits).moveTo(TENANT, AUDIT, "RECOUNT", null, ME);
        order.verify(audits).requestRecount(eq(TENANT), eq(Set.of(LINE)), eq(ME));
        verify(posting, never()).post(any(), any());
        verify(engine, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void aScheduleXDifferenceNeedsASecondSignerWhoIsNotTheSubmitterOrACounter() {
        schedule = "X";
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).anyMatch(f -> f.message().contains("second signature")));

        org.mockito.Mockito.doThrow(ScheduleXSignature.invalid()).when(signature).recheck(TENANT, COUNTER, SIGNER);
        assertThatThrownBy(() -> approve(SIGNER)).isInstanceOf(ForbiddenException.class);

        org.mockito.Mockito.doNothing().when(signature).recheck(TENANT, COUNTER, SIGNER);
        lines = List.of(new Line(LINE, AUDIT, PRODUCT, BATCH, 38, false, null, SIGNER, NOW, false, 40, NOW, REASON, null, null));
        assertThatThrownBy(() -> approve(SIGNER)).as("the signer counted a line").isInstanceOf(ForbiddenException.class);
        verify(posting, never()).post(any(), any());

        lines = List.of(line(38, 40, REASON));
        approve(SIGNER);
        verify(audits).setSigner(TENANT, AUDIT, SIGNER, ME);
        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(posting).post(actor.capture(), any());
        assertThat(actor.getValue().secondSignerMembershipId()).isEqualTo(SIGNER);
    }

    // ---- recount, reasons, handler ----

    @Test
    void recountPutsTheAuditInRecountBeforeItClearsTheLinesAndWithdrawsAWaitingRequest() {
        UUID requestId = UUID.randomUUID();
        current = new Audit(AUDIT, UNIT, 93, "AUD-0093", STORE_ID, "ALL_AVAILABLE", "WAITING_APPROVAL", COUNTER, NOW, requestId,
                null, NOW, ME, 7);
        ApprovalRepository.Request request = new ApprovalRepository.Request(requestId, UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "PENDING", 4);
        when(approvals.find(TENANT, requestId)).thenReturn(Optional.of(request));
        when(approvals.findForUpdate(TENANT, requestId)).thenReturn(Optional.of(request));
        when(approvals.levels(TENANT, requestId)).thenReturn(List.of());
        when(approvals.decisions(TENANT, requestId)).thenReturn(List.of());
        when(engine.approvers(eq(TENANT), eq(request), any(), any())).thenReturn(new ApproverResolver.Result(Map.of(ME, "HOD"),
                false, false));
        when(audits.linesForUpdate(eq(TENANT), eq(AUDIT), any())).thenReturn(List.of(line(38, 40, REASON)));

        service.recount(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.RecountInput(BigDecimal.valueOf(7), List.of(LINE))));

        InOrder order = Mockito.inOrder(approvals, audits);
        order.verify(approvals).findForUpdate(TENANT, requestId);
        order.verify(audits).findForUpdate(TENANT, AUDIT);
        order.verify(approvals).finish(TENANT, requestId, 4L, "WITHDRAWN", ME);
        order.verify(audits).moveTo(TENANT, AUDIT, "RECOUNT", null, ME);
        order.verify(audits).requestRecount(eq(TENANT), eq(Set.of(LINE)), eq(ME));
        verify(auditLog).record(eq("PHARMACY_STOCK_AUDIT_RECOUNT"), any(), eq(AUDIT), any(), any());
    }

    @Test
    void varianceReasonsAreAcceptedOnlyWhileTheCountWaitsForReview() {
        current = audit("WAITING_APPROVAL", COUNTER, 7);
        PharmacyInputs.VarianceReasonsInput in = new PharmacyInputs.VarianceReasonsInput(BigDecimal.valueOf(7), List.of(
                new PharmacyInputs.VarianceReasonLineInput(LINE, REASON, null)));

        assertThatThrownBy(() -> service.saveVarianceReasons(TENANT, USER, STORE_ID.toString(), AUDIT.toString(),
                PharmacyInputs.Body.of(in))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verify(audits, never()).saveVariance(any(), any(), any(), any(), any());
    }

    @Test
    void theHandlerClosesTheAuditAtTheFinalApprovalWithTheApproverOnTheLedgerLines() {
        UUID approver = UUID.randomUUID();
        current = audit("WAITING_APPROVAL", COUNTER, 8);
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "APPROVED", 1);
        PharmacyCaller owner = new PharmacyCaller(TENANT, UUID.randomUUID(), approver, "Test Owner", "OWNER", List.of());

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, owner, request, "OWNER", null));

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(posting).post(actor.capture(), any());
        assertThat(actor.getValue().membershipId()).isEqualTo(COUNTER);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(approver);
        verify(audits).setStatusFromApproval(TENANT, AUDIT, "CLOSED", approver);
    }

    @Test
    void theHandlerReturnsARejectedAuditToCountingAndPostsNothing() {
        UUID approver = UUID.randomUUID();
        current = audit("WAITING_APPROVAL", COUNTER, 8);
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "REJECTED", 1);
        PharmacyCaller owner = new PharmacyCaller(TENANT, UUID.randomUUID(), approver, "Test Owner", "OWNER", List.of());

        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, owner, request, "OWNER", null));

        verify(audits).setStatusFromApproval(TENANT, AUDIT, "COUNTING", approver);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theHandlerServesTheStockAuditSubjectAndReadersNeedReviewCount() {
        assertThat(service.subjectType()).isEqualTo("STOCK_AUDIT");
        assertThat(service.canRead(caller, new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.STOCK_ADJUSTMENT,
                "STOCK_AUDIT", AUDIT, "AUD-0093", STORE_ID, COUNTER, NOW, null, 900, false, false, false, null, null, 1, NOW,
                "PENDING", 0))).as("no mapping, no reviewCount").isFalse();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
