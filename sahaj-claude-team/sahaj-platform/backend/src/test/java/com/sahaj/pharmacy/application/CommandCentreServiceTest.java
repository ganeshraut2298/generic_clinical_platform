package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.integration.NoOpIpdOrderSource;
import com.sahaj.pharmacy.integration.NoOpOpdPrescriptionSource;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-20: the Command Center cards, who sees which, the blind count and the card that fails alone. */
class CommandCentreServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final CommandCentreRepository repository = mock(CommandCentreRepository.class);
    private final RegisterReadRepository registers = mock(RegisterReadRepository.class);
    private final CardIsolation isolation = mock(CardIsolation.class);
    private final CommandCentreService service = new CommandCentreService(w.f.gate, w.f.storeRepository, repository, w.stock,
            w.posting, registers, w.f.unitRepository, new NoOpOpdPrescriptionSource(), new NoOpIpdOrderSource(), w.support, w.blind,
            w.f.time, isolation);
    private final UUID shop = w.shop.id();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");

    CommandCentreServiceTest() {
        when(isolation.run(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        when(repository.sales(any(), anyCollection(), any(), any())).thenReturn(new CommandCentreRepository.Sales(100000, 4, 20000));
        when(repository.expiryBands(any(), anyCollection(), any())).thenReturn(List.of(
                new CommandCentreRepository.ExpiryBand(shop, product, "EXPIRED", 1, 500),
                new CommandCentreRepository.ExpiryBand(shop, product, "D0_30", 2, 1000),
                new CommandCentreRepository.ExpiryBand(shop, product, "D61_90", 1, 250)));
        when(repository.udhar(any(), any())).thenReturn(new CommandCentreRepository.Udhar(30000, 10000, true));
        when(repository.indentsWaiting(any(), anyCollection())).thenReturn(3);
        when(w.stock.overview(any(), any(), any())).thenReturn(new StockRepository.Overview(10, 12, 1000, 1, 2, 3, 0, 5, 0));
        when(registers.h1Entries(any(), any(), any(), any(), eq(false))).thenReturn(6);
        when(registers.h1Entries(any(), any(), any(), any(), eq(true))).thenReturn(2);
        when(registers.xBalances(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(registers.latestCounts(any(), any(), anyCollection())).thenReturn(java.util.Map.of());
    }

    @Test
    void aPharmacistGetsEveryCardForTheirStoreWorkedOutNow() {
        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop.toString());

        assertThat(cc.store().storeId()).isEqualTo(shop);
        assertThat(cc.todaysSales()).isEqualTo(new CommandCentreViews.TodaysSales("OK", 80000L, 4, 25000L));
        assertThat(cc.grossMargin()).isNull(); // absent until the accountant confirms the landed rates (G-13)
        assertThat(cc.nearExpiry90().batchCount()).isEqualTo(3);
        assertThat(cc.nearExpiry90().valuePaise()).isEqualTo(1250L);
        assertThat(cc.nearExpiry90().returnablePaise()).isNull();
        assertThat(cc.reorder().productCount()).isEqualTo(5);
        assertThat(cc.rxWaiting().opdConnected()).isFalse();
        assertThat(cc.rxWaiting().opdWaiting()).isNull();
        assertThat(cc.rxWaiting().indentsWaiting()).isEqualTo(3);
        assertThat(cc.udhar().duePaise()).isEqualTo(30000L);
        assertThat(cc.expiryRisk().bars()).extracting(CommandCentreViews.Bar::band).containsExactly("EXPIRED", "D0_30", "D31_60",
                "D61_90");
        assertThat(cc.expiryRisk().bars().get(2).batchCount()).isZero();
        assertThat(cc.compliance().h1().billsToday()).isEqualTo(6);
        assertThat(cc.compliance().h1().overridesToReview()).isEqualTo(2);
        assertThat(cc.compliance().scheduleX().balanced()).isTrue();
        assertThat(cc.livePrescriptions().items()).isEmpty();
        verify(w.posting).sweepStore(any(), any(), eq(shop), any());
    }

    @Test
    void aCashierGetsTodaysSalesOnly() {
        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.cashier.userId(), shop.toString());

        assertThat(cc.todaysSales().state()).isEqualTo("OK");
        assertThat(cc.nearExpiry90()).isNull();
        assertThat(cc.reorder()).isNull();
        assertThat(cc.udhar()).isNull();
        assertThat(cc.compliance()).isNull();
        assertThat(cc.rxWaiting()).isNull();
        verify(repository, never()).expiryBands(any(), anyCollection(), any());
        verify(w.posting, never()).sweepStore(any(), any(), any(), any());
    }

    @Test
    void theOwnerMayOmitTheStoreAndAnotherPersonMustNameOne() {
        CommandCentreViews.CommandCentre all = service.get(SaleWorld.TENANT, w.f.owner.userId(), null);
        assertThat(all.store()).isNull();
        assertThat(all.todaysSales().state()).isEqualTo("OK");

        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), w.central.id().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.f.owner.userId(), UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBlindCallerGetsNoValueForAnAuditedProductButTheCountsStay() {
        when(w.audits.openAuditProducts(any(), any())).thenReturn(Set.of(product));

        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop.toString());

        assertThat(cc.nearExpiry90().valuePaise()).isNull();
        assertThat(cc.nearExpiry90().batchCount()).isEqualTo(3);
        assertThat(cc.expiryRisk().bars()).allSatisfy(bar -> {
            if (bar.batchCount() > 0) {
                assertThat(bar.valuePaise()).isNull();
            }
        });
        assertThat(cc.reorder().productCount()).isEqualTo(5);
    }

    @Test
    void whenTheOwnerOmitsTheStoreNobodyIsBlind() {
        when(w.audits.openAuditProducts(any(), any())).thenReturn(Set.of(product));

        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.f.owner.userId(), null);

        assertThat(cc.nearExpiry90().valuePaise()).isEqualTo(1250L);
    }

    @Test
    void aCardThatFailsAnswersErrorAndTheOthersStillAnswer() {
        when(repository.udhar(any(), any())).thenThrow(new IllegalStateException("boom"));

        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop.toString());

        assertThat(cc.udhar().state()).isEqualTo("ERROR");
        assertThat(cc.todaysSales().state()).isEqualTo("OK");
        assertThat(cc.reorder().state()).isEqualTo("OK");
    }

    @Test
    void everyCardRunsInItsOwnIsolationSoOneFailureCannotAbortTheOthers() {
        service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop.toString());

        verify(isolation, org.mockito.Mockito.atLeast(6)).run(any());
    }

    @Test
    void noSalesTodayIsTheEmptyStateAndNoCreditLimitHidesTheUdharCard() {
        when(repository.sales(any(), anyCollection(), any(), any())).thenReturn(new CommandCentreRepository.Sales(0, 0, 0));
        when(repository.udhar(any(), any())).thenReturn(new CommandCentreRepository.Udhar(0, 0, false));

        CommandCentreViews.CommandCentre cc = service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop.toString());

        assertThat(cc.todaysSales().state()).isEqualTo("EMPTY");
        assertThat(cc.udhar()).isNull();
    }

    @Test
    void aMemberWithNoSellingStoreIsRefused() {
        PharmacyFixture.Person store = w.f.person("STORE_STAFF", "Test Store Staff", true);
        w.f.map(w.central, store, com.sahaj.pharmacy.domain.StoreRole.CENTRAL_STORE_USER);

        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, store.userId(), null)).isInstanceOf(ForbiddenException.class);
    }
}
