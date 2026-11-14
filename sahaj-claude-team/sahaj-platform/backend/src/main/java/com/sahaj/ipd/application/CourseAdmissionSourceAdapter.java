package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DischargeRepository.FinalBillRow;
import com.sahaj.opd.api.CourseAdmissionSource;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-58 (ADR-022 D12): implements {@link CourseAdmissionSource} so the treatment course of {@code opd} can show the
 * IP number, the status and the bill total of the admissions linked to its day-care and IPD sittings ("Billed in IPD").
 * The total is the running bill while the patient is admitted and the frozen final bill once discharged, the same
 * numbers the admission sheet shows. It reads an IP number, a status and money only, so it applies no ward scope (the
 * course is read by the owner, doctors, front desk and billing, not by clinical IPD staff) and writes nothing.
 */
@Component
class CourseAdmissionSourceAdapter implements CourseAdmissionSource {

    private final AdmissionRepository admissions;
    private final DischargeRepository discharges;
    private final IpdBilling billing;
    private final IpdCatalog catalog;
    private final IpdGate gate;

    CourseAdmissionSourceAdapter(AdmissionRepository admissions, DischargeRepository discharges, IpdBilling billing,
                                 IpdCatalog catalog, IpdGate gate) {
        this.admissions = admissions;
        this.discharges = discharges;
        this.billing = billing;
        this.catalog = catalog;
        this.gate = gate;
    }

    @Override
    public Map<UUID, CourseAdmission> admissions(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, CourseAdmission> result = new LinkedHashMap<>();
        for (UUID id : admissionIds) {
            admissions.find(tenantId, id).ifPresent(row -> result.put(id, facts(tenantId, row)));
        }
        return result;
    }

    private CourseAdmission facts(UUID tenantId, AdmissionRow row) {
        boolean discharged = !"ADMITTED".equals(row.status());
        Optional<FinalBillRow> frozen = discharged ? discharges.findFinalBill(tenantId, row.id()) : Optional.empty();
        long total;
        if (frozen.isPresent()) {
            total = frozen.get().totalPaise();
        } else {
            Instant now = discharged && row.dischargedAt() != null ? row.dischargedAt() : gate.now();
            total = billing.forOne(tenantId, row.id(), now, catalog.depositRules(tenantId).alertPercent())
                    .bill(row, "").totalPaise();
        }
        return new CourseAdmission(row.id(), row.ipNumber(), discharged ? "DISCHARGED" : "ADMITTED", total,
                row.bedCode());
    }
}
