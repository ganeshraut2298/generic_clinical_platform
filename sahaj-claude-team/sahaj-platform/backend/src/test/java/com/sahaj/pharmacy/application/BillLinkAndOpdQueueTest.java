package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.pharmacy.integration.NoOpOpdPrescriptionSource;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-2 (link a pending bill to its patient) and SAH-80-11, 80-12 (the OPD queue behind the no-op port). */
class BillLinkAndOpdQueueTest {

    private final SaleWorld w = new SaleWorld();
    private final BillLinkService links = new BillLinkService(w.f.gate, w.f.guard, w.bills, w.assembler, w.support, w.events,
            w.f.auditLog);
    private final OpdQueueService queue = new OpdQueueService(w.f.gate, w.f.guard, w.support, new NoOpOpdPrescriptionSource());
    private final String shop = w.shop.id().toString();
    private final UUID billId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private SaleBillRepository.BillRow bill(boolean pending, long version) {
        return new SaleBillRepository.BillRow(billId, w.unitId(), w.shop.id(), "SB/26-27/000001", "UNIT", 2026, "SAVED", "WALK_IN",
                null, null, "Test Walk-in One", null, pending, false, null, null, null, null, null, null, null, null, 1000, 0, 893,
                54, 53, 0, 1000, 0, Instant.parse("2026-11-17T05:00:00Z"), w.cashier.member().membershipId(),
                w.f.pharmacist.member().membershipId(), null, null, null, null, null, 0, null, version);
    }

    private void patientExists() {
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientLookup.mobilesOf(any(), any())).thenReturn(Map.of());
    }

    // ---- link a bill ----

    @Test
    void aPharmacistLinksAPendingBillOnceAndTheAuditHoldsIdsOnly() {
        patientExists();
        when(w.bills.findForUpdate(any(), eq(billId))).thenReturn(Optional.of(bill(true, 2L)));
        when(w.bills.find(any(), eq(billId))).thenReturn(Optional.of(bill(false, 3L)));
        when(w.bills.linkPatient(any(), eq(billId), eq(2L), eq(patientId), any())).thenReturn(true);

        links.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(
                new SaleInputs.LinkPatientInput(BigDecimal.valueOf(2), patientId)));

        verify(w.bills).linkPatient(any(), eq(billId), eq(2L), eq(patientId), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_BILL_LINKED");
        assertThat(w.f.audit.toString()).doesNotContain("Test Patient One").doesNotContain("Test Walk-in One");
    }

    @Test
    void aBillThatIsNotPendingIsAlreadyLinkedAnOldVersionIsChangedAndAnUnknownPatientIs404() {
        patientExists();
        when(w.bills.findForUpdate(any(), eq(billId))).thenReturn(Optional.of(bill(false, 2L)));
        SaleInputs.LinkPatientInput in = new SaleInputs.LinkPatientInput(BigDecimal.valueOf(2), patientId);
        assertThatThrownBy(() -> links.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("link-already-set"));

        when(w.bills.findForUpdate(any(), eq(billId))).thenReturn(Optional.of(bill(true, 5L)));
        when(w.bills.find(any(), eq(billId))).thenReturn(Optional.of(bill(true, 5L)));
        assertThatThrownBy(() -> links.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));

        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of());
        assertThatThrownBy(() -> links.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(in)))
                .isInstanceOf(NotFoundException.class);
        verify(w.bills, never()).linkPatient(any(), any(), anyLong(), any(), any());
    }

    @Test
    void aCashierCannotLinkAndAMissingPatientOrVersionIs400() {
        SaleInputs.LinkPatientInput in = new SaleInputs.LinkPatientInput(BigDecimal.ZERO, patientId);
        assertThatThrownBy(() -> links.link(SaleWorld.TENANT, w.cashier.userId(), shop, billId.toString(), SaleWorld.body(in)))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> links.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(
                new SaleInputs.LinkPatientInput(null, null)))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void standaloneModeAnswersIntegrationOff() {
        UnitRepository standalone = mock(UnitRepository.class);
        when(standalone.find(any())).thenReturn(Optional.of(new Unit(UUID.randomUUID(), "U", null, null, null,
                IntegrationMode.STANDALONE, null, 0L)));
        SaleSupport support = new SaleSupport(standalone, w.patientLookup, w.patientClinical, w.patientAccess, w.f.team, w.templates,
                w.f.letterhead, w.f.time);
        BillLinkService off = new BillLinkService(w.f.gate, w.f.guard, w.bills, w.assembler, support, w.events, w.f.auditLog);

        assertThatThrownBy(() -> off.link(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(), SaleWorld.body(
                new SaleInputs.LinkPatientInput(BigDecimal.ZERO, patientId)))).isInstanceOf(IntegrationOffException.class);
    }

    // ---- the OPD queue ----

    @Test
    void theQueueAnswers200WithConnectedFalseNoRowsAndZeroCounts() {
        SaleCheckViews.OpdQueuePage page = queue.queue(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null, null);

        assertThat(page.connected()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.counts()).isEqualTo(new SaleCheckViews.OpdCounts(0, 0, 0, 0));
        assertThat(page.refreshAfterSeconds()).isEqualTo(30);
        assertThat(page.size()).isEqualTo(25);
    }

    @Test
    void theQueueChecksItsQueryValues() {
        assertThatThrownBy(() -> queue.queue(SaleWorld.TENANT, w.cashier.userId(), shop, null, "BILLED", null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> queue.queue(SaleWorld.TENANT, w.cashier.userId(), shop, "x".repeat(41), null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void everyOtherQueueOperationAnswers409NotConnectedAfterTheStoreGuardAndTheModeCheck() {
        String rx = UUID.randomUUID().toString();
        assertThatThrownBy(() -> queue.refuse(SaleWorld.TENANT, w.cashier.userId(), shop, StoreCapability.SELL, true, rx))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("not-connected");
                });
        // close the rest is a pharmacist's act: a cashier is refused before the port is asked
        assertThatThrownBy(() -> queue.refuse(SaleWorld.TENANT, w.cashier.userId(), shop, StoreCapability.SELL_SCHEDULED, true, rx))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> queue.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.SELL_SCHEDULED, true,
                rx)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("not-connected"));
        assertThatThrownBy(() -> queue.refuse(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, StoreCapability.DISPENSE, false))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("not-connected"));
        assertThatThrownBy(() -> queue.refuse(SaleWorld.TENANT, w.cashier.userId(), shop, StoreCapability.SELL, true, "not-a-uuid"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void standaloneModeAnswersIntegrationOffBeforeNotConnected() {
        UnitRepository standalone = mock(UnitRepository.class);
        when(standalone.find(any())).thenReturn(Optional.of(new Unit(UUID.randomUUID(), "U", null, null, null,
                IntegrationMode.STANDALONE, null, 0L)));
        SaleSupport support = new SaleSupport(standalone, w.patientLookup, w.patientClinical, w.patientAccess, w.f.team, w.templates,
                w.f.letterhead, w.f.time);
        OpdQueueService off = new OpdQueueService(w.f.gate, w.f.guard, support, new NoOpOpdPrescriptionSource());

        assertThatThrownBy(() -> off.queue(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null, null))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> off.refuse(SaleWorld.TENANT, w.cashier.userId(), shop, StoreCapability.SELL, true,
                UUID.randomUUID().toString())).isInstanceOf(IntegrationOffException.class);
    }
}
