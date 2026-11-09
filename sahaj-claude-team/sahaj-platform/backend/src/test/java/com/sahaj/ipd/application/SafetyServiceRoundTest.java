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

import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.application.MedicineRoundRepository.TodayDose;
import com.sahaj.ipd.application.SafetyViews.Banner;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.RoundPanel;
import com.sahaj.ipd.application.SafetyViews.RoundRow;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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
 * SAH-35 the Medicine round in the Safety board, the summary and the banner (ADR-012): the panel and its order, the
 * counts and the change token, the role matrix and the Give flag, the two statements per answer, privacy. No database.
 * At the clock of these tests it is 1:30 PM IST on 11 Nov 2026: an OD or TDS 8 AM slot is 5 h 30 min late and the TDS
 * 2 PM slot is due in 30 minutes.
 */
class SafetyServiceRoundTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant TWO_PM = Instant.parse("2026-11-11T08:30:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");

    private EscalationBoardRepository board;
    private PatientLookupPort patients;
    private ActorDirectoryPort actors;
    private TeamMemberPort team;
    private ModuleEntitlementPort entitlements;
    private MedicineRoundRepository roundRepository;
    private SafetyService service;
    private String memberRole = "NURSE";
    private String ipdState = "on";
    private final Map<UUID, PatientSummary> patientMap = new HashMap<>();
    private final List<ActiveOrder> orders = new ArrayList<>();
    private final List<TodayDose> doses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        board = mock(EscalationBoardRepository.class);
        patients = mock(PatientLookupPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        roundRepository = mock(MedicineRoundRepository.class);
        service = new SafetyService(board, mock(AdmissionRepository.class), mock(ClinicalEventRepository.class),
                patients, actors, team, new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)),
                new MedicineRoundReader(roundRepository, new MedicineCatalogue(SeedCatalogue.catalogue()),
                        new SkippedOrderWarningsTest.MutableClock()));
        when(entitlements.forTenant(TENANT)).thenAnswer(call -> new ModuleEntitlement(ipdState, false, 25));
        when(board.databaseNow()).thenReturn(NOW);
        when(board.findOnBoard(TENANT, NOW)).thenReturn(List.of());
        when(board.findLiveOnBoard(TENANT)).thenReturn(List.of());
        when(team.findActiveByUserId(eq(TENANT), eq(ACTOR))).thenAnswer(call -> memberRole == null ? Optional.empty()
                : Optional.of(new TeamMember(UUID.randomUUID(), "Member", memberRole, true, 0L)));
        when(patients.findAll(eq(TENANT), anyCollection())).thenAnswer(call -> patientMap);
        when(actors.displayNames(eq(TENANT), anyCollection())).thenReturn(Map.of());
        when(roundRepository.findActiveOfAdmitted(TENANT)).thenAnswer(call -> List.copyOf(orders));
        when(roundRepository.findDosesBetween(eq(TENANT), anyCollection(), any(), any()))
                .thenAnswer(call -> List.copyOf(doses));
    }

    // ---- fixtures ----

    private ActiveOrder order(String patientName, String bed, String drugKey, String frequencyKey, Instant verifiedAt) {
        UUID patientId = UUID.randomUUID();
        if (patientName != null) {
            patientMap.put(patientId, new PatientSummary(patientId, patientName, 40, "F", "9000000000"));
        }
        ActiveOrder order = new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), patientId, bed, drugKey,
                frequencyKey, verifiedAt);
        orders.add(order);
        return order;
    }

    private static Detail escalation(Status status) {
        return new Detail(UUID.randomUUID(), UUID.randomUUID(), 3, 8, Risk.HIGH, 15, status,
                NOW.minusSeconds(300), UUID.randomUUID(), null, null, null, null, null, 0, null, null);
    }

    private void liveEscalations(Detail... details) {
        List<BoardRow> rows = new ArrayList<>();
        for (Detail d : details) {
            rows.add(new BoardRow(d, UUID.randomUUID(), "9"));
        }
        when(board.findOnBoard(TENANT, NOW)).thenReturn(rows);
        when(board.findLiveOnBoard(TENANT)).thenReturn(rows);
    }

    // ---- the panel ----

    @Test
    void thePanelListsLateBeforeDueAndGivesTheRowTheNameBedMedicineAndLateHours() {
        order("Meena Patil", "204", "hep", "tds", LONG_AGO);

        RoundPanel panel = service.board(TENANT, ACTOR, "NURSE").medicineRound();

        assertThat(panel.rows()).extracting(RoundRow::state).containsExactly(State.LATE, State.DUE);
        RoundRow late = panel.rows().get(0);
        assertThat(late.slotAt()).isEqualTo(EIGHT_AM);
        assertThat(late.lateHours()).isEqualTo(5);
        assertThat(late.drugName()).isEqualTo("Inj. Heparin 5,000 IU");
        assertThat(late.highAlert()).isEqualTo("Anticoagulant");
        assertThat(late.patientName()).isEqualTo("Meena Patil");
        assertThat(late.bedCode()).isEqualTo("204");
        RoundRow due = panel.rows().get(1);
        assertThat(due.slotAt()).isEqualTo(TWO_PM);
        assertThat(due.lateHours()).isNull();
        assertThat(due.admissionId()).isNotNull();
        assertThat(due.orderId()).isNotNull();
        assertThat(panel.truncated()).isFalse();
    }

    @Test
    void anEmptyRoundIsAnEmptyPanelWithTheTrueFlags() {
        RoundPanel panel = service.board(TENANT, ACTOR, "NURSE").medicineRound();

        assertThat(panel.rows()).isEmpty();
        assertThat(panel.truncated()).isFalse();
        assertThat(panel.canGive()).isTrue();
        assertThat(panel.giveLockReason()).isNull();
    }

    @Test
    void aSlotWithADoseRecordIsNotOnThePanelAndNotInTheCounts() {
        ActiveOrder o = order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        doses.add(new TodayDose(o.orderId(), EIGHT_AM, "GIVEN"));

        Board result = service.board(TENANT, ACTOR, "NURSE");

        assertThat(result.medicineRound().rows()).isEmpty();
        assertThat(result.counts().dosesLate()).isZero();
        assertThat(result.counts().dosesDue()).isZero();
    }

    @Test
    void aPatientWhoseRecordIsNotFoundIsNamedPatientAndTheRowIsNeverDropped() {
        order(null, "204", "pcm", "od", LONG_AGO);

        RoundPanel panel = service.board(TENANT, ACTOR, "NURSE").medicineRound();

        assertThat(panel.rows()).hasSize(1);
        assertThat(panel.rows().get(0).patientName()).isEqualTo("Patient");
    }

    @Test
    void thePanelIsCutAtTwoHundredRowsButTheCountsAndTheTokenCoverEverySlot() {
        for (int i = 0; i < 201; i++) {
            order("Patient " + i, String.format("B%03d", i), "pcm", "od", LONG_AGO);
        }

        Board result = service.board(TENANT, ACTOR, "NURSE");

        assertThat(result.medicineRound().rows()).hasSize(200);
        assertThat(result.medicineRound().truncated()).isTrue();
        assertThat(result.counts().dosesLate()).isEqualTo(201);
        assertThat(result.counts().dosesDue()).isZero();
        assertThat(result.medicineRound().rows()).extracting(RoundRow::bedCode).doesNotContain("B200");
        // the token covers all 201: drop the last one and it changes
        String all = result.counts().changeToken();
        orders.remove(orders.size() - 1);
        assertThat(service.summary(TENANT, ACTOR, "NURSE").changeToken()).isNotEqualTo(all);
    }

    @Test
    void thePatientsOfTheEscalationRowsAndTheRoundAreLookedUpInOneCall() {
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        order("Ravi Kale", "205", "pcm", "od", LONG_AGO);
        liveEscalations(escalation(Status.OPEN));

        service.board(TENANT, ACTOR, "NURSE");

        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(patients, times(1)).findAll(eq(TENANT), ids.capture());
        assertThat(ids.getValue()).hasSize(3);
        verify(patients, never()).find(any(), any());
    }

    // ---- counts and the tile ----

    @Test
    void theCountsAreTheListedSlotsDueAndLateAndTheBoardTheSummaryAndTheBannerAgree() {
        order("Meena Patil", "204", "pcm", "tds", LONG_AGO);
        order("Ravi Kale", "205", "pcm", "od", LONG_AGO);
        liveEscalations(escalation(Status.OPEN));

        Board full = service.board(TENANT, ACTOR, "NURSE");
        Counts summary = service.summary(TENANT, ACTOR, "NURSE");
        Banner banner = bannerOf();

        assertThat(full.counts().dosesLate()).isEqualTo(2);
        assertThat(full.counts().dosesDue()).isEqualTo(1);
        assertThat(full.counts().open()).isEqualTo(1);
        assertThat(summary).isEqualTo(full.counts());
        assertThat(banner.counts()).isEqualTo(full.counts());
    }

    @Test
    void theTilePlusTheTabNumberRuleIsOpenPlusLateDosesNotDueDoses() {
        order("Meena Patil", "204", "pcm", "tds", LONG_AGO);
        liveEscalations(escalation(Status.OPEN), escalation(Status.ACK));

        Counts counts = service.summary(TENANT, ACTOR, "NURSE");

        assertThat(counts.dosesDue()).isEqualTo(1);
        assertThat(counts.dosesLate()).isEqualTo(1);
        assertThat(counts.open() + counts.dosesLate()).isEqualTo(3);
    }

    // ---- the change token ----

    @Test
    void withNoDoseInTheWindowTheTokenIsTheSah33Token() {
        Detail d = escalation(Status.OPEN);
        liveEscalations(d);

        Counts counts = service.summary(TENANT, ACTOR, "NURSE");

        assertThat(counts.changeToken()).isEqualTo(SafetyRows.changeToken(List.of(d), List.of()));
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        assertThat(service.summary(TENANT, ACTOR, "NURSE").changeToken()).isNotEqualTo(counts.changeToken());
    }

    @Test
    void aDoseRecordedByAnyoneChangesTheToken() {
        ActiveOrder o = order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        String before = service.summary(TENANT, ACTOR, "NURSE").changeToken();

        doses.add(new TodayDose(o.orderId(), EIGHT_AM, "GIVEN"));
        String after = service.summary(TENANT, ACTOR, "NURSE").changeToken();

        assertThat(after).isNotEqualTo(before).isEqualTo(SafetyRows.changeToken(List.of(), List.of()));
    }

    @Test
    void aDoseRecordedWhileAnotherBecomesDueChangesTheTokenEvenWhenTheCountsAreEqual() {
        ActiveOrder first = order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        Counts before = service.summary(TENANT, ACTOR, "NURSE");
        // the first dose is recorded and, in the same minute, another patient has a late slot: one late dose each time
        doses.add(new TodayDose(first.orderId(), EIGHT_AM, "GIVEN"));
        order("Ravi Kale", "205", "pcm", "od", LONG_AGO);

        Counts after = service.summary(TENANT, ACTOR, "NURSE");

        assertThat(after.dosesLate()).isEqualTo(before.dosesLate());
        assertThat(after.dosesDue()).isEqualTo(before.dosesDue());
        assertThat(after.changeToken()).isNotEqualTo(before.changeToken());
    }

    // ---- roles, Read-only, module Off ----

    @Test
    void canGiveNeedsBothTheTokenRoleAndTheMembershipRoleToBeNurseOrOwnerAndTrialOrOn() {
        String[][] cases = {
            // token role, membership role, ipd state, canGive, lock reason
            {"NURSE", "NURSE", "on", "true", null},
            {"NURSE", "NURSE", "trial", "true", null},
            {"OWNER", "OWNER", "on", "true", null},
            {"DOCTOR", "DOCTOR", "on", "false", "ROLE"},
            {"OWNER", "DOCTOR", "on", "false", "ROLE"},
            {"NURSE", "DOCTOR", "on", "false", "ROLE"},
            {"DOCTOR", "NURSE", "on", "false", "ROLE"},
            {"NURSE", "NURSE", "readonly", "false", "IPD_READONLY"},
            {"OWNER", "OWNER", "readonly", "false", "IPD_READONLY"},
            {"DOCTOR", "DOCTOR", "readonly", "false", "ROLE"},
        };
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);
        for (String[] c : cases) {
            memberRole = c[1];
            ipdState = c[2];

            RoundPanel panel = service.board(TENANT, ACTOR, c[0]).medicineRound();

            String label = String.join("/", c[0], c[1], c[2]);
            assertThat(panel.canGive()).as(label).isEqualTo(Boolean.parseBoolean(c[3]));
            assertThat(panel.giveLockReason()).as(label).isEqualTo(c[4]);
            assertThat(panel.rows()).as(label).hasSize(1);
        }
    }

    @Test
    void readersAreOwnerDoctorAndNurseAndEveryOtherRoleIs403BeforeAnyRoundRead() {
        for (String role : new String[] {"PHARMACIST", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "ADMIN", null}) {
            assertThatThrownBy(() -> service.board(TENANT, ACTOR, role)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.summary(TENANT, ACTOR, role)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(roundRepository);
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            memberRole = role;
            assertThat(service.board(TENANT, ACTOR, role).medicineRound()).isNotNull();
            assertThat(service.summary(TENANT, ACTOR, role)).isNotNull();
        }
    }

    @Test
    void aMembershipRoleChangeIsReadAgainAndRefusedBeforeAnyRoundRead() {
        for (String now : new String[] {"PHARMACIST", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", null}) {
            memberRole = now;
            assertThatThrownBy(() -> service.board(TENANT, ACTOR, "NURSE")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.summary(TENANT, ACTOR, "NURSE")).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(roundRepository);
    }

    @Test
    void ipdOffIsModuleOffAndNothingIsRead() {
        ipdState = "off";

        assertThatThrownBy(() -> service.board(TENANT, ACTOR, "NURSE")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.summary(TENANT, ACTOR, "NURSE")).isInstanceOf(ModuleOffException.class);
        verifyNoInteractions(roundRepository);
    }

    @Test
    void theRoundIsReadableInReadOnlyState() {
        ipdState = "readonly";
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);

        Board result = service.board(TENANT, ACTOR, "NURSE");

        assertThat(result.state()).isEqualTo("readonly");
        assertThat(result.medicineRound().rows()).hasSize(1);
        assertThat(service.summary(TENANT, ACTOR, "NURSE").dosesLate()).isEqualTo(1);
    }

    @Test
    void theBannerCarriesTheDoseCountsOfTheRound() {
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);

        assertThat(bannerOf().counts().dosesLate()).isEqualTo(1);
        verify(roundRepository, times(1)).findActiveOfAdmitted(TENANT);
    }

    /** The banner as the Admitted list builds it (the role gate is the list's, see AdmittedCardRules). */
    private Banner bannerOf() {
        return service.banner(TENANT, board.databaseNow(), service.liveEscalations(TENANT), row -> "Patient");
    }

    // ---- two statements per answer ----

    @Test
    void anAnswerRunsExactlyTwoRoundStatementsWithTheIstDayRangeAndTheAdmissionIdsOfTheOrders() {
        for (int i = 0; i < 30; i++) {
            order("Patient " + i, "B" + i, "pcm", "od", LONG_AGO);
        }

        service.board(TENANT, ACTOR, "NURSE");

        verify(roundRepository, times(1)).findActiveOfAdmitted(TENANT);
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(roundRepository, times(1)).findDosesBetween(eq(TENANT), ids.capture(),
                eq(DoseSlots.dayStart(NOW)), eq(DoseSlots.dayEnd(NOW)));
        assertThat(ids.getValue()).hasSize(30);
        assertThat(DoseSlots.dayStart(NOW)).isEqualTo(Instant.parse("2026-11-10T18:30:00Z"));
        assertThat(DoseSlots.dayEnd(NOW)).isEqualTo(Instant.parse("2026-11-11T18:30:00Z"));
    }

    @Test
    void withNoActiveOrderOnlyTheFirstStatementRuns() {
        service.summary(TENANT, ACTOR, "NURSE");

        verify(roundRepository, times(1)).findActiveOfAdmitted(TENANT);
        verify(roundRepository, never()).findDosesBetween(any(), anyCollection(), any(), any());
    }

    @Test
    void theSummaryLooksUpNoPatientAndNoStaffName() {
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);

        service.summary(TENANT, ACTOR, "NURSE");

        verifyNoInteractions(patients, actors);
    }

    // ---- privacy ----

    @Test
    void theBoardThePanelAndTheRowsHideNamesAndMedicinesFromToString() {
        order("Ramesh Kulkarni", "BED-777", "hep", "od", LONG_AGO);

        Board result = service.board(TENANT, ACTOR, "NURSE");

        RoundRow row = result.medicineRound().rows().get(0);
        assertThat(row.patientName()).isEqualTo("Ramesh Kulkarni");
        for (String text : new String[] {row.toString(), result.medicineRound().toString(), result.toString(),
            result.counts().toString()}) {
            assertThat(text).doesNotContain("Ramesh").doesNotContain("Heparin").doesNotContain("BED-777");
        }
        assertThat(orders.get(0).toString()).doesNotContain("BED-777").doesNotContain("hep");
    }

    @Test
    void theCountsAndTheirTokenHoldNoNameBedOrMedicine() {
        order("Ramesh Kulkarni", "BED-777", "hep", "od", LONG_AGO);

        Counts counts = service.summary(TENANT, ACTOR, "NURSE");

        assertThat(counts.changeToken()).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(counts.toString()).doesNotContain("Ramesh").doesNotContain("Heparin").doesNotContain("BED-777");
    }

    @Test
    void readingTheRoundWritesNothing() {
        order("Meena Patil", "204", "pcm", "od", LONG_AGO);

        service.board(TENANT, ACTOR, "NURSE");
        service.summary(TENANT, ACTOR, "NURSE");
        bannerOf();

        verify(board, never()).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- data faults ----

    @Test
    void anOrderForAMedicineNotInTheCatalogueIsLeftOutOfTheRoundAndTheRestOfTheBoardStillAnswers() {
        order("Meena Patil", "204", "zzz", "od", LONG_AGO);
        order("Raj Kumar", "205", "pcm", "od", LONG_AGO);

        assertThat(service.summary(TENANT, ACTOR, "NURSE").dosesLate()).isEqualTo(1);
        assertThat(service.board(TENANT, ACTOR, "NURSE").medicineRound().rows())
                .extracting(RoundRow::bedCode).containsExactly("205");
        assertThat(bannerOf().counts().dosesLate()).isEqualTo(1);
    }

    @Test
    void anOrderWithAnUnknownFrequencyIsLeftOutOfTheRoundToo() {
        order("Meena Patil", "204", "pcm", "weekly", LONG_AGO);

        assertThat(service.summary(TENANT, ACTOR, "NURSE").dosesLate()).isZero();
        assertThat(service.board(TENANT, ACTOR, "NURSE").medicineRound().rows()).isEmpty();
    }
}
