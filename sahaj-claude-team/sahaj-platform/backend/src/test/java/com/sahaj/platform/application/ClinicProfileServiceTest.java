package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 Clinic profile: owner-only change, field rules, and the tenant row kept in step (AC1, AC4, AC7). */
class ClinicProfileServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();

    private ClinicProfileRepository profileRepository;
    private TenantRepository tenantRepository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ClinicProfileService service;

    private final ProfileRow saved = new ProfileRow("Sai Family Clinic", "श्री साई क्लिनिक", "12 MG Road", "Pune",
            "411001", "9876543210", null, null, "MH-123", "sai-clinic", "gp", "blue", null);

    @BeforeEach
    void setUp() {
        profileRepository = mock(ClinicProfileRepository.class);
        tenantRepository = mock(TenantRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new ClinicProfileService(profileRepository, tenantRepository, ownerGuard, auditLog);
        when(profileRepository.find(TENANT_ID)).thenReturn(Optional.of(saved));
    }

    private static ClinicProfileInput input(String name, String mapsUrl, String brand, String brandHex) {
        return new ClinicProfileInput(name, "श्री साई क्लिनिक", "12 MG Road", "Pune", "Pune", "411001", "9876543210",
                null, mapsUrl, "MH-123", brand, brandHex);
    }

    @Test
    void anyoneCanReadTheProfile() {
        assertThat(service.get(TENANT_ID)).isEqualTo(saved);
        verify(ownerGuard, never()).requireOwner(any(), any());
    }

    @Test
    void aNonOwnerIsRefusedAndNothingIsWritten() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("New name", null, "blue", null)))
                .isInstanceOf(AccessDeniedException.class);

        verify(profileRepository, never()).update(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void renamingTheClinicUpdatesTheProfileAndTheTenantRowAndIsAudited() {
        service.save(TENANT_ID, OWNER_USER, input("  Sai Care Clinic ", " https://maps.app.goo.gl/abc ", "teal", null));

        ArgumentCaptor<ProfileRow> row = ArgumentCaptor.forClass(ProfileRow.class);
        verify(profileRepository).update(eq(TENANT_ID), row.capture(), eq(OWNER_USER));
        assertThat(row.getValue().name()).isEqualTo("Sai Care Clinic");
        assertThat(row.getValue().mapsUrl()).isEqualTo("https://maps.app.goo.gl/abc");
        assertThat(row.getValue().specialtyCode()).isEqualTo("gp");
        assertThat(row.getValue().slug()).isEqualTo("sai-clinic");
        verify(tenantRepository).updateNameAndBrand(TENANT_ID, "Sai Care Clinic", "teal");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("CLINIC_PROFILE_UPDATED"), eq("CLINIC_PROFILE"), eq(TENANT_ID), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("name", "Sai Family Clinic");
        assertThat(after.getValue()).containsEntry("name", "Sai Care Clinic");
    }

    @Test
    void savingTheSameValuesChangesNothingAndWritesNoAuditRow() {
        service.save(TENANT_ID, OWNER_USER, input("Sai Family Clinic", null, "blue", null));

        verify(profileRepository, never()).update(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), anyMap());
    }

    @Test
    void theNameIsRequiredAndAtMost160Characters() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("   ", null, "blue", null)))
                .isInstanceOf(InvalidInputException.class).hasMessage("Enter the clinic name");
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("x".repeat(161), null, "blue", null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void theMapsLinkMustBeAWebLink() {
        for (String bad : new String[] {"javascript:alert(1)", "ftp://maps.example/x", "maps.google.com/abc",
                "https://", "data:text/html,hi"}) {
            assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("Sai Family Clinic", bad, "blue", null)))
                    .as(bad).isInstanceOf(InvalidInputException.class)
                    .hasMessage("Paste a link that starts with https://");
        }
        verify(profileRepository, never()).update(any(), any(), any());
    }

    @Test
    void aCustomColourNeedsAHexValueAndAPresetDropsIt() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("Sai Family Clinic", null, "custom", "blue")))
                .isInstanceOf(InvalidInputException.class);

        service.save(TENANT_ID, OWNER_USER, input("Sai Family Clinic", null, "custom", "#1F5BC8"));
        ArgumentCaptor<ProfileRow> custom = ArgumentCaptor.forClass(ProfileRow.class);
        verify(profileRepository).update(eq(TENANT_ID), custom.capture(), eq(OWNER_USER));
        assertThat(custom.getValue().brand()).isEqualTo("custom");
        assertThat(custom.getValue().brandHex()).isEqualTo("#1f5bc8");
        verify(tenantRepository).updateNameAndBrand(TENANT_ID, "Sai Family Clinic", "custom");
    }

    @Test
    void anUnknownColourIsRefused() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER, input("Sai Family Clinic", null, "pink", null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aClinicWithoutAProfileIsNotFound() {
        when(profileRepository.find(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT_ID)).isInstanceOf(NotFoundException.class);
    }
}
