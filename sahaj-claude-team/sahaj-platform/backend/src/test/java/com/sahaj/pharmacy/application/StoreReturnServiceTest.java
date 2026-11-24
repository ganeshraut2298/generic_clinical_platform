package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
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
 * SAH-79-14 (ADR-029 P12): a store return takes a RET number, moves AVAILABLE to PENDING_RETURN at the returning store and
 * writes a return item for the Central Store; the Central Store cannot return to itself; a quantity above AVAILABLE is
 * refused; a Schedule X batch needs a pharmacist and a verified second signer on both ledger lines.
 */
class StoreReturnServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String REASON = "store-return.excess";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final StoreReturnRepository storeReturns = mock(StoreReturnRepository.class);
    private final ReturnItemRepository returnItems = mock(ReturnItemRepository.class);
    private final ReturnShortageService returnViews = mock(ReturnShortageService.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final StockAuditRepository auditRows = mock(StockAuditRepository.class);
    private final StoreReturnService service = new StoreReturnService(gate, guard, stores, products, stock, posting, storeReturns,
            returnItems, returnViews, series, numbers, clients, events, reasons, engine, signature, team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), new BlindCount(auditRows));
    private final Store ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, CENTRAL, false, true, true, true, 0);
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, true, true,
            true, 0);
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of());
    private String schedule = "NONE";
    private LocalDate expiry = LocalDate.of(2028, 3, 31);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RETURN_DAMAGE))).thenReturn(ward);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.RETURN_DAMAGE))).thenReturn(central);
        when(reasons.isActive(TENANT, ReasonList.STORE_RETURN, REASON)).thenReturn(true);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(stock.findBatch(TENANT, BATCH)).thenAnswer(i -> Optional.of(new BatchRow(BATCH, PRODUCT, "B1", expiry,
                new BigDecimal("10.00"), new BigDecimal("4.5000"), null, NOW)));
        when(stock.availableQty(TENANT, WARD, BATCH)).thenReturn(Optional.of(40));
        when(products.find(TENANT, PRODUCT)).thenAnswer(i -> Optional.of(full()));
        when(stores.findForUpdate(TENANT, WARD)).thenReturn(Optional.of(ward));
        when(stores.list(TENANT, true)).thenReturn(List.of(ward, central));
        when(numbers.next(TENANT, DocType.STORE_RETURN)).thenReturn(311L);
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(storeReturns.findById(eq(TENANT), any())).thenAnswer(i -> Optional.of(saved(i.getArgument(1))));
        when(returnItems.findByStoreReturn(eq(TENANT), any())).thenReturn(Optional.empty());
    }

    private ProductRepository.Full full() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "P-1", "Test Paracetamol 500 mg (fake)", null,
                false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, true, 0, ME);
    }

    private StoreReturnRepository.Row saved(UUID id) {
        return new StoreReturnRepository.Row(id, UNIT, 311, "RET-0311", WARD, PRODUCT, BATCH, 6, REASON, null, null, NOW, ME);
    }

    private PharmacyInputs.StoreReturnCreate input(String qty, String reason, PharmacyInputs.SecondSignerInput signer) {
        return new PharmacyInputs.StoreReturnCreate(KEY, BATCH, new BigDecimal(qty), reason, null, signer);
    }

    private PharmacyViews.Outcome<StoreUseViews.StoreReturn> create(PharmacyInputs.StoreReturnCreate in, UUID signer) {
        return service.create(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in), signer);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aReturnTakesTheNumberMovesAvailableToPendingReturnAndWritesTheItemForTheCentralStore() {
        PharmacyViews.Outcome<StoreUseViews.StoreReturn> out = create(input("6", REASON, null), null);

        assertThat(out.created()).isTrue();
        assertThat(out.view().number()).isEqualTo("RET-0311");
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<StoreReturnRepository.NewRow> row = ArgumentCaptor.forClass(StoreReturnRepository.NewRow.class);
        ArgumentCaptor<ReturnItemRepository.NewItem> item = ArgumentCaptor.forClass(ReturnItemRepository.NewItem.class);
        InOrder order = Mockito.inOrder(clients, stores, numbers, posting, storeReturns, returnItems);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateStoreReturn"), any());
        order.verify(stores).findForUpdate(TENANT, WARD);
        order.verify(numbers).next(TENANT, DocType.STORE_RETURN);
        order.verify(posting).post(any(StockPosting.Actor.class), lines.capture());
        order.verify(storeReturns).insert(eq(TENANT), row.capture());
        order.verify(returnItems).insert(eq(TENANT), item.capture());
        assertThat(lines.getValue()).extracting(StockPosting.Line::state).containsExactly(StockState.AVAILABLE,
                StockState.PENDING_RETURN);
        assertThat(lines.getValue()).extracting(StockPosting.Line::delta).containsExactly(-6, 6);
        assertThat(lines.getValue()).extracting(StockPosting.Line::txnType).containsOnly("STORE_RETURN");
        assertThat(lines.getValue().get(0).movementId()).isEqualTo(lines.getValue().get(1).movementId());
        assertThat(lines.getValue().get(0).doc().no()).isEqualTo("RET-0311");
        assertThat(row.getValue().createdBy()).isEqualTo(ME);
        assertThat(item.getValue().itemType()).isEqualTo("RETURN_TO_CS");
        assertThat(item.getValue().storeReturnId()).isEqualTo(row.getValue().id());
        assertThat(item.getValue().holdingStoreId()).isEqualTo(WARD);
        assertThat(item.getValue().acceptingStoreId()).isEqualTo(CENTRAL);
        assertThat(item.getValue().qty()).isEqualTo(6);
        verify(audit).record(eq("PHARMACY_STORE_RETURN_CREATED"), any(), any(), any(), any());
        UserIdNeverWritten.assertNone(USER, storeReturns, returnItems, posting, events, audit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerBlindAtTheStoreOnAnAuditedProductIsToldNoNumberAndAnInsufficientStockRefusalHidesIt() {
        com.sahaj.pharmacy.domain.StoreRole role = com.sahaj.pharmacy.domain.StoreRole.STORE_USER;
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of(new PharmacyCaller.Mapping(ward, null,
                role, com.sahaj.pharmacy.domain.StoreRules.access("NURSE", role, false))));
        when(auditRows.openAuditProducts(TENANT, WARD)).thenReturn(java.util.Set.of(PRODUCT));

        assertThatThrownBy(() -> create(input("41", REASON, null), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message())
                        .containsExactly("quantity:This is more than the quantity in stock."));

        when(posting.post(any(), any())).thenThrow(PharmacyProblemException.insufficientStock(3, List.of(
                new PharmacyProblemException.FailingLine(null, null, PRODUCT, 3))));
        assertThatThrownBy(() -> create(input("6", REASON, null), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> {
                    assertThat(e.extensions()).containsEntry("available", null);
                    assertThat(((List<PharmacyProblemException.FailingLine>) e.extensions().get("lines")).get(0).available())
                            .isNull();
                });
    }

    @Test
    void theCentralStoreCannotReturnToItselfAndAQuantityAboveAvailableOrABadReasonIsRefusedTogether() {
        when(stock.availableQty(TENANT, CENTRAL, BATCH)).thenReturn(Optional.of(40));
        assertThatThrownBy(() -> service.create(TENANT, USER, CENTRAL.toString(), PharmacyInputs.Body.of(input("6", REASON, null)),
                null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).containsExactly("storeId"));

        assertThatThrownBy(() -> create(input("41", "store-return.unknown", null), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "quantity:Enter a whole number from 1 to 40.", "reasonKey:Choose a reason"));
        verify(posting, never()).post(any(), any());
        verify(storeReturns, never()).insert(any(), any());
    }

    @Test
    void anExpiredBatchAndAnOtherReasonWithoutANoteAreRefused() {
        expiry = LocalDate.of(2026, 10, 31);
        when(reasons.isActive(TENANT, ReasonList.STORE_RETURN, "store-return.other")).thenReturn(true);

        assertThatThrownBy(() -> create(input("6", "store-return.other", null), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).containsExactly("batchId", "note"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aScheduleXBatchNeedsAPharmacistAndTheVerifiedSignerOnBothLines() {
        schedule = "X";
        assertThatThrownBy(() -> create(input("6", REASON, null), null)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.problemType()).isEqualTo("schedule-x-pharmacist"));

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        assertThatThrownBy(() -> create(input("6", REASON, null), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("secondSigner"));

        create(input("6", REASON, null), SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), lines.capture());
        assertThat(lines.getValue()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
    }

    @Test
    void thePreCheckOffersASignerOnlyToAPharmacistReturningAScheduleXBatch() {
        PharmacyInputs.SecondSignerInput named = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.StoreReturnCreate in = input("6", REASON, named);
        assertThat(service.precheck(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in))).isNull();

        schedule = "X";
        assertThat(service.precheck(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in))).isNull();

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        assertThat(service.precheck(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in))).isSameAs(named);
    }

    @Test
    void theSameKeyAgainAnswersTheFirstReturnAndAKeyOfAnotherOperationIs409() {
        UUID first = UUID.randomUUID();
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyCreateStoreReturn", first)));

        PharmacyViews.Outcome<StoreUseViews.StoreReturn> out = create(input("6", REASON, null), null);

        assertThat(out.created()).isFalse();
        assertThat(out.view().id()).isEqualTo(first);
        verify(posting, never()).post(any(), any());

        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyCreateX", first)));
        assertThatThrownBy(() -> create(input("6", REASON, null), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }
}
