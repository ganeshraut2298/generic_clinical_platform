package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.OpeningStockRepository.ImportRow;
import com.sahaj.pharmacy.application.OpeningStockRepository.PostingLine;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-1: "Post to stock" (ADR-029 P5). One transaction in the lock order: the client key, the store, the import, the batches,
 * then the balances inside the posting service; the one update at the end; 409 not-approved, already-posted and
 * batch-mrp-mismatch write nothing; an expired line goes to Quarantine with the reason damage.expired.
 */
class OpeningStockPostServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID IMPORT_ID = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final OpeningStockRepository imports = mock(OpeningStockRepository.class);
    private final OpeningStockAssembler assembler = mock(OpeningStockAssembler.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final OpeningStockPostService service = new OpeningStockPostService(gate, guard, stores, imports, assembler, stock,
            posting, clients, events, team, audit, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Owner One", "OWNER", List.of());
    private final Store store = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true,
            true, 0);

    private ImportRow importRow(ImportStatus status, Instant postedAt) {
        return new ImportRow(IMPORT_ID, STORE_ID, status, 2, UUID.randomUUID(), UUID.randomUUID(), NOW, NOW, UUID.randomUUID(),
                NOW, null, null, null, 2, 2, 30, 30_000L, 0, 0, 3, postedAt, postedAt == null ? null : ME);
    }

    private PostingLine line(int row, String batch, LocalDate expiry, String mrp, int qty) {
        return new PostingLine(UUID.randomUUID(), row, PRODUCT, "Test Paracetamol 500 mg (fake)", batch, expiry,
                new BigDecimal(mrp), new BigDecimal("7.5000"), qty);
    }

    private BatchRow batch(UUID id, String mrp) {
        return new BatchRow(id, PRODUCT, "B1", LocalDate.of(2028, 3, 31), new BigDecimal(mrp), new BigDecimal("7.5000"), null, NOW);
    }

    private void ready(ImportRow row) {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(guard.require(caller, STORE_ID, StoreCapability.POST_OPENING_STOCK)).thenReturn(store);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(store));
        when(imports.findForUpdate(TENANT, STORE_ID, IMPORT_ID)).thenReturn(Optional.of(row));
        when(imports.find(TENANT, STORE_ID, IMPORT_ID)).thenReturn(Optional.of(row));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    private PharmacyViews.OpeningStockImport post() {
        return service.post(TENANT, USER, STORE_ID.toString(), IMPORT_ID.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.ClientKeyInput(KEY)));
    }

    @Test
    void anApprovedImportIsPostedInTheLockOrderAndTheOneUpdateComesAfterTheLedger() {
        ready(importRow(ImportStatus.APPROVED, null));
        UUID batchId = UUID.randomUUID();
        PostingLine ok = line(1, "B1", LocalDate.of(2028, 3, 31), "10.00", 20);
        PostingLine expired = line(2, "OLD", LocalDate.of(2026, 10, 31), "5.00", 10);
        when(imports.postingLines(TENANT, IMPORT_ID)).thenReturn(List.of(ok, expired));
        when(stock.findBatchForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(batch(batchId, "10.00")));
        when(stock.findBatchForUpdate(TENANT, PRODUCT, "OLD", expired.expiryDate()))
                .thenReturn(Optional.of(new BatchRow(UUID.randomUUID(), PRODUCT, "OLD", expired.expiryDate(),
                        new BigDecimal("5.00"), null, null, NOW)));
        when(imports.markPosted(TENANT, IMPORT_ID, ME)).thenReturn(true);

        post();

        InOrder order = Mockito.inOrder(clients, stores, imports, stock, posting);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyPostOpeningStock"), eq(IMPORT_ID));
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(imports).findForUpdate(TENANT, STORE_ID, IMPORT_ID);
        order.verify(stock, Mockito.atLeastOnce()).ensureBatch(eq(TENANT), eq(UNIT), eq(PRODUCT), any(), any(), any(), any(),
                eq("OPENING_STOCK"), eq(ME));
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        order.verify(posting).post(any(StockPosting.Actor.class), moves.capture());
        order.verify(imports).markPosted(TENANT, IMPORT_ID, ME);
        assertThat(moves.getValue()).extracting(StockPosting.Line::state)
                .containsExactly(StockState.AVAILABLE, StockState.QUARANTINE);
        assertThat(moves.getValue()).extracting(StockPosting.Line::reasonKey).containsExactly(null, "damage.expired");
        assertThat(moves.getValue()).extracting(StockPosting.Line::txnType).containsOnly("OPENING_STOCK");
        assertThat(moves.getValue()).extracting(StockPosting.Line::delta).containsExactly(20, 10);
        verify(stock).raiseStatementTimeout(60);
        verify(events).insert(eq(TENANT), eq(UNIT), eq("OPENING_STOCK_IMPORT"), eq(IMPORT_ID), eq("POSTED"), eq(STORE_ID), eq(ME),
                any(), any(), any(), any(), any());
        verify(audit).record(eq("PHARMACY_OPENING_STOCK_POSTED"), any(), eq(IMPORT_ID), any(), any());
    }

    @Test
    void anImportThatIsNotApprovedIs409NotApprovedAndNothingChanges() {
        for (ImportStatus status : new ImportStatus[] {ImportStatus.DRAFT, ImportStatus.WAITING_APPROVAL, ImportStatus.REJECTED,
                ImportStatus.REPLACED, ImportStatus.NOT_ACCEPTED}) {
            ready(importRow(status, null));

            assertThatThrownBy(this::post).as(status.name()).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                assertThat(e.slug()).isEqualTo("not-approved");
                assertThat(e.getMessage()).isEqualTo("This import is not approved.");
            });
        }
        verify(posting, never()).post(any(), any());
        verify(imports, never()).markPosted(any(), any(), any());
    }

    @Test
    void anImportAlreadyPostedIs409AlreadyPostedWithTheTimeAndPostsNothingTwice() {
        Instant postedAt = Instant.parse("2026-11-19T05:00:00Z");
        ready(importRow(ImportStatus.APPROVED, postedAt));

        assertThatThrownBy(this::post).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("already-posted");
            assertThat(e.extensions()).containsEntry("postedAt", postedAt);
            assertThat(e.getMessage()).isEqualTo("This import was already posted.");
        });
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aBatchWithAnotherMrpStopsTheWholePostingAndListsTheRowNumbers() {
        ready(importRow(ImportStatus.APPROVED, null));
        PostingLine clash = line(7, "B1", LocalDate.of(2028, 3, 31), "12.00", 5);
        when(imports.postingLines(TENANT, IMPORT_ID)).thenReturn(List.of(clash));
        when(stock.findBatchForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(batch(UUID.randomUUID(), "10.00")));

        assertThatThrownBy(this::post).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("batch-mrp-mismatch");
            assertThat(e.extensions().get("lines")).isEqualTo(List.of(
                    new PharmacyProblemException.FailingLine(clash.lineId(), 7, PRODUCT, null)));
        });
        verify(posting, never()).post(any(), any());
        verify(imports, never()).markPosted(any(), any(), any());
    }

    @Test
    void theSameKeyOfTheSameCallerForTheSameImportAnswersTheCurrentStateAndWritesNothing() {
        ready(importRow(ImportStatus.APPROVED, Instant.parse("2026-11-19T05:00:00Z")));
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyPostOpeningStock", IMPORT_ID)));

        post();

        verify(posting, never()).post(any(), any());
        verify(stores, never()).findForUpdate(any(), any());
        verify(clients, never()).take(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aKeyOfAnotherCallerOrUsedForAnotherThingIs409ClientKeyReused() {
        ready(importRow(ImportStatus.APPROVED, null));
        when(clients.heldByAnyone(TENANT, KEY)).thenReturn(true);
        assertThatThrownBy(this::post).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));

        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyCreateStockAdjustment",
                UUID.randomUUID())));
        assertThatThrownBy(this::post).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aMissingClientKeyIs400ValidationAndTheStoreIsGuardedBeforeIt() {
        ready(importRow(ImportStatus.APPROVED, null));

        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), IMPORT_ID.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.ClientKeyInput(null)))).isInstanceOf(InvalidFieldsException.class);
        InOrder order = Mockito.inOrder(gate, guard);
        order.verify(gate).require(TENANT, USER, AccessLevel.PHARMACY_ACCESS);
        order.verify(guard).require(caller, STORE_ID, StoreCapability.POST_OPENING_STOCK);
    }

    @Test
    void thePreviewListsExpiredLinesAndMrpClashesAndSaysWhyItCannotPost() {
        ImportRow approved = importRow(ImportStatus.APPROVED, null);
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(guard.require(caller, STORE_ID, StoreCapability.POST_OPENING_STOCK)).thenReturn(store);
        when(imports.find(TENANT, STORE_ID, IMPORT_ID)).thenReturn(Optional.of(approved));
        when(team.findAll(any(), any())).thenReturn(java.util.Map.of());
        PostingLine expired = line(1, "OLD", LocalDate.of(2026, 10, 31), "5.00", 10);
        PostingLine clash = line(2, "B1", LocalDate.of(2028, 3, 31), "12.00", 5);
        when(imports.postingLines(TENANT, IMPORT_ID)).thenReturn(List.of(expired, clash));
        when(stock.findBatchByIdentity(TENANT, PRODUCT, "OLD", expired.expiryDate())).thenReturn(Optional.empty());
        when(stock.findBatchByIdentity(TENANT, PRODUCT, "B1", clash.expiryDate())).thenReturn(Optional.of(batch(UUID.randomUUID(), "10.00")));

        StockViews.PostingPreview preview = service.preview(TENANT, USER, STORE_ID.toString(), IMPORT_ID.toString());

        assertThat(preview.expiredLines()).extracting(StockViews.ExpiredLine::rowNo).containsExactly(1);
        assertThat(preview.mrpMismatchLines()).extracting(StockViews.MrpMismatchLine::rowNo).containsExactly(2);
        assertThat(preview.mrpMismatchLines().get(0).fileMrp()).isEqualTo("12.00");
        assertThat(preview.mrpMismatchLines().get(0).batchMrp()).isEqualTo("10.00");
        assertThat(preview.canPost()).isFalse();
        assertThat(preview.postLockReason()).isEqualTo("MRP_MISMATCH");
        verify(posting, never()).post(any(), any());
    }

    @Test
    void thePreviewOfAnImportThatIsNotApprovedOrAlreadyPostedSaysSo() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(guard.require(caller, STORE_ID, StoreCapability.POST_OPENING_STOCK)).thenReturn(store);
        when(team.findAll(any(), any())).thenReturn(java.util.Map.of());
        when(imports.find(TENANT, STORE_ID, IMPORT_ID)).thenReturn(Optional.of(importRow(ImportStatus.WAITING_APPROVAL, null)));
        assertThat(service.preview(TENANT, USER, STORE_ID.toString(), IMPORT_ID.toString()).postLockReason())
                .isEqualTo("NOT_APPROVED");

        when(imports.find(TENANT, STORE_ID, IMPORT_ID))
                .thenReturn(Optional.of(importRow(ImportStatus.APPROVED, Instant.parse("2026-11-19T05:00:00Z"))));
        StockViews.PostingPreview posted = service.preview(TENANT, USER, STORE_ID.toString(), IMPORT_ID.toString());
        assertThat(posted.postLockReason()).isEqualTo("ALREADY_POSTED");
        assertThat(posted.canPost()).isFalse();
    }

    @Test
    void theUserIdOfTheCallerNeverReachesAWriterWhenAnImportIsPosted() {
        ready(importRow(ImportStatus.APPROVED, null));
        PostingLine ok = line(1, "B1", LocalDate.of(2028, 3, 31), "10.00", 20);
        when(imports.postingLines(TENANT, IMPORT_ID)).thenReturn(List.of(ok));
        when(stock.findBatchForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(batch(UUID.randomUUID(), "10.00")));
        when(imports.markPosted(TENANT, IMPORT_ID, ME)).thenReturn(true);

        post();

        verify(imports).markPosted(TENANT, IMPORT_ID, ME);
        UserIdNeverWritten.assertNone(USER, stores, imports, stock, posting, clients, events, audit);
    }

    /**
     * Review m-5 (migration 1720, DEC-077 L9): the posting locks the store row {@code FOR UPDATE} and reads its status again, as a
     * switch-off does, so a store switched off between the guard and the lock answers 404 and nothing is written.
     */
    @Test
    void aStoreSwitchedOffBetweenTheGuardAndTheLockIsNotFoundAndNothingIsWritten() {
        ready(importRow(ImportStatus.APPROVED, null));
        Store off = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, false, 1);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(off));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::post)
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);

        Mockito.verifyNoInteractions(posting);
        Mockito.verify(imports, Mockito.never()).findForUpdate(any(), any(), any());
        Mockito.verify(imports, Mockito.never()).markPosted(any(), any(), any());
    }
}
