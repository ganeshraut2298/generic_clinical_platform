package com.sahaj.opd.application.emr;

import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrEmergencyPort.TriageScale;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.VisitView.FeeChange;
import com.sahaj.opd.application.emr.EmrInputs.VisitTypeInput;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-52-2 and SAH-67-4 (ADR-020 D10, ADR-025 D11, D14): the doctor changes the OPD visit type from the EMR. NEW, FOLLOW_UP,
 * REVISIT and EMERGENCY are accepted; EMERGENCY needs a level of the clinic's triage scale (SAH-28 scale, or its default).
 * A DOCTOR or VISITING_DOCTOR may change only their own visit; an OWNER with doctor details any visit. The same type and
 * level writes nothing. While the visit's bill has no payment the fee is worked out again by the SAH-9 rule (ADR-025 H11);
 * with a payment the type changes and the fee does not (feeChange ALREADY_BILLED). A visit whose EMR record is signed refuses
 * the change (409 emr-record-signed). One audit entry records the old and the new type, level and fee (no patient data).
 */
@Service
public class EmrVisitTypeService {

    static final Set<String> TYPES = Set.of("NEW", "FOLLOW_UP", "REVISIT", "EMERGENCY");

    private final EmrAccess access;
    private final VisitRepository visits;
    private final PatientLookupPort patients;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;
    private final VisitService visitService;
    private final EmrRecordRepository records;
    private final ObjectProvider<EmrVisitChargePort> charges;
    private final ObjectProvider<EmrEmergencyPort> emergency;

    public EmrVisitTypeService(EmrAccess access, VisitRepository visits, PatientLookupPort patients,
                               TeamMemberPort teamMembers, AuditLog auditLog, VisitService visitService,
                               EmrRecordRepository records, ObjectProvider<EmrVisitChargePort> charges,
                               ObjectProvider<EmrEmergencyPort> emergency) {
        this.access = access;
        this.visits = visits;
        this.patients = patients;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
        this.visitService = visitService;
        this.records = records;
        this.charges = charges;
        this.emergency = emergency;
    }

    /** GET /emr/triage-levels: the clinic's scale or the SAH-28 default (most urgent first). */
    @Transactional(readOnly = true)
    public TriageScale triageLevels(ClinicUser user) {
        access.requireConsultationWrite(user);
        EmrEmergencyPort port = emergency.getIfAvailable();
        if (port == null) {
            throw EmrProblemException.visitTypeNotAvailable();
        }
        return port.triageScale(user.tenantId());
    }

    @Transactional
    public VisitView change(ClinicUser user, UUID visitId, VisitTypeInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        String type = input == null ? null : input.visitType();
        if (type == null || !TYPES.contains(type)) {
            throw new InvalidInputException("visitType", "Choose New, Follow-up, Revisit or Emergency.");
        }
        Integer level = input.triageLevel();
        UUID tenantId = user.tenantId();
        TriageScale scale = null;
        if ("EMERGENCY".equals(type)) {
            EmrEmergencyPort port = emergency.getIfAvailable();
            if (port == null) {
                throw EmrProblemException.visitTypeNotAvailable();
            }
            scale = port.triageScale(tenantId);
            int chosen = level == null ? 0 : level;
            if (level == null || scale.levels().stream().noneMatch(l -> l.level() == chosen)) {
                throw new InvalidInputException("triageLevel", "Choose a triage level");
            }
        } else if (level != null) {
            throw new InvalidInputException("triageLevel", "A triage level is only for the Emergency type.");
        }
        VisitRow first = visits.findById(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        if (!"OWNER".equals(member.role()) && !member.membershipId().equals(first.doctorMembershipId())) {
            throw EmrProblemException.notOwnPatient();
        }
        // ADR-025 D1: the visit row is locked before the payment check and the fee write (L5), then read again, so a sign or
        // a payment that committed first is seen
        visits.lockById(tenantId, visitId);
        VisitRow visit = visits.findById(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        if (records.findOpd(tenantId, visitId).filter(r -> "SIGNED".equals(r.status())).isPresent()) {
            throw EmrProblemException.emrRecordSigned();
        }
        VisitRow current = visit;
        FeeChange feeChange = null;
        boolean sameType = type.equals(visit.visitType()) && Objects.equals(level, visit.triageLevel());
        if (!sameType) {
            long before = visit.feePaise();
            long after = before;
            String outcome = "UNCHANGED";
            EmrVisitChargePort charge = charges.getIfAvailable();
            if (charge != null && charge.hasPayment(tenantId, visitId)) {
                outcome = "ALREADY_BILLED";
            } else {
                after = visitService.feeForType(tenantId, visit, type);
                if (after != before) {
                    visits.updateFee(tenantId, visitId, after, user.userId());
                    outcome = "UPDATED";
                }
            }
            feeChange = new FeeChange(outcome, before, after);
            visits.updateVisitTypeAndTriage(tenantId, visitId, type, "EMERGENCY".equals(type) ? level : null,
                    scale == null ? null : scale.scale(), user.userId());
            Map<String, Object> beforeValues = new LinkedHashMap<>();
            beforeValues.put("visitType", visit.visitType());
            beforeValues.put("triageLevel", visit.triageLevel());
            beforeValues.put("feePaise", before);
            Map<String, Object> afterValues = new LinkedHashMap<>();
            afterValues.put("visitType", type);
            afterValues.put("triageLevel", "EMERGENCY".equals(type) ? level : null);
            afterValues.put("feePaise", after);
            auditLog.record("EMR_VISIT_TYPE_CHANGED", "OPD_VISIT", visitId, beforeValues, afterValues);
            current = visits.findById(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        }
        PatientSummary patient = patients.find(tenantId, current.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = teamMembers.find(tenantId, current.doctorMembershipId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        VisitView view = new VisitView(current.id(), current.tokenNo(), patient.id(), patient.name(), patient.age(),
                patient.sex(), doctor.membershipId(), doctor.name(), current.visitType(), current.stage(),
                current.feePaise(), current.createdAt(), current.workflowVersionNo(), current.emrRecordId(),
                current.emrStatus() == null ? "NONE" : current.emrStatus(), current.triageLevel(), null, null,
                feeChange);
        return visitService.withTriageNames(tenantId, view);
    }
}
