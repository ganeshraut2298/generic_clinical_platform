package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingProfileRepository.NewProfile;
import com.sahaj.ipd.application.NursingProfileRepository.ProfileRow;
import com.sahaj.ipd.application.NursingProfileRepository.WardProfile;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.Settings;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.ipd.application.NursingSettingsViews.WardProfileInput;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-6 nursing settings and ward profiles (ADR-027 D8): who reads and who writes, Read-only, the clinic lock, the
 * starter copy, the 20-profile cap, unique names, "same values write nothing", and the audit rows (keys and numbers only).
 */
class NursingSettingsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-15T05:30:00Z");

    private IpdSettingsRepository settings;
    private NursingProfileRepository profiles;
    private WardRepository wards;
    private AuditLog auditLog;
    private ModuleEntitlementPort entitlements;
    private OwnerGuard ownerGuard;
    private TeamMemberPort team;
    private NursingSettingsService service;

    @BeforeEach
    void setUp() {
        settings = mock(IpdSettingsRepository.class);
        profiles = mock(NursingProfileRepository.class);
        wards = mock(WardRepository.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        ownerGuard = mock(OwnerGuard.class);
        team = mock(TeamMemberPort.class);
        Drug heparin = new Drug("hep", "Inj. Heparin 5,000 IU", "SC", Set.of("anticoagulant", "penicillin", "sulfa",
                "cephalosporin"), "Anticoagulant", RouteKey.SC, List.of(DoseUnit.IU), false);
        Drug timed = new Drug("ins", "Inj. Insulin", "SC", Set.of(), null, RouteKey.SC, List.of(DoseUnit.UNIT), true);
        MedicineCatalogue catalogue = new MedicineCatalogue(new DrugCatalogue(List.of(heparin, timed), List.of()));
        IpdAudit audit = new IpdAudit(auditLog, new AuditDevice());
        service = new NursingSettingsService(settings, profiles, wards, audit,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)), ownerGuard, team, catalogue);

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(team.findActiveByUserId(TENANT, OWNER)).thenReturn(Optional.of(new TeamMember(UUID.randomUUID(), "Owner",
                "OWNER", true, 0L)));
        when(team.findActiveByUserId(TENANT, NURSE)).thenReturn(Optional.of(new TeamMember(UUID.randomUUID(), "Nurse",
                "NURSE", true, 0L)));
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.empty());
        when(profiles.hasProfiles(TENANT)).thenReturn(false);
        when(profiles.findTemplates()).thenReturn(List.of(new ProfileRow("icu", "ICU", true, 60, null, null, 1),
                new ProfileRow("general-ward", "General ward", true, 240, null, null, 2)));
        when(profiles.countWardsPerProfile(TENANT)).thenReturn(Map.of());
        when(profiles.findWardProfiles(TENANT)).thenReturn(List.of(new WardProfile(WARD, "Ward A", null)));
        when(wards.findWard(TENANT, WARD)).thenReturn(Optional.of(new WardRow(WARD, "Ward A", null)));
    }

    private static SettingsInput validSettings() {
        return new SettingsInput(true, 60, 60, 15, 30, 5, 10);
    }

    private static ProfileInput profileInput(String name) {
        return new ProfileInput(name, true, 60, null, null);
    }

    // ---- read ----

    @Test
    void theOwnerReadsDefaultsTheStarterProfilesAndTheMedicineLists() {
        Settings answer = service.read(TENANT, OWNER, "OWNER");

        assertThat(answer.canEdit()).isTrue();
        assertThat(answer.settings().doseWindowBeforeMinutes()).isEqualTo(60);
        assertThat(answer.settings().undoSeconds()).isEqualTo(10);
        assertThat(answer.profiles()).extracting(p -> p.key() + ":" + p.starter())
                .containsExactly("icu:true", "general-ward:true");
        assertThat(answer.wards()).hasSize(1);
        assertThat(answer.highAlertMedicines()).extracting(k -> k.label())
                .containsExactly("Inj. Heparin 5,000 IU · Anticoagulant");
        assertThat(answer.timeCriticalMedicines()).extracting(k -> k.key()).containsExactly("ins");
        verify(wards, never()).lockTenant(any());
    }

    @Test
    void aNurseReadsButCannotEdit() {
        assertThat(service.read(TENANT, NURSE, "NURSE").canEdit()).isFalse();
    }

    @Test
    void readOnlyIpdReadsWithoutEditing() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));

        assertThat(service.read(TENANT, OWNER, "OWNER").canEdit()).isFalse();
    }

    @Test
    void frontDeskAndBillingAreRefused() {
        assertThatThrownBy(() -> service.read(TENANT, NURSE, "FRONT_DESK")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.read(TENANT, NURSE, "BILLING")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRoleThatIsNoLongerAReaderInTheMembershipIsRefused() {
        UUID demoted = UUID.randomUUID();
        when(team.findActiveByUserId(TENANT, demoted)).thenReturn(Optional.of(new TeamMember(UUID.randomUUID(), "X",
                "BILLING", true, 0L)));

        assertThatThrownBy(() -> service.read(TENANT, demoted, "NURSE")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void moduleOffRefusesTheRead() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.read(TENANT, OWNER, "OWNER")).isInstanceOf(ModuleOffException.class);
    }

    // ---- clinic settings ----

    @Test
    void onlyTheOwnerSavesSettings() {
        assertThatThrownBy(() -> service.saveSettings(TENANT, NURSE, "NURSE", validSettings()))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Only the clinic owner can change settings."));
        verify(settings, never()).upsertNursingSettings(any(), any(), any(), any());
    }

    @Test
    void aDemotedOwnerIsRefusedByTheOwnerGuard() {
        doThrow(new org.springframework.security.access.AccessDeniedException("no")).when(ownerGuard)
                .requireOwner(TENANT, OWNER);

        assertThatThrownBy(() -> service.saveSettings(TENANT, OWNER, "OWNER", validSettings()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(settings, never()).upsertNursingSettings(any(), any(), any(), any());
    }

    @Test
    void readOnlyIpdRefusesWrites() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));

        assertThatThrownBy(() -> service.saveSettings(TENANT, OWNER, "OWNER", validSettings()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        assertThatThrownBy(() -> service.addProfile(TENANT, OWNER, "OWNER", profileInput("Ward X")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput(null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aBadBodyIsRefusedBeforeTheLockAndNothingIsStored() {
        assertThatThrownBy(() -> service.saveSettings(TENANT, OWNER, "OWNER", new SettingsInput(true, 0, 60, 15, 30, 5,
                10))).isInstanceOf(InvalidFieldsException.class);

        verify(wards, never()).lockTenant(any());
        verify(settings, never()).upsertNursingSettings(any(), any(), any(), any());
    }

    @Test
    void savingChangedSettingsTakesTheClinicLockWritesAndAuditsBeforeAndAfter() {
        service.saveSettings(TENANT, OWNER, "OWNER", new SettingsInput(false, 30, 45, 10, 20, 3, 7));

        verify(wards).lockTenant(TENANT);
        verify(settings).upsertNursingSettings(eq(TENANT), eq(new NursingSettings(false, 30, 45, 10, 20, 3, 7)),
                eq(OWNER), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_NURSING_SETTINGS_CHANGED"), eq("IPD_CONFIG"), isNull(), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("doseWindowBeforeMinutes", 60).containsEntry("undoSeconds", 10);
        assertThat(after.getValue()).containsEntry("doseWindowBeforeMinutes", 30).containsEntry("undoSeconds", 7)
                .containsEntry("medVerificationRequired", false).containsKey("device");
    }

    @Test
    void savingTheSameValuesWritesAndAuditsNothing() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.of(new NursingSettings(true, 60, 60, 15, 30, 5,
                10)));

        service.saveSettings(TENANT, OWNER, "OWNER", validSettings());

        verify(settings, never()).upsertNursingSettings(any(), any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    // ---- profiles ----

    @Test
    void theFirstProfileWriteCopiesTheStartersThenAddsTheProfileWithAKeyFromTheName() {
        when(profiles.findProfiles(TENANT)).thenReturn(List.of());

        service.addProfile(TENANT, OWNER, "OWNER", new ProfileInput("Step-down", false, 120, 30, null));

        verify(wards).lockTenant(TENANT);
        verify(profiles).copyTemplates(TENANT);
        ArgumentCaptor<NewProfile> created = ArgumentCaptor.forClass(NewProfile.class);
        verify(profiles).insert(eq(TENANT), created.capture());
        assertThat(created.getValue().key()).isEqualTo("step-down");
        assertThat(created.getValue().name()).isEqualTo("Step-down");
        assertThat(created.getValue().medVerificationRequired()).isFalse();
        assertThat(created.getValue().vitalsEveryMinutes()).isEqualTo(120);
        assertThat(created.getValue().doseWindowBeforeMin()).isEqualTo(30);
        assertThat(created.getValue().doseWindowAfterMin()).isNull();
        verify(auditLog).record(eq("IPD_NURSING_PROFILE_SAVED"), eq("IPD_NURSING_PROFILE"), isNull(), isNull(), any());
    }

    @Test
    void theStartersAreNotCopiedAgain() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfiles(TENANT)).thenReturn(List.of(new ProfileRow("icu", "ICU", true, 60, null, null, 1)));

        service.addProfile(TENANT, OWNER, "OWNER", profileInput("Ward X"));

        verify(profiles, never()).copyTemplates(any());
    }

    @Test
    void aTakenKeyGetsANumber() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfiles(TENANT)).thenReturn(List.of());
        when(profiles.keyTaken(TENANT, "ward-x")).thenReturn(true);

        service.addProfile(TENANT, OWNER, "OWNER", profileInput("Ward X"));

        ArgumentCaptor<NewProfile> created = ArgumentCaptor.forClass(NewProfile.class);
        verify(profiles).insert(eq(TENANT), created.capture());
        assertThat(created.getValue().key()).isEqualTo("ward-x-2");
    }

    @Test
    void moreThanTwentyProfilesAreRefused() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfiles(TENANT)).thenReturn(java.util.Collections.nCopies(20,
                new ProfileRow("icu", "ICU", true, 60, null, null, 1)));

        assertThatThrownBy(() -> service.addProfile(TENANT, OWNER, "OWNER", profileInput("One more")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("profile-limit");
                    assertThat(e.getMessage()).isEqualTo("A clinic can have up to 20 profiles.");
                });
        verify(profiles, never()).insert(any(), any());
    }

    @Test
    void aNameAlreadyUsedIgnoringCaseIsRefused() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfiles(TENANT)).thenReturn(List.of());
        when(profiles.nameTaken(TENANT, "icu", null)).thenReturn(true);

        assertThatThrownBy(() -> service.addProfile(TENANT, OWNER, "OWNER", profileInput("icu")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("profile-name-taken");
                    assertThat(e.getMessage()).isEqualTo("A profile with this name already exists.");
                });
    }

    @Test
    void changingAProfileWritesTheNewValuesAndAuditsBeforeAndAfter() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "icu")).thenReturn(Optional.of(new ProfileRow("icu", "ICU", true, 60, null,
                null, 1)));
        when(profiles.update(eq(TENANT), eq("icu"), any(), eq(OWNER), any())).thenReturn(true);

        service.changeProfile(TENANT, OWNER, "OWNER", "icu", new ProfileInput("ICU", false, 30, null, 20));

        verify(wards).lockTenant(TENANT);
        ArgumentCaptor<NewProfile> next = ArgumentCaptor.forClass(NewProfile.class);
        verify(profiles).update(eq(TENANT), eq("icu"), next.capture(), eq(OWNER), any());
        assertThat(next.getValue().medVerificationRequired()).isFalse();
        assertThat(next.getValue().sortOrder()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_NURSING_PROFILE_SAVED"), eq("IPD_NURSING_PROFILE"), isNull(), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("profileKey", "icu").containsEntry("vitalsEveryMinutes", 60);
        assertThat(after.getValue()).containsEntry("vitalsEveryMinutes", 30).containsEntry("doseWindowAfterMinutes", 20);
    }

    @Test
    void changingAProfileToTheSameValuesWritesNothing() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "icu")).thenReturn(Optional.of(new ProfileRow("icu", "ICU", true, 60, null,
                null, 1)));

        service.changeProfile(TENANT, OWNER, "OWNER", "icu", new ProfileInput("ICU", true, 60, null, null));

        verify(profiles, never()).update(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aRenameAloneAuditsTheOldAndTheNewNameSoBeforeAndAfterDiffer() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "icu")).thenReturn(Optional.of(new ProfileRow("icu", "ICU", true, 60, null,
                null, 1)));
        when(profiles.update(eq(TENANT), eq("icu"), any(), eq(OWNER), any())).thenReturn(true);

        service.changeProfile(TENANT, OWNER, "OWNER", "icu", new ProfileInput("Critical care", true, 60, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_NURSING_PROFILE_SAVED"), eq("IPD_NURSING_PROFILE"), isNull(), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("name", "ICU");
        assertThat(after.getValue()).containsEntry("name", "Critical care");
        assertThat(before.getValue()).isNotEqualTo(after.getValue());
    }

    @Test
    void anUnknownProfileKeyIsA404() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeProfile(TENANT, OWNER, "OWNER", "nope", profileInput("X1")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.changeProfile(TENANT, OWNER, "OWNER", "BAD KEY", profileInput("X1")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renamingToAnotherProfilesNameIsRefused() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "icu")).thenReturn(Optional.of(new ProfileRow("icu", "ICU", true, 60, null,
                null, 1)));
        when(profiles.nameTaken(TENANT, "General ward", "icu")).thenReturn(true);

        assertThatThrownBy(() -> service.changeProfile(TENANT, OWNER, "OWNER", "icu", profileInput("General ward")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("profile-name-taken"));
    }

    // ---- the profile of a ward ----

    @Test
    void choosingAProfileForAWardCopiesTheStartersSetsTheKeyAndAudits() {
        when(profiles.findProfile(TENANT, "icu")).thenReturn(Optional.of(new ProfileRow("icu", "ICU", true, 60, null,
                null, 1)));
        when(profiles.setWardProfile(TENANT, WARD, "icu")).thenReturn(true);

        service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput("icu"));

        verify(wards).lockTenant(TENANT);
        verify(profiles).copyTemplates(TENANT);
        verify(profiles).setWardProfile(TENANT, WARD, "icu");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_WARD_PROFILE_CHANGED"), eq("IPD_WARD"), eq(WARD), any(), after.capture());
        assertThat(after.getValue()).containsEntry("profileKey", "icu").containsEntry("wardId", WARD);
    }

    @Test
    void aProfileThatIsNotTheClinicsIsA400OnProfileKey() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findProfile(TENANT, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput("nope")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("profileKey");
                    assertThat(e.getMessage()).isEqualTo("Choose a profile from the list.");
                });
        assertThatThrownBy(() -> service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput("A B")))
                .isInstanceOf(InvalidInputException.class);
        verify(profiles, never()).setWardProfile(any(), any(), anyString());
    }

    @Test
    void anUnknownWardIsA404() {
        when(wards.findWard(TENANT, WARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput(null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void choosingTheClinicDefaultsClearsTheKeyWithoutCopyingTheStarters() {
        when(profiles.hasProfiles(TENANT)).thenReturn(true);
        when(profiles.findWardProfiles(TENANT)).thenReturn(List.of(new WardProfile(WARD, "Ward A", "icu")));
        when(profiles.setWardProfile(eq(TENANT), eq(WARD), isNull())).thenReturn(true);

        service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput(null));

        verify(profiles).setWardProfile(eq(TENANT), eq(WARD), isNull());
        verify(profiles, never()).copyTemplates(any());
        verify(auditLog).record(eq("IPD_WARD_PROFILE_CHANGED"), anyString(), eq(WARD), any(), any());
    }

    @Test
    void theSameWardProfileWritesNothing() {
        // The ward has no profile and the owner chooses none again.
        service.setWardProfile(TENANT, OWNER, "OWNER", WARD, new WardProfileInput(null));

        verify(profiles, never()).setWardProfile(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }
}
