package com.sahaj.opd.application;

import java.util.List;
import java.util.UUID;

/** {@code DoctorSlotSettings} in {@code docs/api/opd.yaml} (SAH-11), as submitted by the caller. */
public record DoctorSlotSettingsInput(UUID doctorMembershipId, Integer slotMinutes, List<Integer> workingDays,
                                       Integer walkInEveryNth, Integer maxOnlinePerDay,
                                       List<SessionInput> sessions) {

    public record SessionInput(String startTime, String endTime) {
    }
}
