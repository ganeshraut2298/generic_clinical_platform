package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.ChargeService;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.ChargeRepository.ChargeRow;
import com.sahaj.ipd.application.IpdSettingsRepository.DepositRules;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/** SAH-25 extra charges, the running bill on the list and the sheet, and the charge-services list. */
class AdmissionChargeServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID BED = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID CHARGE = UUID.randomUUID();
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    /** Two days earlier: bill day 3. */
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-09T05:30:00Z");

    private AdmissionRepository admissions;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private ModuleEntitlementPort entitlements;
    private AuditLog auditLog;
    private PatientLookupPort patients;
    private PatientAccessPort patientAccess;
    private ChargeRepository charges;
    private DischargeRepository discharges = mock(DischargeRepository.class);
    private PatientClinicalPort clinicalPatients;
    private SafetyService safety;
    private AdmissionService service;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        auditLog = mock(AuditLog.class);
        patients = mock(PatientLookupPort.class);
        patientAccess = mock(PatientAccessPort.class);
        charges = mock(ChargeRepository.class);
        TeamMemberPort team = mock(TeamMemberPort.class);
        clinicalPatients = mock(PatientClinicalPort.class);
        safety = mock(SafetyService.class);
        when(safety.databaseNow()).thenReturn(NOW);
        when(safety.liveEscalations(TENANT)).thenReturn(List.of());
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.of(
                new TeamMember(UUID.randomUUID(), "Member", "NURSE", true, 0)));
        IpdCatalog catalog = new IpdCatalog(settings);
        service = new AdmissionService(admissions, mock(BedRepository.class), wards, settings, mock(OwnerGuard.class),
                auditLog, team, patients, patientAccess, mock(ActorDirectoryPort.class),
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), catalog, charges,
                new IpdBilling(charges, mock(TransferRepository.class), catalog), discharges,
                mock(EmergencyService.class), mock(VitalsRepository.class), new AdmittedCards(clinicalPatients, patients, mock(VitalsRepository.class), safety),
                new CardSetups(mock(CardSetupRepository.class)), ScopeStubs.unscoped(), ScopeStubs.repository(),
                mock(com.sahaj.opd.api.EmrAdmissionRequestPort.class));

        state("on");
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.empty());
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Anil Kumar Rao", "DOCTOR", true, 0)));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001")));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT,
                new PatientClinical(PATIENT, "UH-0001", "Test Patient", 56, "M", null)));
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row(ADMISSION, "Self", "ADMITTED", 1_000_000L)));
        when(charges.insert(eq(TENANT), eq(ADMISSION), anyString(), anyString(), anyLong(), any())).thenReturn(CHARGE);
        when(charges.countByService(eq(TENANT), eq(ADMISSION), anyString())).thenReturn(0);
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    private static List<AdmittedRow> placed(AdmissionRow... rows) {
        List<AdmittedRow> placed = new java.util.ArrayList<>();
        for (AdmissionRow row : rows) {
            placed.add(new AdmittedRow(row, UUID.randomUUID(), null));
        }
        return placed;
    }

    private static AdmissionRow row(UUID id, String payer, String status, long deposit) {
        return new AdmissionRow(id, "IP/26-27/0042", PATIENT, BED, "101-A", "gen", "General ward", DOCTOR, "planned",
                payer, "Chest pain", status, ADMITTED_AT, deposit);
    }

    // ---- charge services ----

    @Test
    void chargeServicesAreTheTenOfTheMockupWithTheirBasePrices() {
        List<ChargeService> list = service.chargeServices(TENANT);

        assertThat(list).hasSize(10);
        assertThat(list).extracting(ChargeService::key).containsExactly("visit", "ivfluid", "injection", "cbc",
                "xray", "oxygen", "dressing", "ecg", "nebulisation", "physio");
        assertThat(list).extracting(ChargeService::name).containsExactly("Extra doctor visit",
                "IV fluids (per bottle)", "Injection (IV/IM)", "CBC", "Chest X-ray", "Oxygen (per day)",
                "Major dressing", "ECG", "Nebulisation", "Physiotherapy session");
        assertThat(list).extracting(ChargeService::basePaise).containsExactly(50_000L, 35_000L, 15_000L, 30_000L,
                45_000L, 80_000L, 40_000L, 30_000L, 20_000L, 40_000L);
    }

    @Test
    void chargeServicesAreGatedOnTheIpdStateButReadableInReadonly() {
        state("readonly");
        assertThat(service.chargeServices(TENANT)).hasSize(10);
        state("off");
        assertThatThrownBy(() -> service.chargeServices(TENANT)).isInstanceOf(ModuleOffException.class);
    }

    // ---- add a charge ----

    @Test
    void addChargeInsertsOneRowAuditsIdsAndPriceOnlyAndReturnsTheBill() {
        when(charges.findByAdmission(TENANT, ADMISSION)).thenReturn(List.of(
                new ChargeRow(ADMISSION, "xray", "Chest X-ray", 45_000L, 1)));

        Detail detail = service.addCharge(TENANT, ACTOR, "NURSE", ADMISSION, "xray");

        verify(charges).insert(TENANT, ADMISSION, "xray", "Chest X-ray", 45_000L, ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_CHARGE_ADDED"), eq("IPD_CHARGE"), eq(CHARGE), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("admissionId", "serviceKey", "basePaise")
                .containsEntry("admissionId", ADMISSION).containsEntry("serviceKey", "xray")
                .containsEntry("basePaise", 45_000L);
        assertThat(detail.bill().lines()).extracting(l -> l.name()).contains("Chest X-ray");
        // 3 days x (800 + 500 + 300) = 4,800 plus 450.
        assertThat(detail.runningBillPaise()).isEqualTo(525_000L);
        assertThat(detail.bill().totalPaise()).isEqualTo(525_000L);
        assertThat(detail.canAddCharge()).isTrue();
    }

    @Test
    void addChargeWritesOnePatientRecordAccessRowBecauseItReturnsTheFullSheet() {
        service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc");

        verify(patientAccess, org.mockito.Mockito.times(1)).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void addChargeWritesNoAccessRowWhenItIsRefused() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "DOCTOR", ADMISSION, "cbc"))
                .isInstanceOf(AccessDeniedException.class);

        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void addChargeRefusesARoleThatMayNotAddChargesBeforeAnyLockOrWrite() {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR", "nobody"}) {
            assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, role, ADMISSION, "cbc"))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, null, ADMISSION, "cbc"))
                .isInstanceOf(AccessDeniedException.class);

        verify(wards, never()).lockTenant(any());
        verify(charges, never()).insert(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(entitlements, never()).forTenant(any());
    }

    @Test
    void addChargeTakesTheClinicLockBeforeReadingTheAdmissionAndInserting() {
        service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc");

        InOrder order = inOrder(wards, admissions, charges);
        order.verify(wards).lockTenant(TENANT);
        order.verify(admissions).find(TENANT, ADMISSION);
        order.verify(charges).countByService(TENANT, ADMISSION, "cbc");
        order.verify(charges).insert(TENANT, ADMISSION, "cbc", "CBC", 30_000L, ACTOR);
    }

    @Test
    void anUnknownOrMissingServiceIs400AndTakesNoLock() {
        for (String bad : new String[] {"nope", "", " cbc", "CBC", null}) {
            assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("serviceKey"));
        }
        verify(wards, never()).lockTenant(any());
        verify(charges, never()).insert(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void anUnknownAdmissionIs404() {
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", other, "cbc"))
                .isInstanceOf(NotFoundException.class);
        verify(charges, never()).insert(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aPatientWhoIsNotAdmittedIs409NotAdmitted() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row(ADMISSION, "Self", "DISCHARGED", 0L)));

        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        verify(charges, never()).insert(any(), any(), any(), any(), anyLong(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void theFiftyFirstRowOfTheSameServiceIs400ButTheFiftiethIsAllowed() {
        when(charges.countByService(TENANT, ADMISSION, "ivfluid")).thenReturn(49);
        service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "ivfluid");
        verify(charges).insert(TENANT, ADMISSION, "ivfluid", "IV fluids (per bottle)", 35_000L, ACTOR);

        when(charges.countByService(TENANT, ADMISSION, "ivfluid")).thenReturn(50);
        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "ivfluid"))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.field()).isEqualTo("serviceKey"));
        verify(charges, times(1)).insert(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void addChargeIsBlockedWhenIpdIsReadonlyOrOff() {
        state("readonly");
        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        state("off");
        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "OWNER", ADMISSION, "cbc"))
                .isInstanceOf(ModuleOffException.class);
        verify(wards, never()).lockTenant(any());
        verify(charges, never()).insert(any(), any(), any(), any(), anyLong(), any());
    }

    // ---- the bill on the sheet ----

    @Test
    void detailCarriesTheBillAndTheTotalsAgreeWithTheRowFields() {
        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        // Midnight cut: 9, 10 and 11 Nov is three bill days. Day 3 of 800 + 500 + 300.
        assertThat(detail.bill().billDays()).isEqualTo(3);
        assertThat(detail.bill().lines()).extracting(l -> l.name())
                .containsExactly("Bed · General", "Doctor visits · Anil Kumar", "Nursing care");
        assertThat(detail.bill().totalPaise()).isEqualTo(480_000L);
        assertThat(detail.runningBillPaise()).isEqualTo(480_000L);
        assertThat(detail.bill().paidPaise()).isEqualTo(1_000_000L);
        assertThat(detail.bill().depositPercent()).isEqualTo(48);
        assertThat(detail.bill().alertPercent()).isEqualTo(80);
        assertThat(detail.depositLow()).isFalse();
        assertThat(detail.dayNumber()).isEqualTo(3);
        verify(charges).findByAdmission(TENANT, ADMISSION);
    }

    @Test
    void detailUsesTheClinicRatesFromSettingsAndTheDayCut() {
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(
                new IpdSettingsRepository.ConfigRow(true, "24h", 20_000L, 100_000L)));
        // 48 hours exactly passed since the admission: 2 completed periods.
        AdmissionRow exactlyTwoDays = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen",
                "General ward", DOCTOR, "planned", "Self", "x", "ADMITTED", NOW.minusSeconds(48 * 3600L)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS), 0L);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(exactlyTwoDays));

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(detail.bill().bedDayCut()).isEqualTo("24h");
        assertThat(detail.bill().billDays()).isEqualTo(2);
        assertThat(detail.bill().totalPaise()).isEqualTo(2 * (80_000L + 100_000L + 20_000L));
        assertThat(detail.depositLow()).isTrue();
        assertThat(detail.bill().depositPercent()).isNull();
    }

    @Test
    void canAddChargeIsForOwnerFrontDeskNurseWhileAdmittedAndNotReadonly() {
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canAddCharge()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION).canAddCharge()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).canAddCharge()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canAddCharge()).isFalse();
        assertThat(service.detail(TENANT, ACTOR, "BILLING", ADMISSION).canAddCharge()).isFalse();

        state("readonly");
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canAddCharge()).isFalse();

        state("trial");
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canAddCharge()).isTrue();
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row(ADMISSION, "Self", "DISCHARGED", 0L)));
        stubFrozenBill();
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canAddCharge()).isFalse();
    }

    /** A discharged admission is read from its frozen final bill. */
    private void stubFrozenBill() {
        UUID billId = UUID.randomUUID();
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(new DischargeRepository.FinalBillRow(
                billId, ADMISSION, 100_000L, 0L, 100_000L, "UPI", ACTOR, ADMITTED_AT, 1, "midnight", "gen",
                new java.math.BigDecimal("1.00"))));
        when(discharges.findDischarge(TENANT, ADMISSION)).thenReturn(Optional.of(new DischargeRepository.DischargeRow(
                UUID.randomUUID(), ADMISSION, UUID.randomUUID(), "normal", "general", ACTOR, ADMITTED_AT, 7, null, ACTOR,
                ADMITTED_AT)));
        when(discharges.findFinalBillLines(TENANT, billId)).thenReturn(List.of());
    }

    // ---- the list ----

    @Test
    void listShowsTheRunningBillDepositLowAndTheTileCountInBatchQueries() {
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        // Bill is 480,000 for each (3 days). Deposit 600,000 is exactly 80 percent: low.
        AdmissionRow atBoundary = row(ADMISSION, "Self", "ADMITTED", 600_000L);
        AdmissionRow ok = row(second, "Self", "ADMITTED", 1_000_000L);
        AdmissionRow insurance = row(third, "Insurance", "ADMITTED", 0L);
        AdmissionRow noDeposit = row(fourth, "Self", "ADMITTED", 0L);
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(atBoundary, ok, insurance, noDeposit));
        when(charges.findForAdmitted(TENANT)).thenReturn(List.of(
                new ChargeRow(second, "cbc", "CBC", 30_000L, 1),
                new ChargeRow(second, "cbc", "CBC", 30_000L, 1)));

        AdmissionList list = service.list(TENANT, ACTOR, "NURSE", "ADMITTED", null);

        assertThat(list.items()).extracting(s -> s.runningBillPaise())
                .containsExactly(480_000L, 540_000L, 480_000L, 480_000L);
        assertThat(list.items()).extracting(s -> s.depositLow()).containsExactly(true, false, false, true);
        assertThat(list.depositAlerts()).isEqualTo(2);
        assertThat(list.alertPercent()).isEqualTo(80);
        // The DUE state of SAH-23 stays as it was.
        assertThat(list.items()).extracting(s -> s.depositState()).containsExactly("DUE", "OK", "NOT_NEEDED", "DUE");
        verify(charges, times(1)).findForAdmitted(TENANT);
        verify(charges, never()).findByAdmission(any(), any());
        verify(charges, never()).countByService(any(), any(), any());
    }

    @Test
    void listUsesTheAlertPercentOfTheAdmissionRules() {
        DepositRules ninety = new DepositRules(1_000_000L, 500_000L, 300_000L, 1_500_000L, 0L, 200_000L, 500_000L,
                1_000_000L, true, 90, true);
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(ninety));
        // 480,000 against 600,000 is 80 percent: not low at an alert of 90.
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row(ADMISSION, "Self", "ADMITTED", 600_000L)));

        AdmissionList list = service.list(TENANT, ACTOR, "OWNER", null, null);

        assertThat(list.alertPercent()).isEqualTo(90);
        assertThat(list.depositAlerts()).isZero();
        assertThat(list.items().get(0).depositLow()).isFalse();
    }

    @Test
    void emptyListHasNoAlertsAndTheDefaultAlertPercent() {
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed());

        AdmissionList list = service.list(TENANT, ACTOR, "OWNER", null, null);

        assertThat(list.depositAlerts()).isZero();
        assertThat(list.alertPercent()).isEqualTo(80);
    }
}
