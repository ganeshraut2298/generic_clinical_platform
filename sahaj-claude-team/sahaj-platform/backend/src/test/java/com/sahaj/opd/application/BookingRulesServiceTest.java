package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-11 AC2, AC5: the clinic's booking rules, lazy defaults, owner-only save. */
class BookingRulesServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private BookingRulesRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private BookingRulesService service;

    @BeforeEach
    void setUp() {
        repository = mock(BookingRulesRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new BookingRulesService(repository, ownerGuard, auditLog);
    }

    @Test
    void aClinicWithNoSavedRulesSeesTheMockupDefaults() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());

        BookingRulesRow defaults = service.get(TENANT_ID);

        assertThat(defaults.closeBeforeHours()).isEqualTo(1);
        assertThat(defaults.noShowAfterMinutes()).isEqualTo(15);
        assertThat(defaults.followPriority()).isTrue();
        assertThat(defaults.overbook()).isFalse();
    }

    @Test
    void savingChecksOwnershipAgainstTheDatabase() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, USER_ID);
        BookingRulesInput input = new BookingRulesInput(2, 3, 20, true, false, true);

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, input)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void anOwnerSavesValidRules() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
        BookingRulesInput input = new BookingRulesInput(2, 3, 20, false, true, true);

        BookingRulesRow saved = service.save(TENANT_ID, USER_ID, input);

        assertThat(saved.closeBeforeHours()).isEqualTo(2);
        assertThat(saved.overbook()).isTrue();
        verify(repository).upsert(eq(TENANT_ID), eq(saved), eq(USER_ID));
        verify(auditLog).record(eq("BOOKING_RULES_UPDATED"), eq("BOOKING_RULES"), eq(TENANT_ID), any(), any());
    }

    @Test
    void aCloseBeforeHoursOutOfRangeIs400() {
        BookingRulesInput input = new BookingRulesInput(60, 3, 20, true, false, false);

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, input)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aMissingBooleanIs400() {
        BookingRulesInput input = new BookingRulesInput(1, 2, 15, null, false, false);

        assertThatThrownBy(() -> service.save(TENANT_ID, USER_ID, input)).isInstanceOf(InvalidInputException.class);
    }
}
