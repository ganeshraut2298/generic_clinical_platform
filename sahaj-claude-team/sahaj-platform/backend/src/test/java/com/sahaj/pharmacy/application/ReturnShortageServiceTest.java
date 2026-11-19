package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
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
 * SAH-79-13: returned and rejected items, disputes and notes, and shortages over mocks. Accepting an item moves the held units to
 * saleable stock or quarantine (an expired batch only to quarantine), a Schedule X item needs a pharmacist and the verified
 * signer, the dispute is set once, a settled shortage moves its units (found to the settling store, a transit loss written off now
 * or after approval, a carrier claim only recorded), and the write-off approval handler posts with the raiser as actor.
 */
class ReturnShortageServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID SHORTAGE = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String DISPUTE = "dispute.other";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final ReturnItemRepository returns = mock(ReturnItemRepository.class);
    private final ShortageRepository shortages = mock(ShortageRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final ReturnShortageService service = new ReturnShortageService(gate, guard, stores, products, stock, posting,
            signature, returns, shortages, engine, clients, events, reasons, new DocumentViews(events, stores, team, engine), team,
            audit, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Central User", "STORE_STAFF", List.of());
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true,
            true, 0);
    private final Store ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, CENTRAL, false, true, true, true, 0);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE))).thenReturn(central);
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RECEIVE))).thenReturn(ward);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.RECEIVE))).thenReturn(central);
        when(stores.list(TENANT, true)).thenReturn(List.of(central, ward));
        when(stores.findForUpdate(TENANT, CENTRAL)).thenReturn(Optional.of(central));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "NONE")));
        when(stock.findBatch(eq(TENANT), any())).thenAnswer(i -> Optional.of(batch(LocalDate.of(2027, 3, 31))));
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("WAITING", 0, false)));
        when(returns.findForUpdate(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("WAITING", 0, false)));
        when(shortages.findById(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("OPEN", 0)));
        when(shortages.findForUpdate(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("OPEN", 0)));
        when(reasons.isActive(TENANT, ReasonList.DISPUTE, DISPUTE)).thenReturn(true);
        when(returns.accept(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any())).thenReturn(true);
    }

    private static ProductRepository.Full product(UUID id, String schedule) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test IV Fluid (fake)", null, false,
                "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static StockRepository.BatchRow batch(LocalDate expiry) {
        return new StockRepository.BatchRow(BATCH, PRODUCT, "TESTB001", expiry, new BigDecimal("10.00"),
                new BigDecimal("6.0000"), null, NOW);
    }

    private static ReturnItemRepository.Row item(String status, long version, boolean scheduleX) {
        return new ReturnItemRepository.Row(ITEM, UNIT, "IND-0012 / 1", "INDENT_REJECTION", LINE, null, null, WARD, CENTRAL, PRODUCT,
                BATCH, 2, "receipt-reject.damaged-in-transit", status, "QUARANTINE", scheduleX, RAISER, NOW, null, null, null, null,
                null, null, null, version);
    }

    private static ShortageRepository.Row shortage(String status, long version) {
        return new ShortageRepository.Row(SHORTAGE, UNIT, LINE, "IND-0012 / 1", WARD, CENTRAL, PRODUCT, BATCH, 4, status, null, null,
                null, null, null, RAISER, NOW, version);
    }

    private DispatchViews.ReturnItem accept(String putBack, long version, UUID signer) {
        return service.accept(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.AcceptReturn(KEY, BigDecimal.valueOf(version), putBack, null)), signer);
    }

    private DispatchViews.Shortage settle(String outcome, long version, UUID signer) {
        return service.settle(TENANT, USER, CENTRAL.toString(), SHORTAGE.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.SettleShortage(KEY, BigDecimal.valueOf(version), outcome, "Checked the shelves", null)), signer);
    }

    @SuppressWarnings("unchecked")
    private List<StockPosting.Line> posted() {
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        return moves.getValue();
    }

    // ---- accept ----

    @Test
    void acceptingIntoSaleableStockMovesPendingReturnAtTheHoldingStoreToAvailableHereInOneMovement() {
        accept("SALEABLE", 0, null);

        List<StockPosting.Line> moves = posted();
        assertThat(moves).extracting(StockPosting.Line::storeId).containsExactly(WARD, CENTRAL);
        assertThat(moves).extracting(StockPosting.Line::state).containsExactly(StockState.PENDING_RETURN, StockState.AVAILABLE);
        assertThat(moves).extracting(StockPosting.Line::delta).containsExactly(-2, 2);
        assertThat(moves).extracting(StockPosting.Line::txnType).containsOnly("RETURN_ACCEPTED");
        assertThat(moves.get(0).movementId()).isEqualTo(moves.get(1).movementId());
        verify(returns).accept(TENANT, ITEM, 0, "ACCEPTED_SALEABLE", ME, null,
                "Return accepted. 2 units added to Test Central Store: saleable stock.");
        UserIdNeverWritten.assertNone(USER, returns, shortages, posting, events, audit);
    }

    @Test
    void acceptingIntoQuarantineAnItemThatIsDisputedWorksToo() {
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("DISPUTED", 1, false)));
        when(returns.findForUpdate(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("DISPUTED", 1, false)));

        accept("QUARANTINE", 1, null);

        assertThat(posted()).extracting(StockPosting.Line::state).containsExactly(StockState.PENDING_RETURN, StockState.QUARANTINE);
        verify(returns).accept(TENANT, ITEM, 1, "ACCEPTED_QUARANTINE", ME, null,
                "Return accepted. 2 units added to Test Central Store: quarantine.");
    }

    @Test
    void anExpiredBatchCanOnlyGoToQuarantine() {
        when(stock.findBatch(eq(TENANT), any())).thenAnswer(i -> Optional.of(batch(LocalDate.of(2026, 10, 31))));

        assertThatThrownBy(() -> accept("SALEABLE", 0, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("batch-expired"));
        verify(posting, never()).post(any(), any());

        accept("QUARANTINE", 0, null);
        assertThat(posted()).extracting(StockPosting.Line::state).containsExactly(StockState.PENDING_RETURN, StockState.QUARANTINE);
    }

    @Test
    void anAcceptedItemOrAnOldVersionIs409ChangedAndABadChoiceIs400() {
        assertThatThrownBy(() -> accept("SALEABLE", 5, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("changed"));
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("ACCEPTED_SALEABLE", 1, false)));
        assertThatThrownBy(() -> accept("SALEABLE", 1, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("changed"));
        assertThatThrownBy(() -> accept("SOMEWHERE", 0, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("putBackInto"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aScheduleXItemNeedsAPharmacistAndTheVerifiedSignerWhoIsOnBothLedgerLines() {
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("WAITING", 0, true)));
        when(returns.findForUpdate(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("WAITING", 0, true)));
        PharmacyInputs.AcceptReturn in = new PharmacyInputs.AcceptReturn(KEY, BigDecimal.ZERO, "SALEABLE", null);

        assertThatThrownBy(() -> service.precheckAccept(TENANT, USER, CENTRAL.toString(), ITEM.toString(),
                PharmacyInputs.Body.of(in))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> accept("SALEABLE", 0, SIGNER)).isInstanceOf(ForbiddenException.class);

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        assertThat(service.precheckAccept(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.AcceptReturn(KEY, BigDecimal.ZERO, "SALEABLE", typed)))).isEqualTo(typed);
        assertThatThrownBy(() -> accept("SALEABLE", 0, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field() + ":" + f.message())
                        .containsExactly("secondSigner:" + ScheduleXSignature.NEEDED));

        accept("SALEABLE", 0, SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        assertThat(posted()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
        verify(returns).accept(eq(TENANT), eq(ITEM), eq(0L), eq("ACCEPTED_SALEABLE"), eq(ME), eq(SIGNER), any());
    }

    @Test
    void theSameKeyAgainAnswersTheItemAndMovesNoStockTwice() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyAcceptReturnItem",
                ITEM)));

        assertThat(accept("SALEABLE", 0, null)).isNotNull();

        verify(posting, never()).post(any(), any());
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyAcceptReturnItem",
                UUID.randomUUID())));
        assertThatThrownBy(() -> accept("SALEABLE", 0, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    @Test
    void anItemOfAnotherAcceptingStoreIsNotFound() {
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(new ReturnItemRepository.Row(ITEM, UNIT, "x", "INDENT_REJECTION",
                LINE, null, null, WARD, UUID.randomUUID(), PRODUCT, BATCH, 2, "r", "WAITING", "QUARANTINE", false, RAISER, NOW, null,
                null, null, null, null, null, null, 0)));

        assertThatThrownBy(() -> accept("SALEABLE", 0, null)).isInstanceOf(NotFoundException.class);
    }

    // ---- dispute and reply ----

    @Test
    void aDisputeSetsTheReasonOnceKeepsTheUnitsWhereTheyAreAndNeedsANoteForOther() {
        PharmacyInputs.VersionReasonInput noNote = new PharmacyInputs.VersionReasonInput(BigDecimal.ZERO, DISPUTE, null);
        assertThatThrownBy(() -> service.dispute(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(noNote)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("note:Write at least 4 characters"));
        when(returns.dispute(TENANT, ITEM, 0, DISPUTE, ME)).thenReturn(true);

        service.dispute(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(BigDecimal.ZERO, DISPUTE, "Looks fine on our side")));

        verify(returns).dispute(TENANT, ITEM, 0, DISPUTE, ME);
        verify(returns).insertNote(TENANT, UNIT, ITEM, "Looks fine on our side", ME);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aDisputeOfAnItemThatIsDisputedAlreadyOrAcceptedIs409Changed() {
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("DISPUTED", 1, false)));

        assertThatThrownBy(() -> service.dispute(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(BigDecimal.ONE, DISPUTE, "Looks fine on our side"))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(returns, never()).dispute(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void theHoldingStoreAddsANoteToAWaitingOrDisputedItemWithNoStockEffect() {
        service.reply(TENANT, USER, WARD.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.NoteInput(KEY, "  We checked it twice  ")));

        verify(returns).insertNote(TENANT, UNIT, ITEM, "We checked it twice", ME);
        verify(posting, never()).post(any(), any());

        assertThatThrownBy(() -> service.reply(TENANT, USER, WARD.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.NoteInput(UUID.randomUUID(), "no")))).isInstanceOf(InvalidFieldsException.class);
        when(returns.findById(TENANT, ITEM)).thenAnswer(i -> Optional.of(item("ACCEPTED_SALEABLE", 2, false)));
        assertThatThrownBy(() -> service.reply(TENANT, USER, WARD.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.NoteInput(UUID.randomUUID(), "We checked it twice")))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
    }

    @Test
    void aNoteFromTheAcceptingStoreIsNotFound() {
        assertThatThrownBy(() -> service.reply(TENANT, USER, CENTRAL.toString(), ITEM.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.NoteInput(KEY, "We checked it twice")))).isInstanceOf(NotFoundException.class);
    }

    // ---- reads ----

    @Test
    void theWaitingListNeedsASideAndTheCapabilityOfThatSide() {
        when(returns.page(eq(TENANT), eq(CENTRAL), eq(true), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new ReturnItemRepository.Slice(List.of(item("WAITING", 0, false)),
                1));

        DispatchViews.ReturnItemPage page = service.waiting(TENANT, USER, CENTRAL.toString(), "ACCEPTING", null, null, null);

        assertThat(page.items()).singleElement().satisfies(i -> assertThat(i.qty()).isEqualTo(2));
        verify(guard).require(any(), eq(CENTRAL), eq(StoreCapability.PURCHASE));
        assertThatThrownBy(() -> service.waiting(TENANT, USER, CENTRAL.toString(), null, null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.waiting(TENANT, USER, CENTRAL.toString(), "ACCEPTING", "SOON", null, null))
                .isInstanceOf(InvalidFieldsException.class);
        service.waiting(TENANT, USER, WARD.toString(), "RETURNING", null, null, null);
        verify(guard).require(any(), eq(WARD), eq(StoreCapability.RECEIVE));
    }

    @Test
    void anItemIsReadByItsAcceptingStoreOrItsHoldingStoreAndByNoOneElse() {
        assertThat(service.get(TENANT, USER, CENTRAL.toString(), ITEM.toString()).id()).isEqualTo(ITEM);
        assertThat(service.get(TENANT, USER, WARD.toString(), ITEM.toString()).status()).isEqualTo("WAITING");
        UUID other = UUID.randomUUID();
        when(guard.require(any(), eq(other), eq(StoreCapability.RECEIVE))).thenReturn(ward);
        assertThatThrownBy(() -> service.get(TENANT, USER, other.toString(), ITEM.toString())).isInstanceOf(NotFoundException.class);
    }

    // ---- shortages ----

    @Test
    void aShortageFoundMovesInTransitAtTheReceivingStoreToAvailableAtTheSettlingStore() {
        settle("FOUND", 0, null);

        List<StockPosting.Line> moves = posted();
        assertThat(moves).extracting(StockPosting.Line::storeId).containsExactly(WARD, CENTRAL);
        assertThat(moves).extracting(StockPosting.Line::state).containsExactly(StockState.IN_TRANSIT, StockState.AVAILABLE);
        assertThat(moves).extracting(StockPosting.Line::delta).containsExactly(-4, 4);
        assertThat(moves).extracting(StockPosting.Line::txnType).containsOnly("SHORTAGE_FOUND");
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(0L), eq("FOUND"), any(), eq(null), eq(ME), eq(null), eq(ME));
        UserIdNeverWritten.assertNone(USER, returns, shortages, posting, events, audit);
    }

    @Test
    void aShortageFoundOfAnExpiredBatchGoesToQuarantine() {
        when(stock.findBatch(eq(TENANT), any())).thenAnswer(i -> Optional.of(batch(LocalDate.of(2026, 10, 31))));

        settle("FOUND", 0, null);

        assertThat(posted()).extracting(StockPosting.Line::state).containsExactly(StockState.IN_TRANSIT, StockState.QUARANTINE);
    }

    @Test
    void aScheduleXShortageFoundNeedsAPharmacistAndTheSignerButAWriteOffDoesNot() {
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1), "X")));
        PharmacyInputs.SettleShortage found = new PharmacyInputs.SettleShortage(KEY, BigDecimal.ZERO, "FOUND", null, null);
        assertThatThrownBy(() -> service.precheckSettle(TENANT, USER, CENTRAL.toString(), SHORTAGE.toString(),
                PharmacyInputs.Body.of(found))).isInstanceOf(ForbiddenException.class);
        assertThat(service.precheckSettle(TENANT, USER, CENTRAL.toString(), SHORTAGE.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.SettleShortage(KEY, BigDecimal.ZERO, "TRANSIT_LOSS", null, null)))).isNull();

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        settle("FOUND", 0, SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        assertThat(posted()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
    }

    @Test
    void aTransitLossWithNoApprovalLevelIsWrittenOffAtOnceWithNoSigner() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        settle("TRANSIT_LOSS", 0, null);

        List<StockPosting.Line> moves = posted();
        assertThat(moves).singleElement().satisfies(m -> {
            assertThat(m.storeId()).isEqualTo(WARD);
            assertThat(m.state()).isEqualTo(StockState.IN_TRANSIT);
            assertThat(m.delta()).isEqualTo(-4);
            assertThat(m.txnType()).isEqualTo("SHORTAGE_WRITE_OFF");
            assertThat(m.secondSignerMembershipId()).isNull();
        });
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(0L), eq("WRITTEN_OFF"), any(), eq(null), eq(ME), eq(null), eq(ME));
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.WRITE_OFF);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(CENTRAL);
        assertThat(subject.getValue().amountPaise()).isEqualTo(2_400L);
    }

    @Test
    void aTransitLossThatTheRuleSendsForApprovalWaitsAndPostsNothingYet() {
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        UUID request = UUID.randomUUID();
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(request);

        settle("TRANSIT_LOSS", 0, null);

        verify(posting, never()).post(any(), any());
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(0L), eq("WAITING_WRITE_OFF"), any(), eq(null), eq(null), eq(request),
                eq(ME));
    }

    @Test
    void aCarrierClaimOnlyRecordsTheClaimAndMovesNoStock() {
        settle("CARRIER_CLAIM", 0, null);

        verify(posting, never()).post(any(), any());
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(0L), eq("CLAIM_OPEN"), any(), eq(null), eq(null), eq(null), eq(ME));

        when(shortages.findById(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("CLAIM_OPEN", 1)));
        when(shortages.findForUpdate(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("CLAIM_OPEN", 1)));
        assertThatThrownBy(() -> settle("CARRIER_CLAIM", 1, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("doc-state"));
        settle("FOUND", 1, null);
    }

    @Test
    void aSettledOrWaitingShortageOrAnOldVersionIs409ChangedAndABadOutcomeIs400() {
        when(shortages.findById(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("WAITING_WRITE_OFF", 1)));
        assertThatThrownBy(() -> settle("FOUND", 1, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("changed"));
        when(shortages.findById(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("OPEN", 0)));
        assertThatThrownBy(() -> settle("FOUND", 3, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("changed"));
        assertThatThrownBy(() -> settle("LOST", 0, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("outcome"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theShortageListIsForTheSettlingStoreAndStatusIsValidated() {
        when(shortages.page(eq(TENANT), eq(CENTRAL), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new ShortageRepository.Slice(List.of(shortage("OPEN", 0)), 1));

        DispatchViews.ShortagePage page = service.listShortages(TENANT, USER, CENTRAL.toString(), "OPEN", null, null);

        assertThat(page.items()).singleElement().satisfies(s -> {
            assertThat(s.qty()).isEqualTo(4);
            assertThat(s.banner()).startsWith("Shortage, not a return. 4 units never reached Test Ward 3B");
        });
        assertThatThrownBy(() -> service.listShortages(TENANT, USER, CENTRAL.toString(), "LOST", null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }

    // ---- the write-off approval handler ----

    private ApprovalSubjectHandler.Decision decision() {
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(SHORTAGE);
        when(request.raisedBy()).thenReturn(RAISER);
        PharmacyCaller approver = new PharmacyCaller(TENANT, UUID.randomUUID(), SIGNER, "Test Owner", "OWNER", List.of());
        return new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null);
    }

    @Test
    void theApprovalWritesTheUnitsOffWithTheRaiserAsActorAndTheApproverRecorded() {
        when(shortages.findForUpdate(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("WAITING_WRITE_OFF", 2)));

        service.onApproved(decision());

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(posting).post(actor.capture(), any());
        assertThat(actor.getValue().membershipId()).isEqualTo(RAISER);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(SIGNER);
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(2L), eq("WRITTEN_OFF"), any(), eq(null), eq(RAISER), eq(null), eq(SIGNER));
    }

    @Test
    void aRejectionPutsTheShortageBackToOpenAndPostsNothing() {
        when(shortages.findForUpdate(TENANT, SHORTAGE)).thenAnswer(i -> Optional.of(shortage("WAITING_WRITE_OFF", 2)));

        service.onRejected(decision());

        verify(posting, never()).post(any(), any());
        verify(shortages).move(eq(TENANT), eq(SHORTAGE), eq(2L), eq("OPEN"), eq(null), eq(null), eq(null), eq(null), eq(SIGNER));
    }

    @Test
    void aDecisionOnAShortageThatIsNoLongerWaitingChangesNothing() {
        service.onApproved(decision());
        service.onRejected(decision());

        verify(posting, never()).post(any(), any());
        verify(shortages, never()).move(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any(), any());
        assertThat(service.subjectType()).isEqualTo("SHORTAGE");
    }
}
