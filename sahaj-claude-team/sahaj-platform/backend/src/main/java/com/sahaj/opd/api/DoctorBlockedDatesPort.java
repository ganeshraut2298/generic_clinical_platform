package com.sahaj.opd.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Days on which a doctor takes no bookings for a reason {@code opd} does not own (SAH-21: approved leave).
 * {@code opd} defines the port and asks every implementation when it computes a doctor's slots or validates
 * a new appointment; it never depends on the module that implements it. With no implementation nothing is
 * blocked. Existing appointments on a blocked day are left as they are. Runs in the caller's transaction
 * (tenant already bound).
 */
public interface DoctorBlockedDatesPort {

    /** The dates in {@code from} to {@code to} (both inclusive) on which this doctor takes no bookings. */
    Set<LocalDate> blockedDates(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to);

    /**
     * SAH-90 (ADR-032 D1): the blocked dates of many doctors in one call, keyed by membership id; a doctor with none is
     * absent. The default asks one doctor at a time; {@code hra} overrides it with one read, so the front desk snapshot
     * never costs a query per doctor.
     */
    default Map<UUID, Set<LocalDate>> blockedDatesForDoctors(UUID tenantId, Collection<UUID> doctorMembershipIds,
                                                             LocalDate from, LocalDate to) {
        Map<UUID, Set<LocalDate>> result = new HashMap<>();
        for (UUID id : doctorMembershipIds) {
            Set<LocalDate> dates = blockedDates(tenantId, id, from, to);
            if (!dates.isEmpty()) {
                result.put(id, dates);
            }
        }
        return result;
    }
}
