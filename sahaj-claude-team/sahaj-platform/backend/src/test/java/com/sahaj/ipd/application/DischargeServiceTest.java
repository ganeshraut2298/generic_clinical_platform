package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.PackageCopy;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.DischargeRepository.DischargeRow;
import com.sahaj.ipd.application.DischargeRepository.FinalBillRow;
import com.sahaj.ipd.application.DischargeRepository.NewDischarge;
import com.sahaj.ipd.application.DischargeRepository.NewFinalBill;
import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.DischargeViews.DischargeInput;
import com.sahaj.ipd.application.DischargeViews.Settings;
import com.sahaj.ipd.application.DischargeViews.SettingsInput;
import com.sahaj.ipd.application.DischargeViews.Sheet;
import com.sahaj.ipd.application.DischargeViews.SignoffInput;
import com.sahaj.ipd.application.IpdSettingsRepository.DischargeSettings;
import com.sahaj.opd.api.FollowUpPort;
import com.sahaj.opd.api.FollowUpPort.DischargeFollowUp;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * SAH-27 discharge: Discharge setup, the sheet, the sign-off and the discharge. The bill of the fixture is 480,000
 * paise (3 bill days of General at 80,000 + visits 50,000 + nursing 30,000), the deposit 1,000,000.
 */
class DischargeServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID BED = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID SIGNOFF = UUID.randomUUID();
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    /** 9 Nov 2026, 11:00 AM IST: bill day 3 on the midnight cut. */
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-09T05:30:00Z");
    private static final long BILL = 480_000L;
    private static final String REASON = "Chest pain with a very private clinical history";

    private AdmissionRepository admissions;
    private BedRepository beds;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private DischargeRepository discharges;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private TeamMemberPort team;
    private PatientAccessPort patientAccess;
    private FollowUpPort followUps;
    private ModuleEntitlementPort entitlements;
    private EscalationRepository escalations;
    private ClinicalEventRepository events;
    private DischargeService service;
    private ScopeGuard guard;

    private long deposits = 1_000_000L;
    private String reason = REASON;
    private String admissionType = "planned";
    private PackageCopy packageCopy;
    private boolean discharged;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        beds = mock(BedRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        discharges = mock(DischargeRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        team = mock(TeamMemberPort.class);
        patientAccess = mock(PatientAccessPort.class);
        followUps = mock(FollowUpPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        escalations = mock(EscalationRepository.class);
        events = mock(ClinicalEventRepository.class);
        PatientLookupPort patients = mock(PatientLookupPort.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        ActorDirectoryPort actors = mock(ActorDirectoryPort.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        IpdBilling billing = new IpdBilling(charges, transfers, catalog);
        guard = ScopeStubs.unscoped();
        AdmissionService sheets = new AdmissionService(admissions, beds, wards, settings, ownerGuard, auditLog, team,
                patients, patientAccess, actors, gate, catalog, charges, billing, discharges,
                mock(EmergencyService.class), mock(VitalsRepository.class), mock(AdmittedCards.class), mock(CardSetups.class),
                guard, ScopeStubs.repository(), mock(com.sahaj.opd.api.EmrAdmissionRequestPort.class));
        service = new DischargeService(admissions, beds, wards, settings, discharges, ownerGuard, auditLog, team,
                patients, patientAccess, actors, followUps, gate, catalog, billing, sheets, escalations, events, guard);

        state("on");
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(
                new TeamMember(DOCTOR, "Dr Asha Rao Kulkarni", "DOCTOR", true, 0)));
        when(team.doctorSpecialty(TENANT, DOCTOR)).thenReturn(Optional.of("Cardiology"));
        signerIs("DOCTOR", true);
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001")));
        when(actors.displayName(any(), any())).thenReturn(Optional.of("Dr Signer"));
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(
                new BedRow(BED, WARD, null, "101-A", "gen", "occ")));
        // The admission reads as ADMITTED until markDischarged is called, then as DISCHARGED.
        when(admissions.find(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(row()));
        when(admissions.findForUpdate(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(row()));
        when(admissions.markDischarged(eq(TENANT), eq(ADMISSION), any())).thenAnswer(call -> {
            discharged = true;
            return true;
        });
        when(discharges.insertFinalBill(eq(TENANT), any())).thenReturn(UUID.randomUUID());
        when(followUps.createForDischarge(eq(TENANT), eq(ACTOR), any())).thenReturn(UUID.randomUUID());
        when(followUps.feeRule(TENANT)).thenReturn(new FollowUpPort.FeeRule(7, "free"));
        signed("normal", "general");
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    private AdmissionRow row() {
        return new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen", "General ward", DOCTOR,
                admissionType, "Self", reason, discharged ? "DISCHARGED" : "ADMITTED", ADMITTED_AT, deposits,
                discharged ? NOW : null, packageCopy);
    }

    private void signed(String type, String template) {
        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(Optional.of(
                new SignoffRow(SIGNOFF, ADMISSION, type, template, DOCTOR, Instant.parse("2026-11-11T04:00:00Z"))));
    }

    /** The caller's membership as the database holds it now, whatever the token says. */
    private void signerIs(String role, boolean active) {
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.of(
                new TeamMember(UUID.randomUUID(), "Dr Signer", role, active, 0)));
    }

    private void verifyOnlyTheAccessRowWritten() {
        verify(patientAccess).recordOpened(TENANT, PATIENT, ACTOR);
        verify(discharges, never()).insertFinalBill(any(), any());
        verify(discharges, never()).insertFinalBillLines(any(), any(), any());
        verify(discharges, never()).insertDischarge(any(), any());
        verify(discharges, never()).insertSignoff(any(), any(), any(), any(), any());
        verify(admissions, never()).markDischarged(any(), any(), any());
        verify(beds, never()).updateStatus(any(), any(), any());
        verify(followUps, never()).createForDischarge(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
        verify(settings, never()).upsertDischargeSettings(any(), any(), any(), any());
    }

    private void unsigned() {
        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(Optional.empty());
    }

    /** The deposits the user saw are those of the fixture now, so only the total differs unless a test says so. */
    private DischargeInput input(String type, String template, long expected, String mode) {
        return new DischargeInput(type, template, expected, deposits, mode);
    }

    /** A test that pins the membership (role or status) that differs from the token sets this. */
    private boolean membershipPinned;

    private void pinMembership(String role, boolean active) {
        signerIs(role, active);
        membershipPinned = true;
    }

    private Detail discharge(String role, DischargeInput input) {
        if (!membershipPinned) {
            signerIs(role, true);
        }
        return service.discharge(TENANT, ACTOR, role, ADMISSION, input);
    }

    private void verifyNothingWritten() {
        verify(discharges, never()).insertFinalBill(any(), any());
        verify(discharges, never()).insertFinalBillLines(any(), any(), any());
        verify(discharges, never()).insertDischarge(any(), any());
        verify(discharges, never()).insertSignoff(any(), any(), any(), any(), any());
        verify(admissions, never()).markDischarged(any(), any(), any());
        verify(beds, never()).updateStatus(any(), any(), any());
        verify(followUps, never()).createForDischarge(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
        verify(settings, never()).upsertDischargeSettings(any(), any(), any(), any());
    }

    private void stubFrozenRows(long total, long depositsPaise, String mode) {
        UUID billId = UUID.randomUUID();
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(new FinalBillRow(billId, ADMISSION,
                total, depositsPaise, total - depositsPaise, mode, ACTOR, NOW, 3, "midnight", "gen",
                new BigDecimal("1.00"))));
        when(discharges.findDischarge(TENANT, ADMISSION)).thenReturn(Optional.of(new DischargeRow(UUID.randomUUID(),
                ADMISSION, SIGNOFF, "normal", "general", DOCTOR, Instant.parse("2026-11-11T04:00:00Z"), 7,
                LocalDate.of(2026, 11, 18), ACTOR, NOW)));
        when(discharges.findFinalBillLines(TENANT, billId)).thenReturn(List.of(
                new RunningBill.BillLine("Bed · General", 80_000L, 3, 240_000L)));
    }

    // ---- Discharge setup ----

    @Test
    void settingsShowDefaultsWithTheOpdRuleAndNeverCreateARow() {
        when(followUps.feeRule(TENANT)).thenReturn(new FollowUpPort.FeeRule(6, "free"));

        Settings s = service.settings(TENANT, "OWNER");

        assertThat(s.templateKey()).isNull();
        assertThat(s.approvalChain()).isTrue();
        assertThat(s.followUpDays()).isEqualTo(7);
        assertThat(s.types()).extracting(t -> t.key()).containsExactly("normal", "lama", "referred", "death");
        assertThat(s.types()).extracting(t -> t.label())
                .containsExactly("Normal", "On request (LAMA)", "Referred", "Death");
        assertThat(s.templates()).extracting(t -> t.label())
                .containsExactly("General medicine", "Surgical", "Maternity", "Day-care");
        assertThat(s.opdFollowUpRule().days()).isEqualTo(6);
        assertThat(s.opdFollowUpRule().fee()).isEqualTo("free");
        assertThat(s.canEdit()).isTrue();
        verifyNothingWritten();
    }

    @Test
    void onlyTheOwnerCanEditAndNotInReadOnlyAndEveryIpdRoleReads() {
        when(followUps.feeRule(TENANT)).thenReturn(new FollowUpPort.FeeRule(7, "half"));
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR"}) {
            assertThat(service.settings(TENANT, role).canEdit()).as(role).isFalse();
        }
        state("readonly");
        assertThat(service.settings(TENANT, "OWNER").canEdit()).isFalse();
        assertThat(service.settings(TENANT, "OWNER").opdFollowUpRule().fee()).isEqualTo("half");
        state("off");
        assertThatThrownBy(() -> service.settings(TENANT, "OWNER")).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void settingsAreRefusedForRolesWithoutIpdAccessBeforeTheModuleIsLookedAt() {
        for (String role : new String[] {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR", null}) {
            assertThatThrownBy(() -> service.settings(TENANT, role)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(entitlements);
    }

    @Test
    void savingChangedSettingsLocksWritesAndAuditsBeforeAndAfter() {
        when(followUps.feeRule(TENANT)).thenReturn(new FollowUpPort.FeeRule(7, "free"));

        Settings saved = service.updateSettings(TENANT, ACTOR, new SettingsInput("surgical", false, 10));

        InOrder order = inOrder(ownerGuard, wards, settings, auditLog);
        order.verify(ownerGuard).requireOwner(TENANT, ACTOR);
        order.verify(wards).lockTenant(TENANT);
        order.verify(settings).upsertDischargeSettings(eq(TENANT), eq(new DischargeSettings("surgical", false, 10)),
                eq(ACTOR), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        order.verify(auditLog).record(eq("IPD_DISCHARGE_SETTINGS_UPDATED"), eq("IPD_CONFIG"), eq(null),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("templateKey", null).containsEntry("approvalChain", true)
                .containsEntry("followUpDays", 7);
        assertThat(after.getValue()).containsEntry("templateKey", "surgical").containsEntry("approvalChain", false)
                .containsEntry("followUpDays", 10);
        assertThat(saved.templateKey()).isEqualTo("surgical");
        assertThat(saved.canEdit()).isTrue();
    }

    @Test
    void savingTheSameSettingsAgainWritesAndAuditsNothing() {
        when(settings.findDischargeSettings(TENANT)).thenReturn(Optional.of(new DischargeSettings("daycare", true, 5)));

        Settings saved = service.updateSettings(TENANT, ACTOR, new SettingsInput("daycare", true, 5));

        assertThat(saved.followUpDays()).isEqualTo(5);
        verify(settings, never()).upsertDischargeSettings(any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUnsetTemplateIsStoredAsNoTemplate() {
        when(settings.findDischargeSettings(TENANT)).thenReturn(Optional.of(new DischargeSettings("daycare", true, 7)));

        service.updateSettings(TENANT, ACTOR, new SettingsInput(null, true, 7));

        verify(settings).upsertDischargeSettings(eq(TENANT), eq(new DischargeSettings(null, true, 7)), eq(ACTOR), any());
    }

    @Test
    void aNonOwnerIsRefusedByTheOwnerRecheckBeforeAnythingElse() {
        doThrow(new AccessDeniedException("owner only")).when(ownerGuard).requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, new SettingsInput("general", true, 7)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(entitlements);
        verifyNothingWritten();
    }

    @Test
    void settingsBodyErrorsNameTheFieldAndComeBeforeTheReadOnlyAnswer() {
        state("readonly");
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, new SettingsInput("other", true, 7)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("templateKey");
                    assertThat(e.getMessage()).isEqualTo("Choose a template from the list");
                });
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, new SettingsInput("general", null, 7)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("approvalChain");
                    assertThat(e.getMessage()).isEqualTo("Choose on or off");
                });
        for (Integer days : new Integer[] {null, 0, 31, -1}) {
            assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, new SettingsInput("general", true, days)))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("followUpDays");
                        assertThat(e.getMessage()).isEqualTo("Enter 1 to 30 days");
                    });
        }
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, null))
                .isInstanceOf(InvalidInputException.class);
        // A valid body in Read-only state is 409.
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, new SettingsInput("general", true, 7)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        verifyNothingWritten();
    }

    @Test
    void theBoundariesOneAndThirtyDaysAreAccepted() {
        service.updateSettings(TENANT, ACTOR, new SettingsInput("general", true, 1));
        service.updateSettings(TENANT, ACTOR, new SettingsInput("general", true, 30));
        verify(settings).upsertDischargeSettings(eq(TENANT), eq(new DischargeSettings("general", true, 1)), any(), any());
        verify(settings).upsertDischargeSettings(eq(TENANT), eq(new DischargeSettings("general", true, 30)), any(), any());
    }

    // ---- the sheet ----

    @Test
    void theSheetCarriesTheContractFieldsAndWritesOnlyTheAccessRow() {
        Sheet sheet = service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION);

        assertThat(sheet.admissionId()).isEqualTo(ADMISSION);
        assertThat(sheet.ipNumber()).isEqualTo("IP/26-27/0042");
        assertThat(sheet.patientName()).isEqualTo("Test Patient");
        assertThat(sheet.bedCode()).isEqualTo("101-A");
        assertThat(sheet.dayNumber()).isEqualTo(3);
        assertThat(sheet.approvalChain()).isTrue();
        assertThat(sheet.defaultType()).isEqualTo("normal");
        assertThat(sheet.defaultTemplateKey()).isEqualTo("general");
        assertThat(sheet.preview().diagnosis()).isEqualTo(REASON);
        assertThat(sheet.preview().stayDays()).isEqualTo(3);
        assertThat(sheet.preview().doctorName()).isEqualTo("Dr Asha Rao Kulkarni");
        assertThat(sheet.preview().doctorShortName()).isEqualTo("Dr Asha");
        assertThat(sheet.preview().doctorSpecialty()).isEqualTo("Cardiology");
        assertThat(sheet.bill().totalPaise()).isEqualTo(BILL);
        assertThat(sheet.bill().depositsPaise()).isEqualTo(1_000_000L);
        assertThat(sheet.bill().balancePaise()).isEqualTo(BILL - 1_000_000L);
        assertThat(sheet.followUpDays()).isEqualTo(7);
        // 11 Nov 2026 IST plus 7 days.
        assertThat(sheet.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        verifyOnlyTheAccessRowWritten();
        assertThat(sheet.toString()).doesNotContain("Test Patient").doesNotContain(REASON);
        assertThat(sheet.preview().toString()).doesNotContain(REASON);
    }

    @Test
    void theSpecialtyIsNullWhenTheDoctorHasNone() {
        when(team.doctorSpecialty(TENANT, DOCTOR)).thenReturn(Optional.empty());
        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).preview().doctorSpecialty()).isNull();
    }

    @Test
    void anAdmissionWithAPackageStartsOnTheSurgicalTemplateUnlessSetupOrTheTypeSaysOtherwise() {
        packageCopy = new PackageCopy("Appendicectomy", 4_500_000L, 3);
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("surgical");
        admissionType = "maternity";
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("maternity");
        admissionType = "daycare";
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("daycare");
    }

    @Test
    void theDefaultTemplateFollowsSetupThenTheAdmissionType() {
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("general");
        admissionType = "maternity";
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("maternity");
        admissionType = "daycare";
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("daycare");
        admissionType = "emergency";
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("general");
        // A template chosen in Discharge setup wins over the admission type.
        admissionType = "maternity";
        when(settings.findDischargeSettings(TENANT)).thenReturn(Optional.of(new DischargeSettings("surgical", true, 7)));
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).defaultTemplateKey()).isEqualTo("surgical");
    }

    @Test
    void theSheetShowsTheLatestSignatureOrNone() {
        Sheet signedSheet = service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION);
        assertThat(signedSheet.signature().type()).isEqualTo("normal");
        assertThat(signedSheet.signature().templateKey()).isEqualTo("general");
        assertThat(signedSheet.signature().signedByName()).isEqualTo("Dr Signer");

        unsigned();
        assertThat(service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION).signature()).isNull();
    }

    @Test
    void theCanFlagsFollowTheRoleAndReadOnly() {
        assertFlags("OWNER", true, true, true);
        assertFlags("DOCTOR", true, false, false);
        assertFlags("FRONT_DESK", false, true, false);
        assertFlags("NURSE", false, false, false);
        state("readonly");
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK", "NURSE"}) {
            assertFlags(role, false, false, false);
        }
    }

    private void assertFlags(String role, boolean sign, boolean discharge, boolean refund) {
        Sheet sheet = service.sheet(TENANT, ACTOR, role, ADMISSION);
        assertThat(sheet.canSign()).as(role + " canSign").isEqualTo(sign);
        assertThat(sheet.canDischarge()).as(role + " canDischarge").isEqualTo(discharge);
        assertThat(sheet.canRefund()).as(role + " canRefund").isEqualTo(refund);
    }

    @Test
    void theSheetOfADischargedOrUnknownAdmissionIsRefused() {
        discharged = true;
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "OWNER", ADMISSION)).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "OWNER", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        // M3: a discharged or unknown admission answers 409 or 404 and writes no access row.
        verifyNothingWritten();
    }

    @Test
    void everyOpenOfTheSheetWritesExactlyOneAccessRowForThePatientAndTheCaller() {
        service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);
        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, ACTOR);

        service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION);
        verify(patientAccess, times(2)).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void theSheetIsRefusedForOtherRolesAndWhenIpdIsOff() {
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "BILLING", ADMISSION)).isInstanceOf(AccessDeniedException.class);
        state("off");
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "OWNER", ADMISSION)).isInstanceOf(ModuleOffException.class);
        verifyNothingWritten();
    }

    // ---- the sign-off: the caller is checked again (security review L1) ----

    @Test
    void aDoctorWhoIsNoLongerADoctorOrNoLongerActiveCannotSignWithAStaleToken() {
        unsigned();
        for (String current : new String[] {"NURSE", "FRONT_DESK", "VISITING_DOCTOR", "BILLING"}) {
            signerIs(current, true);
            assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                    new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        }
        signerIs("DOCTOR", false);
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        verifyNothingWritten();
    }

    @Test
    void anActiveDoctorOrOwnerSignsWhateverTheTokenRoleSaysWhenTheMembershipStillAllows() {
        unsigned();
        when(discharges.insertSignoff(any(), any(), any(), any(), any())).thenReturn(
                new SignoffRow(UUID.randomUUID(), ADMISSION, "normal", "general", ACTOR, NOW));
        signerIs("DOCTOR", true);
        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));
        signerIs("OWNER", true);
        service.signoff(TENANT, ACTOR, "OWNER", ADMISSION, new SignoffInput("normal", "general"));

        verify(discharges, times(2)).insertSignoff(TENANT, ADMISSION, "normal", "general", ACTOR);
    }

    @Test
    void aRepeatedSignOffByASignerWhoWasDemotedWritesNothingAndReturnsNoSheet() {
        signed("normal", "general");
        signerIs("NURSE", true);

        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        verifyNothingWritten();
    }

    // ---- the sign-off ----

    @Test
    void aSignOffLocksTheAdmissionFirstAppendsOneRowAndAuditsIdsTypeAndTemplateOnly() {
        unsigned();
        SignoffRow made = new SignoffRow(UUID.randomUUID(), ADMISSION, "lama", "surgical", ACTOR, NOW);
        when(discharges.insertSignoff(TENANT, ADMISSION, "lama", "surgical", ACTOR)).thenReturn(made);

        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("lama", "surgical"));

        InOrder order = inOrder(admissions, discharges, auditLog);
        order.verify(admissions).findForUpdate(TENANT, ADMISSION);
        order.verify(discharges).findLatestSignoff(TENANT, ADMISSION);
        order.verify(discharges).insertSignoff(TENANT, ADMISSION, "lama", "surgical", ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        order.verify(auditLog).record(eq("IPD_DISCHARGE_SIGNED"), anyString(), eq(made.id()), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("admissionId", "type", "templateKey")
                .containsEntry("admissionId", ADMISSION).containsEntry("type", "lama")
                .containsEntry("templateKey", "surgical");
        // The answer is the sheet with the name and the reason, so the access is logged (SAH-27 follow-up).
        verify(patientAccess).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void signingTheSameTypeAndTemplateAgainWritesNothingAndReturnsTheSheet() {
        signed("normal", "general");

        Sheet sheet = service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));

        assertThat(sheet.signature().type()).isEqualTo("normal");
        verify(discharges, never()).insertSignoff(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
        // It still answers with the sheet, so the access row is written again.
        verify(patientAccess).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void everySuccessfulSignOffWritesOneAccessRowAndARefusedOneWritesNone() {
        signed("normal", "general");

        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));
        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));
        verify(patientAccess, times(2)).recordOpened(TENANT, PATIENT, ACTOR);

        // not admitted, read-only, a role that cannot sign and a body that is wrong: no access row
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "NURSE", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput(null, null)))
                .isInstanceOf(InvalidInputException.class);
        state("readonly");
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(ConflictException.class);
        discharged = true;
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(ConflictException.class);
        verify(patientAccess, times(2)).recordOpened(any(), any(), any());
    }

    @Test
    void aChangedTypeOrTemplateNeedsANewSignOffAndTheOldOneNoLongerApplies() {
        signed("normal", "general");
        when(discharges.insertSignoff(any(), any(), any(), any(), any())).thenReturn(
                new SignoffRow(UUID.randomUUID(), ADMISSION, "death", "general", ACTOR, NOW));

        service.signoff(TENANT, ACTOR, "OWNER", ADMISSION, new SignoffInput("death", "general"));
        service.signoff(TENANT, ACTOR, "OWNER", ADMISSION, new SignoffInput("normal", "surgical"));

        verify(discharges).insertSignoff(TENANT, ADMISSION, "death", "general", ACTOR);
        verify(discharges).insertSignoff(TENANT, ADMISSION, "normal", "surgical", ACTOR);
    }

    @Test
    void theSignatureForAnotherTypeDoesNotLetTheDischargeThrough() {
        signed("normal", "general");

        assertThatThrownBy(() -> discharge("OWNER", input("lama", "general", BILL, "UPI"))).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));
        assertThatThrownBy(() -> discharge("OWNER", input("normal", "surgical", BILL, "UPI"))).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));
        verifyNothingWritten();
    }

    @Test
    void onlyADoctorOrTheOwnerSignsAndTheRoleIsCheckedBeforeTheModuleState() {
        for (String role : new String[] {"FRONT_DESK", "NURSE", "VISITING_DOCTOR", "BILLING", null}) {
            assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, role, ADMISSION,
                    new SignoffInput("normal", "general"))).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(entitlements);
        verifyNothingWritten();
    }

    @Test
    void signOffCheckOrderIsModuleBodyAdmissionNotAdmittedReadOnly() {
        state("off");
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput(null, null)))
                .isInstanceOf(ModuleOffException.class);
        state("on");
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput(null, "general")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("type");
                    assertThat(e.getMessage()).isEqualTo("Choose a discharge type");
                });
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "x")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("templateKey");
                    assertThat(e.getMessage()).isEqualTo("Choose a summary template");
                });
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", UUID.randomUUID(),
                new SignoffInput("normal", "general"))).isInstanceOf(NotFoundException.class);
        // Discharged and Read-only at once: not-admitted comes first.
        discharged = true;
        state("readonly");
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        discharged = false;
        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        verifyNothingWritten();
    }

    // ---- the discharge: balance in the three cases ----

    @Test
    void aPositiveBalanceIsCollectedByTheChosenModeWithTheServersAmount() {
        deposits = 100_000L;
        stubFrozenRows(BILL, 100_000L, "Card");

        Detail detail = discharge("FRONT_DESK", input("normal", "general", BILL, "Card"));

        NewFinalBill bill = capturedFinalBill();
        assertThat(bill.totalPaise()).isEqualTo(BILL);
        assertThat(bill.depositsPaise()).isEqualTo(100_000L);
        assertThat(bill.balancePaise()).isEqualTo(380_000L);
        assertThat(bill.settleMode()).isEqualTo("Card");
        assertThat(bill.createdBy()).isEqualTo(ACTOR);
        assertThat(bill.billDays()).isEqualTo(3);
        assertThat(bill.bedDayCut()).isEqualTo("midnight");
        assertThat(bill.categoryKey()).isEqualTo("gen");
        assertThat(bill.multiplier()).isEqualByComparingTo("1.00");
        assertThat(detail.finalBill().outcome()).isEqualTo("collected");
        assertThat(detail.finalBill().balancePaise()).isEqualTo(380_000L);
        verify(ownerGuard, never()).requireOwner(any(), any());
    }

    @Test
    void aNegativeBalanceIsRefundedByTheOwnerAfterTheOwnerRecheck() {
        deposits = 1_000_000L;
        stubFrozenRows(BILL, 1_000_000L, "Cash");

        Detail detail = discharge("OWNER", input("normal", "general", BILL, "Cash"));

        NewFinalBill bill = capturedFinalBill();
        assertThat(bill.balancePaise()).isEqualTo(-520_000L);
        assertThat(bill.settleMode()).isEqualTo("Cash");
        assertThat(detail.finalBill().outcome()).isEqualTo("refunded");
        // SAH-33 R3: the membership read at the start decides; the owner guard is not asked again.
        verify(ownerGuard, never()).requireOwner(any(), any());
    }

    @Test
    void aZeroBalanceIsSettledWithNoModeEvenIfOneWasSent() {
        deposits = BILL;
        stubFrozenRows(BILL, BILL, null);

        Detail detail = discharge("FRONT_DESK", input("normal", "general", BILL, "UPI"));

        NewFinalBill bill = capturedFinalBill();
        assertThat(bill.balancePaise()).isZero();
        assertThat(bill.settleMode()).isNull();
        assertThat(detail.finalBill().outcome()).isEqualTo("settled");
        assertThat(detail.finalBill().mode()).isNull();
    }

    @Test
    void aZeroBalanceNeedsNoModeAtAll() {
        deposits = BILL;
        stubFrozenRows(BILL, BILL, null);

        discharge("FRONT_DESK", input("normal", "general", BILL, null));

        assertThat(capturedFinalBill().settleMode()).isNull();
    }

    private NewFinalBill capturedFinalBill() {
        ArgumentCaptor<NewFinalBill> captor = ArgumentCaptor.forClass(NewFinalBill.class);
        verify(discharges).insertFinalBill(eq(TENANT), captor.capture());
        return captor.getValue();
    }

    @Test
    void theFinalBillLinesAreFrozenInBillOrder() {
        deposits = 0L;
        stubFrozenRows(BILL, 0L, "UPI");

        discharge("OWNER", input("normal", "general", BILL, "UPI"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RunningBill.BillLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(discharges).insertFinalBillLines(eq(TENANT), any(), lines.capture());
        assertThat(lines.getValue()).extracting(RunningBill.BillLine::name)
                .containsExactly("Bed · General", "Doctor visits · Dr Asha", "Nursing care");
        assertThat(lines.getValue()).extracting(RunningBill.BillLine::amountPaise).containsExactly(240_000L, 150_000L,
                90_000L);
    }

    // ---- the discharge: check order and refusals ----

    @Test
    void aRefundBySomeoneWhoIsNotTheOwnerIs403RefundNeedsOwnerAndNothingIsWritten() {
        deposits = 1_000_000L;

        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, "Cash")))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("refund-needs-owner");
                    assertThat(e.getMessage()).isEqualTo("Only the owner can refund a deposit.");
                });
        verifyNothingWritten();
    }

    @Test
    void aRefundByAnOwnerWhoseMembershipIsNowFrontDeskIsRefusedByTheMembershipRole() {
        deposits = 1_000_000L;
        pinMembership("FRONT_DESK", true);

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, "Cash")))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.problemType()).isEqualTo("refund-needs-owner"));
        verifyNothingWritten();
    }

    @Test
    void theMembershipRoleNotTheTokenRoleDecidesAllowedRefund() {
        // The membership role decides (ADR-007 R3): the token says FRONT_DESK, the membership says OWNER.
        deposits = 1_000_000L;
        stubFrozenRows(BILL, 1_000_000L, "Cash");
        pinMembership("OWNER", true);

        Detail detail = discharge("FRONT_DESK", input("normal", "general", BILL, "Cash"));

        assertThat(detail.finalBill().outcome()).isEqualTo("refunded");
    }

    @Test
    void aMissingModeWhenThereIsABalanceIs400OnTheModeField() {
        deposits = 100_000L;

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, null)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("mode");
                    assertThat(e.getMessage()).isEqualTo("Choose UPI, Cash or Card");
                });
        verifyNothingWritten();
    }

    @Test
    void aBillThatChangedSinceTheUserSawItIs409BillChangedWhateverTheDirection() {
        for (long wrong : new long[] {BILL - 1, BILL + 1, 0L}) {
            assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", wrong, "UPI")))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("bill-changed");
                        assertThat(e.getMessage()).isEqualTo("The bill changed. Review the final bill and try again.");
                    });
        }
        verifyNothingWritten();
    }

    @Test
    void aDepositAddedSinceTheUserSawTheSheetIs409BillChangedEvenWhenTheTotalIsTheSame() {
        deposits = 1_000_000L;
        for (long seen : new long[] {0L, 999_999L, 1_000_001L}) {
            assertThatThrownBy(() -> discharge("OWNER", new DischargeInput("normal", "general", BILL, seen, "UPI")))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("bill-changed");
                        assertThat(e.getMessage()).isEqualTo("The bill changed. Review the final bill and try again.");
                    });
        }
        verifyNothingWritten();
    }

    @Test
    void theDepositsCheckComesAfterTheSummaryCheckAndBeforeTheRefundCheck() {
        deposits = 1_000_000L;
        unsigned();
        assertThatThrownBy(() -> discharge("FRONT_DESK", new DischargeInput("normal", "general", BILL, 0L, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));
        signed("normal", "general");
        // a refund case by a non-owner, but the deposits differ: bill-changed, not refund-needs-owner
        assertThatThrownBy(() -> discharge("FRONT_DESK", new DischargeInput("normal", "general", BILL, 0L, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("bill-changed"));
        verifyNothingWritten();
        verify(ownerGuard, never()).requireOwner(any(), any());
    }

    @Test
    void anUnsignedSummaryIs409SummaryNotSigned() {
        unsigned();

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, "UPI")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("summary-not-signed");
                    assertThat(e.getMessage()).isEqualTo("The doctor signs the summary first");
                });
        verifyNothingWritten();
    }

    @Test
    void theCheckOrderIsRoleModuleBodyThenAdmissionNotAdmittedReadOnlySummaryBillRefundMode() {
        // role first, before the module state is even read
        assertThatThrownBy(() -> discharge("NURSE", input("normal", "general", BILL, "UPI")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(entitlements);
        // module Off before the body
        state("off");
        assertThatThrownBy(() -> discharge("OWNER", input(null, null, 0, null))).isInstanceOf(ModuleOffException.class);
        state("on");
        // body 400 before the admission is looked up
        assertThatThrownBy(() -> service.discharge(TENANT, ACTOR, "OWNER", UUID.randomUUID(),
                input(null, "general", BILL, "UPI"))).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.field()).isEqualTo("type"));
        assertThatThrownBy(() -> discharge("OWNER", input("normal", null, BILL, "UPI")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("templateKey");
                    assertThat(e.getMessage()).isEqualTo("Choose a summary template");
                });
        for (Long bad : new Long[] {null, -1L, 10_000_000_001L}) {
            assertThatThrownBy(() -> discharge("OWNER", new DischargeInput("normal", "general", bad, deposits, "UPI")))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("expectedTotalPaise");
                        assertThat(e.getMessage()).isEqualTo("Review the final bill and try again");
                    });
            assertThatThrownBy(() -> discharge("OWNER", new DischargeInput("normal", "general", BILL, bad, "UPI")))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("expectedDepositsPaise");
                        assertThat(e.getMessage()).isEqualTo("Review the final bill and try again");
                    });
        }
        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, "Cheque")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("mode"));
        assertThatThrownBy(() -> discharge("OWNER", null)).isInstanceOf(InvalidInputException.class);
        verifyNothingWritten();

        // 404 before not-admitted
        assertThatThrownBy(() -> service.discharge(TENANT, ACTOR, "OWNER", UUID.randomUUID(),
                input("normal", "general", BILL, "UPI"))).isInstanceOf(NotFoundException.class);
        // not-admitted before read-only, summary, bill and refund
        discharged = true;
        state("readonly");
        unsigned();
        deposits = 5_000_000L;
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        // read-only before summary, bill and refund
        discharged = false;
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        state("on");
        // summary before bill and refund
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));
        signed("normal", "general");
        // bill before refund (this is a refund case by a non-owner)
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("bill-changed"));
        // refund before mode
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, null)))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.problemType()).isEqualTo("refund-needs-owner"));
        verifyNothingWritten();
    }

    @Test
    void aSecondDischargeOfTheSamePatientIs409NotAdmittedAndNothingIsCollectedTwice() {
        deposits = 100_000L;
        stubFrozenRows(BILL, 100_000L, "UPI");
        discharge("FRONT_DESK", input("normal", "general", BILL, "UPI"));

        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, "UPI")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("not-admitted");
                    assertThat(e.getMessage()).isEqualTo(
                            "This patient is not admitted any more. The patient cannot be discharged.");
                });
        verify(discharges).insertFinalBill(any(), any());
        verify(discharges).insertDischarge(any(), any());
        verify(followUps).createForDischarge(any(), any(), any());
    }

    @Test
    void onlyOwnerAndFrontDeskDischargeAndNeverNurseDoctorOrOthers() {
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", null}) {
            assertThatThrownBy(() -> discharge(role, input("normal", "general", BILL, "UPI")))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNothingWritten();
    }

    // ---- the discharge: writes ----

    @Test
    void theWritesHappenInTheDocumentedLockAndInsertOrderWithTheFollowUpLast() {
        deposits = 100_000L;
        stubFrozenRows(BILL, 100_000L, "UPI");

        discharge("OWNER", input("normal", "general", BILL, "UPI"));

        InOrder order = inOrder(wards, admissions, beds, discharges, followUps, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(beds).findForUpdate(TENANT, BED);
        order.verify(admissions).findForUpdate(TENANT, ADMISSION);
        order.verify(discharges).insertFinalBill(eq(TENANT), any());
        order.verify(discharges).insertFinalBillLines(eq(TENANT), any(), any());
        order.verify(discharges).insertDischarge(eq(TENANT), any());
        order.verify(admissions).markDischarged(eq(TENANT), eq(ADMISSION), any());
        order.verify(beds).updateStatus(TENANT, BED, "clean");
        order.verify(followUps).createForDischarge(eq(TENANT), eq(ACTOR), any());
        order.verify(auditLog).record(eq("IPD_DISCHARGED"), anyString(), eq(ADMISSION), eq(null), any());
        verify(patientAccess).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void theDischargeRecordCopiesTypeTemplateAndSignatureFromTheSignOffRow() {
        stubFrozenRows(BILL, 1_000_000L, "UPI");
        Instant signedAt = Instant.parse("2026-11-11T04:00:00Z");
        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(Optional.of(
                new SignoffRow(SIGNOFF, ADMISSION, "referred", "surgical", DOCTOR, signedAt)));
        when(settings.findDischargeSettings(TENANT)).thenReturn(Optional.of(new DischargeSettings(null, true, 10)));

        discharge("OWNER", input("referred", "surgical", BILL, "UPI"));

        ArgumentCaptor<NewDischarge> captor = ArgumentCaptor.forClass(NewDischarge.class);
        verify(discharges).insertDischarge(eq(TENANT), captor.capture());
        NewDischarge d = captor.getValue();
        assertThat(d.admissionId()).isEqualTo(ADMISSION);
        assertThat(d.signoffId()).isEqualTo(SIGNOFF);
        assertThat(d.type()).isEqualTo("referred");
        assertThat(d.templateKey()).isEqualTo("surgical");
        assertThat(d.signedBy()).isEqualTo(DOCTOR);
        assertThat(d.signedAt()).isEqualTo(signedAt);
        assertThat(d.followUpDays()).isEqualTo(10);
        assertThat(d.completedBy()).isEqualTo(ACTOR);
    }

    @Test
    void theFollowUpIsDueOnTheIstDischargeDayPlusTheSetupDaysForTheAdmittingDoctor() {
        stubFrozenRows(BILL, 1_000_000L, "UPI");
        when(settings.findDischargeSettings(TENANT)).thenReturn(Optional.of(new DischargeSettings(null, true, 10)));

        discharge("OWNER", input("normal", "general", BILL, "UPI"));

        ArgumentCaptor<DischargeFollowUp> captor = ArgumentCaptor.forClass(DischargeFollowUp.class);
        verify(followUps).createForDischarge(eq(TENANT), eq(ACTOR), captor.capture());
        DischargeFollowUp followUp = captor.getValue();
        assertThat(followUp.sourceAdmissionId()).isEqualTo(ADMISSION);
        assertThat(followUp.patientId()).isEqualTo(PATIENT);
        assertThat(followUp.doctorMembershipId()).isEqualTo(DOCTOR);
        assertThat(followUp.dueDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(followUp.reason()).isEqualTo("Review after discharge · " + REASON);
        assertThat(followUp.toString()).doesNotContain(REASON);
        ArgumentCaptor<NewDischarge> stored = ArgumentCaptor.forClass(NewDischarge.class);
        verify(discharges).insertDischarge(eq(TENANT), stored.capture());
        assertThat(stored.getValue().followUpDue()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    @Test
    void theFollowUpDayIsTheIstDayEvenWhenUtcIsStillYesterday() {
        // 11 Nov 2026 00:30 AM IST is 10 Nov 19:00 UTC.
        Instant lateNight = Instant.parse("2026-11-10T19:00:00Z");
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(lateNight, ZoneOffset.UTC));
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdBilling billing = new IpdBilling(mock(ChargeRepository.class), mock(TransferRepository.class), catalog);
        AdmissionService sheets = new AdmissionService(admissions, beds, wards, settings, ownerGuard, auditLog, team,
                mock(PatientLookupPort.class), patientAccess, mock(ActorDirectoryPort.class), gate, catalog,
                mock(ChargeRepository.class), billing, discharges, mock(EmergencyService.class),
                mock(VitalsRepository.class), mock(AdmittedCards.class), mock(CardSetups.class), ScopeStubs.unscoped(), ScopeStubs.repository(),
                mock(com.sahaj.opd.api.EmrAdmissionRequestPort.class));
        DischargeService lateService = new DischargeService(admissions, beds, wards, settings, discharges, ownerGuard,
                auditLog, team, mock(PatientLookupPort.class), patientAccess, mock(ActorDirectoryPort.class),
                followUps, gate, catalog, billing, sheets, escalations, events, guard);
        deposits = 0L;
        // Admitted 9 Nov 11:00 IST; at 11 Nov 00:30 IST the midnight cut is day 3 as well.
        stubFrozenRows(BILL, 0L, "UPI");
        signerIs("OWNER", true);

        lateService.discharge(TENANT, ACTOR, "OWNER", ADMISSION, input("normal", "general", BILL, "UPI"));

        ArgumentCaptor<DischargeFollowUp> captor = ArgumentCaptor.forClass(DischargeFollowUp.class);
        verify(followUps).createForDischarge(eq(TENANT), eq(ACTOR), captor.capture());
        assertThat(captor.getValue().dueDate()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    void aVeryLongReasonIsCutTo200CodePointsWithoutSplittingACharacter() {
        reason = "😀".repeat(200);
        stubFrozenRows(BILL, 1_000_000L, "UPI");

        discharge("OWNER", input("normal", "general", BILL, "UPI"));

        ArgumentCaptor<DischargeFollowUp> captor = ArgumentCaptor.forClass(DischargeFollowUp.class);
        verify(followUps).createForDischarge(eq(TENANT), eq(ACTOR), captor.capture());
        String sent = captor.getValue().reason();
        assertThat(sent.codePointCount(0, sent.length())).isEqualTo(200);
        assertThat(sent).startsWith("Review after discharge · ");
        assertThat(Character.isHighSurrogate(sent.charAt(sent.length() - 2))).isTrue();
        assertThat(Character.isLowSurrogate(sent.charAt(sent.length() - 1))).isTrue();
    }

    @Test
    void aDeathCreatesNoFollowUpAndStoresNoDueDate() {
        stubFrozenRows(BILL, 1_000_000L, "UPI");
        signed("death", "general");

        discharge("OWNER", input("death", "general", BILL, "UPI"));

        verify(followUps, never()).createForDischarge(any(), any(), any());
        ArgumentCaptor<NewDischarge> captor = ArgumentCaptor.forClass(NewDischarge.class);
        verify(discharges).insertDischarge(eq(TENANT), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("death");
        assertThat(captor.getValue().followUpDue()).isNull();
        verify(admissions).markDischarged(eq(TENANT), eq(ADMISSION), any());
        verify(beds).updateStatus(TENANT, BED, "clean");
    }

    @Test
    void aFailedFollowUpPropagatesSoTheWholeTransactionRollsBack() {
        stubFrozenRows(BILL, 1_000_000L, "UPI");
        when(followUps.createForDischarge(eq(TENANT), eq(ACTOR), any())).thenThrow(new IllegalArgumentException("bad"));

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, "UPI")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void theAuditRowHasExactlyTheDocumentedKeys() {
        deposits = 100_000L;
        stubFrozenRows(BILL, 100_000L, "UPI");

        discharge("OWNER", input("normal", "general", BILL, "UPI"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_DISCHARGED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("admissionId", "ipNumber", "type", "totalPaise", "balancePaise",
                "mode", "bedCode");
        assertThat(after.getValue()).containsEntry("totalPaise", BILL).containsEntry("balancePaise", 380_000L)
                .containsEntry("mode", "UPI").containsEntry("bedCode", "101-A").containsEntry("type", "normal");
        assertThat(after.getValue().values().toString()).doesNotContain("Test Patient").doesNotContain("9000000001")
                .doesNotContain("Chest pain");
    }

    @Test
    void theAnswerIsTheDischargedAdmissionFrozenFromTheStoredBill() {
        deposits = 100_000L;
        stubFrozenRows(BILL, 100_000L, "UPI");

        Detail detail = discharge("OWNER", input("normal", "general", BILL, "UPI"));

        assertThat(detail.status()).isEqualTo("DISCHARGED");
        assertThat(detail.dischargedAt()).isEqualTo(NOW);
        assertThat(detail.discharge()).isNotNull();
        assertThat(detail.discharge().type()).isEqualTo("normal");
        assertThat(detail.discharge().followUpDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(detail.finalBill().totalPaise()).isEqualTo(BILL);
        assertThat(detail.canStartDischarge()).isFalse();
        assertThat(detail.canCollectDeposit()).isFalse();
        assertThat(detail.canAddCharge()).isFalse();
        assertThat(detail.canTransfer()).isFalse();
        assertThat(detail.depositLow()).isFalse();
        assertThat(detail.goingHomeToday()).isFalse();
        assertThat(detail.dayNumber()).isEqualTo(3);
        assertThat(detail.runningBillPaise()).isEqualTo(BILL);
    }

    // ---- SAH-33: an early warning escalation that is not closed (ADR-007 D6) ----

    private static final UUID ESCALATION = UUID.randomUUID();

    /** An escalation raised at 03:30, before the default sign-off at 04:00. */
    private void escalationIs(String status) {
        escalationIs(status, Instant.parse("2026-11-11T03:30:00Z"), null);
    }

    private void escalationIs(String status, Instant raisedAt, Instant reopenedAt) {
        when(escalations.findOpen(TENANT, ADMISSION)).thenReturn(Optional.of(new EscalationRepository.EscalationRow(
                ESCALATION, ADMISSION, 8, com.sahaj.ipd.domain.EarlyWarningScore.Risk.HIGH, 15, status,
                raisedAt, reopenedAt)));
        when(escalations.closeAtDischarge(TENANT, ESCALATION, ACTOR)).thenReturn(true);
    }

    private void settledDeposit() {
        deposits = BILL;
        stubFrozenRows(BILL, BILL, null);
    }

    @Test
    void aNormalDischargeWithAnEscalationNotClosedIs409EscalationOpenAndNothingIsWritten() {
        settledDeposit();
        for (String status : new String[] {"OPEN", "ACK"}) {
            escalationIs(status);

            assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, null)))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("escalation-open");
                        assertThat(e.getMessage()).isEqualTo("This patient has an early warning escalation that is "
                                + "not closed. A doctor must record the response first.");
                    });
        }
        verifyNothingWritten();
        verify(escalations, never()).closeAtDischarge(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void theEscalationIsReadOnlyAfterTheAdmissionRowLock() {
        settledDeposit();
        escalationIs("OPEN");

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, null)))
                .isInstanceOf(ConflictException.class);

        InOrder order = inOrder(wards, beds, admissions, escalations);
        order.verify(wards).lockTenant(TENANT);
        order.verify(beds).findForUpdate(TENANT, BED);
        order.verify(admissions).findForUpdate(TENANT, ADMISSION);
        order.verify(escalations).findOpen(TENANT, ADMISSION);
    }

    @Test
    void theEscalationCheckComesAfterTheSummaryCheckAndBeforeTheBillCheck() {
        settledDeposit();
        escalationIs("OPEN");
        unsigned();
        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));

        signed("normal", "general");
        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", 1, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("escalation-open"));
    }

    @Test
    void aFrontDeskUserCannotSkipTheRefusalByChoosingAnotherTypeThanTheDoctorSigned() {
        settledDeposit();
        escalationIs("OPEN");
        signed("normal", "general");

        assertThatThrownBy(() -> discharge("FRONT_DESK", input("death", "general", BILL, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("summary-not-signed"));
        verify(escalations, never()).closeAtDischarge(any(), any(), any());
    }

    @Test
    void aNormalDischargeWithNoEscalationGoesThroughAndWritesNoEscalationEventOrAudit() {
        settledDeposit();

        discharge("FRONT_DESK", input("normal", "general", BILL, null));

        verify(escalations, never()).closeAtDischarge(any(), any(), any());
        verifyNoInteractions(events);
        verify(auditLog, never()).record(eq("IPD_ESCALATION_CLOSED_AT_DISCHARGE"), any(), any(), any(), any());
        verify(auditLog).record(eq("IPD_DISCHARGED"), any(), any(), any(), any());
    }

    @Test
    void deathLamaAndReferredCloseTheEscalationAtDischargeWithThePlainEventText() {
        String[][] cases = {{"death", "death"}, {"lama", "on request (lama)"}, {"referred", "referred"}};
        for (String[] c : cases) {
            org.mockito.Mockito.reset(events, auditLog, escalations);
            discharged = false;
            signed(c[0], "general");
            settledDeposit();
            escalationIs("OPEN");

            discharge("OWNER", input(c[0], "general", BILL, null));

            verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_ESC,
                    "Escalation closed at discharge (" + c[1] + ") · no doctor response recorded", ACTOR);
            verify(escalations).closeAtDischarge(TENANT, ESCALATION, ACTOR);
        }
    }

    @Test
    void theCloseAtDischargeRunsAfterTheAdmissionIsMarkedDischargedAndBeforeTheBedIsReleased() {
        signed("lama", "general");
        settledDeposit();
        escalationIs("ACK");

        discharge("FRONT_DESK", input("lama", "general", BILL, null));

        InOrder order = inOrder(admissions, escalations, events, beds);
        order.verify(admissions).markDischarged(eq(TENANT), eq(ADMISSION), any());
        order.verify(escalations).closeAtDischarge(TENANT, ESCALATION, ACTOR);
        order.verify(events).insert(eq(TENANT), eq(ADMISSION), eq("esc"), anyString(), eq(ACTOR));
        order.verify(beds).updateStatus(TENANT, BED, "clean");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCloseAtDischargeIsAuditedWithIdsScoreRiskStatusAndTypeOnly() {
        signed("referred", "general");
        settledDeposit();
        escalationIs("ACK");

        discharge("OWNER", input("referred", "general", BILL, null));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ESCALATION_CLOSED_AT_DISCHARGE"), eq("IPD_EWS_ESCALATION"), eq(ESCALATION),
                org.mockito.ArgumentMatchers.isNull(), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("escalationId", "admissionId", "ewsScore", "ewsRisk",
                "statusBefore", "dischargeType");
        assertThat(after.getValue()).containsEntry("ewsScore", 8).containsEntry("ewsRisk", "HIGH")
                .containsEntry("statusBefore", "ACK").containsEntry("dischargeType", "referred");
        assertThat(after.getValue().values().toString()).doesNotContain("Test Patient").doesNotContain("Chest pain");
        verify(auditLog).record(eq("IPD_DISCHARGED"), any(), any(), any(), any());
    }

    @Test
    void aFailedCloseAtDischargeStopsTheWholeDischarge() {
        signed("death", "general");
        settledDeposit();
        escalationIs("OPEN");
        when(escalations.closeAtDischarge(TENANT, ESCALATION, ACTOR)).thenReturn(false);

        assertThatThrownBy(() -> discharge("OWNER", input("death", "general", BILL, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(beds, never()).updateStatus(any(), any(), any());
    }

    // ---- SAH-33 review fix R2: the sign-off must be later than the escalation ----

    private static final String AFTER_SIGNOFF = "This patient has an early warning escalation that started after the "
            + "doctor signed the summary. A doctor must record the response or sign the summary again.";

    @Test
    void deathLamaAndReferredAreRefusedWhenTheEscalationWasRaisedAfterTheSignoff() {
        for (String type : new String[] {"death", "lama", "referred"}) {
            org.mockito.Mockito.reset(escalations);
            signed(type, "general");
            settledDeposit();
            escalationIs("OPEN", Instant.parse("2026-11-11T04:00:01Z"), null);

            assertThatThrownBy(() -> discharge("FRONT_DESK", input(type, "general", BILL, null)))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.problemType()).isEqualTo("escalation-open");
                        assertThat(e.getMessage()).isEqualTo(AFTER_SIGNOFF);
                    });
        }
        verifyNothingWritten();
        verify(escalations, never()).closeAtDischarge(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void equalInstantsDoNotCountAsCoveredAndAStrictlyLaterSignoffDoes() {
        signed("lama", "general");
        settledDeposit();
        escalationIs("OPEN", Instant.parse("2026-11-11T04:00:00Z"), null);
        assertThatThrownBy(() -> discharge("OWNER", input("lama", "general", BILL, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(AFTER_SIGNOFF));

        escalationIs("OPEN", Instant.parse("2026-11-11T03:59:59.999Z"), null);
        discharge("OWNER", input("lama", "general", BILL, null));
        verify(escalations).closeAtDischarge(TENANT, ESCALATION, ACTOR);
    }

    @Test
    void aReopenedEscalationStartsItsClockAtTheReopenTimeNotTheFirstRaise() {
        signed("referred", "general");
        settledDeposit();
        // raised long before the sign-off, re-opened after it
        escalationIs("OPEN", Instant.parse("2026-11-11T01:00:00Z"), Instant.parse("2026-11-11T04:10:00Z"));

        assertThatThrownBy(() -> discharge("OWNER", input("referred", "general", BILL, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(AFTER_SIGNOFF));
    }

    @Test
    void aNormalDischargeKeepsItsOwnTextWhateverTheSignoffTime() {
        settledDeposit();
        escalationIs("OPEN", Instant.parse("2026-11-11T04:30:00Z"), null);

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, null)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessage())
                        .isEqualTo(DischargeService.ESCALATION_OPEN_MESSAGE));
    }

    @Test
    void theSheetShowsSignoffBeforeEscalationOnlyWithAnEscalationAndASignatureThatDoesNotCoverIt() {
        assertThat(service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION).signoffBeforeEscalation()).isFalse();
        escalationIs("OPEN", Instant.parse("2026-11-11T03:00:00Z"), null);
        assertThat(service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION).signoffBeforeEscalation()).isFalse();
        escalationIs("OPEN", Instant.parse("2026-11-11T04:00:00Z"), null);
        assertThat(service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION).signoffBeforeEscalation()).isTrue();
        escalationIs("ACK", Instant.parse("2026-11-11T03:00:00Z"), Instant.parse("2026-11-11T05:00:00Z"));
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).signoffBeforeEscalation()).isTrue();
        unsigned();
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).signoffBeforeEscalation()).isFalse();
    }

    @Test
    void aRepeatedSignoffIsAppendedWhenItIsOlderThanAnEscalationNotClosed() {
        signed("normal", "general");
        escalationIs("OPEN", Instant.parse("2026-11-11T04:30:00Z"), null);
        SignoffRow made = new SignoffRow(UUID.randomUUID(), ADMISSION, "normal", "general", ACTOR, NOW);
        when(discharges.insertSignoff(TENANT, ADMISSION, "normal", "general", ACTOR)).thenReturn(made);

        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));

        verify(discharges).insertSignoff(TENANT, ADMISSION, "normal", "general", ACTOR);
        verify(auditLog).record(eq("IPD_DISCHARGE_SIGNED"), anyString(), eq(made.id()), eq(null), any());
    }

    @Test
    void aRepeatedSignoffStaysANoOpWhenItIsLaterThanTheEscalation() {
        signed("normal", "general");
        escalationIs("OPEN", Instant.parse("2026-11-11T03:59:59Z"), null);

        service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION, new SignoffInput("normal", "general"));

        verify(discharges, never()).insertSignoff(any(), any(), any(), any(), any());
    }

    // ---- SAH-33 review fix R3: the membership is read again, first ----

    @Test
    void aDeactivatedOrDemotedOrMissingMembershipIs403AndNothingIsLockedOrWritten() {
        settledDeposit();
        pinMembership("FRONT_DESK", false);
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, null)))
                .isInstanceOf(AccessDeniedException.class);
        pinMembership("NURSE", true);
        assertThatThrownBy(() -> discharge("FRONT_DESK", input("normal", "general", BILL, null)))
                .isInstanceOf(AccessDeniedException.class);
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, null)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNothingWritten();
        verifyNoInteractions(wards, escalations, events);
    }

    @Test
    void theMembershipIsReadBeforeTheAdvisoryLock() {
        settledDeposit();

        discharge("FRONT_DESK", input("normal", "general", BILL, null));

        InOrder order = inOrder(team, wards);
        order.verify(team).findActiveByUserId(TENANT, ACTOR);
        order.verify(wards).lockTenant(TENANT);
    }

    @Test
    void theSheetTellsWhetherAnEscalationIsNotClosedAsABooleanOnly() {
        assertThat(service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION).openEscalation()).isFalse();
        escalationIs("OPEN");
        assertThat(service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION).openEscalation()).isTrue();
        escalationIs("ACK");
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).openEscalation()).isTrue();
    }

    // ---- SAH-34 patient scope ----

    @Test
    void sheetOutsideScopeIsRefused() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(ACTOR), eq("NURSE"), any(),
                eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(OutsideScopeException.class);

        verifyNothingWritten();
    }

    @Test
    void signoffOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("DOCTOR"),
                any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.signoff(TENANT, ACTOR, "DOCTOR", ADMISSION,
                new SignoffInput("normal", "general"))).isInstanceOf(OutsideScopeException.class);

        verifyNothingWritten();
    }

    @Test
    void dischargeOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("OWNER"),
                any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> discharge("OWNER", input("normal", "general", BILL, "UPI")))
                .isInstanceOf(OutsideScopeException.class);

        verifyNothingWritten();
        verify(beds, never()).findForUpdate(any(), any());
    }
}
