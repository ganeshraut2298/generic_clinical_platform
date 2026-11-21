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

import com.sahaj.pharmacy.application.IndentRepository.Line;
import com.sahaj.pharmacy.application.StockRepository.Bal;
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
 * SAH-79-11: dispatching an indent over mocks. The first-expiry-first-out pick, the two-sided stock lines (the Central Store's
 * Available out, the requesting store's In transit in), the order of the locks and writes, the changes of a line (a smaller
 * quantity with a back-order or a closed balance, a replacement product), Schedule X with the verified second signer, a second
 * dispatcher that gets 409, and that the actor stored is the membership id.
 */
class IndentDispatchServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID OTHER_PRODUCT = UUID.randomUUID();
    private static final UUID INDENT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID BATCH_A = UUID.randomUUID();
    private static final UUID BATCH_B = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String CHANGE = "cs-change.out-of-stock";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final IndentRepository indents = mock(IndentRepository.class);
    private final DispatchRepository dispatches = mock(DispatchRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final DispatchAssembler assembler = mock(DispatchAssembler.class);
    private final IndentService indentService = mock(IndentService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final PharmacyTime time = new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC));
    private final StockPosting realPosting = new StockPosting(stock, time);
    private final IndentDispatchService service = new IndentDispatchService(gate, guard, stores, products, indents, dispatches,
            stock, posting, signature, clients, events, reasons, assembler, indentService, audit, time,
            new BlindCount(mock(StockAuditRepository.class)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Central User", "STORE_STAFF", List.of());
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true,
            true, 0);
    private final DispatchViews.Dispatch dispatchView = new DispatchViews.Dispatch(UUID.randomUUID(), "IND-0012 / 1", "INDENT",
            INDENT, "IND-0012", null, null, "IN_TRANSIT", null, NOW, "Acting as", List.of(), null, null, null, 0);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE))).thenReturn(central);
        when(stores.findForUpdate(TENANT, CENTRAL)).thenReturn(Optional.of(central));
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("APPROVED", 3)));
        when(indents.findForUpdateById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("APPROVED", 3)));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)));
        when(indents.linesForUpdate(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(dispatches.nextSeq(TENANT, INDENT)).thenReturn(1);
        when(dispatches.findById(eq(TENANT), any())).thenAnswer(i -> Optional.of(dispatchRow(i.getArgument(1))));
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "NONE")));
        when(reasons.isActive(TENANT, ReasonList.CS_CHANGE, CHANGE)).thenReturn(true);
        when(stock.availableOfProduct(TENANT, CENTRAL, PRODUCT)).thenReturn(List.of(
                bal(BATCH_B, PRODUCT, 30, LocalDate.of(2027, 9, 30), NOW.minusSeconds(100)),
                bal(BATCH_A, PRODUCT, 8, LocalDate.of(2027, 3, 31), NOW.minusSeconds(200))));
        when(posting.pick(any(), anyInt())).thenAnswer(i -> realPosting.pick(i.getArgument(0), i.getArgument(1)));
        when(assembler.view(any(), any(), any())).thenReturn(dispatchView);
        when(indentService.get(any(), any(), any(), any())).thenReturn(mock(IndentViews.Indent.class));
    }

    private static IndentRepository.Row row(String status, long version) {
        return new IndentRepository.Row(INDENT, UNIT, WARD, CENTRAL, 12L, "IND-0012", "URGENT", status, null, NOW, null, null, NOW,
                UUID.randomUUID(), version);
    }

    private static Line line(int approved, int dispatched, int closed) {
        return new Line(LINE, PRODUCT, 1, 20, approved, null, dispatched, 0, 0, 0, closed, null, null, null, null);
    }

    private static DispatchRepository.Row dispatchRow(UUID id) {
        return new DispatchRepository.Row(id, UNIT, "INDENT", INDENT, null, 1, "IND-0012 / 1", CENTRAL, WARD, "IN_TRANSIT", ME,
                NOW, null, null, null, null, null, null, 0);
    }

    private static ProductRepository.Full product(UUID id, String schedule) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test IV Fluid (fake)", null, false,
                "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static Bal bal(UUID batch, UUID product, int qty, LocalDate expiry, Instant received) {
        return new Bal(UUID.randomUUID(), CENTRAL, product, batch, StockState.AVAILABLE, qty, "TESTB-" + batch.toString()
                .substring(0, 4), expiry, received, new BigDecimal("10.00"), new BigDecimal("6.0000"));
    }

    private static PharmacyInputs.DispatchLineInput input(UUID product, String qty, String action, String reason, String note) {
        return new PharmacyInputs.DispatchLineInput(LINE, product, new BigDecimal(qty), action, reason, note);
    }

    private static PharmacyInputs.IndentDispatch body(long version, PharmacyInputs.DispatchLineInput... lines) {
        return new PharmacyInputs.IndentDispatch(KEY, BigDecimal.valueOf(version), List.of(lines), null);
    }

    private PharmacyViews.Outcome<DispatchViews.Dispatch> run(PharmacyInputs.IndentDispatch in, UUID signer) {
        return service.dispatch(TENANT, USER, CENTRAL.toString(), INDENT.toString(), PharmacyInputs.Body.of(in), signer);
    }

    @SuppressWarnings("unchecked")
    private List<StockPosting.Line> posted() {
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        return moves.getValue();
    }

    // ---- the happy paths ----

    @Test
    void aFullDispatchPicksFirstExpiryFirstAcrossTwoBatchesAndPostsTwoSidedLinesInOneMovementEach() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 20, 0)));

        PharmacyViews.Outcome<DispatchViews.Dispatch> out = run(body(3, input(PRODUCT, "20", null, null, null)), null);

        assertThat(out.created()).isTrue();
        List<StockPosting.Line> moves = posted();
        assertThat(moves).hasSize(4);
        assertThat(moves).extracting(StockPosting.Line::batchId).containsExactly(BATCH_A, BATCH_A, BATCH_B, BATCH_B);
        assertThat(moves).extracting(StockPosting.Line::delta).containsExactly(-8, 8, -12, 12);
        assertThat(moves).extracting(StockPosting.Line::storeId).containsExactly(CENTRAL, WARD, CENTRAL, WARD);
        assertThat(moves).extracting(StockPosting.Line::state).containsExactly(StockState.AVAILABLE, StockState.IN_TRANSIT,
                StockState.AVAILABLE, StockState.IN_TRANSIT);
        assertThat(moves).extracting(StockPosting.Line::txnType).containsOnly("INDENT_DISPATCH");
        assertThat(moves.get(0).movementId()).isEqualTo(moves.get(1).movementId()).isNotEqualTo(moves.get(2).movementId());
        assertThat(moves.get(0).doc().no()).isEqualTo("IND-0012 / 1");
        assertThat(moves).extracting(StockPosting.Line::secondSignerMembershipId).containsOnlyNulls();
        verify(indents).setStatusLocked(TENANT, INDENT, "APPROVED", "DISPATCHED", ME);
        verify(indents).applyDispatch(TENANT, LINE, 20, 0, null, null, null, null, ME);
        UserIdNeverWritten.assertNone(USER, indents, dispatches, posting, events, audit);
    }

    @Test
    void aDispatchOfMoreThan400PicksIsRefusedWithSplitThisDispatchAndNothingMoves() {
        java.util.List<com.sahaj.pharmacy.domain.Fefo.Pick> many = new java.util.ArrayList<>();
        for (int i = 0; i < 401; i++) {
            many.add(new com.sahaj.pharmacy.domain.Fefo.Pick(UUID.randomUUID(), 1));
        }
        Mockito.doReturn(new com.sahaj.pharmacy.domain.Fefo.Result(many, 401, 0)).when(posting).pick(any(), anyInt());
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(401, 0, 0)));
        when(indents.linesForUpdate(TENANT, INDENT)).thenReturn(List.of(line(401, 0, 0)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(body(3, input(PRODUCT, "401", null, null, null)), null))
                .isInstanceOfSatisfying(com.sahaj.platform.api.InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("lines:Split this dispatch into smaller ones."));
        verify(posting, Mockito.never()).post(any(), any());
        verify(dispatches, Mockito.never()).insert(any(), any());
    }

    @Test
    void theClientKeyTheStoreTheHeaderAndTheLinesAreLockedInOrderThenStockThenTheDispatchBeforeItsLines() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 20, 0)));

        run(body(3, input(PRODUCT, "20", null, null, null)), null);

        InOrder order = Mockito.inOrder(clients, stores, indents, posting, dispatches);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyDispatchIndent"), any());
        order.verify(stores).findForUpdate(TENANT, CENTRAL);
        order.verify(indents).findForUpdateById(TENANT, INDENT);
        order.verify(indents).linesForUpdate(TENANT, INDENT);
        order.verify(posting).post(any(), any());
        order.verify(dispatches).insert(eq(TENANT), any());
        order.verify(dispatches, Mockito.times(2)).insertLine(eq(TENANT), any());
        order.verify(indents).applyDispatch(eq(TENANT), eq(LINE), anyInt(), anyInt(), any(), any(), any(), any(), eq(ME));
        order.verify(indents).setStatusLocked(eq(TENANT), eq(INDENT), any(), any(), eq(ME));
    }

    @Test
    void aSmallerQuantityWithABackOrderKeepsTheIndentPartlyDispatchedAndNotesTheBackOrderOnTheFirstLine() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 15, 0)));

        run(body(3, input(PRODUCT, "15", "BACK_ORDER", null, null)), null);

        verify(indents).setStatusLocked(TENANT, INDENT, "APPROVED", "PARTLY_DISPATCHED", ME);
        verify(indents).applyDispatch(TENANT, LINE, 15, 0, "BACK_ORDER", null, null, null, ME);
        ArgumentCaptor<DispatchRepository.NewLine> lines = ArgumentCaptor.forClass(DispatchRepository.NewLine.class);
        verify(dispatches, Mockito.times(2)).insertLine(eq(TENANT), lines.capture());
        assertThat(lines.getAllValues()).extracting(DispatchRepository.NewLine::dispatchedQty).containsExactly(8, 7);
        assertThat(lines.getAllValues()).extracting(DispatchRepository.NewLine::backOrderedQty).containsExactly(5, 0);
    }

    @Test
    void aClosedBalanceNeedsAReasonAndStopsCountingSoTheIndentIsDispatched() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 15, 5)));

        run(body(3, input(PRODUCT, "15", "CLOSE", CHANGE, "Out of stock for now")), null);

        verify(indents).applyDispatch(TENANT, LINE, 15, 5, "CLOSE", CHANGE, null, null, ME);
        verify(indents).setStatusLocked(TENANT, INDENT, "APPROVED", "DISPATCHED", ME);
    }

    @Test
    void aReplacementProductIsSentWithItsReasonAndIsRecordedOnTheIndentLine() {
        when(dispatches.replacementCandidates(eq(TENANT), eq(PRODUCT), anyInt())).thenReturn(List.of(OTHER_PRODUCT));
        when(stock.availableOfProduct(TENANT, CENTRAL, OTHER_PRODUCT)).thenReturn(List.of(
                bal(BATCH_B, OTHER_PRODUCT, 30, LocalDate.of(2027, 9, 30), NOW)));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 20, 0)));

        run(body(3, input(OTHER_PRODUCT, "20", null, CHANGE, null)), null);

        verify(indents).applyDispatch(TENANT, LINE, 20, 0, null, null, OTHER_PRODUCT, CHANGE, ME);
        ArgumentCaptor<DispatchRepository.NewLine> lines = ArgumentCaptor.forClass(DispatchRepository.NewLine.class);
        verify(dispatches).insertLine(eq(TENANT), lines.capture());
        assertThat(lines.getValue().replacedByCs()).isTrue();
        assertThat(lines.getValue().changeReasonKey()).isEqualTo(CHANGE);
        assertThat(lines.getValue().productId()).isEqualTo(OTHER_PRODUCT);
    }

    // ---- refusals ----

    @Test
    void everyLineErrorIsReportedTogetherAndNothingIsWritten() {
        PharmacyInputs.IndentDispatch bad = new PharmacyInputs.IndentDispatch(KEY, BigDecimal.valueOf(3), List.of(
                input(PRODUCT, "5", null, null, null), input(PRODUCT, "2.5", null, null, null)), null);

        assertThatThrownBy(() -> run(bad, null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                        "lines[0].balanceAction:Choose back-order or close",
                        "lines[1].indentLineId:Choose a line of this indent"));
        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insert(any(), any());
    }

    @Test
    void aReplacementThatIsNotAnOptionOrHasNoReasonAndACloseWithNoReasonAreRefused() {
        assertThatThrownBy(() -> run(body(3, input(OTHER_PRODUCT, "20", null, CHANGE, null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].dispatchProductId:Choose the requested product or one of the replacement options"));
        when(dispatches.replacementCandidates(eq(TENANT), eq(PRODUCT), anyInt())).thenReturn(List.of(OTHER_PRODUCT));
        assertThatThrownBy(() -> run(body(3, input(OTHER_PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("lines[0].changeReasonKey:Choose a reason"));
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "10", "CLOSE", null, null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("lines[0].changeReasonKey:Choose a reason"));
    }

    @Test
    void allQuantitiesZeroIsNothingToDispatchAndALineLeftOutIsRefused() {
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "0", "BACK_ORDER", null, null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("lines:Nothing to dispatch"));
        PharmacyInputs.IndentDispatch empty = new PharmacyInputs.IndentDispatch(KEY, BigDecimal.valueOf(3),
                List.of(new PharmacyInputs.DispatchLineInput(UUID.randomUUID(), PRODUCT, BigDecimal.ONE, null, null, null)), null);
        assertThatThrownBy(() -> run(empty, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message()).contains(
                        "lines:" + IndentDispatchService.LIST_EVERY));
    }

    @Test
    void aQuantityAboveTheRemainingApprovedQuantityIsRefused() {
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "21", null, null, null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "lines[0].quantity:Enter a whole number from 0 to 20"));
    }

    @Test
    void aSecondDispatcherWithAnOldIndentVersionGets409ChangedAndNothingIsDispatchedTwice() {
        assertThatThrownBy(() -> run(body(2, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        // the version moved between the first read and the lock
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("APPROVED", 2)));
        assertThatThrownBy(() -> run(body(2, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void onlyAnApprovedOrPartlyDispatchedIndentCanBeDispatched() {
        for (String status : List.of("WAITING_APPROVAL", "DISPATCHED", "RECEIVED", "CANCELLED")) {
            when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row(status, 3)));
            assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                    .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        }
        verify(posting, never()).post(any(), any());
    }

    @Test
    void anIndentOfAnotherStoreOrAStoreThatIsNotCentralIsNotFoundOrRefused() {
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(new IndentRepository.Row(INDENT, UNIT, WARD,
                UUID.randomUUID(), 12L, "IND-0012", "URGENT", "APPROVED", null, NOW, null, null, NOW, UUID.randomUUID(), 3)));
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOf(NotFoundException.class);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE))).thenReturn(new Store(CENTRAL, UNIT, "Test Ward", "W",
                StoreType.WARD, CENTRAL, false, true, true, true, 0));
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void moreThanTheCentralStoreHoldsIs409InsufficientStockWithTheAvailableQuantityAndNothingIsPosted() {
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(60, 0, 0)));
        when(indents.linesForUpdate(TENANT, INDENT)).thenReturn(List.of(line(60, 0, 0)));

        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "60", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("insufficient-stock");
                    assertThat(e.extensions()).containsEntry("available", 38);
                });
        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insert(any(), any());
    }

    @Test
    void anExpiredBatchIsNeverPickedSoOnlyTheUnexpiredQuantityCounts() {
        when(stock.availableOfProduct(TENANT, CENTRAL, PRODUCT)).thenReturn(List.of(
                bal(BATCH_A, PRODUCT, 8, LocalDate.of(2026, 10, 31), NOW),
                bal(BATCH_B, PRODUCT, 12, LocalDate.of(2027, 9, 30), NOW)));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)));
        when(indents.linesForUpdate(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)));

        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("insufficient-stock");
                    assertThat(e.extensions()).containsEntry("available", 12);
                });
    }

    // ---- replay ----

    @Test
    void theSameKeyAgainAnswersTheFirstDispatchWithNothingWritten() {
        UUID first = UUID.randomUUID();
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyDispatchIndent", first)));

        PharmacyViews.Outcome<DispatchViews.Dispatch> out = run(body(3, input(PRODUCT, "20", null, null, null)), null);

        assertThat(out.created()).isFalse();
        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insert(any(), any());
    }

    @Test
    void aKeyUsedForAnotherOperationOrAnotherIndentOrAnotherPersonIs409ClientKeyReused() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyCreateIndent", INDENT)));
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty());
        when(clients.heldByAnyone(TENANT, KEY)).thenReturn(true);
        assertThatThrownBy(() -> run(body(3, input(PRODUCT, "20", null, null, null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    // ---- Schedule X ----

    @Test
    void aScheduleXLineNeedsAPharmacistAndTheVerifiedSignerWhoIsOnBothLedgerLinesAndOnTheDispatch() {
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "X")));
        when(indents.lines(TENANT, INDENT)).thenReturn(List.of(line(20, 0, 0)), List.of(line(20, 0, 0)), List.of(line(20, 20, 0)));
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.IndentDispatch in = new PharmacyInputs.IndentDispatch(KEY, BigDecimal.valueOf(3),
                List.of(input(PRODUCT, "20", null, null, null)), typed);

        assertThat(service.precheck(TENANT, USER, CENTRAL.toString(), INDENT.toString(), PharmacyInputs.Body.of(in)))
                .isEqualTo(typed);
        assertThatThrownBy(() -> run(in, null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                .extracting(f -> f.field() + ":" + f.message()).containsExactly("secondSigner:" + ScheduleXSignature.NEEDED));

        run(in, SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        assertThat(posted()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
        ArgumentCaptor<DispatchRepository.NewDispatch> header = ArgumentCaptor.forClass(DispatchRepository.NewDispatch.class);
        verify(dispatches).insert(eq(TENANT), header.capture());
        assertThat(header.getValue().secondSignerMembershipId()).isEqualTo(SIGNER);
    }

    @Test
    void aCallerWhoIsNotAPharmacistIsRefusedForAScheduleXLineBeforeAnyPinIsChecked() {
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "X")));
        PharmacyInputs.IndentDispatch in = body(3, input(PRODUCT, "20", null, null, null));

        assertThatThrownBy(() -> service.precheck(TENANT, USER, CENTRAL.toString(), INDENT.toString(),
                PharmacyInputs.Body.of(in))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> run(in, SIGNER)).isInstanceOf(ForbiddenException.class);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void noSignerIsAskedForAnOrdinaryDispatchOrWhenTheKeyWasUsedOrTheIndentCannotBeDispatched() {
        PharmacyInputs.IndentDispatch in = body(3, input(PRODUCT, "20", null, null, null));
        assertThat(service.precheck(TENANT, USER, CENTRAL.toString(), INDENT.toString(), PharmacyInputs.Body.of(in))).isNull();

        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "X")));
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "x", INDENT)));
        assertThat(service.precheck(TENANT, USER, CENTRAL.toString(), INDENT.toString(), PharmacyInputs.Body.of(in))).isNull();
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty());
        when(indents.findById(TENANT, INDENT)).thenAnswer(i -> Optional.of(row("DISPATCHED", 3)));
        assertThat(service.precheck(TENANT, USER, CENTRAL.toString(), INDENT.toString(), PharmacyInputs.Body.of(in))).isNull();
    }

    // ---- replacement options and reads ----

    @Test
    void theReplacementOptionsShowTheCentralStoresUnexpiredAvailableQuantity() {
        when(dispatches.replacementCandidates(eq(TENANT), eq(PRODUCT), anyInt())).thenReturn(List.of(OTHER_PRODUCT));
        when(stock.availableOfProduct(TENANT, CENTRAL, OTHER_PRODUCT)).thenReturn(List.of(
                bal(BATCH_A, OTHER_PRODUCT, 8, LocalDate.of(2026, 10, 31), NOW),
                bal(BATCH_B, OTHER_PRODUCT, 12, LocalDate.of(2027, 9, 30), NOW)));

        DispatchViews.ReplacementOptions options = service.replacementOptions(TENANT, USER, CENTRAL.toString(), INDENT.toString(),
                LINE.toString());

        assertThat(options.items()).singleElement().satisfies(o -> assertThat(o.csAvailable()).isEqualTo(12));
        verify(posting).sweepStore(TENANT, UNIT, CENTRAL, ME);
    }

    @Test
    void aLineOfAnotherIndentHasNoOptionsAndAnIncomingListNeedsTheReceiveCapability() {
        assertThatThrownBy(() -> service.replacementOptions(TENANT, USER, CENTRAL.toString(), INDENT.toString(),
                UUID.randomUUID().toString())).isInstanceOf(NotFoundException.class);

        when(guard.require(any(), eq(WARD), eq(StoreCapability.RECEIVE))).thenReturn(new Store(WARD, UNIT, "Test Ward", "W",
                StoreType.WARD, CENTRAL, false, true, true, true, 0));
        when(dispatches.incoming(eq(TENANT), eq(WARD), eq(false), eq(20), eq(0))).thenReturn(new DispatchRepository.Slice(List.of(),
                0));
        assertThat(service.incoming(TENANT, USER, WARD.toString(), null, null, null).total()).isZero();
        assertThatThrownBy(() -> service.incoming(TENANT, USER, WARD.toString(), "LOST", null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }
}
