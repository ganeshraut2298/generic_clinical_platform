package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientClinicalUpdatePort;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-68-3 (ADR-025 D13): writes the allergy and long-term conditions texts from the Doctor EMR's sign. The patient row is
 * locked first; when its texts no longer match the fingerprint the visit read at open, nothing is written and the answer is
 * CONFLICT (the sign still completes, in opd). The texts are never logged or audited: the audit holds field names only.
 */
@Service
public class PatientClinicalUpdateService implements PatientClinicalUpdatePort {

    static final int MAX_TEXT = 400;
    private static final String ENTITY = "PATIENT";

    private final PatientRepository patients;
    private final AuditLog auditLog;

    public PatientClinicalUpdateService(PatientRepository patients, AuditLog auditLog) {
        this.patients = patients;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FactsOutcome updateFacts(UUID tenantId, UUID actorUserId, UUID patientId, String expectedFingerprint,
                                    FactsChange change) {
        if (change == null || (change.allergyText() == null && change.chronicText() == null)) {
            return FactsOutcome.NOT_CHANGED;
        }
        requireLength(change.allergyText());
        requireLength(change.chronicText());
        Optional<PatientRow> found = patients.findByIdForUpdate(tenantId, patientId);
        if (found.isEmpty() || found.get().mergedIntoId() != null) {
            return FactsOutcome.CONFLICT;
        }
        PatientRow row = found.get();
        if (!Objects.equals(expectedFingerprint, PatientClinicalUpdatePort.fingerprint(row.allergy(), row.chronic()))) {
            return FactsOutcome.CONFLICT;
        }
        boolean allergyChanged = change.allergyText() != null && !change.allergyText().equals(nullToEmpty(row.allergy()));
        boolean chronicChanged = change.chronicText() != null && !change.chronicText().equals(nullToEmpty(row.chronic()));
        if (!allergyChanged && !chronicChanged) {
            return FactsOutcome.NOT_CHANGED;
        }
        patients.updateClinicalFacts(patientId, allergyChanged ? change.allergyText() : null,
                chronicChanged ? change.chronicText() : null, actorUserId);
        List<String> fields = new ArrayList<>();
        if (allergyChanged) {
            fields.add("allergy");
        }
        if (chronicChanged) {
            fields.add("chronic");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fields", fields);
        auditLog.record("PATIENT_CLINICAL_FACTS_UPDATED", ENTITY, patientId, null, after);
        return FactsOutcome.UPDATED;
    }

    private static void requireLength(String text) {
        if (text != null && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("A patient record text is at most 400 characters");
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
