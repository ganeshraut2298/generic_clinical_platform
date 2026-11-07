package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.AdmittedCardAbilities;
import com.sahaj.ipd.application.AdmissionViews.AdmittedPatientRow;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.application.VitalsRepository.LatestScore;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-36 (ADR-013) {@code AdmissionService.list} for the Admitted patient cards, with the real {@link SafetyService} and
 * {@link AdmittedCards} over mocked repositories: the role matrix of {@code clinical}, the membership re-read, the one
 * batch read, one {@code asOf}, the flags, the wards, the rooms, the abilities, and the absence of any write.
 * No database.
 */
class AdmissionListCardsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID WARD_A = UUID.randomUUID();
    private static final UUID WARD_B = UUID.randomUUID();
    private static final UUID EMPTY_WARD = UUID.randomUUID();
    /** The application clock (billing). The database clock {@link #AS_OF} differs on purpose. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");
    private static final Instant AS_OF = Instant.parse("2026-11-11T05:30:09Z");
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-10T05:30:00Z");

    private AdmissionRepository admissions;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private ModuleEntitlementPort entitlements;
    private TeamMemberPort team;
    private PatientLookupPort lookup;
    private PatientClinicalPort clinicalPatients;
    private PatientAccessPort patientAccess;
    private AuditLog auditLog;
    private EscalationBoardRepository board;
    private VitalsRepository vitals;
    private MedicineRoundRepository roundRepository;
    private AdmissionService service;

    private String memberRole = "NURSE";
    private boolean memberActive = true;
    private String ipdState = "on";
    private final Map<UUID, PatientClinical> patientMap = new HashMap<>();
    private final Map<UUID, LatestScore> latestScores = new HashMap<>();
    private final List<AdmittedRow> placed = new ArrayList<>();
    private final List<BoardRow> live = new ArrayList<>();
    private final List<ActiveOrder> orders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        team = mock(TeamMemberPort.class);
        lookup = mock(PatientLookupPort.class);
        clinicalPatients = mock(PatientClinicalPort.class);
        patientAccess = mock(PatientAccessPort.class);
        auditLog = mock(AuditLog.class);
        board = mock(EscalationBoardRepository.class);
        vitals = mock(VitalsRepository.class);
        roundRepository = mock(MedicineRoundRepository.class);
        DischargeRepository discharges = mock(DischargeRepository.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        SafetyService safety = new SafetyService(board, admissions, mock(ClinicalEventRepository.class), lookup,
                mock(ActorDirectoryPort.class), team, gate,
                new MedicineRoundReader(roundRepository, new MedicineCatalogue(SeedCatalogue.catalogue())));
        service = new AdmissionService(admissions, mock(BedRepository.class), wards, settings, mock(OwnerGuard.class),
                auditLog, team, lookup, patientAccess, mock(ActorDirectoryPort.class), gate, catalog, charges,
                new IpdBilling(charges, mock(TransferRepository.class), catalog), discharges,
                mock(EmergencyService.class), vitals, new AdmittedCards(clinicalPatients, lookup, vitals, safety));

        when(entitlements.forTenant(TENANT)).thenAnswer(call -> new ModuleEntitlement(ipdState, false, 25));
        when(team.findActiveByUserId(TENANT, ACTOR)).thenAnswer(call -> memberRole == null ? Optional.empty()
                : Optional.of(new TeamMember(UUID.randomUUID(), "Member", memberRole, memberActive, 0L)));
        when(team.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(DOCTOR,
                new TeamMember(DOCTOR, "Dr Test", "DOCTOR", true, 0L)));
        when(settings.findDepositRules(TENANT)).thenReturn(Optional.empty());
        when(settings.findCategories(TENANT)).thenReturn(List.of());
        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(true, "midnight", 0L, 0L)));
        when(board.databaseNow()).thenReturn(AS_OF);
        when(board.findLiveOnBoard(TENANT)).thenAnswer(call -> List.copyOf(live));
        when(admissions.findAdmittedWithPlacement(TENANT)).thenAnswer(call -> List.copyOf(placed));
        when(clinicalPatients.findAll(eq(TENANT), anyCollection())).thenAnswer(call -> Map.copyOf(patientMap));
        when(lookup.findAllCurrent(eq(TENANT), anyCollection())).thenAnswer(call -> {
            Map<UUID, PatientSummary> summaries = new HashMap<>();
            patientMap.forEach((id, p) -> summaries.put(id,
                    new PatientSummary(p.id(), p.name(), p.age(), p.sex(), null, false, p.uhid())));
            return summaries;
        });
        when(vitals.findLatestOfAdmitted(TENANT)).thenAnswer(call -> Map.copyOf(latestScores));
        when(roundRepository.findActiveOfAdmitted(TENANT)).thenAnswer(call -> List.copyOf(orders));
        when(wards.findWards(TENANT)).thenReturn(List.of(new WardRow(WARD_A, "General ward", "Station 1"),
                new WardRow(WARD_B, "ICU", "Station 2"), new WardRow(EMPTY_WARD, "Maternity", "Station 3")));
    }

    // ---- fixtures ----

    /** An admitted patient in ward A, room "Room 12", with a patient record holding {@code allergy}. */
    private UUID admit(String name, String uhid, String allergy) {
        UUID admissionId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        if (name != null) {
            patientMap.put(patientId, new PatientClinical(patientId, uhid, name, 41, "F", allergy));
        }
        placed.add(new AdmittedRow(new AdmissionRow(admissionId, "IP/26-27/" + placed.size(), patientId,
                UUID.randomUUID(), "20" + placed.size(), "gen", "General ward", DOCTOR, "planned", "Self", "x",
                "ADMITTED", ADMITTED_AT, 0L), WARD_A, "Room 12"));
        return admissionId;
    }

    private Detail escalation(UUID admissionId, Status status, int target, Instant raisedAt, Instant reopenedAt) {
        Detail d = new Detail(UUID.randomUUID(), admissionId, 3, 8, Risk.HIGH, target, status, raisedAt,
                UUID.randomUUID(), reopenedAt, null, null, null, null, 0, null, null);
        live.add(new BoardRow(d, UUID.randomUUID(), "204"));
        return d;
    }

    private AdmissionList list(String tokenRole) {
        return service.list(TENANT, ACTOR, tokenRole, null);
    }

    private AdmittedPatientRow only(AdmissionList list) {
        assertThat(list.items()).hasSize(1);
        return list.items().get(0);
    }

    // ---- who gets the clinical facts ----

    @Test
    void ownerDoctorAndNurseGetTheClinicalFactsOnEveryRowAndTheSafetyBlock() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        admit("Raj Kumar", "UH-0002", null);
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            memberRole = role;

            AdmissionList list = list(role);

            assertThat(list.items()).as(role).allMatch(row -> row.clinical() != null);
            assertThat(list.safety()).as(role).isNotNull();
        }
    }

    @Test
    void frontDeskGetsNoClinicalFactsNoSafetyBlockAndNoClinicalReadIsMade() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        memberRole = "FRONT_DESK";

        AdmissionList list = list("FRONT_DESK");

        assertThat(only(list).clinical()).isNull();
        assertThat(list.safety()).isNull();
        verify(board, never()).findLiveOnBoard(any());
        verify(vitals, never()).findLatestOfAdmitted(any());
        verifyNoInteractions(roundRepository);
        // The table still gets name, UHID, ward and room: they are not clinical.
        assertThat(only(list).uhid()).isEqualTo("UH-0001");
        assertThat(only(list).patientName()).isEqualTo("Meena Patil");
    }

    // ---- ADR-013 D15: a caller without clinical access never triggers the clinical port ----

    @Test
    void frontDeskReadsThePatientsThroughTheLookupPortOnlyAndTheClinicalPortIsNeverCalled() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        admit("Raj Kumar", "UH-0002", null);
        memberRole = "FRONT_DESK";

        AdmissionList list = list("FRONT_DESK");

        verifyNoInteractions(clinicalPatients);
        verify(lookup, times(1)).findAllCurrent(eq(TENANT), anyCollection());
        verify(lookup, never()).find(any(), any());
        verify(lookup, never()).findAll(any(), any());
        assertThat(list.items()).extracting(AdmittedPatientRow::patientName).containsExactly("Meena Patil", "Raj Kumar");
        assertThat(list.items()).extracting(AdmittedPatientRow::uhid).containsExactly("UH-0001", "UH-0002");
        assertThat(list.items()).allMatch(row -> row.clinical() == null);
    }

    @Test
    void everyCombinationOfTokenAndMembershipWithoutClinicalAccessAvoidsTheClinicalPort() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        String[][] combinations = {{"FRONT_DESK", "FRONT_DESK"}, {"NURSE", "FRONT_DESK"}, {"FRONT_DESK", "NURSE"},
            {"DOCTOR", "FRONT_DESK"}, {"OWNER", "FRONT_DESK"}, {"FRONT_DESK", "OWNER"}};
        for (String[] combination : combinations) {
            memberRole = combination[1];

            AdmissionList list = list(combination[0]);

            assertThat(only(list).clinical()).as(String.join("/", combination)).isNull();
            assertThat(only(list).patientName()).isEqualTo("Meena Patil");
        }
        verifyNoInteractions(clinicalPatients);
    }

    @Test
    void aClinicalCallerStillReadsThroughTheClinicalPortAndNeverThroughTheLookupBatch() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        memberRole = "DOCTOR";

        list("DOCTOR");

        verify(clinicalPatients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(lookup, never()).findAllCurrent(any(), any());
    }

    @Test
    void theCardShowsTheSameNameUhidAgeAndSexToEveryRole() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        AdmittedPatientRow reference = null;
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE", "FRONT_DESK"}) {
            memberRole = role;

            AdmittedPatientRow row = only(list(role));

            if (reference == null) {
                reference = row;
            }
            assertThat(row.patientName()).as(role).isEqualTo(reference.patientName());
            assertThat(row.uhid()).as(role).isEqualTo(reference.uhid());
            assertThat(row.age()).as(role).isEqualTo(reference.age());
            assertThat(row.sex()).as(role).isEqualTo(reference.sex());
        }
    }

    @Test
    void aPatientRecordThatCannotBeFoundKeepsTheRowWithoutUhidForFrontDeskToo() {
        admit(null, null, null);
        memberRole = "FRONT_DESK";

        AdmittedPatientRow row = only(list("FRONT_DESK"));

        assertThat(row.uhid()).isNull();
        assertThat(row.clinical()).isNull();
    }

    @Test
    void anEmptyHospitalMakesNoPatientReadForAnyRole() {
        memberRole = "FRONT_DESK";

        list("FRONT_DESK");

        verifyNoInteractions(clinicalPatients);
        verify(lookup, never()).findAllCurrent(any(), any());
    }

    @Test
    void aTokenWithAClinicalRoleButAMembershipChangedToFrontDeskGetsNoClinicalFacts() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        memberRole = "FRONT_DESK";

        AdmissionList list = list("NURSE");

        assertThat(only(list).clinical()).isNull();
        assertThat(list.safety()).isNull();
        assertThat(list.card()).isEqualTo(new AdmittedCardAbilities(false, false, true, false, false));
    }

    @Test
    void aTokenOfFrontDeskWithAMembershipNowNurseGetsNoClinicalFactsEither() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        memberRole = "NURSE";

        AdmissionList list = list("FRONT_DESK");

        assertThat(only(list).clinical()).isNull();
        assertThat(list.safety()).isNull();
    }

    @Test
    void theRoleChangeAppliesOnTheNextReload() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        memberRole = "NURSE";
        assertThat(only(list("NURSE")).clinical()).isNotNull();

        memberRole = "FRONT_DESK";

        assertThat(only(list("NURSE")).clinical()).isNull();
    }

    // ---- the membership re-read: 403 ----

    @Test
    void aDeactivatedMemberWithAValidTokenIsRefusedBeforeAnyRead() {
        admit("Meena Patil", "UH-0001", null);
        memberActive = false;

        assertThatThrownBy(() -> list("OWNER")).isInstanceOf(AccessDeniedException.class);

        verifyNoReads();
    }

    @Test
    void aCallerWithNoMembershipIsRefused() {
        memberRole = null;

        assertThatThrownBy(() -> list("OWNER")).isInstanceOf(AccessDeniedException.class);

        verifyNoReads();
    }

    @Test
    void aMembershipRoleOutsideTheFourListRolesIsRefusedWhateverTheToken() {
        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"}) {
            memberRole = role;
            assertThatThrownBy(() -> list("OWNER")).as(role).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoReads();
    }

    @Test
    void aTokenRoleOutsideTheFourListRolesIsRefusedEvenWithAnOwnerMembership() {
        memberRole = "OWNER";

        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> list(role)).as(String.valueOf(role)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoReads();
    }

    @Test
    void ipdOffAnswersModuleOffBeforeTheMembershipIsRead() {
        ipdState = "off";

        assertThatThrownBy(() -> list("OWNER")).isInstanceOf(ModuleOffException.class);

        verify(team, never()).findActiveByUserId(any(), any());
        verifyNoReads();
    }

    @Test
    void aBadStatusIsA400AfterTheRoleChecks() {
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", "DISCHARGED"))
                .isInstanceOf(InvalidInputException.class);
        memberRole = "BILLING";
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", "DISCHARGED"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void verifyNoReads() {
        verify(admissions, never()).findAdmittedWithPlacement(any());
        verify(board, never()).databaseNow();
        verify(board, never()).findLiveOnBoard(any());
        verify(clinicalPatients, never()).findAll(any(), any());
        verify(vitals, never()).findLatestOfAdmitted(any());
        verify(wards, never()).findWards(any());
    }

    // ---- one batch read, one asOf, nothing written ----

    @Test
    void aHundredPatientsAreReadWithAFixedNumberOfSetQueriesAndNoCallPerPatient() {
        for (int i = 0; i < 100; i++) {
            admit("Patient " + i, "UH-" + i, i % 2 == 0 ? "Penicillin" : null);
        }

        AdmissionList list = list("NURSE");

        assertThat(list.items()).hasSize(100);
        verify(admissions, times(1)).findAdmittedWithPlacement(TENANT);
        verify(clinicalPatients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(board, times(1)).findLiveOnBoard(TENANT);
        verify(vitals, times(1)).findLatestOfAdmitted(TENANT);
        verify(roundRepository, times(1)).findActiveOfAdmitted(TENANT);
        verify(board, times(1)).databaseNow();
        verify(team, times(1)).findActiveByUserId(TENANT, ACTOR);
        verify(team, times(1)).findAll(eq(TENANT), anyCollection());
        // No per-patient call: no patient or team lookup by id, no per-admission vitals or escalation read.
        verify(team, never()).find(any(), any());
        verify(lookup, never()).find(any(), any());
        verify(lookup, never()).findAll(any(), any());
        verify(clinicalPatients, never()).find(any(), any());
        verify(vitals, never()).findLatest(any(), any());
        verify(vitals, never()).databaseNow();
    }

    @Test
    void asOfIsTheDatabaseClockReadOnceAndNotTheApplicationClock() {
        admit("Meena Patil", "UH-0001", null);

        AdmissionList list = list("OWNER");

        assertThat(list.asOf()).isEqualTo(AS_OF).isNotEqualTo(NOW);
        assertThat(list.safety().counts().asOf()).isEqualTo(AS_OF);
        verify(board, times(1)).databaseNow();
    }

    @Test
    void theListWritesNoRecordAccessRowNoAuditRowAndNoEvent() {
        admit("Meena Patil", "UH-0001", "Penicillin");
        admit("Raj Kumar", "UH-0002", null);

        list("OWNER");
        list("OWNER");

        verifyNoInteractions(patientAccess);
        verifyNoInteractions(auditLog);
    }

    // ---- flags ----

    @Test
    void theEscalationFlagHasTheMinutesOfTheBoardAndIsNullWhenNoneIsOpen() {
        UUID unseen = admit("Meena Patil", "UH-0001", null);
        UUID calm = admit("Raj Kumar", "UH-0002", null);
        escalation(unseen, Status.OPEN, 15, AS_OF.minusSeconds(3 * 3600), AS_OF.minusSeconds(4 * 60 + 5));

        AdmissionList list = list("NURSE");

        AdmittedPatientRow withEscalation = list.items().stream().filter(r -> r.id().equals(unseen)).findFirst().get();
        AdmittedPatientRow without = list.items().stream().filter(r -> r.id().equals(calm)).findFirst().get();
        assertThat(withEscalation.clinical().escalation().status()).isEqualTo(Status.OPEN);
        assertThat(withEscalation.clinical().escalation().waitingMinutes()).isEqualTo(4);
        assertThat(withEscalation.clinical().escalation().targetMinutes()).isEqualTo(15);
        assertThat(withEscalation.clinical().escalation().pastTarget()).isFalse();
        assertThat(without.clinical().escalation()).isNull();
    }

    @Test
    void theCardsTheBannerAndTheTabNumberAgreeOnTheSameEscalationRows() {
        UUID a = admit("Meena Patil", "UH-0001", null);
        UUID b = admit("Raj Kumar", "UH-0002", null);
        admit("Sunita Rao", "UH-0003", null);
        escalation(a, Status.OPEN, 15, AS_OF.minusSeconds(20 * 60), null);
        escalation(b, Status.ACK, 15, AS_OF.minusSeconds(30 * 60), null);

        AdmissionList list = list("DOCTOR");

        long flagged = list.items().stream().filter(r -> r.clinical().escalation() != null).count();
        assertThat(flagged).isEqualTo(2);
        assertThat(list.safety().counts().open()).isEqualTo((int) flagged);
        // Only the OPEN one past its 15 minute target is on the banner.
        assertThat(list.safety().pastTarget()).hasSize(1);
        assertThat(list.safety().pastTarget().get(0).admissionId()).isEqualTo(a);
        AdmittedPatientRow rowA = list.items().stream().filter(r -> r.id().equals(a)).findFirst().get();
        assertThat(rowA.clinical().escalation().pastTarget()).isTrue();
        AdmittedPatientRow rowB = list.items().stream().filter(r -> r.id().equals(b)).findFirst().get();
        assertThat(rowB.clinical().escalation().status()).isEqualTo(Status.ACK);
        assertThat(rowB.clinical().escalation().pastTarget()).isFalse();
        verify(board, times(1)).findLiveOnBoard(TENANT);
    }

    @Test
    void theLatestScoreAndVitalsDueComeFromOneBatchRead() {
        UUID fresh = admit("Meena Patil", "UH-0001", null);
        UUID stale = admit("Raj Kumar", "UH-0002", null);
        UUID never = admit("Sunita Rao", "UH-0003", null);
        latestScores.put(fresh, new LatestScore(fresh, 7, Risk.HIGH, AS_OF.minusSeconds(240 * 60)));
        latestScores.put(stale, new LatestScore(stale, 1, Risk.LOW, AS_OF.minusSeconds(241 * 60)));

        Map<UUID, AdmittedPatientRow> byId = new HashMap<>();
        list("NURSE").items().forEach(r -> byId.put(r.id(), r));

        assertThat(byId.get(fresh).clinical().latestEws().score()).isEqualTo(7);
        assertThat(byId.get(fresh).clinical().latestEws().risk()).isEqualTo(Risk.HIGH);
        assertThat(byId.get(fresh).clinical().vitalsDue()).isFalse();
        assertThat(byId.get(stale).clinical().vitalsDue()).isTrue();
        assertThat(byId.get(never).clinical().latestEws()).isNull();
        assertThat(byId.get(never).clinical().vitalsDue()).isTrue();
    }

    @Test
    void allergyStatusAndTextFollowThePatientRecord() {
        UUID recorded = admit("Meena Patil", "UH-0001", "Penicillin");
        UUID noneKnown = admit("Raj Kumar", "UH-0002", "None known");
        UUID notRecorded = admit("Sunita Rao", "UH-0003", null);

        Map<UUID, AdmittedPatientRow> byId = new HashMap<>();
        list("OWNER").items().forEach(r -> byId.put(r.id(), r));

        assertThat(byId.get(recorded).clinical().allergy().status()).isEqualTo(AllergyStatus.RECORDED);
        assertThat(byId.get(recorded).clinical().allergy().text()).isEqualTo("Penicillin");
        assertThat(byId.get(noneKnown).clinical().allergy().status()).isEqualTo(AllergyStatus.NONE_KNOWN);
        assertThat(byId.get(noneKnown).clinical().allergy().text()).isNull();
        assertThat(byId.get(notRecorded).clinical().allergy().status()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(byId.get(notRecorded).clinical().allergy().text()).isNull();
    }

    @Test
    void aPatientRecordThatCannotBeFoundKeepsTheRowWithNoUhidAndNoAllergyFlag() {
        UUID id = admit(null, null, null);

        AdmittedPatientRow row = only(list("NURSE"));

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.patientName()).isEqualTo("Patient");
        assertThat(row.uhid()).isNull();
        assertThat(row.age()).isNull();
        assertThat(row.sex()).isNull();
        assertThat(row.clinical().allergy().status()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(row.clinical().allergy().text()).isNull();
    }

    @Test
    void theRowCarriesUhidWardIdAndAgeSexFromThePatientPort() {
        admit("Meena Patil", "UH-0001", null);

        AdmittedPatientRow row = only(list("OWNER"));

        assertThat(row.uhid()).isEqualTo("UH-0001");
        assertThat(row.wardId()).isEqualTo(WARD_A);
        assertThat(row.age()).isEqualTo(41);
        assertThat(row.sex()).isEqualTo("F");
        assertThat(row.wardName()).isEqualTo("General ward");
        assertThat(row.doctorName()).isEqualTo("Dr Test");
    }

    // ---- SAH-35 unchanged: dose counts in safety ----

    @Test
    void theSafetyBlockStillCarriesTheDoseCountsOfTheMedicineRound() {
        UUID admissionId = admit("Meena Patil", "UH-0001", null);
        orders.add(new ActiveOrder(UUID.randomUUID(), admissionId, placed.get(0).admission().patientId(), "204",
                "pcm", "od", Instant.parse("2026-11-09T05:00:00Z")));

        AdmissionList list = service.list(TENANT, ACTOR, "NURSE", null);

        assertThat(list.safety().counts().dosesLate()).isEqualTo(1);
        assertThat(list.safety().counts().changeToken()).hasSize(22);
    }

    // ---- wards and rooms ----

    @Test
    void theWardOptionsAreEveryWardInTheOrderOfTheBedsSetupAlsoOneWithNoPatient() {
        admit("Meena Patil", "UH-0001", null);

        List<AdmissionViews.WardOption> options = list("FRONT_DESK").wards();

        assertThat(options).extracting(AdmissionViews.WardOption::id).containsExactly(WARD_A, WARD_B, EMPTY_WARD);
        assertThat(options).extracting(AdmissionViews.WardOption::name)
                .containsExactly("General ward", "ICU", "Maternity");
    }

    @Test
    void theRoomNameIsShownWhenRoomsAreOnAndNullWhenTheClinicUsesNoRooms() {
        admit("Meena Patil", "UH-0001", null);
        assertThat(only(list("OWNER")).roomName()).isEqualTo("Room 12");

        when(settings.findConfig(TENANT)).thenReturn(Optional.of(new ConfigRow(false, "midnight", 0L, 0L)));

        assertThat(only(list("OWNER")).roomName()).isNull();
    }

    @Test
    void aBedWithNoRoomHasANullRoomName() {
        AdmissionRow row = new AdmissionRow(UUID.randomUUID(), "IP/26-27/9", UUID.randomUUID(), UUID.randomUUID(),
                "300", "gen", "General ward", DOCTOR, "planned", "Self", "x", "ADMITTED", ADMITTED_AT, 0L);
        placed.add(new AdmittedRow(row, WARD_B, null));

        AdmittedPatientRow shown = only(list("OWNER"));

        assertThat(shown.roomName()).isNull();
        assertThat(shown.wardId()).isEqualTo(WARD_B);
    }

    @Test
    void anEmptyHospitalGivesNoItemsButTheWardsTheAbilitiesAndAsOf() {
        AdmissionList list = list("NURSE");

        assertThat(list.items()).isEmpty();
        assertThat(list.wards()).hasSize(3);
        assertThat(list.asOf()).isEqualTo(AS_OF);
        assertThat(list.card()).isNotNull();
        verify(clinicalPatients, never()).findAll(any(), any());
    }

    // ---- abilities through the list ----

    @Test
    void theCardAbilitiesFollowTheRoleAndTheIpdState() {
        memberRole = "NURSE";
        assertThat(list("NURSE").card()).isEqualTo(new AdmittedCardAbilities(true, true, true, false, false));
        memberRole = "DOCTOR";
        assertThat(list("DOCTOR").card()).isEqualTo(new AdmittedCardAbilities(true, true, false, true, false));
        memberRole = "OWNER";
        assertThat(list("OWNER").card()).isEqualTo(new AdmittedCardAbilities(true, true, true, true, true));
        memberRole = "FRONT_DESK";
        assertThat(list("FRONT_DESK").card()).isEqualTo(new AdmittedCardAbilities(false, false, true, false, true));

        ipdState = "readonly";
        memberRole = "OWNER";
        assertThat(list("OWNER").card()).isEqualTo(new AdmittedCardAbilities(true, true, false, false, true));
        ipdState = "trial";
        assertThat(list("OWNER").card()).isEqualTo(new AdmittedCardAbilities(true, true, true, true, true));
    }

    @Test
    void theTableStillShowsTheBillAndDepositFlagsToAllFourRoles() {
        admit("Meena Patil", "UH-0001", null);
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            memberRole = role;

            AdmittedPatientRow row = only(list(role));

            assertThat(row.depositState()).as(role).isNotNull();
            assertThat(row.runningBillPaise()).as(role).isPositive();
        }
    }
}
