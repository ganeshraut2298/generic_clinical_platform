package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.SpecialtyServicesPort;
import com.sahaj.platform.api.SpecialtyServicesPort.TemplateService;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import com.sahaj.platform.application.SpecialtyRepository.SpecialtyRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC9: templates show with their phase; applying keeps all data and refuses Phase 2. */
class SpecialtyServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final String DENTAL_JSON = """
            {"schema_version":1,"features":["Tooth chart","Payment per sitting"],
             "services":[{"name":"Extraction","category":"Dental","price_paise":80000},
                         {"name":"IOPA X-ray","category":"Radiology","price_paise":25000}]}
            """;

    private SpecialtyRepository specialtyRepository;
    private ClinicProfileRepository profileRepository;
    private SpecialtyServicesPort servicesPort;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private SpecialtyService service;

    @BeforeEach
    void setUp() {
        specialtyRepository = mock(SpecialtyRepository.class);
        profileRepository = mock(ClinicProfileRepository.class);
        servicesPort = mock(SpecialtyServicesPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new SpecialtyService(specialtyRepository, profileRepository, servicesPort, ownerGuard, auditLog,
                new ObjectMapper());
        when(profileRepository.find(TENANT_ID)).thenReturn(Optional.of(new ProfileRow("Sai", null, null, "Pune",
                null, null, null, null, null, "sai", "gp", "blue", null)));
    }

    @Test
    void listShowsFeaturesAndPhase() {
        when(specialtyRepository.findAll()).thenReturn(List.of(
                new SpecialtyRow("dental", "Dental", 1, DENTAL_JSON),
                new SpecialtyRow("ayur", "Ayurveda", 2,
                        "{\"schema_version\":1,\"features\":[],\"services\":[]}")));

        List<SpecialtyService.SpecialtyView> views = service.list();

        assertThat(views).extracting(SpecialtyService.SpecialtyView::phase).containsExactly(1, 2);
        assertThat(views.get(0).features()).containsExactly("Tooth chart", "Payment per sitting");
    }

    @Test
    void applyingATemplateAddsMissingServicesSetsTheSpecialtyAndIsAudited() {
        when(specialtyRepository.find("dental")).thenReturn(Optional.of(new SpecialtyRow("dental", "Dental", 1, DENTAL_JSON)));
        when(servicesPort.addMissingServices(eq(TENANT_ID), eq(OWNER_USER), any())).thenReturn(1);

        service.apply(TENANT_ID, OWNER_USER, "dental");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TemplateService>> services = ArgumentCaptor.forClass(List.class);
        verify(servicesPort).addMissingServices(eq(TENANT_ID), eq(OWNER_USER), services.capture());
        assertThat(services.getValue()).containsExactly(new TemplateService("Extraction", "Dental", 80000),
                new TemplateService("IOPA X-ray", "Radiology", 25000));
        verify(profileRepository).setSpecialty(TENANT_ID, "dental", OWNER_USER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("SPECIALTY_APPLIED"), eq("CLINIC_PROFILE"), eq(TENANT_ID), anyMap(),
                after.capture());
        assertThat(after.getValue()).containsEntry("specialtyCode", "dental").containsEntry("servicesAdded", 1);
    }

    @Test
    void aPhase2TemplateIsRefusedWith400AndChangesNothing() {
        when(specialtyRepository.find("ayur")).thenReturn(Optional.of(new SpecialtyRow("ayur", "Ayurveda", 2,
                "{\"schema_version\":1,\"features\":[],\"services\":[]}")));

        assertThatThrownBy(() -> service.apply(TENANT_ID, OWNER_USER, "ayur"))
                .isInstanceOf(InvalidInputException.class).hasMessage("That template is not available yet");

        verify(servicesPort, never()).addMissingServices(any(), any(), any());
        verify(profileRepository, never()).setSpecialty(any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownCodeIsRefusedWith400() {
        when(specialtyRepository.find("xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(TENANT_ID, OWNER_USER, "xyz"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.apply(TENANT_ID, OWNER_USER, null))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aNonOwnerCannotApplyATemplate() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("no")).when(ownerGuard)
                .requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.apply(TENANT_ID, OWNER_USER, "dental"))
                .isInstanceOf(AccessDeniedException.class);
        verify(servicesPort, never()).addMissingServices(any(), any(), any());
    }
}
