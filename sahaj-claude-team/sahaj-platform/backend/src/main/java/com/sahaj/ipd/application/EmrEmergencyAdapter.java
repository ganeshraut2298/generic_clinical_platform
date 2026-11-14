package com.sahaj.ipd.application;

import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import com.sahaj.opd.api.EmrEmergencyPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-2 and SAH-67-4 (ADR-025 D9, D11): implements {@link EmrEmergencyPort} so the Doctor EMR of {@code opd} can close an
 * Emergency case with the doctor's outcome, and read the triage scale, without {@code opd} depending on {@code ipd}. It runs
 * in the EMR sign transaction (MANDATORY) and delegates to {@link EmergencyService}, which holds the SAH-28 rules and audits.
 * Nothing here is logged; the case holds personal data that never leaves ipd through this port.
 */
@Component
class EmrEmergencyAdapter implements EmrEmergencyPort {

    private final EmergencyService emergency;

    EmrEmergencyAdapter(EmergencyService emergency) {
        this.emergency = emergency;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CaseLock lockCase(UUID tenantId, UUID caseId) {
        CaseRow row = emergency.lockForEmr(tenantId, caseId);
        return new CaseLock(row.id(), row.status(), row.open(), row.level());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CloseOutcome close(UUID tenantId, UUID actorUserId, UUID caseId, CloseKind kind, UUID patientId,
                              UUID opdVisitId) {
        return new CloseOutcome(emergency.closeForEmr(tenantId, actorUserId, caseId, kind.name(), patientId, opdVisitId));
    }

    @Override
    @Transactional(readOnly = true)
    public TriageScale triageScale(UUID tenantId) {
        EmergencySetup setup = emergency.setupOrDefault(tenantId);
        List<Level> levels = EmergencyRules.levels(setup);
        return new TriageScale(setup.scale(), !emergency.hasEmergencySetup(tenantId),
                levels.stream().map(l -> new TriageLevel(l.level(), l.name(), l.description(), l.tone())).toList());
    }
}
