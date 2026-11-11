package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.WardScopeRepository.UnreviewedMark;
import com.sahaj.ipd.application.ClinicalEventRepository.EventRow;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.PatientScope.Kind;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.EventPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-34 AC 11, 15 to 20 and 64, 71: the Safety board, the counts and the audit trail on the caller's scope. A scoped
 * nurse sees her wards (and her live grants) only; OWNER and DOCTOR are never narrowed; the owner gets the emergency access
 * tile, panel and count; a scoped nurse also gets, as a number only, the escalations past target in other wards.
 */
class SafetyServiceScopeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID DOCTOR_USER = UUID.randomUUID();
    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID ADM_IN = UUID.randomUUID();
    private static final UUID ADM_OUT_PAST = UUID.randomUUID();
    private static final UUID ADM_OUT_ACK = UUID.randomUUID();
    private static final UUID ADM_OUT_WITHIN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    private EscalationBoardRepository board;
    private AdmissionRepository admissions;
    private ClinicalEventRepository events;
    private PatientLookupPort patients;
    private ActorDirectoryPort actors;
    private TeamMemberPort team;
    private ModuleEntitlementPort entitlements;
    private MedicineRoundRepository roundRepository;
    private ScopeGuard guard;
    private WardScopeRepository scopeRepository;
    private EmergencyAccessReader grantReader;
    private SafetyService service;
    private final Map<UUID, String> roles = new HashMap<>();

    @BeforeEach
    void setUp() {
        board = mock(EscalationBoardRepository.class);
        admissions = mock(AdmissionRepository.class);
        events = mock(ClinicalEventRepository.class);
        patients = mock(PatientLookupPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        roundRepository = mock(MedicineRoundRepository.class);
        guard = mock(ScopeGuard.class);
        scopeRepository = mock(WardScopeRepository.class);
        grantReader = mock(EmergencyAccessReader.class);
        service = new SafetyService(board, admissions, events, patients, actors, team,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)),
                new MedicineRoundReader(roundRepository, new MedicineCatalogue(
                        com.sahaj.ipd.domain.SeedCatalogue.catalogue()),
                        new SkippedOrderWarningsTest.MutableClock()), guard, scopeRepository, grantReader);
        roles.put(NURSE, "NURSE");
        roles.put(OWNER, "OWNER");
        roles.put(DOCTOR_USER, "DOCTOR");
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(board.databaseNow()).thenReturn(NOW);
        when(team.findActiveByUserId(eq(TENANT), any())).thenAnswer(call -> {
            String role = roles.get(call.<UUID>getArgument(1));
            return role == null ? Optional.empty()
                    : Optional.of(new TeamMember(MEMBERSHIP, "Member", role, true, 0L));
        });
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of());
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(RAISER, "Sr. Anjali Pawar"));
        when(admissions.countAdmitted(TENANT)).thenReturn(12);
        when(guard.scope(any(), any(), any(), any())).thenReturn(PatientScope.all());
        when(guard.visibleAdmissionIds(any(), any())).thenReturn(null);
        when(scopeRepository.unreviewedMark(TENANT)).thenReturn(new UnreviewedMark(0, null));
    }

    private static Detail esc(UUID admission, Status status, Instant raisedAt, int target) {
        return new Detail(UUID.randomUUID(), admission, 3, 9, Risk.HIGH, target, status, raisedAt, RAISER, null,
                status == Status.OPEN ? null : UUID.randomUUID(), status == Status.OPEN ? null : raisedAt, null, null, 0,
                null, null);
    }

    private void boardHas(Detail... details) {
        List<BoardRow> rows = new java.util.ArrayList<>();
        for (Detail d : details) {
            rows.add(new BoardRow(d, UUID.randomUUID(), "B" + rows.size()));
        }
        when(board.findOnBoard(TENANT, NOW)).thenReturn(rows);
        when(board.findLiveOnBoard(TENANT)).thenReturn(rows);
    }

    private PatientScope scoped(Map<UUID, Instant> grants) {
        return new PatientScope(Kind.WARDS, NURSE, "NURSE", "NURSE", MEMBERSHIP, Set.of(WARD), grants);
    }

    private void nurseSeesOnly(UUID... admissionIds) {
        PatientScope scope = scoped(Map.of());
        when(guard.scope(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(scope);
        when(guard.visibleAdmissionIds(TENANT, scope)).thenReturn(Set.of(admissionIds));
    }

    private Detail[] fourEscalations() {
        return new Detail[] {
            esc(ADM_IN, Status.OPEN, NOW.minus(Duration.ofMinutes(5)), 15),
            esc(ADM_OUT_PAST, Status.OPEN, NOW.minus(Duration.ofMinutes(20)), 15),
            esc(ADM_OUT_ACK, Status.ACK, NOW.minus(Duration.ofHours(3)), 15),
            esc(ADM_OUT_WITHIN, Status.OPEN, NOW.minus(Duration.ofMinutes(2)), 15)};
    }

    // ---- the scoped nurse ----

    @Test
    void aScopedNurseBoardHoldsOnlyHerWardsAndTheCountsAgreeWithTheRows() {
        boardHas(fourEscalations());
        nurseSeesOnly(ADM_IN);

        Board result = service.board(TENANT, NURSE, "NURSE");

        assertThat(result.escalations()).extracting(r -> r.admissionId()).containsExactly(ADM_IN);
        assertThat(result.counts().open()).isEqualTo(1);
        assertThat(result.counts().pastTarget()).isZero();
        assertThat(result.yourPatients()).isEqualTo(1);
        assertThat(result.scope().kind()).isEqualTo("WARDS");
        assertThat(result.scope().visible()).isEqualTo(1);
        assertThat(result.scope().admitted()).isEqualTo(12);
        assertThat(result.scope().hasWard()).isTrue();
        assertThat(result.emergencyAccess()).isNull();
        assertThat(result.counts().emergencyAccessWaiting()).isNull();
    }

    @Test
    void theOtherWardsCountIsTheOpenPastTargetOnesOutsideTheScopeAndANumberOnly() {
        boardHas(fourEscalations());
        nurseSeesOnly(ADM_IN);

        Board result = service.board(TENANT, NURSE, "NURSE");

        // ADM_OUT_PAST is open and past its 15 minutes; ADM_OUT_ACK is acknowledged; ADM_OUT_WITHIN is within target.
        assertThat(result.otherWardsPastTarget()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain(ADM_OUT_PAST.toString());
    }

    @Test
    void aNurseWithNoWardSeesNothingAndHasWardFalse() {
        boardHas(fourEscalations());
        PatientScope none = new PatientScope(Kind.NONE, NURSE, "NURSE", "NURSE", MEMBERSHIP, Set.of(), Map.of());
        when(guard.scope(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(none);
        when(guard.visibleAdmissionIds(TENANT, none)).thenReturn(Set.of());

        Board result = service.board(TENANT, NURSE, "NURSE");

        assertThat(result.escalations()).isEmpty();
        assertThat(result.yourPatients()).isZero();
        assertThat(result.scope().hasWard()).isFalse();
        assertThat(result.otherWardsPastTarget()).isEqualTo(1);
    }

    @Test
    void aNurseWithTheSwitchOffSeesEverythingWithKindAllAndNoOtherWardsCount() {
        boardHas(fourEscalations());

        Board result = service.board(TENANT, NURSE, "NURSE");

        assertThat(result.escalations()).hasSize(4);
        assertThat(result.scope().kind()).isEqualTo("ALL");
        assertThat(result.yourPatients()).isEqualTo(12);
        assertThat(result.otherWardsPastTarget()).isNull();
    }

    @Test
    void theSummaryAndTheBoardAgreePerCaller() {
        boardHas(fourEscalations());
        nurseSeesOnly(ADM_IN, ADM_OUT_PAST);

        Counts summary = service.summary(TENANT, NURSE, "NURSE");
        Board board = service.board(TENANT, NURSE, "NURSE");

        assertThat(summary.open()).isEqualTo(2).isEqualTo(board.counts().open());
        assertThat(summary.pastTarget()).isEqualTo(1).isEqualTo(board.counts().pastTarget());
        assertThat(summary.changeToken()).isEqualTo(board.counts().changeToken());
        assertThat(summary.emergencyAccessWaiting()).isNull();
    }

    @Test
    void theLiveEscalationsOfTheBannerAreOnTheScopeToo() {
        boardHas(fourEscalations());

        assertThat(service.liveEscalations(TENANT, Set.of(ADM_IN))).hasSize(1);
        assertThat(service.liveEscalations(TENANT, null)).hasSize(4);
    }

    // ---- owner and doctor are never narrowed ----

    @Test
    void doctorAndOwnerSeeEveryEscalationWithNoScopeFields() {
        boardHas(fourEscalations());

        for (UUID user : new UUID[] {DOCTOR_USER, OWNER}) {
            String role = roles.get(user);
            Board result = service.board(TENANT, user, role);
            assertThat(result.escalations()).as(role).hasSize(4);
            assertThat(result.counts().open()).as(role).isEqualTo(4);
            assertThat(result.scope()).as(role).isNull();
            assertThat(result.yourPatients()).as(role).isNull();
            assertThat(result.otherWardsPastTarget()).as(role).isNull();
        }
        assertThat(service.board(TENANT, DOCTOR_USER, "DOCTOR").emergencyAccess()).isNull();
    }

    // ---- the owner's emergency access ----

    @Test
    void theOwnerGetsTheFirstPageTheWaitingCountAndATokenThatChangesWithAGrant() {
        boardHas(fourEscalations());
        EmergencyAccessReviewPage page = new EmergencyAccessReviewPage(NOW, 1, List.of(), null);
        UUID grant = UUID.randomUUID();
        when(grantReader.page(eq(TENANT), eq(NOW), eq(25), eq(null), any(UnreviewedMark.class))).thenReturn(page);

        Board none = service.board(TENANT, OWNER, "OWNER");
        when(scopeRepository.unreviewedMark(TENANT)).thenReturn(new UnreviewedMark(1, grant));
        Board one = service.board(TENANT, OWNER, "OWNER");

        assertThat(none.counts().emergencyAccessWaiting()).isZero();
        assertThat(one.counts().emergencyAccessWaiting()).isEqualTo(1);
        assertThat(one.emergencyAccess()).isSameAs(page);
        assertThat(one.counts().changeToken()).isNotEqualTo(none.counts().changeToken());
        assertThat(service.summary(TENANT, OWNER, "OWNER").emergencyAccessWaiting()).isEqualTo(1);
        assertThat(service.summary(TENANT, OWNER, "OWNER").changeToken()).isEqualTo(one.counts().changeToken());
        assertThat(service.summary(TENANT, DOCTOR_USER, "DOCTOR").emergencyAccessWaiting()).isNull();
    }

    @Test
    void theOwnerBoardCountsTheWaitingGrantsOnceAndHandsTheSameMarkToThePage() {
        boardHas(fourEscalations());
        UnreviewedMark mark = new UnreviewedMark(3, UUID.randomUUID());
        when(scopeRepository.unreviewedMark(TENANT)).thenReturn(mark);
        when(grantReader.page(eq(TENANT), eq(NOW), eq(25), eq(null), eq(mark)))
                .thenReturn(new EmergencyAccessReviewPage(NOW, 3, List.of(), null));

        Board board = service.board(TENANT, OWNER, "OWNER");

        assertThat(board.counts().emergencyAccessWaiting()).isEqualTo(3);
        org.mockito.Mockito.verify(scopeRepository, org.mockito.Mockito.times(1)).unreviewedMark(TENANT);
        org.mockito.Mockito.verify(scopeRepository, org.mockito.Mockito.never()).countUnreviewed(any());
    }

    // ---- the audit trail ----

    @Test
    void aScopedNurseReadsHerOwnEntriesAndThoseOfHerWardsAndGrantsOnly() {
        UUID granted = UUID.randomUUID();
        PatientScope scope = scoped(Map.of(granted, NOW.plusSeconds(600)));
        when(guard.scope(TENANT, NURSE, "NURSE", "NURSE")).thenReturn(scope);
        when(events.findPageInScope(eq(TENANT), eq(NURSE), eq(Set.of(WARD)), eq(Set.of(granted)), eq(null), eq(15)))
                .thenReturn(List.of());

        EventPage page = service.events(TENANT, NURSE, "NURSE", null, null);

        assertThat(page.items()).isEmpty();
        verify(events, never()).findPage(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void anUnscopedNurseKeepsTheOldFilterAndOwnerAndDoctorReadEveryEntry() {
        when(events.findPage(eq(TENANT), eq(NURSE), eq(null), eq(15))).thenReturn(List.of());
        when(events.findPage(eq(TENANT), eq(null), eq(null), eq(15))).thenReturn(List.of());

        service.events(TENANT, NURSE, "NURSE", null, null);
        service.events(TENANT, OWNER, "OWNER", null, null);
        service.events(TENANT, DOCTOR_USER, "DOCTOR", null, null);

        verify(events).findPage(TENANT, NURSE, null, 15);
        verify(events, org.mockito.Mockito.times(2)).findPage(TENANT, null, null, 15);
        verify(events, never()).findPageInScope(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theReviewedEntryNamesWhoOpenedTheGrantReadAtDisplayTimeAndNoOtherEntryDoes() {
        UUID grantee = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(patients.findAll(eq(TENANT), anyCollection())).thenReturn(Map.of(patientId,
                new PatientSummary(patientId, "Test Patient", 40, "F", "9000000000")));
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(OWNER, "Owner Kulkarni", grantee,
                "Sr. Rupali More"));
        EventRow reviewed = new EventRow(UUID.randomUUID(), 2, "bg", EmergencyAccessReader.REVIEWED_TEXT, NOW, OWNER,
                UUID.randomUUID(), patientId, "B1", grantee);
        EventRow opened = new EventRow(UUID.randomUUID(), 1, "bg",
                EmergencyAccessReader.openedText(EmergencyAccessReason.DETERIORATING), NOW, grantee, UUID.randomUUID(),
                patientId, "B1", grantee);
        EventRow plain = new EventRow(UUID.randomUUID(), 0, "info", "Vitals recorded", NOW, OWNER, null, null, null);
        when(events.findPage(eq(TENANT), eq(null), eq(null), eq(15))).thenReturn(List.of(reviewed, opened, plain));

        EventPage page = service.events(TENANT, OWNER, "OWNER", null, null);

        assertThat(page.items().get(0).grantStaffName()).isEqualTo("Sr. Rupali More");
        assertThat(page.items().get(1).grantStaffName()).isNull();
        assertThat(page.items().get(2).grantStaffName()).isNull();
        assertThat(page.items().get(1).text()).isEqualTo("Emergency access opened · Patient deteriorating, rapid response");
    }
}
