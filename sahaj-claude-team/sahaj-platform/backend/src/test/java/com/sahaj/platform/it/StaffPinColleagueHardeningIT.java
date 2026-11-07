package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.application.StaffPinLimits;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;

/**
 * SAH-38 (HARD-3, ADR-014) the colleague PIN check as IPD calls it ({@code StaffPinPort.checkColleague}) on the real
 * {@code sahaj_test} database: the durable pair cap (5 wrong tries per caller and colleague in a rolling 7 days, answered
 * from the table alone so a restart forgets nothing, 8-day-old tries do not count, a right PIN clears the pair, a new PIN or
 * an owner reset starts it again), the colleague's own failed-check bucket that is gone, colleague locks that never move the
 * lock count and never switch the PIN off while three own locks still do, mixed runs, two parallel tries that are both
 * counted, the fail-closed answer (a forced lock timeout answers 503-type CHECK_FAILED with everything rolled back and the
 * same answer for a right PIN), the code-fault branch (23514 and 42501 are thrown, never turned into an answer about the
 * PIN), the notice to the PIN's owner, the clinic owner's history and the Team figure, and no PIN or name in any log, audit
 * row or event row. Test plan: docs/test-plans/SAH-38.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinColleagueHardeningIT extends PinTestSupport {

    private static final String NURSE = "NURSE";
    private static final String EVENTS = "platform.staff_pin_colleague_event";
    private static final String[] EVENT_TABLE = {EVENTS};
    private static final String SEEN_PATH = ME_PIN + "/colleague-tries/seen";
    private static final String NOTICE_PATH = ME_PIN + "/colleague-tries";

    @Autowired
    private StaffPinPort port;

    @Autowired
    private StaffPinLimits limits;

    private record Colleague(Pn p, String pin) {
    }

    private static String wrongFor(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    private Colleague colleague(Clinic clinic) throws Exception {
        Pn p = newPn(clinic, NURSE);
        String pin = goodPin(p);
        setPin(p, pin);
        return new Colleague(p, pin);
    }

    private ColleaguePinCheck check(Pn caller, Pn colleague, String pin) {
        return port.checkColleague(caller.clinic().tenantId(), caller.userId(), colleague.membershipId(), NURSE, pin);
    }

    private void wrong(Pn caller, Colleague c, int n) {
        for (int i = 0; i < n; i++) {
            assertThat(check(caller, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
        }
    }

    /** The colleague's own right PIN (a run of wrong tries ends; no pair is cleared by it). */
    private void ownRight(Colleague c) {
        assertThat(port.check(c.p().clinic().tenantId(), c.p().userId(), c.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    private long events(Clinic clinic, Pn owner, String kind) throws SQLException {
        return count(clinic, "select count(*) from " + EVENTS + " where membership_id = ?::uuid and kind = ?",
                owner.membershipId().toString(), kind);
    }

    private long pairEvents(Clinic clinic, Pn owner, Pn caller, String kind) throws SQLException {
        return count(clinic, "select count(*) from " + EVENTS + " where membership_id = ?::uuid and by_membership_id = ?::uuid "
                + "and kind = ?", owner.membershipId().toString(), caller.membershipId().toString(), kind);
    }

    /** The row of the PIN's owner with the two new flags, as text. */
    private String flags(Clinic clinic, Pn owner) throws SQLException {
        return values(clinic, "select failed_count || '/' || lock_count || '/' || run_has_colleague_try || '/' || "
                + "lock_by_colleagues || '/' || (pin_hash is not null) from platform.staff_pin where membership_id = ?::uuid",
                owner.membershipId().toString()).get(0);
    }

    /** Moves the owner's event rows back in time (the append-only trigger is switched off for this one statement). */
    private void ageEvents(Pn owner, String kind, String interval) throws SQLException {
        asOwner(true, EVENT_TABLE, c -> {
            DbAccess.execute(c, "alter table " + EVENTS + " disable trigger trg_staff_pin_colleague_event_no_update_delete");
            DbAccess.execute(c, "update " + EVENTS + " set at = at - interval '" + interval + "' where membership_id = ?::uuid "
                    + "and kind = ?", owner.membershipId().toString(), kind);
            DbAccess.execute(c, "alter table " + EVENTS + " enable trigger trg_staff_pin_colleague_event_no_update_delete");
            return null;
        });
    }

    /** Rows written by the owner with chosen times (the insert guard is switched off for this statement). */
    private void historic(Pn owner, Pn caller, String kind, String ago, int n) throws SQLException {
        asOwner(true, EVENT_TABLE, c -> {
            DbAccess.execute(c, "alter table " + EVENTS + " disable trigger trg_staff_pin_colleague_event_guard_insert");
            DbAccess.execute(c, "insert into " + EVENTS + " (tenant_id, membership_id, by_membership_id, kind, created_by, at) "
                    + "select ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, clock_timestamp() - interval '" + ago + "' "
                    + "from generate_series(1, " + n + ")", owner.clinic().tenantId().toString(),
                    owner.membershipId().toString(), caller == null ? null : caller.membershipId().toString(), kind,
                    (caller == null ? owner : caller).userId().toString());
            DbAccess.execute(c, "alter table " + EVENTS + " enable trigger trg_staff_pin_colleague_event_guard_insert");
            return null;
        });
    }

    private void expectPairLimit(ColleaguePinCheck r) {
        assertThat(r.outcome()).isEqualTo(Outcome.PAIR_LIMIT);
        assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("colleague-pin-limit");
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.title()).isEqualTo("Too many wrong PIN tries");
            assertThat(e.getMessage()).isEqualTo("Too many wrong PIN tries for this nurse. Choose another nurse.");
            assertThat(e.retryAfterSeconds()).as("no retryAfterSeconds").isZero();
            assertThat(e.subject()).isEqualTo(StaffPinException.Subject.COLLEAGUE);
        });
    }

    // ---------------- the pair cap ----------------

    /** Four wrong tries by the caller, the colleague's own right PIN ends the run, one more wrong try: five on the pair. */
    private void fiveOnThePair(Pn caller, Colleague c) {
        wrong(caller, c, 4);
        ownRight(c);
        wrong(caller, c, 1);
        freshPinAllowance(caller);
    }

    @Test
    void theSixthTryOfOneCallerOnOneColleagueIsRefusedBeforeAnythingIsComparedOrCounted() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        fiveOnThePair(caller, c);
        assertThat(pairEvents(clinic, c.p(), caller, "WRONG")).isEqualTo(5);
        String rowBefore = flags(clinic, c.p());
        PinRow pinBefore = pinRow(clinic, c.p().membershipId());
        long audits = allAudits(clinic);
        long failedAudits = auditCount(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", c.p().membershipId());
        long total = count(clinic, "select count(*) from " + EVENTS);
        try (LogCapture logs = new LogCapture()) {
            expectPairLimit(check(caller, c.p(), wrongFor(c.pin())));
            expectPairLimit(check(caller, c.p(), c.pin()));
            assertThat(logs.allText()).contains("staff-pin-colleague-check").contains("outcome=PAIR_LIMIT");
            assertThat(hasBarePin(logs.allText(), c.pin())).isFalse();
            assertThat(hasBarePin(logs.allText(), wrongFor(c.pin()))).isFalse();
        }
        assertThat(flags(clinic, c.p())).as("the PIN was not compared or counted").isEqualTo(rowBefore);
        assertThat(pinRow(clinic, c.p().membershipId())).isEqualTo(pinBefore);
        assertThat(allAudits(clinic)).as("nothing audited").isEqualTo(audits);
        assertThat(auditCount(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", c.p().membershipId())).isEqualTo(failedAudits);
        assertThat(count(clinic, "select count(*) from " + EVENTS)).as("no event written").isEqualTo(total);
        assertThat(limits.colleagueCheckWait(clinic.tenantId(), caller.userId())).as("the caller's bucket is not filled by a refusal")
                .isZero();
    }

    @Test
    void theFifthWrongTryAnswersLikeAnyWrongTryAndTheCapDoesNotTouchAnotherCallerOrAnotherColleague() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Pn other = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        Colleague d = colleague(clinic);
        wrong(caller, c, 4);
        ownRight(c);
        ColleaguePinCheck fifth = check(caller, c.p(), wrongFor(c.pin()));
        assertThat(fifth.outcome()).isEqualTo(Outcome.WRONG);
        assertThatThrownBy(fifth::requireOk).isInstanceOfSatisfying(StaffPinException.class,
                e -> assertThat(e.slug()).isEqualTo("wrong-pin"));
        freshPinAllowance(caller);
        expectPairLimit(check(caller, c.p(), c.pin()));
        // the same caller on another colleague, and another caller on the same colleague, are not capped
        assertThat(check(caller, d.p(), d.pin()).outcome()).isEqualTo(Outcome.OK);
        assertThat(check(other, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
        assertThat(check(other, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
    }

    @Test
    void theCapSurvivesARestartBecauseNothingInMemoryIsInvolved() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        fiveOnThePair(caller, c);
        // a restart forgets every in-memory bucket of the caller and of the colleague
        freshPinAllowance(caller);
        freshPinAllowance(c.p());
        forgetColleagueShortWindow(caller);
        expectPairLimit(check(caller, c.p(), c.pin()));
        expectPairLimit(check(caller, c.p(), wrongFor(c.pin())));
    }

    @Test
    void wrongTriesOlderThanSevenDaysDoNotCountAndSixDaysOldOnesStillDo() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        fiveOnThePair(caller, c);
        ageEvents(c.p(), "WRONG", "6 days 23 hours");
        // the PIN row's set_at is today, so the pair window is the later of the two: age set_at too, as the owner
        forcePinRow(c.p(), "set_at = clock_timestamp() - interval '30 days'");
        expectPairLimit(check(caller, c.p(), c.pin()));
        ageEvents(c.p(), "WRONG", "2 hours");
        assertThat(check(caller, c.p(), c.pin()).outcome()).as("eight days old is outside the window").isEqualTo(Outcome.OK);
        assertThat(check(caller, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
    }

    @Test
    void aRightPinOfTheSameCallerClearsThePairWritesTheMarkerAndTheAuditWithTheNumberOfWrongTries() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        long cleared = auditCount(clinic, "PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG", c.p().membershipId());
        // a right PIN with no wrong tries in the pair writes nothing new
        assertThat(check(caller, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
        assertThat(pairEvents(clinic, c.p(), caller, "RIGHT_AFTER_WRONG")).isZero();
        wrong(caller, c, 3);
        assertThat(check(caller, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
        assertThat(pairEvents(clinic, c.p(), caller, "RIGHT_AFTER_WRONG")).isOne();
        assertThat(auditCount(clinic, "PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG", c.p().membershipId()) - cleared).isOne();
        List<String[]> rows = audit(clinic, "PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG");
        JsonNode after = objectMapper.readTree(rows.get(rows.size() - 1)[3]);
        assertThat(after.get("membershipId").asText()).isEqualTo(c.p().membershipId().toString());
        assertThat(after.get("checkedByMembershipId").asText()).isEqualTo(caller.membershipId().toString());
        assertThat(after.get("wrongTries").asInt()).isEqualTo(3);
        assertThat(rows.get(rows.size() - 1)[4]).as("the person who typed it").isEqualTo(caller.userId().toString());
        // the pair is zero again: five more wrong tries are allowed, the sixth is not
        freshPinAllowance(caller);
        ownRight(c);
        wrong(caller, c, 1);
        ownRight(c);
        wrong(caller, c, 4);
        freshPinAllowance(caller);
        ownRight(c);
        assertThat(pairEvents(clinic, c.p(), caller, "WRONG")).isEqualTo(8);
        expectPairLimit(check(caller, c.p(), c.pin()));
        // the colleague's own right PIN does not clear the pair of a caller
        assertThat(pairEvents(clinic, c.p(), caller, "RIGHT_AFTER_WRONG")).isOne();
    }

    @Test
    void anOwnerResetAndANewPinStartThePairAgain() throws Exception {
        for (boolean reset : new boolean[] {true, false}) {
            Clinic clinic = hospital();
            Pn caller = newPn(clinic, NURSE);
            Colleague c = colleague(clinic);
            fiveOnThePair(caller, c);
            expectPairLimit(check(caller, c.p(), c.pin()));
            String newPin;
            if (reset) {
                resetPin(clinic, c.p().membershipId());
                newPin = otherPin(c.p(), c.pin());
                setPin(c.p(), newPin);
            } else {
                newPin = otherPin(c.p(), c.pin());
                String code = sendCode(c.p());
                ok(HttpMethod.PUT, ME_PIN, c.p().auth(), pinBody(code, newPin, newPin), 200);
            }
            assertThat(check(caller, c.p(), wrongFor(newPin)).outcome()).as("reset " + reset).isEqualTo(Outcome.WRONG);
            assertThat(pairEvents(clinic, c.p(), caller, "WRONG")).as("old rows stay as evidence").isEqualTo(6);
            assertThat(flags(clinic, c.p())).startsWith("1/0/true/false");
            assertThat(check(caller, c.p(), newPin).outcome()).isEqualTo(Outcome.OK);
        }
    }

    // ---------------- the colleague's own bucket is gone ----------------

    @Test
    void wrongColleagueTriesNeverFillOrReadTheColleaguesOwnFailedCheckBucket() throws Exception {
        Clinic clinic = hospital();
        Colleague c = colleague(clinic);
        for (int i = 0; i < 2; i++) {
            Pn caller = newPn(clinic, NURSE);
            wrong(caller, c, 3);
            ownRight(c);
        }
        assertThat(limits.pinCheckWait(clinic.tenantId(), c.p().userId())).as("the colleague's own bucket is empty").isZero();
        // ten own checks in a row are still allowed: nobody's guesses used the allowance (old limit: 10 per 5 minutes)
        for (int i = 0; i < 10; i++) {
            assertThat(port.check(clinic.tenantId(), c.p().userId(), c.pin()).outcome()).isEqualTo(Outcome.OK);
        }
        // and a third caller naming the colleague never gets a "too many tries" answer made of others' guesses
        Pn third = newPn(clinic, NURSE);
        assertThat(check(third, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    // ---------------- colleague locks ----------------

    @Test
    void aColleagueLockLocksForFifteenMinutesKeepsTheLockCountWritesTheLockedEventAndNeverSwitchesThePinOff() throws Exception {
        Clinic clinic = hospital();
        Colleague c = colleague(clinic);
        for (int round = 1; round <= 4; round++) {
            Pn caller = newPn(clinic, NURSE);
            wrong(caller, c, 4);
            ColleaguePinCheck fifth = check(caller, c.p(), wrongFor(c.pin()));
            assertThat(fifth.outcome()).as("round " + round).isEqualTo(Outcome.LOCKED);
            assertThat(fifth.pin().lockedUntil()).isNotNull();
            assertThatThrownBy(fifth::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
                assertThat(e.slug()).isEqualTo("pin-locked");
                assertThat(e.status()).isEqualTo(403);
            });
            assertThat(flags(clinic, c.p())).as("round " + round).isEqualTo("0/0/false/true/true");
            assertThat(events(clinic, c.p(), "LOCKED")).isEqualTo(round);
            assertThat(pinRow(clinic, c.p().membershipId()).lockedNow()).isTrue();
            assertThat(pinRow(clinic, c.p().membershipId()).hasWindow()).as("the window is never started by colleagues").isFalse();
            lockEnded(c.p());
        }
        List<String[]> locked = audit(clinic, "PLATFORM_PIN_LOCKED");
        JsonNode after = objectMapper.readTree(locked.get(locked.size() - 1)[3]);
        assertThat(after.get("causedBy").asText()).isEqualTo("COLLEAGUES");
        assertThat(after.get("lockCount").asInt()).as("the lock count in the audit is unchanged").isZero();
        assertThat(allAuditText(clinic)).doesNotContain("PLATFORM_PIN_CLEARED_AFTER_LOCKS");
        // the person's own PIN still works after the lock ends
        assertThat(port.check(clinic.tenantId(), c.p().userId(), c.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void whileLockedByColleaguesNothingIsComparedOrCountedAndAnOwnerResetOrNewPinClearsTheCause() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 4);
        assertThat(check(caller, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        long wrongRows = events(clinic, c.p(), "WRONG");
        freshPinAllowance(caller);   // the five wrong tries used up the caller's own 5-per-15-minutes allowance
        assertThat(check(caller, c.p(), c.pin()).outcome()).as("a right PIN is not compared while locked").isEqualTo(Outcome.LOCKED);
        assertThat(port.check(clinic.tenantId(), c.p().userId(), c.pin()).outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(events(clinic, c.p(), "WRONG")).isEqualTo(wrongRows);
        // a new PIN (code to the person's own mobile) ends the lock and clears both flags
        String newPin = otherPin(c.p(), c.pin());
        setPin(c.p(), newPin);
        assertThat(flags(clinic, c.p())).isEqualTo("0/0/false/false/true");
        assertThat(check(caller, c.p(), newPin).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void threeOwnLocksStillSwitchThePinOffAndOwnLocksAfterColleagueLocksCountFromOne() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        // a colleague lock first: lock count stays 0
        wrong(caller, c, 4);
        check(caller, c.p(), wrongFor(c.pin()));
        lockEnded(c.p());
        for (int lock = 1; lock <= 3; lock++) {
            freshPinAllowance(c.p());
            for (int i = 0; i < 4; i++) {
                assertThat(port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
            }
            var fifth = port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin()));
            if (lock < 3) {
                assertThat(fifth.outcome()).isEqualTo(Outcome.LOCKED);
                assertThat(flags(clinic, c.p())).as("own lock " + lock).isEqualTo("0/" + lock + "/false/false/true");
                assertThat(events(clinic, c.p(), "LOCKED")).as("an own lock writes no LOCKED event").isOne();
                lockEnded(c.p());
            } else {
                assertThat(fifth.outcome()).isEqualTo(Outcome.NOT_SET);
                assertThat(fifth.notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
            }
        }
        assertThat(pinRow(clinic, c.p().membershipId()).hasPin()).isFalse();
        assertThat(flags(clinic, c.p())).isEqualTo("0/0/false/false/false");
        assertThat(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).hasSize(1);
        JsonNode off = objectMapper.readTree(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS").get(0)[3]);
        assertThat(off.get("causedBy").asText()).isEqualTo("SELF");
    }

    // ---------------- mixed runs ----------------

    @Test
    void aMixedRunWithAColleagueTryLocksAsAColleagueLockWhoeverTypesTheFifthTry() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        // two colleague tries, then three own tries: the fifth is the person's own, the lock is still a colleague lock
        Colleague c = colleague(clinic);
        wrong(caller, c, 2);
        for (int i = 0; i < 2; i++) {
            assertThat(port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
        }
        assertThat(flags(clinic, c.p())).isEqualTo("4/0/true/false/true");
        var fifth = port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin()));
        assertThat(fifth.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(flags(clinic, c.p())).isEqualTo("0/0/false/true/true");
        assertThat(events(clinic, c.p(), "LOCKED")).isOne();
        assertThat(objectMapper.readTree(audit(clinic, "PLATFORM_PIN_LOCKED").get(0)[3]).get("causedBy").asText())
                .isEqualTo("COLLEAGUES");
        // four own tries, then a colleague's fifth
        Colleague d = colleague(clinic);
        for (int i = 0; i < 4; i++) {
            assertThat(port.check(clinic.tenantId(), d.p().userId(), wrongFor(d.pin())).outcome()).isEqualTo(Outcome.WRONG);
        }
        assertThat(check(caller, d.p(), wrongFor(d.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(flags(clinic, d.p())).isEqualTo("0/0/false/true/true");
        // five own tries are an own lock, as in SAH-31
        Colleague e = colleague(clinic);
        for (int i = 0; i < 4; i++) {
            port.check(clinic.tenantId(), e.p().userId(), wrongFor(e.pin()));
        }
        assertThat(port.check(clinic.tenantId(), e.p().userId(), wrongFor(e.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(flags(clinic, e.p())).isEqualTo("0/1/false/false/true");
        assertThat(events(clinic, e.p(), "LOCKED")).isZero();
    }

    // ---------------- parallel tries ----------------

    @Test
    void twoParallelWrongTriesOnOneColleagueAreBothCounted() throws Exception {
        Clinic clinic = hospital();
        Pn a1 = newPn(clinic, NURSE);
        Pn a2 = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        CompletableFuture<ColleaguePinCheck> f1 = CompletableFuture.supplyAsync(() -> check(a1, c.p(), wrongFor(c.pin())));
        CompletableFuture<ColleaguePinCheck> f2 = CompletableFuture.supplyAsync(() -> check(a2, c.p(), wrongFor(c.pin())));
        assertThat(f1.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(f2.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(events(clinic, c.p(), "WRONG")).isEqualTo(2);
        assertThat(flags(clinic, c.p())).isEqualTo("2/0/true/false/true");
        // the same caller twice in parallel: both on the pair
        Pn a3 = newPn(clinic, NURSE);
        Colleague d = colleague(clinic);
        CompletableFuture<ColleaguePinCheck> g1 = CompletableFuture.supplyAsync(() -> check(a3, d.p(), wrongFor(d.pin())));
        CompletableFuture<ColleaguePinCheck> g2 = CompletableFuture.supplyAsync(() -> check(a3, d.p(), wrongFor(d.pin())));
        assertThat(g1.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(g2.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(pairEvents(clinic, d.p(), a3, "WRONG")).isEqualTo(2);
    }

    @Test
    void twoParallelTriesAtFourWrongTriesLetExactlyOneThroughAndRefuseTheOtherAtTheCap() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 4);
        ownRight(c);
        freshPinAllowance(caller);
        CompletableFuture<ColleaguePinCheck> f1 = CompletableFuture.supplyAsync(() -> check(caller, c.p(), wrongFor(c.pin())));
        CompletableFuture<ColleaguePinCheck> f2 = CompletableFuture.supplyAsync(() -> check(caller, c.p(), wrongFor(c.pin())));
        List<Outcome> outcomes = List.of(f1.get(20, TimeUnit.SECONDS).outcome(), f2.get(20, TimeUnit.SECONDS).outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.WRONG, Outcome.PAIR_LIMIT);
        assertThat(pairEvents(clinic, c.p(), caller, "WRONG")).as("never a sixth").isEqualTo(5);
    }

    // ---------------- fail closed ----------------

    @Test
    void aForcedLockTimeoutAnswersCheckFailedWithEverythingRolledBackAndTheSameAnswerForARightPin() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 1);
        String row = flags(clinic, c.p());
        PinRow pinBefore = pinRow(clinic, c.p().membershipId());
        long audits = allAudits(clinic);
        long total = count(clinic, "select count(*) from " + EVENTS);
        ColleaguePinCheck rightAnswer;
        ColleaguePinCheck wrongAnswer;
        long started = System.nanoTime();
        try (Connection holder = tenantConnection(clinic); LogCapture logs = new LogCapture()) {
            DbAccess.execute(holder, "select 1 from platform.staff_pin where membership_id = ?::uuid for update",
                    c.p().membershipId().toString());
            rightAnswer = check(caller, c.p(), c.pin());
            wrongAnswer = check(caller, c.p(), wrongFor(c.pin()));
            holder.rollback();
            assertThat(logs.allText()).contains("outcome=CHECK_FAILED");
            assertThat(hasBarePin(logs.allText(), c.pin())).isFalse();
            assertThat(hasBarePin(logs.allText(), wrongFor(c.pin()))).isFalse();
        }
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).as("two waits of the 3 second lock timeout")
                .isBetween(4L, 20L);
        for (ColleaguePinCheck r : new ColleaguePinCheck[] {rightAnswer, wrongAnswer}) {
            assertThat(r.outcome()).isEqualTo(Outcome.CHECK_FAILED);
            assertThat(r.isOk()).isFalse();
            assertThat(r.pin().triesLeft()).isZero();
            assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
                assertThat(e.slug()).isEqualTo("pin-check-failed");
                assertThat(e.status()).isEqualTo(503);
                assertThat(e.title()).isEqualTo("PIN check failed");
                assertThat(e.getMessage()).isEqualTo("We could not check the PIN. Try again.");
            });
        }
        assertThat(rightAnswer.toString()).isEqualTo(wrongAnswer.toString());
        assertThat(flags(clinic, c.p())).isEqualTo(row);
        assertThat(pinRow(clinic, c.p().membershipId())).isEqualTo(pinBefore);
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(count(clinic, "select count(*) from " + EVENTS)).isEqualTo(total);
        assertThat(limits.colleagueCheckWait(clinic.tenantId(), caller.userId())).as("only the one real wrong try is in the bucket")
                .isZero();
        // once the lock is free the right PIN is right again
        assertThat(check(caller, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void aTimeoutRaisedAfterTheComparisonLeavesNothingBehindAndSaysTheSameThing() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 1);
        String row = flags(clinic, c.p());
        long audits = allAudits(clinic);
        long total = count(clinic, "select count(*) from " + EVENTS);
        // the comparison happens first, then the writes fail with lock_not_available (55P03) on the event insert
        failEventInsert(clinic, c.p(), "55P03", () -> {
            ColleaguePinCheck wrongOne = check(caller, c.p(), wrongFor(c.pin()));
            assertThat(wrongOne.outcome()).isEqualTo(Outcome.CHECK_FAILED);
            freshPinAllowance(caller);
        });
        // a right PIN touches the event table only when the pair had wrong tries: it has one, so the marker insert fails too
        failEventInsert(clinic, c.p(), "55P03", () -> {
            ColleaguePinCheck rightOne = check(caller, c.p(), c.pin());
            assertThat(rightOne.outcome()).as("a right PIN is never reported as right when its writes failed")
                    .isEqualTo(Outcome.CHECK_FAILED);
        });
        assertThat(flags(clinic, c.p())).isEqualTo(row);
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(count(clinic, "select count(*) from " + EVENTS)).isEqualTo(total);
        assertThat(limits.colleagueCheckWait(clinic.tenantId(), caller.userId())).isZero();
    }

    @Test
    void aConstraintOrPermissionFaultIsACodeFaultThatIsThrownNeverAnAnswerAboutThePin() throws Exception {
        for (String errcode : new String[] {"23514", "42501", "23503"}) {
            Clinic clinic = hospital();
            Pn caller = newPn(clinic, NURSE);
            Colleague c = colleague(clinic);
            String row = flags(clinic, c.p());
            long audits = allAudits(clinic);
            failEventInsert(clinic, c.p(), errcode, () -> {
                assertThatThrownBy(() -> check(caller, c.p(), wrongFor(c.pin()))).as(errcode)
                        .isInstanceOf(DataAccessException.class).isNotInstanceOf(StaffPinException.class);
            });
            assertThat(flags(clinic, c.p())).as(errcode + " rolled back").isEqualTo(row);
            assertThat(allAudits(clinic)).isEqualTo(audits);
            assertThat(events(clinic, c.p(), "WRONG")).isZero();
            assertThat(check(caller, c.p(), c.pin()).outcome()).as("the system is usable after " + errcode).isEqualTo(Outcome.OK);
        }
    }

    /**
     * A temporary trigger on the event table that raises the given SQLSTATE for the rows of this one colleague, then removes
     * it (the same technique as DoseGiveIT: a trigger limited to one membership cannot disturb another test).
     */
    private void failEventInsert(Clinic clinic, Pn owner, String errcode, Action action) throws Exception {
        String suffix = owner.membershipId().toString().replace('-', '_');
        String function = "platform.it_fail_event_" + suffix;
        String trigger = "trg_it_fail_event_" + suffix;
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the fail-closed test' using errcode = '" + errcode + "'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before insert on " + EVENTS + " for each row when "
                    + "(new.membership_id = '" + owner.membershipId() + "'::uuid) execute function " + function + "()");
            try {
                action.run();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on " + EVENTS);
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    // ---------------- the notice to the PIN's owner ----------------

    private JsonNode notice(Pn p) throws Exception {
        return ok(HttpMethod.GET, NOTICE_PATH, p.auth(), null, 200);
    }

    private void seen(Pn p, String seenThrough) throws Exception {
        ok(HttpMethod.POST, SEEN_PATH, p.auth(), "{\"seenThrough\":\"" + seenThrough + "\"}", 204);
    }

    @Test
    void theNoticeCountsOnlyWrongTriesByColleaguesSinceTheLastGotItAndShowsNoNameAndNoCaller() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        JsonNode none = notice(c.p());
        assertThat(none.get("unseenCount").asInt()).isZero();
        assertThat(none.get("latestAt").isNull()).isTrue();
        assertThat(none.get("state").asText()).isEqualTo("SET");
        assertThat(none.get("lockedUntil").isNull()).isTrue();
        wrong(caller, c, 2);
        assertThat(port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
        JsonNode two = notice(c.p());
        assertThat(two.get("unseenCount").asInt()).as("the person's own wrong try is not in it").isEqualTo(2);
        assertThat(two.get("latestAt").asText()).isNotBlank();
        assertThat(two.toString()).doesNotContain(caller.membershipId().toString()).doesNotContain(caller.userId().toString())
                .doesNotContain(displayName(caller));
        assertThat(two.fieldNames().hasNext()).isTrue();
        assertThat(keysOf(two)).containsExactlyInAnyOrder("unseenCount", "latestAt", "state", "lockedUntil", "notSetReason");
        // the caller's own notice does not hold the colleague's tries
        assertThat(notice(caller).get("unseenCount").asInt()).isZero();
        // GET writes nothing
        long rows = count(clinic, "select count(*) from " + EVENTS);
        notice(c.p());
        assertThat(count(clinic, "select count(*) from " + EVENTS)).isEqualTo(rows);
        seen(c.p(), two.get("latestAt").asText());
        assertThat(notice(c.p()).get("unseenCount").asInt()).isZero();
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isOne();
        // a double tap writes nothing
        seen(c.p(), two.get("latestAt").asText());
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isOne();
        // a new PIN does not clear unseen tries
        wrong(caller, c, 1);
        String newPin = otherPin(c.p(), c.pin());
        setPin(c.p(), newPin);
        assertThat(notice(c.p()).get("unseenCount").asInt()).isOne();
    }

    @Test
    void aTryThatArrivesBetweenShowingTheNoticeAndGotItStaysUnseen() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 1);
        JsonNode shown = notice(c.p());
        Thread.sleep(20);
        wrong(caller, c, 1);
        seen(c.p(), shown.get("latestAt").asText());
        JsonNode after = notice(c.p());
        assertThat(after.get("unseenCount").asInt()).as("the later try is still unseen").isOne();
        assertThat(after.get("latestAt").asText()).isNotEqualTo(shown.get("latestAt").asText());
        // an older seenThrough than the marker changes nothing
        seen(c.p(), shown.get("latestAt").asText());
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isOne();
        // nothing unseen up to that moment: nothing is written
        seen(c.p(), "2020-01-01T00:00:00Z");
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isOne();
    }

    @Test
    void aSeenThroughInTheFutureIsCappedAtTheDatabaseClockAndCannotHideLaterTries() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 2);
        seen(c.p(), java.time.Instant.now().plusSeconds(86400).toString());
        assertThat(notice(c.p()).get("unseenCount").asInt()).isZero();
        assertThat(count(clinic, "select count(*) from " + EVENTS + " where kind = 'NOTICE_SEEN' and membership_id = ?::uuid "
                + "and seen_through <= at and seen_through <= clock_timestamp()", c.p().membershipId().toString())).isOne();
        Thread.sleep(20);
        wrong(caller, c, 1);
        assertThat(notice(c.p()).get("unseenCount").asInt()).as("a try after the Got it stays unseen").isOne();
    }

    @Test
    void theNoticeShowsTheLockAndTheSwitchedOffState() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 4);
        check(caller, c.p(), wrongFor(c.pin()));
        JsonNode locked = notice(c.p());
        assertThat(locked.get("state").asText()).isEqualTo("LOCKED");
        assertThat(locked.get("lockedUntil").asText()).isNotBlank();
        assertThat(locked.get("unseenCount").asInt()).isEqualTo(5);
        resetPin(clinic, c.p().membershipId());
        JsonNode off = notice(c.p());
        assertThat(off.get("state").asText()).isEqualTo("NOT_SET");
        assertThat(off.get("notSetReason").asText()).isEqualTo("RESET_BY_OWNER");
        assertThat(off.get("unseenCount").asInt()).as("a reset does not clear the evidence").isEqualTo(5);
        // a person with no PIN row at all
        Pn none = newPn(clinic, NURSE);
        JsonNode never = notice(none);
        assertThat(never.get("unseenCount").asInt()).isZero();
        assertThat(never.get("state").asText()).isEqualTo("NOT_SET");
    }

    @Test
    void seenThroughThatIsMissingOrNotADateTimeIsA400WithTheFieldAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, NURSE);
        setPin(p, goodPin(p));
        long rows = count(clinic, "select count(*) from " + EVENTS);
        for (String body : new String[] {"{}", "{\"seenThrough\":null}", "{\"seenThrough\":\"\"}", "{\"seenThrough\":\"yesterday\"}",
            "{\"seenThrough\":\"2026-11-07\"}", "{\"seenThrough\":\"2026-11-07T10:00:00\"}"}) {
            JsonNode p400 = problem(call(HttpMethod.POST, SEEN_PATH, p.auth(), body), 400);
            assertType(p400, "validation");
            assertThat(fieldNames(p400)).as(body).contains("seenThrough");
            assertThat(fieldMessage(p400, "seenThrough")).isEqualTo(RELOAD);
        }
        assertThat(count(clinic, "select count(*) from " + EVENTS)).isEqualTo(rows);
        // no body at all: the generic malformed-body problem (not a stack trace, not a 500)
        JsonNode noBody = problem(call(HttpMethod.POST, SEEN_PATH, p.auth(), null), 400);
        assertThat(noBody.get("type").asText()).startsWith(PROBLEM_BASE);
        assertThat(noBody.toString()).doesNotContain("Exception").doesNotContain("com.sahaj");
        JsonNode broken = problem(call(HttpMethod.POST, SEEN_PATH, p.auth(), "{not json"), 400);
        assertThat(broken.toString()).doesNotContain("Exception");
    }

    /** S38-3: the range of "seen through" is checked before any database call; every failure is the same 400. */
    @Test
    void seenThroughOutsideTheAllowedRangeOrTooLongIsTheSame400AndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 2);
        long rows = count(clinic, "select count(*) from " + EVENTS);
        String twoDaysAhead = java.time.Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS).toString();
        String[] bad = {
            "+200000-01-01T00:00:00Z",                  // year 200000: outside the range of the database column
            "+999999999-12-31T23:59:59Z",               // the largest year Java reads
            "2020-01-01T00:00:00Z" + " ".repeat(21),    // a readable value, but 41 characters: refused before it is read
            "x".repeat(41),
            "x".repeat(5000),
            "2019-12-31T23:59:59Z",                     // one second before 2020-01-01T00:00Z
            "1999-01-01T00:00:00Z",
            "0001-01-01T00:00:00Z",
            twoDaysAhead,
            "   ",
        };
        for (String value : bad) {
            String body = "{\"seenThrough\":\"" + value + "\"}";
            JsonNode p400 = problem(call(HttpMethod.POST, SEEN_PATH, c.p().auth(), body), 400);
            assertType(p400, "validation");
            assertThat(fieldNames(p400)).as("value of length " + value.length()).containsExactly("seenThrough");
            assertThat(fieldMessage(p400, "seenThrough")).isEqualTo(RELOAD);
            assertThat(p400.toString()).as("the submitted value is never echoed").doesNotContain(value.trim().isEmpty() ? "\u0000" : value.trim())
                    .doesNotContain("Exception").doesNotContain("com.sahaj");
        }
        assertThat(count(clinic, "select count(*) from " + EVENTS)).as("nothing written by a refused value").isEqualTo(rows);
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isZero();
        assertThat(notice(c.p()).get("unseenCount").asInt()).as("the tries are still unseen").isEqualTo(2);
    }

    @Test
    void seenThroughOneHourAheadAndTheExactFloorAreAcceptedAndOneHourAheadIsCappedAtTheDatabaseClock() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(caller, c, 2);
        // exactly 2020-01-01T00:00:00Z is inside the range (204); nothing is unseen up to it, so nothing is written
        seen(c.p(), "2020-01-01T00:00:00Z");
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isZero();
        // an offset form of the same range is read too
        seen(c.p(), java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHoursMinutes(5, 30)).plusHours(1).toString());
        assertThat(notice(c.p()).get("unseenCount").asInt()).isZero();
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isOne();
        assertThat(count(clinic, "select count(*) from " + EVENTS + " where kind = 'NOTICE_SEEN' and membership_id = ?::uuid "
                + "and seen_through <= at and seen_through <= clock_timestamp()", c.p().membershipId().toString())).isOne();
        Thread.sleep(20);
        wrong(caller, c, 1);
        seen(c.p(), java.time.Instant.now().plusSeconds(3600).toString());
        assertThat(notice(c.p()).get("unseenCount").asInt()).isZero();
        assertThat(events(clinic, c.p(), "NOTICE_SEEN")).isEqualTo(2);
    }

    @Test
    void everyClinicRoleReadsItsOwnNoticeAndOnlyOwnDataAndNoSessionIsRefused() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            Pn p = newPn(clinic, role);
            assertThat(notice(p).get("unseenCount").asInt()).as(role).isZero();
            ok(HttpMethod.POST, SEEN_PATH, p.auth(), "{\"seenThrough\":\"2020-01-01T00:00:00Z\"}", 204);
        }
        assertThat(notice(ownerPn(clinic)).get("unseenCount").asInt()).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(NOTICE_PATH))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThat(caller).isNotNull();
    }

    // ---------------- the clinic owner's history and the Team figure ----------------

    private String historyPath(Pn p) {
        return TEAM + "/" + p.membershipId() + "/pin/colleague-tries";
    }

    @Test
    void theOwnerSeesTheLastSevenDaysGroupedByCallerAndDayWithNamesLatestFirstAndTheMemberIsNotTold() throws Exception {
        Clinic clinic = hospital();
        Pn alpha = newPn(clinic, NURSE);
        Pn beta = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrong(alpha, c, 2);
        wrong(beta, c, 1);
        freshPinAllowance(alpha);
        ownRight(c);
        check(alpha, c.p(), c.pin());
        historic(c.p(), alpha, "WRONG", "2 days", 3);
        historic(c.p(), beta, "WRONG", "9 days", 4);
        long audits = allAudits(clinic);
        long access = count(clinic, "select count(*) from patient.record_access");
        JsonNode h = ok(HttpMethod.GET, historyPath(c.p()), clinic.owner(), null, 200);
        assertThat(h.get("membershipId").asText()).isEqualTo(c.p().membershipId().toString());
        assertThat(h.get("truncated").asBoolean()).isFalse();
        assertThat(h.get("wrongTries7d").asInt()).as("2 + 1 today and 3 two days ago; 9 days old rows are out").isEqualTo(6);
        assertThat(h.get("asOf").asText()).isNotBlank();
        JsonNode entries = h.get("entries");
        String alphaName = displayName(alpha);
        String betaName = displayName(beta);
        int wrongSum = 0;
        boolean sawRight = false;
        java.time.Instant last = null;
        for (JsonNode e : entries) {
            java.time.Instant at = java.time.Instant.parse(e.get("at").asText());
            if (last != null) {
                assertThat(at).as("latest first").isBeforeOrEqualTo(last);
            }
            last = at;
            switch (e.get("kind").asText()) {
                case "WRONG_TRIES" -> {
                    wrongSum += e.get("count").asInt();
                    assertThat(e.get("byName").asText()).isIn(alphaName, betaName);
                    assertThat(e.get("byMembershipId").asText()).isIn(alpha.membershipId().toString(), beta.membershipId().toString());
                }
                case "RIGHT_AFTER_WRONG" -> {
                    sawRight = true;
                    assertThat(e.get("byName").asText()).isEqualTo(alphaName);
                    assertThat(e.get("count").isNull()).isTrue();
                }
                default -> throw new AssertionError("unexpected kind " + e);
            }
        }
        assertThat(wrongSum).isEqualTo(6);
        assertThat(sawRight).isTrue();
        long alphaToday = 0;
        for (JsonNode e : entries) {
            if ("WRONG_TRIES".equals(e.get("kind").asText()) && alpha.membershipId().toString().equals(e.get("byMembershipId").asText())
                    && e.get("count").asInt() == 2) {
                alphaToday++;
            }
        }
        assertThat(alphaToday).as("alpha's two tries of today are one entry").isOne();
        // the history writes nothing and the record-access log is untouched
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(count(clinic, "select count(*) from patient.record_access")).isEqualTo(access);
        // the PIN's owner, a colleague and every other role are refused
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            Pn other = newPn(clinic, role);
            JsonNode refused = problem(call(HttpMethod.GET, historyPath(c.p()), other.auth(), null), 403);
            assertType(refused, "forbidden");
            assertThat(refused.get("detail").asText()).as(role).isEqualTo(ONLY_OWNER);
        }
        JsonNode self = problem(call(HttpMethod.GET, historyPath(c.p()), c.p().auth(), null), 403);
        assertThat(self.get("detail").asText()).isEqualTo(ONLY_OWNER);
    }

    @Test
    void theHistoryOfAnotherClinicsOrAnUnknownMemberIs404AndASwitchedOffCallerKeepsTheName() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        Pn alpha = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        Colleague foreign = colleague(other);
        wrong(alpha, c, 1);
        String alphaName = displayName(alpha);
        JsonNode foreign404 = problem(call(HttpMethod.GET, historyPath(foreign.p()), clinic.owner(), null), 404);
        assertType(foreign404, "not-found");
        problem(call(HttpMethod.GET, TEAM + "/" + UUID.randomUUID() + "/pin/colleague-tries", clinic.owner(), null), 404);
        // access removed: the entries stay under the person's name
        ok(HttpMethod.DELETE, TEAM + "/" + alpha.membershipId(), clinic.owner(), null, 204);
        JsonNode h = ok(HttpMethod.GET, historyPath(c.p()), clinic.owner(), null, 200);
        assertThat(h.get("entries").get(0).get("byName").asText()).isEqualTo(alphaName);
        assertThat(h.get("entries").get(0).get("byName").asText()).isNotEqualTo("Former staff member");
        // an empty history is an empty list, not an error
        Colleague quiet = colleague(clinic);
        JsonNode empty = ok(HttpMethod.GET, historyPath(quiet.p()), clinic.owner(), null, 200);
        assertThat(empty.get("entries")).isEmpty();
        assertThat(empty.get("wrongTries7d").asInt()).isZero();
        assertThat(empty.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void theHistoryHoldsAtMostOneHundredEntriesAndSaysWhenItIsCut() throws Exception {
        Clinic clinic = hospital();
        Colleague c = colleague(clinic);
        Pn caller = newPn(clinic, NURSE);
        historic(c.p(), null, "LOCKED", "1 day", 100);
        JsonNode exact = ok(HttpMethod.GET, historyPath(c.p()), clinic.owner(), null, 200);
        assertThat(exact.get("entries")).hasSize(100);
        assertThat(exact.get("truncated").asBoolean()).isFalse();
        wrong(caller, c, 1);
        JsonNode cut = ok(HttpMethod.GET, historyPath(c.p()), clinic.owner(), null, 200);
        assertThat(cut.get("entries")).hasSize(100);
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        assertThat(cut.get("entries").get(0).get("kind").asText()).as("latest first, the newest is kept").isEqualTo("WRONG_TRIES");
        // a LOCKED entry never names anyone
        JsonNode locked = cut.get("entries").get(1);
        assertThat(locked.get("kind").asText()).isEqualTo("LOCKED");
        assertThat(locked.get("byName").isNull()).isTrue();
        assertThat(locked.get("byMembershipId").isNull()).isTrue();
    }

    @Test
    void theTeamListCarriesTheSevenDayFigureForTheOwnerOnlyAndAResetDoesNotClearIt() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        Colleague quiet = colleague(clinic);
        wrong(caller, c, 3);
        historic(c.p(), caller, "WRONG", "8 days", 2);
        JsonNode ownerView = teamRow(clinic, clinic.owner(), c.p().membershipId());
        assertThat(ownerView.get("pin").get("colleagueTries7d").asInt()).isEqualTo(3);
        assertThat(teamRow(clinic, clinic.owner(), quiet.p().membershipId()).get("pin").get("colleagueTries7d").asInt()).isZero();
        assertThat(teamRow(clinic, clinic.owner(), caller.membershipId()).get("pin").get("colleagueTries7d").asInt()).isZero();
        for (Pn viewer : new Pn[] {caller, c.p(), newPn(clinic, "DOCTOR"), newPn(clinic, "FRONT_DESK"), newPn(clinic, "BILLING")}) {
            JsonNode row = teamRow(clinic, viewer.auth(), c.p().membershipId());
            if (viewer.membershipId().equals(c.p().membershipId())) {
                // the person's own row: the figure is null even though three tries were made (the number reaches them only through the notice)
                assertThat(row.get("pin").has("colleagueTries7d")).as("own row has the key").isTrue();
                assertThat(row.get("pin").get("colleagueTries7d").isNull()).as("own row gets null").isTrue();
            } else {
                // SAH-31 (ADR-010 D12): another person's PIN object is not sent to a non-owner at all
                assertThat(row.has("pin")).as(viewer.role() + " gets no PIN object of another person").isFalse();
            }
            assertThat(teamRow(clinic, viewer.auth(), viewer.membershipId()).get("pin").get("colleagueTries7d").isNull())
                    .as(viewer.role() + " own row").isTrue();
        }
        resetPin(clinic, c.p().membershipId());
        assertThat(teamRow(clinic, clinic.owner(), c.p().membershipId()).get("pin").get("colleagueTries7d").asInt())
                .as("evidence is not cleared by a reset").isEqualTo(3);
        // another clinic's tries never count here
        Clinic other = hospital();
        Colleague theirs = colleague(other);
        Pn theirCaller = newPn(other, NURSE);
        wrong(theirCaller, theirs, 2);
        assertThat(teamRow(other, other.owner(), theirs.p().membershipId()).get("pin").get("colleagueTries7d").asInt()).isEqualTo(2);
        assertThat(teamRow(clinic, clinic.owner(), c.p().membershipId()).get("pin").get("colleagueTries7d").asInt()).isEqualTo(3);
    }

    // ---------------- privacy ----------------

    @Test
    void noPinNameOrMobileReachesAnyLogAuditRowOrEventRow() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        String bad = wrongFor(c.pin());
        try (LogCapture logs = new LogCapture()) {
            wrong(caller, c, 4);
            check(caller, c.p(), bad);
            check(caller, c.p(), c.pin());
            lockEnded(c.p());
            check(caller, c.p(), c.pin());
            notice(c.p());
            ok(HttpMethod.GET, historyPath(c.p()), clinic.owner(), null, 200);
            String text = logs.allText();
            assertThat(text).doesNotContain(displayName(caller)).doesNotContain(displayName(c.p()))
                    .doesNotContain(caller.mobile()).doesNotContain(c.p().mobile());
            assertThat(hasBarePin(text, c.pin())).as("the right PIN in a log").isFalse();
            assertThat(hasBarePin(text, bad)).as("the wrong PIN in a log").isFalse();
        }
        String audits = allAuditText(clinic);
        assertThat(audits).doesNotContain(displayName(caller)).doesNotContain(caller.mobile()).doesNotContain(c.p().mobile());
        assertThat(hasBarePin(audits, c.pin())).isFalse();
        assertThat(hasBarePin(audits, bad)).isFalse();
        String eventText = values(clinic, "select coalesce(string_agg(e::text, E'\\n'), '') from " + EVENTS + " e").get(0);
        assertThat(eventText).doesNotContain(displayName(caller)).doesNotContain(caller.mobile()).doesNotContain(c.p().mobile())
                .doesNotContain("$2a$");
        assertThat(hasBarePin(eventText, c.pin())).isFalse();
        assertThat(hasBarePin(eventText, bad)).isFalse();
        String pinRowText = values(clinic, "select coalesce(string_agg((s.run_has_colleague_try::text || s.lock_by_colleagues::text), ','), '') "
                + "from platform.staff_pin s").get(0);
        assertThat(pinRowText).doesNotContain(c.pin());
    }

    private String displayName(Pn p) throws SQLException {
        return nameOf(p.clinic(), p.userId());
    }

    private static java.util.Set<String> keysOf(JsonNode n) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        n.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
