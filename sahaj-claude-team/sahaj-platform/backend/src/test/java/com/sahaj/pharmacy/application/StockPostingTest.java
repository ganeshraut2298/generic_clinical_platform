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

import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.LedgerIn;
import com.sahaj.pharmacy.domain.StockState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * ADR-029 P4 and P5: every stock change goes through {@link StockPosting}: the lock order (missing destination rows inserted
 * first, then the balance rows of each store and product locked), the expiry move inside a movement, FEFO in memory, and all or
 * nothing (a refusal writes nothing).
 */
class StockPostingTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    /** 19 Nov 2026, 12:00 IST. */
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = new StockPosting(stock, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final StockPosting.Actor actor = new StockPosting.Actor(TENANT, UNIT, ME, null, null);

    private static Bal bal(UUID batch, StockState state, int qty, LocalDate expiry) {
        return new Bal(UUID.randomUUID(), STORE, PRODUCT, batch, state, qty, "B-" + batch.toString().substring(0, 4), expiry,
                NOW.minusSeconds(100), new BigDecimal("10.00"), new BigDecimal("7.5000"));
    }

    private static StockPosting.Line line(UUID batch, StockState state, int delta, String txn) {
        return new StockPosting.Line(STORE, PRODUCT, batch, state, delta, txn, null, UUID.randomUUID(), null,
                new StockPosting.Doc("OPENING_STOCK_IMPORT", UUID.randomUUID(), null, UUID.randomUUID()), 3);
    }

    private List<LedgerIn> ledger() {
        ArgumentCaptor<LedgerIn> captor = ArgumentCaptor.forClass(LedgerIn.class);
        verify(stock, Mockito.atLeast(0)).insertLedger(eq(TENANT), eq(UNIT), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void anIncreaseInsertsTheMissingRowFirstThenLocksTheStoreAndProductAndWritesBalanceThenLedger() {
        UUID batch = UUID.randomUUID();
        Bal existing = bal(batch, StockState.AVAILABLE, 4, TODAY.plusDays(200));
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of(existing));

        List<StockPosting.Applied> applied = posting.post(actor, List.of(line(batch, StockState.AVAILABLE, 6, "OPENING_STOCK")));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).balanceAfter()).isEqualTo(10);
        InOrder order = Mockito.inOrder(stock);
        order.verify(stock).ensureBalance(TENANT, UNIT, STORE, PRODUCT, batch, StockState.AVAILABLE, ME);
        order.verify(stock).lockBalances(eq(TENANT), eq(STORE), any());
        order.verify(stock).setBalance(TENANT, existing.id(), 10, ME);
        order.verify(stock).insertLedger(eq(TENANT), eq(UNIT), any(LedgerIn.class));
        LedgerIn written = ledger().get(0);
        assertThat(written.qtyIn()).isEqualTo(6);
        assertThat(written.qtyOut()).isZero();
        assertThat(written.balanceAfter()).isEqualTo(10);
        assertThat(written.actorMembershipId()).isEqualTo(ME);
        assertThat(written.txnType()).isEqualTo("OPENING_STOCK");
    }

    @Test
    void aDecreaseBelowWhatIsAvailableIsRefusedWithTheQuantityAndNothingIsWritten() {
        UUID batch = UUID.randomUUID();
        when(stock.lockBalances(eq(TENANT), eq(STORE), any()))
                .thenReturn(List.of(bal(batch, StockState.AVAILABLE, 4, TODAY.plusDays(200))));

        assertThatThrownBy(() -> posting.post(actor, List.of(line(batch, StockState.AVAILABLE, -5, "STOCK_ADJUSTMENT"))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("insufficient-stock");
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.extensions()).containsEntry("available", 4);
                });

        verify(stock, never()).setBalance(any(), any(), anyInt(), any());
        verify(stock, never()).insertLedger(any(), any(), any());
    }

    @Test
    void aDecreaseOfARowThatNeverExistedIsInsufficientStockToo() {
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of());

        assertThatThrownBy(() -> posting.post(actor, List.of(line(UUID.randomUUID(), StockState.AVAILABLE, -1, "WRITE_OFF"))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("insufficient-stock"));
        verify(stock, never()).insertLedger(any(), any(), any());
    }

    @Test
    void anExpiredAvailableRowIsMovedToQuarantineByTheSystemBeforeTheMovementRuns() {
        UUID expired = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        Bal old = bal(expired, StockState.AVAILABLE, 7, TODAY.minusDays(1));
        Bal quarantine = bal(expired, StockState.QUARANTINE, 2, TODAY.minusDays(1));
        Bal ok = bal(fresh, StockState.AVAILABLE, 9, TODAY.plusDays(60));
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of(old, quarantine, ok));

        posting.post(actor, List.of(line(fresh, StockState.AVAILABLE, -3, "STOCK_ADJUSTMENT")));

        List<LedgerIn> written = ledger();
        assertThat(written).hasSize(3);
        assertThat(written.get(0).txnType()).isEqualTo("EXPIRY_QUARANTINE");
        assertThat(written.get(0).state()).isEqualTo(StockState.AVAILABLE);
        assertThat(written.get(0).qtyOut()).isEqualTo(7);
        assertThat(written.get(0).actorMembershipId()).as("SYSTEM").isNull();
        assertThat(written.get(0).reasonKey()).isEqualTo("damage.expired");
        assertThat(written.get(1).state()).isEqualTo(StockState.QUARANTINE);
        assertThat(written.get(1).qtyIn()).isEqualTo(7);
        assertThat(written.get(1).balanceAfter()).isEqualTo(9);
        assertThat(written.get(0).movementId()).isEqualTo(written.get(1).movementId());
        assertThat(written.get(2).balanceAfter()).isEqualTo(6);
        verify(stock).setBalance(TENANT, old.id(), 0, ME);
        verify(stock).setBalance(TENANT, quarantine.id(), 9, ME);
        verify(stock).setBalance(TENANT, ok.id(), 6, ME);
    }

    @Test
    void anExpiredBatchIsNeverReceivedIntoAvailableButMayBeIntoQuarantine() {
        UUID batch = UUID.randomUUID();
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of(
                bal(batch, StockState.AVAILABLE, 0, TODAY.minusDays(3)), bal(batch, StockState.QUARANTINE, 0, TODAY.minusDays(3))));

        assertThatThrownBy(() -> posting.post(actor, List.of(line(batch, StockState.AVAILABLE, 5, "OPENING_STOCK"))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("batch-expired"));
        verify(stock, never()).insertLedger(any(), any(), any());

        List<StockPosting.Applied> applied = posting.post(actor, List.of(line(batch, StockState.QUARANTINE, 5, "OPENING_STOCK")));
        assertThat(applied.get(0).state()).isEqualTo(StockState.QUARANTINE);
        assertThat(applied.get(0).balanceAfter()).isEqualTo(5);
    }

    @Test
    void aMoveBetweenStatesIsTwoLinesWithOneMovementIdAndTheSecondLineSeesTheFirst() {
        UUID batch = UUID.randomUUID();
        UUID movement = UUID.randomUUID();
        Bal available = bal(batch, StockState.AVAILABLE, 10, TODAY.plusDays(90));
        Bal held = bal(batch, StockState.PENDING_RETURN, 1, TODAY.plusDays(90));
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of(available, held));
        StockPosting.Doc doc = new StockPosting.Doc("DEBIT_NOTE", UUID.randomUUID(), "DN-0001", null);

        posting.post(actor, List.of(
                new StockPosting.Line(STORE, PRODUCT, batch, StockState.AVAILABLE, -4, "VENDOR_RETURN_HOLD", null, movement,
                        null, doc, null),
                new StockPosting.Line(STORE, PRODUCT, batch, StockState.PENDING_RETURN, 4, "VENDOR_RETURN_HOLD", null, movement,
                        null, doc, null)));

        List<LedgerIn> written = ledger();
        assertThat(written).extracting(LedgerIn::movementId).containsOnly(movement);
        assertThat(written).extracting(LedgerIn::balanceAfter).containsExactly(6, 5);
        assertThat(written).extracting(LedgerIn::qtyOut).containsExactly(4, 0);
    }

    @Test
    void storesAreLockedInAscendingIdOneStatementEach() {
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID high = UUID.fromString("ffffffff-0000-4000-8000-000000000001");
        UUID batch = UUID.randomUUID();
        when(stock.lockBalances(any(), any(), any())).thenReturn(List.of());

        try {
            posting.post(actor, List.of(
                    new StockPosting.Line(high, PRODUCT, batch, StockState.AVAILABLE, 1, "GRN", null, UUID.randomUUID(), null,
                            null, null),
                    new StockPosting.Line(low, PRODUCT, batch, StockState.AVAILABLE, 1, "GRN", null, UUID.randomUUID(), null,
                            null, null)));
        } catch (RuntimeException ignored) {
            // the rows are not there in this fake; only the order of the lock statements is under test
        }

        InOrder order = Mockito.inOrder(stock);
        order.verify(stock).lockBalances(eq(TENANT), eq(low), any());
        order.verify(stock).lockBalances(eq(TENANT), eq(high), any());
        List<Object> none = new ArrayList<>();
        assertThat(none).isEmpty();
    }

    @Test
    void theSweepMovesEachExpiredRowAndWritesNothingWhenNothingHasExpired() {
        UUID batch = UUID.randomUUID();
        Bal expired = bal(batch, StockState.AVAILABLE, 5, TODAY.minusDays(2));
        Bal target = bal(batch, StockState.QUARANTINE, 1, TODAY.minusDays(2));
        when(stock.lockExpiredAvailable(TENANT, STORE, TODAY)).thenReturn(List.of(expired));
        when(stock.ensureBalanceLocked(TENANT, UNIT, STORE, PRODUCT, batch, StockState.QUARANTINE, ME)).thenReturn(target);

        assertThat(posting.sweepStore(TENANT, UNIT, STORE, ME)).isEqualTo(1);

        verify(stock).setBalance(TENANT, expired.id(), 0, ME);
        verify(stock).setBalance(TENANT, target.id(), 6, ME);
        assertThat(ledger()).hasSize(2).allSatisfy(l -> {
            assertThat(l.txnType()).isEqualTo("EXPIRY_QUARANTINE");
            assertThat(l.actorMembershipId()).isNull();
        });

        StockRepository quiet = mock(StockRepository.class);
        when(quiet.lockExpiredAvailable(TENANT, STORE, TODAY)).thenReturn(List.of());
        assertThat(new StockPosting(quiet, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC))).sweepStore(TENANT, UNIT, STORE, ME))
                .isZero();
        verify(quiet, never()).insertLedger(any(), any(), any());
    }

    @Test
    void theFefoPickUsesTheUsableRowsOnly() {
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();

        var result = posting.pick(List.of(bal(late, StockState.AVAILABLE, 10, TODAY.plusDays(100)),
                bal(early, StockState.AVAILABLE, 3, TODAY.plusDays(10)), bal(early, StockState.QUARANTINE, 99, TODAY.plusDays(10)),
                bal(UUID.randomUUID(), StockState.AVAILABLE, 50, TODAY.minusDays(1))), 5);

        assertThat(result.picks()).extracting(p -> p.batchId()).containsExactly(early, late);
        assertThat(result.available()).isEqualTo(13);
        assertThat(result.shortBy()).isZero();
    }

    /** SAH-79-7 and 79-8: a document with a Schedule X line and others carries the signer on that line only. */
    @Test
    void aLinesOwnSecondSignerIsWrittenOnItsLedgerLineAndTheActorsSignerIsTheFallback() {
        UUID signed = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        UUID signer = UUID.randomUUID();
        UUID fallback = UUID.randomUUID();
        when(stock.lockBalances(eq(TENANT), eq(STORE), any())).thenReturn(List.of(bal(signed, StockState.AVAILABLE, 4,
                TODAY.plusDays(200)), bal(plain, StockState.AVAILABLE, 4, TODAY.plusDays(200))));
        StockPosting.Line withSigner = new StockPosting.Line(STORE, PRODUCT, signed, StockState.AVAILABLE, 1, "GRN", null,
                UUID.randomUUID(), null, null, null, signer);

        posting.post(new StockPosting.Actor(TENANT, UNIT, ME, null, null), List.of(withSigner, line(plain, StockState.AVAILABLE, 1,
                "GRN")));
        posting.post(new StockPosting.Actor(TENANT, UNIT, ME, fallback, null), List.of(line(plain, StockState.AVAILABLE, 1, "GRN")));

        assertThat(ledger()).extracting(LedgerIn::secondSignerMembershipId).containsExactly(signer, null, fallback);
    }
}
