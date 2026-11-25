package com.sahaj.opd.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sahaj.opd.application.DoctorSlotSettingsInput;
import com.sahaj.opd.application.DoctorSlotSettingsInput.SessionInput;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsView;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * {@code DoctorSlotSettings} in {@code docs/api/opd.yaml} (SAH-11), for the request and the response.
 * {@code closeBeforeHours} (0.9.0, SAH-91-2) is a JSON node so the request can tell "absent" (Java null: keep) from
 * {@code null} (NullNode: clear); the response always carries it.
 */
public record DoctorSlotSettingsDto(UUID doctorMembershipId, Integer slotMinutes, List<Integer> workingDays,
                                     Integer walkInEveryNth, Integer maxOnlinePerDay, List<SessionDto> sessions,
                                     JsonNode closeBeforeHours) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public record SessionDto(String startTime, String endTime) {
    }

    public static DoctorSlotSettingsDto from(DoctorSlotSettingsView view) {
        List<SessionDto> sessions = view.sessions().stream()
                .map(DoctorSlotSettingsDto::toSessionDto)
                .toList();
        JsonNode closeBefore = view.closeBeforeHours() == null ? NullNode.getInstance()
                : IntNode.valueOf(view.closeBeforeHours());
        return new DoctorSlotSettingsDto(view.doctorMembershipId(), view.slotMinutes(), view.workingDays(),
                view.walkInEveryNth(), view.maxOnlinePerDay(), sessions, closeBefore);
    }

    private static SessionDto toSessionDto(SessionRow row) {
        return new SessionDto(row.startTime().format(HHMM), row.endTime().format(HHMM));
    }

    public DoctorSlotSettingsInput toInput() {
        List<SessionInput> sessionInputs = sessions == null ? List.of() : sessions.stream()
                .map(dto -> new SessionInput(dto.startTime(), dto.endTime()))
                .toList();
        boolean sent = closeBeforeHours != null;
        Number raw = null;
        if (sent && !closeBeforeHours.isNull()) {
            raw = closeBeforeHours.isNumber() ? closeBeforeHours.numberValue() : Double.NaN;
        }
        return new DoctorSlotSettingsInput(doctorMembershipId, slotMinutes, workingDays, walkInEveryNth,
                maxOnlinePerDay, sessionInputs, sent, raw);
    }
}
