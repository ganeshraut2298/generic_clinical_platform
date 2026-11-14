package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionRepository.DepositRow;
import com.sahaj.ipd.application.AdmissionRepository.NewAdmission;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.CategoryAdds;
import com.sahaj.ipd.application.AdmissionViews.DepositAmounts;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.AdmissionViews.Options;
import com.sahaj.ipd.application.AdmissionViews.Rules;
import com.sahaj.ipd.application.AdmissionViews.RulesInput;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.IpdSettingsRepository.DepositRules;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import com.sahaj.ipd.application.WardRepository.WardRow;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/** SAH-23 admission rules, admit, deposits, lists and the checks in the order of AC4. */
class AdmissionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID BED = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID DEPOSIT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    /** The database clock the mocked vitals repository reports: seconds after the application clock, so they cannot be mixed up. */
    private static final Instant DB_NOW = Instant.parse("2026-11-11T05:30:07.654321Z");
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-09T05:30:00Z");
    private static final Instant AS_OF = Instant.parse("2026-11-11T05:30:09Z");

    private AdmissionRepository admissions;
    private BedRepository beds;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private ModuleEntitlementPort entitlements;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private TeamMemberPort team;
    private PatientLookupPort patients;
    private PatientAccessPort patientAccess;
    private ActorDirectoryPort actors;
    private ChargeRepository charges;
    private DischargeRepository discharges;
    private EmergencyService emergency;
    private VitalsRepository vitals;
    private SafetyService safety;
    private PatientClinicalPort clinicalPatients;
    private AdmissionService service;
    private ScopeGuard guard;
    private BedRow freeBed;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        beds = mock(BedRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        team = mock(TeamMemberPort.class);
        patients = mock(PatientLookupPort.class);
        patientAccess = mock(PatientAccessPort.class);
        actors = mock(ActorDirectoryPort.class);
        discharges = mock(DischargeRepository.class);
        charges = mock(ChargeRepository.class);
        emergency = mock(EmergencyService.class);
        vitals = mock(VitalsRepository.class);
        safety = mock(SafetyService.class);
        clinicalPatients = mock(PatientClinicalPort.class);
        when(safety.databaseNow()).thenReturn(AS_OF);
        when(safety.liveEscalations(TENANT)).thenReturn(List.of());
        when(vitals.findLatestOfAdmitted(TENANT)).thenReturn(Map.of());
        when(vitals.databaseNow()).thenReturn(DB_NOW);
        IpdCatalog catalog = new IpdCatalog(settings);
        guard = ScopeStubs.unscoped();
        service = new AdmissionService(admissions, beds, wards, settings, ownerGuard, auditLog, team, patients,
                patientAccess, actors, new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)),
                catalog, charges, new IpdBilling(charges, mock(TransferRepository.class), catalog), discharges,
                emergency, vitals, new AdmittedCards(clinicalPatients, patients, vitals, safety),
                new CardSetups(mock(CardSetupRepository.class)), guard, ScopeStubs.repository(),
                mock(com.sahaj.opd.api.EmrAdmissionRequestPort.class));

        state("on");
        freeBed = new BedRow(BED, WARD, null, "101-A", "gen", "free");
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.empty());
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001")));
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of(
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 30000)));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(freeBed));
        when(admissions.existsAdmitted(TENANT, PATIENT)).thenReturn(false);
        when(admissions.nextIpNo(eq(TENANT), anyString())).thenReturn(42);
        when(admissions.insert(eq(TENANT), any())).thenReturn(ADMISSION);
        when(admissions.insertDeposit(eq(TENANT), eq(ADMISSION), anyLong(), anyString(), any())).thenReturn(DEPOSIT);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("Self", "planned", "gen", 1_000_000L)));
        when(admissions.findDeposits(TENANT, ADMISSION)).thenReturn(List.of(
                new DepositRow(DEPOSIT, 1_000_000L, "UPI", ADMITTED_AT, ACTOR)));
        when(actors.displayName(TENANT, ACTOR)).thenReturn(Optional.of("Front Desk"));
        when(wards.findWards(TENANT)).thenReturn(List.of(new WardRow(WARD, "General ward", "Nursing station 1")));
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    /** SAH-36: the list needs the caller's active membership (the role is read again), so each call sets it first. */
    private AdmissionList listAs(String role, String status) {
        membershipIs(role, true);
        return service.list(TENANT, ACTOR, role, status, null);
    }

    private static List<AdmittedRow> placed(AdmissionRow... rows) {
        List<AdmittedRow> placed = new java.util.ArrayList<>();
        for (AdmissionRow row : rows) {
            placed.add(new AdmittedRow(row, WARD, null));
        }
        return placed;
    }

    private static PatientClinical clinical(String allergy) {
        return new PatientClinical(PATIENT, "UH-0001", "Test Patient", 56, "M", allergy);
    }

    private static AdmissionRow row(String payer, String type, String category, long deposit) {
        return new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", category, "General ward", DOCTOR,
                type, payer, "Chest pain", "ADMITTED", ADMITTED_AT, deposit);
    }

    private static AdmissionInput input(String type, String payer, Long deposit, String mode, Boolean consent,
                                        String reason) {
        return new AdmissionInput(PATIENT, DOCTOR, type, BED, reason, payer, deposit, mode, consent);
    }

    private static AdmissionInput validInput() {
        return input("planned", "Self", 1_000_000L, "UPI", true, "Chest pain");
    }

    // ---- admit ----

    @Test
    void admitIssuesIpNumberStoresAdmissionOccupiesBedAndTakesDeposit() {
        Detail detail = service.admit(TENANT, ACTOR, "FRONT_DESK", validInput());

        InOrder order = inOrder(wards, admissions, beds, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(admissions).nextIpNo(TENANT, "26-27");
        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        order.verify(admissions).insert(eq(TENANT), saved.capture());
        order.verify(beds).markOccupied(TENANT, BED);
        order.verify(admissions).insertDeposit(TENANT, ADMISSION, 1_000_000L, "UPI", ACTOR);
        assertThat(saved.getValue().ipNumber()).isEqualTo("IP/26-27/0042");
        assertThat(saved.getValue().admittedBy()).isEqualTo(ACTOR);
        assertThat(saved.getValue().doctorMembershipId()).isEqualTo(DOCTOR);
        assertThat(saved.getValue().reason()).isEqualTo("Chest pain");

        assertThat(detail.ipNumber()).isEqualTo("IP/26-27/0042");
        assertThat(detail.patientName()).isEqualTo("Test Patient");
        assertThat(detail.mobile()).isEqualTo("9000000001");
        assertThat(detail.depositState()).isEqualTo("OK");
        assertThat(detail.minimumDepositPaise()).isEqualTo(1_000_000L);
        assertThat(detail.dayNumber()).isEqualTo(3);
        assertThat(detail.deposits()).hasSize(1);
        assertThat(detail.deposits().get(0).collectedByName()).isEqualTo("Front Desk");
        assertThat(detail.canCollectDeposit()).isTrue();
    }

    @Test
    void admitAuditsIdsTypePayerBedAndDepositButNeverPatientMobileOrReason() {
        service.admit(TENANT, ACTOR, "OWNER", validInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ADMITTED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("ipNumber", "IP/26-27/0042")
                .containsEntry("admissionType", "planned").containsEntry("payer", "Self")
                .containsEntry("bedCode", "101-A").containsEntry("depositPaise", 1_000_000L)
                .containsEntry("depositMode", "UPI").containsEntry("source", "DIRECT");
        assertThat(after.getValue().keySet()).doesNotContain("reason", "patientName", "name", "mobile");
        assertThat(after.getValue().values().toString()).doesNotContain("Test Patient", "9000000001", "Chest pain");
    }

    @Test
    void admitWithoutDepositForInsuranceStoresNoDepositAndShowsNotNeeded() {
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row("Insurance", "planned", "gen", 0)));
        when(admissions.findDeposits(TENANT, ADMISSION)).thenReturn(List.of());

        Detail detail = service.admit(TENANT, ACTOR, "NURSE",
                input("planned", "Insurance", null, null, true, null));

        verify(admissions, never()).insertDeposit(any(), any(), anyLong(), anyString(), any());
        assertThat(detail.depositState()).isEqualTo("NOT_NEEDED");
        assertThat(detail.canCollectDeposit()).isFalse();
        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo("Under evaluation");
    }

    @Test
    void blankReasonIsStoredAsUnderEvaluationAndReasonIsTrimmed() {
        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "Cash", true, "   "));
        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "Cash", true, "  Fever  "));

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions, org.mockito.Mockito.times(2)).insert(eq(TENANT), saved.capture());
        assertThat(saved.getAllValues()).extracting(NewAdmission::reason).containsExactly("Under evaluation", "Fever");
    }

    @Test
    void aReasonOfNoBreakSpacesIsBlankAndTheReasonIsStrippedOfSpaceSeparators() {
        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "Cash", true, "\u00a0\u3000"));
        service.admit(TENANT, ACTOR, "OWNER",
                input("planned", "Self", 1_000_000L, "Cash", true, "\u00a0\u2007Fever\u202f"));

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions, org.mockito.Mockito.times(2)).insert(eq(TENANT), saved.capture());
        assertThat(saved.getAllValues()).extracting(NewAdmission::reason).containsExactly("Under evaluation", "Fever");
    }

    @Test
    void reasonOver200CharactersIsRejected() {
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                input("planned", "Self", 1_000_000L, "UPI", true, "x".repeat(201))))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    }

    @Test
    void minimumAddsTheBedCategoryAndShowsRupeesInIndianGrouping() {
        BedRow icu = new BedRow(BED, WARD, null, "ICU-1", "icu", "free");
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(icu));

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                input("maternity", "Self", 1_000_000L, "UPI", true, null)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("depositPaise");
                    assertThat(e.getMessage()).isEqualTo("The deposit is below the minimum of ₹25,000. "
                            + "Collect at least that, or choose Insurance or Corporate.");
                });
        verify(admissions, never()).nextIpNo(any(), anyString());
    }

    @Test
    void depositEqualToTheMinimumIsAccepted() {
        BedRow icu = new BedRow(BED, WARD, null, "ICU-1", "icu", "free");
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(icu));

        service.admit(TENANT, ACTOR, "OWNER", input("maternity", "Self", 2_500_000L, "Card", true, null));

        verify(admissions).insertDeposit(TENANT, ADMISSION, 2_500_000L, "Card", ACTOR);
    }

    @Test
    void emergencyMayBeAdmittedFirstOnlyWhenTheClinicAllowsIt() {
        service.admit(TENANT, ACTOR, "OWNER", input("emergency", "Self", null, null, true, null));
        verify(admissions).insert(eq(TENANT), any());

        DepositRules strict = new DepositRules(1_000_000L, 500_000L, 300_000L, 1_500_000L, 0L, 200_000L, 500_000L,
                1_000_000L, false, 80, true);
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(strict));
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                input("emergency", "Self", null, null, true, null)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.getMessage())
                        .startsWith("The deposit is below the minimum of ₹5,000."));
    }

    @Test
    void checksRunInTheOrderOfAc4() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.empty());
        when(admissions.existsAdmitted(TENANT, PATIENT)).thenReturn(true);
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of());
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.empty());
        AdmissionInput bad = input("planned", "Self", 0L, null, false, null);

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", bad)).isInstanceOf(NotFoundException.class);

        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "P", 1, "M", null)));
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("already-admitted");
                    assertThat(e.getMessage()).isEqualTo("This patient is already admitted.");
                });

        when(admissions.existsAdmitted(TENANT, PATIENT)).thenReturn(false);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.field()).isEqualTo("doctorMembershipId"));

        when(team.findActiveDoctors(TENANT)).thenReturn(List.of(new TeamMember(DOCTOR, "Dr", "DOCTOR", true, 0)));
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("bed-not-free");
                    assertThat(e.getMessage()).isEqualTo("That bed is no longer free. Pick another.");
                });

        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(freeBed));
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("consentSigned");
                    assertThat(e.getMessage()).isEqualTo("Record the admission consent first");
                });

        AdmissionInput consented = input("planned", "Self", 0L, null, true, null);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", consented))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.field()).isEqualTo("depositPaise"));
    }

    @Test
    void aMergedAwayPatientCannotBeAdmitted() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(
                new PatientSummary(PATIENT, "Test Patient", 56, "M", "9000000001", true)));

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("patient-merged");
                    assertThat(e.getMessage())
                            .isEqualTo("This patient record was merged into another. Use the kept record.");
                });
        verify(admissions, never()).insert(any(), any());
    }

    @Test
    void anIpNumberClashIsAServerErrorAndNotAlreadyAdmitted() {
        doThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"uq_admission_tenant_ip_number\""))
                .when(admissions).insert(eq(TENANT), any());

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Test Patient").hasMessageNotContaining("9000000001");
        verify(beds, never()).markOccupied(any(), any());
    }

    @Test
    void aControlCharacterAtEitherEndOfTheReasonIsRejectedNotStrippedAway() {
        for (String bad : new String[] {"Fever\u0007", "\u0007Fever", "  Fever\u0007  ", "\u0007", "\n", "Fever\t",
                "Fever​", "﻿Fever", "Fever ", "a b", "؜x", "x⁠", "x⁤"}) {
            assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                    input("planned", "Self", 1_000_000L, "UPI", true, bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("reason"));
        }
        verify(wards, never()).lockTenant(any());
        verify(admissions, never()).insert(any(), any());
    }

    @Test
    void aReasonInDevanagariWithAZeroWidthJoinerIsAccepted() {
        String reason = "क्‍ष दर्द";

        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "UPI", true, reason));

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo(reason);
    }

    @Test
    void aJoinerInTheReasonThatIsFirstLastDoubledOrNotAfterAViramaIsRejected() {
        for (String bad : new String[] {"IC\u200DU", "\u200Dfever", "fever\u200C", "\u0915\u094D\u200D",
                "\u0915\u094D\u200D\u200D\u0937", "\u0915\u200C\u0937"}) {
            assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                    input("planned", "Self", 1_000_000L, "UPI", true, bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("reason"));
        }
        verify(admissions, never()).insert(any(), any());
    }

    @Test
    void theReasonIsStoredInNfc() {
        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "UPI", true, "Cafe\u0301 pain"));

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo("Caf\u00E9 pain");
    }

    @Test
    void plainSpacesAroundTheReasonAreStripped() {
        service.admit(TENANT, ACTOR, "OWNER", input("planned", "Self", 1_000_000L, "UPI", true, " \u2003Fever\u2003 "));

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo("Fever");
    }

    @Test
    void controlAndTextDirectionCharactersInTheReasonAreRejected() {
        for (String bad : new String[] {"Fever\nNurse says ok", "a\u0000b", "x\u0085y", "pay \u202Etxt", "\u2066x",
                "x\u200Fy", "tab\there", "del\u007Fx"}) {
            assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                    input("planned", "Self", 1_000_000L, "UPI", true, bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("reason"));
        }
        service.admit(TENANT, ACTOR, "OWNER",
                input("planned", "Self", 1_000_000L, "UPI", true, "Dengue, plt 80k (\u0915\u0930)"));
        verify(admissions, org.mockito.Mockito.times(1)).insert(eq(TENANT), any());
    }

    @Test
    void inputIsValidatedBeforeTheClinicLockIsTaken() {
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                input("other", "Self", 1_000_000L, "UPI", true, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER",
                input("planned", "Self", 1_000_050L, "UPI", true, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", null)).isInstanceOf(InvalidInputException.class);
        verify(wards, never()).lockTenant(any());

        service.admit(TENANT, ACTOR, "OWNER", validInput());
        verify(wards).lockTenant(TENANT);
    }

    @Test
    void theIpNumberIsBuiltFromOneFinancialYear() {
        service.admit(TENANT, ACTOR, "OWNER", validInput());

        verify(admissions, org.mockito.Mockito.times(1)).nextIpNo(eq(TENANT), anyString());
    }

    @Test
    void anOccupiedBedIsNotFree() {
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(
                new BedRow(BED, WARD, null, "101-A", "gen", "occ")));

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("bed-not-free"));
        verify(beds, never()).markOccupied(any(), any());
    }

    @Test
    void aRaceOnTheOneAdmissionPerPatientIndexIsAlreadyAdmitted() {
        doThrow(new DuplicateKeyException("ux_admission_tenant_patient_admitted"))
                .when(admissions).insert(eq(TENANT), any());

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("already-admitted"));
        verify(beds, never()).markOccupied(any(), any());
    }

    @Test
    void malformedInputIsRejectedBeforeAnyLookup() {
        List<AdmissionInput> bad = List.of(
                new AdmissionInput(null, DOCTOR, "planned", BED, null, "Self", null, null, true),
                new AdmissionInput(PATIENT, null, "planned", BED, null, "Self", null, null, true),
                new AdmissionInput(PATIENT, DOCTOR, "other", BED, null, "Self", null, null, true),
                new AdmissionInput(PATIENT, DOCTOR, "planned", null, null, "Self", null, null, true),
                new AdmissionInput(PATIENT, DOCTOR, "planned", BED, null, "Cheque", null, null, true),
                input("planned", "Self", 1_000_050L, "UPI", true, null),
                input("planned", "Self", -100L, "UPI", true, null),
                input("planned", "Self", 100_000_100L, "UPI", true, null),
                input("planned", "Self", 1_000_000L, null, true, null),
                input("planned", "Self", 1_000_000L, "Cheque", true, null));
        for (AdmissionInput in : bad) {
            assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", in))
                    .isInstanceOf(InvalidInputException.class);
        }
        verify(patients, never()).find(any(), any());
    }

    @Test
    void readonlyAndOffGateEveryEndpoint() {
        state("readonly");
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "Cash"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirmRules(TENANT, ACTOR)).isInstanceOf(ConflictException.class);
        verify(wards, never()).lockTenant(any());
        assertThat(service.rules(TENANT)).isNotNull();
        assertThat(listAs("OWNER", null).canAdmit()).isFalse();

        state("off");
        assertThatThrownBy(() -> service.rules(TENANT)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "OWNER", validInput()))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> listAs("OWNER", null)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.options(TENANT, "FRONT_DESK", null)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.detail(TENANT, ACTOR, "OWNER", ADMISSION)).isInstanceOf(ModuleOffException.class);
    }

    // ---- deposits ----

    @Test
    void depositIsStoredAuditedAndReturnsTheAdmission() {
        Detail detail = service.addDeposit(TENANT, ACTOR, "FRONT_DESK", ADMISSION, 500_000L, "Cash");

        verify(admissions).insertDeposit(TENANT, ADMISSION, 500_000L, "Cash", ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_DEPOSIT_COLLECTED"), eq("IPD_DEPOSIT"), eq(DEPOSIT), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("admissionId", ADMISSION).containsEntry("amountPaise", 500_000L)
                .containsEntry("mode", "Cash");
        assertThat(detail.id()).isEqualTo(ADMISSION);
    }

    @Test
    void depositTakesTheClinicLockBeforeReadingTheAdmission() {
        service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI");

        InOrder order = inOrder(wards, admissions);
        order.verify(wards).lockTenant(TENANT);
        order.verify(admissions).find(TENANT, ADMISSION);
        order.verify(admissions).insertDeposit(TENANT, ADMISSION, 10_000L, "UPI", ACTOR);
    }

    @Test
    void depositIsRefusedForAnAdmissionThatIsNotAdmitted() {
        AdmissionRow discharged = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen",
                "General ward", DOCTOR, "planned", "Self", "x", "DISCHARGED", ADMITTED_AT, 0);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(discharged));

        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("not-admitted"));
        verify(admissions, never()).insertDeposit(any(), any(), anyLong(), anyString(), any());
    }

    @Test
    void depositAmountMustBeWholeRupeesFromOneRupeeToTenLakh() {
        for (Long bad : new Long[] {null, 0L, 50L, 150L, -100L, 100_000_100L}) {
            assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, bad, "UPI"))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("amountPaise"));
        }
        service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 100L, "UPI");
        service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 100_000_000L, "UPI");
        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 100L, "Cheque"))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("mode"));
    }

    @Test
    void depositForAnUnknownAdmissionIsNotFound() {
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", other, 10_000L, "UPI"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- list and detail ----

    @Test
    void listCountsBedsAndShowsDepositStatePerRow() {
        when(beds.findAll(TENANT)).thenReturn(List.of(
                freeBed, new BedRow(UUID.randomUUID(), WARD, null, "101-B", "gen", "occ"),
                new BedRow(UUID.randomUUID(), WARD, null, "101-C", "gen", "occ"),
                new BedRow(UUID.randomUUID(), WARD, null, "101-D", "gen", "blk")));
        UUID second = UUID.randomUUID();
        AdmissionRow due = new AdmissionRow(second, "IP/26-27/0041", PATIENT, BED, "101-B", "semi", "General ward",
                DOCTOR, "planned", "Self", "x", "ADMITTED", ADMITTED_AT, 500_000L);
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 1_000_000L), due,
                row("Corporate", "planned", "gen", 0)));

        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.bedsTotal()).isEqualTo(4);
        assertThat(list.bedsOccupied()).isEqualTo(2);
        assertThat(list.bedsAvailable()).isEqualTo(1);
        assertThat(list.canAdmit()).isTrue();
        assertThat(list.items()).extracting(s -> s.depositState()).containsExactly("OK", "DUE", "NOT_NEEDED");
        assertThat(list.items().get(1).minimumDepositPaise()).isEqualTo(1_200_000L);
        assertThat(list.items().get(0).doctorName()).isEqualTo("Dr Test");
        assertThat(list.items().get(0).categoryName()).isEqualTo("General");
        assertThat(list.items().get(0).age()).isEqualTo(56);
        verify(clinicalPatients, org.mockito.Mockito.times(1)).findAll(eq(TENANT), any());
        verify(patients, never()).findAll(any(), any());
        // The doctors of all rows come in one query, not one per row (code review 6).
        verify(team, org.mockito.Mockito.times(1)).findAll(eq(TENANT), any());
        verify(team, never()).find(any(), any());
        verify(patients, never()).find(any(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void listCanAdmitOnlyForAdmittingRoles() {
        when(beds.findAll(TENANT)).thenReturn(List.of());
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed());

        assertThat(listAs("OWNER", null).canAdmit()).isTrue();
        assertThat(listAs("FRONT_DESK", null).canAdmit()).isTrue();
        assertThat(listAs("DOCTOR", null).canAdmit()).isFalse();
        // BILLING (and every role outside the four) is refused by the list now (SAH-36).
        assertThatThrownBy(() -> listAs("BILLING", null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listRejectsAnyStatusButAdmitted() {
        assertThatThrownBy(() -> listAs("OWNER", "DISCHARGED"))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("status"));
    }

    @Test
    void dayNumberIsOneOnTheAdmissionDayOnTheIstCalendar() {
        Instant lateEvening = Instant.parse("2026-11-10T18:45:00Z");
        AdmissionRow admittedAtIstMidnightPast = new AdmissionRow(ADMISSION, "IP/26-27/0001", PATIENT, BED, "101-A",
                "gen", "General ward", DOCTOR, "planned", "Self", "x", "ADMITTED", lateEvening, 0);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(admittedAtIstMidnightPast));

        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).dayNumber()).isEqualTo(1);
    }

    @Test
    void dayNumberIsTheBillDaysOfTheBedDayCutInTheDetailAndTheList() {
        // Admitted 9 Nov 11:00 IST, now 11 Nov 11:00 IST: calendar day 3, but exactly two 24-hour periods.
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new IpdSettingsRepository.ConfigRow(true, "24h", 0L, 0L)));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 1_000_000L)));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);
        AdmissionList list = listAs("OWNER", "ADMITTED");

        assertThat(detail.bill().billDays()).isEqualTo(2);
        assertThat(detail.dayNumber()).isEqualTo(2);
        assertThat(list.items().get(0).dayNumber()).isEqualTo(2);
    }

    @Test
    void detailShowsMobileReasonAndCollectButtonOnlyForOwnerAndFrontDesk() {
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canCollectDeposit()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION).canCollectDeposit()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).canCollectDeposit()).isFalse();
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canCollectDeposit()).isFalse();
        Detail detail = service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION);
        assertThat(detail.reason()).isEqualTo("Chest pain");
        state("readonly");
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canCollectDeposit()).isFalse();
    }

// ---- SAH-30: the Nursing button, vitals due and the latest score on the admission sheet ----

    private static VitalsRepository.VitalsRow vitalsAt(Instant at, int score, com.sahaj.ipd.domain.EarlyWarningScore.Risk risk) {
        return new VitalsRepository.VitalsRow(UUID.randomUUID(), ADMISSION, 120, 80, 80, 98,
                new java.math.BigDecimal("37.0"), 16, score, risk, false, at, ACTOR);
    }

    @Test
    void theNursingButtonIsForOwnerNurseAndDoctorAndVitalsFieldsAreNullForOtherRoles() {
        when(vitals.findLatest(TENANT, ADMISSION)).thenReturn(Optional.of(
                vitalsAt(NOW.minusSeconds(35 * 60), 6, com.sahaj.ipd.domain.EarlyWarningScore.Risk.MEDIUM)));

        for (String role : new String[] {"OWNER", "NURSE", "DOCTOR"}) {
            Detail detail = service.detail(TENANT, ACTOR, role, ADMISSION);
            assertThat(detail.canOpenNursing()).as(role).isTrue();
            assertThat(detail.vitalsDue()).as(role).isFalse();
            assertThat(detail.latestEws().score()).as(role).isEqualTo(6);
            assertThat(detail.latestEws().risk()).isEqualTo(com.sahaj.ipd.domain.EarlyWarningScore.Risk.MEDIUM);
            assertThat(detail.latestEws().recordedAt()).isEqualTo(NOW.minusSeconds(35 * 60));
        }
        org.mockito.Mockito.clearInvocations(vitals);
        Detail frontDesk = service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION);
        assertThat(frontDesk.canOpenNursing()).isFalse();
        assertThat(frontDesk.vitalsDue()).isNull();
        assertThat(frontDesk.latestEws()).isNull();
        verify(vitals, never()).findLatest(any(), any());
        verify(vitals, never()).databaseNow();
    }

    @Test
    void vitalsDueIsJudgedOnTheDatabaseClockNotTheApplicationClock() {
        // 241 whole minutes before the database clock, but 240 minutes 53 seconds before the application clock
        when(vitals.findLatest(TENANT, ADMISSION)).thenReturn(Optional.of(
                vitalsAt(DB_NOW.minusSeconds(241 * 60), 0, com.sahaj.ipd.domain.EarlyWarningScore.Risk.LOW)));

        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).vitalsDue()).isTrue();
        verify(vitals).databaseNow();
    }

    @Test
    void theNursingButtonStaysInReadOnlyState() {
        state("readonly");

        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).canOpenNursing()).isTrue();
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canOpenNursing()).isTrue();
    }

    @Test
    void anAdmittedPatientWithNoVitalsIsDueAndHasNoScore() {
        Detail detail = service.detail(TENANT, ACTOR, "NURSE", ADMISSION);

        assertThat(detail.vitalsDue()).isTrue();
        assertThat(detail.latestEws()).isNull();
    }

    @Test
    void vitalsAreDueOnlyAfterMoreThan240WholeMinutes() {
        when(vitals.findLatest(TENANT, ADMISSION)).thenReturn(Optional.of(
                vitalsAt(DB_NOW.minusSeconds(240 * 60), 0, com.sahaj.ipd.domain.EarlyWarningScore.Risk.LOW)));
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).vitalsDue()).isFalse();

        when(vitals.findLatest(TENANT, ADMISSION)).thenReturn(Optional.of(
                vitalsAt(DB_NOW.minusSeconds(241 * 60), 0, com.sahaj.ipd.domain.EarlyWarningScore.Risk.LOW)));
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).vitalsDue()).isTrue();
    }

    @Test
    void aDischargedDetailIsNeverDueAndKeepsTheLastScoreForNursingRolesOnly() {
        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        AdmissionRow stored = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen",
                "General ward", DOCTOR, "planned", "Self", "Chest pain", "DISCHARGED", ADMITTED_AT, 900_000L,
                dischargedAt);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(stored));
        UUID billId = UUID.randomUUID();
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.FinalBillRow(billId, ADMISSION, 800_000L, 1_000_000L, -200_000L, "Cash",
                        ACTOR, dischargedAt, 5, "24h", "gen", new java.math.BigDecimal("1.00"))));
        when(discharges.findDischarge(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.DischargeRow(UUID.randomUUID(), ADMISSION, UUID.randomUUID(), "lama",
                        "surgical", DOCTOR, dischargedAt.minusSeconds(60), 7, java.time.LocalDate.of(2026, 11, 17),
                        ACTOR, dischargedAt)));
        when(discharges.findFinalBillLines(TENANT, billId)).thenReturn(List.of());
        when(vitals.findLatest(TENANT, ADMISSION)).thenReturn(Optional.of(
                vitalsAt(dischargedAt.minusSeconds(3600), 9, com.sahaj.ipd.domain.EarlyWarningScore.Risk.HIGH)));

        Detail nurse = service.detail(TENANT, ACTOR, "NURSE", ADMISSION);
        Detail frontDesk = service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION);

        assertThat(nurse.status()).isEqualTo("DISCHARGED");
        assertThat(nurse.canOpenNursing()).isTrue();
        assertThat(nurse.vitalsDue()).isFalse();
        verify(vitals, never()).databaseNow();
        assertThat(nurse.latestEws().score()).isEqualTo(9);
        assertThat(frontDesk.canOpenNursing()).isFalse();
        assertThat(frontDesk.vitalsDue()).isNull();
        assertThat(frontDesk.latestEws()).isNull();
    }

    @Test
    void mobileIsReturnedOnlyToOwnerAndFrontDesk() {
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).mobile()).isEqualTo("9000000001");
        assertThat(service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION).mobile()).isEqualTo("9000000001");
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).mobile()).isNull();
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).mobile()).isNull();
        assertThat(service.admit(TENANT, ACTOR, "NURSE", validInput()).mobile()).isNull();
        assertThat(service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI").mobile())
                .isEqualTo("9000000001");
    }

    @Test
    void openingTheDetailWritesOneAccessRowForThePatientAndTheUser() {
        service.detail(TENANT, ACTOR, "NURSE", ADMISSION);

        verify(patientAccess, org.mockito.Mockito.times(1)).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void addDepositWritesOnePatientRecordAccessRowBecauseItReturnsTheFullSheet() {
        service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI");

        verify(patientAccess, org.mockito.Mockito.times(1)).recordOpened(TENANT, PATIENT, ACTOR);
    }

    @Test
    void addDepositRefusesARoleThatMayNotCollectBeforeAnyLockOrWrite() {
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, role, ADMISSION, 10_000L, "UPI"))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, null, ADMISSION, 10_000L, "UPI"))
                .isInstanceOf(AccessDeniedException.class);

        verify(wards, never()).lockTenant(any());
        verify(admissions, never()).insertDeposit(any(), any(), anyLong(), anyString(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void anAccessRowIsNotWrittenForAdmitOrAFailedDetail() {
        service.admit(TENANT, ACTOR, "OWNER", validInput());
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(TENANT, ACTOR, "OWNER", other)).isInstanceOf(NotFoundException.class);
        state("off");
        assertThatThrownBy(() -> service.detail(TENANT, ACTOR, "OWNER", ADMISSION))
                .isInstanceOf(ModuleOffException.class);

        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void detailOfAnotherClinicsAdmissionIsNotFound() {
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(TENANT, ACTOR, "OWNER", other)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordsNeverPrintReasonOrPatientIdentity() {
        AdmissionRow r = row("Self", "planned", "gen", 0);
        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(r.toString()).doesNotContain("Chest pain").doesNotContain(PATIENT.toString());
        assertThat(detail.toString()).doesNotContain("Chest pain", "Test Patient", "9000000001");
        assertThat(validInput().toString()).doesNotContain("Chest pain").doesNotContain(PATIENT.toString());
        assertThat(new NewAdmission(PATIENT, "IP/1", BED, DOCTOR, "planned", "Self", "Chest pain", ACTOR).toString())
                .doesNotContain("Chest pain");
    }

    // ---- options ----

    @Test
    void optionsListActiveDoctorsAndFreeBedsWithWardCategoryAndRate() {
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed,
                new BedRow(UUID.randomUUID(), WARD, null, "101-B", "gen", "occ"),
                new BedRow(UUID.randomUUID(), WARD, null, "ICU-1", "icu", "free")));
        when(settings.findCategories(TENANT)).thenReturn(List.of(
                new IpdSettingsRepository.CategoryRow("gen", 90_000L, new java.math.BigDecimal("1.00"))));

        Options options = service.options(TENANT, "FRONT_DESK", null);

        assertThat(options.doctors()).extracting(d -> d.name()).containsExactly("Dr Test");
        assertThat(options.beds()).extracting(b -> b.code()).containsExactly("101-A", "ICU-1");
        assertThat(options.beds().get(0).wardName()).isEqualTo("General ward");
        assertThat(options.beds().get(0).categoryName()).isEqualTo("General");
        assertThat(options.beds().get(0).ratePaise()).isEqualTo(90_000L);
        assertThat(options.beds().get(1).ratePaise()).isEqualTo(500_000L);
        assertThat(options.rules().confirmed()).isFalse();
    }

    @Test
    void optionsOrderFreeBedsByCategoryThenRoomThenCode() {
        UUID room1 = UUID.randomUUID();
        UUID room2 = UUID.randomUUID();
        when(wards.findRooms(TENANT)).thenReturn(List.of(new RoomRow(room1, WARD, "Room 101"),
                new RoomRow(room2, WARD, "Room 102")));
        when(beds.findAll(TENANT)).thenReturn(List.of(
                new BedRow(UUID.randomUUID(), WARD, room1, "ICU-1", "icu", "free"),
                new BedRow(UUID.randomUUID(), WARD, room2, "102-B", "gen", "free"),
                new BedRow(UUID.randomUUID(), WARD, null, "NR-A", "gen", "free"),
                new BedRow(UUID.randomUUID(), WARD, room1, "101-C", "pvt", "free"),
                new BedRow(UUID.randomUUID(), WARD, room2, "102-A", "gen", "free"),
                new BedRow(UUID.randomUUID(), WARD, room1, "101-B", "gen", "free"),
                new BedRow(UUID.randomUUID(), WARD, room1, "101-S", "semi", "free"),
                new BedRow(UUID.randomUUID(), WARD, room1, "101-A", "gen", "free"),
                new BedRow(UUID.randomUUID(), WARD, room1, "101-X", "gen", "occ")));

        Options options = service.options(TENANT, "FRONT_DESK", null);

        assertThat(options.beds()).extracting(b -> b.code())
                .containsExactly("101-A", "101-B", "102-A", "102-B", "NR-A", "101-S", "101-C", "ICU-1");
        assertThat(options.beds()).extracting(b -> b.categoryKey())
                .containsExactly("gen", "gen", "gen", "gen", "gen", "semi", "pvt", "icu");
    }

    // ---- SAH-26 transfer additions to the sheet ----

    @Test
    void canTransferIsForOwnerFrontDeskAndNurseWhileAdmittedAndNotReadonly() {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            assertThat(service.detail(TENANT, ACTOR, role, ADMISSION).canTransfer()).as(role).isTrue();
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING"}) {
            assertThat(service.detail(TENANT, ACTOR, role, ADMISSION).canTransfer()).as(role).isFalse();
        }
        state("trial");
        assertThat(service.detail(TENANT, ACTOR, "NURSE", ADMISSION).canTransfer()).isTrue();
        state("readonly");
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canTransfer()).isFalse();
        state("on");
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionRow(ADMISSION,
                "IP/26-27/0042", PATIENT, BED, "101-A", "gen", "General ward", DOCTOR, "planned", "Self",
                "Chest pain", "DISCHARGED", ADMITTED_AT, 0L)));
        stubFrozenBill();
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canTransfer()).isFalse();
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

    @Test
    void detailCarriesTheTransferRulesWithDefaultsWhenNeverSaved() {
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).transferRules())
                .isEqualTo(new TransferRules(true, "same"));

        when(settings.findTransferRules(TENANT)).thenReturn(Optional.of(new TransferRules(false, "next")));

        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).transferRules())
                .isEqualTo(new TransferRules(false, "next"));
    }

    // ---- rules ----

    private static RulesInput rulesInput() {
        return new RulesInput(new DepositAmounts(1_200_000L, 500_000L, 300_000L, 1_500_000L),
                new CategoryAdds(0L, 200_000L, 500_000L, 1_000_000L), true, 90);
    }

    @Test
    void rulesDefaultToThePaiseValuesOfTheStory() {
        Rules rules = service.rules(TENANT);

        assertThat(rules.depositPaise()).isEqualTo(new DepositAmounts(1_000_000L, 500_000L, 300_000L, 1_500_000L));
        assertThat(rules.categoryAddPaise()).isEqualTo(new CategoryAdds(0L, 200_000L, 500_000L, 1_000_000L));
        assertThat(rules.emergencyWithoutDeposit()).isTrue();
        assertThat(rules.alertPercent()).isEqualTo(80);
        assertThat(rules.confirmed()).isFalse();
        verify(settings, never()).upsertDepositRules(any(), any(), any(), any());
    }

    @Test
    void updateRulesSavesAuditsAndKeepsTheConfirmedFlag() {
        DepositRules confirmed = new DepositRules(1_000_000L, 500_000L, 300_000L, 1_500_000L, 0L, 200_000L, 500_000L,
                1_000_000L, true, 80, true);
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(confirmed));

        Rules saved = service.updateRules(TENANT, ACTOR, rulesInput());

        verify(ownerGuard).requireOwner(TENANT, ACTOR);
        verify(wards).lockTenant(TENANT);
        ArgumentCaptor<DepositRules> written = ArgumentCaptor.forClass(DepositRules.class);
        verify(settings).upsertDepositRules(eq(TENANT), written.capture(), eq(ACTOR),
                eq(Instant.parse("2026-11-11T05:30:00.123456Z")));
        assertThat(written.getValue().planned()).isEqualTo(1_200_000L);
        assertThat(written.getValue().alertPercent()).isEqualTo(90);
        assertThat(written.getValue().confirmed()).isTrue();
        assertThat(saved.confirmed()).isTrue();
        verify(auditLog).record(eq("IPD_ADMISSION_RULES_CHANGED"), eq("IPD_CONFIG"), eq(null), any(), any());
    }

    @Test
    void updateRulesWithNoChangeWritesNothing() {
        DepositRules same = new DepositRules(1_200_000L, 500_000L, 300_000L, 1_500_000L, 0L, 200_000L, 500_000L,
                1_000_000L, true, 90, false);
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(same));

        service.updateRules(TENANT, ACTOR, rulesInput());

        verify(settings, never()).upsertDepositRules(any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateRulesRejectsAmountsThatAreNotWholeRupeesOrOutOfRange() {
        Long[] bad = {-100L, 150L, 100_000_100L, null};
        for (Long amount : bad) {
            RulesInput in = new RulesInput(new DepositAmounts(amount, 500_000L, 300_000L, 1_500_000L),
                    new CategoryAdds(0L, 200_000L, 500_000L, 1_000_000L), true, 80);
            assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, in))
                    .isInstanceOf(InvalidInputException.class);
            RulesInput inCat = new RulesInput(new DepositAmounts(100L, 500_000L, 300_000L, 1_500_000L),
                    new CategoryAdds(0L, 200_000L, 500_000L, amount), true, 80);
            assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, inCat))
                    .isInstanceOf(InvalidInputException.class);
        }
        verify(settings, never()).upsertDepositRules(any(), any(), any(), any());
    }

    @Test
    void updateRulesRejectsAlertPercentOutsideFiftyToHundredInFives() {
        for (int percent : new int[] {45, 56, 105, 0}) {
            RulesInput in = new RulesInput(rulesInput().depositPaise(), rulesInput().categoryAddPaise(), true, percent);
            assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, in))
                    .isInstanceOfSatisfying(InvalidInputException.class,
                            e -> assertThat(e.field()).isEqualTo("alertPercent"));
        }
        service.updateRules(TENANT, ACTOR, new RulesInput(rulesInput().depositPaise(), rulesInput().categoryAddPaise(),
                false, 100));
        service.updateRules(TENANT, ACTOR, new RulesInput(rulesInput().depositPaise(), rulesInput().categoryAddPaise(),
                false, 50));
    }

    @Test
    void updateRulesRejectsMissingFields() {
        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR,
                new RulesInput(null, rulesInput().categoryAddPaise(), true, 80)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR,
                new RulesInput(rulesInput().depositPaise(), rulesInput().categoryAddPaise(), null, 80)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void confirmMarksTheRulesConfirmedOnceAndIsIdempotent() {
        Rules first = service.confirmRules(TENANT, ACTOR);

        assertThat(first.confirmed()).isTrue();
        ArgumentCaptor<DepositRules> written = ArgumentCaptor.forClass(DepositRules.class);
        verify(settings).upsertDepositRules(eq(TENANT), written.capture(), eq(ACTOR), any());
        assertThat(written.getValue()).isEqualTo(new DepositRules(1_000_000L, 500_000L, 300_000L, 1_500_000L, 0L,
                200_000L, 500_000L, 1_000_000L, true, 80, true));
        verify(auditLog).record(eq("IPD_ADMISSION_RULES_CONFIRMED"), eq("IPD_CONFIG"), eq(null), any(), any());

        when(settings.findDepositRules(TENANT)).thenReturn(Optional.of(written.getValue()));
        Rules second = service.confirmRules(TENANT, ACTOR);

        assertThat(second.confirmed()).isTrue();
        verify(settings, org.mockito.Mockito.times(1)).upsertDepositRules(any(), any(), any(), any());
        verify(auditLog, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    void ruleWritesRequireTheOwner() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("owner only")).when(ownerGuard)
                .requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.updateRules(TENANT, ACTOR, rulesInput()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirmRules(TENANT, ACTOR)).isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).upsertDepositRules(any(), any(), any(), any());
        verify(wards, never()).lockTenant(any());
    }

    // ---- helpers under test ----

    @Test
    void financialYearRunsAprilToMarchOnTheIstCalendar() {
        assertThat(AdmissionService.financialYear(Instant.parse("2026-11-11T05:30:00Z"))).isEqualTo("26-27");
        assertThat(AdmissionService.financialYear(Instant.parse("2027-03-31T18:29:59Z"))).isEqualTo("26-27");
        assertThat(AdmissionService.financialYear(Instant.parse("2027-03-31T18:30:00Z"))).isEqualTo("27-28");
        assertThat(AdmissionService.financialYear(Instant.parse("2026-03-31T18:30:00Z"))).isEqualTo("26-27");
        assertThat(AdmissionService.financialYear(Instant.parse("2026-03-31T18:29:59Z"))).isEqualTo("25-26");
        assertThat(AdmissionService.financialYear(Instant.parse("2099-05-01T00:00:00Z"))).isEqualTo("99-00");
    }

    @Test
    void ipNumberUsesAFourDigitRunningNumberPerFinancialYear() {
        when(admissions.nextIpNo(TENANT, "26-27")).thenReturn(7);

        service.admit(TENANT, ACTOR, "OWNER", validInput());

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().ipNumber()).isEqualTo("IP/26-27/0007");
    }

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(AdmissionService.rupees(0)).isEqualTo("₹0");
        assertThat(AdmissionService.rupees(80_000)).isEqualTo("₹800");
        assertThat(AdmissionService.rupees(1_000_000)).isEqualTo("₹10,000");
        assertThat(AdmissionService.rupees(12_500_000)).isEqualTo("₹1,25,000");
        assertThat(AdmissionService.rupees(100_000_000)).isEqualTo("₹10,00,000");
    }

    @Test
    void minimumIsBaseOfTheTypePlusTheCategoryAddOn() {
        DepositRules d = DepositRules.DEFAULTS;
        assertThat(d.minimum("planned", "gen")).isEqualTo(1_000_000L);
        assertThat(d.minimum("emergency", "semi")).isEqualTo(700_000L);
        assertThat(d.minimum("daycare", "pvt")).isEqualTo(800_000L);
        assertThat(d.minimum("maternity", "icu")).isEqualTo(2_500_000L);
    }

    // ---- SAH-27 going home today, discharged today and the frozen discharged sheet ----

    private static SignoffRow signoffAt(UUID admission, String instant) {
        return new SignoffRow(UUID.randomUUID(), admission, "normal", "general", DOCTOR, Instant.parse(instant));
    }

    @Test
    void listCountsGoingHomeTodayAndDischargedTodayWithTheIstDayBounds() {
        UUID second = UUID.randomUUID();
        AdmissionRow other = new AdmissionRow(second, "IP/26-27/0041", PATIENT, BED, "101-B", "gen", "General ward",
                DOCTOR, "planned", "Self", "x", "ADMITTED", ADMITTED_AT, 500_000L);
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 1_000_000L), other));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));
        // NOW is 11 Nov 11:00 IST. Signed this morning: going home. Signed yesterday evening IST: not.
        when(discharges.findLatestSignoffsOfAdmitted(TENANT)).thenReturn(Map.of(
                ADMISSION, signoffAt(ADMISSION, "2026-11-11T02:00:00Z"),
                second, signoffAt(second, "2026-11-10T17:00:00Z")));
        when(discharges.countDischargedBetween(TENANT, Instant.parse("2026-11-10T18:30:00Z"),
                Instant.parse("2026-11-11T18:30:00Z"))).thenReturn(4);

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.dischargedToday()).isEqualTo(4);
        assertThat(list.goingHomeToday()).isEqualTo(1);
        assertThat(list.items()).extracting(s -> s.goingHomeToday()).containsExactly(true, false);
        // The list reads the admitted patients only, so a discharged patient is in neither the rows nor the alerts.
        verify(admissions).findAdmittedWithPlacement(TENANT);
        verify(discharges, org.mockito.Mockito.times(1)).findLatestSignoffsOfAdmitted(TENANT);
    }

    @Test
    void anAdmittedDetailIsGoingHomeOnlyWhenTheLatestSignOffIsFromToday() {
        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(
                Optional.of(signoffAt(ADMISSION, "2026-11-11T02:00:00Z")));
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).goingHomeToday()).isTrue();

        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(
                Optional.of(signoffAt(ADMISSION, "2026-11-10T02:00:00Z")));
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).goingHomeToday()).isFalse();

        when(discharges.findLatestSignoff(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).goingHomeToday()).isFalse();
    }

    @Test
    void canStartDischargeIsForTheFourIpdRolesWhileAdmittedAndNotReadOnly() {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            Detail detail = service.detail(TENANT, ACTOR, role, ADMISSION);
            assertThat(detail.canStartDischarge()).as(role).isTrue();
            assertThat(detail.status()).isEqualTo("ADMITTED");
            assertThat(detail.discharge()).isNull();
            assertThat(detail.finalBill()).isNull();
            assertThat(detail.dischargedAt()).isNull();
        }
        assertThat(service.detail(TENANT, ACTOR, "BILLING", ADMISSION).canStartDischarge()).isFalse();
        state("readonly");
        assertThat(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).canStartDischarge()).isFalse();
    }

    @Test
    void aDischargedDetailIsFrozenFromTheStoredFinalBillAndStaysReadableInReadOnly() {
        // The bed is now an ICU bed and the clock says day 3, but the bill was frozen on General at 5 days.
        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        AdmissionRow stored = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "icu",
                "General ward", DOCTOR, "planned", "Self", "Chest pain", "DISCHARGED", ADMITTED_AT, 900_000L,
                dischargedAt);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(stored));
        UUID billId = UUID.randomUUID();
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.FinalBillRow(billId, ADMISSION, 800_000L, 1_000_000L, -200_000L, "Cash",
                        ACTOR, dischargedAt, 5, "24h", "gen", new java.math.BigDecimal("1.00"))));
        when(discharges.findDischarge(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.DischargeRow(UUID.randomUUID(), ADMISSION, UUID.randomUUID(), "lama",
                        "surgical", DOCTOR, dischargedAt.minusSeconds(60), 7, java.time.LocalDate.of(2026, 11, 17),
                        ACTOR, dischargedAt)));
        when(discharges.findFinalBillLines(TENANT, billId)).thenReturn(List.of(
                new RunningBill.BillLine("Bed · General", 80_000L, 5, 400_000L),
                new RunningBill.BillLine("Old negotiated line", 100_000L, 4, 400_000L, true)));
        state("readonly");

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(detail.status()).isEqualTo("DISCHARGED");
        assertThat(detail.dischargedAt()).isEqualTo(dischargedAt);
        assertThat(detail.dayNumber()).isEqualTo(5);
        assertThat(detail.bill().billDays()).isEqualTo(5);
        assertThat(detail.bill().bedDayCut()).isEqualTo("24h");
        assertThat(detail.bill().totalPaise()).isEqualTo(800_000L);
        assertThat(detail.bill().lines()).extracting(l -> l.name())
                .containsExactly("Bed · General", "Old negotiated line");
        assertThat(detail.runningBillPaise()).isEqualTo(800_000L);
        assertThat(detail.categoryName()).isEqualTo("General");
        assertThat(detail.bill().categoryName()).isEqualTo("General");
        assertThat(detail.depositPaise()).isEqualTo(1_000_000L);
        assertThat(detail.finalBill().outcome()).isEqualTo("refunded");
        assertThat(detail.finalBill().balancePaise()).isEqualTo(-200_000L);
        assertThat(detail.finalBill().mode()).isEqualTo("Cash");
        assertThat(detail.finalBill().lines()).hasSize(2);
        assertThat(detail.discharge().type()).isEqualTo("lama");
        assertThat(detail.discharge().templateKey()).isEqualTo("surgical");
        assertThat(detail.discharge().followUpDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 17));
        assertThat(detail.canCollectDeposit()).isFalse();
        assertThat(detail.canAddCharge()).isFalse();
        assertThat(detail.canTransfer()).isFalse();
        assertThat(detail.canStartDischarge()).isFalse();
        assertThat(detail.depositLow()).isFalse();
        assertThat(detail.goingHomeToday()).isFalse();
        // Never recomputed from today's rates or clock.
        verify(charges, never()).findByAdmission(any(), any());
    }

    @Test
    void aDischargedRowWithoutAFrozenBillFromBeforeSah27IsStillReadableAndCannotBeChanged() {
        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionRow(ADMISSION, "IP/26-27/0042",
                PATIENT, BED, "101-A", "gen", "General ward", DOCTOR, "planned", "Self", "x", "DISCHARGED",
                ADMITTED_AT, 0L, dischargedAt)));

        Detail detail = service.detail(TENANT, ACTOR, "OWNER", ADMISSION);

        assertThat(detail.status()).isEqualTo("DISCHARGED");
        assertThat(detail.dischargedAt()).isEqualTo(dischargedAt);
        // The bill is worked out as at the discharge time (admitted 9 Nov, discharged 10 Nov: 2 bill days).
        assertThat(detail.dayNumber()).isEqualTo(2);
        assertThat(detail.discharge()).isNull();
        assertThat(detail.finalBill()).isNull();
        assertThat(detail.canCollectDeposit()).isFalse();
        assertThat(detail.canAddCharge()).isFalse();
        assertThat(detail.canTransfer()).isFalse();
        assertThat(detail.canStartDischarge()).isFalse();
        assertThat(detail.goingHomeToday()).isFalse();
        // Code review 1: no red "further deposit" notice on a discharged patient (a 0 deposit against a bill).
        assertThat(detail.depositLow()).isFalse();
    }

    // ---- SAH-28 admission from the emergency queue ----

    private static final UUID CASE_ID = UUID.randomUUID();

    private static EmergencyCaseRepository.CaseRow emergencyCase(int level, String bay) {
        return new EmergencyCaseRepository.CaseRow(CASE_ID, null, "Ramesh Kulkarni", 52, "M", "9876543210",
                "Crushing chest pain", false, level, bay, "WAITING", ADMITTED_AT, ACTOR, null, null, null, null, null,
                UUID.randomUUID(), 0);
    }

    private AdmissionInput emergencyInput() {
        return new AdmissionInput(PATIENT, DOCTOR, "emergency", BED, "Chest pain", "Self", 500_000L, "Cash", true,
                CASE_ID);
    }

    @Test
    void admitFromAnEmergencyCaseChecksTheCaseAfterTheClinicLockAndBeforeThePatient() {
        EmergencyCaseRepository.CaseRow row = emergencyCase(1, "ER-1");
        when(emergency.lockOpenCase(TENANT, CASE_ID)).thenReturn(row);

        service.admit(TENANT, ACTOR, "NURSE", emergencyInput());

        InOrder order = inOrder(wards, emergency, patients, admissions, beds, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(emergency).lockOpenCase(TENANT, CASE_ID);
        order.verify(patients).find(TENANT, PATIENT);
        order.verify(admissions).nextIpNo(eq(TENANT), anyString());
        order.verify(admissions).insert(eq(TENANT), any());
        order.verify(beds).markOccupied(TENANT, BED);
        order.verify(auditLog).record(eq("IPD_ADMITTED"), any(), eq(ADMISSION), any(), any());
        order.verify(emergency).markAdmitted(TENANT, ACTOR, row, ADMISSION, PATIENT);
    }

    @Test
    void anEmergencyAdmissionIsStoredAndAuditedWithSourceEmergencyAndNotForcedToTheEmergencyType() {
        when(emergency.lockOpenCase(TENANT, CASE_ID)).thenReturn(emergencyCase(2, null));
        AdmissionInput planned = new AdmissionInput(PATIENT, DOCTOR, "planned", BED, "Chest pain", "Self", 1_000_000L,
                "UPI", true, CASE_ID);

        service.admit(TENANT, ACTOR, "OWNER", planned);

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().source()).isEqualTo("EMERGENCY");
        assertThat(saved.getValue().admissionType()).isEqualTo("planned");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ADMITTED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("source", "EMERGENCY");
        assertThat(after.getValue().values().toString()).doesNotContain("Ramesh", "9876543210", "chest pain");
    }

    @Test
    void anAdmissionWithoutAnEmergencyCaseNeverTouchesTheEmergencyService() {
        service.admit(TENANT, ACTOR, "FRONT_DESK", validInput());

        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        verify(admissions).insert(eq(TENANT), saved.capture());
        assertThat(saved.getValue().source()).isEqualTo("DIRECT");
        org.mockito.Mockito.verifyNoInteractions(emergency);
    }

    @Test
    void aClosedOrMissingCaseStopsTheAdmissionBeforeAnythingElseIsLookedUpOrWritten() {
        org.mockito.Mockito.doThrow(EmergencyService.caseClosed()).when(emergency).lockOpenCase(TENANT, CASE_ID);

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "NURSE", emergencyInput()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("case-closed");
                    assertThat(e.getMessage()).isEqualTo("This patient is no longer in the queue.");
                });
        org.mockito.Mockito.doThrow(new NotFoundException("Emergency case not found")).when(emergency)
                .lockOpenCase(TENANT, CASE_ID);
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "NURSE", emergencyInput()))
                .isInstanceOf(NotFoundException.class);

        verify(patients, never()).find(any(), any());
        verify(admissions, never()).insert(any(), any());
        verify(beds, never()).markOccupied(any(), any());
        verify(emergency, never()).markAdmitted(any(), any(), any(), any(), any());
    }

    @Test
    void ifALaterCheckFailsTheCaseIsNotLinked() {
        when(emergency.lockOpenCase(TENANT, CASE_ID)).thenReturn(emergencyCase(1, "ER-1"));
        when(beds.findForUpdate(TENANT, BED)).thenReturn(Optional.of(new BedRow(BED, WARD, null, "101-A", "gen", "occ")));

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "NURSE", emergencyInput()))
                .isInstanceOf(ConflictException.class);

        verify(emergency, never()).markAdmitted(any(), any(), any(), any(), any());
        verify(admissions, never()).insert(any(), any());
    }

    @Test
    void admitRechecksTheAdmitRolesBeforeTheIpdState() {
        state("off");

        for (String role : new String[] {"DOCTOR", "BILLING", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> service.admit(TENANT, ACTOR, role, emergencyInput()))
                    .as(String.valueOf(role)).isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "NURSE", emergencyInput()))
                .isInstanceOf(ModuleOffException.class);
    }

    @Test
    void optionsWithAnEmergencyCaseCarryTheHandoffAfterRoleModuleAndCaseChecks() {
        EmergencyCaseRepository.CaseRow row = emergencyCase(1, "ER-1");
        EmergencyViews.Handoff handoff = new EmergencyViews.Handoff(CASE_ID, "Ramesh Kulkarni", 52, "M", "9876543210",
                "Crushing chest pain", false, 1, "Red", BED);
        when(emergency.findOpenCase(TENANT, CASE_ID)).thenReturn(row);
        when(emergency.handoff(eq(TENANT), eq(row), any())).thenReturn(handoff);
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));

        Options options = service.options(TENANT, "NURSE", CASE_ID);

        assertThat(options.emergencyCase()).isSameAs(handoff);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AdmissionViews.BedOption>> free = ArgumentCaptor.forClass(List.class);
        verify(emergency).handoff(eq(TENANT), eq(row), free.capture());
        assertThat(free.getValue()).extracting(AdmissionViews.BedOption::id).containsExactly(BED);

        assertThatThrownBy(() -> service.options(TENANT, "DOCTOR", CASE_ID)).isInstanceOf(AccessDeniedException.class);
        state("off");
        assertThatThrownBy(() -> service.options(TENANT, "NURSE", CASE_ID)).isInstanceOf(ModuleOffException.class);
    }

    @Test
    void optionsAnswer404AndCaseClosedForAnUnknownOrClosedCaseAndReadNothingElseFirst() {
        org.mockito.Mockito.doThrow(new NotFoundException("Emergency case not found")).when(emergency)
                .findOpenCase(TENANT, CASE_ID);
        assertThatThrownBy(() -> service.options(TENANT, "NURSE", CASE_ID)).isInstanceOf(NotFoundException.class);
        org.mockito.Mockito.doThrow(EmergencyService.caseClosed()).when(emergency).findOpenCase(TENANT, CASE_ID);
        assertThatThrownBy(() -> service.options(TENANT, "NURSE", CASE_ID)).isInstanceOf(ConflictException.class);

        verify(team, never()).findActiveDoctors(any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void optionsWithoutAnEmergencyCaseHaveNoHandoffAndNeverAskTheEmergencyService() {
        Options options = service.options(TENANT, "FRONT_DESK", null);

        assertThat(options.emergencyCase()).isNull();
        org.mockito.Mockito.verifyNoInteractions(emergency);
    }

    @Test
    void theListCarriesTheEmergencyCountsForTheTileAndTheTab() {
        EmergencyViews.Counts counts = new EmergencyViews.Counts(true, 3, 1);
        when(emergency.counts(eq(TENANT), any())).thenReturn(counts);

        assertThat(listAs("DOCTOR", null).emergency()).isEqualTo(counts);
    }

    // ---- SAH-31: the EMR button and the EMR header numbers ----

    private void membershipIs(String role, boolean active) {
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.of(
                new TeamMember(UUID.randomUUID(), "Member", role, active, 0)));
    }

    @Test
    void theEmrButtonNeedsTheTokenRoleAndTheMembershipRoleBothOwnerDoctorOrNurse() {
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            membershipIs(role, true);
            assertThat(service.detail(TENANT, ACTOR, role, ADMISSION).canOpenEmr()).as(role).isTrue();
        }
        membershipIs("OWNER", true);
        for (String role : new String[] {"FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"}) {
            assertThat(service.detail(TENANT, ACTOR, role, ADMISSION).canOpenEmr()).as(role).isFalse();
        }
    }

    @Test
    void theEmrButtonIsHiddenWhenTheMembershipRoleIsOutsideTheThreeInactiveOrMissing() {
        membershipIs("FRONT_DESK", true);
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canOpenEmr()).isFalse();
        membershipIs("DOCTOR", false);
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canOpenEmr()).isFalse();
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.empty());
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canOpenEmr()).isFalse();
    }

    @Test
    void theEmrButtonStaysInReadOnlyStateAndIsNeverShownForADischargedAdmission() {
        membershipIs("DOCTOR", true);
        state("readonly");
        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canOpenEmr()).isTrue();

        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionRow(ADMISSION,
                "IP/26-27/0042", PATIENT, BED, "101-A", "gen", "General ward", DOCTOR, "planned", "Self", "x",
                "DISCHARGED", ADMITTED_AT, 0, dischargedAt)));

        assertThat(service.detail(TENANT, ACTOR, "DOCTOR", ADMISSION).canOpenEmr()).isFalse();
    }

    @Test
    void theEmrButtonRoleIsNotLookedUpForARoleThatCanNeverHaveIt() {
        service.detail(TENANT, ACTOR, "FRONT_DESK", ADMISSION);

        verify(team, never()).findActiveByUserId(any(), any());
    }

    @Test
    void theEmrHeaderIsTheDoctorNameAndTheBilledDaysOfTheRunningBill() {
        AdmissionRow admitted = row("Self", "planned", "gen", 1_000_000L);

        AdmissionService.EmrHeader header = service.emrHeader(TENANT, admitted);

        assertThat(header.doctorName()).isEqualTo("Dr Test");
        assertThat(header.dayNumber()).isEqualTo(service.detail(TENANT, ACTOR, "OWNER", ADMISSION).dayNumber());
    }

    @Test
    void theEmrHeaderOfADischargedAdmissionIsTheFrozenBillDays() {
        Instant dischargedAt = Instant.parse("2026-11-10T05:30:00Z");
        AdmissionRow stored = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "icu",
                "General ward", DOCTOR, "planned", "Self", "x", "DISCHARGED", ADMITTED_AT, 0, dischargedAt);
        when(discharges.findFinalBill(TENANT, ADMISSION)).thenReturn(Optional.of(
                new DischargeRepository.FinalBillRow(UUID.randomUUID(), ADMISSION, 800_000L, 1_000_000L, -200_000L,
                        "Cash", ACTOR, dischargedAt, 5, "24h", "gen", new java.math.BigDecimal("1.00"))));

        assertThat(service.emrHeader(TENANT, stored).dayNumber()).isEqualTo(5);
    }

    @Test
    void theEmrHeaderNamesADoctorWhoCannotBeFoundAsDoctor() {
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.empty());

        assertThat(service.emrHeader(TENANT, row("Self", "planned", "gen", 1_000_000L)).doctorName())
                .isEqualTo("Doctor");
    }

    // ---- SAH-34 patient scope ----

    @Test
    void detailOutsideScopeIsRefusedWithNoRecordAccess() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(ACTOR), eq("NURSE"), any(),
                eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.detail(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(OutsideScopeException.class);

        verify(patientAccess, never()).recordOpened(any(), any(), any());
        verify(patients, never()).find(any(), any());
    }

    @Test
    void depositOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("OWNER"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI"))
                .isInstanceOf(OutsideScopeException.class);

        verify(wards).lockTenant(TENANT);
        verify(admissions, never()).insertDeposit(any(), any(), anyLong(), anyString(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void chargeOutsideScopeWritesNothing() {
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("NURSE"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.addCharge(TENANT, ACTOR, "NURSE", ADMISSION, ChargeServices.ALL.get(0).key()))
                .isInstanceOf(OutsideScopeException.class);

        verify(charges, never()).insert(any(), any(), anyString(), anyString(), anyLong(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
        verify(patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void theOutsideScopeAnswerComesAfterTheNotFoundAndBeforeNotAdmitted() {
        AdmissionRow discharged = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, BED, "101-A", "gen",
                "General ward", DOCTOR, "planned", "Self", "x", "DISCHARGED", ADMITTED_AT, 0);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(discharged));
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("OWNER"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", ADMISSION, 10_000L, "UPI"))
                .isInstanceOf(OutsideScopeException.class);

        UUID unknown = UUID.randomUUID();
        when(admissions.find(TENANT, unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addDeposit(TENANT, ACTOR, "OWNER", unknown, 10_000L, "UPI"))
                .isInstanceOf(NotFoundException.class);
        verify(guard, never()).requireAdmissionLocked(any(), any(), any(), any(), eq(unknown), anyString());
    }

    private static PatientScope nurseScope(java.util.Set<UUID> wardIds, Map<UUID, Instant> grants, UUID membership) {
        return new PatientScope(wardIds.isEmpty() ? PatientScope.Kind.NONE : PatientScope.Kind.WARDS, ACTOR, "NURSE",
                "NURSE", membership, wardIds, grants);
    }

    private AdmissionRow otherPatientRow(UUID id, UUID patient) {
        return new AdmissionRow(id, "IP/26-27/0099", patient, UUID.randomUUID(), "202-B", "gen", "ICU", DOCTOR,
                "planned", "Self", "x", "ADMITTED", ADMITTED_AT, 1_000_000L);
    }

    @Test
    void aScopedNurseListHoldsOnlyHerWardsAndNothingElseIsReadForTheOthers() {
        UUID otherWard = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID otherPatient = UUID.randomUUID();
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(List.of(
                new AdmittedRow(row("Self", "planned", "gen", 1_000_000L), WARD, null),
                new AdmittedRow(otherPatientRow(otherId, otherPatient), otherWard, null)));
        when(guard.scopeWithMembership(TENANT, ACTOR, "NURSE", "NURSE"))
                .thenReturn(nurseScope(java.util.Set.of(WARD), Map.of(), UUID.randomUUID()));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.items()).extracting(i -> i.id()).containsExactly(ADMISSION);
        assertThat(list.scope().kind()).isEqualTo("WARDS");
        assertThat(list.scope().visible()).isEqualTo(1);
        assertThat(list.scope().admitted()).isEqualTo(2);
        assertThat(list.scope().hasWard()).isTrue();
        assertThat(list.scope().canOpenEmergencyAccess()).isTrue();
        assertThat(list.items().get(0).mine()).isTrue();
        assertThat(list.items().get(0).emergencyAccess()).isNull();
        verify(clinicalPatients).findAll(eq(TENANT), eq(java.util.Set.of(PATIENT)));
        verify(clinicalPatients, never()).findAll(eq(TENANT), eq(java.util.Set.of(otherPatient)));
    }

    @Test
    void aGrantedPatientOutsideHerWardsIsListedWithHerOwnEmergencyAccessFlagAndNotMine() {
        UUID otherWard = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID otherPatient = UUID.randomUUID();
        Instant ends = Instant.parse("2026-11-11T06:00:00Z");
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(List.of(
                new AdmittedRow(row("Self", "planned", "gen", 1_000_000L), WARD, null),
                new AdmittedRow(otherPatientRow(otherId, otherPatient), otherWard, null)));
        when(guard.scopeWithMembership(TENANT, ACTOR, "NURSE", "NURSE"))
                .thenReturn(nurseScope(java.util.Set.of(WARD), Map.of(otherId, ends), UUID.randomUUID()));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known"),
                otherPatient, new PatientClinical(otherPatient, "UH-0002", "Other Patient", 30, "F", null)));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.items()).extracting(i -> i.id()).containsExactlyInAnyOrder(ADMISSION, otherId);
        AdmissionViews.AdmittedPatientRow granted = list.items().stream().filter(i -> i.id().equals(otherId))
                .findFirst().orElseThrow();
        assertThat(granted.emergencyAccess().expiresAt()).isEqualTo(ends);
        assertThat(granted.mine()).isFalse();
        assertThat(list.scope().visible()).isEqualTo(2);
    }

    @Test
    void aNurseWithNoWardSeesAnEmptyListAndHasWardFalseAndStillMayOpenEmergencyAccess() {
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 0)));
        when(guard.scopeWithMembership(TENANT, ACTOR, "NURSE", "NURSE"))
                .thenReturn(nurseScope(java.util.Set.of(), Map.of(), UUID.randomUUID()));

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.items()).isEmpty();
        assertThat(list.scope().kind()).isEqualTo("WARDS");
        assertThat(list.scope().hasWard()).isFalse();
        assertThat(list.scope().visible()).isZero();
        assertThat(list.scope().admitted()).isEqualTo(1);
        assertThat(list.scope().canOpenEmergencyAccess()).isTrue();
        verify(clinicalPatients, never()).findAll(any(), any());
    }

    @Test
    void anUnscopedNurseHasKindAllHasWardMineByWardAndNoEmergencyAccessButton() {
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 0)));
        when(guard.scopeWithMembership(TENANT, ACTOR, "NURSE", "NURSE")).thenReturn(
                PatientScope.all(ACTOR, "NURSE", "NURSE", UUID.randomUUID(), java.util.Set.of(WARD)));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));

        AdmissionList list = listAs("NURSE", "ADMITTED");

        assertThat(list.scope().kind()).isEqualTo("ALL");
        assertThat(list.scope().hasWard()).isTrue();
        assertThat(list.scope().canOpenEmergencyAccess()).isFalse();
        assertThat(list.items().get(0).mine()).isTrue();
        assertThat(list.items().get(0).emergencyAccess()).isNull();
    }

    @Test
    void mineIsTheAdmittingDoctorForOwnerAndDoctorAndAbsentForFrontDesk() {
        when(beds.findAll(TENANT)).thenReturn(List.of(freeBed));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenReturn(placed(row("Self", "planned", "gen", 0)));
        when(clinicalPatients.findAll(eq(TENANT), any())).thenReturn(Map.of(PATIENT, clinical("None known")));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0)));
        for (String role : new String[] {"OWNER", "DOCTOR"}) {
            when(guard.scopeWithMembership(TENANT, ACTOR, role, role))
                    .thenReturn(PatientScope.all(ACTOR, role, role, DOCTOR, java.util.Set.of()));
            AdmissionList mine = listAs(role, "ADMITTED");
            assertThat(mine.items().get(0).mine()).as(role).isTrue();
            assertThat(mine.scope().kind()).isEqualTo("ALL");
            assertThat(mine.scope().hasWard()).isNull();
            assertThat(mine.scope().canOpenEmergencyAccess()).isFalse();
            when(guard.scopeWithMembership(TENANT, ACTOR, role, role))
                    .thenReturn(PatientScope.all(ACTOR, role, role, UUID.randomUUID(), java.util.Set.of()));
            assertThat(listAs(role, "ADMITTED").items().get(0).mine()).as(role + " not the admitting doctor").isFalse();
        }
        when(guard.scopeWithMembership(TENANT, ACTOR, "FRONT_DESK", "FRONT_DESK"))
                .thenReturn(PatientScope.all(ACTOR, "FRONT_DESK", "FRONT_DESK", UUID.randomUUID(), java.util.Set.of()));
        assertThat(listAs("FRONT_DESK", "ADMITTED").items().get(0).mine()).isNull();
    }
    // ---- SAH-58: admit now from a treatment course (ADR-022 D12; DEC-042 H1) ----

    private static final UUID SITTING = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();

    private static AdmissionInput courseInput() {
        return new AdmissionInput(PATIENT, DOCTOR, "planned", BED, "Chest pain", "Self", 1_000_000L, "UPI", true,
                null, SITTING);
    }

    @Test
    void admitForACourseSittingChecksFirstLinksAndCarriesTheCreditInTheSameTransaction() {
        AdmissionCourseLink link = mock(AdmissionCourseLink.class);
        service.setCourseLink(link);
        AdmissionCourseLink.Prepared prepared = new AdmissionCourseLink.Prepared(COURSE, "TC-0412", SITTING);
        when(link.prepare(TENANT, PATIENT, SITTING)).thenReturn(prepared);
        when(link.complete(TENANT, ACTOR, prepared, ADMISSION))
                .thenReturn(new AdmissionCourseLink.Carried(500_000L, UUID.randomUUID()));
        when(link.courseNo(TENANT, ADMISSION)).thenReturn(Optional.of("TC-0412"));
        when(admissions.findDeposits(TENANT, ADMISSION)).thenReturn(List.of(
                new DepositRow(DEPOSIT, 1_000_000L, "UPI", ADMITTED_AT, ACTOR),
                new DepositRow(UUID.randomUUID(), 500_000L, null, ADMITTED_AT, ACTOR, "COURSE")));

        Detail detail = service.admit(TENANT, ACTOR, "FRONT_DESK", courseInput());

        InOrder order = inOrder(link, admissions);
        order.verify(link).prepare(TENANT, PATIENT, SITTING);
        ArgumentCaptor<NewAdmission> saved = ArgumentCaptor.forClass(NewAdmission.class);
        order.verify(admissions).insert(eq(TENANT), saved.capture());
        order.verify(link).complete(TENANT, ACTOR, prepared, ADMISSION);
        assertThat(saved.getValue().treatmentCourseId()).isEqualTo(COURSE);
        assertThat(saved.getValue().treatmentCourseSittingId()).isEqualTo(SITTING);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ADMITTED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("treatmentCourseId", COURSE).containsEntry("carriedPaise", 500_000L);
        assertThat(detail.deposits()).hasSize(2);
        assertThat(detail.deposits().get(0).source()).isEqualTo("DESK");
        assertThat(detail.deposits().get(0).courseNo()).isNull();
        assertThat(detail.deposits().get(1).source()).isEqualTo("COURSE");
        assertThat(detail.deposits().get(1).mode()).isNull();
        assertThat(detail.deposits().get(1).courseNo()).isEqualTo("TC-0412");
    }

    @Test
    void aSittingThatCannotBeAdmittedIs409AndNothingIsWrittenNotEvenTheIpNumber() {
        AdmissionCourseLink link = mock(AdmissionCourseLink.class);
        service.setCourseLink(link);
        when(link.prepare(TENANT, PATIENT, SITTING)).thenThrow(AdmissionCourseLink.notAvailable());

        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "FRONT_DESK", courseInput()))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("course-sitting-not-available"));
        verify(admissions, never()).nextIpNo(any(), anyString());
        verify(admissions, never()).insert(any(), any());
        verify(beds, never()).markOccupied(any(), any());
        verify(link, never()).complete(any(), any(), any(), any());
        verify(auditLog, never()).record(eq("IPD_ADMITTED"), anyString(), any(), any(), any());
    }

    @Test
    void withoutTheCourseLinkASittingIdCannotBeAdmitted() {
        assertThatThrownBy(() -> service.admit(TENANT, ACTOR, "FRONT_DESK", courseInput()))
                .isInstanceOf(ConflictException.class);
        verify(admissions, never()).insert(any(), any());
    }

    @Test
    void anAdmissionWithoutASittingNeverTouchesTheCourseLink() {
        AdmissionCourseLink link = mock(AdmissionCourseLink.class);
        service.setCourseLink(link);

        service.admit(TENANT, ACTOR, "FRONT_DESK", validInput());

        org.mockito.Mockito.verifyNoInteractions(link);
    }

    @Test
    void aCarriedZeroLeavesNoCourseDepositButStillLinksTheSitting() {
        AdmissionCourseLink link = mock(AdmissionCourseLink.class);
        service.setCourseLink(link);
        AdmissionCourseLink.Prepared prepared = new AdmissionCourseLink.Prepared(COURSE, "TC-0412", SITTING);
        when(link.prepare(TENANT, PATIENT, SITTING)).thenReturn(prepared);
        when(link.complete(TENANT, ACTOR, prepared, ADMISSION)).thenReturn(new AdmissionCourseLink.Carried(0, null));

        service.admit(TENANT, ACTOR, "FRONT_DESK", courseInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ADMITTED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("carriedPaise", 0L);
    }
}
