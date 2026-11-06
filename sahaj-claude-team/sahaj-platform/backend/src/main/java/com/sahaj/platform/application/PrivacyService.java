package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.PrivacySettingsRepository.PrivacyRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-17 Privacy settings (AC2, AC5, AC9). Any clinic user reads; only the owner saves. The row is
 * lazy: until the first save the defaults are served (all switches on, no HFR ID) and nothing is
 * inserted. Every save is audited with the before and after values - three switches and the HFR ID,
 * none of which is patient data.
 */
@Service
public class PrivacyService {

    static final int MAX_HFR_ID = 32;
    static final PrivacyRow DEFAULTS = new PrivacyRow(true, true, true, null);
    private static final String ENTITY_TYPE = "PRIVACY_SETTINGS";

    private final PrivacySettingsRepository repository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public PrivacyService(PrivacySettingsRepository repository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.repository = repository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public PrivacyRow get(UUID tenantId) {
        return repository.find(tenantId).orElse(DEFAULTS);
    }

    @Transactional
    public PrivacyRow save(UUID tenantId, UUID actorUserId, Boolean consentRequired, Boolean promoConsent,
                            Boolean recordCopy, String hfrId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        PrivacyRow before = repository.find(tenantId).orElse(DEFAULTS);
        PrivacyRow after = new PrivacyRow(required(consentRequired, "consentRequired"),
                required(promoConsent, "promoConsent"), required(recordCopy, "recordCopy"), cleanHfrId(hfrId));
        repository.upsert(tenantId, after, actorUserId);
        auditLog.record("PRIVACY_SETTINGS_CHANGED", ENTITY_TYPE, tenantId, snapshot(before), snapshot(after));
        return after;
    }

    private static boolean required(Boolean value, String field) {
        if (value == null) {
            throw new InvalidInputException(field, "Choose yes or no");
        }
        return value;
    }

    private static String cleanHfrId(String hfrId) {
        if (hfrId == null) {
            return null;
        }
        String trimmed = hfrId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_HFR_ID) {
            throw new InvalidInputException("hfrId", "The HFR ID can be up to 32 characters");
        }
        return trimmed;
    }

    private static Map<String, Object> snapshot(PrivacyRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("consentRequired", row.consentRequired());
        values.put("promoConsent", row.promoConsent());
        values.put("recordCopy", row.recordCopy());
        values.put("hfrId", row.hfrId());
        return values;
    }
}
