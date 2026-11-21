package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-19: licence status, pharmacist registrations (owner only, one per member) and the audit-log feed. */
class ComplianceServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final SaleWorld w = new SaleWorld();
    private final ComplianceRepository repository = mock(ComplianceRepository.class);
    private final ComplianceService service = new ComplianceService(w.f.gate, repository, w.f.unitRepository,
            w.f.storeRepository, w.f.team, w.support, w.reasons, w.clients, w.f.auditLog, w.f.time, w.blind);
    private final List<ComplianceRepository.Registration> saved = new ArrayList<>();

    ComplianceServiceTest() {
        when(repository.registrations(any())).thenAnswer(inv -> List.copyOf(saved));
        when(repository.registration(any(), any())).thenAnswer(inv -> saved.stream().filter(r -> r.id().equals(inv.getArgument(1)))
                .findFirst());
        when(repository.registrationOfMember(any(), any())).thenAnswer(inv -> saved.stream()
                .filter(r -> r.membershipId().equals(inv.getArgument(1))).findFirst());
        doAnswer(inv -> {
            saved.add(inv.getArgument(1));
            return null;
        }).when(repository).insertRegistration(any(), any(), any());
        when(repository.scheduleXLicence(any())).thenReturn(Optional.empty());
    }

    @Test
    void theStatusIsDueWithinSixtyDaysValidBeyondExpiredAfterAndNoDateWithout() {
        assertThat(ComplianceService.status(null, TODAY).state()).isEqualTo("NO_DATE");
        assertThat(ComplianceService.status(TODAY.minusDays(1), TODAY).state()).isEqualTo("EXPIRED");
        assertThat(ComplianceService.status(TODAY, TODAY)).isEqualTo(new SalesReturnViews.LicenceStatus("DUE", 0));
        assertThat(ComplianceService.status(TODAY.plusDays(60), TODAY)).isEqualTo(new SalesReturnViews.LicenceStatus("DUE", 60));
        assertThat(ComplianceService.status(TODAY.plusDays(61), TODAY)).isEqualTo(new SalesReturnViews.LicenceStatus("VALID", 61));
    }

    @Test
    void theOwnerAndAPharmacistReadTheLicencesAndACashierIsRefused() {
        SalesReturnViews.Licences owner = service.licences(SaleWorld.TENANT, w.f.owner.userId());
        assertThat(owner.warningDays()).isEqualTo(60);
        assertThat(owner.rows()).extracting(SalesReturnViews.LicenceRow::kind).containsExactly("RETAIL_DRUG_LICENCE",
                "GST_REGISTRATION");
        assertThat(service.licences(SaleWorld.TENANT, w.f.pharmacist.userId()).rows()).hasSize(2);
        assertThatThrownBy(() -> service.licences(SaleWorld.TENANT, w.cashier.userId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aScheduleXLicenceShowsOnlyWhenEnteredAndAnExpiredOneIsListed() {
        when(repository.scheduleXLicence(any())).thenReturn(Optional.of(new ComplianceRepository.ScheduleXLicence("X-1",
                TODAY.minusDays(3))));

        SalesReturnViews.Licences licences = service.licences(SaleWorld.TENANT, w.f.owner.userId());

        assertThat(licences.rows()).extracting(SalesReturnViews.LicenceRow::kind).contains("SCHEDULE_X_LICENCE");
        assertThat(licences.expired()).containsExactly("Schedule X licence (20F / 21F)");
    }

    @Test
    void theOwnerAddsARegistrationForAPharmacistOnceAndTheAuditHoldsNoName() {
        UUID member = w.f.pharmacist.member().membershipId();
        SaleInputs.PharmacistRegistrationCreate in = new SaleInputs.PharmacistRegistrationCreate(UUID.randomUUID(), member,
                "PCI-1234", LocalDate.of(2028, 3, 31));

        PharmacyViews.Outcome<SalesReturnViews.PharmacistRegistration> out = service.add(SaleWorld.TENANT, w.f.owner.userId(),
                SaleWorld.body(in));

        assertThat(out.created()).isTrue();
        assertThat(out.view().registrationNo()).isEqualTo("PCI-1234");
        assertThat(out.view().member().name()).isEqualTo("Test Pharmacist One");
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_PHARMACIST_REGISTRATION_ADDED");
        assertThat(w.f.audit.toString()).doesNotContain("Test Pharmacist One");
        assertThat(service.licences(SaleWorld.TENANT, w.f.owner.userId()).rows()).extracting(SalesReturnViews.LicenceRow::kind)
                .contains("PHARMACIST");

        SaleInputs.PharmacistRegistrationCreate again = new SaleInputs.PharmacistRegistrationCreate(UUID.randomUUID(), member,
                "PCI-9", null);
        assertThatThrownBy(() -> service.add(SaleWorld.TENANT, w.f.owner.userId(), SaleWorld.body(again)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("already-registered");
                });
    }

    @Test
    void onlyAnActivePharmacistMemberCanBeRegisteredAndOnlyTheOwnerMayAdd() {
        SaleInputs.PharmacistRegistrationCreate notPharmacist = new SaleInputs.PharmacistRegistrationCreate(UUID.randomUUID(),
                w.cashier.member().membershipId(), "PCI-1", null);
        assertThatThrownBy(() -> service.add(SaleWorld.TENANT, w.f.owner.userId(), SaleWorld.body(notPharmacist)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("Choose a pharmacist."));
        assertThatThrownBy(() -> service.add(SaleWorld.TENANT, w.f.pharmacist.userId(), SaleWorld.body(notPharmacist)))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, org.mockito.Mockito.never()).insertRegistration(any(), any(), any());
    }

    @Test
    void anUpdateWithAnOldVersionAnswersSettingsChangedWithTheCurrentRecord() {
        UUID id = UUID.randomUUID();
        saved.add(new ComplianceRepository.Registration(id, w.unitId(), w.f.pharmacist.member().membershipId(), "PCI-1", null, 3L));

        assertThatThrownBy(() -> service.update(SaleWorld.TENANT, w.f.owner.userId(), id.toString(), SaleWorld.body(
                new SaleInputs.PharmacistRegistrationUpdate(BigDecimal.ZERO, "PCI-2", null)))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("settings-changed"));

        when(repository.updateRegistration(any(), eq(id), eq(3L), eq("PCI-2"), any(), any())).thenAnswer(inv -> {
            saved.set(0, new ComplianceRepository.Registration(id, w.unitId(), w.f.pharmacist.member().membershipId(), "PCI-2",
                    null, 4L));
            return true;
        });
        SalesReturnViews.PharmacistRegistration out = service.update(SaleWorld.TENANT, w.f.owner.userId(), id.toString(),
                SaleWorld.body(new SaleInputs.PharmacistRegistrationUpdate(BigDecimal.valueOf(3), "PCI-2", null)));

        assertThat(out.registrationNo()).isEqualTo("PCI-2");
        assertThat(out.version()).isEqualTo(4L);
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_PHARMACIST_REGISTRATION_CHANGED");
    }

    @Test
    void theFeedBuildsSentencesFromNumbersAndLabelsOnly() {
        Map<String, String> labels = Map.of("allergy-override.confirmed", "Prescriber confirmed", "stock-adjustment.breakage",
                "Breakage");
        ComplianceRepository.ActivityEvent allergy = new ComplianceRepository.ActivityEvent(Instant.now(), "ALLERGY_OVERRIDE", "BILL",
                "SB/26-27/018422", null, "ALLERGY_OVERRIDE", null, null, null, "allergy-override.confirmed", null, null);
        ComplianceRepository.ActivityEvent adjusted = new ComplianceRepository.ActivityEvent(Instant.now(), "STOCK_ADJUSTED",
                "STOCK_ADJUSTMENT", "SA/26-27/000004", null, "POSTED", null, null, null, "stock-adjustment.breakage", 60, 58);
        ComplianceRepository.ActivityEvent requested = new ComplianceRepository.ActivityEvent(Instant.now(), "DISCOUNT_REQUESTED",
                "SALE_DISCOUNT", null, new BigDecimal("15.00"), "DISCOUNT_REQUESTED", null, null, null, null, null, null);

        assertThat(ComplianceService.sentence(allergy, labels, "Main Pharmacy")).isEqualTo(
                "overrode allergy block on bill SB/26-27/018422 · reason: Prescriber confirmed · Main Pharmacy");
        assertThat(ComplianceService.sentence(adjusted, labels, "Main Pharmacy")).isEqualTo(
                "adjusted SA/26-27/000004 60 to 58 · Breakage · Main Pharmacy");
        assertThat(ComplianceService.sentence(requested, labels, "Main Pharmacy")).isEqualTo(
                "requested 15% discount · sent for approval · Main Pharmacy");
    }

    @Test
    void theFeedLeavesTheQuantitiesOutOfAStockAdjustmentForACallerBlindAtAStoreWithAnOpenAudit() {
        ComplianceRepository.ActivityEvent adjusted = new ComplianceRepository.ActivityEvent(Instant.now(), "STOCK_ADJUSTED",
                "STOCK_ADJUSTMENT", "SA/26-27/000004", null, "POSTED", w.f.pharmacist.member().membershipId(), null, w.shop.id(),
                "stock-adjustment.breakage", 40, 38);
        when(repository.activity(any(), anyCollection(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ComplianceRepository.ActivitySlice(List.of(adjusted), 1));

        String open = service.activity(SaleWorld.TENANT, w.f.pharmacist.userId(), null, null, null, null, null, null, null)
                .items().get(0).text();
        assertThat(open).contains("40 to 38");

        when(w.audits.openAuditProducts(any(), any())).thenReturn(java.util.Set.of(UUID.randomUUID()));
        String blind = service.activity(SaleWorld.TENANT, w.f.pharmacist.userId(), null, null, null, null, null, null, null)
                .items().get(0).text();
        assertThat(blind).startsWith("adjusted SA/26-27/000004").doesNotContain("40").doesNotContain("38");

        assertThat(service.activity(SaleWorld.TENANT, w.f.owner.userId(), null, null, null, null, null, null, null)
                .items().get(0).text()).as("the owner is never blind").contains("40 to 38");
    }

    @Test
    void thePharmacistSeesOnlyTheirStoresAndAStoreOutsideThemIsNotFound() {
        when(repository.activity(any(), anyCollection(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ComplianceRepository.ActivitySlice(List.of(), 0));

        SalesReturnViews.ActivityPage page = service.activity(SaleWorld.TENANT, w.f.pharmacist.userId(), null, null, null, null,
                null, null, null);

        assertThat(page.items()).isEmpty();
        verify(repository).activity(any(), eq(java.util.Set.of(w.shop.id())), any(), any(), any(), any(), eq(25), eq(0));
        assertThatThrownBy(() -> service.activity(SaleWorld.TENANT, w.f.pharmacist.userId(), w.central.id().toString(), null, null,
                null, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.activity(SaleWorld.TENANT, w.f.owner.userId(), null, null, null, null, "NOT_A_TYPE", null,
                null)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.activity(SaleWorld.TENANT, w.cashier.userId(), null, null, null, null, null, null, null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.activity(SaleWorld.TENANT, w.f.owner.userId(), null, "2025-01-01", "2026-11-17", null, null,
                null, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("range-too-long"));
    }
}
