package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC8: holidays can be added and removed; a repeated date is refused. */
class HolidayServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);

    private HolidayRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private HolidayService service;

    @BeforeEach
    void setUp() {
        repository = mock(HolidayRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new HolidayService(repository, ownerGuard, auditLog);
    }

    @Test
    void theOwnerAddsAHolidayAndItIsAudited() {
        HolidayRow added = service.add(TENANT_ID, OWNER_USER, DAY, " Diwali ", "CLOSED");

        assertThat(added.name()).isEqualTo("Diwali");
        verify(repository).insert(eq(added.id()), eq(DAY), eq("Diwali"), eq("CLOSED"), eq(OWNER_USER));
        verify(auditLog).record(eq("HOLIDAY_ADDED"), eq("CLINIC_HOLIDAY"), eq(added.id()), isNull(), any());
    }

    @Test
    void aRepeatedDateIsAConflictAndNothingIsAudited() {
        doThrow(new DuplicateKeyException("uq_clinic_holiday_tenant_date")).when(repository)
                .insert(any(), eq(DAY), any(), any(), any());

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, DAY, "Diwali", "CLOSED"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("holiday-date-taken");
                    assertThat(ex.getMessage()).isEqualTo("A holiday is already set for that date.");
                });
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aNonOwnerIsRefused() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, DAY, "Diwali", "CLOSED"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.remove(TENANT_ID, OWNER_USER, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).insert(any(), any(), any(), any(), any());
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void theDateNameAndTypeAreChecked() {
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, null, "Diwali", "CLOSED"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, DAY, " ", "CLOSED"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER, DAY, "Diwali", "HALF_DAY"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void removingAHolidayDeletesItAndIsAudited() {
        UUID id = UUID.randomUUID();
        when(repository.findById(TENANT_ID, id)).thenReturn(Optional.of(new HolidayRow(id, DAY, "Diwali", "CLOSED")));
        when(repository.delete(TENANT_ID, id)).thenReturn(1);

        service.remove(TENANT_ID, OWNER_USER, id);

        verify(auditLog).record(eq("HOLIDAY_REMOVED"), eq("CLINIC_HOLIDAY"), eq(id), any(), isNull());
    }

    @Test
    void removingAnotherClinicsHolidayIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(TENANT_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(TENANT_ID, OWNER_USER, id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any(), any());
    }
}
