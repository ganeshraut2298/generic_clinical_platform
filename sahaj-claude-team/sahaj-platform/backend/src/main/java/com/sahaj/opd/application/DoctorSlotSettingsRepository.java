package com.sahaj.opd.application;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.doctor_slot_template} and {@code opd.doctor_slot_session} (SAH-11). Needs
 * {@code app.tenant_id} bound.
 */
public interface DoctorSlotSettingsRepository {

    /** Another clinic's doctor, or a doctor with no settings saved yet, is simply absent. */
    Optional<SlotSettingsRow> find(UUID tenantId, UUID doctorMembershipId);

    /** Replaces the doctor's slot length, working days, walk-in reserve, max online bookings and sessions. */
    void upsert(UUID tenantId, UUID doctorMembershipId, SlotSettingsRow row, UUID actorUserId);

    /** {@code workingDays} is 0 (Sunday) to 6 (Saturday); {@code sessions} has at most 2 entries, ordered by index. */
    record SlotSettingsRow(int slotMinutes, List<Integer> workingDays, int walkInEveryNth, int maxOnlinePerDay,
                            List<SessionRow> sessions, Integer closeBeforeHours) {

        /** Settings with no per-doctor cut-off (the clinic's value applies). */
        public SlotSettingsRow(int slotMinutes, List<Integer> workingDays, int walkInEveryNth, int maxOnlinePerDay,
                                List<SessionRow> sessions) {
            this(slotMinutes, workingDays, walkInEveryNth, maxOnlinePerDay, sessions, null);
        }
    }

    /** SAH-90: every doctor's settings of the clinic in one read, keyed by doctor; doctors with none are absent. */
    java.util.Map<UUID, SlotSettingsRow> findAllByTenant(UUID tenantId);

    record SessionRow(int sessionIndex, LocalTime startTime, LocalTime endTime) {
    }
}
