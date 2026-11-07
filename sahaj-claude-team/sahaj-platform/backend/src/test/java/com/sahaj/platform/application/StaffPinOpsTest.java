package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.application.StaffPinOps.Own;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-31, ADR-010 D5 to D10: the check, the lock, the third-lock switch-off, set, reset and the audit rows. */
class StaffPinOpsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");
    private static final String JWT_SECRET = "a-jwt-secret-that-is-at-least-32-characters-long";
    private static final String PIN = "4821";

    private TeamRepository teamRepository;
    private StaffPinRepository pins;
    private StaffPinColleagueEventRepository events;
    private AuditLog auditLog;
    private StaffPinHasher hasher;
    private StaffPinOps ops;
    private String goodHash;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        pins = mock(StaffPinRepository.class);
        events = mock(StaffPinColleagueEventRepository.class);
        auditLog = mock(AuditLog.class);
        byte[] pepper = new byte[32];
        new SecureRandom().nextBytes(pepper);
        hasher = StaffPinHasher.create(Base64.getEncoder().encodeToString(pepper), JWT_SECRET, 4);
        ops = new StaffPinOps(teamRepository, pins, events, hasher, auditLog);
        goodHash = hasher.hash(TENANT, MEMBER, PIN);
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.of(member()));
    }

    private static TeamRow member() {
        return new TeamRow(MEMBER, USER, "Rahul", "9822077410", MembershipRole.PHARMACIST, MembershipStatus.ACTIVE,
                null, false, null, null, null, 0L);
    }

    private StaffPinRow row(String hash, int failed, Instant lockedUntil, int lockCount, Instant window,
                            String reason) {
        return new StaffPinRow(MEMBER, hash, 1, failed, lockedUntil, lockCount, window,
                hash == null ? null : NOW.minusSeconds(3600), reason, null, 4L, NOW);
    }

    private void rowIs(StaffPinRow row) {
        when(pins.find(TENANT, MEMBER, true)).thenReturn(Optional.ofNullable(row));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object>[] auditedMaps(String action) {
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), eq("STAFF_PIN"), eq(MEMBER), before.capture(), after.capture());
        return new Map[] {before.getValue(), after.getValue()};
    }

    // ---- check ----

    @Test
    void withNoPepperNothingIsReadOrCounted() {
        StaffPinOps off = new StaffPinOps(teamRepository, pins, events, StaffPinHasher.create(null, JWT_SECRET, 4), auditLog);

        assertThat(off.check(TENANT, USER, PIN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verifyNoInteractions(pins, auditLog);
    }

    @Test
    void aPersonWithoutAnActiveMembershipIsNotSet() {
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.empty());

        PinCheck result = ops.check(TENANT, USER, PIN);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_SET);
        verifyNoInteractions(pins);
    }

    @Test
    void noRowAndAnOffRowAreNotSetWithTheirReason() {
        rowIs(null);
        assertThat(ops.check(TENANT, USER, PIN)).extracting(PinCheck::outcome, PinCheck::notSetReason)
                .containsExactly(Outcome.NOT_SET, NotSetReason.NEVER_SET);

        rowIs(row(null, 0, null, 0, null, "RESET_BY_OWNER"));
        assertThat(ops.check(TENANT, USER, PIN).notSetReason()).isEqualTo(NotSetReason.RESET_BY_OWNER);

        rowIs(row(null, 0, null, 0, null, "TOO_MANY_LOCKS"));
        assertThat(ops.check(TENANT, USER, PIN).notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);

        rowIs(row(null, 0, null, 0, null, null));
        assertThat(ops.check(TENANT, USER, PIN).notSetReason()).isEqualTo(NotSetReason.NEVER_SET);
        verify(pins, never()).recordWrongTry(any(), any(), any());
    }

    @Test
    void theRowIsLockedBeforeItIsRead() {
        rowIs(row(goodHash, 0, null, 0, null, null));

        ops.check(TENANT, USER, PIN);

        verify(pins).find(TENANT, MEMBER, true);
    }

    @Test
    void whileLockedEvenTheRightPinAnswersLockedAndCountsNothing() {
        rowIs(row(goodHash, 0, NOW.plusSeconds(840), 1, NOW.minusSeconds(60), null));

        PinCheck result = ops.check(TENANT, USER, PIN);

        assertThat(result.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(result.lockedUntil()).isEqualTo(NOW.plusSeconds(840));
        assertThat(result.retryAfterSeconds()).isEqualTo(840);
        verify(pins, never()).recordWrongTry(any(), any(), any());
        verify(pins, never()).recordRightPin(any(), any(), any());
        verify(pins, never()).lock(any(), any(), anyInt(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theRetryWaitIsRoundedUpToWholeSecondsAndAtLeastOne() {
        rowIs(row(goodHash, 0, NOW.plusMillis(1500), 1, NOW.minusSeconds(60), null));
        assertThat(ops.check(TENANT, USER, PIN).retryAfterSeconds()).isEqualTo(2);
        rowIs(row(goodHash, 0, NOW.plusMillis(1), 1, NOW.minusSeconds(60), null));
        assertThat(ops.check(TENANT, USER, PIN).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void aLockThatEndedIsTreatedAsNotLocked() {
        rowIs(row(goodHash, 0, NOW.minusSeconds(1), 1, NOW.minusSeconds(900), null));

        assertThat(ops.check(TENANT, USER, PIN).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void aRightPinWithNoWrongTriesWritesNothing() {
        rowIs(row(goodHash, 0, null, 0, null, null));

        PinCheck result = ops.check(TENANT, USER, PIN);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.triesLeft()).isEqualTo(5);
        verify(pins, never()).recordRightPin(any(), any(), any());
        verify(pins, never()).recordWrongTry(any(), any(), any());
    }

    @Test
    void aRightPinAfterWrongTriesResetsTheCountButNotTheLockCount() {
        rowIs(row(goodHash, 3, null, 1, NOW.minusSeconds(3000), null));

        assertThat(ops.check(TENANT, USER, PIN).outcome()).isEqualTo(Outcome.OK);

        verify(pins).recordRightPin(TENANT, MEMBER, USER);
        verify(pins, never()).lock(any(), any(), anyInt(), any());
    }

    @Test
    void eachWrongTryIsCountedAndReportsTheTriesLeft() {
        for (int failed = 0; failed <= 3; failed++) {
            rowIs(row(goodHash, failed, null, 0, null, null));

            PinCheck result = ops.check(TENANT, USER, "9999");

            assertThat(result.outcome()).as("failed=" + failed).isEqualTo(Outcome.WRONG);
            assertThat(result.triesLeft()).as("failed=" + failed).isEqualTo(4 - failed);
        }
        verify(pins, org.mockito.Mockito.times(4)).recordWrongTry(TENANT, MEMBER, USER);
    }

    @Test
    void aMalformedOrMissingPinIsAWrongTryToo() {
        for (String bad : new String[] {null, "", "abcd", "48211"}) {
            rowIs(row(goodHash, 0, null, 0, null, null));
            assertThat(ops.check(TENANT, USER, bad).outcome()).as("[%s]", bad).isEqualTo(Outcome.WRONG);
        }
        verify(pins, org.mockito.Mockito.times(4)).recordWrongTry(TENANT, MEMBER, USER);
    }

    @Test
    void theFifthWrongTryInANewWindowLocksForFifteenMinutesAndIsAudited() {
        StaffPinRow before = row(goodHash, 4, null, 0, null, null);
        Instant lockedUntil = NOW.plusSeconds(900);
        StaffPinRow after = new StaffPinRow(MEMBER, goodHash, 1, 0, lockedUntil, 1, NOW, NOW.minusSeconds(3600),
                null, null, 5L, NOW);
        when(pins.find(TENANT, MEMBER, true)).thenReturn(Optional.of(before));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(after));

        PinCheck result = ops.check(TENANT, USER, "9999");

        assertThat(result.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(result.lockedUntil()).isEqualTo(lockedUntil);
        assertThat(result.retryAfterSeconds()).isEqualTo(900);
        verify(pins).lock(TENANT, MEMBER, 1, USER);
        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_LOCKED");
        assertThat(audited[0]).containsEntry("pinState", "SET").containsEntry("membershipId", MEMBER.toString());
        assertThat(audited[1]).containsEntry("pinState", "LOCKED").containsEntry("lockCount", 1)
                .containsEntry("lockedUntil", "2026-11-06T06:15:00Z");
    }

    @Test
    void aSecondLockInsideTheWindowCountsUp() {
        StaffPinRow before = row(goodHash, 4, null, 1, NOW.minusSeconds(7200), null);
        StaffPinRow after = new StaffPinRow(MEMBER, goodHash, 1, 0, NOW.plusSeconds(900), 2, NOW.minusSeconds(7200),
                NOW.minusSeconds(3600), null, null, 5L, NOW);
        when(pins.find(TENANT, MEMBER, true)).thenReturn(Optional.of(before));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(after));

        assertThat(ops.check(TENANT, USER, "9999").outcome()).isEqualTo(Outcome.LOCKED);

        verify(pins).lock(TENANT, MEMBER, 2, USER);
        assertThat(auditedMaps("PLATFORM_PIN_LOCKED")[1]).containsEntry("lockCount", 2);
    }

    @Test
    void aLockAfterThe24HourWindowStartsOverAtOne() {
        StaffPinRow before = row(goodHash, 4, null, 2, NOW.minusSeconds(25 * 3600), null);
        StaffPinRow after = new StaffPinRow(MEMBER, goodHash, 1, 0, NOW.plusSeconds(900), 1, NOW,
                NOW.minusSeconds(3600), null, null, 5L, NOW);
        when(pins.find(TENANT, MEMBER, true)).thenReturn(Optional.of(before));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(after));

        assertThat(ops.check(TENANT, USER, "9999").outcome()).isEqualTo(Outcome.LOCKED);

        verify(pins).lock(TENANT, MEMBER, 1, USER);
        verify(pins, never()).switchOffAfterLocks(any(), any(), any());
    }

    @Test
    void theThirdLockInTwentyFourHoursSwitchesThePinOff() {
        rowIs(row(goodHash, 4, null, 2, NOW.minusSeconds(3600), null));

        PinCheck result = ops.check(TENANT, USER, "9999");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(result.notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
        verify(pins).switchOffAfterLocks(TENANT, MEMBER, USER);
        verify(pins, never()).lock(any(), any(), anyInt(), any());
        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_CLEARED_AFTER_LOCKS");
        assertThat(audited[0]).containsEntry("pinState", "LOCKED");
        assertThat(audited[1]).containsEntry("pinState", "NOT_SET").containsEntry("reason", "TOO_MANY_LOCKS");
    }

    @Test
    void noAuditRowEverCarriesAPinAHashOrAMobile() {
        rowIs(row(goodHash, 4, null, 2, NOW.minusSeconds(3600), null));
        ops.check(TENANT, USER, "9999");

        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_CLEARED_AFTER_LOCKS");
        for (Map<String, Object> values : audited) {
            assertThat(values.toString()).doesNotContain("$2a$").doesNotContain(goodHash).doesNotContain("9999")
                    .doesNotContain(PIN).doesNotContain("9822077410").doesNotContain("Rahul");
        }
    }

    @Test
    void anOwnCheckNeverAddsCheckedByMembershipIdToItsLockAudits() {
        rowIs(row(goodHash, 4, null, 2, NOW.minusSeconds(3600), null));
        ops.check(TENANT, USER, "9999");

        for (Map<String, Object> values : auditedMaps("PLATFORM_PIN_CLEARED_AFTER_LOCKS")) {
            assertThat(values).doesNotContainKey("checkedByMembershipId");
        }
        verify(auditLog, never()).record(eq("PLATFORM_PIN_COLLEAGUE_CHECK_FAILED"), anyString(), any(), any(), any());
    }

    // ---- the run flags of the own check (SAH-38, ADR-014 D6) ----

    private StaffPinRow flagged(int failed, Instant lockedUntil, int lockCount, Instant window, boolean runFlag,
                                boolean lockFlag) {
        return new StaffPinRow(MEMBER, goodHash, 1, failed, lockedUntil, lockCount, window, NOW.minusSeconds(3600),
                null, null, runFlag, lockFlag, 4L, NOW);
    }

    @Test
    void anOwnWrongTryInsideARunWithAColleagueTryIsAnOwnWrongTryAndLeavesTheFlagAlone() {
        rowIs(flagged(2, null, 0, null, true, false));

        assertThat(ops.check(TENANT, USER, "9999").outcome()).isEqualTo(Outcome.WRONG);

        verify(pins).recordWrongTry(TENANT, MEMBER, USER);
        verify(pins, never()).recordColleagueWrongTry(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void anOwnFifthTryAfterAColleagueTryIsAColleagueLockThatNeverSwitchesThePinOff() {
        // a mixed run counts as colleague-caused (OQ-38-4), even when two own locks are already inside the window
        rowIs(flagged(4, null, 2, NOW.minusSeconds(3600), true, false));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(new StaffPinRow(MEMBER, goodHash, 1, 0,
                NOW.plusSeconds(900), 2, NOW.minusSeconds(3600), NOW.minusSeconds(3600), null, null, false, true, 5L,
                NOW)));

        PinCheck result = ops.check(TENANT, USER, "9999");

        assertThat(result.outcome()).isEqualTo(Outcome.LOCKED);
        verify(pins).lockByColleagues(TENANT, MEMBER, USER);
        verify(events).insertLocked(MEMBER, USER);
        verify(pins, never()).lock(any(), any(), anyInt(), any());
        verify(pins, never()).switchOffAfterLocks(any(), any(), any());
        verify(events, never()).insertWrong(any(), any(), any());
        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_LOCKED");
        assertThat(audited[1]).containsEntry("causedBy", "COLLEAGUES").containsEntry("lockCount", 2)
                .doesNotContainKey("checkedByMembershipId");
    }

    @Test
    void anOwnFifthTryOfAnOwnRunIsTheSah31LockAuditedAsCausedBySelf() {
        StaffPinRow before = flagged(4, null, 0, null, false, true);
        when(pins.find(TENANT, MEMBER, true)).thenReturn(Optional.of(before));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(new StaffPinRow(MEMBER, goodHash, 1, 0,
                NOW.plusSeconds(900), 1, NOW, NOW.minusSeconds(3600), null, null, 5L, NOW)));

        assertThat(ops.check(TENANT, USER, "9999").outcome()).isEqualTo(Outcome.LOCKED);

        verify(pins).lock(TENANT, MEMBER, 1, USER);
        verify(pins, never()).lockByColleagues(any(), any(), any());
        verifyNoInteractions(events);
        assertThat(auditedMaps("PLATFORM_PIN_LOCKED")[1]).containsEntry("causedBy", "SELF");
    }

    @Test
    void theThirdOwnLockStillSwitchesThePinOffAndIsAuditedAsCausedBySelf() {
        // earlier colleague locks do not count: lock_count 2 here came from two own-session locks
        rowIs(flagged(4, null, 2, NOW.minusSeconds(3600), false, true));

        PinCheck result = ops.check(TENANT, USER, "9999");

        assertThat(result.notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
        verify(pins).switchOffAfterLocks(TENANT, MEMBER, USER);
        assertThat(auditedMaps("PLATFORM_PIN_CLEARED_AFTER_LOCKS")[1]).containsEntry("causedBy", "SELF");
    }

    @Test
    void aRightOwnPinAfterAColleagueTryResetsTheRunThroughTheRightPinStatement() {
        rowIs(flagged(2, null, 0, null, true, false));

        assertThat(ops.check(TENANT, USER, PIN).outcome()).isEqualTo(Outcome.OK);

        verify(pins).recordRightPin(TENANT, MEMBER, USER);
        verifyNoInteractions(events);
    }

    // ---- colleague check (SAH-32, ADR-011 D7; SAH-38, ADR-014 D3 to D6) ----

    private static final UUID CALLER_USER = UUID.randomUUID();
    private static final UUID CALLER_MEMBER = UUID.randomUUID();
    private static final UUID COLLEAGUE_USER = UUID.randomUUID();
    private static final UUID COLLEAGUE_MEMBER = UUID.randomUUID();

    private static TeamRow teamRow(UUID id, UUID userId, String name, MembershipRole role, MembershipStatus status) {
        return new TeamRow(id, userId, name, "9822077411", role, status, null, false, null, null, null, 0L);
    }

    private void colleagueSetUp(MembershipRole colleagueRole, MembershipStatus colleagueStatus) {
        when(teamRepository.findActiveByUser(TENANT, CALLER_USER))
                .thenReturn(Optional.of(teamRow(CALLER_MEMBER, CALLER_USER, "Sunita", MembershipRole.NURSE,
                        MembershipStatus.ACTIVE)));
        when(teamRepository.findById(TENANT, COLLEAGUE_MEMBER))
                .thenReturn(Optional.of(teamRow(COLLEAGUE_MEMBER, COLLEAGUE_USER, "Anita", colleagueRole,
                        colleagueStatus)));
    }

    private void colleagueSetUp() {
        colleagueSetUp(MembershipRole.NURSE, MembershipStatus.ACTIVE);
    }

    private StaffPinRow colleagueRow(String hash, int failed, Instant lockedUntil, int lockCount, Instant window,
                                     String reason) {
        return new StaffPinRow(COLLEAGUE_MEMBER, hash, 1, failed, lockedUntil, lockCount, window,
                hash == null ? null : NOW.minusSeconds(3600), reason, null, 4L, NOW);
    }

    private StaffPinRow colleagueRow(int failed, int lockCount, Instant window, boolean runFlag, boolean lockFlag) {
        return new StaffPinRow(COLLEAGUE_MEMBER, colleagueHash(), 1, failed, null, lockCount, window,
                NOW.minusSeconds(3600), null, null, runFlag, lockFlag, 4L, NOW);
    }

    private String colleagueHash() {
        return hasher.hash(TENANT, COLLEAGUE_MEMBER, PIN);
    }

    private void colleagueRowIs(StaffPinRow row) {
        when(pins.find(TENANT, COLLEAGUE_MEMBER, true)).thenReturn(Optional.ofNullable(row));
    }

    /** The pair's wrong tries inside its window, as the event table counts them. */
    private void pairHas(int wrongTries) {
        when(events.countPairWrongTries(eq(TENANT), eq(COLLEAGUE_MEMBER), eq(CALLER_MEMBER), any()))
                .thenReturn(wrongTries);
    }

    private StaffPinOps.ColleagueResult checkColleague(String pin) {
        return ops.checkColleague(TENANT, CALLER_USER, COLLEAGUE_MEMBER, MembershipRole.NURSE, pin);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object>[] auditedForColleague(String action) {
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), eq("STAFF_PIN"), eq(COLLEAGUE_MEMBER), before.capture(), after.capture());
        return new Map[] {before.getValue(), after.getValue()};
    }

    private void lockedRowReadBack(int lockCount) {
        when(pins.find(TENANT, COLLEAGUE_MEMBER, false)).thenReturn(Optional.of(new StaffPinRow(COLLEAGUE_MEMBER,
                colleagueHash(), 1, 0, NOW.plusSeconds(900), lockCount, NOW.minusSeconds(60), NOW.minusSeconds(3600),
                null, null, false, true, 5L, NOW)));
    }

    @Test
    void anIneligibleColleagueIsNotEligibleForEveryReasonAndNoPinRowIsReadOrWritten() {
        UUID unknown = UUID.randomUUID();
        when(teamRepository.findById(TENANT, unknown)).thenReturn(Optional.empty());
        colleagueSetUp();

        // another clinic's or an unknown id, and no id at all
        assertThat(ops.checkColleague(TENANT, CALLER_USER, unknown, MembershipRole.NURSE, PIN).check().outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);
        assertThat(ops.checkColleague(TENANT, CALLER_USER, null, MembershipRole.NURSE, PIN).check().outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);
        // a colleague who is not active, or has another role
        for (MembershipStatus status : MembershipStatus.values()) {
            if (status != MembershipStatus.ACTIVE) {
                colleagueSetUp(MembershipRole.NURSE, status);
                assertThat(checkColleague(PIN).check().outcome()).as(String.valueOf(status))
                        .isEqualTo(Outcome.NOT_ELIGIBLE);
            }
        }
        for (MembershipRole role : MembershipRole.values()) {
            if (role != MembershipRole.NURSE) {
                colleagueSetUp(role, MembershipStatus.ACTIVE);
                assertThat(checkColleague(PIN).check().outcome()).as(String.valueOf(role))
                        .isEqualTo(Outcome.NOT_ELIGIBLE);
            }
        }
        // the caller named as their own colleague, by membership id and by user id
        when(teamRepository.findById(TENANT, CALLER_MEMBER)).thenReturn(Optional.of(
                teamRow(CALLER_MEMBER, CALLER_USER, "Sunita", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        assertThat(ops.checkColleague(TENANT, CALLER_USER, CALLER_MEMBER, MembershipRole.NURSE, PIN).check().outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);
        when(teamRepository.findById(TENANT, COLLEAGUE_MEMBER)).thenReturn(Optional.of(
                teamRow(COLLEAGUE_MEMBER, CALLER_USER, "Sunita", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        assertThat(checkColleague(PIN).check().outcome()).isEqualTo(Outcome.NOT_ELIGIBLE);
        // a caller without an active membership
        colleagueSetUp();
        when(teamRepository.findActiveByUser(TENANT, CALLER_USER)).thenReturn(Optional.empty());
        StaffPinOps.ColleagueResult result = checkColleague(PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.NOT_ELIGIBLE);
        assertThat(result.wrongTry()).isFalse();
        assertThat(result.colleagueUserId()).isNull();
        verify(pins, never()).find(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void withNoPepperTheColleagueCheckReadsNothingAndCountsNothing() {
        StaffPinOps off = new StaffPinOps(teamRepository, pins, events, StaffPinHasher.create(null, JWT_SECRET, 4),
                auditLog);
        colleagueSetUp();

        StaffPinOps.ColleagueResult result = off.checkColleague(TENANT, CALLER_USER, COLLEAGUE_MEMBER,
                MembershipRole.NURSE, PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.wrongTry()).isFalse();
        verifyNoInteractions(pins, events, auditLog);
    }

    @Test
    void theWaitingTimeIsLimitedBeforeTheColleaguesRowIsLockedAndTheCallersOwnRowIsNeverTouched() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        pairHas(0);

        checkColleague(PIN);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(pins, events);
        order.verify(pins).limitWaitingTime();
        order.verify(pins).find(TENANT, COLLEAGUE_MEMBER, true);
        order.verify(events).countPairWrongTries(eq(TENANT), eq(COLLEAGUE_MEMBER), eq(CALLER_MEMBER), any());
        verify(pins, never()).find(eq(TENANT), eq(CALLER_MEMBER), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void thePairWindowStartsAtTheTimeTheCurrentPinWasSet() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        pairHas(0);

        checkColleague(PIN);

        verify(events).countPairWrongTries(TENANT, COLLEAGUE_MEMBER, CALLER_MEMBER, NOW.minusSeconds(3600));
    }

    @Test
    void aRightColleaguePinReturnsTheColleaguesUserIdAndWritesNothingWhenThereWereNoWrongTries() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        pairHas(0);

        StaffPinOps.ColleagueResult result = checkColleague(PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.OK);
        assertThat(result.colleagueUserId()).isEqualTo(COLLEAGUE_USER);
        assertThat(result.wrongTry()).isFalse();
        verify(pins, never()).recordRightPin(any(), any(), any());
        verify(pins, never()).recordWrongTry(any(), any(), any());
        verify(events, never()).insertRightAfterWrong(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aRightColleaguePinResetsTheRowsWrongTriesButWritesNoMarkerWhenThePairHadNone() {
        // the 3 wrong tries on the row came from other callers: this pair is clean
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 3, null, 1, NOW.minusSeconds(3000), null));
        pairHas(0);

        assertThat(checkColleague(PIN).check().outcome()).isEqualTo(Outcome.OK);

        verify(pins).recordRightPin(TENANT, COLLEAGUE_MEMBER, CALLER_USER);
        verify(events, never()).insertRightAfterWrong(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aRightPinOfAPairWithWrongTriesClearsThePairAndAuditsTheLikelySuccessfulGuess() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        pairHas(2);

        StaffPinOps.ColleagueResult result = checkColleague(PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.OK);
        assertThat(result.wrongTry()).isFalse();
        verify(events).insertRightAfterWrong(COLLEAGUE_MEMBER, CALLER_MEMBER, CALLER_USER);
        Map<String, Object>[] audited = auditedForColleague("PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG");
        assertThat(audited[0]).isNull();
        assertThat(audited[1]).containsOnlyKeys("membershipId", "checkedByMembershipId", "wrongTries")
                .containsEntry("membershipId", COLLEAGUE_MEMBER.toString())
                .containsEntry("checkedByMembershipId", CALLER_MEMBER.toString())
                .containsEntry("wrongTries", 2);
    }

    @Test
    void eachWrongColleagueTryIsAColleagueTryOnTheRowAnEventAndAnAuditWithIdsAndTriesLeftOnly() {
        for (int failed = 0; failed <= 3; failed++) {
            org.mockito.Mockito.reset(auditLog);
            colleagueSetUp();
            colleagueRowIs(colleagueRow(colleagueHash(), failed, null, 0, null, null));
            pairHas(failed);

            StaffPinOps.ColleagueResult result = checkColleague("9999");

            assertThat(result.check().outcome()).as("failed=" + failed).isEqualTo(Outcome.WRONG);
            assertThat(result.check().triesLeft()).isEqualTo(4 - failed);
            assertThat(result.wrongTry()).isTrue();
            assertThat(result.colleagueUserId()).isNull();
            Map<String, Object>[] audited = auditedForColleague("PLATFORM_PIN_COLLEAGUE_CHECK_FAILED");
            assertThat(audited[0]).isNull();
            assertThat(audited[1]).containsOnlyKeys("membershipId", "checkedByMembershipId", "triesLeft")
                    .containsEntry("membershipId", COLLEAGUE_MEMBER.toString())
                    .containsEntry("checkedByMembershipId", CALLER_MEMBER.toString())
                    .containsEntry("triesLeft", 4 - failed);
        }
        verify(pins, org.mockito.Mockito.times(4)).recordColleagueWrongTry(TENANT, COLLEAGUE_MEMBER, CALLER_USER);
        verify(pins, never()).recordWrongTry(any(), any(), any());
        verify(events, org.mockito.Mockito.times(4)).insertWrong(COLLEAGUE_MEMBER, CALLER_MEMBER, CALLER_USER);
    }

    @Test
    void aMalformedOrMissingColleaguePinIsAWrongTryAuditedLikeAnyOther() {
        colleagueSetUp();
        pairHas(0);
        for (String bad : new String[] {null, "", "abcd", "48211"}) {
            org.mockito.Mockito.reset(auditLog);
            colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));

            StaffPinOps.ColleagueResult result = checkColleague(bad);

            assertThat(result.check().outcome()).as("[%s]", bad).isEqualTo(Outcome.WRONG);
            assertThat(result.wrongTry()).isTrue();
            auditedForColleague("PLATFORM_PIN_COLLEAGUE_CHECK_FAILED");
        }
    }

    @Test
    void theFifthWrongTryOfAPairIsAnsweredLikeAnyWrongTryAndTheSixthIsRefusedBeforeTheHash() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 1, null, 0, null, null));
        pairHas(4);

        StaffPinOps.ColleagueResult fifth = checkColleague("9999");

        assertThat(fifth.check().outcome()).isEqualTo(Outcome.WRONG);
        assertThat(fifth.wrongTry()).isTrue();
        verify(events).insertWrong(COLLEAGUE_MEMBER, CALLER_MEMBER, CALLER_USER);

        org.mockito.Mockito.reset(events, pins, auditLog);
        colleagueRowIs(colleagueRow(colleagueHash(), 2, null, 0, null, null));
        pairHas(5);

        StaffPinOps.ColleagueResult sixth = checkColleague("9999");

        assertThat(sixth.check().outcome()).isEqualTo(Outcome.PAIR_LIMIT);
        assertThat(sixth.check().retryAfterSeconds()).isZero();
        assertThat(sixth.wrongTry()).as("a refused try is not counted").isFalse();
        verify(events, never()).insertWrong(any(), any(), any());
        verify(events, never()).insertLocked(any(), any());
        verify(events, never()).insertRightAfterWrong(any(), any(), any());
        verify(pins, never()).recordColleagueWrongTry(any(), any(), any());
        verify(pins, never()).recordRightPin(any(), any(), any());
        verify(pins, never()).lockByColleagues(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theCapRefusesEvenTheRightPinAndNeverComparesIt() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        pairHas(7);

        StaffPinOps.ColleagueResult result = checkColleague(PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.PAIR_LIMIT);
        assertThat(result.colleagueUserId()).isNull();
        verify(pins, never()).recordRightPin(any(), any(), any());
        verify(events, never()).insertRightAfterWrong(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void noPinAndLockedAreAnsweredBeforeTheCapIsEvenCounted() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(null, 0, null, 0, null, null));
        assertThat(checkColleague(PIN).check().outcome()).isEqualTo(Outcome.NOT_SET);
        colleagueRowIs(colleagueRow(colleagueHash(), 0, NOW.plusSeconds(840), 1, NOW.minusSeconds(60), null));
        assertThat(checkColleague(PIN).check().outcome()).isEqualTo(Outcome.LOCKED);

        verify(events, never()).countPairWrongTries(any(), any(), any(), any());
    }

    @Test
    void theFifthWrongColleagueTryIsAColleagueLockThatKeepsTheLockCountAndIsAuditedAsCausedByColleagues() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(4, 1, NOW.minusSeconds(60), true, false));
        pairHas(1);
        lockedRowReadBack(1);

        StaffPinOps.ColleagueResult result = checkColleague("9999");

        assertThat(result.check().outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(result.check().retryAfterSeconds()).isEqualTo(900);
        assertThat(result.wrongTry()).as("the try that locks is a wrong try").isTrue();
        verify(pins).lockByColleagues(TENANT, COLLEAGUE_MEMBER, CALLER_USER);
        verify(pins, never()).lock(any(), any(), anyInt(), any());
        verify(pins, never()).switchOffAfterLocks(any(), any(), any());
        verify(events).insertWrong(COLLEAGUE_MEMBER, CALLER_MEMBER, CALLER_USER);
        verify(events).insertLocked(COLLEAGUE_MEMBER, CALLER_USER);
        Map<String, Object>[] locked = auditedForColleague("PLATFORM_PIN_LOCKED");
        for (Map<String, Object> values : locked) {
            assertThat(values).containsEntry("checkedByMembershipId", CALLER_MEMBER.toString())
                    .containsEntry("membershipId", COLLEAGUE_MEMBER.toString());
        }
        assertThat(locked[1]).containsEntry("pinState", "LOCKED").containsEntry("lockCount", 1)
                .containsEntry("causedBy", "COLLEAGUES");
        assertThat(auditedForColleague("PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")[1]).containsEntry("triesLeft", 0);
    }

    @Test
    void aColleagueNeverSwitchesThePinOffNoMatterHowManyLocksAreInTheWindow() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(4, 2, NOW.minusSeconds(3600), false, false));
        pairHas(0);
        lockedRowReadBack(2);

        StaffPinOps.ColleagueResult result = checkColleague("9999");

        assertThat(result.check().outcome()).isEqualTo(Outcome.LOCKED);
        verify(pins).lockByColleagues(TENANT, COLLEAGUE_MEMBER, CALLER_USER);
        verify(pins, never()).switchOffAfterLocks(any(), any(), any());
        verify(auditLog, never()).record(eq("PLATFORM_PIN_CLEARED_AFTER_LOCKS"), anyString(), any(), any(), any());
        assertThat(auditedForColleague("PLATFORM_PIN_LOCKED")[1]).containsEntry("lockCount", 2);
    }

    @Test
    void aLockedColleagueAnswersLockedEvenToTheRightPinAndIsNotAWrongTry() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, NOW.plusSeconds(840), 1, NOW.minusSeconds(60), null));

        StaffPinOps.ColleagueResult result = checkColleague(PIN);

        assertThat(result.check().outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(result.check().retryAfterSeconds()).isEqualTo(840);
        assertThat(result.wrongTry()).isFalse();
        verify(pins, never()).recordColleagueWrongTry(any(), any(), any());
        verify(pins, never()).recordRightPin(any(), any(), any());
        verifyNoInteractions(events);
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aColleagueWithoutAPinIsNotSetForEveryReasonAndIsNotAWrongTry() {
        colleagueSetUp();
        for (String reason : new String[] {null, "RESET_BY_OWNER", "TOO_MANY_LOCKS"}) {
            colleagueRowIs(colleagueRow(null, 0, null, 0, null, reason));

            StaffPinOps.ColleagueResult result = checkColleague(PIN);

            assertThat(result.check().outcome()).isEqualTo(Outcome.NOT_SET);
            assertThat(result.wrongTry()).isFalse();
        }
        colleagueRowIs(null);
        assertThat(checkColleague(PIN).check().outcome()).isEqualTo(Outcome.NOT_SET);
        verify(pins, never()).recordColleagueWrongTry(any(), any(), any());
        verifyNoInteractions(events);
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aFailureOfTheDatabaseInTheCheckPropagatesWithNothingReturned() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(colleagueHash(), 0, null, 0, null, null));
        when(events.countPairWrongTries(any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> checkColleague(PIN)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void noColleagueAuditRowCarriesAPinAHashANameOrAMobile() {
        colleagueSetUp();
        colleagueRowIs(colleagueRow(4, 0, null, true, false));
        pairHas(3);
        lockedRowReadBack(0);
        checkColleague("9999");
        org.mockito.Mockito.reset(events);
        colleagueRowIs(colleagueRow(0, 0, null, false, false));
        pairHas(3);
        checkColleague(PIN);

        for (String action : new String[] {"PLATFORM_PIN_LOCKED", "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED",
                "PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG"}) {
            for (Map<String, Object> values : auditedForColleague(action)) {
                if (values == null) {
                    continue;
                }
                assertThat(values.toString()).doesNotContain("$2a$").doesNotContain(colleagueHash()).doesNotContain("9999")
                        .doesNotContain(PIN).doesNotContain("9822077411").doesNotContain("Anita")
                        .doesNotContain("Sunita");
            }
        }
    }

    @Test
    void theColleagueResultPrintsTheOutcomeOnlyAndNoIds() {
        StaffPinOps.ColleagueResult result = new StaffPinOps.ColleagueResult(
                new PinCheck(Outcome.OK, 5, null, 0), COLLEAGUE_USER, false);

        assertThat(result.toString()).isEqualTo("ColleagueResult[outcome=OK, wrongTry=false]")
                .doesNotContain(COLLEAGUE_USER.toString());
    }

    // ---- set ----

    @Test
    void aFirstPinIsInsertedAndAuditedAsSetWithNothingBefore() {
        rowIs(null);
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        PinStatusView view = ops.commitSet(TENANT, USER, PIN);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(pins).insert(eq(MEMBER), hash.capture(), eq(1), eq(USER));
        assertThat(hash.getValue()).startsWith("$2a$04$").doesNotContain(PIN);
        assertThat(hasher.matches(TENANT, MEMBER, PIN, hash.getValue())).isTrue();
        verify(pins, never()).replace(any(), any(), any(), anyInt(), any());
        verify(auditLog).record(eq("PLATFORM_PIN_SET"), eq("STAFF_PIN"), eq(MEMBER), isNull(), anyMap());
        assertThat(view.state()).isEqualTo(PinState.SET);
        assertThat(view.available()).isTrue();
        assertThat(view.mobileMasked()).isEqualTo("••••••7410");
    }

    @Test
    void aNewPinAfterAResetUpdatesTheRowAndIsAuditedAsSet() {
        rowIs(row(null, 0, null, 0, null, "RESET_BY_OWNER"));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        ops.commitSet(TENANT, USER, PIN);

        verify(pins).replace(eq(TENANT), eq(MEMBER), any(), eq(1), eq(USER));
        verify(pins, never()).insert(any(), any(), anyInt(), any());
        verify(auditLog).record(eq("PLATFORM_PIN_SET"), eq("STAFF_PIN"), eq(MEMBER), isNull(), anyMap());
    }

    @Test
    void changingASetPinIsAuditedAsChangedWithTheStateBefore() {
        rowIs(row(goodHash, 2, null, 0, null, null));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        ops.commitSet(TENANT, USER, PIN);

        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_CHANGED");
        assertThat(audited[0]).containsEntry("pinState", "SET");
        assertThat(audited[1]).containsEntry("pinState", "SET");
    }

    @Test
    void replacingALockedPinIsAuditedAsChangedFromLocked() {
        rowIs(row(goodHash, 0, NOW.plusSeconds(600), 1, NOW.minusSeconds(60), null));
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        ops.commitSet(TENANT, USER, PIN);

        assertThat(auditedMaps("PLATFORM_PIN_CHANGED")[0]).containsEntry("pinState", "LOCKED");
    }

    @Test
    void settingAPinNeedsAnActiveMembership() {
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ops.commitSet(TENANT, USER, PIN)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(pins, auditLog);
    }

    @Test
    void theSetAuditRowHoldsNoPinHashOrMobile() {
        rowIs(null);
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        ops.commitSet(TENANT, USER, PIN);

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PLATFORM_PIN_SET"), eq("STAFF_PIN"), eq(MEMBER), isNull(), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("membershipId", "pinState");
        assertThat(after.getValue().toString()).doesNotContain(PIN).doesNotContain("$2a$").doesNotContain("9822077410");
    }

    // ---- reset ----

    @Test
    void resettingASetPinClearsItAndAuditsIdsAndStatesOnly() {
        rowIs(row(goodHash, 0, null, 0, null, null));

        assertThat(ops.clear(TENANT, USER, MEMBER)).isTrue();

        verify(pins).reset(TENANT, MEMBER, USER);
        Map<String, Object>[] audited = auditedMaps("PLATFORM_PIN_RESET");
        assertThat(audited[0]).containsEntry("pinState", "SET");
        assertThat(audited[1]).containsEntry("pinState", "NOT_SET").containsEntry("reason", "RESET_BY_OWNER");
    }

    @Test
    void resettingALockedPinAuditsLockedAsTheStateBefore() {
        rowIs(row(goodHash, 0, NOW.plusSeconds(600), 1, NOW.minusSeconds(60), null));

        assertThat(ops.clear(TENANT, USER, MEMBER)).isTrue();

        assertThat(auditedMaps("PLATFORM_PIN_RESET")[0]).containsEntry("pinState", "LOCKED");
    }

    @Test
    void resettingWhenThereIsNoPinWritesNothing() {
        rowIs(null);
        assertThat(ops.clear(TENANT, USER, MEMBER)).isFalse();
        rowIs(row(null, 0, null, 0, null, "TOO_MANY_LOCKS"));
        assertThat(ops.clear(TENANT, USER, MEMBER)).isFalse();

        verify(pins, never()).reset(any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    // ---- status ----

    @Test
    void theStatusShowsStateTriesAndTheMaskedMobileOnly() {
        Own none = new Own(MEMBER, "9822077410", null);
        PinStatusView notSet = ops.statusOf(none, true);
        assertThat(notSet.state()).isEqualTo(PinState.NOT_SET);
        assertThat(notSet.triesLeft()).isNull();
        assertThat(notSet.setAt()).isNull();
        assertThat(notSet.notSetReason()).isNull();
        assertThat(notSet.mobileMasked()).isEqualTo("••••••7410");

        PinStatusView set = ops.statusOf(new Own(MEMBER, "9822077410", row(goodHash, 2, null, 0, null, null)), true);
        assertThat(set.state()).isEqualTo(PinState.SET);
        assertThat(set.triesLeft()).isEqualTo(3);
        assertThat(set.setAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(set.lockedUntil()).isNull();
        assertThat(set.notSetReason()).isNull();

        PinStatusView locked = ops.statusOf(new Own(MEMBER, "9822077410",
                row(goodHash, 0, NOW.plusSeconds(600), 1, NOW, null)), true);
        assertThat(locked.state()).isEqualTo(PinState.LOCKED);
        assertThat(locked.triesLeft()).isZero();
        assertThat(locked.lockedUntil()).isEqualTo(NOW.plusSeconds(600));

        PinStatusView reset = ops.statusOf(new Own(MEMBER, "9822077410",
                row(null, 0, null, 0, null, "RESET_BY_OWNER")), false);
        assertThat(reset.state()).isEqualTo(PinState.NOT_SET);
        assertThat(reset.notSetReason()).isEqualTo("RESET_BY_OWNER");
        assertThat(reset.setAt()).isNull();
        assertThat(reset.available()).isFalse();
    }

    @Test
    void ownNeverPrintsTheMobile() {
        assertThat(new Own(MEMBER, "9822077410", null).toString()).doesNotContain("9822077410");
    }

    @Test
    void readOwnReturnsEmptyWithoutAnActiveMembership() {
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(ops.readOwn(TENANT, USER)).isEmpty();
        verifyNoInteractions(pins);
    }

    @Test
    void readOwnReadsTheRowWithoutALock() {
        when(pins.find(TENANT, MEMBER, false)).thenReturn(Optional.of(row(goodHash, 0, null, 0, null, null)));

        Own own = ops.readOwn(TENANT, USER).orElseThrow();

        assertThat(own.membershipId()).isEqualTo(MEMBER);
        assertThat(own.row().hasPin()).isTrue();
        verify(pins, never()).find(TENANT, MEMBER, true);
    }
}
