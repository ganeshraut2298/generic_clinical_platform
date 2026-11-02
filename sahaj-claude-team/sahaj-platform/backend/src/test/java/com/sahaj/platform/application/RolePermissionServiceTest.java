package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.application.RolePermissionRepository.Cell;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-6 AC13: the owner column is locked on; only changed cells are saved. */
class RolePermissionServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();

    private RolePermissionRepository repository;
    private TeamRepository teamRepository;
    private AuditLog auditLog;
    private RolePermissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RolePermissionRepository.class);
        teamRepository = mock(TeamRepository.class);
        auditLog = mock(AuditLog.class);
        service = new RolePermissionService(repository, teamRepository, auditLog);
        when(teamRepository.findByUser(TENANT_ID, OWNER_USER)).thenReturn(Optional.of(new TeamRow(
                UUID.randomUUID(), OWNER_USER, "Asha", "9876547410", MembershipRole.OWNER, MembershipStatus.ACTIVE,
                null, false, null, null, null, 0L)));
        when(repository.effective()).thenReturn(List.of(
                new Cell(MembershipRole.OWNER, "refund", true),
                new Cell(MembershipRole.NURSE, "token", true),
                new Cell(MembershipRole.NURSE, "refund", false)));
    }

    @Test
    void onlyTheCellsThatDifferAreSavedAndAudited() {
        service.save(TENANT_ID, OWNER_USER, List.of(
                new Cell(MembershipRole.OWNER, "refund", true),
                new Cell(MembershipRole.NURSE, "token", true),
                new Cell(MembershipRole.NURSE, "refund", true)));

        verify(repository).upsert(MembershipRole.NURSE, "refund", true, OWNER_USER);
        verify(repository, never()).upsert(eq(MembershipRole.NURSE), eq("token"), anyBoolean(), any());
        verify(auditLog).record(eq("ROLE_PERMISSIONS_UPDATED"), eq("ROLE_PERMISSION"), eq(null), anyMap(), anyMap());
    }

    @Test
    void savingTheMatrixAsItIsWritesNothing() {
        List<Cell> same = repository.effective();

        service.save(TENANT_ID, OWNER_USER, same);

        verify(repository, never()).upsert(any(), any(), anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void switchingTheOwnerOffIsRefusedBeforeTheDatabaseSeesIt() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                List.of(new Cell(MembershipRole.OWNER, "refund", false))))
                .isInstanceOf(TeamInputInvalidException.class);
        verify(repository, never()).upsert(any(), any(), anyBoolean(), any());
    }

    @Test
    void aNonOwnerCannotSave() {
        UUID nurse = UUID.randomUUID();
        when(teamRepository.findByUser(TENANT_ID, nurse)).thenReturn(Optional.of(new TeamRow(
                UUID.randomUUID(), nurse, "Nisha", "9123456780", MembershipRole.NURSE, MembershipStatus.ACTIVE,
                null, false, null, null, null, 0L)));

        assertThatThrownBy(() -> service.save(TENANT_ID, nurse, List.of()))
                .isInstanceOf(AccessRefusedException.class);
        assertThat(service.get()).hasSize(3);
    }
}
