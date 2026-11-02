package com.sahaj.opd.web;

import com.sahaj.opd.application.DoctorSlotSettingsInput;
import com.sahaj.opd.application.DoctorSlotSettingsInput.SessionInput;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsView;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** {@code DoctorSlotSettings} in {@code docs/api/opd.yaml} (SAH-11), for the request and the response. */
public record DoctorSlotSettingsDto(UUID doctorMembershipId, Integer slotMinutes, List<Integer> workingDays,
                                     Integer walkInEveryNth, Integer maxOnlinePerDay, List<SessionDto> sessions) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public record SessionDto(String startTime, String endTime) {
    }

    public static DoctorSlotSettingsDto from(DoctorSlotSettingsView view) {
        List<SessionDto> sessions = view.sessions().stream()
                .map(DoctorSlotSettingsDto::toSessionDto)
                .toList();
        return new DoctorSlotSettingsDto(view.doctorMembershipId(), view.slotMinutes(), view.workingDays(),
                view.walkInEveryNth(), view.maxOnlinePerDay(), sessions);
    }

    private static SessionDto toSessionDto(SessionRow row) {
        return new SessionDto(row.startTime().format(HHMM), row.endTime().format(HHMM));
    }

    public DoctorSlotSettingsInput toInput() {
        List<SessionInput> sessionInputs = sessions == null ? List.of() : sessions.stream()
                .map(dto -> new SessionInput(dto.startTime(), dto.endTime()))
                .toList();
        return new DoctorSlotSettingsInput(doctorMembershipId, slotMinutes, workingDays, walkInEveryNth,
                maxOnlinePerDay, sessionInputs);
    }
}
