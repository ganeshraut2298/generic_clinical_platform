package com.sahaj.hra.application;

import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.opd.api.DoctorOpdDaysPort;
import com.sahaj.platform.api.ActorDirectoryPort;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-21 Leave requests and balances (HRA add-on, part 2). Every call first checks the HRA add-on (403
 * module-off). Any clinic role asks for leave for themselves and reads their own requests and balance;
 * only the owner names another person, sees everyone's and approves or declines (owner re-read through
 * {@link OwnerGuard}). Days are Asia/Kolkata. Audit rows carry ids, dates, type and status only: the
 * reason is free text typed by staff and is never audited or logged, and neither is any name.
 */
@Service
public class LeaveService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_SPAN_DAYS = 60;
    static final int MAX_AHEAD_DAYS = 60;
    static final int MAX_PENDING_PER_PERSON = 20;
    static final int OWNER_LIST_LIMIT = 200;
    static final int MAX_REASON = 200;
    static final String OVERLAP_MESSAGE = "That person already has leave on some of these days.";
    static final String DECIDED_MESSAGE = "This request was already decided.";
    static final String TOO_MANY_PENDING_MESSAGE = "Too many pending requests. Wait for a decision.";

    static final List<LeaveView.TypeInfo> TYPES = List.of(
            new LeaveView.TypeInfo("CL", "Casual leave", 8),
            new LeaveView.TypeInfo("SL", "Sick leave", 7),
            new LeaveView.TypeInfo("EL", "Earned leave", 15));

    private static final String OWNER = "OWNER";
    private static final String ENTITY = "LEAVE_REQUEST";
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");

    private final LeaveRepository leaveRepository;
    private final TeamMemberPort teamMemberPort;
    private final ModuleEntitlementPort entitlementPort;
    private final DoctorOpdDaysPort opdDaysPort;
    private final ActorDirectoryPort actorDirectoryPort;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final Clock clock;

    public LeaveService(LeaveRepository leaveRepository, TeamMemberPort teamMemberPort,
                        ModuleEntitlementPort entitlementPort, DoctorOpdDaysPort opdDaysPort,
                        ActorDirectoryPort actorDirectoryPort, OwnerGuard ownerGuard, AuditLog auditLog,
                        Clock clock) {
        this.leaveRepository = leaveRepository;
        this.teamMemberPort = teamMemberPort;
        this.entitlementPort = entitlementPort;
        this.opdDaysPort = opdDaysPort;
        this.actorDirectoryPort = actorDirectoryPort;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LeaveView view(UUID tenantId, UUID actorUserId, String actorRole) {
        requireHraOn(tenantId);
        return buildView(tenantId, actorUserId, actorRole);
    }

    @Transactional
    public LeaveView create(UUID tenantId, UUID actorUserId, String actorRole, LeaveInput input) {
        requireHraOn(tenantId);
        boolean owner = OWNER.equals(actorRole);
        TeamMember self = teamMemberPort.findActiveByUserId(tenantId, actorUserId).orElse(null);
        boolean forSelf = input.membershipId() == null || (self != null && input.membershipId().equals(self.membershipId()));
        if (!forSelf) {
            if (!owner) {
                throw new AccessDeniedException("Only the owner can ask for leave for someone else.");
            }
            ownerGuard.requireOwner(tenantId, actorUserId);
        }
        validate(input);
        TeamMember person;
        if (forSelf) {
            person = self;
            if (person == null) {
                throw new NotFoundException("Team member not found");
            }
        } else {
            person = teamMemberPort.find(tenantId, input.membershipId())
                    .filter(TeamMember::active)
                    .orElseThrow(() -> new NotFoundException("Team member not found"));
        }
        leaveRepository.lockMember(tenantId, person.membershipId());
        if (leaveRepository.countPendingForMember(tenantId, person.membershipId()) >= MAX_PENDING_PER_PERSON) {
            throw new ConflictException("too-many-pending", "Too many pending requests", TOO_MANY_PENDING_MESSAGE);
        }
        if (leaveRepository.existsActiveOverlap(tenantId, person.membershipId(), input.from(), input.to())) {
            throw new ConflictException("leave-overlap", "Leave already exists", OVERLAP_MESSAGE);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String reason = normaliseReason(input.reason());
        leaveRepository.insert(tenantId, id, person.membershipId(), input.type(), input.from(), input.to(), reason,
                actorUserId, now);
        auditLog.record("LEAVE_REQUESTED", ENTITY, id, null,
                snapshot(person.membershipId(), input.type(), input.from(), input.to(), "pending"));
        return buildView(tenantId, actorUserId, actorRole);
    }

    @Transactional
    public LeaveView approve(UUID tenantId, UUID actorUserId, String actorRole, UUID id) {
        return decide(tenantId, actorUserId, actorRole, id, "approved", "LEAVE_APPROVED");
    }

    @Transactional
    public LeaveView decline(UUID tenantId, UUID actorUserId, String actorRole, UUID id) {
        return decide(tenantId, actorUserId, actorRole, id, "declined", "LEAVE_DECLINED");
    }

    private LeaveView decide(UUID tenantId, UUID actorUserId, String actorRole, UUID id, String status,
                             String auditAction) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        requireHraOn(tenantId);
        LeaveRow row = leaveRepository.findById(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Leave request not found"));
        if (!"pending".equals(row.status())) {
            throw new ConflictException("leave-already-decided", "Already decided", DECIDED_MESSAGE);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!leaveRepository.decide(tenantId, id, status, actorUserId, now)) {
            throw new ConflictException("leave-already-decided", "Already decided", DECIDED_MESSAGE);
        }
        auditLog.record(auditAction, ENTITY, id,
                snapshot(row.membershipId(), row.type(), row.from(), row.to(), "pending"),
                snapshot(row.membershipId(), row.type(), row.from(), row.to(), status));
        return buildView(tenantId, actorUserId, actorRole);
    }

    private void validate(LeaveInput input) {
        if (input.type() == null || TYPES.stream().noneMatch(t -> t.key().equals(input.type()))) {
            throw new InvalidInputException("type", "Choose Casual, Sick or Earned leave");
        }
        if (input.from() == null) {
            throw new InvalidInputException("from", "Choose the first day");
        }
        if (input.to() == null) {
            throw new InvalidInputException("to", "Choose the last day");
        }
        if (input.from().isBefore(today())) {
            throw new InvalidInputException("from", "The first day cannot be in the past");
        }
        if (input.from().isAfter(today().plusDays(MAX_AHEAD_DAYS))) {
            throw new InvalidInputException("from", "Leave can be asked for up to 60 days ahead");
        }
        if (input.to().isBefore(input.from())) {
            throw new InvalidInputException("to", "The last day cannot be before the first day");
        }
        if (ChronoUnit.DAYS.between(input.from(), input.to()) > MAX_SPAN_DAYS) {
            throw new InvalidInputException("to", "Leave can be asked for up to 60 days ahead of the first day");
        }
        if (input.reason() != null && input.reason().length() > MAX_REASON) {
            throw new InvalidInputException("reason", "Keep the reason to 200 characters");
        }
        if (input.reason() != null && input.reason().chars().anyMatch(Character::isISOControl)) {
            throw new InvalidInputException("reason", "Remove special characters from the reason");
        }
    }

    private LeaveView buildView(UUID tenantId, UUID actorUserId, String actorRole) {
        boolean owner = OWNER.equals(actorRole);
        List<TeamMember> members = teamMemberPort.findActiveMembers(tenantId);
        TeamMember self = teamMemberPort.findActiveByUserId(tenantId, actorUserId).orElse(null);
        List<LeaveRow> rows;
        if (owner) {
            rows = leaveRepository.findRecent(tenantId, OWNER_LIST_LIMIT);
        } else if (self != null) {
            rows = leaveRepository.findForMember(tenantId, self.membershipId());
        } else {
            rows = List.of();
        }
        Map<UUID, TeamMember> byId = new HashMap<>();
        for (TeamMember member : members) {
            byId.put(member.membershipId(), member);
        }
        Map<UUID, String> decidedNames = new HashMap<>();
        Map<UUID, Map<LocalDate, Integer>> booked = bookedForPendingDoctors(tenantId, rows, byId);
        List<LeaveView.Request> requests = new ArrayList<>();
        int pending = 0;
        for (LeaveRow row : rows) {
            TeamMember person = byId.computeIfAbsent(row.membershipId(),
                    key -> teamMemberPort.find(tenantId, key).orElse(null));
            requests.add(toRequest(tenantId, row, person, decidedNames, booked));
            if ("pending".equals(row.status())) {
                pending++;
            }
        }
        if (owner) {
            pending = (int) leaveRepository.countPending(tenantId);
        }
        List<TeamMember> visible = owner ? members : (self == null ? List.of() : List.of(self));
        LocalDate today = today();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate yearEnd = LocalDate.of(today.getYear(), 12, 31);
        Map<UUID, Map<String, Integer>> used = new HashMap<>();
        for (LeaveRow row : leaveRepository.findApprovedOverlapping(tenantId, yearStart, yearEnd)) {
            LocalDate from = row.from().isBefore(yearStart) ? yearStart : row.from();
            LocalDate to = row.to().isAfter(yearEnd) ? yearEnd : row.to();
            int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
            used.computeIfAbsent(row.membershipId(), key -> new HashMap<>()).merge(row.type(), days, Integer::sum);
        }
        List<LeaveView.Balance> balances = new ArrayList<>();
        List<LeaveView.Applicant> applicants = new ArrayList<>();
        for (TeamMember member : visible) {
            Map<String, Integer> spent = used.getOrDefault(member.membershipId(), Map.of());
            balances.add(new LeaveView.Balance(member.membershipId(), member.name(), new LeaveView.Remaining(
                    remaining("CL", spent), remaining("SL", spent), remaining("EL", spent))));
            applicants.add(new LeaveView.Applicant(member.membershipId(), member.name()));
        }
        return new LeaveView(TYPES, requests, balances, applicants, pending, owner);
    }

    private LeaveView.Request toRequest(UUID tenantId, LeaveRow row, TeamMember person, Map<UUID, String> decidedNames,
                                        Map<UUID, Map<LocalDate, Integer>> booked) {
        LeaveView.TypeInfo type = TYPES.stream().filter(t -> t.key().equals(row.type())).findFirst().orElseThrow();
        int affected = 0;
        if ("pending".equals(row.status()) && person != null && DOCTOR_ROLES.contains(person.role())) {
            for (Map.Entry<LocalDate, Integer> day : booked.getOrDefault(row.membershipId(), Map.of()).entrySet()) {
                if (!day.getKey().isBefore(row.from()) && !day.getKey().isAfter(row.to())) {
                    affected += day.getValue();
                }
            }
        }
        String decidedByName = null;
        if (row.decidedBy() != null) {
            decidedByName = decidedNames.computeIfAbsent(row.decidedBy(),
                    key -> actorDirectoryPort.displayName(tenantId, key).orElse("Someone"));
        }
        return new LeaveView.Request(row.id(), row.membershipId(), person == null ? "Former team member" : person.name(),
                person == null ? "" : RoleLabels.of(person.role()), row.type(), type.name(), row.from(), row.to(),
                (int) ChronoUnit.DAYS.between(row.from(), row.to()) + 1, row.reason(), row.status(), row.createdAt(),
                row.decidedAt(), decidedByName, affected);
    }

    /** One grouped query for every pending doctor request in the list (security review M2), not one per row. */
    private Map<UUID, Map<LocalDate, Integer>> bookedForPendingDoctors(UUID tenantId, List<LeaveRow> rows,
                                                                       Map<UUID, TeamMember> byId) {
        Set<UUID> doctors = new HashSet<>();
        LocalDate first = null;
        LocalDate last = null;
        for (LeaveRow row : rows) {
            TeamMember person = byId.computeIfAbsent(row.membershipId(),
                    key -> teamMemberPort.find(tenantId, key).orElse(null));
            if ("pending".equals(row.status()) && person != null && DOCTOR_ROLES.contains(person.role())) {
                doctors.add(row.membershipId());
                first = first == null || row.from().isBefore(first) ? row.from() : first;
                last = last == null || row.to().isAfter(last) ? row.to() : last;
            }
        }
        if (doctors.isEmpty()) {
            return Map.of();
        }
        return opdDaysPort.bookedAppointmentsByDay(tenantId, doctors, first, last);
    }

    private static int remaining(String type, Map<String, Integer> spent) {
        int allowance = TYPES.stream().filter(t -> t.key().equals(type)).findFirst().orElseThrow().days();
        return Math.max(0, allowance - spent.getOrDefault(type, 0));
    }

    private static String normaliseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }

    private static Map<String, Object> snapshot(UUID membershipId, String type, LocalDate from, LocalDate to,
                                                String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("membershipId", membershipId);
        values.put("type", type);
        values.put("from", from.toString());
        values.put("to", to.toString());
        values.put("status", status);
        return values;
    }

    private void requireHraOn(UUID tenantId) {
        if (!entitlementPort.forTenant(tenantId).hraOn()) {
            throw new ModuleOffException("Staff, timings and leave is an add-on. Ask the owner to switch it on.");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }
}
