package com.sahaj.hra.application;

import com.sahaj.hra.application.AttendanceRepository.AttendanceRow;
import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.hra.application.RosterRepository.ShiftRow;
import com.sahaj.opd.api.DoctorOpdDaysPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-20 Staff and leave (HRA add-on, part 1): the 7-day roster, the daily attendance sheet and the
 * tiles. Every call first checks the HRA add-on (403 module-off). Reads are for any clinic role; the
 * owner edits cells and publishes (owner re-read through {@link OwnerGuard}); the owner and front desk
 * mark attendance. Doctors' roster cells are derived from Appointments and slots, never stored. Days are
 * Asia/Kolkata. Audit rows carry ids, dates and the before and after values, never names.
 */
@Service
public class HraService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int DAYS = 7;
    static final int LATE_AFTER_MINUTES = 15;

    static final List<RosterView.ShiftInfo> SHIFTS = List.of(
            new RosterView.ShiftInfo("M", "Morning", "08:00 – 14:00"),
            new RosterView.ShiftInfo("E", "Evening", "14:00 – 20:00"),
            new RosterView.ShiftInfo("N", "Night", "20:00 – 08:00"),
            new RosterView.ShiftInfo("X", "Off", ""));

    private static final Set<String> SHIFT_KEYS = Set.of("M", "E", "N", "X");
    private static final Set<String> STATUSES = Set.of("in", "late", "abs");
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");
    private static final Set<String> MARK_ROLES = Set.of("OWNER", "FRONT_DESK");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final RosterRepository rosterRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRepository leaveRepository;
    private final TeamMemberPort teamMemberPort;
    private final ModuleEntitlementPort entitlementPort;
    private final DoctorOpdDaysPort opdDaysPort;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final Clock clock;

    public HraService(RosterRepository rosterRepository, AttendanceRepository attendanceRepository,
                      LeaveRepository leaveRepository,
                      TeamMemberPort teamMemberPort, ModuleEntitlementPort entitlementPort,
                      DoctorOpdDaysPort opdDaysPort, OwnerGuard ownerGuard, AuditLog auditLog, Clock clock) {
        this.rosterRepository = rosterRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveRepository = leaveRepository;
        this.teamMemberPort = teamMemberPort;
        this.entitlementPort = entitlementPort;
        this.opdDaysPort = opdDaysPort;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffSummaryView summary(UUID tenantId, String actorRole) {
        requireHraOn(tenantId);
        List<TeamMember> members = teamMemberPort.findActiveMembers(tenantId);
        int doctors = (int) members.stream().filter(HraService::isDoctor).count();
        Set<UUID> activeIds = members.stream().map(TeamMember::membershipId).collect(Collectors.toSet());
        int present = 0;
        int late = 0;
        for (AttendanceRow row : attendanceRepository.findForDate(tenantId, today())) {
            if (!activeIds.contains(row.membershipId())) {
                continue;
            }
            if ("in".equals(row.status())) {
                present++;
            } else if ("late".equals(row.status())) {
                present++;
                late++;
            }
        }
        int leaveRequests = "OWNER".equals(actorRole) ? (int) leaveRepository.countPending(tenantId) : 0;
        return new StaffSummaryView(members.size(), doctors, members.size() - doctors, present, late, leaveRequests);
    }

    @Transactional(readOnly = true)
    public RosterView roster(UUID tenantId) {
        requireHraOn(tenantId);
        return buildRoster(tenantId);
    }

    @Transactional
    public RosterView setCell(UUID tenantId, UUID actorUserId, RosterCellInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        requireHraOn(tenantId);
        if (input.shift() == null || !SHIFT_KEYS.contains(input.shift())) {
            throw new InvalidInputException("shift", "Choose Morning, Evening, Night or Off");
        }
        if (input.date() == null) {
            throw new InvalidInputException("date", "Choose the date");
        }
        if (!days().contains(input.date())) {
            throw new InvalidInputException("date", "You can change only the seven days shown");
        }
        if (input.membershipId() == null) {
            throw new InvalidInputException("membershipId", "Choose the team member");
        }
        TeamMember member = teamMemberPort.find(tenantId, input.membershipId())
                .filter(TeamMember::active)
                .orElseThrow(() -> new NotFoundException("Team member not found"));
        if (isDoctor(member)) {
            throw new InvalidInputException("membershipId",
                    "A doctor's days come from Appointments and slots, not from the roster");
        }
        if (!leaveRepository.findApprovedOverlappingForMember(tenantId, member.membershipId(), input.date(), input.date())
                .isEmpty()) {
            throw new ConflictException("on-leave", "Person is on leave", "That person is on leave on this day.");
        }
        String before = rosterRepository.findShift(tenantId, member.membershipId(), input.date()).orElse("X");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        rosterRepository.upsertShift(tenantId, member.membershipId(), input.date(), input.shift(), actorUserId, now);
        auditLog.record("ROSTER_CELL_CHANGED", "ROSTER_SHIFT", member.membershipId(),
                cellSnapshot(input.date(), before), cellSnapshot(input.date(), input.shift()));
        return buildRoster(tenantId);
    }

    @Transactional
    public RosterView publish(UUID tenantId, UUID actorUserId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        requireHraOn(tenantId);
        if (rosterRepository.findPublishedAt(tenantId).isEmpty()) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            rosterRepository.publish(tenantId, actorUserId, now);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("published", false);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("published", true);
            after.put("publishedAt", now.toString());
            auditLog.record("ROSTER_PUBLISHED", "ROSTER_STATE", tenantId, before, after);
        }
        return buildRoster(tenantId);
    }

    @Transactional(readOnly = true)
    public AttendanceView attendance(UUID tenantId, String actorRole) {
        requireHraOn(tenantId);
        return buildAttendance(tenantId, actorRole);
    }

    @Transactional
    public AttendanceView mark(UUID tenantId, UUID actorUserId, String actorRole, UUID membershipId, String status) {
        String role = teamMemberPort.findActiveByUserId(tenantId, actorUserId).map(TeamMember::role).orElse(null);
        if (role == null || !MARK_ROLES.contains(role)) {
            throw new AccessDeniedException("Only the owner or front desk can mark attendance.");
        }
        requireHraOn(tenantId);
        if (status == null || !STATUSES.contains(status)) {
            throw new InvalidInputException("status", "Choose Present, Late or Absent");
        }
        TeamMember member = teamMemberPort.find(tenantId, membershipId)
                .filter(TeamMember::active)
                .orElseThrow(() -> new NotFoundException("Team member not found"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, INDIA);
        if (!leaveRepository.findApprovedOverlappingForMember(tenantId, member.membershipId(), today, today).isEmpty()) {
            throw new ConflictException("on-leave", "Person is on leave", "That person is on leave today.");
        }
        LocalTime markedTime = "abs".equals(status) ? null
                : LocalTime.ofInstant(now, INDIA).truncatedTo(ChronoUnit.SECONDS);
        AttendanceRow before = attendanceRepository.find(tenantId, member.membershipId(), today).orElse(null);
        attendanceRepository.upsert(tenantId, member.membershipId(), today, status, markedTime, actorUserId, now);
        auditLog.record("ATTENDANCE_MARKED", "ATTENDANCE", member.membershipId(),
                before == null ? null : attendanceSnapshot(today, before.status()), attendanceSnapshot(today, status));
        return buildAttendance(tenantId, role);
    }

    private RosterView buildRoster(UUID tenantId) {
        List<LocalDate> days = days();
        List<TeamMember> members = teamMemberPort.findActiveMembers(tenantId);
        Map<String, String> saved = new HashMap<>();
        for (ShiftRow row : rosterRepository.findBetween(tenantId, days.get(0), days.get(DAYS - 1))) {
            saved.put(row.membershipId() + "|" + row.date(), row.shift());
        }
        Set<String> onLeave = new HashSet<>();
        for (LeaveRow row : leaveRepository.findApprovedOverlapping(tenantId, days.get(0), days.get(DAYS - 1))) {
            for (LocalDate day : days) {
                if (!day.isBefore(row.from()) && !day.isAfter(row.to())) {
                    onLeave.add(row.membershipId() + "|" + day);
                }
            }
        }
        List<RosterView.Row> rows = new ArrayList<>();
        for (TeamMember member : members) {
            boolean doctor = isDoctor(member);
            Set<LocalDate> opd = doctor ? opdDaysPort.opdDates(tenantId, member.membershipId(), days) : Set.of();
            List<RosterView.Cell> cells = new ArrayList<>();
            for (LocalDate day : days) {
                if (onLeave.contains(member.membershipId() + "|" + day)) {
                    cells.add(new RosterView.Cell(day, "LEAVE", null));
                } else if (doctor) {
                    cells.add(new RosterView.Cell(day, opd.contains(day) ? "OPD" : "OFF", null));
                } else {
                    cells.add(new RosterView.Cell(day, "SHIFT",
                            saved.getOrDefault(member.membershipId() + "|" + day, "X")));
                }
            }
            rows.add(new RosterView.Row(member.membershipId(), member.name(), RoleLabels.of(member.role()), doctor, cells));
        }
        Instant publishedAt = rosterRepository.findPublishedAt(tenantId).orElse(null);
        return new RosterView(days, SHIFTS, publishedAt != null, publishedAt, rows);
    }

    private AttendanceView buildAttendance(UUID tenantId, String actorRole) {
        LocalDate today = today();
        Map<UUID, AttendanceRow> byMember = new HashMap<>();
        for (AttendanceRow row : attendanceRepository.findForDate(tenantId, today)) {
            byMember.put(row.membershipId(), row);
        }
        Set<UUID> onLeave = new HashSet<>();
        for (LeaveRow row : leaveRepository.findApprovedOverlapping(tenantId, today, today)) {
            onLeave.add(row.membershipId());
        }
        List<AttendanceView.Row> rows = new ArrayList<>();
        for (TeamMember member : teamMemberPort.findActiveMembers(tenantId)) {
            AttendanceRow mark = byMember.get(member.membershipId());
            rows.add(new AttendanceView.Row(member.membershipId(), member.name(), RoleLabels.of(member.role()),
                    mark == null ? null : mark.status(),
                    mark == null || mark.markedTime() == null ? null : mark.markedTime().format(HH_MM),
                    onLeave.contains(member.membershipId())));
        }
        return new AttendanceView(today, LATE_AFTER_MINUTES, MARK_ROLES.contains(actorRole), rows);
    }

    private void requireHraOn(UUID tenantId) {
        if (!entitlementPort.forTenant(tenantId).hraOn()) {
            throw new ModuleOffException("Staff, timings and leave is an add-on. Ask the owner to switch it on.");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private List<LocalDate> days() {
        LocalDate today = today();
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            days.add(today.plusDays(i));
        }
        return List.copyOf(days);
    }

    private static boolean isDoctor(TeamMember member) {
        return DOCTOR_ROLES.contains(member.role());
    }

    private static Map<String, Object> cellSnapshot(LocalDate date, String shift) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", date.toString());
        values.put("shift", shift);
        return values;
    }

    private static Map<String, Object> attendanceSnapshot(LocalDate date, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", date.toString());
        values.put("status", status);
        return values;
    }
}
