package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchView;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rule 24 on the stock reads (docs/api/pharmacy.yaml 0.3.6): while the store has an open audit, a caller who can count but not review
 * gets null for every quantity of an audited product and for the store's value; the rows are still listed; the HOD sees the numbers.
 */
class StockReadServiceBlindTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID AUDITED = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID BATCH2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2028, 3, 31);

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final StockAuditRepository audits = mock(StockAuditRepository.class);
    private final StockReadService service = new StockReadService(gate, guard, stores, products, stock, posting,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), new BlindCount(audits));
    private final Store store = new Store(STORE, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, UUID.randomUUID(), false, true, true,
            true, 0);
    private PharmacyCaller caller;

    private PharmacyCaller callerAs(String teamRole, StoreRole role) {
        List<PharmacyCaller.Mapping> mappings = role == null ? List.of()
                : List.of(new PharmacyCaller.Mapping(store, null, role, StoreRules.access(teamRole, role, false)));
        return new PharmacyCaller(TENANT, USER, ME, "Test Person", teamRole, mappings);
    }

    @BeforeEach
    void ready() {
        caller = callerAs("STORE_STAFF", StoreRole.STORE_USER);
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(STORE), eq(StoreCapability.READ_STOCK))).thenReturn(store);
        when(audits.openAuditProducts(TENANT, STORE)).thenReturn(Set.of(AUDITED));
        when(stock.overview(eq(TENANT), eq(STORE), any())).thenReturn(new StockRepository.Overview(2, 2, 123_400, 1, 0, 0, 0, 1, 0));
        when(stock.batches(eq(TENANT), eq(STORE), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new StockRepository.BatchSlice(List.of(view(AUDITED, 17), view(OTHER, 9)), 2));
        when(stock.lowStock(eq(TENANT), eq(STORE), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new StockRepository.LowStockSlice(List.of(
                        low(AUDITED, 4), low(OTHER, 3)), 2));
        when(stock.availableOfProduct(TENANT, STORE, AUDITED)).thenReturn(List.of(new Bal(UUID.randomUUID(), STORE, AUDITED, BATCH,
                StockState.AVAILABLE, 17, "B1", EXPIRY, NOW, new BigDecimal("10.00"), new BigDecimal("4.5000")),
                new Bal(UUID.randomUUID(), STORE, AUDITED, BATCH2, StockState.AVAILABLE, 3, "B2", EXPIRY, NOW,
                        new BigDecimal("10.00"), new BigDecimal("4.5000"))));
        when(stock.lowStock(eq(TENANT), eq(STORE), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), eq(Set.of(AUDITED)))).thenReturn(new StockRepository.LowStockSlice(
                        List.of(low(OTHER, 3)), 1));
        when(posting.pick(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(new Fefo.Result(List.of(new Fefo.Pick(BATCH, 5),
                new Fefo.Pick(BATCH2, 3)), 17, 0));
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, AUDITED, "P-1", "Test Paracetamol 500 mg (fake)", null,
                false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), "NONE", false,
                "ACTIVE", false, null);
        when(products.find(TENANT, AUDITED)).thenReturn(Optional.of(new ProductRepository.Full(row, true, true, null, true, null,
                true, 0, ME)));
        when(stock.statesOfProduct(eq(TENANT), eq(AUDITED), any())).thenReturn(List.of(new StockRepository.StateRow(STORE, 17, 2, 1,
                3, 10)));
    }

    private static BatchView view(UUID product, int qty) {
        return new BatchView(product, "P", "Test Product (fake)", "500 mg", "Tablet", "NONE", "Strip", "Tablet", 10, UUID.randomUUID(),
                "B1", EXPIRY, StockState.AVAILABLE, qty, 1, new BigDecimal("10.00"), new BigDecimal("4.5000"), null, null, null,
                false, new BigDecimal("12"));
    }

    private static StockRepository.LowStockRow low(UUID product, int available) {
        return new StockRepository.LowStockRow(product, "P", "Test Product (fake)", "500 mg", "Tablet", "NONE", "Strip", "Tablet", 10,
                available, 5, new BigDecimal("12"));
    }

    @Test
    void theOverviewValueIsNullForABlindCallerAndRealForTheHod() {
        assertThat(service.overview(TENANT, USER, STORE.toString()).stockValuePaise()).isNull();
        assertThat(service.overview(TENANT, USER, STORE.toString()).skuCount()).as("counts of products stay").isEqualTo(2);

        caller = callerAs("NURSE", StoreRole.APPROVER);
        assertThat(service.overview(TENANT, USER, STORE.toString()).stockValuePaise()).isEqualTo(123_400L);
    }

    @Test
    void onlyTheRowsOfAuditedProductsLoseTheirQuantityAndEveryRowIsStillListed() {
        StockViews.BatchPage page = service.batches(TENANT, USER, STORE.toString(), null, null, null, null, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).extracting(r -> r.qty()).containsExactly(null, 9);
        assertThat(page.items().get(0).statusChip()).as("chips and rates stay").isNotEmpty();
    }

    @Test
    void theLowStockListAndThePillLeaveAuditedProductsOutForABlindCallerAndKeepThemForTheHod() {
        StockViews.LowStockPage page = service.lowStock(TENANT, USER, STORE.toString(), null, null);

        assertThat(page.items()).extracting(i -> i.product().productId()).containsExactly(OTHER);
        assertThat(page.total()).isEqualTo(1);
        assertThat(service.overview(TENANT, USER, STORE.toString()).pills().lowStock())
                .as("the audited product is not counted").isEqualTo(1);

        caller = callerAs("NURSE", StoreRole.APPROVER);
        assertThat(service.lowStock(TENANT, USER, STORE.toString(), null, null).items())
                .extracting(StockViews.LowStockItem::available).containsExactly(4, 3);
    }

    @Test
    void theFefoPickStillNamesTheBatchesButAnswersNoQuantityForAnAuditedProduct() {
        StockViews.FefoPick pick = service.fefoPick(TENANT, USER, STORE.toString(), AUDITED.toString(), "5");

        assertThat(pick.availableQty()).isNull();
        assertThat(pick.shortBy()).isNull();
        assertThat(pick.batches()).as("only the first FEFO batch: the count of batches must not reveal a quantity").hasSize(1);
        assertThat(pick.batches().get(0).quantity()).isNull();
        assertThat(pick.batches().get(0).batch().batchNo()).isEqualTo("B1");

        caller = callerAs("NURSE", StoreRole.APPROVER);
        StockViews.FefoPick open = service.fefoPick(TENANT, USER, STORE.toString(), AUDITED.toString(), "5");
        assertThat(open.availableQty()).isEqualTo(17);
        assertThat(open.batches()).hasSize(2);
        assertThat(open.batches().get(0).quantity()).isEqualTo(5);
    }

    @Test
    void theStoreRowOfTheByStateReadHidesTheFourQuantitiesAndTheTotalRowHidesThemToo() {
        StockViews.ProductStockByStore blind = service.productMyStore(TENANT, USER, STORE.toString(), AUDITED.toString());

        StockViews.StoreStateRow row = blind.rows().get(0);
        assertThat(row.available()).isNull();
        assertThat(row.inTransit()).isNull();
        assertThat(row.pendingReturn()).isNull();
        assertThat(row.quarantine()).isNull();
        assertThat(row.reorderLevel()).as("settings and status stay").isEqualTo(10);
        assertThat(row.status()).as("the status compares the hidden quantity with the reorder level").isNull();
        assertThat(blind.total().available()).isNull();
        assertThat(blind.empty()).isFalse();

        caller = callerAs("OWNER", null);
        when(guard.require(any(), eq(STORE), eq(StoreCapability.READ_STOCK))).thenReturn(store);
        StockViews.ProductStockByStore open = service.productMyStore(TENANT, USER, STORE.toString(), AUDITED.toString());
        assertThat(open.rows().get(0).available()).isEqualTo(17);
        assertThat(open.total().quarantine()).isEqualTo(3);
    }
}
