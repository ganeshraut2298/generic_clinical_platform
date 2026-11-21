package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.integration.NoOpPatientSearchPort;
import com.sahaj.pharmacy.integration.PatientSearchPort;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-2, TP-1: the patient of a bill, through the pharmacy-owned search port and the existing patient.api reads. */
class SalePatientServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final String shop = w.shop.id().toString();
    private final UUID patientId = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

    private SalePatientService with(PatientSearchPort port) {
        return new SalePatientService(w.f.gate, w.f.guard, w.support, port, w.customers);
    }

    private void patient(String mobile) {
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientLookup.mobilesOf(any(), any())).thenReturn(mobile == null ? Map.of() : Map.of(patientId, mobile));
    }

    private void allergy(String text) {
        when(w.patientClinical.find(any(), eq(patientId))).thenReturn(Optional.of(new PatientClinicalPort.PatientClinical(
                patientId, "UHID-1", "Test Patient One", 34, "F", text)));
    }

    @Test
    void theNoOpSearchAnswers503PatientsUnreachableSoTheCounterFallsBackToAWalkIn() {
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).search(SaleWorld.TENANT, w.cashier.userId(), shop, "Test"))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.slug()).isEqualTo("patients-unreachable");
                    assertThat(e.getMessage()).isEqualTo(
                            "Patient records are not reachable. You can continue as a walk-in and link the patient later.");
                });
    }

    @Test
    void aSearchNeedsIntegratedModeAndTwoToEightyCharacters() {
        w.f.unitRepository.updateSettings(SaleWorld.TENANT, w.unitId(), 0L, null, null, null, IntegrationMode.STANDALONE,
                w.f.owner.userId());
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).search(SaleWorld.TENANT, w.cashier.userId(), shop, "Test"))
                .isInstanceOf(IntegrationOffException.class);
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).search(SaleWorld.TENANT, w.cashier.userId(), shop, "T"))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aConnectedSearchAnswersNameAgeSexUhidAndOnlyTheMaskedMobile() {
        patient("9876543214");

        SaleViews.SalePatientList list = with((tenant, text, limit) -> List.of(new PatientSearchPort.Hit(patientId)))
                .search(SaleWorld.TENANT, w.cashier.userId(), shop, "Test");

        assertThat(list.items()).hasSize(1);
        SaleViews.SalePatientRow row = list.items().get(0);
        assertThat(row.name()).isEqualTo("Test Patient One");
        assertThat(row.uhid()).isEqualTo("UHID-1");
        assertThat(row.maskedMobile()).isEqualTo("+91 98XXX XX214");
        verify(w.patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void thePatientCardCarriesTheAllergyAsRecordedAndWritesOneRecordAccessEntry() {
        patient("9876543214");
        allergy("Penicillin");
        CustomerRepository.Row account = new CustomerRepository.Row(UUID.randomUUID(), w.unitId(), "PATIENT", patientId, null,
                null, null, 500_000, 120_000, 0, "ACTIVE", java.time.Instant.parse("2026-11-01T00:00:00Z"), 0L);
        when(w.customers.findByPatient(any(), eq(patientId))).thenReturn(Optional.of(account));

        SaleViews.SalePatient card = with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.cashier.userId(), shop,
                patientId.toString());

        assertThat(card.allergy().status()).isEqualTo("RECORDED");
        assertThat(card.allergy().text()).isEqualTo("Penicillin");
        assertThat(card.allergy().classesNotChecked()).isTrue();
        assertThat(card.maskedMobile()).isEqualTo("+91 98XXX XX214");
        assertThat(card.abhaLinked()).isNull();
        assertThat(card.account().creditLimitPaise()).isEqualTo(500_000L);
        assertThat(card.account().over60DaysPaise()).isNull();
        verify(w.patientAccess, times(1)).recordOpened(SaleWorld.TENANT, patientId, w.cashier.userId());
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void noAllergyAndAFailedClinicalReadAreToldApart() {
        patient(null);
        allergy("NKDA");
        assertThat(with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, patientId.toString())
                .allergy().status()).isEqualTo("NONE_RECORDED");

        when(w.patientClinical.find(any(), eq(patientId))).thenThrow(new IllegalStateException("clinical down"));
        SaleViews.SalePatient failed = with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop,
                patientId.toString());
        assertThat(failed.allergy().status()).isEqualTo("NOT_CHECKED");
        assertThat(failed.allergy().text()).isNull();
        assertThat(failed.account()).isNull();
    }

    @Test
    void anUnknownPatientIs404AndAFailingPortIs503() {
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of());
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.cashier.userId(), shop,
                patientId.toString())).isInstanceOf(NotFoundException.class);

        when(w.patientLookup.findAllCurrent(any(), any())).thenThrow(new IllegalStateException("port down"));
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.cashier.userId(), shop,
                patientId.toString())).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("patients-unreachable"));
    }

    @Test
    void aStoreThatDoesNotAllowSaleAndAnUnmappedPersonAreRefusedBeforeAnyRead() {
        PharmacyViews.Store ward = w.f.createStore("Test Ward Store", "WS", com.sahaj.pharmacy.domain.StoreType.SUB_STORE);
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.f.owner.userId(),
                ward.id().toString(), patientId.toString())).isInstanceOf(PharmacyProblemException.class);
        assertThatThrownBy(() -> with(new NoOpPatientSearchPort()).get(SaleWorld.TENANT, w.f.nurse.userId(), shop,
                patientId.toString())).isInstanceOf(com.sahaj.platform.api.ForbiddenException.class);
        verify(w.patientLookup, never()).findAllCurrent(any(), any());
    }
}
