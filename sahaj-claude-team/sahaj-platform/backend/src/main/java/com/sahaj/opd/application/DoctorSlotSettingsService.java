package com.sahaj.opd.application;

import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-11 AC1, AC5: each doctor's own slot pattern (slot length, up to two sessions, working days,
 * every Nth slot kept for walk-ins, max online bookings a day). Any clinic role reads every
 * doctor's settings (one column per doctor on the Appointments screen); OWNER and FRONT_DESK
 * change a doctor's settings. A doctor with no settings saved yet sees the mockup's defaults,
 * the same lazy pattern as SAH-10's bill.
 */
@Service
public class DoctorSlotSettingsService {

    static final SlotSettingsRow DEFAULTS = new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0,
            List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));

    private static final String ENTITY_TYPE = "DOCTOR_SLOT_SETTINGS";
    private static final Set<String> WRITE_ROLES = Set.of("OWNER", "FRONT_DESK");
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");

    private final DoctorSlotSettingsRepository repository;
    private final TeamMemberPort teamMemberPort;
    private final AuditLog auditLog;

    public DoctorSlotSettingsService(DoctorSlotSettingsRepository repository, TeamMemberPort teamMemberPort,
                                      AuditLog auditLog) {
        this.repository = repository;
        this.teamMemberPort = teamMemberPort;
        this.auditLog = auditLog;
    }

    /** One entry per active doctor on the team, their saved settings or the mockup's defaults. */
    @Transactional(readOnly = true)
    public List<DoctorSlotSettingsView> listAll(UUID tenantId) {
        List<DoctorSlotSettingsView> views = new ArrayList<>();
        for (TeamMember doctor : teamMemberPort.findActiveDoctors(tenantId)) {
            SlotSettingsRow row = repository.find(tenantId, doctor.membershipId()).orElse(DEFAULTS);
            views.add(toView(doctor.membershipId(), row));
        }
        return views;
    }

    @Transactional
    public DoctorSlotSettingsView save(UUID tenantId, UUID actorUserId, String actorRole,
                                        UUID doctorMembershipId, DoctorSlotSettingsInput input) {
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can change slot settings.");
        }
        if (input.doctorMembershipId() != null && !input.doctorMembershipId().equals(doctorMembershipId)) {
            throw new InvalidInputException("doctorMembershipId", "The doctor in the body must match the one in the address");
        }
        TeamMember doctor = teamMemberPort.find(tenantId, doctorMembershipId)
                .filter(member -> member.active() && DOCTOR_ROLES.contains(member.role()))
                .orElseThrow(() -> new NotFoundException("Doctor not found"));

        SlotSettingsRow before = repository.find(tenantId, doctorMembershipId).orElse(DEFAULTS);
        SlotSettingsRow after = validate(input);
        repository.upsert(tenantId, doctorMembershipId, after, actorUserId);
        auditLog.record("DOCTOR_SLOT_SETTINGS_UPDATED", ENTITY_TYPE, doctorMembershipId, snapshot(before),
                snapshot(after));
        return toView(doctor.membershipId(), after);
    }

    private static SlotSettingsRow validate(DoctorSlotSettingsInput input) {
        if (input.slotMinutes() == null || input.slotMinutes() < 5 || input.slotMinutes() > 60) {
            throw new InvalidInputException("slotMinutes", "Enter a slot length from 5 to 60 minutes");
        }
        if (input.workingDays() == null || input.workingDays().isEmpty()) {
            throw new InvalidInputException("workingDays", "Choose at least one working day");
        }
        Set<Integer> days = new LinkedHashSet<>();
        for (Integer day : input.workingDays()) {
            if (day == null || day < 0 || day > 6) {
                throw new InvalidInputException("workingDays", "Working days are 0 (Sunday) to 6 (Saturday)");
            }
            days.add(day);
        }
        if (input.walkInEveryNth() == null || input.walkInEveryNth() < 0) {
            throw new InvalidInputException("walkInEveryNth", "Walk-in reserve cannot be negative");
        }
        if (input.maxOnlinePerDay() == null || input.maxOnlinePerDay() < 0) {
            throw new InvalidInputException("maxOnlinePerDay", "Max online bookings cannot be negative");
        }
        if (input.sessions() == null || input.sessions().isEmpty() || input.sessions().size() > 2) {
            throw new InvalidInputException("sessions", "Add one or two sessions");
        }
        List<SessionRow> sessions = new ArrayList<>();
        for (int i = 0; i < input.sessions().size(); i++) {
            DoctorSlotSettingsInput.SessionInput session = input.sessions().get(i);
            LocalTime start = parseTime(session.startTime());
            LocalTime end = parseTime(session.endTime());
            if (!end.isAfter(start)) {
                throw new InvalidInputException("sessions", "A session's end time must be after its start time");
            }
            sessions.add(new SessionRow(i, start, end));
        }
        return new SlotSettingsRow(input.slotMinutes(), List.copyOf(days), input.walkInEveryNth(),
                input.maxOnlinePerDay(), sessions);
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException | NullPointerException ex) {
            throw new InvalidInputException("sessions", "Enter session times as HH:mm");
        }
    }

    private static DoctorSlotSettingsView toView(UUID doctorMembershipId, SlotSettingsRow row) {
        return new DoctorSlotSettingsView(doctorMembershipId, row.slotMinutes(), row.workingDays(),
                row.walkInEveryNth(), row.maxOnlinePerDay(), row.sessions());
    }

    private static Map<String, Object> snapshot(SlotSettingsRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("slotMinutes", row.slotMinutes());
        values.put("workingDays", row.workingDays());
        values.put("walkInEveryNth", row.walkInEveryNth());
        values.put("maxOnlinePerDay", row.maxOnlinePerDay());
        values.put("sessions", row.sessions().size());
        return values;
    }
}
