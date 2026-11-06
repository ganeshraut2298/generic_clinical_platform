package com.sahaj.opd.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which days a doctor has OPD (SAH-20): {@code hra} shows OPD or Off in a doctor's roster cell from the
 * same slot settings the booking board uses, so booking and the roster always match. A day counts as
 * OPD when the doctor's weekday pattern has bookable slots that day and the clinic is not closed on
 * that date. A doctor with no saved settings has the default pattern. Implemented by {@code opd}; runs
 * in the caller's transaction (tenant already bound).
 */
public interface DoctorOpdDaysPort {

    /** The subset of {@code dates} on which this doctor has OPD slots. */
    Set<LocalDate> opdDates(UUID tenantId, UUID doctorMembershipId, Collection<LocalDate> dates);

    /** True when the doctor has OPD slots on this date. */
    boolean hasOpdSlots(UUID tenantId, UUID doctorMembershipId, LocalDate date);

    /** SAH-21: BOOKED appointments of this doctor from {@code from} to {@code to}, both inclusive (not arrived, cancelled or no-show). */
    int bookedAppointments(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to);

    /**
     * SAH-21 (security review M2): BOOKED appointments per doctor and day in one grouped query, for the doctors
     * in {@code doctorMembershipIds} between {@code from} and {@code to}, both inclusive. Days with none are absent.
     */
    Map<UUID, Map<LocalDate, Integer>> bookedAppointmentsByDay(UUID tenantId, Collection<UUID> doctorMembershipIds,
                                                              LocalDate from, LocalDate to);
}
