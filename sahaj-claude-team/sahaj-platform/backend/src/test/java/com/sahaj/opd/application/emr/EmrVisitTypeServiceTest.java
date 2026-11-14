package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.emr.EmrInputs.VisitTypeInput;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class EmrVisitTypeServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final com.sahaj.opd.application.VisitService visitService = mock(com.sahaj.opd.application.VisitService.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private EmrVisitTypeService service;

    private static VisitRow visit(UUID doctor, String type) {
        return new VisitRow(VISIT, PATIENT, doctor, type, "WITH_DOCTOR", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"),
                1, 1);
    }

    @BeforeEach
    void setUp() {
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 50000);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30,
                "F", "9000000000", false)));
        // the fee rule is not what this class tests: the fee of the new type is the visit's own, so nothing changes
        when(visitService.feeForType(any(), any(VisitRow.class), any())).thenAnswer(call ->
                ((VisitRow) call.getArgument(1)).feePaise());
        when(visitService.withTriageNames(eq(TENANT), any(VisitView.class))).thenAnswer(call -> call.getArgument(1));
        service = new EmrVisitTypeService(access, visits, patients, team, audit, visitService, records,
                EmrTestData.noBean(), EmrTestData.noBean());
    }

    @Test
    void theDoctorChangesTheTypeOfTheirOwnVisitAndOneAuditEntryHoldsTheOldAndNewType() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(MEMBER, "NEW")), Optional.of(visit(MEMBER, "NEW")),
                Optional.of(visit(MEMBER, "REVISIT")));

        VisitView view = service.change(DOCTOR, VISIT, new VisitTypeInput("REVISIT"));

        assertThat(view.visitType()).isEqualTo("REVISIT");
        verify(visits).updateVisitTypeAndTriage(TENANT, VISIT, "REVISIT", null, null, USER);
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("visitType", "NEW");
        before.put("triageLevel", null);
        before.put("feePaise", view.feePaise());
        Map<String, Object> after = new java.util.LinkedHashMap<>(before);
        after.put("visitType", "REVISIT");
        verify(audit).record("EMR_VISIT_TYPE_CHANGED", "OPD_VISIT", VISIT, before, after);
    }

    @Test
    void theSameTypeWritesNothing() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(MEMBER, "FOLLOW_UP")));

        VisitView view = service.change(DOCTOR, VISIT, new VisitTypeInput("FOLLOW_UP"));

        assertThat(view.visitType()).isEqualTo("FOLLOW_UP");
        verify(visits, never()).updateVisitTypeAndTriage(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void emergencyIsNotAvailableYetAndOtherValuesAreInvalid() {
        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput("EMERGENCY")))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.slug()).isEqualTo("visit-type-not-available");
                    assertThat(ex.getMessage()).isEqualTo("Emergency is available in a later step.");
                });
        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput("WALK_IN")))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput(null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, null)).isInstanceOf(InvalidInputException.class);
        verify(visits, never()).updateVisitTypeAndTriage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDoctorCannotChangeAnotherDoctorsVisitButAnOwnerWithDoctorDetailsCan() {
        UUID other = UUID.randomUUID();
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(other, "NEW")));

        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput("REVISIT")))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.slug()).isEqualTo("not-own-patient");
                });
        verify(visits, never()).updateVisitTypeAndTriage(any(), any(), any(), any(), any(), any());

        ClinicUser owner = new ClinicUser(USER, TENANT, "OWNER");
        TeamMember ownerMember = new TeamMember(UUID.randomUUID(), "Dr Owner", "OWNER", true, 0);
        when(access.requireConsultationWrite(owner)).thenReturn(ownerMember);
        when(team.find(TENANT, other)).thenReturn(Optional.of(new TeamMember(other, "Dr Other", "DOCTOR", true, 0)));
        service.change(owner, VISIT, new VisitTypeInput("REVISIT"));
        verify(visits).updateVisitTypeAndTriage(eq(TENANT), eq(VISIT), eq("REVISIT"), eq(null), eq(null), eq(USER));
    }

    @Test
    void aVisitOfAnotherClinicIs404AndOtherRolesAreRefusedFirst() {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.change(DOCTOR, VISIT, new VisitTypeInput("NEW")))
                .isInstanceOf(NotFoundException.class);

        ClinicUser nurse = new ClinicUser(USER, TENANT, "NURSE");
        when(access.requireConsultationWrite(nurse)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> service.change(nurse, VISIT, new VisitTypeInput("NEW")))
                .isInstanceOf(AccessDeniedException.class);
    }
}
