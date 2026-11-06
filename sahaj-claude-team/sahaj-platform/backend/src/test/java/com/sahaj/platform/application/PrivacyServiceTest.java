package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.PrivacySettingsRepository.PrivacyRow;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-17 AC2, AC5, AC9: defaults until the first save, owner-only save, HFR ID length, upsert and audit. */
class PrivacyServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();

    private PrivacySettingsRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private PrivacyService service;

    @BeforeEach
    void setUp() {
        repository = mock(PrivacySettingsRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new PrivacyService(repository, ownerGuard, auditLog);
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
    }

    @Test
    void withNoSavedRowTheDefaultsAreServedAndNothingIsInserted() {
        PrivacyRow row = service.get(TENANT_ID);

        assertThat(row.consentRequired()).isTrue();
        assertThat(row.promoConsent()).isTrue();
        assertThat(row.recordCopy()).isTrue();
        assertThat(row.hfrId()).isNull();
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void aSavedRowIsServedAsItIs() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(new PrivacyRow(false, true, false, "HFR-1")));

        assertThat(service.get(TENANT_ID)).isEqualTo(new PrivacyRow(false, true, false, "HFR-1"));
    }

    @Test
    void theOwnerSavesWithAnUpsertAndTheChangeIsAuditedWithBeforeAndAfter() {
        PrivacyRow saved = service.save(TENANT_ID, OWNER_USER, false, true, true, " HFR-0042 ");

        assertThat(saved).isEqualTo(new PrivacyRow(false, true, true, "HFR-0042"));
        verify(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);
        verify(repository).upsert(TENANT_ID, saved, OWNER_USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PRIVACY_SETTINGS_CHANGED"), eq("PRIVACY_SETTINGS"), eq(TENANT_ID),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("consentRequired", true).containsEntry("hfrId", null);
        assertThat(after.getValue()).containsEntry("consentRequired", false).containsEntry("hfrId", "HFR-0042");
        assertThat(after.getValue()).containsOnlyKeys("consentRequired", "promoConsent", "recordCopy", "hfrId");
    }

    @Test
    void aBlankHfrIdIsStoredAsNothingAndExactly32CharactersIsAccepted() {
        assertThat(service.save(TENANT_ID, OWNER_USER, true, true, true, "   ").hfrId()).isNull();
        String max = "A".repeat(32);
        assertThat(service.save(TENANT_ID, OWNER_USER, true, true, true, max).hfrId()).isEqualTo(max);
    }

    @Test
    void anHfrIdLongerThan32CharactersIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, true, true, true, "A".repeat(33)))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("hfrId"));
        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingSwitchIsRefused() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, null, true, true, null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        ex -> assertThat(ex.field()).isEqualTo("consentRequired"));
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void aNonOwnerIsRefusedBeforeAnythingIsWritten() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, true, true, true, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }
}
