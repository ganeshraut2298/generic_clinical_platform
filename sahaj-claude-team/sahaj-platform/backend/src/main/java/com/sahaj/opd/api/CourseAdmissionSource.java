package com.sahaj.opd.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * SAH-58 (ADR-022 D1, D12): how the treatment course view of {@code opd} shows the status, IP number and bill total of
 * the admissions linked to its day-care and IPD sittings, without a module cycle ({@code ipd} depends on this package,
 * so {@code opd} must not depend on {@code ipd.api}). Declared here, implemented by {@code ipd}. It runs in the
 * caller's transaction (the clinic is already bound), reads money and numbers only and writes nothing. Another clinic's
 * admission is simply absent from the answer.
 */
public interface CourseAdmissionSource {

    /**
     * {@code status} is ADMITTED or DISCHARGED; {@code billTotalPaise} the running bill, or the final bill once discharged;
     * {@code bedLabel} the code of the bed the patient is in (or was in), null when unknown. Never logged.
     */
    record CourseAdmission(UUID admissionId, String ipNumber, String status, long billTotalPaise, String bedLabel) {

        /** Without a bed label (0.7.0 adds it, SAH-75-11 O2-2: the oncology order sheet line "Day-care bed <n>"). */
        public CourseAdmission(UUID admissionId, String ipNumber, String status, long billTotalPaise) {
            this(admissionId, ipNumber, status, billTotalPaise, null);
        }
    }

    /** The facts of these admissions, keyed by admission id. */
    Map<UUID, CourseAdmission> admissions(UUID tenantId, Collection<UUID> admissionIds);
}
