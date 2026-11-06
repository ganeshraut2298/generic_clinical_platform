package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.ClinicalEventRepository.EventRow;
import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Banner;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.EventPage;
import com.sahaj.ipd.application.SafetyViews.Row;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-33 the Safety board, the counts-only summary, the banner and the clinical audit trail: order, counts, the
 * response clock on the database clock, the role matrix with the membership read again, paging and the guard against
 * a query per row.
 */
class SafetyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    /** 11 Nov 2026, 11:30 AM IST: the database clock of every answer. */
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    private EscalationBoardRepository board;
    private AdmissionRepository admissions;
    private ClinicalEventRepository events;
    private PatientLookupPort patients;
    private ActorDirectoryPort actors;
    private TeamMemberPort team;
    private ModuleEntitlementPort entitlements;
    private SafetyService service;
    private String memberRole = "DOCTOR";
    private boolean memberActive = true;
    private final Map<UUID, PatientSummary> patientMap = new HashMap<>();

    @BeforeEach
    void setUp() {
        board = mock(EscalationBoardRepository.class);
        admissions = mock(AdmissionRepository.class);
        events = mock(ClinicalEventRepository.class);
        patients = mock(PatientLookupPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        service = new SafetyService(board, admissions, events, patients, actors, team,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(board.databaseNow()).thenReturn(NOW);
        when(team.findActiveByUserId(eq(TENANT), eq(ACTOR))).thenAnswer(call -> memberRole == null ? Optional.empty()
                : Optional.of(new TeamMember(UUID.randomUUID(), "Member", memberRole, memberActive, 0L)));
        when(patients.findAll(eq(TENANT), anyCollection())).thenAnswer(call -> patientMap);
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of(RAISER, "Sr. Anjali Pawar",
                DOCTOR, "Dr Meera Joshi", ACTOR, "Dr Asha Rao"));
        when(admissions.countAdmitted(TENANT)).thenReturn(12);
    }

    // ---- fixtures ----

    private static Detail esc(int score, Status status, Instant raisedAt, Instant reopenedAt, int target) {
        Risk risk = target == 15 ? Risk.HIGH : Risk.MEDIUM;
        UUID by = status == Status.OPEN ? null : DOCTOR;
        String action = status == Status.DONE ? "BEDSIDE_REVIEW" : null;
        String note = status == Status.DONE ? "Plan text for the doctor only" : null;
        return new Detail(UUID.randomUUID(), UUID.randomUUID(), 3, score, risk, target, status, raisedAt, RAISER,
                reopenedAt, by, by == null ? null : raisedAt.plusSeconds(60), action, note, 0,
                status == Status.DONE ? DOCTOR : null, status == Status.DONE ? raisedAt.plusSeconds(120) : null);
    }

    private BoardRow onBoard(Detail d, String patientName, String bed) {
        UUID patientId = UUID.randomUUID();
        if (patientName != null) {
            patientMap.put(patientId, new PatientSummary(patientId, patientName, 40, "F", "9000000000"));
        }
        return new BoardRow(d, patientId, bed);
    }

    private void boardHas(BoardRow... rows) {
        when(board.findOnBoard(TENANT, NOW)).thenReturn(List.of(rows));
        List<BoardRow> live = new ArrayList<>();
        for (BoardRow row : rows) {
            if (row.escalation().status() != Status.DONE) {
                live.add(row);
            }
        }
        when(board.findLiveOnBoard(TENANT)).thenReturn(live);
    }

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    // ---- the board: rows, order and counts ----

    @Test
    void theBoardListsNotClosedFirstThenTheHigherScoreThenTheEarlierClockStartThenTheId() {
        Detail closedHigh = esc(14, Status.DONE, ago(Duration.ofHours(3)), null, 15);
        Detail ackMedium = esc(6, Status.ACK, ago(Duration.ofMinutes(50)), null, 60);
        Detail openMediumOlder = esc(5, Status.OPEN, ago(Duration.ofMinutes(40)), null, 60);
        Detail openMediumNewer = esc(5, Status.OPEN, ago(Duration.ofMinutes(10)), null, 60);
        Detail openHigh = esc(9, Status.OPEN, ago(Duration.ofMinutes(5)), null, 15);
        // raised long ago but re-opened just now: its clock starts at the re-open, so it sorts after the older start
        Detail reopened = esc(5, Status.OPEN, ago(Duration.ofHours(5)), ago(Duration.ofMinutes(2)), 60);
        boardHas(onBoard(closedHigh, "A", "1"), onBoard(openMediumNewer, "B", "2"), onBoard(reopened, "C", "3"),
                onBoard(ackMedium, "D", "4"), onBoard(openHigh, "E", "5"), onBoard(openMediumOlder, "F", "6"));

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.escalations()).extracting(Row::id).containsExactly(openHigh.id(), ackMedium.id(),
                openMediumOlder.id(), openMediumNewer.id(), reopened.id(), closedHigh.id());
    }

    @Test
    void twoEscalationsWithTheSameScoreAndClockStartAreOrderedByIdSoTheOrderIsStable() {
        Instant at = ago(Duration.ofMinutes(5));
        Detail one = esc(8, Status.OPEN, at, null, 15);
        Detail two = esc(8, Status.OPEN, at, null, 15);
        boardHas(onBoard(one, "A", "1"), onBoard(two, "B", "2"));
        UUID first = one.id().compareTo(two.id()) < 0 ? one.id() : two.id();

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.escalations().get(0).id()).isEqualTo(first);
    }

    @Test
    void theCountsAreTheNotClosedRowsAndThePastTargetRowsOfTheSameAnswer() {
        boardHas(onBoard(esc(9, Status.OPEN, ago(Duration.ofMinutes(20)), null, 15), "A", "1"),
                onBoard(esc(5, Status.OPEN, ago(Duration.ofMinutes(10)), null, 60), "B", "2"),
                onBoard(esc(7, Status.ACK, ago(Duration.ofHours(4)), null, 15), "C", "3"),
                onBoard(esc(8, Status.DONE, ago(Duration.ofHours(6)), null, 15), "D", "4"));

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.counts().open()).isEqualTo(3);
        assertThat(result.counts().pastTarget()).isEqualTo(1);
        assertThat(result.counts().asOf()).isEqualTo(NOW);
        assertThat(result.asOf()).isEqualTo(NOW);
        long notClosed = result.escalations().stream().filter(r -> r.status() != Status.DONE).count();
        long past = result.escalations().stream().filter(Row::pastTarget).count();
        assertThat(notClosed).isEqualTo(result.counts().open());
        assertThat(past).isEqualTo(result.counts().pastTarget());
    }

    @Test
    void pastTargetUsesTheExactDurationOnTheDatabaseClockAtTheEdges() {
        Detail exact15 = esc(9, Status.OPEN, NOW.minus(Duration.ofMinutes(15)), null, 15);
        Detail over15 = esc(9, Status.OPEN, NOW.minus(Duration.ofMinutes(15)).minusNanos(1_000), null, 15);
        Detail exact60 = esc(5, Status.OPEN, NOW.minus(Duration.ofMinutes(60)), null, 60);
        Detail over60 = esc(5, Status.OPEN, NOW.minus(Duration.ofMinutes(60)).minusSeconds(1), null, 60);
        boardHas(onBoard(exact15, "A", "1"), onBoard(over15, "B", "2"), onBoard(exact60, "C", "3"),
                onBoard(over60, "D", "4"));

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        Map<UUID, Row> byId = new HashMap<>();
        result.escalations().forEach(r -> byId.put(r.id(), r));
        assertThat(byId.get(exact15.id()).pastTarget()).isFalse();
        assertThat(byId.get(over15.id()).pastTarget()).isTrue();
        assertThat(byId.get(exact60.id()).pastTarget()).isFalse();
        assertThat(byId.get(over60.id()).pastTarget()).isTrue();
        assertThat(byId.get(exact15.id()).waitingMinutes()).isEqualTo(15);
        assertThat(byId.get(over60.id()).waitingMinutes()).isEqualTo(60);
        assertThat(result.counts().pastTarget()).isEqualTo(2);
    }

    @Test
    void aReopenedEscalationIsMeasuredFromTheReopenButShowsTheFirstRaiseAgo() {
        Detail reopened = esc(9, Status.OPEN, ago(Duration.ofMinutes(70)), ago(Duration.ofMinutes(10)), 15);
        boardHas(onBoard(reopened, "A", "1"));

        Row row = service.board(TENANT, ACTOR, "NURSE").escalations().get(0);

        assertThat(row.minutesSinceRaised()).isEqualTo(70);
        assertThat(row.waitingMinutes()).isEqualTo(10);
        assertThat(row.pastTarget()).isFalse();
        assertThat(row.reopenedAt()).isEqualTo(ago(Duration.ofMinutes(10)));
        assertThat(row.raisedAt()).isEqualTo(ago(Duration.ofMinutes(70)));
    }

    @Test
    void anAcknowledgedAndAClosedEscalationAreNeverPastTarget() {
        boardHas(onBoard(esc(9, Status.ACK, ago(Duration.ofHours(9)), null, 15), "A", "1"),
                onBoard(esc(9, Status.DONE, ago(Duration.ofHours(9)), null, 15), "B", "2"));

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.escalations()).allMatch(r -> !r.pastTarget());
        assertThat(result.counts().pastTarget()).isZero();
    }

    @Test
    void aRowCarriesTheNamesTheBedTheAcknowledgingDoctorAndTheResponseOfAClosedOne() {
        Detail done = esc(8, Status.DONE, ago(Duration.ofMinutes(30)), null, 15);
        Detail ack = esc(6, Status.ACK, ago(Duration.ofMinutes(20)), null, 60);
        boardHas(onBoard(done, "Sunita Patil", "ICU-2"), onBoard(ack, "Ramesh Kulkarni", "202"));

        Board result = service.board(TENANT, ACTOR, "OWNER");

        Row ackRow = result.escalations().get(0);
        assertThat(ackRow.patientName()).isEqualTo("Ramesh Kulkarni");
        assertThat(ackRow.bedCode()).isEqualTo("202");
        assertThat(ackRow.raisedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(ackRow.acknowledgedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(ackRow.response()).isNull();
        Row doneRow = result.escalations().get(1);
        assertThat(doneRow.patientName()).isEqualTo("Sunita Patil");
        assertThat(doneRow.response().action()).isEqualTo("BEDSIDE_REVIEW");
        assertThat(doneRow.response().actionLabel()).isEqualTo("Reviewed at the bedside");
        assertThat(doneRow.response().note()).isEqualTo("Plan text for the doctor only");
        assertThat(doneRow.response().closedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(doneRow.response().closedAt()).isNotNull();
    }

    @Test
    void anOpenRowHasNoAcknowledgingDoctorAndAMissingPatientIsShownAsPatientNotDropped() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), null, "9"));

        Row row = service.board(TENANT, ACTOR, "DOCTOR").escalations().get(0);

        assertThat(row.acknowledgedByName()).isNull();
        assertThat(row.acknowledgedAt()).isNull();
        assertThat(row.patientName()).isEqualTo("Patient");
        assertThat(row.bedCode()).isEqualTo("9");
    }

    @Test
    void anUnknownStaffMemberIsNamedTeamMember() {
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of());
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), "A", "1"));

        assertThat(service.board(TENANT, ACTOR, "DOCTOR").escalations().get(0).raisedByName())
                .isEqualTo("Team member");
    }

    @Test
    void anEmptyBoardHasZeroCountsAndNoLookups() {
        boardHas();

        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.escalations()).isEmpty();
        assertThat(result.counts().open()).isZero();
        assertThat(result.counts().pastTarget()).isZero();
        verify(patients, never()).findAll(any(), any());
        verify(actors, never()).displayNames(any(), any());
    }

    // ---- the view, the flags and the roles ----

    @Test
    void ownerAndDoctorSeeTheDoctorViewWithTheActionButtonsAndNoSecondTile() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), "A", "1"),
                onBoard(esc(6, Status.ACK, ago(Duration.ofMinutes(3)), null, 60), "B", "2"),
                onBoard(esc(7, Status.DONE, ago(Duration.ofMinutes(3)), null, 15), "C", "3"));
        for (String role : new String[] {"OWNER", "DOCTOR"}) {
            memberRole = role;

            Board result = service.board(TENANT, ACTOR, role);

            assertThat(result.view()).as(role).isEqualTo("DOCTOR");
            assertThat(result.yourPatients()).isNull();
            Row open = result.escalations().get(0);
            assertThat(open.status()).isEqualTo(Status.OPEN);
            assertThat(open.canAcknowledge()).isTrue();
            assertThat(open.canRespond()).isTrue();
            assertThat(open.canCallSenior()).isFalse();
            Row ack = result.escalations().get(1);
            assertThat(ack.canAcknowledge()).isFalse();
            assertThat(ack.canRespond()).isTrue();
            assertThat(ack.canCallSenior()).isFalse();
            Row done = result.escalations().get(2);
            assertThat(done.canAcknowledge()).isFalse();
            assertThat(done.canRespond()).isFalse();
            assertThat(done.canCallSenior()).isFalse();
            assertThat(result.responseActions()).extracting(a -> a.key()).containsExactly("BEDSIDE_REVIEW",
                    "TREATMENT_CHANGED", "MOVED_ICU_HDU", "RAPID_RESPONSE", "NO_CHANGE");
            assertThat(result.responseActions()).extracting(a -> a.label()).containsExactly("Reviewed at the bedside",
                    "Treatment changed", "Moved to ICU or HDU", "Rapid response team called",
                    "Reviewed, no change needed");
        }
        verify(admissions, never()).countAdmitted(any());
    }

    @Test
    void aNurseSeesTheNurseViewWithYourPatientsAndOnlyTheCallSeniorButtonOnAnOpenRow() {
        memberRole = "NURSE";
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), "A", "1"),
                onBoard(esc(6, Status.ACK, ago(Duration.ofMinutes(3)), null, 60), "B", "2"),
                onBoard(esc(7, Status.DONE, ago(Duration.ofMinutes(3)), null, 15), "C", "3"));

        Board result = service.board(TENANT, ACTOR, "NURSE");

        assertThat(result.view()).isEqualTo("NURSE");
        assertThat(result.yourPatients()).isEqualTo(12);
        assertThat(result.escalations()).extracting(Row::canAcknowledge).containsOnly(false);
        assertThat(result.escalations()).extracting(Row::canRespond).containsOnly(false);
        assertThat(result.escalations()).extracting(Row::canCallSenior).containsExactly(true, false, false);
        verify(admissions, times(1)).countAdmitted(TENANT);
    }

    @Test
    void aShownButtonIsNeverRefusedForTheRoleBecauseBothTheTokenAndTheMembershipMustAgree() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), "A", "1"));

        // the token still says OWNER, the membership is a NURSE now: the nurse view, and no doctor button
        memberRole = "NURSE";
        Row staleOwner = service.board(TENANT, ACTOR, "OWNER").escalations().get(0);
        assertThat(staleOwner.canAcknowledge()).isFalse();
        assertThat(staleOwner.canRespond()).isFalse();
        assertThat(staleOwner.canCallSenior()).isFalse();

        // the token says NURSE, the membership is a DOCTOR now
        memberRole = "DOCTOR";
        Row staleNurse = service.board(TENANT, ACTOR, "NURSE").escalations().get(0);
        assertThat(staleNurse.canAcknowledge()).isFalse();
        assertThat(staleNurse.canRespond()).isFalse();
        assertThat(staleNurse.canCallSenior()).isFalse();
    }

    @Test
    void everyOtherRoleIs403OnTheBoardTheSummaryAndTheEventsBeforeAnyModuleOrMembershipLookup() {
        for (String role : new String[] {"VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR",
            null}) {
            assertThatThrownBy(() -> service.board(TENANT, ACTOR, role)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.summary(TENANT, ACTOR, role)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.events(TENANT, ACTOR, role, null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(board, events, patients, actors, team, entitlements);
    }

    @Test
    void theMembershipIsReadAgainAndAChangedRoleADeactivationOrNoMembershipIs403() {
        for (String now : new String[] {"VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST", null}) {
            memberRole = now;
            assertThatThrownBy(() -> service.board(TENANT, ACTOR, "OWNER")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.summary(TENANT, ACTOR, "DOCTOR"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.events(TENANT, ACTOR, "NURSE", null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        memberRole = "DOCTOR";
        memberActive = false;
        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "DOCTOR")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(board, events, patients, actors);
    }

    @Test
    void moduleOffIs403ModuleOffForAnAllowedRoleAndTheRoleCheckComesFirst() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "DOCTOR")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.summary(TENANT, ACTOR, "NURSE")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.events(TENANT, ACTOR, "OWNER", null, null))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "FRONT_DESK")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(board, team);
    }

    @Test
    void trialOnAndReadOnlyAllShowTheBoardWithTheStateAndTheActionFlagsUnchanged() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(3)), null, 15), "A", "1"));
        for (String state : new String[] {"trial", "on", "readonly"}) {
            when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(state, false, 25));

            Board result = service.board(TENANT, ACTOR, "DOCTOR");

            assertThat(result.state()).isEqualTo(state);
            assertThat(result.escalations().get(0).canAcknowledge()).as(state).isTrue();
            assertThat(result.escalations().get(0).canRespond()).as(state).isTrue();
        }
    }

    // ---- N+1 guard ----

    @Test
    void aBoardOfFiftyRowsCostsOneEscalationQueryOnePatientLookupAndOneNamesLookup() {
        memberRole = "NURSE";
        List<BoardRow> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(onBoard(esc(3 + i % 12, i % 7 == 0 ? Status.DONE : Status.OPEN, ago(Duration.ofMinutes(i)), null,
                    i % 2 == 0 ? 15 : 60), "Patient " + i, "B" + i));
        }
        boardHas(rows.toArray(new BoardRow[0]));

        Board result = service.board(TENANT, ACTOR, "NURSE");

        assertThat(result.escalations()).hasSize(50);
        verify(board, times(1)).findOnBoard(TENANT, NOW);
        verify(board, never()).findById(any(), any());
        verify(patients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(patients, never()).find(any(), any());
        verify(actors, times(1)).displayNames(eq(TENANT), anyCollection());
        verify(actors, never()).displayName(any(), any());
        verify(admissions, times(1)).countAdmitted(TENANT);
        verify(admissions, never()).find(any(), any());
        verify(team, times(1)).findActiveByUserId(TENANT, ACTOR);
    }

    @Test
    void theBannerOfFiftyRowsCostsNoPatientLookupAtAll() {
        List<BoardRow> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(20 + i)), null, 15), "Patient " + i, "B" + i));
        }
        boardHas(rows.toArray(new BoardRow[0]));

        Banner banner = service.banner(TENANT, ACTOR, "DOCTOR", patientMap);

        assertThat(banner.pastTarget()).hasSize(50);
        verify(board, times(1)).findLiveOnBoard(TENANT);
        verify(board, never()).findOnBoard(any(), any());
        verifyNoInteractions(patients, actors);
    }

    // ---- the summary ----

    @Test
    void theSummaryIsCountsOnlyAndAgreesWithTheBoard() {
        boardHas(onBoard(esc(9, Status.OPEN, ago(Duration.ofMinutes(20)), null, 15), "A", "1"),
                onBoard(esc(5, Status.OPEN, ago(Duration.ofMinutes(10)), null, 60), "B", "2"),
                onBoard(esc(7, Status.ACK, ago(Duration.ofHours(4)), null, 15), "C", "3"),
                onBoard(esc(8, Status.DONE, ago(Duration.ofHours(6)), null, 15), "D", "4"));

        Counts summary = service.summary(TENANT, ACTOR, "NURSE");
        Board full = service.board(TENANT, ACTOR, "NURSE");

        assertThat(summary).isEqualTo(full.counts());
        assertThat(summary.open()).isEqualTo(3);
        assertThat(summary.pastTarget()).isEqualTo(1);
        assertThat(summary.asOf()).isEqualTo(NOW);
        verify(board, times(1)).findLiveOnBoard(TENANT);
    }

    @Test
    void theBoardTheSummaryAndTheBannerCarryTheSameChangeTokenAndItChangesWithAVersion() {
        Detail a = esc(9, Status.OPEN, ago(Duration.ofMinutes(20)), null, 15);
        Detail b = esc(7, Status.ACK, ago(Duration.ofHours(4)), null, 15);
        Detail closed = esc(8, Status.DONE, ago(Duration.ofHours(6)), null, 15);
        boardHas(onBoard(a, "A", "1"), onBoard(b, "B", "2"), onBoard(closed, "D", "4"));

        String fromBoard = service.board(TENANT, ACTOR, "NURSE").counts().changeToken();
        String fromSummary = service.summary(TENANT, ACTOR, "NURSE").changeToken();
        String fromBanner = service.banner(TENANT, ACTOR, "OWNER", new HashMap<>(patientMap)).counts().changeToken();

        assertThat(fromBoard).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(fromSummary).isEqualTo(fromBoard);
        assertThat(fromBanner).isEqualTo(fromBoard);
        // the closed row is not in the token: the token equals the one over the two live rows
        assertThat(fromBoard).isEqualTo(SafetyRows.changeToken(List.of(a, b)));

        Detail aAfterRaise = new Detail(a.id(), a.admissionId(), a.version() + 1, 10, a.ewsRisk(),
                a.targetMinutes(), a.status(), a.raisedAt(), a.raisedBy(), a.reopenedAt(), a.acknowledgedBy(),
                a.acknowledgedAt(), a.responseAction(), a.responseNote(), a.seniorCalls(), a.closedBy(),
                a.closedAt());
        boardHas(onBoard(aAfterRaise, "A", "1"), onBoard(b, "B", "2"), onBoard(closed, "D", "4"));
        assertThat(service.summary(TENANT, ACTOR, "NURSE").changeToken()).isNotEqualTo(fromBoard);
    }

    @Test
    void theBoardAsksTheRepositoryForTheTwentyFourHourWindowAgainstTheOneDatabaseClockReading() {
        boardHas();

        service.board(TENANT, ACTOR, "DOCTOR");

        verify(board).findOnBoard(TENANT, NOW);
        verify(board, times(1)).databaseNow();
    }

    @Test
    void theSummaryReadsNoNamesAndNoPatients() {
        boardHas(onBoard(esc(9, Status.OPEN, ago(Duration.ofMinutes(20)), null, 15), "A", "1"));

        service.summary(TENANT, ACTOR, "DOCTOR");

        verifyNoInteractions(patients, actors, admissions);
        verify(board, never()).findOnBoard(any(), any());
    }

    // ---- the banner of the Admitted patients tab ----

    @Test
    void theBannerListsOnlyOpenEscalationsPastTargetWithNameAndScoreInBoardOrder() {
        Detail past8 = esc(8, Status.OPEN, ago(Duration.ofMinutes(20)), null, 15);
        Detail past10 = esc(10, Status.OPEN, ago(Duration.ofMinutes(30)), null, 15);
        Detail notYet = esc(9, Status.OPEN, ago(Duration.ofMinutes(5)), null, 15);
        Detail acked = esc(12, Status.ACK, ago(Duration.ofHours(2)), null, 15);
        boardHas(onBoard(past8, "Sunita Patil", "1"), onBoard(notYet, "X", "2"), onBoard(acked, "Y", "3"),
                onBoard(past10, "Ramesh Kulkarni", "4"));
        Map<UUID, PatientSummary> loaded = new HashMap<>(patientMap);

        Banner banner = service.banner(TENANT, ACTOR, "OWNER", loaded);

        assertThat(banner.counts().open()).isEqualTo(4);
        assertThat(banner.counts().pastTarget()).isEqualTo(2);
        assertThat(banner.pastTarget()).extracting(SafetyViews.BannerItem::ewsScore).containsExactly(10, 8);
        assertThat(banner.pastTarget()).extracting(SafetyViews.BannerItem::patientName)
                .containsExactly("Ramesh Kulkarni", "Sunita Patil");
        assertThat(banner.pastTarget()).extracting(SafetyViews.BannerItem::escalationId)
                .containsExactly(past10.id(), past8.id());
        assertThat(banner.pastTarget()).extracting(SafetyViews.BannerItem::admissionId)
                .containsExactly(past10.admissionId(), past8.admissionId());
    }

    @Test
    void theBannerIsNullForEveryRoleWithoutVitalsAccessAndNothingIsRead() {
        for (String role : new String[] {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR",
            null}) {
            assertThat(service.banner(TENANT, ACTOR, role, Map.of())).as(String.valueOf(role)).isNull();
        }
        verifyNoInteractions(board, team, patients, actors);
    }

    @Test
    void theBannerIsNullWhenTheMembershipNoLongerAllowsIt() {
        for (String now : new String[] {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", null}) {
            memberRole = now;
            assertThat(service.banner(TENANT, ACTOR, "OWNER", Map.of())).as(String.valueOf(now)).isNull();
        }
        memberRole = "NURSE";
        memberActive = false;
        assertThat(service.banner(TENANT, ACTOR, "NURSE", Map.of())).isNull();
        verifyNoInteractions(board);
    }

    @Test
    void theBannerIsPresentWithEmptyListWhenNothingIsPastTarget() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(5)), null, 15), "A", "1"));

        Banner banner = service.banner(TENANT, ACTOR, "NURSE", Map.of());

        assertThat(banner).isNotNull();
        assertThat(banner.counts().open()).isEqualTo(1);
        assertThat(banner.counts().pastTarget()).isZero();
        assertThat(banner.pastTarget()).isEmpty();
    }

    @Test
    void aPatientMissingFromTheLoadedMapIsNamedPatientOnTheBanner() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(25)), null, 15), "A", "1"));

        Banner banner = service.banner(TENANT, ACTOR, "DOCTOR", Map.of());

        assertThat(banner.pastTarget().get(0).patientName()).isEqualTo("Patient");
    }

    // ---- the clinical audit trail ----

    private EventRow event(int n, UUID admissionId, UUID patientId, UUID actor) {
        return new EventRow(UUID.randomUUID(), 1000L - n, "esc", "Event " + n,
                Instant.parse("2026-11-11T05:30:00Z").minusSeconds(n), actor, admissionId, patientId, "202");
    }

    @Test
    void theFirstPageIs14EntriesAndAskedForOneMoreToKnowWhetherOlderOnesExist() {
        when(events.findPage(eq(TENANT), isNull(), isNull(), anyInt())).thenReturn(List.of());

        EventPage page = service.events(TENANT, ACTOR, "OWNER", null, null);

        verify(events).findPage(TENANT, null, null, 15);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.asOf()).isEqualTo(NOW);
    }

    @Test
    void aFullPageSetsTheNextCursorOfTheLastShownEntryAndHidesTheExtraRow() {
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            rows.add(event(i, null, null, RAISER));
        }
        when(events.findPage(eq(TENANT), isNull(), isNull(), eq(15))).thenReturn(rows);

        EventPage page = service.events(TENANT, ACTOR, "DOCTOR", null, null);

        assertThat(page.items()).hasSize(14);
        assertThat(page.items().get(13).text()).isEqualTo("Event 13");
        assertThat(EventCursor.decode(page.nextCursor())).isEqualTo(rows.get(13).position());
    }

    @Test
    void aPageWithExactlyTheLimitHasNoNextCursor() {
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            rows.add(event(i, null, null, RAISER));
        }
        when(events.findPage(eq(TENANT), isNull(), isNull(), eq(15))).thenReturn(rows);

        EventPage page = service.events(TENANT, ACTOR, "DOCTOR", null, null);

        assertThat(page.items()).hasSize(14);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void showEarlierEntriesSends50AndTheCursorOfThePageBeforeAndPassesTheDecodedPosition() {
        Position position = new Position(Instant.parse("2026-11-11T05:00:00.123456Z"), 77);
        String cursor = EventCursor.encode(position);
        when(events.findPage(eq(TENANT), isNull(), any(Position.class), eq(51))).thenReturn(List.of());

        service.events(TENANT, ACTOR, "OWNER", "50", cursor);

        ArgumentCaptor<Position> after = ArgumentCaptor.forClass(Position.class);
        verify(events).findPage(eq(TENANT), isNull(), after.capture(), eq(51));
        assertThat(after.getValue()).isEqualTo(position);
    }

    @Test
    void aLimitThatIsNotAWholeNumberFrom1To50IsA400OnTheFieldLimit() {
        for (String bad : new String[] {"0", "51", "-1", "abc", "1.5", " 5", "", "1000", "5 ", "+5", "٥"}) {
            assertThatThrownBy(() -> service.events(TENANT, ACTOR, "OWNER", bad, null))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("limit");
                        assertThat(e.getMessage()).isEqualTo("Reload the page and try again.");
                    });
        }
        for (String good : new String[] {"1", "14", "50", "07"}) {
            when(events.findPage(eq(TENANT), isNull(), isNull(), anyInt())).thenReturn(List.of());
            service.events(TENANT, ACTOR, "OWNER", good, null);
        }
        verify(events, never()).findPage(any(), any(), any(), eq(1001));
    }

    @Test
    void aCursorThisServerDidNotMakeIsA400OnTheFieldCursorAndNothingIsRead() {
        for (String bad : new String[] {"", "abc", "not a cursor", "A".repeat(65), "MS4y!"}) {
            assertThatThrownBy(() -> service.events(TENANT, ACTOR, "OWNER", null, bad))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field())
                            .isEqualTo("cursor"));
        }
        verify(events, never()).findPage(any(), any(), any(), anyInt());
    }

    @Test
    void ownerAndDoctorReadEveryEntryAndANurseReadsHerOwnAndTheAdmittedPatients() {
        when(events.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());
        memberRole = "OWNER";
        service.events(TENANT, ACTOR, "OWNER", null, null);
        memberRole = "DOCTOR";
        service.events(TENANT, ACTOR, "DOCTOR", null, null);
        memberRole = "NURSE";
        service.events(TENANT, ACTOR, "NURSE", null, null);

        verify(events, times(2)).findPage(TENANT, null, null, 15);
        verify(events, times(1)).findPage(TENANT, ACTOR, null, 15);
    }

    @Test
    void theNurseScopeFollowsTheMembershipRoleNotAStaleTokenRole() {
        when(events.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());
        memberRole = "NURSE";

        service.events(TENANT, ACTOR, "OWNER", null, null);

        verify(events).findPage(TENANT, ACTOR, null, 15);
    }

    @Test
    void anEntryShowsTheUserPatientAndBedReadAtDisplayTimeAndAnEntryWithoutAnAdmissionHasNoPatient() {
        UUID admission = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        patientMap.put(patientId, new PatientSummary(patientId, "Sunita Patil", 40, "F", "9000000000"));
        when(events.findPage(eq(TENANT), isNull(), isNull(), eq(15))).thenReturn(List.of(
                event(0, admission, patientId, DOCTOR), event(1, null, null, UUID.randomUUID()),
                event(2, admission, UUID.randomUUID(), RAISER)));

        EventPage page = service.events(TENANT, ACTOR, "OWNER", null, null);

        assertThat(page.items().get(0).actorName()).isEqualTo("Dr Meera Joshi");
        assertThat(page.items().get(0).patientName()).isEqualTo("Sunita Patil");
        assertThat(page.items().get(0).bedCode()).isEqualTo("202");
        assertThat(page.items().get(0).admissionId()).isEqualTo(admission);
        assertThat(page.items().get(0).kind()).isEqualTo("esc");
        assertThat(page.items().get(0).at()).isEqualTo(Instant.parse("2026-11-11T05:30:00Z"));
        assertThat(page.items().get(1).actorName()).isEqualTo("Team member");
        assertThat(page.items().get(1).patientName()).isNull();
        assertThat(page.items().get(1).bedCode()).isNull();
        assertThat(page.items().get(1).admissionId()).isNull();
        assertThat(page.items().get(2).patientName()).isEqualTo("Patient");
    }

    @Test
    void aPageOfFiftyEntriesCostsOneEventQueryOnePatientLookupAndOneNamesLookup() {
        List<EventRow> rows = new ArrayList<>();
        UUID patientId = UUID.randomUUID();
        patientMap.put(patientId, new PatientSummary(patientId, "Sunita Patil", 40, "F", "9000000000"));
        for (int i = 0; i < 51; i++) {
            rows.add(event(i, UUID.randomUUID(), patientId, i % 2 == 0 ? RAISER : DOCTOR));
        }
        when(events.findPage(eq(TENANT), isNull(), isNull(), eq(51))).thenReturn(rows);

        EventPage page = service.events(TENANT, ACTOR, "OWNER", "50", null);

        assertThat(page.items()).hasSize(50);
        assertThat(page.nextCursor()).isNotNull();
        verify(events, times(1)).findPage(any(), any(), any(), anyInt());
        verify(patients, times(1)).findAll(eq(TENANT), anyCollection());
        verify(patients, never()).find(any(), any());
        verify(actors, times(1)).displayNames(eq(TENANT), anyCollection());
        verify(actors, never()).displayName(any(), any());
    }

    @Test
    void anEmptyPageLooksUpNoNames() {
        when(events.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.events(TENANT, ACTOR, "OWNER", null, null);

        verify(patients, never()).findAll(any(), any());
    }

    // ---- privacy ----

    @Test
    void theBoardTheBannerAndTheEntriesHideNamesAndThePlanFromToString() {
        boardHas(onBoard(esc(8, Status.DONE, ago(Duration.ofMinutes(5)), null, 15), "Ramesh Kulkarni", "202"));
        Board result = service.board(TENANT, ACTOR, "DOCTOR");

        assertThat(result.toString()).doesNotContain("Ramesh").doesNotContain("Plan text");
        assertThat(result.escalations().get(0).toString()).doesNotContain("Ramesh").doesNotContain("Plan text");
        assertThat(result.escalations().get(0).response().toString()).doesNotContain("Plan text");
        assertThat(new SafetyViews.BannerItem(UUID.randomUUID(), UUID.randomUUID(), "Ramesh Kulkarni", 8).toString())
                .doesNotContain("Ramesh");
        assertThat(new SafetyViews.Event(UUID.randomUUID(), "esc", "text", NOW, "Dr Meera", UUID.randomUUID(),
                "Ramesh Kulkarni", "202").toString()).doesNotContain("Ramesh").doesNotContain("Meera");
        BoardRow row = onBoard(esc(8, Status.DONE, ago(Duration.ofMinutes(5)), null, 15), "Ramesh Kulkarni", "202");
        assertThat(row.toString()).doesNotContain("Plan text").doesNotContain("Ramesh");
        assertThat(row.escalation().toString()).doesNotContain("Plan text");
    }

    @Test
    void readingTheBoardWritesNothing() {
        boardHas(onBoard(esc(8, Status.OPEN, ago(Duration.ofMinutes(5)), null, 15), "A", "1"));

        service.board(TENANT, ACTOR, "DOCTOR");
        service.summary(TENANT, ACTOR, "DOCTOR");

        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(board, never()).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- transactions ----

    @Test
    void everyReadRunsInOneReadOnlyRepeatableReadTransaction() throws NoSuchMethodException {
        java.lang.reflect.Method[] methods = {
            SafetyService.class.getMethod("board", UUID.class, UUID.class, String.class),
            SafetyService.class.getMethod("summary", UUID.class, UUID.class, String.class),
            SafetyService.class.getMethod("events", UUID.class, UUID.class, String.class, String.class,
                    String.class)};
        for (java.lang.reflect.Method method : methods) {
            org.springframework.transaction.annotation.Transactional tx = method
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.readOnly()).as(method.getName()).isTrue();
            assertThat(tx.isolation()).as(method.getName())
                    .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        }
    }
}
