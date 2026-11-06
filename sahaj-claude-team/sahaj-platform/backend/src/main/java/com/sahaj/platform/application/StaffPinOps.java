package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short platform transactions of the staff PIN (SAH-31, ADR-010 D5 to D10). {@link StaffPinService} decides the
 * order of the steps and calls these methods from outside any transaction, so the one-time code check and the
 * rate limits never sit inside a write transaction, and a wrong try is committed before the caller's own request
 * fails. Each method here is one READ COMMITTED transaction with the clinic bound.
 *
 * <p>Row lock first ({@code select ... for update}), then decisions on the database clock, then one guarded update
 * and, for a lock, a switch-off, a set or a reset, one audit row with ids and states only: never the PIN, a hash,
 * the pepper, a code, a mobile number or a name. Log lines carry the event key, ids and the outcome only.
 */
@Service
public class StaffPinOps {

    static final String ENTITY_TYPE = "STAFF_PIN";
    static final String SET = "PLATFORM_PIN_SET";
    static final String CHANGED = "PLATFORM_PIN_CHANGED";
    static final String RESET = "PLATFORM_PIN_RESET";
    static final String LOCKED = "PLATFORM_PIN_LOCKED";
    static final String CLEARED_AFTER_LOCKS = "PLATFORM_PIN_CLEARED_AFTER_LOCKS";
    static final String COLLEAGUE_CHECK_FAILED = "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED";

    private static final Logger log = LoggerFactory.getLogger(StaffPinOps.class);

    private final TeamRepository teamRepository;
    private final StaffPinRepository staffPinRepository;
    private final StaffPinHasher hasher;
    private final AuditLog auditLog;

    public StaffPinOps(TeamRepository teamRepository, StaffPinRepository staffPinRepository, StaffPinHasher hasher,
                       AuditLog auditLog) {
        this.teamRepository = teamRepository;
        this.staffPinRepository = staffPinRepository;
        this.hasher = hasher;
        this.auditLog = auditLog;
    }

    /** The signed-in person's own active membership, mobile (full, server side only) and PIN row. */
    public record Own(UUID membershipId, String mobile, StaffPinRow row) {

        /** Redacted: no mobile number. */
        @Override
        public String toString() {
            return "Own[membershipId=" + membershipId + "]";
        }
    }

    /** Reads the person's own membership; absent when they have no ACTIVE membership in the clinic. */
    @Transactional(readOnly = true)
    public Optional<Own> readOwn(UUID tenantId, UUID userId) {
        return teamRepository.findActiveByUser(tenantId, userId)
                .map(member -> new Own(member.id(), member.mobile(),
                        staffPinRepository.find(tenantId, member.id(), false).orElse(null)));
    }

    /** The status screen data for an {@link Own}. */
    public PinStatusView statusOf(Own own, boolean available) {
        StaffPinRow row = own.row();
        String masked = MobileMask.mask(own.mobile());
        if (row == null) {
            return new PinStatusView(available, PinState.NOT_SET, null, null, null, null, masked);
        }
        PinState state = row.state();
        return new PinStatusView(available, state, row.hasPin() ? row.setAt() : null,
                state == PinState.LOCKED ? row.lockedUntil() : null, row.triesLeft(),
                state == PinState.NOT_SET ? row.notSetReason() : null, masked);
    }

    /**
     * The check of ADR-010 D5 and D6: lock the row, decide on the database clock, compare, count. Runs in its own
     * transaction (the caller guarantees none is active).
     */
    @Transactional
    public PinCheck check(UUID tenantId, UUID userId, String pin) {
        if (!hasher.isAvailable()) {
            return new PinCheck(PinCheck.Outcome.UNAVAILABLE, 0, null, 0);
        }
        TeamRow member = teamRepository.findActiveByUser(tenantId, userId).orElse(null);
        if (member == null) {
            log.info("staff-pin-check tenantId={} outcome=NOT_SET reason=no-active-membership", tenantId);
            return notSet(null);
        }
        UUID membershipId = member.id();
        StaffPinRow row = staffPinRepository.find(tenantId, membershipId, true).orElse(null);
        return done(tenantId, membershipId, decide(tenantId, userId, membershipId, row, pin, null).check());
    }

    /** An ACTIVE member who may be named as a second checker: membership id and user id (never name or mobile). */
    public record Colleague(UUID membershipId, UUID userId) {
    }

    /**
     * What one colleague check decided. {@code wrongTry} is true exactly when the PIN was compared and was wrong,
     * including the wrong try that locked the PIN or switched it off: those are the tries the limits count.
     * {@code colleagueUserId} is set only for OK. Numbers, states and ids only.
     */
    public record ColleagueResult(PinCheck check, UUID colleagueUserId, boolean wrongTry) {

        /** Outcome and flag only: no ids, no PIN. */
        @Override
        public String toString() {
            return "ColleagueResult[outcome=" + check.outcome() + ", wrongTry=" + wrongTry + "]";
        }
    }

    /**
     * Who may be named (ADR-011 D7): the caller has an ACTIVE membership in the clinic, and the colleague is an ACTIVE
     * member of the same clinic (another clinic's id is simply absent) with the required role, who is not the caller.
     * Reads only; used to find the colleague's user id for the colleague's own failed-check limit before any hash.
     */
    @Transactional(readOnly = true)
    public Optional<Colleague> findEligibleColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                                     MembershipRole requiredRole) {
        return resolveColleague(tenantId, callerUserId, colleagueMembershipId, requiredRole)
                .map(pair -> new Colleague(pair.colleague().id(), pair.colleague().userId()));
    }

    /**
     * The check of ADR-011 D7: the eligibility again under the same transaction, then the row lock, the clock
     * decision and the comparison of ADR-010 D5 on the COLLEAGUE's own row. A wrong try is audited as
     * {@code PLATFORM_PIN_COLLEAGUE_CHECK_FAILED} (ids and the tries left only); a lock or a switch-off also carries
     * {@code checkedByMembershipId}. Runs in its own transaction (the caller guarantees none is active). The
     * {@code updated_by} of the changed row is the caller: the person who typed the try.
     */
    @Transactional
    public ColleagueResult checkColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                          MembershipRole requiredRole, String pin) {
        if (!hasher.isAvailable()) {
            return new ColleagueResult(new PinCheck(PinCheck.Outcome.UNAVAILABLE, 0, null, 0), null, false);
        }
        Pair pair = resolveColleague(tenantId, callerUserId, colleagueMembershipId, requiredRole).orElse(null);
        if (pair == null) {
            log.info("staff-pin-colleague-check tenantId={} outcome=NOT_ELIGIBLE", tenantId);
            return new ColleagueResult(new PinCheck(PinCheck.Outcome.NOT_ELIGIBLE, 0, null, 0), null, false);
        }
        UUID colleagueId = pair.colleague().id();
        UUID callerMembershipId = pair.caller().id();
        StaffPinRow row = staffPinRepository.find(tenantId, colleagueId, true).orElse(null);
        Decision decision = decide(tenantId, callerUserId, colleagueId, row, pin, callerMembershipId);
        PinCheck result = decision.check();
        if (decision.wrongTry()) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("membershipId", colleagueId.toString());
            failed.put("checkedByMembershipId", callerMembershipId.toString());
            failed.put("triesLeft", result.outcome() == PinCheck.Outcome.WRONG ? result.triesLeft() : 0);
            auditLog.record(COLLEAGUE_CHECK_FAILED, ENTITY_TYPE, colleagueId, null, failed);
        }
        log.info("staff-pin-colleague-check tenantId={} membershipId={} checkedByMembershipId={} outcome={}", tenantId,
                colleagueId, callerMembershipId, result.outcome());
        return new ColleagueResult(result, result.outcome() == PinCheck.Outcome.OK ? pair.colleague().userId() : null,
                decision.wrongTry());
    }

    private record Pair(TeamRow caller, TeamRow colleague) {
    }

    private Optional<Pair> resolveColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                            MembershipRole requiredRole) {
        if (colleagueMembershipId == null) {
            return Optional.empty();
        }
        TeamRow caller = teamRepository.findActiveByUser(tenantId, callerUserId).orElse(null);
        if (caller == null) {
            return Optional.empty();
        }
        TeamRow colleague = teamRepository.findById(tenantId, colleagueMembershipId).orElse(null);
        if (colleague == null || colleague.status() != MembershipStatus.ACTIVE || colleague.role() != requiredRole
                || colleague.userId().equals(callerUserId) || colleague.id().equals(caller.id())) {
            return Optional.empty();
        }
        return Optional.of(new Pair(caller, colleague));
    }

    /** The decision on one locked row: {@code wrongTry} is true when the PIN was compared and was wrong. */
    private record Decision(PinCheck check, boolean wrongTry) {
    }

    /**
     * The shared rules of ADR-010 D5 for the own check and the colleague check. {@code checkedBy} is the caller's
     * membership id for a colleague check (added to the lock audits), null for the own check.
     */
    private Decision decide(UUID tenantId, UUID actorUserId, UUID membershipId, StaffPinRow row, String pin,
                            UUID checkedBy) {
        if (row == null || !row.hasPin()) {
            return new Decision(notSet(row), false);
        }
        if (row.isLocked()) {
            return new Decision(locked(row), false);
        }
        if (hasher.matches(tenantId, membershipId, pin, row.pinHash())) {
            if (row.failedCount() > 0) {
                staffPinRepository.recordRightPin(tenantId, membershipId, actorUserId);
            }
            return new Decision(new PinCheck(PinCheck.Outcome.OK, StaffPinRow.MAX_TRIES, null, 0), false);
        }
        if (row.failedCount() < StaffPinRow.MAX_TRIES - 1) {
            staffPinRepository.recordWrongTry(tenantId, membershipId, actorUserId);
            return new Decision(new PinCheck(PinCheck.Outcome.WRONG,
                    StaffPinRow.MAX_TRIES - (row.failedCount() + 1), null, 0), true);
        }
        return new Decision(fifthWrongTry(tenantId, actorUserId, membershipId, row, checkedBy), true);
    }

    private PinCheck fifthWrongTry(UUID tenantId, UUID userId, UUID membershipId, StaffPinRow row, UUID checkedBy) {
        boolean newWindow = row.lockWindowIsNew();
        if (!newWindow && row.lockCount() >= 2) {
            staffPinRepository.switchOffAfterLocks(tenantId, membershipId, userId);
            auditLog.record(CLEARED_AFTER_LOCKS, ENTITY_TYPE, membershipId,
                    snapshot(membershipId, PinState.LOCKED, null, null, checkedBy),
                    snapshot(membershipId, PinState.NOT_SET, "TOO_MANY_LOCKS", null, checkedBy));
            return new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0, PinCheck.NotSetReason.TOO_MANY_LOCKS);
        }
        int newLockCount = newWindow ? 1 : row.lockCount() + 1;
        staffPinRepository.lock(tenantId, membershipId, newLockCount, userId);
        StaffPinRow locked = staffPinRepository.find(tenantId, membershipId, false)
                .orElseThrow(() -> new IllegalStateException("The staff PIN row disappeared during a lock"));
        Map<String, Object> after = snapshot(membershipId, PinState.LOCKED, null, locked.lockCount(), checkedBy);
        after.put("lockedUntil", locked.lockedUntil() == null ? null
                : locked.lockedUntil().truncatedTo(ChronoUnit.SECONDS).toString());
        auditLog.record(LOCKED, ENTITY_TYPE, membershipId, snapshot(membershipId, PinState.SET, null, null, checkedBy),
                after);
        return locked(locked);
    }

    /** Saves a new PIN for the person: first PIN, change, forgot, or after a reset or switch-off (ADR-010 D7). */
    @Transactional
    public PinStatusView commitSet(UUID tenantId, UUID userId, String newPin) {
        TeamRow member = teamRepository.findActiveByUser(tenantId, userId)
                .orElseThrow(StaffPinOps::notAllowed);
        UUID membershipId = member.id();
        StaffPinRow before = staffPinRepository.find(tenantId, membershipId, true).orElse(null);
        String hash = hasher.hash(tenantId, membershipId, newPin);
        if (before == null) {
            staffPinRepository.insert(membershipId, hash, StaffPinHasher.PEPPER_VERSION, userId);
        } else {
            staffPinRepository.replace(tenantId, membershipId, hash, StaffPinHasher.PEPPER_VERSION, userId);
        }
        boolean hadPin = before != null && before.hasPin();
        Map<String, Object> beforeSnapshot = before != null && hadPin
                ? snapshot(membershipId, before.state(), null, null) : null;
        auditLog.record(hadPin ? CHANGED : SET, ENTITY_TYPE, membershipId, beforeSnapshot,
                snapshot(membershipId, PinState.SET, null, null));
        log.info("staff-pin-set tenantId={} membershipId={} first={}", tenantId, membershipId, !hadPin);
        StaffPinRow after = staffPinRepository.find(tenantId, membershipId, false)
                .orElseThrow(() -> new IllegalStateException("The staff PIN row was not saved"));
        return statusOf(new Own(membershipId, member.mobile(), after), true);
    }

    /**
     * Clears a member's PIN (owner reset, or removal of access, ADR-010 D8). Joins the caller's transaction. Nothing
     * is written when there is no PIN. Returns true when a PIN was cleared.
     */
    @Transactional
    public boolean clear(UUID tenantId, UUID actorUserId, UUID membershipId) {
        StaffPinRow row = staffPinRepository.find(tenantId, membershipId, true).orElse(null);
        if (row == null || !row.hasPin()) {
            return false;
        }
        staffPinRepository.reset(tenantId, membershipId, actorUserId);
        auditLog.record(RESET, ENTITY_TYPE, membershipId, snapshot(membershipId, row.state(), null, null),
                snapshot(membershipId, PinState.NOT_SET, "RESET_BY_OWNER", null));
        log.info("staff-pin-reset tenantId={} membershipId={}", tenantId, membershipId);
        return true;
    }

    private static PinCheck notSet(StaffPinRow row) {
        PinCheck.NotSetReason reason = PinCheck.NotSetReason.NEVER_SET;
        if (row != null && "RESET_BY_OWNER".equals(row.notSetReason())) {
            reason = PinCheck.NotSetReason.RESET_BY_OWNER;
        } else if (row != null && "TOO_MANY_LOCKS".equals(row.notSetReason())) {
            reason = PinCheck.NotSetReason.TOO_MANY_LOCKS;
        }
        return new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0, reason);
    }

    private static PinCheck locked(StaffPinRow row) {
        long seconds = Math.max(1, (Duration.between(row.dbNow(), row.lockedUntil()).toMillis() + 999) / 1000);
        return new PinCheck(PinCheck.Outcome.LOCKED, 0, row.lockedUntil(), seconds);
    }

    private PinCheck done(UUID tenantId, UUID membershipId, PinCheck result) {
        log.info("staff-pin-check tenantId={} membershipId={} outcome={}", tenantId, membershipId, result.outcome());
        return result;
    }


    private static Map<String, Object> snapshot(UUID membershipId, PinState state, String reason, Integer lockCount) {
        return snapshot(membershipId, state, reason, lockCount, null);
    }

    /** {@code checkedBy}: the caller's membership id when the try came from a colleague check (ADR-011 D7). */
    private static Map<String, Object> snapshot(UUID membershipId, PinState state, String reason, Integer lockCount,
                                                UUID checkedBy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("membershipId", membershipId.toString());
        if (checkedBy != null) {
            values.put("checkedByMembershipId", checkedBy.toString());
        }
        values.put("pinState", state.name());
        if (reason != null) {
            values.put("reason", reason);
        }
        if (lockCount != null) {
            values.put("lockCount", lockCount);
        }
        return values;
    }

    private static ForbiddenException notAllowed() {
        return new ForbiddenException("forbidden", "Not allowed", "You do not have access to this.");
    }
}
