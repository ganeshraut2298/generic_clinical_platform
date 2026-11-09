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
    private CardSetupRepository cardSetupRepository;
    private ActorDirectoryPort actors;
    private MedicineRoundRepository roundRepository;
    private AdmissionService service;

    private String memberRole = "NURSE";
    private boolean memberActive = true;
    private String ipdState = "on";
    private final Map<UUID, PatientClinical> patientMap = new HashMap<>();
    private final Map<UUID, LatestScore> latestScores = new HashMap<>();
    private final Map<UUID, String> mobiles = new HashMap<>();
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
        cardSetupRepository = mock(CardSetupRepository.class);
        actors = mock(ActorDirectoryPort.class);
        roundRepository = mock(MedicineRoundRepository.class);
        DischargeRepository discharges = mock(DischargeRepository.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        SafetyService safety = new SafetyService(board, admissions, mock(ClinicalEventRepository.class), lookup,
                mock(ActorDirectoryPort.class), team, gate,
                new MedicineRoundReader(roundRepository, new MedicineCatalogue(SeedCatalogue.catalogue()),
                        new SkippedOrderWarningsTest.MutableClock()));
        service = new AdmissionService(admissions, mock(BedRepository.class), wards, settings, mock(OwnerGuard.class),
                auditLog, team, lookup, patientAccess, actors, gate, catalog, charges,
                new IpdBilling(charges, mock(TransferRepository.class), catalog), discharges,
                mock(EmergencyService.class), vitals, new AdmittedCards(clinicalPatients, lookup, vitals, safety),
                new CardSetups(cardSetupRepository));

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
                    new PatientSummary(p.id(), p.name(), p.age(), p.sex(), mobiles.get(id), false, p.uhid())));
            return summaries;
        });
        when(vitals.findLatestOfAdmitted(TENANT)).thenAnswer(call -> Map.copyOf(latestScores));
        when(vitals.findLatestReadingsOfAdmitted(TENANT)).thenAnswer(call -> Map.copyOf(latestScores));
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
        return service.list(TENANT, ACTOR, tokenRole, null, null);
    }

    private static final String[] ALL_GROUPS = {"patient", "contact", "clinical", "vitals", "billing"};

    private static AdmittedCardAbilities card(boolean nursing, boolean emr, boolean transfer, boolean discharge,
                                              boolean deposit, boolean depositLowFlag, String... groups) {
        return new AdmittedCardAbilities(nursing, emr, transfer, discharge, deposit, depositLowFlag,
                java.util.List.of(groups));
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
        assertThat(list.card()).isEqualTo(card(false, false, true, false, false, false, "patient"));
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
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", "DISCHARGED", null))
                .isInstanceOf(InvalidInputException.class);
        memberRole = "BILLING";
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", "DISCHARGED", null))
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

        AdmissionList list = service.list(TENANT, ACTOR, "NURSE", null, null);

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
        assertThat(list("NURSE").card()).isEqualTo(card(true, true, true, false, false, false, "patient", "clinical", "vitals"));
        memberRole = "DOCTOR";
        assertThat(list("DOCTOR").card()).isEqualTo(card(true, true, false, true, false, false, "patient", "clinical", "vitals"));
        memberRole = "OWNER";
        assertThat(list("OWNER").card()).isEqualTo(card(true, true, true, true, true, true, ALL_GROUPS));
        memberRole = "FRONT_DESK";
        assertThat(list("FRONT_DESK").card())
                .isEqualTo(card(false, false, true, false, true, true, "patient", "contact", "billing"));

        ipdState = "readonly";
        memberRole = "OWNER";
        assertThat(list("OWNER").card()).isEqualTo(card(true, true, false, false, false, true, ALL_GROUPS));
        ipdState = "trial";
        assertThat(list("OWNER").card()).isEqualTo(card(true, true, true, true, true, true, ALL_GROUPS));
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

    // ---- SAH-37: the published setup, the new card facts, cardFacts=all ----

    private static final UUID RECORDER = UUID.randomUUID();

    /** The standard setup with these fields switched on (and Billing switched on when a billing field is named). */
    private void publishWithFieldsOn(String... keys) {
        java.util.Set<String> on = java.util.Set.of(keys);
        java.util.List<com.sahaj.ipd.domain.card.CardSetup.Section> sections = new ArrayList<>();
        for (com.sahaj.ipd.domain.card.CardSetup.Section s : com.sahaj.ipd.domain.card.CardSetupDefaults.standard()
                .sections()) {
            java.util.List<com.sahaj.ipd.domain.card.CardSetup.FieldPlacement> fields = new ArrayList<>();
            for (com.sahaj.ipd.domain.card.CardSetup.FieldPlacement f : s.fields()) {
                fields.add(on.contains(f.key())
                        ? new com.sahaj.ipd.domain.card.CardSetup.FieldPlacement(f.key(), true, "") : f);
            }
            boolean billing = s.key().equals("billing") && (on.contains("runningBill") || on.contains("deposit"));
            sections.add(new com.sahaj.ipd.domain.card.CardSetup.Section(s.key(), billing || s.on(), fields));
        }
        com.sahaj.ipd.domain.card.CardSetup setup = new com.sahaj.ipd.domain.card.CardSetup("expanded", 4, sections,
                com.sahaj.ipd.domain.card.CardSetupDefaults.standard().actions());
        when(cardSetupRepository.findLatestVersion(TENANT)).thenReturn(Optional.of(
                new CardSetupRepository.VersionRow(2, CardSetupJson.write(setup), false, List.of("layout", "fields"),
                        RECORDER, AS_OF)));
    }

    private UUID admitWithReason(String name, String reason) {
        UUID admissionId = admit(name, "UH-0001", null);
        AdmittedRow old = placed.remove(placed.size() - 1);
        AdmissionRow a = old.admission();
        placed.add(new AdmittedRow(new AdmissionRow(a.id(), a.ipNumber(), a.patientId(), a.bedId(), a.bedCode(),
                a.categoryKey(), a.wardName(), a.doctorMembershipId(), a.admissionType(), a.payer(), reason,
                a.status(), a.admittedAt(), a.depositTotalPaise()), old.wardId(), old.roomName()));
        return admissionId;
    }

    private void recordVitals(UUID admissionId) {
        latestScores.put(admissionId, new LatestScore(admissionId, 5, Risk.MEDIUM, AS_OF.minusSeconds(35 * 60),
                new VitalsRepository.Readings(85, 60, 95, 96, new java.math.BigDecimal("37.0"), 22), RECORDER));
    }

    @Test
    void aClinicThatNeverPublishedGetsTheStandardCardAndNoNewFact() {
        memberRole = "OWNER";
        UUID id = admitWithReason("Meena Patil", "Chest pain");
        recordVitals(id);
        mobiles.putAll(Map.of(placed.get(0).admission().patientId(), "9876543210"));

        AdmissionList list = list("OWNER");

        assertThat(list.cardSetup().version()).isEqualTo(1);
        assertThat(list.cardSetup().isStandard()).isTrue();
        assertThat(list.cardSetup().fallback()).isFalse();
        assertThat(list.cardSetup().layout()).isEqualTo("standard");
        assertThat(list.cardSetup().cardsPerRow()).isEqualTo(3);
        AdmittedPatientRow row = only(list);
        assertThat(row.clinical().diagnosis()).isNull();
        assertThat(row.clinical().latestVitals()).isNull();
        assertThat(row.mobile()).isNull();
        assertThat(row.clinical().latestEws()).isNotNull();
        verify(vitals, never()).findLatestReadingsOfAdmitted(any());
        verify(vitals, times(1)).findLatestOfAdmitted(TENANT);
        verify(actors, never()).displayNames(any(), any());
        verify(lookup, never()).findAllCurrent(any(), any());
    }

    @Test
    void theDiagnosisIsSentToOwnerDoctorAndNurseOnlyWhenTheSetupSwitchesItOn() {
        publishWithFieldsOn("diagnosis");
        admitWithReason("Meena Patil", "Chest pain");

        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            memberRole = role;
            assertThat(only(list(role)).clinical().diagnosis()).as(role).isEqualTo("Chest pain");
        }
        memberRole = "FRONT_DESK";
        AdmittedPatientRow frontDesk = only(list("FRONT_DESK"));
        assertThat(frontDesk.clinical()).isNull();
        verify(vitals, never()).findLatestReadingsOfAdmitted(any());
    }

    @Test
    void aBlankReasonIsUnderEvaluationAndNothingIsSentWhenTheFieldIsOff() {
        publishWithFieldsOn("diagnosis");
        admitWithReason("Meena Patil", "  ");
        memberRole = "DOCTOR";

        assertThat(only(list("DOCTOR")).clinical().diagnosis()).isEqualTo("Under evaluation");

        when(cardSetupRepository.findLatestVersion(TENANT)).thenReturn(Optional.empty());
        assertThat(only(list("DOCTOR")).clinical().diagnosis()).isNull();
    }

    @Test
    void theReadingsAndTheirMarksAreSentOnlyForTheFieldsThatAreOnWithOneBatchOfRecorderNames() {
        publishWithFieldsOn("bp", "pulse", "lastRecorded");
        UUID id = admitWithReason("Meena Patil", "x");
        recordVitals(id);
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(RECORDER, "Sr. Anjali Pawar"));
        memberRole = "NURSE";

        AdmittedPatientRow row = only(list("NURSE"));
        AdmissionViews.CardLatestVitals tiles = row.clinical().latestVitals().get();

        assertThat(tiles.bp().systolic()).isEqualTo(85);
        assertThat(tiles.bp().mark()).isEqualTo(com.sahaj.ipd.domain.card.VitalMarks.Mark.CRITICAL);
        assertThat(tiles.pulse().value()).isEqualByComparingTo("95");
        assertThat(tiles.pulse().mark()).isEqualTo(com.sahaj.ipd.domain.card.VitalMarks.Mark.CHECK);
        assertThat(tiles.spo2()).isNull();
        assertThat(tiles.temp()).isNull();
        assertThat(tiles.rr()).isNull();
        assertThat(tiles.recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(tiles.recordedAt()).isEqualTo(AS_OF.minusSeconds(35 * 60));
        verify(vitals, times(1)).findLatestReadingsOfAdmitted(TENANT);
        verify(vitals, never()).findLatestOfAdmitted(any());
        verify(actors, times(1)).displayNames(eq(TENANT), anyCollection());
    }

    @Test
    void withoutLastRecordedTheRecorderIsNeverLookedUp() {
        publishWithFieldsOn("spo2");
        recordVitals(admitWithReason("Meena Patil", "x"));
        memberRole = "NURSE";

        AdmissionViews.CardLatestVitals tiles = only(list("NURSE")).clinical().latestVitals().get();

        assertThat(tiles.spo2().value()).isEqualByComparingTo("96");
        assertThat(tiles.recordedByName()).isNull();
        verify(actors, never()).displayNames(any(), any());
    }

    @Test
    void noVitalsRecordedIsAnEmptyOptionalWhenAReadingIsOn() {
        publishWithFieldsOn("bp");
        admitWithReason("Meena Patil", "x");
        memberRole = "NURSE";

        AdmittedPatientRow row = only(list("NURSE"));

        assertThat(row.clinical().latestVitals()).isEqualTo(Optional.empty());
        assertThat(row.clinical().latestEws()).isNull();
    }

    @Test
    void frontDeskNeverGetsDiagnosisOrVitalsEvenWhenTheSetupShowsThemAndGetsTheMobile() {
        publishWithFieldsOn("diagnosis", "bp", "pulse", "spo2", "temp", "rr", "lastRecorded", "mobile");
        UUID id = admitWithReason("Meena Patil", "Chest pain");
        recordVitals(id);
        mobiles.put(placed.get(0).admission().patientId(), "9876543210");
        memberRole = "FRONT_DESK";

        AdmittedPatientRow row = only(list("FRONT_DESK"));

        assertThat(row.clinical()).isNull();
        assertThat(row.mobile()).contains("9876543210");
        verify(vitals, never()).findLatestReadingsOfAdmitted(any());
        verify(vitals, never()).findLatestOfAdmitted(any());
        verify(lookup, times(1)).findAllCurrent(eq(TENANT), anyCollection());
        verifyNoInteractions(clinicalPatients);
    }

    @Test
    void nurseAndDoctorNeverGetTheMobileEvenWhenTheSetupShowsIt() {
        publishWithFieldsOn("mobile");
        admitWithReason("Meena Patil", "x");
        mobiles.put(placed.get(0).admission().patientId(), "9876543210");
        for (String role : new String[] {"NURSE", "DOCTOR"}) {
            memberRole = role;
            assertThat(only(list(role)).mobile()).as(role).isNull();
        }
        verify(lookup, never()).findAllCurrent(any(), any());
    }

    @Test
    void theOwnerGetsTheMobileFromOneExtraBatchAndNullWhenTheRecordHasNone() {
        publishWithFieldsOn("mobile");
        admitWithReason("Meena Patil", "x");
        admitWithReason("Raj Kumar", "y");
        mobiles.put(placed.get(0).admission().patientId(), "9876543210");
        memberRole = "OWNER";

        AdmissionList list = list("OWNER");

        assertThat(list.items().get(0).mobile()).contains("9876543210");
        assertThat(list.items().get(1).mobile()).isEqualTo(Optional.empty());
        verify(lookup, times(1)).findAllCurrent(eq(TENANT), anyCollection());
    }

    @Test
    void aFieldInASectionThatIsSwitchedOffIsNotSent() {
        // Diagnosis moved into the Care team section, which is then switched off.
        com.sahaj.ipd.domain.card.CardSetup standard = com.sahaj.ipd.domain.card.CardSetupDefaults.standard();
        java.util.List<com.sahaj.ipd.domain.card.CardSetup.Section> sections = new ArrayList<>();
        for (com.sahaj.ipd.domain.card.CardSetup.Section s : standard.sections()) {
            java.util.List<com.sahaj.ipd.domain.card.CardSetup.FieldPlacement> fields = new ArrayList<>(s.fields());
            fields.removeIf(f -> f.key().equals("diagnosis"));
            if (s.key().equals("care")) {
                fields.add(new com.sahaj.ipd.domain.card.CardSetup.FieldPlacement("diagnosis", true, ""));
            }
            sections.add(new com.sahaj.ipd.domain.card.CardSetup.Section(s.key(), !s.key().equals("care") && s.on(),
                    fields));
        }
        com.sahaj.ipd.domain.card.CardSetup setup = new com.sahaj.ipd.domain.card.CardSetup("standard", 3, sections,
                standard.actions());
        when(cardSetupRepository.findLatestVersion(TENANT)).thenReturn(Optional.of(
                new CardSetupRepository.VersionRow(2, CardSetupJson.write(setup), false, List.of("fields"), RECORDER,
                        AS_OF)));
        admitWithReason("Meena Patil", "Chest pain");
        memberRole = "DOCTOR";

        AdmissionList list = list("DOCTOR");

        assertThat(only(list).clinical().diagnosis()).isNull();
        assertThat(list.cardSetup().shownFields()).doesNotContain("diagnosis");
    }

    @Test
    void thePublishedSetupIsResolvedWithLabelsOrderAndActions() {
        publishWithFieldsOn("ipNumber");
        admitWithReason("Meena Patil", "x");
        memberRole = "OWNER";

        com.sahaj.ipd.domain.card.ResolvedCardSetup resolved = list("OWNER").cardSetup();

        assertThat(resolved.version()).isEqualTo(2);
        assertThat(resolved.isStandard()).isFalse();
        assertThat(resolved.layout()).isEqualTo("expanded");
        assertThat(resolved.cardsPerRow()).isEqualTo(4);
        assertThat(resolved.sections().get(0).fields()).extracting(f -> f.key())
                .containsExactly("name", "uhid", "ageSex", "bed", "ipNumber");
        assertThat(resolved.actions()).containsExactly("nursing", "emr", "transfer", "discharge");
    }

    @Test
    void aStoredSetupThatCannotBeReadShowsTheStandardCardWithTheFallbackFlag() {
        when(cardSetupRepository.findLatestVersion(TENANT)).thenReturn(Optional.of(
                new CardSetupRepository.VersionRow(4, "{broken", false, List.of(), RECORDER, AS_OF)));
        admitWithReason("Meena Patil", "x");

        AdmissionList list = list("NURSE");

        assertThat(list.cardSetup().fallback()).isTrue();
        assertThat(list.cardSetup().isStandard()).isTrue();
        assertThat(list.cardSetup().layout()).isEqualTo("standard");
        assertThat(list.items()).hasSize(1);
    }

    @Test
    void aStoredSetupThatLacksAllergiesStillShowsThemOnTheCard() {
        com.sahaj.ipd.domain.card.CardSetup standard = com.sahaj.ipd.domain.card.CardSetupDefaults.standard();
        java.util.List<com.sahaj.ipd.domain.card.CardSetup.Section> sections = new ArrayList<>();
        for (com.sahaj.ipd.domain.card.CardSetup.Section s : standard.sections()) {
            java.util.List<com.sahaj.ipd.domain.card.CardSetup.FieldPlacement> fields = new ArrayList<>(s.fields());
            fields.removeIf(f -> f.key().equals("allergies") || f.key().equals("ews"));
            sections.add(new com.sahaj.ipd.domain.card.CardSetup.Section(s.key(), s.on(), fields));
        }
        when(cardSetupRepository.findLatestVersion(TENANT)).thenReturn(Optional.of(new CardSetupRepository.VersionRow(
                2, CardSetupJson.write(new com.sahaj.ipd.domain.card.CardSetup("compact", 2, sections,
                        standard.actions())), false, List.of("layout"), RECORDER, AS_OF)));

        com.sahaj.ipd.domain.card.ResolvedCardSetup resolved = list("NURSE").cardSetup();

        assertThat(resolved.layout()).isEqualTo("compact");
        assertThat(resolved.shownFields()).contains("allergies", "ews", "uhid", "alerts", "name");
    }

    @Test
    void cardFactsAllSendsEveryNewFactToTheOwnerWhateverTheSetupSays() {
        recordVitals(admitWithReason("Meena Patil", "Chest pain"));
        mobiles.put(placed.get(0).admission().patientId(), "9876543210");
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(RECORDER, "Sr. Anjali Pawar"));
        memberRole = "OWNER";

        AdmissionList list = service.list(TENANT, ACTOR, "OWNER", null, "all");

        AdmittedPatientRow row = only(list);
        assertThat(row.clinical().diagnosis()).isEqualTo("Chest pain");
        AdmissionViews.CardLatestVitals tiles = row.clinical().latestVitals().get();
        assertThat(tiles.bp()).isNotNull();
        assertThat(tiles.pulse()).isNotNull();
        assertThat(tiles.spo2()).isNotNull();
        assertThat(tiles.temp()).isNotNull();
        assertThat(tiles.rr()).isNotNull();
        assertThat(tiles.recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(row.mobile()).contains("9876543210");
        // the setup itself is still the published one
        assertThat(list.cardSetup().version()).isEqualTo(1);
    }

    @Test
    void cardFactsAllIsForTheOwnerOnlyTokenRoleAndMembershipBoth() {
        admitWithReason("Meena Patil", "x");
        for (String role : new String[] {"NURSE", "DOCTOR", "FRONT_DESK"}) {
            memberRole = role;
            assertThatThrownBy(() -> service.list(TENANT, ACTOR, role, null, "all"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Only the clinic owner can preview the card setup.");
        }
        memberRole = "NURSE";
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", null, "all"))
                .isInstanceOf(AccessDeniedException.class);
        memberRole = "OWNER";
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "NURSE", null, "all"))
                .isInstanceOf(AccessDeniedException.class);
        verify(vitals, never()).findLatestReadingsOfAdmitted(any());
    }

    @Test
    void cardFactsSetupIsTheDefaultAndAnyOtherValueIs400() {
        admitWithReason("Meena Patil", "x");
        memberRole = "OWNER";

        assertThat(service.list(TENANT, ACTOR, "OWNER", null, "setup").items()).hasSize(1);
        assertThatThrownBy(() -> service.list(TENANT, ACTOR, "OWNER", null, "everything"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void theListWritesNothingAndReadsTheSetupOncePerAnswer() {
        publishWithFieldsOn("diagnosis");
        admitWithReason("Meena Patil", "x");
        memberRole = "OWNER";

        list("OWNER");

        verify(cardSetupRepository, times(1)).findLatestVersion(TENANT);
        verify(cardSetupRepository, never()).findDraft(any());
        verifyNoInteractions(auditLog, patientAccess);
    }
}
