package com.sahaj.opd.application;

import java.util.List;
import java.util.UUID;

/**
 * {@code DoctorSlotSettings} in {@code docs/api/opd.yaml} (SAH-11), as submitted by the caller.
 * {@code closeBeforeSent} tells "absent" (keep the stored cut-off) from "null" (clear it); {@code closeBeforeHours} is the raw
 * number sent (a decimal or a non-number is refused by the service).
 */
public record DoctorSlotSettingsInput(UUID doctorMembershipId, Integer slotMinutes, List<Integer> workingDays,
                                       Integer walkInEveryNth, Integer maxOnlinePerDay,
                                       List<SessionInput> sessions, boolean closeBeforeSent, Number closeBeforeHours) {

    /** A body that does not mention the per-doctor cut-off. */
    public DoctorSlotSettingsInput(UUID doctorMembershipId, Integer slotMinutes, List<Integer> workingDays,
                                    Integer walkInEveryNth, Integer maxOnlinePerDay, List<SessionInput> sessions) {
        this(doctorMembershipId, slotMinutes, workingDays, walkInEveryNth, maxOnlinePerDay, sessions, false, null);
    }

    public record SessionInput(String startTime, String endTime) {
    }
}
