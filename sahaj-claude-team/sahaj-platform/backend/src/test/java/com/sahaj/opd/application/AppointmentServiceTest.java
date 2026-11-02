package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.AppointmentRepository.AppointmentRow;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
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

/** SAH-11 AC2, AC3, AC4: book, cancel, no-show and create-token, plus the booking rules behind them. */
class AppointmentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID APPT_ID = UUID.randomUUID();
    // Fixed at 2026-11-01 10:00 IST (04:30 UTC) so "today" bookings exercise the close-before window.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T04:30:00Z"), ZoneOffset.UTC);

    private AppointmentRepository appointmentRepository;
    private BookingRulesRepository bookingRulesRepository;
    private VisitRepository visitRepository;
    private VisitService visitService;
    private PatientLookupPort patientLookup;
    private TeamMemberPort teamMemberPort;
    private AuditLog auditLog;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        bookingRulesRepository = mock(BookingRulesRepository.class);
        visitRepository = mock(VisitRepository.class);
        visitService = mock(VisitService.class);
        patientLookup = mock(PatientLookupPort.class);
        teamMemberPort = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        service = new AppointmentService(appointmentRepository, bookingRulesRepository, visitRepository,
                visitService, patientLookup, teamMemberPort, auditLog, CLOCK);

        when(patientLookup.find(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(new PatientSummary(PATIENT_ID, "Asha Rao", 30, "F")));
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        when(bookingRulesRepository.find(TENANT_ID)).thenReturn(Optional.empty());
        when(appointmentRepository.findActiveConflict(eq(TENANT_ID), eq(DOCTOR_ID), any(), any()))
                .thenReturn(Optional.empty());
    }

    private AppointmentCreateInput createInput(LocalDate date, String time) {
        return new AppointmentCreateInput(PATIENT_ID, DOCTOR_ID, date, time, "NEW", "FRONT_DESK", null);
    }

    private void stubFindByIdEchoingTheRequestedId(String status, UUID visitId) {
        when(appointmentRepository.findById(eq(TENANT_ID), any())).thenAnswer(invocation -> {
            UUID calledId = invocation.getArgument(1);
            return Optional.of(new AppointmentRow(calledId, PATIENT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                    LocalTime.of(12, 0), status, "NEW", "FRONT_DESK", null, visitId, 0));
        });
    }

    /** For a write that reads the row once before the change and once after (changeStatus, createToken). */
    private void stubFindByIdBeforeThenAfter(String statusBefore, UUID visitIdBefore, String statusAfter,
                                              UUID visitIdAfter) {
        AppointmentRow before = new AppointmentRow(APPT_ID, PATIENT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                LocalTime.of(12, 0), statusBefore, "NEW", "FRONT_DESK", null, visitIdBefore, 0);
        AppointmentRow after = new AppointmentRow(APPT_ID, PATIENT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                LocalTime.of(12, 0), statusAfter, "NEW", "FRONT_DESK", null, visitIdAfter, 1);
        when(appointmentRepository.findById(TENANT_ID, APPT_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
    }

    @Test
    void anOwnerOrFrontDeskBooksAFreeSlot() {
        stubFindByIdEchoingTheRequestedId("BOOKED", null);

        AppointmentView view = service.create(TENANT_ID, USER_ID, "FRONT_DESK",
                createInput(LocalDate.of(2026, 11, 1), "12:00"));

        assertThat(view.status()).isEqualTo("BOOKED");
        assertThat(view.patientName()).isEqualTo("Asha Rao");
        verify(auditLog).record(eq("APPOINTMENT_BOOKED"), eq("APPOINTMENT"), any(), eq(null), any());
    }

    @Test
    void anUnknownPatientIs404() {
        when(patientLookup.find(TENANT_ID, PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(TENANT_ID, USER_ID, "OWNER",
                createInput(LocalDate.of(2026, 11, 1), "12:00"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anInvalidApptTimeIs400() {
        assertThatThrownBy(() -> service.create(TENANT_ID, USER_ID, "OWNER",
                createInput(LocalDate.of(2026, 11, 1), "not-a-time"))).isInstanceOf(InvalidInputException.class);
    }

    /** AC2: a non-staff caller reaching this service directly is refused inside the close-before window. */
    @Test
    void aNonStaffCallerIsRefusedWithinTheCloseBeforeWindow() {
        when(bookingRulesRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, false)));

        // "Now" is 2026-11-01 10:00 IST; 10:30 the same day is inside the 1-hour close-before window.
        assertThatThrownBy(() -> service.create(TENANT_ID, USER_ID, "NURSE",
                createInput(LocalDate.of(2026, 11, 1), "10:30"))).isInstanceOf(InvalidInputException.class);
        verify(appointmentRepository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** AC2: OWNER/FRONT_DESK can always book, even inside the close-before window. */
    @Test
    void staffOverridesTheCloseBeforeWindow() {
        when(bookingRulesRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, false)));
        stubFindByIdEchoingTheRequestedId("BOOKED", null);

        AppointmentView view = service.create(TENANT_ID, USER_ID, "FRONT_DESK",
                createInput(LocalDate.of(2026, 11, 1), "10:30"));

        assertThat(view.status()).isEqualTo("BOOKED");
    }

    @Test
    void overbookOffRefusesASecondBookingInTheSameSlot() {
        when(bookingRulesRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, false)));
        AppointmentRow existing = new AppointmentRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID,
                LocalDate.of(2026, 11, 1), LocalTime.of(12, 0), "BOOKED", "NEW", "FRONT_DESK", null, null, 0);
        when(appointmentRepository.findActiveConflict(TENANT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                LocalTime.of(12, 0))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(TENANT_ID, USER_ID, "OWNER",
                createInput(LocalDate.of(2026, 11, 1), "12:00"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void overbookOnLetsStaffDoubleBookTheSameSlot() {
        when(bookingRulesRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, true)));
        AppointmentRow existing = new AppointmentRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID,
                LocalDate.of(2026, 11, 1), LocalTime.of(12, 0), "BOOKED", "NEW", "FRONT_DESK", null, null, 0);
        when(appointmentRepository.findActiveConflict(TENANT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                LocalTime.of(12, 0))).thenReturn(Optional.of(existing));
        stubFindByIdEchoingTheRequestedId("BOOKED", null);

        AppointmentView view = service.create(TENANT_ID, USER_ID, "OWNER",
                createInput(LocalDate.of(2026, 11, 1), "12:00"));

        assertThat(view.status()).isEqualTo("BOOKED");
    }

    @Test
    void overbookOnStillRefusesANonStaffCaller() {
        when(bookingRulesRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new BookingRulesRow(1, 2, 15, true, false, true)));
        AppointmentRow existing = new AppointmentRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID,
                LocalDate.of(2026, 11, 1), LocalTime.of(12, 0), "BOOKED", "NEW", "FRONT_DESK", null, null, 0);
        when(appointmentRepository.findActiveConflict(TENANT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 1),
                LocalTime.of(12, 0))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(TENANT_ID, USER_ID, "NURSE",
                createInput(LocalDate.of(2026, 11, 1), "12:00"))).isInstanceOf(AccessDeniedException.class);
    }

    // --- changeStatus ---

    @Test
    void aNonStaffRoleCannotCancel() {
        assertThatThrownBy(() -> service.changeStatus(TENANT_ID, USER_ID, "NURSE", APPT_ID, "CANCELLED"))
                .isInstanceOf(AccessDeniedException.class);
        verify(appointmentRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void anInvalidStatusIs400() {
        stubFindByIdEchoingTheRequestedId("BOOKED", null);
        assertThatThrownBy(() -> service.changeStatus(TENANT_ID, USER_ID, "OWNER", APPT_ID, "ARRIVED"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void onlyABookedAppointmentCanBeCancelled() {
        stubFindByIdEchoingTheRequestedId("CANCELLED", null);

        assertThatThrownBy(() -> service.changeStatus(TENANT_ID, USER_ID, "OWNER", APPT_ID, "NOSHOW"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void cancellingABookedAppointmentSucceeds() {
        stubFindByIdBeforeThenAfter("BOOKED", null, "CANCELLED", null);

        AppointmentView view = service.changeStatus(TENANT_ID, USER_ID, "OWNER", APPT_ID, "CANCELLED");

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(auditLog).record(eq("APPOINTMENT_CANCELLED"), eq("APPOINTMENT"), eq(APPT_ID), any(), any());
    }

    @Test
    void anAppointmentFromAnotherTenantIs404() {
        when(appointmentRepository.findById(TENANT_ID, APPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(TENANT_ID, USER_ID, "OWNER", APPT_ID, "CANCELLED"))
                .isInstanceOf(NotFoundException.class);
    }

    // --- createToken ---

    @Test
    void createTokenIssuesAVisitAndMarksTheAppointmentArrived() {
        UUID visitId = UUID.randomUUID();
        stubFindByIdBeforeThenAfter("BOOKED", null, "ARRIVED", visitId);
        when(visitService.issueToken(eq(TENANT_ID), eq(USER_ID), eq("FRONT_DESK"), any()))
                .thenReturn(new VisitView(visitId, 9, PATIENT_ID, "Asha Rao", 30, "F", DOCTOR_ID, "Dr Rao", "NEW",
                        "WITH_DOCTOR", 50000, CLOCK.instant()));
        when(visitRepository.findById(TENANT_ID, visitId))
                .thenReturn(Optional.of(new VisitRow(visitId, PATIENT_ID, DOCTOR_ID, "NEW", "WITH_DOCTOR", 9, 50000,
                        CLOCK.instant(), 0)));

        AppointmentView view = service.createToken(TENANT_ID, USER_ID, "FRONT_DESK", APPT_ID);

        assertThat(view.status()).isEqualTo("ARRIVED");
        assertThat(view.visitId()).isEqualTo(visitId);
        assertThat(view.tokenNo()).isEqualTo(9);
        verify(appointmentRepository).setVisit(APPT_ID, visitId, "ARRIVED", USER_ID);
        verify(auditLog).record(eq("APPOINTMENT_TOKEN_ISSUED"), eq("APPOINTMENT"), eq(APPT_ID), any(), any());
    }

    @Test
    void anAppointmentThatAlreadyHasATokenIs409() {
        stubFindByIdEchoingTheRequestedId("ARRIVED", UUID.randomUUID());

        assertThatThrownBy(() -> service.createToken(TENANT_ID, USER_ID, "OWNER", APPT_ID))
                .isInstanceOf(ConflictException.class);
        verify(visitService, never()).issueToken(any(), any(), any(), any());
    }

    @Test
    void aCancelledAppointmentCannotTakeAToken() {
        stubFindByIdEchoingTheRequestedId("CANCELLED", null);

        assertThatThrownBy(() -> service.createToken(TENANT_ID, USER_ID, "OWNER", APPT_ID))
                .isInstanceOf(ConflictException.class);
    }
}
