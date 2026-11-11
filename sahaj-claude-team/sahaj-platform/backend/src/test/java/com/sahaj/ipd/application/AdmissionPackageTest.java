package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.PackageCopy;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.ChargeRepository.ChargeRow;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-29 (AC2 to AC5) the package copy on the admission sheet and the one bill path behind the sheet, the Admitted
 * list, the board and the tiles: the package copy of the admission row reaches {@link RunningBill}.
 */
class AdmissionPackageTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID BED = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    /** 11 Nov 2026, 11:00 AM IST: bill day 3 for an admission of 9 Nov 11:00 AM IST (midnight cut). */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-09T05:30:00Z");
    private static final PackageCopy PACKAGE = new PackageCopy("Appendicectomy", 4_500_000L, 2);
    private static final AdmissionViews.AdmissionPackage CONTRACT_PACKAGE =
            new AdmissionViews.AdmissionPackage("Appendicectomy", 4_500_000L, 2);

    private AdmissionRepository admissions;
    private IpdSettingsRepository settings;
    private ChargeRepository charges;
    private TransferRepository transfers;
    private DischargeRepository discharges;
    private AdmissionService service;
    private IpdBilling billing;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        settings = mock(IpdSettingsRepository.class);
        charges = mock(ChargeRepository.class);
        transfers = mock(TransferRepository.class);
        discharges = mock(DischargeRepository.class);
        ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
        TeamMemberPort team = mock(TeamMemberPort.class);
        PatientLookupPort patients = mock(PatientLookupPort.class);
        VitalsRepository vitals = mock(VitalsRepository.class);
        SafetyService safety = mock(SafetyService.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        billing = new IpdBilling(charges, transfers, catalog);
        service = new AdmissionService(admissions, mock(BedRepository.class), mock(WardRepository.class), settings,
                mock(OwnerGuard.class), mock(AuditLog.class), team, patients, mock(PatientAccessPort.class),
                mock(ActorDirectoryPort.class), new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), catalog,
                charges, billing, discharges, mock(EmergencyService.class), vitals,
                new AdmittedCards(mock(PatientClinicalPort.class), patients, vitals, safety),
                new CardSetups(mock(CardSetupRepository.class)), ScopeStubs.unscoped(), ScopeStubs.repository());
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.empty());
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001")));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));
        when(vitals.databaseNow()).thenReturn(NOW);
    }

    private static AdmissionRow row(String status, long deposit, Instant dischargedAt, PackageCopy pkg) {
        return new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen", "General ward", DOCTOR,
                "planned", "Self", "Chest pain", status, ADMITTED_AT, deposit, dischargedAt, pkg);
    }

    @Test
    void theSheetShowsThePackageCopyAndTheBillOfAPackageAdmission() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("ADMITTED", 1_000_000L, null, PACKAGE)));
        when(charges.findByAdmission(TENANT, ADMISSION)).thenReturn(List.of(
                new ChargeRow(ADMISSION, "cbc", "CBC", 30_000L, 1)));
        // A transfer written before the package was set in the database must not reach the package bill.
        when(transfers.findByAdmission(TENANT, ADMISSION)).thenReturn(List.of(new TransferRow(ADMISSION, "pvt", 1)));

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(detail.packageCopy()).isEqualTo(CONTRACT_PACKAGE);
        assertThat(detail.bill().lines()).extracting(RunningBill.BillLine::name).containsExactly(
                "Package · Appendicectomy (bed, nursing and surgeon for 2 days)",
                "Bed · General beyond the package", "CBC · not in package");
        assertThat(detail.bill().lines()).noneMatch(RunningBill.BillLine::segment);
        // Bill day 3, 2 included: 1 day beyond at General ₹800; CBC ₹300 at multiplier 1.00.
        assertThat(detail.bill().totalPaise()).isEqualTo(4_500_000L + 80_000L + 30_000L);
        assertThat(detail.runningBillPaise()).isEqualTo(detail.bill().totalPaise());
        assertThat(detail.dayNumber()).isEqualTo(3);
    }

    @Test
    void theDepositLowFlagOfTheSheetUsesThePackageBill() {
        // The deposit (₹10,000) is far below the package price, so a Self payer is low from the first day.
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("ADMITTED", 1_000_000L, null, PACKAGE)));

        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).depositLow()).isTrue();
    }

    @Test
    void anAdmissionWithoutAPackageHasNullPackageAndTheUsualBill() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("ADMITTED", 1_000_000L, null, null)));

        Detail detail = service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION);

        assertThat(detail.packageCopy()).isNull();
        assertThat(detail.bill().lines()).extracting(RunningBill.BillLine::name)
                .containsExactly("Bed · General", "Doctor visits · Dr Test", "Nursing care");
    }

    @Test
    void allFourReadingRolesGetThePackageCopy() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("ADMITTED", 1_000_000L, null, PACKAGE)));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            assertThat(service.detail(TENANT, ACTOR, role, ADMISSION).packageCopy()).isEqualTo(CONTRACT_PACKAGE);
        }
    }

    @Test
    void aDischargedSheetKeepsThePackageCopyWhileItsBillComesFromTheFrozenLines() {
        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(
                row("DISCHARGED", 1_000_000L, dischargedAt, PACKAGE)));
        UUID billId = UUID.randomUUID();
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.FinalBillRow(billId, ADMISSION, 4_500_000L, 1_000_000L, 3_500_000L, "Cash",
                        ACTOR, dischargedAt, 2, "midnight", "gen", new java.math.BigDecimal("1.00"))));
        when(discharges.findDischarge(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.DischargeRow(UUID.randomUUID(), ADMISSION, UUID.randomUUID(), "normal",
                        "surgical", DOCTOR, dischargedAt.minusSeconds(60), 7, java.time.LocalDate.of(2026, 11, 17),
                        ACTOR, dischargedAt)));
        when(discharges.findFinalBillLines(TENANT, billId)).thenReturn(List.of(
                new RunningBill.BillLine("Package · Appendicectomy (bed, nursing and surgeon for 2 days)", 4_500_000L,
                        1, 4_500_000L)));

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(detail.packageCopy()).isEqualTo(CONTRACT_PACKAGE);
        assertThat(detail.bill().lines()).hasSize(1);
        assertThat(detail.bill().totalPaise()).isEqualTo(4_500_000L);
    }

    // ---- the one bill path behind the list, the board and the tiles ----

    @Test
    void theBatchForAllAdmittedPatientsBillsAPackageAdmissionAndANormalOneSideBySide() {
        UUID other = UUID.randomUUID();
        AdmissionRow plain = new AdmissionRow(other, "IP/26-27/0041", PATIENT, BED, "101-B", "gen", "General ward",
                DOCTOR, "planned", "Self", "Fever", "ADMITTED", ADMITTED_AT, 500_000L, null, null);
        when(charges.findForAdmitted(TENANT)).thenReturn(List.of());
        when(transfers.findForAdmitted(TENANT)).thenReturn(List.of());

        IpdBilling.Batch batch = billing.forAdmitted(TENANT, NOW, 80);
        RunningBill packaged = batch.bill(row("ADMITTED", 1_000_000L, null, PACKAGE), "Dr Test");
        RunningBill normal = batch.bill(plain, "Dr Test");

        assertThat(packaged.lines().get(0).name())
                .isEqualTo("Package · Appendicectomy (bed, nursing and surgeon for 2 days)");
        assertThat(packaged.totalPaise()).isEqualTo(4_580_000L);
        assertThat(normal.lines().get(0).name()).isEqualTo("Bed · General");
    }

    @Test
    void theContractDtoCopiesThePackageFieldsAndIsNullWhenThereIsNone() {
        assertThat(AdmissionViews.AdmissionPackage.of(PACKAGE)).isEqualTo(CONTRACT_PACKAGE);
        assertThat(AdmissionViews.AdmissionPackage.of(null)).isNull();
        assertThat(CONTRACT_PACKAGE.toString()).doesNotContain("Appendicectomy");
    }
}
