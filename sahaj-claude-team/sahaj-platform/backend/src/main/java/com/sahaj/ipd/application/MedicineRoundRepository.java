package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * SAH-35 the two set-based reads of the Medicine round (ADR-012 D1, docs/database/SAH-35.md Q1 and Q2). Every method is
 * scoped to the tenant and reads only: nothing is written. Two statements per answer whatever the number of patients.
 */
public interface MedicineRoundRepository {

    /**
     * Q1: every ACTIVE order of every ADMITTED admission of the clinic, with the admission's patient and the current
     * bed code. Unordered.
     */
    List<ActiveOrder> findActiveOfAdmitted(UUID tenantId);

    /**
     * Q2: the dose records of these admissions with {@code from <= slot_at < to} (today, IST). The admission ids are
     * the ones Q1 returned; the tenant is still part of the statement so the tenant index prefix is used.
     */
    List<TodayDose> findDosesBetween(UUID tenantId, Collection<UUID> admissionIds, Instant from, Instant to);

    /** An ACTIVE order of an ADMITTED admission. No name of a person. */
    record ActiveOrder(UUID orderId, UUID admissionId, UUID patientId, String bedCode, String drugKey,
                       String frequencyKey, Instant verifiedAt) {

        @Override
        public String toString() {
            return "ActiveOrder[orderId=" + orderId + "]";
        }
    }

    /** A dose record of today: order, slot instant and outcome only. */
    record TodayDose(UUID orderId, Instant slotAt, String outcome) {
    }
}
