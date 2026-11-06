package com.sahaj.opd.api;

import java.time.LocalDate;
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
}
