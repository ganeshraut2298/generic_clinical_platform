package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionService.EmrHeader;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineOrderRepository.PendingRow;
import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import com.sahaj.ipd.application.MedicineViews.DrugOption;
import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.MedicineViews.OrderFinding;
import com.sahaj.ipd.application.MedicineViews.PharmacistCheckRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.ipd.domain.RejectReason;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-31 builds the answers of the medicine endpoints from rows already read: the EMR sheet, the check view, and a
 * page of the pharmacist list (ADR-009 D2, D8, D11). It runs the one {@link MedicineSafetyCheck} for the list rows,
 * so the order form, the list and the verify agree. Staff names are looked up in one batch per answer; the patient
 * data comes from {@link PatientClinicalPort}. Everything it builds is shown on screen only and never logged.
 */
@Component
class MedicineAssembler {

    static final String PATIENT_FALLBACK = "Patient";

    private final MedicineCatalogue catalogue;
    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final AdmissionService admissionService;

    MedicineAssembler(MedicineCatalogue catalogue, MedicineOrderRepository orders, DoseRepository doses,
                      PatientClinicalPort patients, PatientAccessPort patientAccess, ActorDirectoryPort actors,
                      AdmissionService admissionService) {
        this.catalogue = catalogue;
        this.orders = orders;
        this.doses = doses;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.admissionService = admissionService;
    }

    // ---- the EMR sheet ----

    /**
     * The EMR sheet. {@code canOrder} needs the role in the token and the role of the membership both OWNER or DOCTOR,
     * the admission ADMITTED and IPD Trial or On; {@code canStop} the same roles and a live order of an ADMITTED
     * admission (Read-only allowed).
     */
    EmrSheet sheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent, AdmissionRow admission,
                   PatientClinical patient, List<OrderRow> rows, Instant asOf) {
        boolean admitted = "ADMITTED".equals(admission.status());
        String state = ent.ipdState();
        boolean writableState = "trial".equals(state) || "on".equals(state);
        boolean roleCanOrder = MedicineRules.ORDER_ROLES.contains(role)
                && MedicineRules.ORDER_ROLES.contains(memberRole);
        boolean canOrder = roleCanOrder && admitted && writableState;
        String lockReason = null;
        if (!canOrder) {
            lockReason = !roleCanOrder ? "ROLE" : !writableState ? "IPD_READONLY" : "NOT_ADMITTED";
        }
        DrugCatalogue drugs = catalogue.catalogue();
        Set<UUID> staff = new HashSet<>();
        for (OrderRow o : rows) {
            collectStaff(o, staff);
        }
        // SAH-32 (ADR-011 D11): the latest dose of each order, one query for the sheet.
        Map<UUID, DoseRow> lastDoses = new HashMap<>();
        if (!rows.isEmpty()) {
            for (DoseRow d : doses.findLastPerOrder(tenantId, admission.id())) {
                lastDoses.put(d.orderId(), d);
                DoseViews.collectStaff(d, staff);
            }
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);
        List<MedicineOrderRow> views = new ArrayList<>();
        for (OrderRow o : rows) {
            views.add(row(o, names, roleCanOrder && admitted && o.live(), lastDoses.get(o.id())));
        }
        EmrHeader header = admissionService.emrHeader(tenantId, admission);
        List<DrugOption> options = new ArrayList<>();
        for (Drug d : drugs.drugs()) {
            options.add(new DrugOption(d.key(), d.name(), d.route(), d.highAlert()));
        }
        List<KeyLabel> frequencies = new ArrayList<>();
        for (Frequency f : Frequency.values()) {
            frequencies.add(new KeyLabel(f.key(), f.label()));
        }
        List<KeyLabel> reasons = new ArrayList<>();
        for (OverrideReason r : OverrideReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return new EmrSheet(admission.id(), admission.status(), state, asOf, patient(patient, admission.bedCode()),
                header.doctorName(), admission.reason(), header.dayNumber(), List.copyOf(views), canOrder,
                lockReason, List.copyOf(options), List.copyOf(frequencies), List.copyOf(reasons));
    }

    MedicineOrderRow row(OrderRow o, ActorNames names, boolean canStop, DoseRow lastDose) {
        Drug drug = drug(o.drugKey());
        Frequency frequency = frequency(o);
        KeyLabel override = overrideLabel(o);
        KeyLabel reject = o.rejectReasonKey() == null ? null : RejectReason.ofKey(o.rejectReasonKey())
                .map(r -> new KeyLabel(r.key(), r.label()))
                .orElseThrow(() -> new IllegalStateException("An order has an unknown reject reason"));
        return new MedicineOrderRow(o.id(), drug.key(), drug.name(), drug.route(), drug.highAlert(),
                frequency.key(), frequency.label(), o.status(), names.of(o.orderedBy()), o.orderedAt(), override,
                o.verifiedBy() == null ? null : names.of(o.verifiedBy()), o.verifiedAt(), reject,
                o.rejectedBy() == null ? null : names.of(o.rejectedBy()), o.rejectedAt(),
                o.stoppedBy() == null ? null : names.of(o.stoppedBy()), o.stoppedAt(), canStop,
                lastDose == null ? null : DoseViews.of(lastDose, names));
    }

    /** The frequency of a stored order; an unknown key is a code fault. */
    private static Frequency frequency(OrderRow o) {
        return Frequency.ofKey(o.frequencyKey())
                .orElseThrow(() -> new IllegalStateException("An order has an unknown frequency"));
    }

    /** The reason given when the doctor ordered past warnings, or null when there was none. */
    private static KeyLabel overrideLabel(OrderRow o) {
        return o.overrideReasonKey() == null ? null : OverrideReason.ofKey(o.overrideReasonKey())
                .map(r -> new KeyLabel(r.key(), r.label()))
                .orElseThrow(() -> new IllegalStateException("An order has an unknown override reason"));
    }

    private static void collectStaff(OrderRow o, Set<UUID> staff) {
        staff.add(o.orderedBy());
        for (UUID id : new UUID[] {o.verifiedBy(), o.rejectedBy(), o.stoppedBy()}) {
            if (id != null) {
                staff.add(id);
            }
        }
    }

    // ---- the identity bar and the check ----

    /** The identity bar. The chip is computed from the allergy text by the one rule class. */
    EmrPatient patient(PatientClinical patient, String bedCode) {
        AllergyStatus status = MedicineSafetyCheck.allergyStatus(patient.allergyText());
        return new EmrPatient(patient.id(), patient.name(), patient.uhid(), patient.age(), patient.sex(), bedCode,
                patient.allergyText(), status.name());
    }

    static OrderCheckView view(OrderCheck check) {
        List<OrderFinding> hardStops = new ArrayList<>();
        for (Finding f : check.hardStops()) {
            hardStops.add(finding(f));
        }
        List<OrderFinding> warnings = new ArrayList<>();
        for (Finding f : check.warnings()) {
            warnings.add(finding(f));
        }
        return new OrderCheckView(check.allergyStatus().name(), List.copyOf(hardStops), List.copyOf(warnings),
                check.checkToken());
    }

    private static OrderFinding finding(Finding f) {
        return new OrderFinding(f.code().name(), f.severity().name(), f.otherDrugKey(), f.text());
    }

    Drug drug(String key) {
        return catalogue.catalogue().find(key)
                .orElseThrow(() -> new IllegalStateException("An order names a medicine not in the catalogue"));
    }

    MedicineSafetyCheck safety() {
        return catalogue.safety();
    }

    /** The drug keys of the live orders in chart order, without {@code excludeId} (null excludes nothing). */
    static List<String> liveKeys(List<OrderRow> rows, UUID excludeId) {
        List<String> keys = new ArrayList<>();
        for (OrderRow o : rows) {
            if (o.live() && !o.id().equals(excludeId)) {
                keys.add(o.drugKey());
            }
        }
        return keys;
    }

    // ---- the pharmacist list ----

    /**
     * One page of the panel "Pharmacist check": the waiting orders of ADMITTED admissions, oldest first, each with the
     * safety check run now (the order itself excluded). The caller has already checked the roles (both PHARMACIST or
     * OWNER). {@code waiting} is the full count (a count query, not the rows). Because the page shows patient names,
     * UHIDs and allergy texts, it writes one record-access row per distinct patient on the page, in the caller's
     * transaction, which therefore must not be read-only (security review S3). Besides those rows, the query count
     * does not depend on the number of rows: one for the page, one for the count, one for the live orders, one for the
     * patients, one for the staff names.
     */
    PharmacistChecks queue(UUID tenantId, UUID actor, ModuleEntitlement ent, int limit, Position after) {
        Instant asOf = orders.databaseNow();
        List<PendingRow> found = orders.findPending(tenantId, after, limit + 1);
        boolean more = found.size() > limit;
        List<PendingRow> page = more ? found.subList(0, limit) : found;
        int waiting = orders.countWaiting(tenantId);

        Set<UUID> patientIds = new LinkedHashSet<>();
        Set<UUID> admissionIds = new LinkedHashSet<>();
        Set<UUID> staff = new HashSet<>();
        staff.add(actor);
        for (PendingRow row : page) {
            patientIds.add(row.patientId());
            admissionIds.add(row.order().admissionId());
            staff.add(row.order().orderedBy());
        }
        Map<UUID, PatientClinical> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
        // Security review S3: the list shows names, UHIDs and allergies, so it is a record access, once per patient.
        for (UUID patientId : patientIds) {
            if (patientMap.containsKey(patientId)) {
                patientAccess.recordOpened(tenantId, patientId, actor);
            }
        }
        Map<UUID, List<OrderRow>> liveByAdmission = new LinkedHashMap<>();
        for (OrderRow live : orders.findLiveByAdmissions(tenantId, admissionIds)) {
            liveByAdmission.computeIfAbsent(live.admissionId(), k -> new ArrayList<>()).add(live);
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);
        String state = ent.ipdState();
        boolean writable = "trial".equals(state) || "on".equals(state);

        List<PharmacistCheckRow> items = new ArrayList<>();
        for (PendingRow row : page) {
            OrderRow o = row.order();
            PatientClinical patient = patientMap.get(row.patientId());
            String allergyText = patient == null ? null : patient.allergyText();
            Drug drug = drug(o.drugKey());
            OrderCheck check = safety().check(drug, allergyText,
                    liveKeys(liveByAdmission.getOrDefault(o.admissionId(), List.of()), o.id()));
            boolean self = o.orderedBy().equals(actor);
            String lock = check.blocked() ? "HARD_STOP" : self ? "SELF" : !writable ? "IPD_READONLY" : null;
            Frequency frequency = frequency(o);
            KeyLabel override = overrideLabel(o);
            items.add(new PharmacistCheckRow(o.id(), o.admissionId(), drug.key(), drug.name(), drug.highAlert(),
                    frequency.key(), frequency.label(), patient == null ? unknownPatient(row) : patient(patient, row.bedCode()),
                    names.of(o.orderedBy()), o.orderedAt(), override, view(check), lock == null, lock, true));
        }
        List<KeyLabel> rejectReasons = new ArrayList<>();
        for (RejectReason r : RejectReason.values()) {
            rejectReasons.add(new KeyLabel(r.key(), r.label()));
        }
        String next = more && !page.isEmpty() ? MedicineRules.encodeCursor(page.get(page.size() - 1).position()) : null;
        return new PharmacistChecks(state, asOf, waiting, names.of(actor), List.copyOf(rejectReasons),
                List.copyOf(items), next);
    }

    /** A patient the record cannot be found for: the row stays (a list never drops a waiting order). */
    private static EmrPatient unknownPatient(PendingRow row) {
        return new EmrPatient(row.patientId(), PATIENT_FALLBACK, "", null, null, row.bedCode(), null,
                AllergyStatus.NOT_RECORDED.name());
    }
}
