package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-32 the tab "Medicines (MAR)" of the Nursing sheet (ADR-011 D2, D3, D10, D12). OWNER, NURSE and DOCTOR read it
 * (DOCTOR only reads); the token role and the role read again from the active membership must both be in that set.
 * Allowed in Read-only state and for a discharged admission. Takes no lock. The answer shows the patient's name, UHID,
 * allergy text and every medicine, so opening it writes one record-access row, like the Nursing sheet (D10).
 */
@Service
public class MarService {

    private final AdmissionRepository admissions;
    private final MedicineOrderRepository orders;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final TeamMemberPort team;
    private final IpdGate gate;
    private final MarAssembler marAssembler;

    MarService(AdmissionRepository admissions, MedicineOrderRepository orders, PatientClinicalPort patients,
               PatientAccessPort patientAccess, TeamMemberPort team, IpdGate gate,
               MarAssembler marAssembler) {
        this.admissions = admissions;
        this.orders = orders;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.team = team;
        this.gate = gate;
        this.marAssembler = marAssembler;
    }

    /**
     * Check order of the contract: token role (403), module Off (403), then in one READ COMMITTED read-write
     * transaction the role read again from the membership (403), the admission (404), its patient (404); then one
     * record-access row. A refused call writes nothing. {@code asOf} is the database clock.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Mar mar(UUID tenantId, UUID actor, String role, UUID admissionId) {
        MedicineOrderService.requireRole(role, DoseRules.MAR_ROLES, DoseRules.MAR_HIDDEN);
        ModuleEntitlement ent = gate.check(tenantId, false);
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> DoseRules.MAR_ROLES.contains(m.role()))
                .orElseThrow(() -> MedicineOrderService.forbidden(DoseRules.MAR_HIDDEN));
        AdmissionRow admission = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientClinical patient = patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        return marAssembler.mar(tenantId, member.membershipId(), role, member.role(), ent, admission, patient,
                orders.databaseNow());
    }
}
