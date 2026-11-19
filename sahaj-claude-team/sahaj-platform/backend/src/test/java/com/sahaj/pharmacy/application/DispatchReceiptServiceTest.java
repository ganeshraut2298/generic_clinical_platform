package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.DispatchRepository.Line;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
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
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-12: receiving a dispatch over mocks. Accepted units to Available, units sent back to Pending return with a return item,
 * short units left In transit with a shortage; accepted plus rejected equals dispatched; an expired batch cannot be accepted;
 * each dispatch line is received once; a Schedule X line is skipped by anyone who is not a pharmacist and the dispatch stays
 * PARTLY_RECEIVED; the indent is RECEIVED only when everything approved is dispatched and received; the order of the locks and
 * writes; and that the actor stored is the membership id.
 */
class DispatchReceiptServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID INDENT = UUID.randomUUID();
    private static final UUID INDENT_LINE = UUID.randomUUID();
    private static final UUID INDENT_LINE_X = UUID.randomUUID();
    private static final UUID DISPATCH = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID LINE_X = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PRODUCT_X = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID BATCH_X = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String DAMAGED = "receipt-reject.damaged-in-transit";
    private static final String SHORT = "receipt-reject.short";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final IndentRepository indents = mock(IndentRepository.class);
    private final DispatchRepository dispatches = mock(DispatchRepository.class);
    private final ReturnItemRepository returns = mock(ReturnItemRepository.class);
    private final ShortageRepository shortages = mock(ShortageRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final DispatchAssembler assembler = mock(DispatchAssembler.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final DispatchReceiptService service = new DispatchReceiptService(gate, guard, stores, indents, dispatches, returns,
            shortages, stock, posting, signature, clients, events, reasons, assembler, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of());
    private final Store ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, CENTRAL, false, true, true, true, 0);
    private final DispatchViews.Dispatch view = new DispatchViews.Dispatch(DISPATCH, "IND-0012 / 1", "INDENT", INDENT, "IND-0012",
            null, null, "RECEIVED", null, NOW, "Acting as", List.of(), null, null, null, 0);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RECEIVE))).thenReturn(ward);
        when(stores.findForUpdate(TENANT, WARD)).thenReturn(Optional.of(ward));
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("IN_TRANSIT", 1)));
        when(dispatches.findForUpdate(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("IN_TRANSIT", 1)));
        when(dispatches.lines(TENANT, DISPATCH)).thenReturn(List.of(line(LINE, INDENT_LINE, PRODUCT, BATCH, 18, false)));
        when(dispatches.unreceivedLines(TENANT, DISPATCH)).thenReturn(List.of(line(LINE, INDENT_LINE, PRODUCT, BATCH, 18, false)));
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(indent("DISPATCHED")));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(indentLine(INDENT_LINE, 18, 18, 0, 0)));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.RECEIPT_REJECT, DAMAGED)).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.RECEIPT_REJECT, SHORT)).thenReturn(true);
        when(stock.findBatch(eq(TENANT), any())).thenAnswer(i -> Optional.of(batch(i.getArgument(1), LocalDate.of(2027, 3, 31))));
        when(assembler.view(any(), any(), any())).thenReturn(view);
        when(dispatches.markReceived(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(true);
    }

    private static DispatchRepository.Row row(String status, long version) {
        return new DispatchRepository.Row(DISPATCH, UNIT, "INDENT", INDENT, null, 1, "IND-0012 / 1", CENTRAL, WARD, status, UUID.randomUUID(),
                NOW, null, null, null, null, null, null, version);
    }

    private static IndentRepository.Row indent(String status) {
        return new IndentRepository.Row(INDENT, UNIT, WARD, CENTRAL, 12L, "IND-0012", "URGENT", status, null, NOW, null, null, NOW,
                UUID.randomUUID(), 5);
    }

    private static Line line(UUID id, UUID indentLine, UUID product, UUID batch, int qty, boolean x) {
        return new Line(id, DISPATCH, indentLine, product, batch, qty, false, null, null, 0, x);
    }

    private static IndentRepository.Line indentLine(UUID id, int approved, int dispatched, int received, int rejectedAndShort) {
        return new IndentRepository.Line(id, PRODUCT, 1, approved, approved, null, dispatched, received, rejectedAndShort, 0, 0,
                null, null, null, null);
    }

    private static StockRepository.BatchRow batch(UUID id, LocalDate expiry) {
        return new StockRepository.BatchRow(id, PRODUCT, "TESTB001", expiry, new BigDecimal("10.00"), new BigDecimal("6.0000"), null,
                NOW);
    }

    private static PharmacyInputs.ReceiptLineInput input(UUID line, String accepted, String rejected, String reason,
                                                         boolean skipped) {
        return new PharmacyInputs.ReceiptLineInput(line, new BigDecimal(accepted), new BigDecimal(rejected), reason, null, skipped);
    }

    private static PharmacyInputs.DispatchReceipt body(long version, PharmacyInputs.ReceiptLineInput... lines) {
        return new PharmacyInputs.DispatchReceipt(KEY, BigDecimal.valueOf(version), List.of(lines), null);
    }

    private DispatchViews.Dispatch run(PharmacyInputs.DispatchReceipt in, UUID signer) {
        return service.receive(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in), signer);
    }

    @SuppressWarnings("unchecked")
    private List<StockPosting.Line> posted() {
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        return moves.getValue();
    }

    private void withScheduleXLine() {
        List<Line> both = List.of(line(LINE, INDENT_LINE, PRODUCT, BATCH, 18, false),
                line(LINE_X, INDENT_LINE_X, PRODUCT_X, BATCH_X, 5, true));
        when(dispatches.lines(TENANT, DISPATCH)).thenReturn(both);
        when(dispatches.unreceivedLines(TENANT, DISPATCH)).thenReturn(both);
    }

    // ---- accepted, rejected, short ----

    @Test
    void acceptedUnitsGoToAvailableRejectedUnitsToPendingReturnWithAReturnItemAndTheDispatchIsReceived() {
        PharmacyInputs.DispatchReceipt in = body(1, input(LINE, "16", "2", DAMAGED, false));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(indentLine(INDENT_LINE, 18, 18, 16, 2)));
        when(dispatches.receipts(TENANT, DISPATCH)).thenReturn(List.of(new DispatchRepository.Receipt(LINE, 16, 2, DAMAGED, null, ME,
                NOW, null)));

        run(in, null);

        List<StockPosting.Line> moves = posted();
        assertThat(moves).extracting(StockPosting.Line::txnType).containsExactly("INDENT_RECEIPT_ACCEPTED", "INDENT_RECEIPT_ACCEPTED",
                "INDENT_RECEIPT_REJECTED", "INDENT_RECEIPT_REJECTED");
        assertThat(moves).extracting(StockPosting.Line::state).containsExactly(StockState.IN_TRANSIT, StockState.AVAILABLE,
                StockState.IN_TRANSIT, StockState.PENDING_RETURN);
        assertThat(moves).extracting(StockPosting.Line::delta).containsExactly(-16, 16, -2, 2);
        assertThat(moves).extracting(StockPosting.Line::storeId).containsOnly(WARD);
        assertThat(moves.get(2).reasonKey()).isEqualTo(DAMAGED);
        ArgumentCaptor<ReturnItemRepository.NewItem> item = ArgumentCaptor.forClass(ReturnItemRepository.NewItem.class);
        verify(returns).insert(eq(TENANT), item.capture());
        assertThat(item.getValue().holdingStoreId()).isEqualTo(WARD);
        assertThat(item.getValue().acceptingStoreId()).isEqualTo(CENTRAL);
        assertThat(item.getValue().qty()).isEqualTo(2);
        assertThat(item.getValue().docNo()).isEqualTo("IND-0012 / 1");
        assertThat(item.getValue().itemType()).isEqualTo("INDENT_REJECTION");
        assertThat(item.getValue().defaultPutBackInto()).isEqualTo("QUARANTINE");
        verify(shortages, never()).insert(any(), any());
        verify(indents).applyReceipt(TENANT, INDENT_LINE, 16, 2, 0, ME);
        verify(dispatches).markReceived(eq(TENANT), eq(DISPATCH), eq(1L), eq("RECEIVED"), eq(ME), eq(null),
                eq("The 2 rejected units do not enter Test Ward 3B stock. They wait for Central Store to accept them back."),
                eq(null));
        verify(indents).setStatusLocked(TENANT, INDENT, "DISPATCHED", "RECEIVED", ME);
        UserIdNeverWritten.assertNone(USER, indents, dispatches, returns, shortages, posting, events, audit);
    }

    @Test
    void shortUnitsStayInTransitWithAnOpenShortageAndNeverReachAvailableOrPendingReturn() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(indentLine(INDENT_LINE, 18, 18, 10, 0)));

        run(body(1, input(LINE, "10", "8", SHORT, false)), null);

        List<StockPosting.Line> moves = posted();
        assertThat(moves).extracting(StockPosting.Line::state).containsExactly(StockState.IN_TRANSIT, StockState.AVAILABLE);
        assertThat(moves).extracting(StockPosting.Line::delta).containsExactly(-10, 10);
        ArgumentCaptor<ShortageRepository.NewShortage> shortage = ArgumentCaptor.forClass(ShortageRepository.NewShortage.class);
        verify(shortages).insert(eq(TENANT), shortage.capture());
        assertThat(shortage.getValue().qty()).isEqualTo(8);
        assertThat(shortage.getValue().receivingStoreId()).isEqualTo(WARD);
        assertThat(shortage.getValue().settlingStoreId()).isEqualTo(CENTRAL);
        verify(returns, never()).insert(any(), any());
        verify(indents).applyReceipt(TENANT, INDENT_LINE, 10, 0, 8, ME);
    }

    @Test
    void theLocksAndWritesRunInOrderTheDispatchThenTheIndentThenStockThenReceiptLinesBeforeTheDispatchStatus() {
        run(body(1, input(LINE, "18", "0", null, false)), null);

        InOrder order = Mockito.inOrder(clients, stores, dispatches, indents, posting);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyReceiveDispatch"), eq(DISPATCH));
        order.verify(stores).findForUpdate(TENANT, WARD);
        order.verify(dispatches).findForUpdate(TENANT, DISPATCH);
        order.verify(indents).findForUpdateById(TENANT, INDENT);
        order.verify(indents).linesForUpdate(TENANT, INDENT);
        order.verify(posting).post(any(), any());
        order.verify(dispatches).insertReceipt(eq(TENANT), any());
        order.verify(indents).applyReceipt(eq(TENANT), eq(INDENT_LINE), anyInt(), anyInt(), anyInt(), eq(ME));
        order.verify(dispatches).markReceived(eq(TENANT), eq(DISPATCH), eq(1L), any(), eq(ME), any(), any(), any());
        order.verify(indents).setStatusLocked(eq(TENANT), eq(INDENT), any(), any(), eq(ME));
    }

    @Test
    void anIndentWithABackOrderOpenStaysPartlyDispatchedAfterTheReceipt() {
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(indent("PARTLY_DISPATCHED")));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(new IndentRepository.Line(INDENT_LINE, PRODUCT, 1, 30, 30, null, 18,
                18, 0, 0, 0, "BACK_ORDER", null, null, null)));

        run(body(1, input(LINE, "18", "0", null, false)), null);

        verify(indents).setStatusLocked(TENANT, INDENT, "PARTLY_DISPATCHED", "PARTLY_DISPATCHED", ME);
    }

    // ---- checks ----

    @Test
    void everyLineErrorIsReportedTogetherAndNothingIsWritten() {
        assertThatThrownBy(() -> run(body(1, input(LINE, "10", "3", DAMAGED, false)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].rejected:Accepted + Rejected must equal Dispatched"));
        assertThatThrownBy(() -> run(body(1, input(LINE, "16", "2", null, false)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].rejectReasonKey:Choose a reason for the rejected quantity"));
        assertThatThrownBy(() -> run(body(1, input(LINE, "19", "0", null, false)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].accepted:Enter a whole number from 0 to 18"));
        assertThatThrownBy(() -> run(body(1, input(UUID.randomUUID(), "18", "0", null, false)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).contains(
                                "lines[0].dispatchLineId:Choose a line of this dispatch", "lines:" + DispatchReceiptService.LIST_EVERY));
        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insertReceipt(any(), any());
    }

    @Test
    void anExpiredBatchCannotBeAcceptedButCanBeRejectedAndGoesToQuarantineByDefault() {
        when(stock.findBatch(eq(TENANT), any())).thenAnswer(i -> Optional.of(batch(i.getArgument(1), LocalDate.of(2026, 10, 31))));

        assertThatThrownBy(() -> run(body(1, input(LINE, "18", "0", null, false)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].accepted:" + DispatchReceiptService.EXPIRED_MESSAGE));

        when(reasons.isActive(TENANT, ReasonList.RECEIPT_REJECT, "receipt-reject.wrong-item")).thenReturn(true);

        run(body(1, input(LINE, "0", "18", "receipt-reject.wrong-item", false)), null);

        ArgumentCaptor<ReturnItemRepository.NewItem> item = ArgumentCaptor.forClass(ReturnItemRepository.NewItem.class);
        verify(returns).insert(eq(TENANT), item.capture());
        assertThat(item.getValue().defaultPutBackInto()).isEqualTo("QUARANTINE");
    }

    @Test
    void anOldDispatchVersionOrAReceivedDispatchIs409ChangedAndAReceivedLineSentAgainIs409DocState() {
        assertThatThrownBy(() -> run(body(0, input(LINE, "18", "0", null, false)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("RECEIVED", 2)));
        assertThatThrownBy(() -> run(body(2, input(LINE, "18", "0", null, false)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        // the line was received between the first read and the lock
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("PARTLY_RECEIVED", 2)));
        when(dispatches.findForUpdate(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("PARTLY_RECEIVED", 2)));
        withScheduleXLine();
        when(dispatches.unreceivedLines(TENANT, DISPATCH)).thenReturn(List.of(line(LINE_X, INDENT_LINE_X, PRODUCT_X, BATCH_X, 5,
                true)));
        assertThatThrownBy(() -> run(body(2, input(LINE, "18", "0", null, false), input(LINE_X, "0", "0", null, true)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aDispatchOfAnotherStoreIsNotFoundAndASkippedLineMustBeScheduleX() {
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(new DispatchRepository.Row(DISPATCH, UNIT, "INDENT",
                INDENT, null, 1, "IND-0012 / 1", CENTRAL, UUID.randomUUID(), "IN_TRANSIT", ME, NOW, null, null, null, null, null,
                null, 1)));
        assertThatThrownBy(() -> run(body(1, input(LINE, "18", "0", null, false)), null)).isInstanceOf(NotFoundException.class);

        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("IN_TRANSIT", 1)));
        assertThatThrownBy(() -> run(body(1, input(LINE, "0", "0", null, true)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).contains("lines[0].skipped"));
    }

    // ---- replay ----

    @Test
    void theSameKeyAgainAnswersTheDispatchAndWritesNothing() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyReceiveDispatch", DISPATCH)));

        assertThat(run(body(1, input(LINE, "18", "0", null, false)), null)).isSameAs(view);

        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insertReceipt(any(), any());
    }

    @Test
    void aKeyUsedForAnotherDispatchOrByAnotherPersonIs409ClientKeyReused() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyReceiveDispatch", UUID.randomUUID())));
        assertThatThrownBy(() -> run(body(1, input(LINE, "18", "0", null, false)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty());
        when(clients.heldByAnyone(TENANT, KEY)).thenReturn(true);
        assertThatThrownBy(() -> run(body(1, input(LINE, "18", "0", null, false)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    // ---- Schedule X ----

    @Test
    void aCallerWhoIsNotAPharmacistSkipsTheScheduleXLineTheDispatchStaysPartlyReceivedAndTheIndentKeepsItsStatus() {
        withScheduleXLine();
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(indentLine(INDENT_LINE, 18, 18, 18, 0),
                indentLine(INDENT_LINE_X, 5, 5, 0, 0)));
        when(dispatches.scheduleXWaiting(TENANT, INDENT)).thenReturn(1);
        PharmacyInputs.DispatchReceipt in = body(1, input(LINE, "18", "0", null, false), input(LINE_X, "0", "0", null, true));

        assertThat(service.precheck(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in))).isNull();
        run(in, null);

        assertThat(posted()).extracting(StockPosting.Line::productId).containsOnly(PRODUCT);
        verify(dispatches, times(1)).insertReceipt(eq(TENANT), any());
        verify(dispatches).markReceived(eq(TENANT), eq(DISPATCH), eq(1L), eq("PARTLY_RECEIVED"), eq(ME), eq(null), eq(null), eq(null));
        verify(indents).setStatusLocked(TENANT, INDENT, "DISPATCHED", "DISPATCHED", ME);
    }

    @Test
    void aNonPharmacistWhoSendsAScheduleXLineAsReceivedIs403BeforeAnyPinIsChecked() {
        withScheduleXLine();
        PharmacyInputs.DispatchReceipt in = body(1, input(LINE, "18", "0", null, false), input(LINE_X, "5", "0", null, false));

        assertThatThrownBy(() -> service.precheck(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> run(in, SIGNER)).isInstanceOf(ForbiddenException.class);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aPharmacistReceivesTheScheduleXLineWithTheSignerOnTheLedgerTheReceiptLineAndTheDispatchAndTheIndentIsReceived() {
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("PARTLY_RECEIVED", 2)));
        when(dispatches.findForUpdate(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("PARTLY_RECEIVED", 2)));
        Line x = line(LINE_X, INDENT_LINE_X, PRODUCT_X, BATCH_X, 5, true);
        when(dispatches.lines(TENANT, DISPATCH)).thenReturn(List.of(line(LINE, INDENT_LINE, PRODUCT, BATCH, 18, false), x));
        when(dispatches.unreceivedLines(TENANT, DISPATCH)).thenReturn(List.of(x));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(indentLine(INDENT_LINE, 18, 18, 18, 0),
                indentLine(INDENT_LINE_X, 5, 5, 5, 0)));
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.DispatchReceipt in = new PharmacyInputs.DispatchReceipt(KEY, BigDecimal.valueOf(2),
                List.of(input(LINE_X, "5", "0", null, false)), typed);

        assertThat(service.precheck(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in))).isEqualTo(typed);
        assertThatThrownBy(() -> run(in, null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                .extracting(f -> f.field() + ":" + f.message()).containsExactly("secondSigner:" + ScheduleXSignature.NEEDED));

        run(in, SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        assertThat(posted()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
        ArgumentCaptor<DispatchRepository.NewReceipt> receipt = ArgumentCaptor.forClass(DispatchRepository.NewReceipt.class);
        verify(dispatches).insertReceipt(eq(TENANT), receipt.capture());
        assertThat(receipt.getValue().secondSignerMembershipId()).isEqualTo(SIGNER);
        verify(dispatches).markReceived(eq(TENANT), eq(DISPATCH), eq(2L), eq("RECEIVED"), eq(ME), eq(SIGNER), any(), any());
        verify(indents).setStatusLocked(TENANT, INDENT, "DISPATCHED", "RECEIVED", ME);
    }

    @Test
    void aReceiptForAReceivedDispatchOrAUsedKeyAsksForNoSigner() {
        withScheduleXLine();
        PharmacyInputs.DispatchReceipt in = body(1, input(LINE, "18", "0", null, false), input(LINE_X, "5", "0", null, false));
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("RECEIVED", 3)));
        assertThat(service.precheck(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in))).isNull();
        when(dispatches.findById(TENANT, DISPATCH)).thenAnswer(i -> Optional.of(row("IN_TRANSIT", 1)));
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "x", DISPATCH)));
        assertThat(service.precheck(TENANT, USER, WARD.toString(), DISPATCH.toString(), PharmacyInputs.Body.of(in))).isNull();
    }
}
