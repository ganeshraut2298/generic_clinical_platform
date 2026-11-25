package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.DoctorBlockedDatesPort;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.DeskReadRepository.AppointmentCardRow;
import com.sahaj.opd.application.DeskReadRepository.DepartmentLink;
import com.sahaj.opd.application.DeskReadRepository.FollowUpCardRow;
import com.sahaj.opd.application.DeskReadRepository.RulesAndFees;
import com.sahaj.opd.application.DeskReadRepository.ToBookPage;
import com.sahaj.opd.application.DeskReadRepository.ToBookRow;
import com.sahaj.opd.application.DeskReadRepository.VisitCardRow;
import com.sahaj.opd.application.DeskReadRepository.VisitPart;
import com.sahaj.opd.application.DeskViews.Action;
import com.sahaj.opd.application.DeskViews.Card;
import com.sahaj.opd.application.DeskViews.Column;
import com.sahaj.opd.application.DeskViews.DaySlots;
import com.sahaj.opd.application.DeskViews.DepartmentSource;
import com.sahaj.opd.application.DeskViews.Doctor;
import com.sahaj.opd.application.DeskViews.FollowUpState;
import com.sahaj.opd.application.DeskViews.Snapshot;
import com.sahaj.opd.application.DeskViews.Tag;
import com.sahaj.opd.application.DeskViews.TodayStatus;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.opd.application.WorkflowRepository.VersionRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-90 (ADR-032 D1, D3, D10, D11): the snapshot mapping, status derivation, counters and the day slots. */
class DeskReadServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEERA = UUID.randomUUID();
    private static final UUID ARUN = UUID.randomUUID();
    private static final UUID LEAVE_DOC = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    /** Wednesday 25 Nov 2026, 10:00 AM IST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T04:30:00Z"), ZoneOffset.UTC);

    private DeskReadRepository desk;
    private DoctorSlotSettingsRepository slots;
    private TeamMemberPort team;
    private DoctorBlockedDatesPort leavePort;
    private PatientLookupPort patients;
    private WorkflowRepository workflows;
    private ClinicSpecialtyPort clinicSpecialty;
    private DeskReadService service;

    private static SlotSettingsRow plan(LocalTime from, LocalTime to) {
        return new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0, List.of(new SessionRow(0, from, to)));
    }

    @BeforeEach
    void setUp() {
        desk = mock(DeskReadRepository.class);
        slots = mock(DoctorSlotSettingsRepository.class);
        team = mock(TeamMemberPort.class);
        leavePort = mock(DoctorBlockedDatesPort.class);
        patients = mock(PatientLookupPort.class);
        workflows = mock(WorkflowRepository.class);
        clinicSpecialty = mock(ClinicSpecialtyPort.class);
        service = new DeskReadService(desk, slots, team, new DoctorBlockedDates(List.of(leavePort)), patients, workflows,
                mock(WorkflowService.class), clinicSpecialty, CLOCK);

        when(desk.rulesAndFees(TENANT)).thenReturn(new RulesAndFees(null, "half"));
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of(
                new TeamMember(ARUN, "Dr Arun Shah", "DOCTOR", true, 50000),
                new TeamMember(MEERA, "Dr Meera Kulkarni", "DOCTOR", true, 40000),
                new TeamMember(LEAVE_DOC, "Dr Leela Nair", "VISITING_DOCTOR", true, 30000)));
        when(team.doctorSpecialties(eq(TENANT), anyCollection())).thenReturn(Map.of(ARUN, "Cardiology"));
        when(slots.findAllByTenant(TENANT)).thenReturn(Map.of(
                MEERA, plan(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                ARUN, plan(LocalTime.of(17, 0), LocalTime.of(18, 0)),
                LEAVE_DOC, plan(LocalTime.of(9, 0), LocalTime.of(13, 0))));
        when(desk.doctorDepartments(TENANT)).thenReturn(List.of(
                new DepartmentLink(MEERA, "gm", "General medicine", "gm", List.of("Room 2", "Room 3"))));
        when(leavePort.blockedDatesForDoctors(eq(TENANT), anyCollection(), eq(TODAY), eq(TODAY.plusDays(60))))
                .thenReturn(Map.of(LEAVE_DOC, Set.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(2))));
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of());
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(List.of());
        when(desk.followUpsOfVisits(eq(TENANT), any(), any())).thenReturn(List.of());
        when(desk.followUpsToBook(TENANT, TODAY, 200)).thenReturn(new ToBookPage(0, List.of()));
        when(workflows.listVersions(TENANT)).thenReturn(List.of(new VersionRow(1, List.of(
                new WorkflowStep("vitals", true, null), new WorkflowStep("counsel", false, null)), Instant.EPOCH, null)));
        when(patients.findAll(eq(TENANT), anyCollection())).thenAnswer(inv -> {
            Map<UUID, PatientSummary> map = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(1)) {
                map.put((UUID) id, new PatientSummary((UUID) id, "Patient " + id.toString().substring(0, 4), 30, "F", "9"));
            }
            return map;
        });
    }

    private static VisitPart visit(String stage, int token, String createdUtc) {
        return new VisitPart(UUID.randomUUID(), "NEW", stage, token, Instant.parse(createdUtc), 1, null, null);
    }

    private static AppointmentCardRow booked(UUID doctor, String time, String status) {
        return new AppointmentCardRow(UUID.randomUUID(), UUID.randomUUID(), doctor, TODAY, LocalTime.parse(time), status,
                "NEW", 3, null);
    }

    // ---------------------------------------------------------------- the rail

    @Test
    void railIsSortedByNameWithStatusDepartmentRoomAndFees() {
        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);

        assertThat(snapshot.doctors().total()).isEqualTo(3);
        assertThat(snapshot.doctors().items()).extracting(Doctor::name)
                .containsExactly("Dr Arun Shah", "Dr Leela Nair", "Dr Meera Kulkarni");
        Doctor meera = snapshot.doctors().items().get(2);
        assertThat(meera.department()).isEqualTo("General medicine");
        assertThat(meera.departmentSource()).isEqualTo(DepartmentSource.EMR_DEPARTMENT);
        assertThat(meera.room()).isEqualTo("Room 2, Room 3");
        assertThat(meera.consultFeePaise()).isEqualTo(40000);
        assertThat(meera.followUpFeePaise()).isEqualTo(20000);
        assertThat(meera.slotMinutes()).isEqualTo(15);
        assertThat(meera.closeBeforeHours()).isEqualTo(1);
        assertThat(meera.days()).hasSize(7);
        assertThat(meera.days().get(0).date()).isEqualTo(TODAY);
        Doctor arun = snapshot.doctors().items().get(0);
        assertThat(arun.department()).isEqualTo("Cardiology");
        assertThat(arun.departmentSource()).isEqualTo(DepartmentSource.SPECIALTY);
        assertThat(arun.room()).isNull();
        assertThat(snapshot.followUpFee()).isEqualTo("half");
        assertThat(snapshot.bookingRules()).isEqualTo(BookingRulesService.DEFAULTS);
    }

    @Test
    void aDoctorWithNoDepartmentAndNoSpecialtyHasNone() {
        when(team.doctorSpecialties(eq(TENANT), anyCollection())).thenReturn(Map.of());
        when(desk.doctorDepartments(TENANT)).thenReturn(List.of());

        Doctor arun = service.snapshot(TENANT, "OWNER", null, null, null).doctors().items().get(0);

        assertThat(arun.department()).isNull();
        assertThat(arun.departmentSource()).isEqualTo(DepartmentSource.NONE);
    }

    @Test
    void statusIsDerivedInTheContractOrder() {
        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);
        Doctor arun = snapshot.doctors().items().get(0);
        Doctor leela = snapshot.doctors().items().get(1);
        Doctor meera = snapshot.doctors().items().get(2);

        assertThat(meera.todayStatus()).isEqualTo(TodayStatus.IN_OPD);
        assertThat(arun.todayStatus()).isEqualTo(TodayStatus.STARTS_LATER);
        assertThat(arun.startsAt()).isEqualTo("17:00");
        assertThat(leela.todayStatus()).isEqualTo(TodayStatus.ON_LEAVE);
        assertThat(leela.leaveUntil()).isEqualTo(TODAY.plusDays(2));
        assertThat(leela.freeToday()).isZero();
        assertThat(leela.days().get(0).onLeave()).isTrue();
        assertThat(leela.days().get(3).onLeave()).isFalse();
        assertThat(leela.nextFree().date()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void statusRules() {
        SlotSettingsRow morning = plan(LocalTime.of(9, 0), LocalTime.of(13, 0));
        java.time.LocalDateTime ten = TODAY.atTime(10, 0);
        SlotSettingsRow offDay = new SlotSettingsRow(15, List.of(1), 0, 0, morning.sessions());
        SlotSettingsRow noSessions = new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0, List.of());

        assertThat(DeskReadService.status(morning, Set.of(TODAY), TODAY, ten, 5)).isEqualTo(TodayStatus.ON_LEAVE);
        assertThat(DeskReadService.status(offDay, Set.of(), TODAY, ten, 5)).isEqualTo(TodayStatus.OFF_TODAY);
        assertThat(DeskReadService.status(noSessions, Set.of(), TODAY, ten, 0)).isEqualTo(TodayStatus.OFF_TODAY);
        assertThat(DeskReadService.status(morning, Set.of(), TODAY, ten, 0)).isEqualTo(TodayStatus.FULL_TODAY);
        assertThat(DeskReadService.status(morning, Set.of(), TODAY, ten, 5)).isEqualTo(TodayStatus.IN_OPD);
        SlotSettingsRow split = new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new SessionRow(1, LocalTime.of(17, 0), LocalTime.of(20, 0))));
        assertThat(DeskReadService.status(split, Set.of(), TODAY, TODAY.atTime(14, 0), 5))
                .isEqualTo(TodayStatus.STARTS_LATER);
        assertThat(DeskReadService.status(split, Set.of(), TODAY, TODAY.atTime(21, 0), 0))
                .isEqualTo(TodayStatus.FULL_TODAY);
    }

    @Test
    void freeCountsFollowTheCallersRole() {
        Doctor staffView = service.snapshot(TENANT, "FRONT_DESK", null, null, null).doctors().items().get(2);
        Doctor nurseView = service.snapshot(TENANT, "NURSE", null, null, null).doctors().items().get(2);

        // 10:15, 10:30, 10:45 are inside the 1 hour cut-off: staff count them, a nurse does not
        assertThat(staffView.freeToday() - nurseView.freeToday()).isEqualTo(3);
        assertThat(service.snapshot(TENANT, "FRONT_DESK", null, null, null).counters().freeToday())
                .isEqualTo(staffView.freeToday() + 4 /* Arun 17:00-18:00 */);
        assertThat(service.snapshot(TENANT, "NURSE", null, null, null).counters().freeToday())
                .isEqualTo(nurseView.freeToday() + 4);
    }

    @Test
    void aBookedSlotLeavesTheCountAndTheNextFreeMovesOn() {
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of(
                booked(ARUN, "17:00", "BOOKED"), booked(ARUN, "17:15", "ARRIVED"), booked(ARUN, "17:30", "NOSHOW")));

        Doctor arun = service.snapshot(TENANT, "FRONT_DESK", null, null, null).doctors().items().get(0);

        // 17:00 BOOKED and 17:15 ARRIVED are taken; NOSHOW frees the slot again
        assertThat(arun.freeToday()).isEqualTo(2);
        assertThat(arun.nextFree().date()).isEqualTo(TODAY);
        assertThat(arun.nextFree().freeCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the board

    @Test
    void boardMapsColumnsTagsAndActions() {
        AppointmentCardRow late = booked(MEERA, "09:30", "BOOKED");          // 10:00 >= 09:30 + 15 min
        AppointmentCardRow dueNow = booked(MEERA, "09:50", "BOOKED");         // inside the grace
        AppointmentCardRow later = booked(MEERA, "11:00", "BOOKED");
        AppointmentCardRow stored = booked(MEERA, "08:00", "NOSHOW");
        AppointmentCardRow onLeave = booked(LEAVE_DOC, "11:30", "BOOKED");
        VisitPart waiting = visit("WAITING", 1, "2026-11-25T03:45:00Z");
        VisitPart vitals = visit("VITALS", 2, "2026-11-25T03:50:00Z");
        VisitPart with = visit("WITH_DOCTOR", 3, "2026-11-25T03:55:00Z");
        VisitPart done = visit("DONE", 4, "2026-11-25T03:40:00Z");
        UUID patient = UUID.randomUUID();
        AppointmentCardRow arrived = new AppointmentCardRow(UUID.randomUUID(), patient, MEERA, TODAY, LocalTime.of(9, 15),
                "ARRIVED", "FOLLOW_UP", 5, waiting);
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of(late, dueNow, later, stored,
                onLeave, arrived));
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(List.of(
                new VisitCardRow(UUID.randomUUID(), ARUN, vitals), new VisitCardRow(UUID.randomUUID(), ARUN, with),
                new VisitCardRow(UUID.randomUUID(), ARUN, done)));
        when(desk.followUpsOfVisits(eq(TENANT), any(), any())).thenReturn(List.of(new FollowUpCardRow(UUID.randomUUID(),
                done.id(), TODAY.plusDays(7), null, UUID.randomUUID(), false, TODAY.plusDays(7), LocalTime.of(10, 30))));

        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);
        Map<UUID, Card> byKey = new java.util.HashMap<>();
        snapshot.board().cards().forEach(c -> byKey.put(c.key(), c));

        Card lateCard = byKey.get(late.id());
        assertThat(lateCard.tags()).containsExactly(Tag.NO_SHOW);
        assertThat(lateCard.primaryAction()).isEqualTo(Action.REBOOK);
        assertThat(lateCard.column()).isEqualTo(Column.BOOKED);
        assertThat(lateCard.appointmentVersion()).isEqualTo(3L);
        assertThat(byKey.get(dueNow.id()).tags()).containsExactly(Tag.DUE_NOW);
        assertThat(byKey.get(dueNow.id()).primaryAction()).isEqualTo(Action.CHECK_IN);
        assertThat(byKey.get(later.id()).tags()).isEmpty();
        assertThat(byKey.get(later.id()).primaryAction()).isEqualTo(Action.CHECK_IN);
        assertThat(byKey.get(stored.id()).tags()).containsExactly(Tag.NO_SHOW);
        assertThat(byKey.get(onLeave.id()).tags()).containsExactly(Tag.DOCTOR_ON_LEAVE);
        assertThat(byKey.get(onLeave.id()).primaryAction()).isEqualTo(Action.MOVE_TO_NEW_SLOT);

        Card arrivedCard = byKey.get(arrived.id());
        assertThat(arrivedCard.column()).isEqualTo(Column.WAITING);
        assertThat(arrivedCard.stage()).isEqualTo("WAITING");
        assertThat(arrivedCard.nextStage()).isEqualTo("VITALS");
        assertThat(arrivedCard.tokenNo()).isEqualTo(1);
        assertThat(arrivedCard.visitType()).isEqualTo("NEW");
        assertThat(arrivedCard.primaryAction()).isEqualTo(Action.START_CONSULTATION);

        Card vitalsCard = byKey.get(vitals.id());
        assertThat(vitalsCard.kind()).isEqualTo(DeskViews.Kind.WALK_IN);
        assertThat(vitalsCard.column()).isEqualTo(Column.WAITING);
        assertThat(vitalsCard.tags()).containsExactly(Tag.VITALS);
        assertThat(vitalsCard.nextStage()).isEqualTo("WITH_DOCTOR");
        assertThat(vitalsCard.time()).isEqualTo("09:20");
        assertThat(byKey.get(with.id()).column()).isEqualTo(Column.IN_CONSULTATION);
        assertThat(byKey.get(with.id()).primaryAction()).isEqualTo(Action.COMPLETE_VISIT);
        Card doneCard = byKey.get(done.id());
        assertThat(doneCard.column()).isEqualTo(Column.DONE);
        assertThat(doneCard.nextStage()).isNull();
        assertThat(doneCard.followUp().state()).isEqualTo(FollowUpState.BOOKED);
        assertThat(doneCard.followUp().apptTime()).isEqualTo("10:30");
        assertThat(doneCard.primaryAction()).isEqualTo(Action.NONE);

        // counters: booked excludes the two no-shows; waiting = checked-in + vitals walk-in
        assertThat(snapshot.counters().booked()).isEqualTo(3);
        assertThat(snapshot.counters().waiting()).isEqualTo(2);
        assertThat(snapshot.counters().inConsult()).isEqualTo(1);
        assertThat(snapshot.counters().done()).isEqualTo(1);
    }

    @Test
    void aDoneVisitWithoutOrWithAnOpenFollowUpOffersSetFollowUp() {
        VisitPart done = visit("DONE", 4, "2026-11-25T03:40:00Z");
        VisitPart billing = visit("BILLING", 5, "2026-11-25T03:41:00Z");
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(List.of(
                new VisitCardRow(UUID.randomUUID(), ARUN, done), new VisitCardRow(UUID.randomUUID(), ARUN, billing)));
        when(desk.followUpsOfVisits(eq(TENANT), any(), any())).thenReturn(List.of(new FollowUpCardRow(UUID.randomUUID(),
                billing.id(), TODAY, null, null, false, null, null)));

        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);
        Map<UUID, Card> byKey = new java.util.HashMap<>();
        snapshot.board().cards().forEach(c -> byKey.put(c.key(), c));

        assertThat(byKey.get(done.id()).followUp().state()).isEqualTo(FollowUpState.NONE);
        assertThat(byKey.get(done.id()).primaryAction()).isEqualTo(Action.SET_FOLLOW_UP);
        assertThat(byKey.get(billing.id()).followUp().state()).isEqualTo(FollowUpState.OPEN);
        assertThat(byKey.get(billing.id()).primaryAction()).isEqualTo(Action.SET_FOLLOW_UP);
        assertThat(snapshot.counters().done()).isEqualTo(2);
    }

    @Test
    void notNeededAndReturnedFollowUpsShowNoAction() {
        VisitPart notNeeded = visit("DONE", 4, "2026-11-25T03:40:00Z");
        VisitPart returned = visit("DONE", 5, "2026-11-25T03:41:00Z");
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(List.of(
                new VisitCardRow(UUID.randomUUID(), ARUN, notNeeded), new VisitCardRow(UUID.randomUUID(), ARUN, returned)));
        when(desk.followUpsOfVisits(eq(TENANT), any(), any())).thenReturn(List.of(
                new FollowUpCardRow(UUID.randomUUID(), notNeeded.id(), TODAY, null, null, true, null, null),
                new FollowUpCardRow(UUID.randomUUID(), returned.id(), TODAY, UUID.randomUUID(), null, false, null, null)));

        Map<UUID, Card> byKey = new java.util.HashMap<>();
        service.snapshot(TENANT, "FRONT_DESK", null, null, null).board().cards().forEach(c -> byKey.put(c.key(), c));

        assertThat(byKey.get(notNeeded.id()).followUp().state()).isEqualTo(FollowUpState.NOT_NEEDED);
        assertThat(byKey.get(returned.id()).followUp().state()).isEqualTo(FollowUpState.RETURNED);
        assertThat(byKey.values()).extracting(Card::primaryAction).containsOnly(Action.NONE);
    }

    @Test
    void columnsAreOrderedAndEachByTimeAndTheBoardFilterNeverChangesCounters() {
        AppointmentCardRow a1 = booked(MEERA, "11:00", "BOOKED");
        AppointmentCardRow a2 = booked(ARUN, "10:30", "BOOKED");
        AppointmentCardRow a3 = booked(MEERA, "10:45", "BOOKED");
        VisitPart w = visit("WAITING", 1, "2026-11-25T03:45:00Z");
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of(a1, a2, a3));
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(List.of(
                new VisitCardRow(UUID.randomUUID(), MEERA, w)));

        Snapshot all = service.snapshot(TENANT, "FRONT_DESK", null, null, null);
        Snapshot meera = service.snapshot(TENANT, "FRONT_DESK", null, null, MEERA);

        assertThat(all.board().cards()).extracting(Card::column)
                .containsExactly(Column.BOOKED, Column.BOOKED, Column.BOOKED, Column.WAITING);
        assertThat(all.board().cards().subList(0, 3)).extracting(Card::time).containsExactly("10:30", "10:45", "11:00");
        assertThat(meera.board().cards()).hasSize(3);
        assertThat(meera.counters()).isEqualTo(all.counters());
        assertThat(all.board().truncated()).isFalse();
        assertThat(all.board().date()).isEqualTo(TODAY);
    }

    @Test
    void theBoardSaysTruncatedWhenTheAppointmentReadHitsItsRowLimit() {
        AppointmentCardRow row = booked(MEERA, "11:00", "BOOKED");
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6)))
                .thenReturn(java.util.Collections.nCopies(DeskReadRepository.MAX_CARD_ROWS + 1, row));

        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);

        assertThat(snapshot.board().truncated()).isTrue();
        assertThat(snapshot.board().cards()).hasSize(DeskReadService.MAX_CARDS);
    }

    @Test
    void theBoardSaysTruncatedWhenTheWalkInReadIsLargeOrHitsItsRowLimit() {
        VisitPart w = visit("WAITING", 1, "2026-11-25T03:45:00Z");
        VisitCardRow walkIn = new VisitCardRow(UUID.randomUUID(), MEERA, w);
        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(
                java.util.Collections.nCopies(DeskReadRepository.MAX_CARD_ROWS, walkIn));
        assertThat(service.snapshot(TENANT, "FRONT_DESK", null, null, null).board().truncated())
                .isTrue();   // 5,000 cards is above the 2,000 the board shows

        when(desk.walkInVisits(eq(TENANT), any(), any(), eq(TODAY))).thenReturn(
                java.util.Collections.nCopies(DeskReadRepository.MAX_CARD_ROWS + 1, walkIn));
        assertThat(service.snapshot(TENANT, "FRONT_DESK", null, null, null).board().truncated()).isTrue();
    }

    @Test
    void anAppointmentOfALaterDayIsNotACardButTakesItsSlot() {
        AppointmentCardRow tomorrow = new AppointmentCardRow(UUID.randomUUID(), UUID.randomUUID(), MEERA,
                TODAY.plusDays(1), LocalTime.of(9, 0), "BOOKED", "NEW", 0, null);
        when(desk.appointments(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of(tomorrow));

        Snapshot snapshot = service.snapshot(TENANT, "FRONT_DESK", null, null, null);
        Doctor meera = snapshot.doctors().items().get(2);

        assertThat(snapshot.board().cards()).isEmpty();
        assertThat(snapshot.counters().booked()).isZero();
        assertThat(meera.days().get(1).freeCount()).isEqualTo(15);
    }

    @Test
    void theToBookListMasksADischargeReasonForOtherRolesAndCountsLateDays() {
        UUID patient = UUID.randomUUID();
        when(desk.followUpsToBook(TENANT, TODAY, 200)).thenReturn(new ToBookPage(201, List.of(
                new ToBookRow(UUID.randomUUID(), patient, MEERA, TODAY, "Review after treatment", false),
                new ToBookRow(UUID.randomUUID(), patient, MEERA, TODAY.minusDays(3), "Sutures out; CKD stage 3", true))));

        DeskViews.ToBook staff = service.snapshot(TENANT, "FRONT_DESK", null, null, null).followUpsToBook();
        DeskViews.ToBook pharmacist = service.snapshot(TENANT, "PHARMACIST", null, null, null).followUpsToBook();

        assertThat(staff.total()).isEqualTo(201);
        assertThat(staff.items().get(0).status()).isEqualTo("DUE");
        assertThat(staff.items().get(0).daysLate()).isZero();
        assertThat(staff.items().get(1).status()).isEqualTo("MISSED");
        assertThat(staff.items().get(1).daysLate()).isEqualTo(3);
        assertThat(staff.items().get(1).reason()).isEqualTo("Sutures out; CKD stage 3");
        assertThat(pharmacist.items().get(1).reason()).isEqualTo(FollowUpService.MASKED_DISCHARGE_REASON);
        assertThat(staff.items().get(0).doctorName()).isEqualTo("Dr Meera Kulkarni");
    }

    // ---------------------------------------------------------------- the finder

    @Test
    void theSelectedDoctorsDayHasMorningAndEveningGroupsAndTheCutOffNoteForStaff() {
        when(slots.findAllByTenant(TENANT)).thenReturn(Map.of(
                MEERA, new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0,
                        List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(11, 0)),
                                new SessionRow(1, LocalTime.of(17, 0), LocalTime.of(17, 30))))));
        when(desk.takenSlots(eq(TENANT), eq(MEERA), eq(TODAY), eq(TODAY.plusDays(14)))).thenReturn(Map.of(
                TODAY, Set.of(LocalTime.of(10, 45), LocalTime.of(17, 0))));

        DaySlots staff = service.snapshot(TENANT, "FRONT_DESK", MEERA, TODAY, null).selected();
        DaySlots nurse = service.snapshot(TENANT, "NURSE", MEERA, TODAY, null).selected();

        assertThat(staff.groups()).extracting(DeskViews.SlotGroup::label)
                .containsExactly(DeskViews.GroupLabel.MORNING, DeskViews.GroupLabel.EVENING);
        assertThat(staff.groups().get(0).slots()).extracting(DeskViews.Slot::time)
                .containsExactly("10:15", "10:30");
        assertThat(staff.groups().get(0).slots()).allMatch(DeskViews.Slot::insideCutOff);
        assertThat(staff.groups().get(1).slots()).extracting(DeskViews.Slot::time).containsExactly("17:15");
        assertThat(staff.freeCount()).isEqualTo(3);
        assertThat(nurse.groups()).hasSize(1);
        assertThat(nurse.groups().get(0).label()).isEqualTo(DeskViews.GroupLabel.EVENING);
        assertThat(nurse.freeCount()).isEqualTo(1);
        assertThat(staff.doctorMembershipId()).isEqualTo(MEERA);
        assertThat(staff.working()).isTrue();
    }

    @Test
    void withoutADoctorThereIsNoSelectedBlock() {
        assertThat(service.snapshot(TENANT, "OWNER", null, null, null).selected()).isNull();
    }

    @Test
    void aFinderDayOutsideTodayToSixDaysAheadIs400() {
        assertThatThrownBy(() -> service.snapshot(TENANT, "OWNER", MEERA, TODAY.plusDays(7), null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.snapshot(TENANT, "OWNER", MEERA, TODAY.minusDays(1), null))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anUnknownOrOtherClinicDoctorIs404() {
        assertThatThrownBy(() -> service.snapshot(TENANT, "OWNER", UUID.randomUUID(), TODAY, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.snapshot(TENANT, "OWNER", null, null, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSnapshotMakesAFixedNumberOfReadsWhateverTheNumberOfDoctors() {
        service.snapshot(TENANT, "FRONT_DESK", null, null, null);

        verify(desk, times(1)).rulesAndFees(TENANT);
        verify(desk, times(1)).doctorDepartments(TENANT);
        verify(desk, times(1)).appointments(eq(TENANT), any(), any());
        verify(desk, times(1)).walkInVisits(eq(TENANT), any(), any(), any());
        verify(desk, times(1)).followUpsOfVisits(eq(TENANT), any(), any());
        verify(desk, times(1)).followUpsToBook(eq(TENANT), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(slots, times(1)).findAllByTenant(TENANT);
        verify(team, times(1)).findActiveDoctors(TENANT);
        verify(team, times(1)).doctorSpecialties(eq(TENANT), anyCollection());
        verify(leavePort, times(1)).blockedDatesForDoctors(eq(TENANT), anyCollection(), any(), any());
        verify(desk, org.mockito.Mockito.never()).takenSlots(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- day-slots endpoint

    @Test
    void daySlotsReturnsTheDayAndTheNextFreeDay() {
        when(team.find(TENANT, MEERA)).thenReturn(Optional.of(new TeamMember(MEERA, "Dr Meera", "DOCTOR", true, 1)));
        when(slots.find(TENANT, MEERA)).thenReturn(Optional.of(plan(LocalTime.of(9, 0), LocalTime.of(10, 0))));
        when(leavePort.blockedDates(eq(TENANT), eq(MEERA), eq(TODAY), any())).thenReturn(Set.of());
        when(desk.takenSlots(eq(TENANT), eq(MEERA), eq(TODAY), any())).thenReturn(Map.of());

        DaySlots today = service.daySlots(TENANT, "NURSE", MEERA, TODAY);

        // 09:00-10:00 is all in the past at 10:00 IST: nothing today, the next free day is tomorrow
        assertThat(today.freeCount()).isZero();
        assertThat(today.groups()).isEmpty();
        assertThat(today.nextFreeDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(today.working()).isTrue();
    }

    @Test
    void daySlotsOnLeaveShowsNoSlots() {
        when(team.find(TENANT, MEERA)).thenReturn(Optional.of(new TeamMember(MEERA, "Dr Meera", "DOCTOR", true, 1)));
        when(slots.find(TENANT, MEERA)).thenReturn(Optional.empty());
        when(leavePort.blockedDates(eq(TENANT), eq(MEERA), any(), any())).thenReturn(Set.of(TODAY.plusDays(2)));
        when(desk.takenSlots(eq(TENANT), eq(MEERA), any(), any())).thenReturn(Map.of());

        DaySlots day = service.daySlots(TENANT, "OWNER", MEERA, TODAY.plusDays(2));

        assertThat(day.onLeave()).isTrue();
        assertThat(day.freeCount()).isZero();
        assertThat(day.nextFreeDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void daySlotsRefusesADateOutsideTodayToOneHundredTwentyDaysAndNeverLooksPastTheCap() {
        when(team.find(TENANT, MEERA)).thenReturn(Optional.of(new TeamMember(MEERA, "Dr Meera", "DOCTOR", true, 1)));
        when(slots.find(TENANT, MEERA)).thenReturn(Optional.empty());
        when(leavePort.blockedDates(eq(TENANT), eq(MEERA), any(), any())).thenReturn(Set.of());
        when(desk.takenSlots(eq(TENANT), eq(MEERA), any(), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.daySlots(TENANT, "OWNER", MEERA, TODAY.minusDays(1)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.daySlots(TENANT, "OWNER", MEERA, TODAY.plusDays(121)))
                .isInstanceOf(InvalidInputException.class);
        DaySlots last = service.daySlots(TENANT, "OWNER", MEERA, TODAY.plusDays(120));
        assertThat(last.freeCount()).isEqualTo(16);
        assertThat(last.nextFreeDate()).isNull();
    }

    @Test
    void daySlotsOfAnInactiveOrUnknownDoctorIs404() {
        when(team.find(TENANT, MEERA)).thenReturn(Optional.of(new TeamMember(MEERA, "Dr Meera", "DOCTOR", false, 1)));

        assertThatThrownBy(() -> service.daySlots(TENANT, "OWNER", MEERA, TODAY)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.daySlots(TENANT, "OWNER", UUID.randomUUID(), TODAY))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- token panel (SAH-92-2)

    private DeskReadService withVisits(VisitRepository visits) {
        return new DeskReadService(desk, slots, team, new DoctorBlockedDates(List.of(leavePort)), patients, workflows,
                mock(WorkflowService.class), clinicSpecialty, CLOCK, visits);
    }

    private VisitRepository.VisitRow queued(UUID doctor, String stage) {
        return new VisitRepository.VisitRow(UUID.randomUUID(), UUID.randomUUID(), doctor, "NEW", stage, 8, 40000,
                Instant.parse("2026-11-25T04:00:00Z"), 0);
    }

    @Test
    void theTokenPanelCountsPatientsAheadAndMultipliesBySlotLength() {
        VisitRepository visits = mock(VisitRepository.class);
        VisitRepository.VisitRow visit = queued(MEERA, "WAITING");
        when(visits.findById(TENANT, visit.id())).thenReturn(java.util.Optional.of(visit));
        when(visits.countAhead(TENANT, MEERA, TODAY, visit.createdAt(), 8)).thenReturn(3);
        when(patients.find(TENANT, visit.patientId()))
                .thenReturn(java.util.Optional.of(new PatientSummary(visit.patientId(), "Asha Rao", 30, "F", "9")));
        when(team.find(TENANT, MEERA)).thenReturn(java.util.Optional.of(
                new TeamMember(MEERA, "Dr Meera Kulkarni", "DOCTOR", true, 40000)));
        when(slots.find(TENANT, MEERA)).thenReturn(java.util.Optional.of(plan(LocalTime.of(9, 0), LocalTime.of(13, 0))));

        var panel = withVisits(visits).tokenPanel(TENANT, visit.id());

        assertThat(panel.patientsAhead()).isEqualTo(3);
        assertThat(panel.slotMinutes()).isEqualTo(15);
        assertThat(panel.estimatedWaitMinutes()).isEqualTo(45);
        assertThat(panel.tokenDate()).isEqualTo(TODAY);
        assertThat(panel.room()).isEqualTo("Room 2, Room 3");
        assertThat(panel.tokenNo()).isEqualTo(8);
        assertThat(panel.toString()).doesNotContain("Asha");
    }

    @Test
    void theTokenPanelOfAVisitPastTheQueueHasNoOneAheadAndNoWait() {
        VisitRepository visits = mock(VisitRepository.class);
        VisitRepository.VisitRow visit = queued(MEERA, "WITH_DOCTOR");
        when(visits.findById(TENANT, visit.id())).thenReturn(java.util.Optional.of(visit));
        when(patients.find(TENANT, visit.patientId()))
                .thenReturn(java.util.Optional.of(new PatientSummary(visit.patientId(), "Asha Rao", 30, "F", "9")));
        when(team.find(TENANT, MEERA)).thenReturn(java.util.Optional.of(
                new TeamMember(MEERA, "Dr Meera Kulkarni", "DOCTOR", true, 40000)));
        when(slots.find(TENANT, MEERA)).thenReturn(java.util.Optional.empty());

        var panel = withVisits(visits).tokenPanel(TENANT, visit.id());

        assertThat(panel.patientsAhead()).isZero();
        assertThat(panel.estimatedWaitMinutes()).isZero();
        verify(visits, org.mockito.Mockito.never()).countAhead(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theTokenPanelOfAnotherClinicsVisitIs404() {
        VisitRepository visits = mock(VisitRepository.class);
        when(visits.findById(eq(TENANT), any())).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> withVisits(visits).tokenPanel(TENANT, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
