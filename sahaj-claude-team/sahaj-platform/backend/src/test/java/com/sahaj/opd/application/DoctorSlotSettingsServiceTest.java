package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.DoctorSlotSettingsInput.SessionInput;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-11 AC1, AC5: per-doctor slot settings, lazy defaults, owner/front-desk only. */
class DoctorSlotSettingsServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();

    private DoctorSlotSettingsRepository repository;
    private TeamMemberPort teamMemberPort;
    private AuditLog auditLog;
    private DoctorSlotSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(DoctorSlotSettingsRepository.class);
        teamMemberPort = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        service = new DoctorSlotSettingsService(repository, teamMemberPort, auditLog);
    }

    @Test
    void aDoctorWithNoSavedSettingsGetsTheMockupDefaults() {
        when(teamMemberPort.findActiveDoctors(TENANT_ID))
                .thenReturn(List.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        when(repository.find(TENANT_ID, DOCTOR_ID)).thenReturn(Optional.empty());

        List<DoctorSlotSettingsView> all = service.listAll(TENANT_ID);

        assertThat(all).hasSize(1);
        DoctorSlotSettingsView view = all.get(0);
        assertThat(view.slotMinutes()).isEqualTo(15);
        assertThat(view.workingDays()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(view.walkInEveryNth()).isZero();
        assertThat(view.maxOnlinePerDay()).isZero();
        assertThat(view.sessions()).containsExactly(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0)));
    }

    private static SessionInput session(String start, String end) {
        return new SessionInput(start, end);
    }

    private static DoctorSlotSettingsInput validInput() {
        return new DoctorSlotSettingsInput(DOCTOR_ID, 15, List.of(1, 2, 3, 4, 5), 4, 24,
                List.of(session("09:00", "13:00")));
    }

    @Test
    void aNonOwnerNonFrontDeskRoleCannotSave() {
        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "NURSE", DOCTOR_ID, validInput()))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any(), any());
    }

    @Test
    void frontDeskMaySaveSlotSettings() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        when(repository.find(TENANT_ID, DOCTOR_ID)).thenReturn(Optional.empty());

        DoctorSlotSettingsView saved = service.save(TENANT_ID, USER_ID, "FRONT_DESK", DOCTOR_ID, validInput());

        assertThat(saved.slotMinutes()).isEqualTo(15);
        assertThat(saved.walkInEveryNth()).isEqualTo(4);
        verify(repository).upsert(eq(TENANT_ID), eq(DOCTOR_ID), any(), eq(USER_ID));
        verify(auditLog).record(eq("DOCTOR_SLOT_SETTINGS_UPDATED"), eq("DOCTOR_SLOT_SETTINGS"), eq(DOCTOR_ID), any(),
                any());
    }

    @Test
    void anUnknownDoctorIs404() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "OWNER", DOCTOR_ID, validInput()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBodyDoctorIdThatDoesNotMatchThePathIs400() {
        DoctorSlotSettingsInput mismatched = new DoctorSlotSettingsInput(UUID.randomUUID(), 15, List.of(1), 0, 0,
                List.of(session("09:00", "13:00")));

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "OWNER", DOCTOR_ID, mismatched))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anEndTimeNotAfterStartTimeIs400() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        DoctorSlotSettingsInput input = new DoctorSlotSettingsInput(DOCTOR_ID, 15, List.of(1), 0, 0,
                List.of(session("13:00", "09:00")));

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "OWNER", DOCTOR_ID, input))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void moreThanTwoSessionsIs400() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        DoctorSlotSettingsInput input = new DoctorSlotSettingsInput(DOCTOR_ID, 15, List.of(1), 0, 0,
                List.of(session("09:00", "11:00"), session("12:00", "14:00"), session("15:00", "17:00")));

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "OWNER", DOCTOR_ID, input))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aSlotLengthOutsideFiveToSixtyIs400() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        DoctorSlotSettingsInput input = new DoctorSlotSettingsInput(DOCTOR_ID, 90, List.of(1), 0, 0,
                List.of(session("09:00", "13:00")));

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, "OWNER", DOCTOR_ID, input))
                .isInstanceOf(InvalidInputException.class);
    }
}
