package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DoseRepository.CorrectionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.DoseRepository.NewCorrection;
import com.sahaj.ipd.application.DoseViews.Viewer;
import com.sahaj.ipd.application.MarViews.CorrectionInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.DoseCorrectionRules;
import com.sahaj.ipd.domain.DoseCorrectionRules.Refusal;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.InErrorReason;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-5 undo and "recorded in error" of a given dose (ADR-027 D3, D13). One READ COMMITTED transaction each: the
 * role, the dose by id (404), the bare admission row locked ({@code for no key update}, the only lock), the dose read
 * again, the scope guard on the locked admission, the not-admitted check, the replay of the client key, then the rules of
 * {@link DoseCorrectionRules}. The history stays append-only: a correction row is added, nothing is changed or deleted.
 * A review-after-dose item of the dose is closed with DOSE_UNDONE in the same transaction. Notes, names and the PIN never
 * reach a log, an event or an audit row; those hold ids and fixed keys.
 */
@Service
public class DoseCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(DoseCorrectionService.class);

    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientClinicalPort patients;
    private final TeamMemberPort team;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final MedicineAssembler medicines;
    private final MarAssembler marAssembler;
    private final ScopeGuard guard;
    private final PharmacistReviews reviews;

    DoseCorrectionService(MedicineOrderRepository orders, DoseRepository doses, AdmissionRepository admissions,
                          ClinicalEventRepository events, PatientClinicalPort patients, TeamMemberPort team,
                          IpdAudit audit, IpdGate gate, MedicineAssembler medicines, MarAssembler marAssembler,
                          ScopeGuard guard, PharmacistReviews reviews) {
        this.orders = orders;
        this.doses = doses;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.team = team;
        this.audit = audit;
        this.gate = gate;
        this.medicines = medicines;
        this.marAssembler = marAssembler;
        this.guard = guard;
        this.reviews = reviews;
    }

    /** Undo within the window, by the nurse who recorded the dose (200, the corrected dose and the MAR now). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DoseRecorded undo(UUID tenantId, UUID actor, String role, UUID doseId, CorrectionInput input) {
        return correct(tenantId, actor, role, doseId, input, DoseRepository.KIND_UNDO);
    }

    /** Recorded in error, on the day of the slot, by the recorder, any nurse in scope, or the owner. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DoseRecorded recordedInError(UUID tenantId, UUID actor, String role, UUID doseId, CorrectionInput input) {
        return correct(tenantId, actor, role, doseId, input, DoseRepository.KIND_IN_ERROR);
    }

    private DoseRecorded correct(UUID tenantId, UUID actor, String role, UUID doseId, CorrectionInput input,
                                 String kind) {
        boolean undo = DoseRepository.KIND_UNDO.equals(kind);
        String refusal = undo ? DoseRules.ONLY_GIVERS : DoseRules.ONLY_CORRECTORS;
        MedicineOrderService.requireRole(role, DoseRules.GIVE_ROLES, refusal);
        ModuleEntitlement ent = gate.check(tenantId, true);
        UUID clientKey = DoseRules.correctionKey(input);
        InErrorReason reason = undo ? null : DoseRules.inErrorReason(input);
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> DoseRules.GIVE_ROLES.contains(m.role()))
                .orElseThrow(() -> MedicineOrderService.forbidden(refusal));

        DoseRow first = doses.findById(tenantId, doseId).orElseThrow(() -> new NotFoundException("Dose not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Dose not found"));
        DoseRow dose = doses.findById(tenantId, doseId).orElseThrow(() -> new NotFoundException("Dose not found"));
        PatientClinical patient = patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        // SAH-34: after the lock, on locked data and the database clock.
        guard.requireAdmissionLocked(tenantId, actor, role, member.role(), admission.id(), "dose-correct");
        if (!"ADMITTED".equals(admission.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted", DoseRules.NOT_ADMITTED);
        }
        Instant now = orders.databaseNow();

        CorrectionRow earlier = doses.findCorrectionByClientKey(tenantId, clientKey).orElse(null);
        if (earlier != null) {
            return replay(tenantId, actor, role, member, ent, admission, patient, dose, earlier, kind, reason);
        }
        boolean corrected = dose.correction() != null;
        Refusal refused = undo
                ? DoseCorrectionRules.undo(dose.given(), corrected, dose.recordedBy(), actor, dose.undoUntil(), now)
                : DoseCorrectionRules.inError(dose.given(), corrected, dose.slotAt(), now);
        if (refused != null) {
            throw refusal(refused, undo);
        }
        CorrectionRow row;
        try {
            Instant at = doses.insertCorrection(tenantId, new NewCorrection(dose.id(), kind,
                    reason == null ? null : reason.key(), actor, clientKey));
            row = new CorrectionRow(dose.id(), kind, reason == null ? null : reason.key(), actor, at, clientKey);
        } catch (DataAccessException e) {
            log.error("Dose correction refused tenantId={} doseId={} sqlState={}", tenantId, dose.id(), sqlState(e));
            throw new IllegalStateException("The dose could not be corrected", e);
        }
        // The review item of a STAT dose before the pharmacist check closes with DOSE_UNDONE for both kinds (ADR-027 D6).
        reviews.closeForDose(tenantId, dose.id(), ReviewOutcome.DOSE_UNDONE, actor);

        OrderRow order = orders.findById(tenantId, dose.orderId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        Drug drug = medicines.drug(order.drugKey());
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_GIVE,
                undo ? DoseRules.undoneText(drug) : DoseRules.inErrorText(drug, reason), actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("doseId", dose.id());
        after.put("orderId", dose.orderId());
        after.put("admissionId", admission.id());
        after.put("drugKey", drug.key());
        after.put("slotAt", dose.slotAt().toString());
        after.put("orderVersion", dose.orderVersion());
        if (!undo) {
            after.put("reasonKey", reason.key());
        }
        audit.record(undo ? "IPD_DOSE_UNDONE" : "IPD_DOSE_RECORDED_IN_ERROR", MedicineAdministration.ENTITY_DOSE,
                dose.id(), null, after);
        return answer(tenantId, actor, role, member, ent, admission, patient, dose.corrected(row), false);
    }

    /**
     * A repeat of a client key: the same dose, kind, caller and reason answers 200 with nothing written; anything else
     * is a 409 client-key-reused.
     */
    private DoseRecorded replay(UUID tenantId, UUID actor, String role, TeamMember member, ModuleEntitlement ent,
                                AdmissionRow admission, PatientClinical patient, DoseRow dose, CorrectionRow earlier,
                                String kind, InErrorReason reason) {
        boolean same = earlier.doseId().equals(dose.id()) && earlier.kind().equals(kind)
                && earlier.correctedBy().equals(actor)
                && Objects.equals(earlier.reasonKey(), reason == null ? null : reason.key());
        if (!same) {
            throw new ConflictException("client-key-reused", "Client key already used", DoseRules.KEY_REUSED);
        }
        return answer(tenantId, actor, role, member, ent, admission, patient, dose.corrected(earlier), true);
    }

    private DoseRecorded answer(UUID tenantId, UUID actor, String role, TeamMember member, ModuleEntitlement ent,
                                AdmissionRow admission, PatientClinical patient, DoseRow dose, boolean replayed) {
        // The clock is read again after the writes, so asOf is never before the correction time.
        Instant now = orders.databaseNow();
        Viewer viewer = marAssembler.viewer(actor, role, member.role(), ent, "ADMITTED".equals(admission.status()), now);
        return new DoseRecorded(replayed, marAssembler.view(tenantId, dose, viewer),
                marAssembler.mar(tenantId, actor, member.membershipId(), role, member.role(), ent, admission, patient,
                        now));
    }

    private static RuntimeException refusal(Refusal refused, boolean undo) {
        switch (refused) {
            case NOT_RECORDER:
                return MedicineOrderService.forbidden(DoseRules.ONLY_RECORDER);
            case UNDO_EXPIRED:
                return new ConflictException("undo-expired", "Time to undo has passed", DoseRules.UNDO_EXPIRED);
            case ERROR_WINDOW_CLOSED:
                return new ConflictException("error-window-closed", "Too late to change this dose",
                        DoseRules.ERROR_WINDOW_CLOSED);
            default:
                return new ConflictException("dose-state", "Dose state", undo ? DoseRules.CANNOT_UNDO
                        : DoseRules.CANNOT_MARK_ERROR);
        }
    }

    private static String sqlState(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof java.sql.SQLException sql ? sql.getSQLState() : "-";
    }
}
