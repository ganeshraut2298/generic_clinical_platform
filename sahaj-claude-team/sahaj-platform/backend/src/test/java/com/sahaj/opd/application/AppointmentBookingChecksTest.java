package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.DoctorBlockedDatesPort;
import com.sahaj.opd.application.AppointmentRepository.AppointmentRow;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/** SAH-91-2, SAH-91-3 (ADR-032 D4, D5): the booking checks in their order, the duplicate and the in-place reschedule. */
class AppointmentBookingChecksTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID OTHER_DOCTOR = UUID.randomUUID();
    private static final UUID APPT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    /** Wednesday 25 Nov 2026, 10:00 AM IST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T04:30:00Z"), ZoneOffset.UTC);

    private AppointmentRepository appointments;
    private BookingRulesRepository rules;
    private DoctorSlotSettingsRepository slotSettings;
    private DoctorBlockedDatesPort leave;
    private AuditLog audit;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        rules = mock(BookingRulesRepository.class);
        slotSettings = mock(DoctorSlotSettingsRepository.class);
        leave = mock(DoctorBlockedDatesPort.class);
        audit = mock(AuditLog.class);
        PatientLookupPort patients = mock(PatientLookupPort.class);
        TeamMemberPort team = mock(TeamMemberPort.class);
        service = new AppointmentService(appointments, rules, mock(VisitRepository.class), mock(VisitService.class),
                patients, team, new DoctorBlockedDates(List.of(leave)), audit, CLOCK, slotSettings);

        when(patients.find(TENANT, PATIENT))
                .thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30, "F", "9000000001")));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr Rao", "DOCTOR", true, 1)));
        when(team.find(TENANT, OTHER_DOCTOR))
                .thenReturn(Optional.of(new TeamMember(OTHER_DOCTOR, "Dr Nair", "DOCTOR", true, 1)));
        when(rules.find(TENANT)).thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, false)));
        when(slotSettings.find(TENANT, DOCTOR)).thenReturn(Optional.of(plan(0, null)));
        when(slotSettings.find(TENANT, OTHER_DOCTOR)).thenReturn(Optional.of(plan(0, null)));
        when(appointments.findActiveConflict(eq(TENANT), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(appointments.findActiveForPatient(eq(TENANT), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    private static SlotSettingsRow plan(int walkInEveryNth, Integer doctorCloseBefore) {
        return new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), walkInEveryNth, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))), doctorCloseBefore);
    }

    private AppointmentCreateInput input(LocalDate date, String time) {
        return new AppointmentCreateInput(PATIENT, DOCTOR, date, time, "NEW", "FRONT_DESK", null);
    }

    private void echoRow(String status, long version) {
        when(appointments.findById(eq(TENANT), any())).thenAnswer(inv -> Optional.of(new AppointmentRow(inv.getArgument(1),
                PATIENT, DOCTOR, TODAY, LocalTime.of(12, 0), status, "NEW", "FRONT_DESK", null, null, version)));
    }

    private static AppointmentRow row(String status, long version) {
        return new AppointmentRow(APPT, PATIENT, DOCTOR, TODAY, LocalTime.of(9, 30), status, "FOLLOW_UP", "FRONT_DESK",
                null, null, version);
    }

    private void assertProblem(Runnable call, String slug, int status) {
        assertThatThrownBy(call::run).isInstanceOf(OpdProblemException.class)
                .satisfies(e -> {
                    assertThat(((OpdProblemException) e).slug()).isEqualTo(slug);
                    assertThat(((OpdProblemException) e).status()).isEqualTo(status);
                });
        verify(appointments, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- booking

    @Test
    void aTimeOffTheGridIs400SlotNotOnGrid() {
        assertProblem(() -> service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "12:07")), "slot-not-on-grid", 400);
        assertProblem(() -> service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "13:00")), "slot-not-on-grid", 400);
    }

    @Test
    void aDayTheDoctorDoesNotWorkIs400DoctorNotWorking() {
        when(slotSettings.find(TENANT, DOCTOR)).thenReturn(Optional.of(new SlotSettingsRow(15, List.of(1), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))))));

        assertProblem(() -> service.create(TENANT, USER, "OWNER", input(TODAY, "12:00")), "doctor-not-working", 400);
    }

    @Test
    void aWalkInReservedSlotIs400() {
        when(slotSettings.find(TENANT, DOCTOR)).thenReturn(Optional.of(plan(4, null)));

        assertProblem(() -> service.create(TENANT, USER, "OWNER", input(TODAY, "11:45")), "slot-walk-in-only", 400);
    }

    @Test
    void aPastTimeIs400WithThePlainMessage() {
        assertThatThrownBy(() -> service.create(TENANT, USER, "OWNER", input(TODAY, "09:00")))
                .isInstanceOf(OpdProblemException.class).hasMessage("That time has passed.");
        assertProblem(() -> service.create(TENANT, USER, "OWNER", input(TODAY, "09:15")), "slot-in-past", 400);
    }

    @Test
    void staffMayBookInsideTheCutOff() {
        echoRow("BOOKED", 0);

        AppointmentView view = service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "10:30"));

        assertThat(view.status()).isEqualTo("BOOKED");
        verify(audit).record(eq("APPOINTMENT_BOOKED"), eq("APPOINTMENT"), any(), eq(null), any());
    }

    @Test
    void aLeaveDayIs409DoctorUnavailableAndComesBeforeTheGridChecks() {
        when(leave.blockedDates(TENANT, DOCTOR, TODAY, TODAY)).thenReturn(Set.of(TODAY));

        assertThatThrownBy(() -> service.create(TENANT, USER, "OWNER", input(TODAY, "12:07")))
                .isInstanceOf(ConflictException.class).hasMessage("This doctor is not taking bookings on that date.");
    }

    @Test
    void theSamePatientInTheSlotIs409DuplicateWithTheSavedIdAndNothingIsWritten() {
        UUID saved = UUID.randomUUID();
        when(appointments.findActiveForPatient(TENANT, PATIENT, DOCTOR, TODAY, LocalTime.of(12, 0), null))
                .thenReturn(Optional.of(new AppointmentRow(saved, PATIENT, DOCTOR, TODAY, LocalTime.of(12, 0), "BOOKED",
                        "NEW", "FRONT_DESK", null, null, 0)));

        assertThatThrownBy(() -> service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "12:00")))
                .isInstanceOfSatisfying(OpdProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("appointment-duplicate");
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.extensions()).containsEntry("appointmentId", saved);
                });
        verify(appointments, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theDuplicateCheckRunsBeforeTheSlotTakenCheckAndAfterTheLock() {
        when(appointments.findActiveForPatient(eq(TENANT), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        echoRow("BOOKED", 0);

        service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "12:00"));

        InOrder order = inOrder(appointments);
        order.verify(appointments).lockSlot(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0));
        order.verify(appointments).findActiveForPatient(TENANT, PATIENT, DOCTOR, TODAY, LocalTime.of(12, 0), null);
        order.verify(appointments).findActiveConflict(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0), null);
        order.verify(appointments).insert(any(), eq(PATIENT), eq(DOCTOR), eq(TODAY), eq(LocalTime.of(12, 0)), any(), any(),
                any(), eq(USER));
    }

    @Test
    void aTakenSlotIs409WithTheJustBookedMessage() {
        when(appointments.findActiveConflict(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0), null)).thenReturn(Optional.of(
                new AppointmentRow(UUID.randomUUID(), UUID.randomUUID(), DOCTOR, TODAY, LocalTime.of(12, 0), "BOOKED",
                        "NEW", "FRONT_DESK", null, null, 0)));

        assertThatThrownBy(() -> service.create(TENANT, USER, "FRONT_DESK", input(TODAY, "12:00")))
                .isInstanceOf(ConflictException.class).hasMessage("That slot was just booked. Pick another time.");
    }

    @Test
    void anotherClinicsOrInactiveDoctorIs404BeforeAnySlotCheck() {
        AppointmentCreateInput stranger = new AppointmentCreateInput(PATIENT, UUID.randomUUID(), TODAY, "12:07", "NEW",
                "FRONT_DESK", null);

        assertThatThrownBy(() -> service.create(TENANT, USER, "OWNER", stranger)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRoleThatMayNotBookIsRefusedFirst() {
        assertThatThrownBy(() -> service.create(TENANT, USER, "DOCTOR", input(TODAY, "12:00")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reschedule(TENANT, USER, "NURSE", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:00"))).isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- reschedule

    @Test
    void rescheduleMovesTheSameRowInPlaceAndAuditsBeforeAndAfterWithoutNames() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 3)));
        when(appointments.reschedule(APPT, 3, OTHER_DOCTOR, TODAY, LocalTime.of(12, 15), USER)).thenReturn(true);
        when(appointments.findById(TENANT, APPT)).thenReturn(Optional.of(new AppointmentRow(APPT, PATIENT, OTHER_DOCTOR,
                TODAY, LocalTime.of(12, 15), "BOOKED", "FOLLOW_UP", "FRONT_DESK", null, null, 4)));

        AppointmentView view = service.reschedule(TENANT, USER, "FRONT_DESK", APPT,
                new AppointmentRescheduleInput(3L, OTHER_DOCTOR, TODAY, "12:15"));

        assertThat(view.id()).isEqualTo(APPT);
        assertThat(view.version()).isEqualTo(4);
        assertThat(view.visitType()).isEqualTo("FOLLOW_UP");
        verify(appointments, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(appointments, never()).updateStatus(any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("APPOINTMENT_RESCHEDULED"), eq("APPOINTMENT"), eq(APPT), before.capture(), after.capture());
        assertThat(before.getValue()).containsOnlyKeys("doctorMembershipId", "apptDate", "apptTime", "status");
        assertThat(after.getValue()).containsEntry("doctorMembershipId", OTHER_DOCTOR)
                .containsEntry("apptTime", LocalTime.of(12, 15)).containsEntry("status", "BOOKED");
        assertThat(before.getValue().toString() + after.getValue()).doesNotContain("Asha");
    }

    @Test
    void rescheduleLocksTheRowFirstThenTheSlotAndExcludesItsOwnRowFromTheClashChecks() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("NOSHOW", 1)));
        when(appointments.reschedule(any(), anyLong(), any(), any(), any(), any())).thenReturn(true);
        when(appointments.findById(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 2)));

        service.reschedule(TENANT, USER, "OWNER", APPT, new AppointmentRescheduleInput(1L, DOCTOR, TODAY, "12:00"));

        InOrder order = inOrder(appointments);
        order.verify(appointments).findByIdForUpdate(TENANT, APPT);
        order.verify(appointments).lockSlot(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0));
        order.verify(appointments).findActiveForPatient(TENANT, PATIENT, DOCTOR, TODAY, LocalTime.of(12, 0), APPT);
        order.verify(appointments).findActiveConflict(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0), APPT);
        order.verify(appointments).reschedule(APPT, 1L, DOCTOR, TODAY, LocalTime.of(12, 0), USER);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void aStaleVersionIs409AppointmentChangedAndNothingIsWritten() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 5)));

        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(4L, DOCTOR, TODAY, "12:00")), "appointment-changed", 409);
        verify(appointments, never()).reschedule(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void aLostVersionRaceAtWriteTimeIs409AppointmentChanged() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 5)));
        when(appointments.reschedule(any(), anyLong(), any(), any(), any(), any())).thenReturn(false);

        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(5L, DOCTOR, TODAY, "12:00")), "appointment-changed", 409);
        verify(audit, never()).record(eq("APPOINTMENT_RESCHEDULED"), any(), any(), any(), any());
    }

    @Test
    void anArrivedOrCancelledAppointmentCannotMove() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("ARRIVED", 0)));
        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:00")), "appointment-not-reschedulable", 409);

        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("CANCELLED", 0)));
        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:00")), "appointment-not-reschedulable", 409);
    }

    @Test
    void anotherClinicsAppointmentIs404() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:00"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rescheduleAppliesTheSameSlotChecks() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 0)));

        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:07")), "slot-not-on-grid", 400);
        assertProblem(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "09:15")), "slot-in-past", 400);
        verify(appointments, never()).reschedule(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void rescheduleIntoATakenSlotIs409AndLeavesTheOldAppointmentUnchanged() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 0)));
        when(appointments.findActiveConflict(TENANT, DOCTOR, TODAY, LocalTime.of(12, 0), APPT)).thenReturn(Optional.of(
                new AppointmentRow(UUID.randomUUID(), UUID.randomUUID(), DOCTOR, TODAY, LocalTime.of(12, 0), "BOOKED",
                        "NEW", "FRONT_DESK", null, null, 0)));

        assertThatThrownBy(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:00"))).isInstanceOf(ConflictException.class);
        verify(appointments, never()).reschedule(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void rescheduleOntoALeaveDayIs409() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 0)));
        when(leave.blockedDates(TENANT, OTHER_DOCTOR, TODAY, TODAY)).thenReturn(Set.of(TODAY));

        assertThatThrownBy(() -> service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(0L, OTHER_DOCTOR, TODAY, "12:00"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void theSameDoctorDateAndTimeOfABookedAppointmentWritesNothing() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 2)));

        AppointmentView view = service.reschedule(TENANT, USER, "OWNER", APPT,
                new AppointmentRescheduleInput(2L, DOCTOR, TODAY, "09:30"));

        assertThat(view.version()).isEqualTo(2);
        verify(appointments, never()).reschedule(any(), anyLong(), any(), any(), any(), any());
        verify(appointments, never()).lockSlot(any(), any(), any(), any());
        verify(audit, never()).record(eq("APPOINTMENT_RESCHEDULED"), any(), any(), any(), any());
    }

    @Test
    void aBookedAppointmentPastItsGraceIsMovedWithoutAManualNoShowMark() {
        when(appointments.findByIdForUpdate(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 0)));
        when(appointments.reschedule(any(), anyLong(), any(), any(), any(), any())).thenReturn(true);
        when(appointments.findById(TENANT, APPT)).thenReturn(Optional.of(row("BOOKED", 1)));

        service.reschedule(TENANT, USER, "FRONT_DESK", APPT, new AppointmentRescheduleInput(0L, DOCTOR, TODAY, "12:30"));

        verify(appointments, never()).updateStatus(any(), any(), any());
        verify(appointments).reschedule(APPT, 0L, DOCTOR, TODAY, LocalTime.of(12, 30), USER);
    }
}
