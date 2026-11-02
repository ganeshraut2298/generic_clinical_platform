package com.sahaj.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.SpecialtyServicesPort;
import com.sahaj.platform.api.SpecialtyServicesPort.TemplateService;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import com.sahaj.platform.application.SpecialtyRepository.SpecialtyRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC9: the specialty templates (global, read-only) and applying one. Applying keeps all data:
 * the template's starter services are added only when the clinic has no service of that name, and
 * nothing is deleted or overwritten. A Phase 2 template is shown but refused (400). The service
 * list belongs to {@code opd}, so it is reached through {@link SpecialtyServicesPort}, in the same
 * transaction as the specialty change.
 */
@Service
public class SpecialtyService {

    private static final String ENTITY_TYPE = "CLINIC_PROFILE";

    private final SpecialtyRepository specialtyRepository;
    private final ClinicProfileRepository profileRepository;
    private final SpecialtyServicesPort servicesPort;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public SpecialtyService(SpecialtyRepository specialtyRepository, ClinicProfileRepository profileRepository,
                             SpecialtyServicesPort servicesPort, OwnerGuard ownerGuard, AuditLog auditLog,
                             ObjectMapper objectMapper) {
        this.specialtyRepository = specialtyRepository;
        this.profileRepository = profileRepository;
        this.servicesPort = servicesPort;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    /** One card on Specialty template. */
    public record SpecialtyView(String code, String name, List<String> features, int phase) {
    }

    @Transactional(readOnly = true)
    public List<SpecialtyView> list() {
        return specialtyRepository.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public ProfileRow apply(UUID tenantId, UUID actorUserId, String code) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        SpecialtyRow specialty = code == null ? null : specialtyRepository.find(code.trim()).orElse(null);
        if (specialty == null) {
            throw new InvalidInputException("code", "Choose a template from the list");
        }
        if (specialty.phase() != 1) {
            throw new InvalidInputException("code", "That template is not available yet");
        }
        ProfileRow before = profileRepository.find(tenantId)
                .orElseThrow(() -> new NotFoundException("Clinic profile not found"));

        int added = servicesPort.addMissingServices(tenantId, actorUserId, templateServices(specialty));
        profileRepository.setSpecialty(tenantId, specialty.code(), actorUserId);

        Map<String, Object> beforeValues = new LinkedHashMap<>();
        beforeValues.put("specialtyCode", before.specialtyCode());
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("specialtyCode", specialty.code());
        afterValues.put("servicesAdded", added);
        auditLog.record("SPECIALTY_APPLIED", ENTITY_TYPE, tenantId, beforeValues, afterValues);

        return profileRepository.find(tenantId).orElseThrow(() -> new NotFoundException("Clinic profile not found"));
    }

    private SpecialtyView toView(SpecialtyRow row) {
        List<String> features = new ArrayList<>();
        for (JsonNode feature : parse(row).path("features")) {
            features.add(feature.asText());
        }
        return new SpecialtyView(row.code(), row.name(), features, row.phase());
    }

    private List<TemplateService> templateServices(SpecialtyRow row) {
        List<TemplateService> services = new ArrayList<>();
        for (JsonNode service : parse(row).path("services")) {
            services.add(new TemplateService(service.path("name").asText(), service.path("category").asText(),
                    service.path("price_paise").asLong()));
        }
        return services;
    }

    private JsonNode parse(SpecialtyRow row) {
        try {
            return objectMapper.readTree(row.templateJson());
        } catch (Exception ex) {
            throw new IllegalStateException("Specialty template " + row.code() + " is not valid JSON", ex);
        }
    }
}
