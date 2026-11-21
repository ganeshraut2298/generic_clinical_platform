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

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-79-15 dispatch over mocks: only an APPROVED transfer at its sending store, FEFO picks move AVAILABLE to the receiving
 * store's IN_TRANSIT (ledger TRANSFER_DISPATCH), a dispatch record of source TRANSFER is written, not enough stock is 409, a
 * Schedule X line needs a pharmacist and the verified signer, and a replay of the key changes nothing.
 */
class TransferDispatchServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final DispatchRepository dispatches = mock(DispatchRepository.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final TransferService transferService = mock(TransferService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TransferDispatchService service = new TransferDispatchService(gate, guard, stores, products, stock, posting,
            signature, transfers, dispatches, clients, events, transferService, audit, new BlindCount(mock(StockAuditRepository.class)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
    private final Store from = new Store(FROM, UNIT, "Test Pharmacy", "PH", StoreType.PHARMACY, null, true, false, true, true, 0);
    private String status = "APPROVED";
    private String schedule = "NONE";

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(FROM), eq(StoreCapability.DISPATCH_TRANSFER))).thenReturn(from);
        when(stores.findForUpdate(TENANT, FROM)).thenReturn(Optional.of(from));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(transfers.findById(TENANT, TRANSFER)).thenAnswer(i -> Optional.of(row(0)));
        when(transfers.findForUpdate(TENANT, TRANSFER)).thenAnswer(i -> Optional.of(row(0)));
        when(transfers.lines(TENANT, TRANSFER)).thenReturn(List.of(new TransferRepository.Line(UUID.randomUUID(), TRANSFER,
                PRODUCT, 6, 3600)));
        when(transfers.setStatus(eq(TENANT), eq(TRANSFER), eq(0L), eq("IN_TRANSIT"), eq(ME))).thenReturn(true);
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product()));
        when(stock.availableOfProduct(TENANT, FROM, PRODUCT)).thenReturn(List.of(new StockRepository.Bal(UUID.randomUUID(), FROM,
                PRODUCT, BATCH, StockState.AVAILABLE, 10, "TESTB001", LocalDate.of(2027, 3, 31), NOW, new BigDecimal("10.00"),
                new BigDecimal("6.0000"))));
        when(posting.pick(any(), anyInt())).thenAnswer(i -> {
            int qty = i.getArgument(1);
            return new Fefo.Result(List.of(new Fefo.Pick(BATCH, Math.min(qty, 10))), 10, Math.max(0, qty - 10));
        });
    }

    private ProductRepository.Full product() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "U-TCB-0001", "Test IV Fluid (fake)", null,
                false, "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private TransferRepository.Row row(long version) {
        return new TransferRepository.Row(TRANSFER, UNIT, 438, "TRF-0438", FROM, TO, "transfer.urgent-shortage", null, status,
                null, 3600, NOW, ME, version);
    }

    private TransferViews.Transfer run(UUID signer) {
        return service.dispatch(TENANT, USER, FROM.toString(), TRANSFER.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.PostWithSigner(KEY, BigDecimal.ZERO, null)), signer);
    }

    @Test
    void anApprovedTransferMovesTheFefoUnitsToTheReceivingStoresTransitAndGoesInTransit() {
        run(null);

        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue()).hasSize(2);
        assertThat(moves.getValue().get(0).storeId()).isEqualTo(FROM);
        assertThat(moves.getValue().get(0).state()).isEqualTo(StockState.AVAILABLE);
        assertThat(moves.getValue().get(0).delta()).isEqualTo(-6);
        assertThat(moves.getValue().get(0).txnType()).isEqualTo("TRANSFER_DISPATCH");
        assertThat(moves.getValue().get(1).storeId()).isEqualTo(TO);
        assertThat(moves.getValue().get(1).state()).isEqualTo(StockState.IN_TRANSIT);
        assertThat(moves.getValue().get(1).delta()).isEqualTo(6);
        ArgumentCaptor<DispatchRepository.NewTransferDispatch> header = ArgumentCaptor
                .forClass(DispatchRepository.NewTransferDispatch.class);
        verify(dispatches).insertTransfer(eq(TENANT), header.capture());
        assertThat(header.getValue().reference()).isEqualTo("TRF-0438");
        assertThat(header.getValue().toStoreId()).isEqualTo(TO);
        verify(dispatches).insertTransferLine(eq(TENANT), any());
        verify(transfers).setStatus(TENANT, TRANSFER, 0L, "IN_TRANSIT", ME);
    }

    @Test
    void onlyAnApprovedTransferCanBeDispatched() {
        status = "WAITING_APPROVAL";

        assertThatThrownBy(() -> run(null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aStaleVersionIs409Changed() {
        assertThatThrownBy(() -> service.dispatch(TENANT, USER, FROM.toString(), TRANSFER.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.PostWithSigner(KEY, BigDecimal.valueOf(3), null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void notEnoughStockAnswers409WithTheAvailableQuantityAndMovesNothing() {
        when(transfers.lines(TENANT, TRANSFER)).thenReturn(List.of(new TransferRepository.Line(UUID.randomUUID(), TRANSFER,
                PRODUCT, 12, 7200)));

        assertThatThrownBy(() -> run(null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("insufficient-stock"));
        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insertTransfer(any(), any());
    }

    @Test
    void aScheduleXLineNeedsAPharmacistAndTheVerifiedSigner() {
        schedule = "X";

        assertThatThrownBy(() -> run(null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains("secondSigner"));
        run(SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue().get(0).secondSignerMembershipId()).isEqualTo(SIGNER);
    }

    @Test
    void aCallerWhoIsNotAPharmacistCannotDispatchAScheduleXLine() {
        schedule = "X";
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Store User", "NURSE", List.of());

        assertThatThrownBy(() -> run(SIGNER)).isInstanceOf(ForbiddenException.class);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void thePrecheckAsksForTheSignerOnlyForAnApprovedScheduleXTransferAndNotOnAReplay() {
        PharmacyInputs.SecondSignerInput signer = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body = PharmacyInputs.Body.of(
                new PharmacyInputs.PostWithSigner(KEY, BigDecimal.ZERO, signer));

        assertThat(service.precheck(TENANT, USER, FROM.toString(), TRANSFER.toString(), body)).isNull();
        schedule = "X";
        assertThat(service.precheck(TENANT, USER, FROM.toString(), TRANSFER.toString(), body)).isEqualTo(signer);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                TransferDispatchService.OP_DISPATCH, TRANSFER)));
        assertThat(service.precheck(TENANT, USER, FROM.toString(), TRANSFER.toString(), body)).isNull();
    }

    @Test
    void aReplayOfTheKeyChangesNothing() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                TransferDispatchService.OP_DISPATCH, TRANSFER)));

        run(null);

        verify(posting, never()).post(any(), any());
        verify(dispatches, never()).insertTransfer(any(), any());
    }
}
