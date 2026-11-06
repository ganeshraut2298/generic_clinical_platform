package com.sahaj.opd.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.appointment} (SAH-11). Needs {@code app.tenant_id} bound. */
public interface AppointmentRepository {

    void insert(UUID id, UUID patientId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime,
                String visitType, String source, String note, UUID actorUserId);

    /** Another clinic's id, or any id at all, is simply absent. */
    Optional<AppointmentRow> findById(UUID tenantId, UUID id);

    /** The day's appointments, newest-created first within a slot; optionally for one doctor only. */
    List<AppointmentRow> findByDate(UUID tenantId, LocalDate apptDate, UUID doctorMembershipId);

    /**
     * Takes a transaction-scoped advisory lock on this doctor's slot so two concurrent bookings of the same slot
     * queue up; the occupancy check that follows then sees the first booking. Released at commit or rollback.
     */
    void lockSlot(UUID tenantId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime);

    /** An existing BOOKED or ARRIVED appointment in this doctor's slot, if any (overbook check, AC2). */
    Optional<AppointmentRow> findActiveConflict(UUID tenantId, UUID doctorMembershipId, LocalDate apptDate,
                                                 LocalTime apptTime);

    /** SAH-21: BOOKED appointments of this doctor from {@code from} to {@code to}, both inclusive. */
    int countBooked(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to);

    /** SAH-21: BOOKED appointments per doctor and day, one grouped query. Days with none are absent. */
    Map<UUID, Map<LocalDate, Integer>> countBookedByDay(UUID tenantId, Collection<UUID> doctorMembershipIds,
                                                       LocalDate from, LocalDate to);

    void updateStatus(UUID id, String status, UUID actorUserId);

    /** Stamps the issued visit and moves the appointment to ARRIVED in the same call (AC3). */
    void setVisit(UUID id, UUID visitId, String status, UUID actorUserId);

    record AppointmentRow(UUID id, UUID patientId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime,
                           String status, String visitType, String source, String note, UUID visitId,
                           long version) {
    }
}
