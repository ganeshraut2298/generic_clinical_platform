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

import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC10: fee timing, follow-up window 1 to 30, discount limit 0 to 100 per role, UPI QR. */
class FeeSettingsServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();

    private FeeSettingsRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private FeeSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(FeeSettingsRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new FeeSettingsService(repository, ownerGuard, auditLog);
    }

    private static FeeSettingsInput input(int days, Map<String, Integer> limits) {
        return new FeeSettingsInput("after", days, "half", limits, false);
    }

    @Test
    void aClinicWithoutARowSeesTheMockupDefaults() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());

        FeeRow fees = service.get(TENANT_ID);

        assertThat(fees.feeTiming()).isEqualTo("before");
        assertThat(fees.followUpDays()).isEqualTo(7);
        assertThat(fees.discountLimits()).containsEntry("FRONT_DESK", 10).containsEntry("NURSE", 0)
                .containsEntry("OWNER", 100);
        assertThat(fees.upiOnBill()).isTrue();
    }

    @Test
    void ownerSavesAndTheChangeIsAudited() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());

        FeeRow saved = service.save(TENANT_ID, OWNER_USER, input(14, Map.of("FRONT_DESK", 5, "OWNER", 100)));

        assertThat(saved.followUpDays()).isEqualTo(14);
        verify(repository).upsert(eq(TENANT_ID), eq(saved), eq(OWNER_USER));
        verify(auditLog).record(eq("FEE_SETTINGS_UPDATED"), eq("FEE_SETTINGS"), eq(TENANT_ID), any(), any());
    }

    @Test
    void theFollowUpWindowIsFrom1To30Days() {
        for (int days : new int[] {0, 31, -1}) {
            assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(days, Map.of("OWNER", 100))))
                    .isInstanceOf(InvalidInputException.class);
        }
        service.save(TENANT_ID, OWNER_USER, input(1, Map.of("OWNER", 100)));
        service.save(TENANT_ID, OWNER_USER, input(30, Map.of("OWNER", 100)));
    }

    @Test
    void aDiscountLimitIsFrom0To100AndOnlyForKnownRoles() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(7, Map.of("FRONT_DESK", 101))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(7, Map.of("FRONT_DESK", -1))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(7, Map.of("JANITOR", 5))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(7, null)))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void timingAndFollowUpFeeMustBeFromTheLists() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new FeeSettingsInput("never", 7, "free", Map.of(), true))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new FeeSettingsInput("before", 7, "double", Map.of(), true))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new FeeSettingsInput("before", 7, "free", Map.of(), null))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void savingTheSameValuesWritesNothing() {
        FeeRow current = new FeeRow("after", 14, "half", Map.of("OWNER", 100), false);
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(current));

        service.save(TENANT_ID, OWNER_USER, input(14, Map.of("OWNER", 100)));

        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aNonOwnerCannotSave() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input(7, Map.of("OWNER", 100))))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any());
    }
}
