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
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.ModuleEntitlementRepository.EntitlementRow;
import com.sahaj.platform.domain.TenantProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-19 AC1, AC2, AC4, AC7, AC8: lazy defaults, plan, menu counts, request, trial, HRA, audit. */
class ModulesServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private ModuleEntitlementRepository repository;
    private TenantRepository tenantRepository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ModulesService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModuleEntitlementRepository.class);
        tenantRepository = mock(TenantRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = newService(true);
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
        when(tenantRepository.findProfile(TENANT_ID))
                .thenReturn(Optional.of(new TenantProfile("Clinic", "teal", "FREE", null)));
    }

    private ModulesService newService(boolean trialSwitch) {
        return new ModulesService(repository, tenantRepository, ownerGuard, auditLog,
                Clock.fixed(NOW, ZoneOffset.UTC), trialSwitch);
    }

    private static EntitlementRow row(String state, Instant trialEnd, boolean hra, Instant requested) {
        return new EntitlementRow(state, trialEnd, hra, 25, requested);
    }

    @Test
    void withNoRowTheDefaultsAreServedAndNothingIsWritten() {
        ModulesView view = service.get(TENANT_ID, "OWNER");

        assertThat(view.plan()).isEqualTo("FREE");
        assertThat(view.ipd().state()).isEqualTo("off");
        assertThat(view.ipd().requested()).isFalse();
        assertThat(view.hraOn()).isFalse();
        assertThat(view.ipdTrialAvailable()).isTrue();
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void anyPlanOtherThanFreeIsPro() {
        when(tenantRepository.findProfile(TENANT_ID))
                .thenReturn(Optional.of(new TenantProfile("Clinic", "teal", "PROFESSIONAL", null)));

        assertThat(service.get(TENANT_ID, "OWNER").plan()).isEqualTo("PRO");
    }

    @Test
    void menuCountsFollowTheSignedInRole() {
        ModulesView owner = service.get(TENANT_ID, "OWNER");
        ModulesView nurse = service.get(TENANT_ID, "NURSE");

        assertThat(owner.menuCounts().advanced()).isEqualTo(9);
        assertThat(owner.menuCounts().simple()).isEqualTo(6);
        assertThat(nurse.menuCounts().advanced()).isEqualTo(3);
        assertThat(nurse.menuCounts().simple()).isEqualTo(3);
    }

    @Test
    void anExpiredTrialReadsAsReadonlyAndAnActiveTrialAsTrial() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("trial", NOW.minusSeconds(1), false, null)));
        ModulesView expired = service.get(TENANT_ID, "OWNER");
        assertThat(expired.ipd().state()).isEqualTo("readonly");
        assertThat(expired.ipdTrialAvailable()).isFalse();

        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("trial", NOW.plusSeconds(60), false, null)));
        assertThat(service.get(TENANT_ID, "OWNER").ipd().state()).isEqualTo("trial");
    }

    @Test
    void requestingIpdStoresTheTimeAndAuditsOnlyEntitlementFields() {
        ModulesView view = service.requestIpd(TENANT_ID, OWNER_USER, "OWNER");

        assertThat(view.ipd().requested()).isTrue();
        assertThat(view.ipd().requestedAt()).isEqualTo(NOW);
        verify(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);
        verify(repository).upsert(TENANT_ID, row("off", null, false, NOW), OWNER_USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("MODULE_IPD_REQUESTED"), eq("MODULE_ENTITLEMENT"), eq(TENANT_ID),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("ipdRequestedAt", null);
        assertThat(after.getValue()).containsEntry("ipdRequestedAt", NOW.toString());
        assertThat(after.getValue())
                .containsOnlyKeys("ipdState", "ipdTrialEndsAt", "hraOn", "bedLimit", "ipdRequestedAt");
    }

    @Test
    void aSecondRequestKeepsTheFirstTimeAndWritesNothing() {
        Instant first = NOW.minus(Duration.ofDays(2));
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("off", null, false, first)));

        ModulesView view = service.requestIpd(TENANT_ID, OWNER_USER, "OWNER");

        assertThat(view.ipd().requestedAt()).isEqualTo(first);
        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void requestingIpdWhenNotOffIs409() {
        for (String state : new String[] {"trial", "on", "readonly"}) {
            when(repository.find(TENANT_ID))
                    .thenReturn(Optional.of(row(state, NOW.plusSeconds(60), false, null)));
            assertThatThrownBy(() -> service.requestIpd(TENANT_ID, OWNER_USER, "OWNER"))
                    .isInstanceOf(ConflictException.class);
        }
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void theTrialRunsThirtyDaysAndIsAudited() {
        ModulesView view = service.startIpdTrial(TENANT_ID, OWNER_USER, "OWNER");

        assertThat(view.ipd().state()).isEqualTo("trial");
        assertThat(view.ipd().trialEndsAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(view.ipdTrialAvailable()).isFalse();
        verify(repository).upsert(TENANT_ID, row("trial", NOW.plus(Duration.ofDays(30)), false, null), OWNER_USER);
        verify(auditLog).record(eq("MODULE_IPD_TRIAL_STARTED"), eq("MODULE_ENTITLEMENT"), eq(TENANT_ID), any(), any());
    }

    @Test
    void theTrialKeepsAnEarlierRequestTime() {
        Instant first = NOW.minus(Duration.ofDays(1));
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("off", null, true, first)));

        ModulesView view = service.startIpdTrial(TENANT_ID, OWNER_USER, "OWNER");

        assertThat(view.ipd().requestedAt()).isEqualTo(first);
        assertThat(view.hraOn()).isTrue();
    }

    @Test
    void theTrialWhenIpdIsNotOffIs409() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("on", null, false, null)));

        assertThatThrownBy(() -> service.startIpdTrial(TENANT_ID, OWNER_USER, "OWNER"))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void withTheTemporarySwitchOffTheTrialIs404AndNotAvailable() {
        service = newService(false);

        assertThatThrownBy(() -> service.startIpdTrial(TENANT_ID, OWNER_USER, "OWNER"))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.get(TENANT_ID, "OWNER").ipdTrialAvailable()).isFalse();
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void switchingHraToItsCurrentValueWritesAndAuditsNothing() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("off", null, true, null)));

        ModulesView view = service.setHra(TENANT_ID, OWNER_USER, "OWNER", true);

        assertThat(view.hraOn()).isTrue();
        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void menuCountsIncludeTheIpdEntryForIpdRolesWhenIpdIsNotOff() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("on", null, false, null)));

        ModulesView owner = service.get(TENANT_ID, "OWNER");
        ModulesView nurse = service.get(TENANT_ID, "NURSE");
        ModulesView billing = service.get(TENANT_ID, "BILLING");

        assertThat(owner.menuCounts().advanced()).isEqualTo(11);
        assertThat(owner.menuCounts().simple()).isEqualTo(7);
        assertThat(nurse.menuCounts().advanced()).isEqualTo(5);
        assertThat(nurse.menuCounts().simple()).isEqualTo(4);
        assertThat(billing.menuCounts().advanced()).isEqualTo(2);

        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("on", null, true, null)));
        assertThat(service.get(TENANT_ID, "OWNER").menuCounts().advanced()).isEqualTo(12);
    }

    @Test
    void menuCountsHaveNoEmergencyEntryWhenIpdIsReadonly() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("readonly", null, false, null)));

        assertThat(service.get(TENANT_ID, "OWNER").menuCounts().advanced()).isEqualTo(10);
        assertThat(service.get(TENANT_ID, "NURSE").menuCounts().advanced()).isEqualTo(4);
    }

    @Test
    void menuCountsIncludeTheStaffEntryWhenHraIsOn() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(row("off", null, true, null)));

        ModulesView owner = service.get(TENANT_ID, "OWNER");

        assertThat(owner.menuCounts().advanced()).isEqualTo(10);
        assertThat(owner.menuCounts().simple()).isEqualTo(7);
    }

    @Test
    void everyWriteLocksTheClinicsEntitlementRowFirst() {
        service.setHra(TENANT_ID, OWNER_USER, "OWNER", true);
        service.requestIpd(TENANT_ID, OWNER_USER, "OWNER");
        service.startIpdTrial(TENANT_ID, OWNER_USER, "OWNER");

        verify(repository, org.mockito.Mockito.times(3)).lockForWrite(eq(TENANT_ID), any(), eq(OWNER_USER));
    }

    @Test
    void hraIsSwitchedOnAndAuditedWithBeforeAndAfter() {
        ModulesView view = service.setHra(TENANT_ID, OWNER_USER, "OWNER", true);

        assertThat(view.hraOn()).isTrue();
        verify(repository).upsert(TENANT_ID, row("off", null, true, null), OWNER_USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("MODULE_HRA_CHANGED"), eq("MODULE_ENTITLEMENT"), eq(TENANT_ID),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("hraOn", false);
        assertThat(after.getValue()).containsEntry("hraOn", true);
    }

    @Test
    void aMissingHraValueIsRefused() {
        assertThatThrownBy(() -> service.setHra(TENANT_ID, OWNER_USER, "OWNER", null))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("on"));
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void aNonOwnerIsRefusedBeforeAnythingIsWritten() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.setHra(TENANT_ID, OWNER_USER, "DOCTOR", true))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.requestIpd(TENANT_ID, OWNER_USER, "DOCTOR"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.startIpdTrial(TENANT_ID, OWNER_USER, "DOCTOR"))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }
}
