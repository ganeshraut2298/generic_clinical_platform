package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.DeskFollowUpService.ListBooking;
import com.sahaj.opd.application.DeskFollowUpService.Result;
import com.sahaj.opd.application.DeskFollowUpService.VisitBooking;
import com.sahaj.opd.application.FollowUpRepository.LockedFollowUp;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-93 (ADR-032 D8): the follow-up panel writes, with mocked ports. */
class DeskFollowUpServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID FOLLOW_UP = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    /** 10:00 IST on 25 Nov 2026. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T04:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private FollowUpRepository followUps;
    private VisitRepository visits;
    private AppointmentService appointments;
    private AuditLog auditLog;
    private DeskFollowUpService service;

    @BeforeEach
    void setUp() {
        followUps = mock(FollowUpRepository.class);
        visits = mock(VisitRepository.class);
        appointments = mock(AppointmentService.class);
        auditLog = mock(AuditLog.class);
        service = new DeskFollowUpService(followUps, visits, appointments, auditLog, CLOCK);
    }

    private void visitAt(String stage) {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, DOCTOR, "NEW", stage,
                8, 50000, CLOCK.instant(), 0)));
    }

    private static LockedFollowUp row(UUID appointmentId, UUID returned, boolean notNeeded) {
        return new LockedFollowUp(FOLLOW_UP, PATIENT, DOCTOR, VISIT, TODAY.plusDays(3), returned, appointmentId,
                notNeeded);
    }

    private static AppointmentView booked() {
        return new AppointmentView(APPOINTMENT, PATIENT, "Asha Rao", 30, "F", DOCTOR, "Dr Rao",
                LocalDate.of(2026, 12, 2), LocalTime.of(10, 30), "BOOKED", "FOLLOW_UP", "FRONT_DESK", null, null, null, 0);
    }

    private static VisitBooking visitBooking() {
        return new VisitBooking("D7", LocalDate.of(2026, 12, 2), "10:30", null);
    }

    @Test
    void aVisitStillWithTheDoctorIs409VisitNotDone() {
        visitAt("WITH_DOCTOR");

        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "FRONT_DESK", VISIT, visitBooking()))
                .isInstanceOfSatisfying(OpdProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("visit-not-done");
                    assertThat(ex.getMessage()).isEqualTo("Set the follow-up after the visit is completed.");
                });
        verify(followUps, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aVisitOfAnotherClinicIs404() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markNotNeeded(TENANT, USER, "OWNER", VISIT))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNonStaffRoleIs403() {
        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "NURSE", VISIT, visitBooking()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.markNotNeeded(TENANT, USER, "DOCTOR", VISIT))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.bookFromList(TENANT, USER, "BILLING", FOLLOW_UP,
                new ListBooking(DOCTOR, TODAY, "10:30", null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownIntervalIs400() {
        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "OWNER", VISIT,
                new VisitBooking("D3", TODAY, "10:30", null))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void booksTheVisitsFollowUpCreatingItForAWalkInVisitAndLinksTheAppointment() {
        visitAt("DONE");
        UUID[] inserted = new UUID[1];
        when(followUps.insertIfAbsent(any(), eq(PATIENT), eq(DOCTOR), eq(VISIT), eq(TODAY), eq("Follow-up"), eq(USER)))
                .thenAnswer(inv -> {
                    inserted[0] = inv.getArgument(0);
                    return true;
                });
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT)).thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(new LockedFollowUp(inserted[0], PATIENT, DOCTOR, VISIT, TODAY, null, null,
                        false)));
        when(appointments.create(eq(TENANT), eq(USER), eq("FRONT_DESK"), any())).thenReturn(booked());

        Result result = service.bookForVisit(TENANT, USER, "FRONT_DESK", VISIT, visitBooking());

        assertThat(result.state()).isEqualTo("BOOKED");
        assertThat(result.followUpId()).isEqualTo(inserted[0]);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(result.appointment().id()).isEqualTo(APPOINTMENT);
        verify(followUps).updateDueDate(TENANT, inserted[0], LocalDate.of(2026, 12, 2), USER);
        verify(followUps).setAppointment(TENANT, inserted[0], APPOINTMENT, "D7", USER);
        verify(auditLog).record(eq("FOLLOW_UP_CREATED"), eq("FOLLOW_UP"), eq(inserted[0]), any(), any());
        verify(auditLog).record(eq("FOLLOW_UP_DUE_CHANGED"), eq("FOLLOW_UP"), eq(inserted[0]), any(), any());
        verify(auditLog).record(eq("FOLLOW_UP_BOOKED"), eq("FOLLOW_UP"), eq(inserted[0]), any(), any());
        verify(appointments).create(eq(TENANT), eq(USER), eq("FRONT_DESK"), org.mockito.ArgumentMatchers.argThat(
                input -> input.patientId().equals(PATIENT) && input.doctorMembershipId().equals(DOCTOR)
                        && "FOLLOW_UP".equals(input.visitType()) && "FRONT_DESK".equals(input.source())));
    }

    @Test
    void anAlreadyBookedFollowUpIs409AndNothingIsBookedTwice() {
        visitAt("BILLING");
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT))
                .thenReturn(Optional.of(row(APPOINTMENT, null, false)));

        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "OWNER", VISIT, visitBooking()))
                .isInstanceOfSatisfying(OpdProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("follow-up-already-booked"));
        verify(appointments, never()).create(any(), any(), any(), any());
    }

    @Test
    void aReturnedOrNotNeededFollowUpIsClosed() {
        visitAt("DONE");
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT)).thenReturn(Optional.of(row(null, null, true)));

        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "OWNER", VISIT, visitBooking()))
                .isInstanceOfSatisfying(OpdProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("follow-up-closed"));

        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT))
                .thenReturn(Optional.of(row(null, UUID.randomUUID(), false)));
        assertThatThrownBy(() -> service.bookForVisit(TENANT, USER, "OWNER", VISIT, visitBooking()))
                .isInstanceOfSatisfying(OpdProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("follow-up-closed"));
    }

    @Test
    void markingNotNeededStoresItOnceAndAuditsIt() {
        visitAt("DONE");
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT)).thenReturn(Optional.of(row(null, null, false)));

        Result result = service.markNotNeeded(TENANT, USER, "FRONT_DESK", VISIT);

        assertThat(result.state()).isEqualTo("NOT_NEEDED");
        assertThat(result.appointment()).isNull();
        verify(followUps).markNotNeeded(TENANT, FOLLOW_UP, USER);
        verify(auditLog).record(eq("FOLLOW_UP_NOT_NEEDED"), eq("FOLLOW_UP"), eq(FOLLOW_UP), any(), any());
    }

    @Test
    void markingNotNeededTwiceWritesNothingTheSecondTime() {
        visitAt("DONE");
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT)).thenReturn(Optional.of(row(null, null, true)));

        Result result = service.markNotNeeded(TENANT, USER, "OWNER", VISIT);

        assertThat(result.state()).isEqualTo("NOT_NEEDED");
        verify(followUps, never()).markNotNeeded(any(), any(), any());
        verify(auditLog, never()).record(eq("FOLLOW_UP_NOT_NEEDED"), any(), any(), any(), any());
    }

    @Test
    void notNeededOnABookedFollowUpIs409() {
        visitAt("DONE");
        when(followUps.findBySourceVisitForUpdate(TENANT, VISIT))
                .thenReturn(Optional.of(row(APPOINTMENT, null, false)));

        assertThatThrownBy(() -> service.markNotNeeded(TENANT, USER, "OWNER", VISIT))
                .isInstanceOfSatisfying(OpdProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("follow-up-already-booked"));
    }

    @Test
    void theIdleListBookingLinksTheFollowUpAndKeepsItsDueDate() {
        when(followUps.findByIdForUpdate(TENANT, FOLLOW_UP)).thenReturn(Optional.of(row(null, null, false)));
        when(appointments.create(eq(TENANT), eq(USER), eq("OWNER"), any())).thenReturn(booked());

        Result result = service.bookFromList(TENANT, USER, "OWNER", FOLLOW_UP,
                new ListBooking(DOCTOR, LocalDate.of(2026, 12, 2), "10:30", "NEW"));

        assertThat(result.dueDate()).isEqualTo(TODAY.plusDays(3));
        verify(followUps, never()).updateDueDate(any(), any(), any(), any());
        verify(followUps).setAppointment(TENANT, FOLLOW_UP, APPOINTMENT, null, USER);
        verify(appointments).create(eq(TENANT), eq(USER), eq("OWNER"), org.mockito.ArgumentMatchers.argThat(
                input -> "NEW".equals(input.visitType()) && input.patientId().equals(PATIENT)));
    }

    @Test
    void aFollowUpOfAnotherClinicIs404() {
        when(followUps.findByIdForUpdate(TENANT, FOLLOW_UP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookFromList(TENANT, USER, "OWNER", FOLLOW_UP,
                new ListBooking(DOCTOR, TODAY, "10:30", null))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRefusedBookingLeavesTheFollowUpUnlinked() {
        when(followUps.findByIdForUpdate(TENANT, FOLLOW_UP)).thenReturn(Optional.of(row(null, null, false)));
        when(appointments.create(any(), any(), any(), any())).thenThrow(OpdProblemException.slotInPast());

        assertThatThrownBy(() -> service.bookFromList(TENANT, USER, "OWNER", FOLLOW_UP,
                new ListBooking(DOCTOR, TODAY, "09:00", null))).isInstanceOf(OpdProblemException.class);
        verify(followUps, never()).setAppointment(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(eq("FOLLOW_UP_BOOKED"), any(), any(), any(), any());
    }
}
