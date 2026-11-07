package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.PlanLimits;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-6 Team and access: add staff, change roles, switch people off and on, remove access.
 *
 * <p>Every change is for owners only. The role in the access token is not trusted for this: the
 * owner's own membership is read again, so an owner who was demoted a moment ago is refused at once.
 * Nothing is deleted. A person is never renamed globally: the name is the clinic's own
 * ({@code membership.display_name}). Audit rows carry the masked mobile only.
 */
@Service
public class TeamService {

    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final int MAX_NAME_LENGTH = 200;
    private static final String ENTITY_TYPE = "MEMBERSHIP";

    private final TeamRepository teamRepository;
    private final AppUserRepository appUserRepository;
    private final DoctorProfileRepository doctorProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLog auditLog;
    private final StaffPinRepository staffPinRepository;
    private final StaffPinOps staffPinOps;
    private final StaffPinLimits staffPinLimits;
    private final StaffPinColleagueEventRepository colleagueEvents;
    private final Clock clock;

    public TeamService(TeamRepository teamRepository, AppUserRepository appUserRepository,
                        DoctorProfileRepository doctorProfileRepository,
                        RefreshTokenRepository refreshTokenRepository, AuditLog auditLog,
                        StaffPinRepository staffPinRepository, StaffPinOps staffPinOps,
                        StaffPinLimits staffPinLimits, StaffPinColleagueEventRepository colleagueEvents,
                        Clock clock) {
        this.teamRepository = teamRepository;
        this.appUserRepository = appUserRepository;
        this.doctorProfileRepository = doctorProfileRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLog = auditLog;
        this.staffPinRepository = staffPinRepository;
        this.staffPinOps = staffPinOps;
        this.staffPinLimits = staffPinLimits;
        this.colleagueEvents = colleagueEvents;
        this.clock = clock;
    }

    /**
     * AC3, AC6: anyone signed in to the clinic may read the team; the mobile is masked. SAH-31 (ADR-010 D12): the PIN
     * state is filled on every row for an active owner (membership read again, not the token) and on the caller's own
     * row for everyone else; other people's PIN state, especially a lock, is not shown to non-owners. SAH-38 (ADR-014
     * D9): {@code pin.colleagueTries7d}, the wrong tries of the member's PIN by colleagues in the last 7 days, is filled
     * for an owner only (one grouped query for the page); null for every other role, the caller's own row included.
     */
    @Transactional(readOnly = true)
    public List<TeamMemberView> list(UUID tenantId, UUID viewerUserId) {
        List<TeamRow> rows = teamRepository.findAll(tenantId);
        boolean viewerIsOwner = rows.stream().anyMatch(row -> row.userId().equals(viewerUserId)
                && row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE);
        Map<UUID, StaffPinRow> pins = new HashMap<>();
        staffPinRepository.findAll(tenantId).forEach(pin -> pins.put(pin.membershipId(), pin));
        Map<UUID, Integer> colleagueTries = viewerIsOwner ? colleagueEvents.wrongTriesLast7Days(tenantId) : Map.of();
        return rows.stream()
                .map(row -> toView(row, viewerIsOwner || row.userId().equals(viewerUserId)
                        ? pinViewOf(pins.get(row.id()),
                                viewerIsOwner ? colleagueTries.getOrDefault(row.id(), 0) : null)
                        : null))
                .toList();
    }

    /**
     * SAH-31 (ADR-010 D8): the owner clears a member's PIN; they must set a new one with a code to their own mobile.
     * The role in the token is not trusted: the owner's membership is read again. Nothing is written when there is
     * no PIN, so a repeat after a lost answer is safe. Allowed for any member of the clinic, whether or not PIN checks
     * are available (clearing is the safe direction).
     */
    @Transactional
    public TeamMemberView resetPin(UUID tenantId, UUID actorUserId, UUID membershipId) {
        boolean owner = teamRepository.findByUser(tenantId, actorUserId)
                .map(row -> row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!owner) {
            throw new ForbiddenException("forbidden", "Not allowed", "Only the clinic owner can do this.");
        }
        TeamRow row = find(tenantId, membershipId);
        long wait = staffPinLimits.resetWait(tenantId, actorUserId);
        if (wait > 0) {
            throw StaffPinException.rateLimited(wait);
        }
        staffPinOps.clear(tenantId, actorUserId, membershipId);
        StaffPinRow pin = staffPinRepository.find(tenantId, membershipId, false).orElse(null);
        return toView(row, pinViewOf(pin, colleagueEvents.wrongTriesLast7Days(tenantId, membershipId)));
    }

    /** AC1, AC4, AC7, AC8, AC12. */
    @Transactional
    public TeamMemberView add(UUID tenantId, UUID actorUserId, TeamAddInput input) {
        requireOwner(tenantId, actorUserId);
        String name = cleanName(input.name());
        if (input.mobile() == null || !MOBILE.matcher(input.mobile()).matches()) {
            throw new TeamInputInvalidException("Enter a 10-digit mobile number");
        }
        if (input.role() == null) {
            throw new TeamInputInvalidException("Choose a role");
        }
        return guarded(() -> {
            teamRepository.lockTenant();
            AppUser person = appUserRepository.findByMobile(input.mobile()).orElse(null);
            if (person != null && teamRepository.findByUser(tenantId, person.id()).isPresent()) {
                throw new TeamConflictException(TeamConflictException.Kind.DUPLICATE_MOBILE);
            }
            requireRoomForOneMore(tenantId);
            UUID userId;
            if (person == null) {
                userId = UUID.randomUUID();
                appUserRepository.insert(userId, input.mobile(), name);
            } else {
                userId = person.id();
            }
            UUID membershipId = UUID.randomUUID();
            teamRepository.insert(membershipId, userId, input.role(), name, actorUserId);
            if (isDoctorRole(input.role())) {
                saveDoctorProfile(tenantId, membershipId, null, input.doctor());
            }
            TeamRow after = find(tenantId, membershipId);
            auditLog.record("TEAM_MEMBER_ADDED", ENTITY_TYPE, membershipId, null, snapshot(after));
            return toView(after);
        });
    }

    /** Rename, change role or change doctor details. AC10: the last active owner cannot be demoted. */
    @Transactional
    public TeamMemberView update(UUID tenantId, UUID actorUserId, UUID membershipId, TeamUpdateInput input) {
        requireOwner(tenantId, actorUserId);
        TeamRow before = find(tenantId, membershipId);
        String newName = input.name() == null ? null : cleanName(input.name());
        boolean nameChanges = newName != null && !newName.equals(before.name());
        boolean roleChanges = input.role() != null && input.role() != before.role();
        if (roleChanges) {
            requireNotLastOwner(tenantId, before);
        }
        MembershipRole role = roleChanges ? input.role() : before.role();
        boolean doctorChanges = isDoctorRole(role) && (input.doctor() != null || !before.hasDoctorProfile());
        if (!nameChanges && !roleChanges && !doctorChanges) {
            return toView(before);
        }
        return guarded(() -> {
            if (nameChanges || roleChanges) {
                teamRepository.update(membershipId, roleChanges ? role : null, nameChanges ? newName : null,
                        actorUserId);
            }
            if (doctorChanges) {
                saveDoctorProfile(tenantId, membershipId, before, input.doctor());
            }
            TeamRow after = find(tenantId, membershipId);
            Map<String, Object> beforeSnapshot = snapshot(before);
            Map<String, Object> afterSnapshot = snapshot(after);
            if (!beforeSnapshot.equals(afterSnapshot)) {
                auditLog.record("TEAM_MEMBER_UPDATED", ENTITY_TYPE, membershipId, beforeSnapshot, afterSnapshot);
            }
            return toView(after);
        });
    }

    /** AC2, AC10: switch a person off and end their sessions. */
    @Transactional
    public TeamMemberView deactivate(UUID tenantId, UUID actorUserId, UUID membershipId) {
        requireOwner(tenantId, actorUserId);
        TeamRow before = find(tenantId, membershipId);
        if (before.status() != MembershipStatus.ACTIVE) {
            return toView(before);
        }
        requireNotLastOwner(tenantId, before);
        return guarded(() -> toView(switchOff(tenantId, actorUserId, before, MembershipStatus.INACTIVE,
                "TEAM_MEMBER_DEACTIVATED")));
    }

    /** AC4: reactivating counts against the plan limit like a new person. Works from switched off and removed. */
    @Transactional
    public TeamMemberView reactivate(UUID tenantId, UUID actorUserId, UUID membershipId) {
        requireOwner(tenantId, actorUserId);
        TeamRow before = find(tenantId, membershipId);
        if (before.status() == MembershipStatus.ACTIVE) {
            return toView(before);
        }
        return guarded(() -> {
            teamRepository.lockTenant();
            requireRoomForOneMore(tenantId);
            teamRepository.updateStatus(membershipId, MembershipStatus.ACTIVE, actorUserId);
            TeamRow after = find(tenantId, membershipId);
            auditLog.record("TEAM_MEMBER_REACTIVATED", ENTITY_TYPE, membershipId, snapshot(before), snapshot(after));
            return toView(after);
        });
    }

    /** Removes access. The row stays so past entries keep the name. */
    @Transactional
    public void remove(UUID tenantId, UUID actorUserId, UUID membershipId) {
        requireOwner(tenantId, actorUserId);
        TeamRow before = find(tenantId, membershipId);
        if (before.status() == MembershipStatus.REMOVED) {
            return;
        }
        requireNotLastOwner(tenantId, before);
        guarded(() -> {
            TeamRow after = switchOff(tenantId, actorUserId, before, MembershipStatus.REMOVED, "TEAM_MEMBER_REMOVED");
            // SAH-31 (ADR-010 D8): a person without access keeps no PIN; same statement and audit as an owner reset.
            staffPinOps.clear(tenantId, actorUserId, before.id());
            return after;
        });
    }

    private TeamRow switchOff(UUID tenantId, UUID actorUserId, TeamRow before, MembershipStatus status,
                               String action) {
        teamRepository.updateStatus(before.id(), status, actorUserId);
        refreshTokenRepository.revokeAllForUserInTenant(tenantId, before.userId(), clock.instant());
        TeamRow after = find(tenantId, before.id());
        auditLog.record(action, ENTITY_TYPE, before.id(), snapshot(before), snapshot(after));
        return after;
    }

    private void requireOwner(UUID tenantId, UUID actorUserId) {
        boolean owner = teamRepository.findByUser(tenantId, actorUserId)
                .map(row -> row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!owner) {
            throw new AccessRefusedException("Only the clinic owner can change the team.");
        }
    }

    private TeamRow find(UUID tenantId, UUID membershipId) {
        return teamRepository.findById(tenantId, membershipId)
                .orElseThrow(() -> new NotFoundException("Team member not found"));
    }

    private void requireRoomForOneMore(UUID tenantId) {
        if (teamRepository.countActive(tenantId) >= PlanLimits.MAX_USERS) {
            throw new PlanLimitException("The free plan allows up to " + PlanLimits.MAX_USERS + " users in total.");
        }
    }

    private void requireNotLastOwner(UUID tenantId, TeamRow row) {
        if (row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE
                && teamRepository.countOtherActiveOwners(tenantId, row.id()) == 0) {
            throw new TeamConflictException(TeamConflictException.Kind.LAST_OWNER);
        }
    }

    private void saveDoctorProfile(UUID tenantId, UUID membershipId, TeamRow current, DoctorInput input) {
        DoctorInput given = input == null ? new DoctorInput(null, null, null, null) : input;
        String specialty = given.specialty() != null ? blankToNull(given.specialty())
                : current == null ? null : current.specialty();
        String degree = given.degree() != null ? blankToNull(given.degree())
                : current == null ? null : current.degree();
        String registrationNo = given.registrationNo() != null ? blankToNull(given.registrationNo())
                : current == null ? null : current.registrationNo();
        long feePaise = given.feeRupees() != null ? given.feeRupees() * 100L
                : current == null ? 0L : current.feePaise();
        doctorProfileRepository.insert(membershipId, tenantId, specialty, feePaise, degree, registrationNo);
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new TeamInputInvalidException("Enter the name");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isDoctorRole(MembershipRole role) {
        return role == MembershipRole.DOCTOR || role == MembershipRole.VISITING_DOCTOR;
    }

    /** No row = never set. SET and LOCKED carry the set time; only LOCKED carries the end of the lock. */
    private static TeamMemberView.PinView pinViewOf(StaffPinRow pin, Integer colleagueTries7d) {
        if (pin == null || !pin.hasPin()) {
            return new TeamMemberView.PinView(PinState.NOT_SET, null, null, colleagueTries7d);
        }
        PinState state = pin.state();
        return new TeamMemberView.PinView(state, pin.setAt(), state == PinState.LOCKED ? pin.lockedUntil() : null,
                colleagueTries7d);
    }

    private static TeamMemberView toView(TeamRow row) {
        return toView(row, null);
    }

    private static TeamMemberView toView(TeamRow row, TeamMemberView.PinView pin) {
        TeamMemberView.DoctorView doctor = isDoctorRole(row.role())
                ? new TeamMemberView.DoctorView(row.specialty(), row.degree(), row.registrationNo(),
                        (int) (row.feePaise() / 100))
                : null;
        return new TeamMemberView(row.id(), row.name(), MobileMask.mask(row.mobile()), row.role(), row.status(),
                row.lastLoginAt(), doctor, pin);
    }

    /** Audit values: the mobile is masked here, and AuditSanitizer masks it again as a second line of defence. */
    private static Map<String, Object> snapshot(TeamRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", row.name());
        values.put("mobile", MobileMask.mask(row.mobile()));
        values.put("role", row.role().name());
        values.put("status", row.status().name());
        if (isDoctorRole(row.role())) {
            Map<String, Object> doctor = new LinkedHashMap<>();
            doctor.put("specialty", row.specialty());
            doctor.put("degree", row.degree());
            doctor.put("registrationNo", row.registrationNo());
            doctor.put("feeRupees", row.feePaise() / 100);
            values.put("doctor", doctor);
        }
        return values;
    }

    /**
     * The database is the backstop for the rules checked above: a second add of the same mobile
     * (23505), a second owner switched off at the same moment (23514 {@code last_owner}) and a
     * deadlock (40P01) all become a clean 409 instead of a 500.
     */
    private <T> T guarded(Supplier<T> body) {
        try {
            return body.get();
        } catch (DataAccessException ex) {
            throw translate(ex);
        }
    }

    static RuntimeException translate(DataAccessException ex) {
        String state = null;
        String message = null;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
                message = sql.getMessage();
            }
        }
        if ("23505".equals(state)) {
            return new TeamConflictException(TeamConflictException.Kind.DUPLICATE_MOBILE);
        }
        if ("23514".equals(state) && message != null && message.contains("last_owner")) {
            return new TeamConflictException(TeamConflictException.Kind.LAST_OWNER);
        }
        if ("40P01".equals(state) || "40001".equals(state) || ex instanceof ConcurrencyFailureException) {
            return new TeamConflictException(TeamConflictException.Kind.CONCURRENT_CHANGE);
        }
        return ex;
    }
}
