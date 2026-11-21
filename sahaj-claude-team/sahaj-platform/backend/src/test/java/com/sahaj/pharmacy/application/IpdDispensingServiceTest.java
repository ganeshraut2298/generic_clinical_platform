package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.IpdDispensingService.Refusal;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.pharmacy.integration.IpdChargePort;
import com.sahaj.pharmacy.integration.NoOpIpdChargePort;
import com.sahaj.pharmacy.integration.NoOpIpdOrderSource;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-13 to 80-17: the IPD dispensing operations behind the no-op ports TP-4 and TP-5 (nothing is written). */
class IpdDispensingServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final SalesReturnService salesReturns = mock(SalesReturnService.class);
    private final IpdDispensingService service = new IpdDispensingService(w.f.gate, w.f.guard, w.support,
            new NoOpIpdOrderSource(), new NoOpIpdChargePort(), salesReturns);
    private final String shop = w.shop.id().toString();

    private static void problem(Runnable call, int status, String slug) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.slug()).isEqualTo(slug);
        });
    }

    @Test
    void theQueueIsEmptyAndNotConnectedAndTheOwnerMayReadItButACashierMayNot() {
        for (UUID reader : List.of(w.f.pharmacist.userId(), w.f.owner.userId())) {
            IpdViews.IpdQueuePage page = service.queue(SaleWorld.TENANT, reader, shop, "STAT", null, null);
            assertThat(page.connected()).isFalse();
            assertThat(page.items()).isEmpty();
            assertThat(page.counts().discharge()).isNull();
            assertThat(page.refreshAfterSeconds()).isEqualTo(30);
            assertThat(page.size()).isEqualTo(25);
        }
        assertThatThrownBy(() -> service.queue(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> service.queue(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "NIGHT", null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.queue(SaleWorld.TENANT, w.f.pharmacist.userId(), w.central.id().toString(), null, null, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void theIssueListIsAnEmptyPageAndItsFiltersAreChecked() {
        IpdViews.IpdIssuePage page = service.issues(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, "CHARGE_PENDING",
                "2026-11-17", "0", "10");

        assertThat(page.items()).isEmpty();
        assertThat(page.size()).isEqualTo(10);
        assertThatThrownBy(() -> service.issues(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, "PAID", null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.issues(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, null, "17-11-2026", null,
                null)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.issues(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "not-a-uuid", null, null, null,
                null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theReturnableSearchNeedsTwoToEightyCharactersAndAnswersNoAdmissions() {
        assertThat(service.returnable(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "IP-1").items()).isEmpty();
        assertThatThrownBy(() -> service.returnable(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "x"))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.returnable(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.returnable(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "x".repeat(81)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theDischargeLaneIsHiddenWhileThePortDoesNotReportDischarges() {
        IpdViews.DischargeList list = service.discharges(SaleWorld.TENANT, w.f.pharmacist.userId(), shop);

        assertThat(list.connected()).isFalse();
        assertThat(list.items()).isEmpty();
    }

    @Test
    void operationsThatNeedAnOrderAnswer409NotConnectedAfterTheGuard() {
        String admission = UUID.randomUUID().toString();
        problem(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, true, true,
                Refusal.NOT_CONNECTED, admission), 409, "not-connected");
        problem(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, false, true,
                Refusal.NOT_CONNECTED, admission, UUID.randomUUID().toString()), 409, "not-connected");
        problem(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, false, true,
                Refusal.NOT_CONNECTED), 409, "not-connected");
        // a cashier is refused before the port is asked, and the owner does not dispense
        assertThatThrownBy(() -> service.refuse(SaleWorld.TENANT, w.cashier.userId(), shop, StoreCapability.DISPENSE, false, true,
                Refusal.NOT_CONNECTED)).isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> service.refuse(SaleWorld.TENANT, w.f.owner.userId(), shop, StoreCapability.DISPENSE, false, true,
                Refusal.NOT_CONNECTED)).isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, false,
                true, Refusal.NOT_CONNECTED, "not-a-uuid")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aWardReturnOfNothingIssuedIsNotIssuedAndAnUnknownIssueOrReturnIsA404() {
        problem(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.RECORD_WARD_RETURN, false,
                true, Refusal.NOT_ISSUED), 409, "not-issued");
        assertThatThrownBy(() -> service.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.ACCEPT_WARD_RETURN,
                false, true, Refusal.NOT_FOUND, UUID.randomUUID().toString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.refuse(SaleWorld.TENANT, w.f.owner.userId(), shop, StoreCapability.DISPENSE, true, false,
                Refusal.NOT_FOUND, UUID.randomUUID().toString())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void waitingChargesAreForTheOwnerAndAPharmacistAndPostingIsNotConnected() {
        IpdViews.ChargesWaitingPage page = service.chargesWaiting(SaleWorld.TENANT, w.f.pharmacist.userId(), null, null, null);

        assertThat(page.connected()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.counts()).isEqualTo(new IpdViews.ChargeCounts(0, 0, 0));
        assertThat(service.chargesWaiting(SaleWorld.TENANT, w.f.owner.userId(), shop, null, null).items()).isEmpty();
        assertThatThrownBy(() -> service.chargesWaiting(SaleWorld.TENANT, w.cashier.userId(), null, null, null))
                .isInstanceOf(ForbiddenException.class);
        problem(() -> service.postWaiting(SaleWorld.TENANT, w.f.owner.userId(), null), 409, "not-connected");
        problem(() -> service.postWaiting(SaleWorld.TENANT, w.f.pharmacist.userId(), shop), 409, "not-connected");
        assertThatThrownBy(() -> service.postWaiting(SaleWorld.TENANT, w.cashier.userId(), null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aConnectedChargePortStillRefusesPostingBecauseNothingIsBuiltBehindIt() {
        IpdChargePort live = mock(IpdChargePort.class);
        when(live.connected()).thenReturn(true);
        IpdDispensingService withLive = new IpdDispensingService(w.f.gate, w.f.guard, w.support, new NoOpIpdOrderSource(), live,
                salesReturns);

        problem(() -> withLive.postWaiting(SaleWorld.TENANT, w.f.owner.userId(), null), 409, "not-connected");
    }

    @Test
    void theReturnsTabShowsTheCounterReturnsAndNoWardReturns() {
        IpdViews.ReturnsWaitingRow row = new IpdViews.ReturnsWaitingRow("SALES_RETURN", UUID.randomUUID(), "OPD counter",
                "Walk-in", "SB/26-27/000001", null, null, 2, null, "Sealed", null, 5000L, List.of("ACCEPT"));
        when(salesReturns.waitingCounterReturns(any(), any(), anyInt(), anyInt()))
                .thenReturn(new IpdViews.ReturnsWaitingSlice(List.of(row), 1));

        IpdViews.ReturnsWaitingPage page = service.returnsWaiting(SaleWorld.TENANT, w.cashier.userId(), shop, null, null);

        assertThat(page.items()).containsExactly(row);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.creditConnected()).isFalse();
        assertThat(page.size()).isEqualTo(25);
    }

    @Test
    void standaloneModeAnswersIntegrationOffBeforeAnythingElseButTheReturnsTabStillWorks() {
        UnitRepository standalone = mock(UnitRepository.class);
        when(standalone.find(any())).thenReturn(Optional.of(new Unit(UUID.randomUUID(), "U", null, null, null,
                IntegrationMode.STANDALONE, null, 0L)));
        SaleSupport support = new SaleSupport(standalone, w.patientLookup, w.patientClinical, w.patientAccess, w.f.team, w.templates,
                w.f.letterhead, w.f.time);
        IpdDispensingService off = new IpdDispensingService(w.f.gate, w.f.guard, support, new NoOpIpdOrderSource(),
                new NoOpIpdChargePort(), salesReturns);
        when(salesReturns.waitingCounterReturns(any(), any(), anyInt(), anyInt()))
                .thenReturn(new IpdViews.ReturnsWaitingSlice(List.of(), 0));

        assertThatThrownBy(() -> off.queue(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, null, null))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.discharges(SaleWorld.TENANT, w.f.pharmacist.userId(), shop))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.issues(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, null, null, null, null))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.returnable(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "IP-1"))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.chargesWaiting(SaleWorld.TENANT, w.f.owner.userId(), null, null, null))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.postWaiting(SaleWorld.TENANT, w.f.owner.userId(), null))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, false, true,
                Refusal.NOT_CONNECTED)).isInstanceOf(IntegrationOffException.class);
        assertThat(off.returnsWaiting(SaleWorld.TENANT, w.cashier.userId(), shop, null, null).items()).isEmpty();
    }
}
