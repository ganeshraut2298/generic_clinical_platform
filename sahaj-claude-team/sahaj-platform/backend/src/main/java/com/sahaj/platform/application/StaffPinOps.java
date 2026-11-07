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
 * The short platform transactions of the staff PIN (SAH-31, ADR-010 D5 to D10; SAH-38, ADR-014). {@link StaffPinService}
 * decides the order of the steps and calls these methods from outside any transaction, so the one-time code check and the
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
    static final String COLLEAGUE_RIGHT_AFTER_WRONG = "PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG";
    static final String CAUSED_BY_SELF = "SELF";
    static final String CAUSED_BY_COLLEAGUES = "COLLEAGUES";
    /** ADR-014 D3 (OQ-38-1, DEC-023): wrong tries of one caller on one colleague's PIN allowed in a rolling 7 days. */
    static final int PAIR_WRONG_TRIES_CAP = 5;

    private static final Logger log = LoggerFactory.getLogger(StaffPinOps.class);

    private final TeamRepository teamRepository;
    private final StaffPinRepository staffPinRepository;
    private final StaffPinColleagueEventRepository colleagueEvents;
    private final StaffPinHasher hasher;
    private final AuditLog auditLog;

    public StaffPinOps(TeamRepository teamRepository, StaffPinRepository staffPinRepository,
                       StaffPinColleagueEventRepository colleagueEvents, StaffPinHasher hasher, AuditLog auditLog) {
        this.teamRepository = teamRepository;
        this.staffPinRepository = staffPinRepository;
        this.colleagueEvents = colleagueEvents;
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
        PinCheck blocked = blocked(row);
        PinCheck result = blocked != null ? blocked
                : compare(tenantId, userId, membershipId, row, pin, null, 0).check();
        return done(tenantId, membershipId, result);
    }

    /**
     * What one colleague check decided. {@code wrongTry} is true exactly when the PIN was compared and was wrong,
     * including the wrong try that locked the PIN: those are the tries the caller's in-memory limit counts.
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
     * The colleague check of ADR-014 D3 on the COLLEAGUE's own row, in one transaction: the waiting time is limited
     * (lock 3 s, statement 5 s, D5), the eligibility is checked (the caller has an ACTIVE membership; the colleague is an
     * ACTIVE member of the same clinic with the required role and is not the caller; another clinic's id looks like an
     * unknown id), the colleague's row is locked, then: no PIN, locked, the pair cap (5 wrong tries of this pair in the
     * pair's window; the PIN is not compared and nothing is counted), then the comparison. A wrong try is audited as
     * {@code PLATFORM_PIN_COLLEAGUE_CHECK_FAILED} (ids and the tries left only) and written as a {@code WRONG} event; a
     * lock caused by it also as {@code LOCKED}; a right PIN of a pair that had wrong tries clears the pair. The
     * {@code updated_by} of the changed row is the caller: the person who typed the try. A database failure or timeout
     * propagates (everything rolled back); {@link StaffPinService} turns it into CHECK_FAILED.
     */
    @Transactional
    public ColleagueResult checkColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                          MembershipRole requiredRole, String pin) {
        if (!hasher.isAvailable()) {
            return new ColleagueResult(new PinCheck(PinCheck.Outcome.UNAVAILABLE, 0, null, 0), null, false);
        }
        staffPinRepository.limitWaitingTime();
        Pair pair = resolveColleague(tenantId, callerUserId, colleagueMembershipId, requiredRole).orElse(null);
        if (pair == null) {
            log.info("staff-pin-colleague-check tenantId={} outcome=NOT_ELIGIBLE", tenantId);
            return new ColleagueResult(new PinCheck(PinCheck.Outcome.NOT_ELIGIBLE, 0, null, 0), null, false);
        }
        UUID colleagueId = pair.colleague().id();
        UUID callerMembershipId = pair.caller().id();
        StaffPinRow row = staffPinRepository.find(tenantId, colleagueId, true).orElse(null);
        Decision decision;
        PinCheck blocked = blocked(row);
        if (blocked != null) {
            decision = new Decision(blocked, false);
        } else {
            int pairWrongTries = colleagueEvents.countPairWrongTries(tenantId, colleagueId, callerMembershipId,
                    row.setAt());
            decision = pairWrongTries >= PAIR_WRONG_TRIES_CAP
                    ? new Decision(new PinCheck(PinCheck.Outcome.PAIR_LIMIT, 0, null, 0), false)
                    : compare(tenantId, callerUserId, colleagueId, row, pin, callerMembershipId, pairWrongTries);
        }
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

    /** No PIN or locked: answered before anything is compared or counted (ADR-010 D5, ADR-014 D3 step 5). */
    private static PinCheck blocked(StaffPinRow row) {
        if (row == null || !row.hasPin()) {
            return notSet(row);
        }
        if (row.isLocked()) {
            return locked(row);
        }
        return null;
    }

    /**
     * The comparison and the counting for the own check and the colleague check, on a row that has a PIN and is not
     * locked. {@code checkedBy} is the caller's membership id for a colleague check (added to the lock audits), null for
     * the own check. {@code pairWrongTries} is the pair's count of wrong tries inside its window (colleague check only).
     */
    private Decision compare(UUID tenantId, UUID actorUserId, UUID membershipId, StaffPinRow row, String pin,
                             UUID checkedBy, int pairWrongTries) {
        boolean byColleague = checkedBy != null;
        if (hasher.matches(tenantId, membershipId, pin, row.pinHash())) {
            if (row.failedCount() > 0) {
                staffPinRepository.recordRightPin(tenantId, membershipId, actorUserId);
            }
            if (byColleague && pairWrongTries > 0) {
                colleagueEvents.insertRightAfterWrong(membershipId, checkedBy, actorUserId);
                Map<String, Object> cleared = new LinkedHashMap<>();
                cleared.put("membershipId", membershipId.toString());
                cleared.put("checkedByMembershipId", checkedBy.toString());
                cleared.put("wrongTries", pairWrongTries);
                auditLog.record(COLLEAGUE_RIGHT_AFTER_WRONG, ENTITY_TYPE, membershipId, null, cleared);
            }
            return new Decision(new PinCheck(PinCheck.Outcome.OK, StaffPinRow.MAX_TRIES, null, 0), false);
        }
        if (byColleague) {
            colleagueEvents.insertWrong(membershipId, checkedBy, actorUserId);
        }
        if (row.failedCount() < StaffPinRow.MAX_TRIES - 1) {
            if (byColleague) {
                staffPinRepository.recordColleagueWrongTry(tenantId, membershipId, actorUserId);
            } else {
                staffPinRepository.recordWrongTry(tenantId, membershipId, actorUserId);
            }
            return new Decision(new PinCheck(PinCheck.Outcome.WRONG,
                    StaffPinRow.MAX_TRIES - (row.failedCount() + 1), null, 0), true);
        }
        return new Decision(fifthWrongTry(tenantId, actorUserId, membershipId, row, checkedBy), true);
    }

    /**
     * The fifth wrong try of a run (ADR-014 D6). A run with any colleague try (this try is a colleague check, or the run
     * flag is set: mixed runs too) locks for 15 minutes, leaves the lock count and the window alone, writes a
     * {@code LOCKED} event and never switches the PIN off. A run typed only in the person's own session is the SAH-31
     * lock, and the third such lock in 24 hours switches the PIN off.
     */
    private PinCheck fifthWrongTry(UUID tenantId, UUID userId, UUID membershipId, StaffPinRow row, UUID checkedBy) {
        if (checkedBy != null || row.runHasColleagueTry()) {
            staffPinRepository.lockByColleagues(tenantId, membershipId, userId);
            colleagueEvents.insertLocked(membershipId, userId);
            return lockedAudit(tenantId, membershipId, checkedBy, CAUSED_BY_COLLEAGUES);
        }
        boolean newWindow = row.lockWindowIsNew();
        if (!newWindow && row.lockCount() >= 2) {
            staffPinRepository.switchOffAfterLocks(tenantId, membershipId, userId);
            Map<String, Object> off = snapshot(membershipId, PinState.NOT_SET, "TOO_MANY_LOCKS", null, null);
            off.put("causedBy", CAUSED_BY_SELF);
            auditLog.record(CLEARED_AFTER_LOCKS, ENTITY_TYPE, membershipId,
                    snapshot(membershipId, PinState.LOCKED, null, null, null), off);
            return new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0, PinCheck.NotSetReason.TOO_MANY_LOCKS);
        }
        int newLockCount = newWindow ? 1 : row.lockCount() + 1;
        staffPinRepository.lock(tenantId, membershipId, newLockCount, userId);
        return lockedAudit(tenantId, membershipId, null, CAUSED_BY_SELF);
    }

    private PinCheck lockedAudit(UUID tenantId, UUID membershipId, UUID checkedBy, String causedBy) {
        StaffPinRow locked = staffPinRepository.find(tenantId, membershipId, false)
                .orElseThrow(() -> new IllegalStateException("The staff PIN row disappeared during a lock"));
        Map<String, Object> after = snapshot(membershipId, PinState.LOCKED, null, locked.lockCount(), checkedBy);
        after.put("lockedUntil", locked.lockedUntil() == null ? null
                : locked.lockedUntil().truncatedTo(ChronoUnit.SECONDS).toString());
        after.put("causedBy", causedBy);
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
