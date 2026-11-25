package com.sahaj.opd.application;

import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import java.util.List;
import java.util.UUID;

/** {@code DoctorSlotSettings} in {@code docs/api/opd.yaml} (SAH-11), read from the database. */
public record DoctorSlotSettingsView(UUID doctorMembershipId, int slotMinutes, List<Integer> workingDays,
                                      int walkInEveryNth, int maxOnlinePerDay, List<SessionRow> sessions,
                                      Integer closeBeforeHours) {

    /** A view with no per-doctor cut-off. */
    public DoctorSlotSettingsView(UUID doctorMembershipId, int slotMinutes, List<Integer> workingDays,
                                   int walkInEveryNth, int maxOnlinePerDay, List<SessionRow> sessions) {
        this(doctorMembershipId, slotMinutes, workingDays, walkInEveryNth, maxOnlinePerDay, sessions, null);
    }
}
