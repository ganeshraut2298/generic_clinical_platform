package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.AppointmentRepository.AppointmentRow;
import com.sahaj.opd.application.AppointmentService.BookedSlot;
import com.sahaj.opd.application.AppointmentService.FreeSlot;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.opd.application.FollowUpRepository.VisitFollowUp;
import com.sahaj.opd.application.FollowUpService.VisitFollowUpResult;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.TeamMemberPort;
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

/**
 * SAH-51-6 (ADR-025 D6): the follow-up the doctor chose at sign (made, moved or left alone) and the appointment booked on the
 * first free slot of the doctor, with leave, taken slots and the 7-day limit.
 */
class FollowUpAndAppointmentFinishTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T05:00:00Z"), ZoneOffset.UTC);

    private final FollowUpRepository followUpRows = mock(FollowUpRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private FollowUpService followUps;

    private final AppointmentRepository appointmentRows = mock(AppointmentRepository.class);
    private final DoctorSlotSettingsRepository slots = mock(DoctorSlotSettingsRepository.class);
    private AppointmentService appointments;
    private final DoctorBlockedDates blocked = new DoctorBlockedDates(List.of());

    private static VisitRow visit() {
        return new VisitRow(VISIT, PATIENT, DOCTOR, "NEW", "WITH_DOCTOR", 3, 50000, Instant.parse("2026-11-13T04:00:00Z"), 0);
    }

    @BeforeEach
    void setUp() {
        followUps = new FollowUpService(followUpRows, mock(FeeSettingsRepository.class), mock(PatientLookupPort.class),
                mock(TeamMemberPort.class), audit, CLOCK);
        appointments = new AppointmentService(appointmentRows, mock(BookingRulesRepository.class),
                mock(VisitRepository.class), mock(VisitService.class), mock(PatientLookupPort.class),
                mock(TeamMemberPort.class), blocked, audit, CLOCK, slots);
    }

    // ---- due dates ----

    @Test
    void intervalsAreDaysOrCalendarMonthsOnTheVisitDayWithTheEndOfMonthClamped() {
        LocalDate day = LocalDate.of(2026, 1, 31);

        assertThat(FollowUpService.dueDateFor(day, "D3")).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(FollowUpService.dueDateFor(day, "D7")).isEqualTo(LocalDate.of(2026, 2, 7));
        assertThat(FollowUpService.dueDateFor(day, "D15")).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(FollowUpService.dueDateFor(day, "D14")).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(FollowUpService.dueDateFor(day, "M1")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(FollowUpService.dueDateFor(day, "M3")).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(FollowUpService.dueDateFor(day, "Y1")).isNull();
        assertThat(FollowUpService.dueDateFor(day, null)).isNull();
    }

    // ---- setForVisit ----

    @Test
    void aVisitWithoutAFollowUpGetsOneWithTheFixedReasonAndOneAudit() {
        UUID stored = UUID.randomUUID();
        when(followUpRows.findBySourceVisit(TENANT, VISIT)).thenReturn(Optional.empty(),
                Optional.of(new VisitFollowUp(stored, LocalDate.of(2026, 11, 20), null, null)));

        VisitFollowUpResult result = followUps.setForVisit(TENANT, USER, visit(), LocalDate.of(2026, 11, 20),
                "Review after treatment");

        assertThat(result.created()).isTrue();
        assertThat(result.id()).isEqualTo(stored);
        verify(followUpRows).insertIfAbsent(any(), eq(PATIENT), eq(DOCTOR), eq(VISIT), eq(LocalDate.of(2026, 11, 20)),
                eq("Review after treatment"), eq(USER));
        verify(audit).record(eq("FOLLOW_UP_CREATED"), eq("FOLLOW_UP"), eq(stored), any(), any());
    }

    @Test
    void anOpenFollowUpFromAnEarlierCardMoveTakesTheNewDueDateWithDatesOnlyInTheAudit() {
        UUID id = UUID.randomUUID();
        when(followUpRows.findBySourceVisit(TENANT, VISIT)).thenReturn(Optional.of(new VisitFollowUp(id,
                LocalDate.of(2026, 11, 20), null, null)));

        VisitFollowUpResult result = followUps.setForVisit(TENANT, USER, visit(), LocalDate.of(2026, 12, 13), "x");

        assertThat(result.created()).isFalse();
        verify(followUpRows).updateDueDate(TENANT, id, LocalDate.of(2026, 12, 13), USER);
        verify(audit).record("FOLLOW_UP_DUE_CHANGED", "FOLLOW_UP", id, Map.of("dueDate", "2026-11-20"),
                Map.of("dueDate", "2026-12-13"));
    }

    @Test
    void aReturnedFollowUpIsLeftAloneAndTheSameDateWritesNothing() {
        UUID returned = UUID.randomUUID();
        when(followUpRows.findBySourceVisit(TENANT, VISIT)).thenReturn(Optional.of(new VisitFollowUp(returned,
                LocalDate.of(2026, 11, 20), UUID.randomUUID(), null)));

        assertThat(followUps.setForVisit(TENANT, USER, visit(), LocalDate.of(2026, 12, 1), "x").returned()).isTrue();
        verify(followUpRows, never()).updateDueDate(any(), any(), any(), any());

        UUID same = UUID.randomUUID();
        when(followUpRows.findBySourceVisit(TENANT, VISIT)).thenReturn(Optional.of(new VisitFollowUp(same,
                LocalDate.of(2026, 11, 20), null, null)));
        followUps.setForVisit(TENANT, USER, visit(), LocalDate.of(2026, 11, 20), "x");
        verify(followUpRows, never()).updateDueDate(any(), eq(same), any(), any());
        verify(audit, never()).record(eq("FOLLOW_UP_DUE_CHANGED"), any(), eq(same), any(), any());
    }

    // ---- free slots and booking ----

    private static SlotSettingsRow nineToTen() {
        // 30-minute slots 09:00 and 09:30, every day of the week, no walk-in reserve
        return new SlotSettingsRow(30, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(10, 0))));
    }

    private static AppointmentRow booked(LocalDate date, LocalTime time) {
        return new AppointmentRow(UUID.randomUUID(), PATIENT, DOCTOR, date, time, "BOOKED", "NEW", "PHONE", null, null, 0);
    }

    @Test
    void theFirstFreeSlotSkipsATakenSlotAndATimeAlreadyPastToday() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));
        // today (13 Nov) it is 10:30 IST: nothing left; tomorrow 09:00 is taken so 09:30 is the first free slot
        when(appointmentRows.findActiveConflict(TENANT, DOCTOR, LocalDate.of(2026, 11, 14), LocalTime.of(9, 0)))
                .thenReturn(Optional.of(booked(LocalDate.of(2026, 11, 14), LocalTime.of(9, 0))));
        when(appointmentRows.findActiveConflict(TENANT, DOCTOR, LocalDate.of(2026, 11, 14), LocalTime.of(9, 30)))
                .thenReturn(Optional.empty());

        List<FreeSlot> free = appointments.freeSlots(TENANT, DOCTOR, LocalDate.of(2026, 11, 13), 7, 1);

        assertThat(free).containsExactly(new FreeSlot(LocalDate.of(2026, 11, 14), LocalTime.of(9, 30)));
    }

    @Test
    void bookingTakesTheFirstFreeSlotLocksItAndAuditsIdsOnly() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));
        when(appointmentRows.findActiveConflict(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(appointmentRows.findById(eq(TENANT), any())).thenAnswer(call -> Optional.of(booked(LocalDate.of(2026, 11, 20),
                LocalTime.of(9, 0))));

        Optional<BookedSlot> slot = appointments.bookFirstFree(TENANT, USER, PATIENT, DOCTOR, LocalDate.of(2026, 11, 20), 7,
                "FOLLOW_UP", "EMR");

        assertThat(slot).isPresent();
        assertThat(slot.get().date()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(slot.get().time()).isEqualTo(LocalTime.of(9, 0));
        verify(appointmentRows).lockSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 0));
        verify(appointmentRows).insert(any(), eq(PATIENT), eq(DOCTOR), eq(LocalDate.of(2026, 11, 20)),
                eq(LocalTime.of(9, 0)), eq("FOLLOW_UP"), eq("EMR"), eq(null), eq(USER));
    }

    @Test
    void aDoctorOnLeaveForTheWholeWeekHasNoFreeSlotAndNothingIsBooked() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));
        DoctorBlockedDates onLeave = new DoctorBlockedDates(List.of((tenant, doctor, from, to) ->
                from.datesUntil(to.plusDays(1)).collect(java.util.stream.Collectors.toSet())));
        AppointmentService leaveService = new AppointmentService(appointmentRows, mock(BookingRulesRepository.class),
                mock(VisitRepository.class), mock(VisitService.class), mock(PatientLookupPort.class),
                mock(TeamMemberPort.class), onLeave, audit, CLOCK, slots);

        assertThat(leaveService.bookFirstFree(TENANT, USER, PATIENT, DOCTOR, LocalDate.of(2026, 11, 20), 7, "FOLLOW_UP",
                "EMR")).isEmpty();
        verify(appointmentRows, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookingReadsOnlyAFewCandidateSlotsNotTwoHundredInsideTheSignTransaction() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));
        when(appointmentRows.findActiveConflict(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(appointmentRows.findById(eq(TENANT), any())).thenAnswer(call -> Optional.of(booked(LocalDate.of(2026, 11, 20),
                LocalTime.of(9, 0))));

        // 60 days of slots are open: the first ten are read, one is booked
        assertThat(appointments.bookFirstFree(TENANT, USER, PATIENT, DOCTOR, LocalDate.of(2026, 11, 20), 60, "FOLLOW_UP",
                "EMR")).isPresent();

        verify(appointmentRows, org.mockito.Mockito.atMost(11)).findActiveConflict(any(), any(), any(), any());
    }

    @Test
    void aSlotIsOpenWhenItIsOnTheGridOfAFutureWorkingDayAndNotOnLeaveOrAlreadyPast() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));

        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 30))).isTrue();
        // off the 30-minute grid, after the session, before it
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 7))).isFalse();
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(10, 0))).isFalse();
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(8, 30))).isFalse();
        // today it is 10:30 IST and yesterday is gone
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 13), LocalTime.of(9, 30))).isFalse();
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 12), LocalTime.of(9, 0))).isFalse();
        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, null, LocalTime.of(9, 0))).isFalse();

        DoctorBlockedDates onLeave = new DoctorBlockedDates(List.of((tenant, doctor, from, to) ->
                from.datesUntil(to.plusDays(1)).collect(java.util.stream.Collectors.toSet())));
        AppointmentService leaveService = new AppointmentService(appointmentRows, mock(BookingRulesRepository.class),
                mock(VisitRepository.class), mock(VisitService.class), mock(PatientLookupPort.class),
                mock(TeamMemberPort.class), onLeave, audit, CLOCK, slots);
        assertThat(leaveService.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 30))).isFalse();
    }

    @Test
    void aSlotAnotherPatientHasBookedIsStillAnOpenSlotOfTheDoctorsGrid() {
        when(slots.find(TENANT, DOCTOR)).thenReturn(Optional.of(nineToTen()));
        when(appointmentRows.findActiveConflict(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 0)))
                .thenReturn(Optional.of(booked(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0))));

        assertThat(appointments.isOpenSlot(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(9, 0))).isTrue();
    }

    @Test
    void aChosenSlotThatWasTakenMeanwhileIsNotBooked() {
        when(appointmentRows.findActiveConflict(TENANT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(17, 0)))
                .thenReturn(Optional.of(booked(LocalDate.of(2026, 11, 20), LocalTime.of(17, 0))));

        assertThat(appointments.bookAt(TENANT, USER, PATIENT, DOCTOR, LocalDate.of(2026, 11, 20), LocalTime.of(17, 0),
                "FOLLOW_UP", "EMR")).isEmpty();
        verify(appointmentRows, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(Set.of()).isEmpty();
    }
}
