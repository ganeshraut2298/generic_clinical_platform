package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-79-15 over mocks: a transfer is requested and submitted in one step (level 0 gives APPROVED, a level gives WAITING_APPROVAL
 * with a request), never goes to the Central Store, never moves stock, a quantity above the sending store's available stock is
 * refused with the available figure, a cancel withdraws the request, and the approval handler finishes the document.
 */
class TransferServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String REASON = "transfer.urgent-shortage";
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final DispatchRepository dispatches = mock(DispatchRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TransferService service = new TransferService(gate, guard, stores, products, stock, posting, transfers,
            dispatches, engine, approvals, series, numbers, clients, events, reasons,
            new DocumentViews(events, stores, team, engine), audit, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), new BlindCount(mock(StockAuditRepository.class)));
    private final PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Store User", "NURSE", List.of());
    private final Store from = new Store(FROM, UNIT, "Test Pharmacy", "PH", StoreType.PHARMACY, CENTRAL, true, false, true, true, 0);
    private final Store to = new Store(TO, UNIT, "Test OT Store", "OT", StoreType.SUB_STORE, CENTRAL, false, true, true, true, 0);
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true,
            true, 0);
    private String status = "APPROVED";

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(guard.require(any(), eq(FROM), eq(StoreCapability.RAISE_INDENT))).thenReturn(from);
        when(guard.require(any(), eq(FROM), eq(StoreCapability.READ_STOCK))).thenReturn(from);
        when(stores.list(TENANT, true)).thenReturn(List.of(central, from, to));
        when(stores.find(eq(TENANT), any())).thenAnswer(i -> List.of(central, from, to).stream()
                .filter(s -> s.id().equals(i.getArgument(1))).findFirst());
        when(stores.findForUpdate(eq(TENANT), any())).thenAnswer(i -> List.of(central, from, to).stream()
                .filter(s -> s.id().equals(i.getArgument(1))).findFirst());
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(numbers.next(TENANT, DocType.TRANSFER)).thenReturn(438L);
        when(reasons.isActive(TENANT, ReasonList.TRANSFER, REASON)).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, "approval-reject.changed-mind")).thenReturn(true);
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1))));
        when(stock.availableOfProduct(TENANT, FROM, PRODUCT)).thenReturn(List.of(bal(10)));
        when(stock.findBatch(eq(TENANT), any())).thenReturn(Optional.of(new StockRepository.BatchRow(BATCH, PRODUCT, "TESTB001",
                LocalDate.of(2027, 3, 31), new BigDecimal("10.00"), new BigDecimal("6.0000"), null, NOW)));
        when(posting.pick(any(), anyInt())).thenAnswer(i -> {
            int qty = i.getArgument(1);
            return new Fefo.Result(List.of(new Fefo.Pick(BATCH, Math.min(qty, 10))), 10, Math.max(0, qty - 10));
        });
        when(transfers.findById(eq(TENANT), any())).thenAnswer(i -> Optional.of(row(status, 0)));
        when(transfers.findForUpdate(eq(TENANT), any())).thenAnswer(i -> Optional.of(row(status, 0)));
        when(transfers.lines(eq(TENANT), any())).thenReturn(List.of(new TransferRepository.Line(UUID.randomUUID(), TRANSFER,
                PRODUCT, 6, 3600)));
    }

    private static ProductRepository.Full product(UUID id) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test IV Fluid (fake)", null, false,
                "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), "NONE", false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static StockRepository.Bal bal(int qty) {
        return new StockRepository.Bal(UUID.randomUUID(), FROM, PRODUCT, BATCH, StockState.AVAILABLE, qty, "TESTB001",
                LocalDate.of(2027, 3, 31), NOW, new BigDecimal("10.00"), new BigDecimal("6.0000"));
    }

    private TransferRepository.Row row(String rowStatus, long version) {
        return new TransferRepository.Row(TRANSFER, UNIT, 438, "TRF-0438", FROM, TO, REASON, null, rowStatus,
                "WAITING_APPROVAL".equals(rowStatus) ? REQUEST : null, 3600, NOW, ME, version);
    }

    private PharmacyViews.Outcome<TransferViews.Transfer> create(UUID toStore, int quantity) {
        return service.create(TENANT, USER, FROM.toString(), PharmacyInputs.Body.of(new PharmacyInputs.TransferCreate(KEY, FROM,
                toStore, REASON, null, List.of(new PharmacyInputs.TransferLineInput(PRODUCT, BigDecimal.valueOf(quantity))))));
    }

    @Test
    void aTransferWithNoApprovalLevelIsApprovedAtOnceAndMovesNoStock() {
        PharmacyViews.Outcome<TransferViews.Transfer> outcome = create(TO, 6);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.view().number()).isEqualTo("TRF-0438");
        assertThat(outcome.view().step()).isEqualTo("APPROVED");
        ArgumentCaptor<TransferRepository.NewRow> header = ArgumentCaptor.forClass(TransferRepository.NewRow.class);
        verify(transfers).insert(eq(TENANT), header.capture());
        assertThat(header.getValue().status()).isEqualTo("APPROVED");
        assertThat(header.getValue().totalValuePaise()).isEqualTo(3600);
        verify(transfers).insertLine(eq(TENANT), any());
        verify(posting, never()).post(any(), any());
        verify(engine, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void anApprovalLevelMakesItWaitAndAsksTheStockTransferRuleAtTheSendingStore() {
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(REQUEST);
        status = "WAITING_APPROVAL";

        create(TO, 6);

        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.STOCK_TRANSFER);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(FROM);
        assertThat(subject.getValue().amountPaise()).isEqualTo(3600);
        verify(transfers).attachRequest(eq(TENANT), any(), eq(REQUEST), eq(ME));
    }

    @Test
    void aTransferNeverGoesToTheCentralStoreOrToItself() {
        assertThatThrownBy(() -> create(CENTRAL, 6)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains("toStoreId").contains(TransferService.NOT_TO_CENTRAL));
        assertThatThrownBy(() -> create(FROM, 6)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains(TransferService.DIFFERENT));
        verify(transfers, never()).insert(any(), any());
    }

    @Test
    void aQuantityAboveTheSendingStoresAvailableStockIsRefusedWithTheAvailableFigure() {
        assertThatThrownBy(() -> create(TO, 11)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains("lines[0].quantity").contains("Enter a whole number from 1 to 10"));
        verify(transfers, never()).insert(any(), any());
    }

    @Test
    void theSameKeyAnswersTheFirstTransferAsAReplay() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                TransferService.OP_CREATE, TRANSFER)));

        PharmacyViews.Outcome<TransferViews.Transfer> outcome = create(TO, 6);

        assertThat(outcome.created()).isFalse();
        verify(transfers, never()).insert(any(), any());
        verify(numbers, never()).next(any(), any());
    }

    @Test
    void theRequesterCancelsAWaitingTransferAndTheRequestIsWithdrawn() {
        status = "WAITING_APPROVAL";
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.id()).thenReturn(REQUEST);
        when(request.pending()).thenReturn(true);
        when(request.version()).thenReturn(3L);
        when(approvals.findForUpdate(TENANT, REQUEST)).thenReturn(Optional.of(request));
        when(transfers.setStatus(eq(TENANT), eq(TRANSFER), anyLong(), eq("CANCELLED"), eq(ME))).thenReturn(true);

        service.cancel(TENANT, USER, FROM.toString(), TRANSFER.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(BigDecimal.ZERO, "approval-reject.changed-mind", null)));

        verify(approvals).finish(TENANT, REQUEST, 3L, "WITHDRAWN", ME);
        verify(transfers).setStatus(TENANT, TRANSFER, 0L, "CANCELLED", ME);
    }

    @Test
    void aDispatchedTransferCannotBeCancelled() {
        status = "IN_TRANSIT";

        assertThatThrownBy(() -> service.cancel(TENANT, USER, FROM.toString(), TRANSFER.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(BigDecimal.ZERO, "approval-reject.changed-mind", null))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verify(transfers, never()).setStatus(any(), any(), anyLong(), eq("CANCELLED"), any());
    }

    @Test
    void theApprovalHandlerFinishesTheTransferAsApprovedOrRejected() {
        status = "WAITING_APPROVAL";
        PharmacyCaller approver = new PharmacyCaller(TENANT, USER, UUID.randomUUID(), "Test HOD", "NURSE", List.of());
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(TRANSFER);
        when(transfers.setStatus(eq(TENANT), eq(TRANSFER), anyLong(), any(), any())).thenReturn(true);

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "HOD", null));
        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "HOD", null));

        verify(transfers).setStatus(TENANT, TRANSFER, 0L, "APPROVED", approver.actorId());
        verify(transfers).setStatus(TENANT, TRANSFER, 0L, "REJECTED", approver.actorId());
    }

    @Test
    void theStepBarAndTheNotReceivedHoursFollowTheStatusAndTheClock() {
        assertThat(TransferService.step("WAITING_APPROVAL")).isEqualTo("REQUESTED");
        assertThat(TransferService.step("APPROVED")).isEqualTo("APPROVED");
        assertThat(TransferService.step("IN_TRANSIT")).isEqualTo("DISPATCHED");
        assertThat(TransferService.step("RECEIVED")).isEqualTo("RECEIVED");
        assertThat(TransferService.notReceivedHours(NOW.minusSeconds(23 * 3600L), NOW)).isNull();
        assertThat(TransferService.notReceivedHours(NOW.minusSeconds(30 * 3600L), NOW)).isEqualTo(30);
        assertThat(TransferService.notReceivedHours(null, NOW)).isNull();
    }

    @Test
    void theOtherEndsOfATransferAreEveryActiveStoreButThisOneTheCentralStoreFirst() {
        TransferViews.StoreList list = service.transferStores(TENANT, USER, FROM.toString());

        assertThat(list.items()).extracting(StockViews.StoreRef::storeId).containsExactly(CENTRAL, TO);
    }
}
