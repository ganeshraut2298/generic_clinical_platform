package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.application.TransferRepository.NewTransfer;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-26 moving an admitted patient to another bed, and the clinic's bed transfer rules. Every call first checks
 * the IPD state (Off answers 403 module-off; a write in Read-only answers 409 ipd-readonly). A move takes the
 * clinic's IPD advisory lock (the one every IPD write uses), then locks the two bed rows in id order, then the
 * admission row, so two moves cannot take the same bed and two opposite moves cannot deadlock. The bill days of the
 * old category are decided once, here, and stored with the transfer row. Audit rows carry ids, bed codes,
 * categories, segment days and rule values only: never the patient's name or the reason.
 */
@Service
public class BedTransferService {

    private static final Set<String> TRANSFER_ROLES = AdmissionService.TRANSFER_ROLES;
    private static final Set<String> NEW_RATE_FROM = Set.of("same", "next");
    private static final Set<String> REASONS = Set.of("Needs closer monitoring", "Moving to a step-down bed",
            "Patient or family request", "Isolation");

    private final AdmissionRepository admissions;
    private final BedRepository beds;
    private final WardRepository wards;
    private final IpdSettingsRepository settings;
    private final TransferRepository transfers;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final PatientAccessPort patientAccess;
    private final IpdGate gate;
    private final IpdCatalog catalog;
    private final AdmissionService sheets;

    BedTransferService(AdmissionRepository admissions, BedRepository beds, WardRepository wards,
                       IpdSettingsRepository settings, TransferRepository transfers, OwnerGuard ownerGuard,
                       AuditLog auditLog, PatientAccessPort patientAccess, IpdGate gate, IpdCatalog catalog,
                       AdmissionService sheets) {
        this.admissions = admissions;
        this.beds = beds;
        this.wards = wards;
        this.settings = settings;
        this.transfers = transfers;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.patientAccess = patientAccess;
        this.gate = gate;
        this.catalog = catalog;
        this.sheets = sheets;
    }

    // ---- transfer rules ----

    @Transactional(readOnly = true)
    public TransferRules rules(UUID tenantId) {
        gate.check(tenantId, false);
        return catalog.transferRules(tenantId);
    }

    @Transactional
    public TransferRules updateRules(UUID tenantId, UUID actor, Boolean upgradeNeedsOwner, String newRateFrom) {
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        if (upgradeNeedsOwner == null) {
            throw new InvalidInputException("upgradeNeedsOwner", "Choose who can move a patient to a higher category");
        }
        if (newRateFrom == null || !NEW_RATE_FROM.contains(newRateFrom)) {
            throw new InvalidInputException("newRateFrom", "Choose Same day or Next day");
        }
        wards.lockTenant(tenantId);
        TransferRules current = catalog.transferRules(tenantId);
        TransferRules next = new TransferRules(upgradeNeedsOwner, newRateFrom);
        if (!next.equals(current)) {
            settings.upsertTransferRules(tenantId, next, actor, gate.now());
            auditLog.record("IPD_TRANSFER_RULES_UPDATED", "IPD_CONFIG", null, snapshot(current), snapshot(next));
        }
        return next;
    }

    // ---- the move ----

    @Transactional
    public Detail transfer(UUID tenantId, UUID actor, String role, UUID admissionId, UUID toBedId, String reason) {
        // Defence in depth (security review L1): a move cannot be undone, so the role is re-checked here.
        if (role == null || !TRANSFER_ROLES.contains(role)) {
            throw new AccessDeniedException("You cannot move a patient.");
        }
        ModuleEntitlement ent = gate.check(tenantId, true);
        if (toBedId == null) {
            throw new InvalidInputException("toBedId", "Choose a bed");
        }
        if (reason != null && !REASONS.contains(reason)) {
            throw new InvalidInputException("reason", "Choose a reason from the list");
        }
        // Same lock order as an admission (SAH-23): the clinic IPD lock first, so that a discharge or another move
        // cannot slip in between the checks below and the writes.
        wards.lockTenant(tenantId);
        AdmissionRow found = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        requireAdmitted(found);
        if (toBedId.equals(found.bedId())) {
            throw new InvalidInputException("toBedId", "The patient is already in this bed. Pick another.");
        }

        // Both bed rows, lower id first; then the admission.
        Map<UUID, BedRow> locked = lockBeds(tenantId, found.bedId(), toBedId);
        BedRow from = locked.get(found.bedId());
        BedRow to = locked.get(toBedId);
        if (to == null) {
            throw new NotFoundException("Bed not found");
        }
        if (from == null) {
            throw new IllegalStateException("The bed of the admission was not found");
        }
        AdmissionRow row = admissions.findForUpdate(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        requireAdmitted(row);
        // The bed ids above came from the read before the locks. The clinic lock makes a change in between
        // impossible; if it ever happened, the locked rows are the wrong ones and nothing may be written.
        if (!row.bedId().equals(found.bedId())) {
            throw new IllegalStateException("The admission changed bed while it was being moved");
        }
        if (!"free".equals(to.status())) {
            throw new ConflictException("bed-not-free", "Bed is not free", "That bed is no longer free. Pick another.");
        }

        TransferRules rules = catalog.transferRules(tenantId);
        if (rank(to.categoryKey()) > rank(from.categoryKey()) && rules.upgradeNeedsOwner()) {
            if (!"OWNER".equals(role)) {
                throw new ForbiddenException("owner-approval-needed", "Owner approval needed",
                        "Moving to " + IpdService.NAMES.get(to.categoryKey())
                                + " needs the owner's approval. Ask the clinic owner.");
            }
            // The owner role in the token is not trusted for this: an owner demoted a moment ago is refused.
            ownerGuard.requireOwner(tenantId, actor);
        }
        // The answer is the full admission sheet, so the access is logged like an open (security review L2).
        patientAccess.recordOpened(tenantId, row.patientId(), actor);

        int billDays = RunningBill.billDaysNow(row.admittedAt(), gate.now(), catalog.config(tenantId));
        int earlier = 0;
        for (TransferRow t : transfers.findByAdmission(tenantId, admissionId)) {
            earlier += t.segmentDays();
        }
        int segmentDays = segmentDays(billDays, earlier, rules.newRateFrom());

        UUID transferId = transfers.insert(tenantId, new NewTransfer(admissionId, from.id(), to.id(),
                from.categoryKey(), to.categoryKey(), segmentDays, rules.newRateFrom(), reason, actor));
        admissions.updateBed(tenantId, admissionId, to.id());
        beds.updateStatus(tenantId, from.id(), "clean");
        beds.markOccupied(tenantId, to.id());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("ipNumber", row.ipNumber());
        after.put("fromBedId", from.id());
        after.put("fromBedCode", from.code());
        after.put("toBedId", to.id());
        after.put("toBedCode", to.code());
        after.put("fromCategoryKey", from.categoryKey());
        after.put("toCategoryKey", to.categoryKey());
        after.put("billDays", billDays);
        after.put("segmentDays", segmentDays);
        after.put("newRateFrom", rules.newRateFrom());
        after.put("upgradeNeedsOwner", rules.upgradeNeedsOwner());
        auditLog.record("IPD_BED_TRANSFER", "IPD_BED_TRANSFER", transferId, null, after);

        AdmissionRow updated = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new IllegalStateException("The admission was not found"));
        return sheets.sheet(tenantId, actor, role, ent, updated);
    }

    /**
     * Locks the two bed rows lower id first, so two opposite moves cannot wait for each other. A bed that does not
     * exist is missing from the answer.
     */
    private Map<UUID, BedRow> lockBeds(UUID tenantId, UUID bedA, UUID bedB) {
        UUID low = bedA.compareTo(bedB) <= 0 ? bedA : bedB;
        UUID high = low.equals(bedA) ? bedB : bedA;
        Map<UUID, BedRow> locked = new HashMap<>();
        beds.findForUpdate(tenantId, low).ifPresent(bed -> locked.put(low, bed));
        beds.findForUpdate(tenantId, high).ifPresent(bed -> locked.put(high, bed));
        return locked;
    }

    /**
     * Bill days of the old category, fixed at the moment of the move (SAH-26 AC 4). {@code billDays} is the bill days
     * now (n), {@code earlierSegmentDays} the days already moved to earlier segments (p). Days on the current bed so
     * far are c = max(0, n - p); the segment is c - 1 days with "Same day" (the transfer day is billed at the new
     * category) and c days with "Next day", never below 0.
     */
    static int segmentDays(int billDays, int earlierSegmentDays, String newRateFrom) {
        int onCurrentBed = Math.max(0, billDays - earlierSegmentDays);
        return "next".equals(newRateFrom) ? onCurrentBed : Math.max(0, onCurrentBed - 1);
    }

    /** General is 0, Semi-private 1, Private 2, ICU 3. */
    static int rank(String categoryKey) {
        return IpdService.KEYS.indexOf(categoryKey);
    }

    private static void requireAdmitted(AdmissionRow row) {
        if (!"ADMITTED".equals(row.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted",
                    "This patient is not admitted any more. The patient cannot be moved.");
        }
    }

    private static Map<String, Object> snapshot(TransferRules rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upgradeNeedsOwner", rules.upgradeNeedsOwner());
        m.put("newRateFrom", rules.newRateFrom());
        return m;
    }
}
