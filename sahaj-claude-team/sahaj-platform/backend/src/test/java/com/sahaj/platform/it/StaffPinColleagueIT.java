package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-32 the second-nurse PIN check that IPD calls ({@code platform.api.StaffPinPort.checkColleague}, ADR-011 D7) on the real
 * {@code sahaj_test} database: a right PIN answers the colleague's user id, a wrong one is counted on the colleague's own row
 * and audited with the checking nurse, the colleague's lockout (5 wrong tries, 15 minutes, the third lock in 24 hours
 * switches the PIN off), who can be named (only an ACTIVE NURSE of the same clinic who is not the caller; every other case
 * is NOT_ELIGIBLE and writes no counter, no audit and no limit entry), the per-caller limits (5 failed checks per 15 minutes,
 * 10 per 24 hours, wrong tries only) and the colleague's own limit, parallel wrong tries, a check inside a transaction, the
 * texts of {@link ColleaguePinCheck#requireOk()}, ids that stay whole in the audit rows, and no PIN in any log. Test plan:
 * docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinColleagueIT extends PinTestSupport {

    private static final String NURSE = "NURSE";

    @Autowired
    private StaffPinPort port;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String wrongFor(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    private record Colleague(Pn p, String pin) {
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

    private void wrongTries(Pn caller, Colleague c, int n) {
        for (int i = 0; i < n; i++) {
            assertThat(check(caller, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
        }
    }

    private JsonNode after(Clinic clinic, String action, int index) throws Exception {
        return objectMapper.readTree(audit(clinic, action).get(index)[3]);
    }

    // ---------------- ok ----------------

    @Test
    void aRightPinIsOkWithTheColleaguesUserIdAndWritesNothingWhenThereWereNoWrongTries() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        PinRow before = pinRow(clinic, c.p().membershipId());
        long audits = allAudits(clinic);
        ColleaguePinCheck r = check(caller, c.p(), c.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.isOk()).isTrue();
        assertThat(r.pin().triesLeft()).isEqualTo(5);
        assertThat(r.colleagueUserId()).isEqualTo(c.p().userId());
        assertThat(r.requireOk()).as("the user id, not the membership id").isEqualTo(c.p().userId()).isNotEqualTo(c.p().membershipId());
        assertThat(pinRow(clinic, c.p().membershipId())).isEqualTo(before);
        assertThat(allAudits(clinic)).isEqualTo(audits);
        assertThat(r.toString()).contains("OK").doesNotContain(c.pin()).doesNotContain(c.p().userId().toString())
                .doesNotContain(c.p().membershipId().toString());
    }

    @Test
    void aRightPinAfterWrongTriesClearsTheCountOnTheColleagueRow() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        wrongTries(caller, c, 3);
        assertThat(pinRow(clinic, c.p().membershipId()).failed()).isEqualTo(3);
        assertThat(check(caller, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
        assertThat(pinRow(clinic, c.p().membershipId()).failed()).isZero();
    }

    // ---------------- wrong ----------------

    @Test
    void aWrongPinIsCountedOnTheColleaguesRowAuditedWithTheCheckingNurseAndSpeaksInTheSecondNurseWording() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        String bad = wrongFor(c.pin());
        ColleaguePinCheck r = check(caller, c.p(), bad);
        assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
        assertThat(r.pin().triesLeft()).as("the outcome still carries the count for the audit").isEqualTo(4);
        assertThat(r.colleagueUserId()).isNull();
        assertThat(pinRow(clinic, c.p().membershipId()).failed()).isOne();
        assertThat(pinRow(clinic, caller.membershipId()).exists()).isFalse();
        assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("wrong-pin");
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.subject()).isEqualTo(StaffPinException.Subject.COLLEAGUE);
            assertThat(e.getMessage()).as("no tries-left count for a colleague (review S4)").isEqualTo("That PIN is not right.")
                    .doesNotContainPattern("[0-9]");
        });
        List<String[]> rows = audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("STAFF_PIN");
        assertThat(rows.get(0)[1]).isEqualTo(c.p().membershipId().toString());
        assertThat(rows.get(0)[4]).as("the person who typed the try").isEqualTo(caller.userId().toString());
        JsonNode a = after(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", 0);
        assertThat(a.get("membershipId").asText()).isEqualTo(c.p().membershipId().toString());
        assertThat(a.get("checkedByMembershipId").asText()).isEqualTo(caller.membershipId().toString());
        assertThat(a.get("triesLeft").asInt()).isEqualTo(4);
        assertThat(rows.get(0)[3]).doesNotContain(bad).doesNotContain(c.pin());
        for (int left = 3; left >= 1; left--) {
            ColleaguePinCheck next = check(caller, c.p(), bad);
            assertThat(next.pin().triesLeft()).isEqualTo(left);
        }
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).hasSize(4);
    }

    @Test
    void theFifthWrongTryLocksTheColleagueForFifteenMinutesAndTheLockAuditNamesTheCheckingNurse() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Pn other = ownerPn(clinic);
        Colleague c = colleague(clinic);
        wrongTries(caller, c, 4);
        ColleaguePinCheck fifth = check(other, c.p(), wrongFor(c.pin()));
        assertThat(fifth.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(fifth.pin().retryAfterSeconds()).isBetween(800L, 900L);
        PinRow row = pinRow(clinic, c.p().membershipId());
        assertThat(row.lockedNow()).isTrue();
        assertThat(row.lockCount()).as("SAH-38: a colleague-caused lock never moves the lock count").isZero();
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).as("the locking try is audited too").hasSize(5);
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(1);
        JsonNode lock = after(clinic, "PLATFORM_PIN_LOCKED", 0);
        assertThat(lock.get("checkedByMembershipId").asText()).as("the fifth try was the owner's").isEqualTo(other.membershipId().toString());
        assertThat(lock.get("membershipId").asText()).isEqualTo(c.p().membershipId().toString());
        assertThatThrownBy(fifth::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-locked");
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.getMessage()).startsWith("The second nurse's PIN is locked after 5 wrong tries. Choose another nurse, or try again at ")
                    .endsWith(".").doesNotContain("Your PIN");
            assertThat(e.retryAfterSeconds()).isBetween(800L, 900L);
        });
        // a right PIN while locked is LOCKED and counts nothing, for any caller
        freshPinAllowance(caller);
        ColleaguePinCheck still = check(caller, c.p(), c.pin());
        assertThat(still.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(check(other, c.p(), c.pin()).outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(pinRow(clinic, c.p().membershipId()).failed()).isEqualTo(row.failed());
        assertThat(pinRow(clinic, c.p().membershipId()).lockCount()).isZero();
        // after the lock the right PIN is OK again
        lockEnded(c.p());
        assertThat(check(caller, c.p(), c.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void aColleagueCausedFifthTryAfterTwoLocksIsOnlyALockAndNeverSwitchesThePinOff() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        forceCounters(c.p(), 4, 2);
        ColleaguePinCheck r = check(caller, c.p(), wrongFor(c.pin()));
        assertThat(r.outcome()).as("SAH-38 (ADR-014 D6): locked, not switched off").isEqualTo(Outcome.LOCKED);
        PinRow row = pinRow(clinic, c.p().membershipId());
        assertThat(row.hasPin()).isTrue();
        assertThat(row.lockCount()).isEqualTo(2);
        assertThat(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).isEmpty();
    }

    @Test
    void theThirdOwnSessionLockInTwentyFourHoursSwitchesThePinOffAndEveryNotSetReasonHasTheSameSecondNurseText()
            throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        forceCounters(c.p(), 4, 2);
        assertThat(port.check(clinic.tenantId(), c.p().userId(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.NOT_SET);
        ColleaguePinCheck r = check(caller, c.p(), c.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(r.pin().notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
        assertThat(pinRow(clinic, c.p().membershipId()).hasPin()).isFalse();
        assertThat(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).hasSize(1);
        assertThat(after(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS", 0).get("causedBy").asText()).isEqualTo("SELF");
        assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-not-set");
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("The second nurse has not set a PIN yet. Choose another nurse.");
        });
        // never set, and reset by the owner: the same outcome and the same text, nothing counted
        Pn never = newPn(clinic, NURSE);
        long audits = allAudits(clinic);
        ColleaguePinCheck n = check(caller, never, "4827");
        assertThat(n.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(n.pin().notSetReason()).isEqualTo(NotSetReason.NEVER_SET);
        Colleague reset = colleague(clinic);
        resetPin(clinic, reset.p().membershipId());
        audits = allAudits(clinic);
        ColleaguePinCheck x = check(caller, reset.p(), reset.pin());
        assertThat(x.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(x.pin().notSetReason()).isEqualTo(NotSetReason.RESET_BY_OWNER);
        for (ColleaguePinCheck notSet : List.of(n, x)) {
            assertThatThrownBy(notSet::requireOk).isInstanceOfSatisfying(StaffPinException.class,
                    e -> assertThat(e.getMessage()).isEqualTo("The second nurse has not set a PIN yet. Choose another nurse."));
        }
        assertThat(allAudits(clinic)).as("a missing PIN is not a wrong try: no audit row").isEqualTo(audits);
        assertThat(pinRow(clinic, never.membershipId()).exists()).isFalse();
    }

    // ---------------- who can be named ----------------

    @Test
    void everyCaseThatIsNotAnActiveNurseOfTheClinicOtherThanTheCallerIsNotEligibleAndWritesNoCounterNoAuditAndNoLimitEntry()
            throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Clinic otherClinic = hospital();
        Colleague foreign = colleague(otherClinic);
        Pn owner = ownerPn(clinic);
        Pn doctor = newPn(clinic, "DOCTOR");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String ownerPin = goodPin(owner);
        setPin(owner, ownerPin);
        String doctorPin = goodPin(doctor);
        setPin(doctor, doctorPin);
        String pharmacistPin = goodPin(pharmacist);
        setPin(pharmacist, pharmacistPin);
        Colleague inactive = colleague(clinic);
        setMembershipStatus(clinic.tenantId(), inactive.p().userId(), "INACTIVE");
        Colleague active = colleague(clinic);
        long audits = allAudits(clinic) + allAudits(otherClinic);
        String pinRowsBefore = values(clinic, "select coalesce(string_agg(membership_id::text || failed_count || '/' || lock_count || '/' "
                + "|| version, ',' order by membership_id), '') from platform.staff_pin").get(0);

        List<ColleaguePinCheck> results = new ArrayList<>();
        UUID tenant = clinic.tenantId();
        results.add(port.checkColleague(tenant, caller.userId(), foreign.p().membershipId(), NURSE, foreign.pin()));
        results.add(port.checkColleague(tenant, caller.userId(), UUID.randomUUID(), NURSE, "4827"));
        results.add(port.checkColleague(tenant, caller.userId(), null, NURSE, "4827"));
        results.add(port.checkColleague(tenant, caller.userId(), inactive.p().membershipId(), NURSE, inactive.pin()));
        results.add(port.checkColleague(tenant, caller.userId(), owner.membershipId(), NURSE, ownerPin));
        results.add(port.checkColleague(tenant, caller.userId(), doctor.membershipId(), NURSE, doctorPin));
        results.add(port.checkColleague(tenant, caller.userId(), pharmacist.membershipId(), NURSE, pharmacistPin));
        results.add(port.checkColleague(tenant, caller.userId(), caller.membershipId(), NURSE, "4827"));
        // the caller without an active membership of the clinic, a random caller, the other clinic's id as the tenant
        results.add(port.checkColleague(tenant, UUID.randomUUID(), active.p().membershipId(), NURSE, active.pin()));
        results.add(port.checkColleague(otherClinic.tenantId(), caller.userId(), active.p().membershipId(), NURSE, active.pin()));
        for (ColleaguePinCheck r : results) {
            assertThat(r.outcome()).as(r.toString()).isEqualTo(Outcome.NOT_ELIGIBLE);
            assertThat(r.colleagueUserId()).isNull();
            assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                assertThat(e.errors()).hasSize(1);
                assertThat(e.errors().get(0).field()).isEqualTo("secondNurseId");
                assertThat(e.errors().get(0).message()).isEqualTo("Choose a nurse from the list.");
                assertThat(e.firstMessageAsDetail()).isTrue();
            });
        }
        assertThat(allAudits(clinic) + allAudits(otherClinic)).isEqualTo(audits);
        assertThat(values(clinic, "select coalesce(string_agg(membership_id::text || failed_count || '/' || lock_count || '/' || version, "
                + "',' order by membership_id), '') from platform.staff_pin").get(0)).isEqualTo(pinRowsBefore);
        // no limit entry either: after ten not-eligible calls five wrong tries and then a right one still pass normally
        wrongTries(caller, active, 2);
        assertThat(check(caller, active.p(), active.pin()).outcome()).isEqualTo(Outcome.OK);
        // the same caller as the colleague of another membership of the same person is also refused (not the caller)
        assertThat(port.checkColleague(tenant, caller.userId(), caller.membershipId(), NURSE, "4827").outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);
    }

    @Test
    void theRequiredRoleIsPartOfTheCallAnOwnerCanBeCheckedAsOwnerAndAnUnknownRoleIsAProgrammingError() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Pn owner = ownerPn(clinic);
        String ownerPin = goodPin(owner);
        setPin(owner, ownerPin);
        assertThat(port.checkColleague(clinic.tenantId(), caller.userId(), owner.membershipId(), "OWNER", ownerPin).outcome())
                .isEqualTo(Outcome.OK);
        assertThat(port.checkColleague(clinic.tenantId(), caller.userId(), owner.membershipId(), NURSE, ownerPin).outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);
        assertThatThrownBy(() -> port.checkColleague(clinic.tenantId(), caller.userId(), owner.membershipId(), "WIZARD", ownerPin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> port.checkColleague(null, caller.userId(), owner.membershipId(), NURSE, ownerPin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> port.checkColleague(clinic.tenantId(), null, owner.membershipId(), NURSE, ownerPin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- limits ----------------

    @Test
    void fiveFailedChecksInFifteenMinutesStopTheCallerBeforeAnyHashAndTheyCountWrongTriesOnlyAndOtherCallersAreNotStopped()
            throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Pn other = ownerPn(clinic);
        Colleague a = colleague(clinic);
        Colleague b = colleague(clinic);
        Colleague spare = colleague(clinic);
        // right PINs, locked PINs, missing PINs and refusals never use the allowance
        for (int i = 0; i < 7; i++) {
            assertThat(check(caller, spare.p(), spare.pin()).outcome()).isEqualTo(Outcome.OK);
        }
        wrongTries(caller, a, 3);
        wrongTries(caller, b, 2);
        long audits = allAudits(clinic);
        ColleaguePinCheck limited = check(caller, spare.p(), spare.pin());
        assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(limited.pin().retryAfterSeconds()).isPositive();
        assertThat(check(caller, spare.p(), wrongFor(spare.pin())).outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(check(caller, a.p(), a.pin()).outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThatThrownBy(limited::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("rate-limit");
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.getMessage()).isEqualTo(PIN_RATE);
        });
        assertThat(pinRow(clinic, spare.p().membershipId()).failed()).as("no hash, no count").isZero();
        assertThat(pinRow(clinic, a.p().membershipId()).failed()).isEqualTo(3);
        assertThat(allAudits(clinic)).isEqualTo(audits);
        // a not-eligible call or a missing person is answered first: the limit is looked at before who is named
        assertThat(check(caller, newPn(clinic, NURSE), "4827").outcome()).isEqualTo(Outcome.RATE_LIMITED);
        // another caller is free; this caller is free again when the 15 minutes are over
        assertThat(check(other, spare.p(), spare.pin()).outcome()).isEqualTo(Outcome.OK);
        forgetColleagueShortWindow(caller);
        assertThat(check(caller, spare.p(), spare.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void tenFailedChecksInTwentyFourHoursStopTheCallerEvenWhenTheFifteenMinuteBucketIsFreeAndTheyCannotSwitchAPinOffAlone()
            throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague a = colleague(clinic);
        Colleague b = colleague(clinic);
        Colleague c = colleague(clinic);
        Colleague d = colleague(clinic);
        Colleague spare = colleague(clinic);
        wrongTries(caller, a, 3);
        wrongTries(caller, b, 2);
        assertThat(check(caller, spare.p(), spare.pin()).outcome()).isEqualTo(Outcome.RATE_LIMITED);
        forgetColleagueShortWindow(caller);
        wrongTries(caller, c, 3);
        wrongTries(caller, d, 2);
        forgetColleagueShortWindow(caller);
        assertThat(check(caller, spare.p(), spare.pin()).outcome()).as("the 24-hour bucket holds ten").isEqualTo(Outcome.RATE_LIMITED);
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).hasSize(10);
        for (Colleague x : List.of(a, b, c, d)) {
            assertThat(pinRow(clinic, x.p().membershipId()).hasPin()).isTrue();
            assertThat(pinRow(clinic, x.p().membershipId()).lockCount()).isZero();
        }
        // SAH-38: one caller can make 5 wrong tries on ONE colleague in 7 days (the pair cap), so a second lock by the same
        // caller is no longer reachable; the 5 tries are one colleague lock that never counts toward the switch-off
        Pn lone = newPn(clinic, NURSE);
        Colleague target = colleague(clinic);
        wrongTries(lone, target, 4);
        assertThat(check(lone, target.p(), wrongFor(target.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(pinRow(clinic, target.p().membershipId()).lockCount()).as("SAH-38: a colleague lock never counts").isZero();
        lockEnded(target.p());
        forgetColleagueShortWindow(lone);
        assertThat(check(lone, target.p(), target.pin()).outcome()).as("the pair is at its cap of 5 for 7 days").isEqualTo(Outcome.PAIR_LIMIT);
        assertThat(check(lone, target.p(), wrongFor(target.pin())).outcome()).isEqualTo(Outcome.PAIR_LIMIT);
        assertThat(pinRow(clinic, target.p().membershipId()).lockCount()).isZero();
        assertThat(pinRow(clinic, target.p().membershipId()).hasPin()).isTrue();
    }

    @Test
    void theColleaguesOwnFailedCheckBucketIsGoneSoTenWrongTriesOfOthersStopNeitherAnotherCallerNorTheirOwnChecks() throws Exception {
        Clinic clinic = hospital();
        Pn c1 = newPn(clinic, NURSE);
        Pn c2 = newPn(clinic, NURSE);
        Pn c3 = ownerPn(clinic);
        Colleague target = colleague(clinic);
        // four wrong tries, the counters cleared by the owner's tool, again: ten wrong tries in the bucket without a lock
        wrongTries(c1, target, 4);
        forceCounters(target.p(), 0, 0);
        wrongTries(c2, target, 4);
        forceCounters(target.p(), 0, 0);
        wrongTries(c3, target, 2);
        forceCounters(target.p(), 0, 0);
        Pn fresh = newPn(clinic, NURSE);
        // SAH-38 (ADR-014 D7): the colleague.s own failed-check bucket (10 per 5 minutes) is no longer read or filled by colleague
        // checks, so ten wrong tries by others stop nobody and the colleague.s own checks go on working
        ColleaguePinCheck r = check(fresh, target.p(), target.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(port.check(clinic.tenantId(), target.p().userId(), target.pin()).outcome()).isEqualTo(Outcome.OK);
    }

    // ---------------- parallel ----------------

    @Test
    void parallelWrongTriesOfOneCallerAreAllCountedOnTheColleagueAndBeforeTheLimit() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        List<ColleaguePinCheck> results = runParallel(3, () -> check(caller, c.p(), wrongFor(c.pin())));
        List<Integer> left = new ArrayList<>();
        for (ColleaguePinCheck r : results) {
            assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
            left.add(r.pin().triesLeft());
        }
        assertThat(left).containsExactlyInAnyOrder(4, 3, 2);
        assertThat(pinRow(clinic, c.p().membershipId()).failed()).isEqualTo(3);
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).hasSize(3);
    }

    @Test
    void aBurstOfParallelWrongTriesCannotPassTheCallersLimitOfFive() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague a = colleague(clinic);
        Colleague b = colleague(clinic);
        Colleague c = colleague(clinic);
        List<ColleaguePinCheck> results = runParallel(9, () -> {
            Colleague target = List.of(a, b, c).get((int) (Thread.currentThread().getId() % 3));
            return check(caller, target.p(), wrongFor(target.pin()));
        });
        long limited = results.stream().filter(r -> r.outcome() == Outcome.RATE_LIMITED).count();
        long others = results.stream().filter(r -> r.outcome() == Outcome.WRONG || r.outcome() == Outcome.LOCKED).count();
        assertThat(others + limited).as("every answer is a wrong try, a lock or the limit").isEqualTo(9);
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).as("no more than five failed checks get through")
                .hasSizeLessThanOrEqualTo(5);
        assertThat(limited).isGreaterThanOrEqualTo(4);
    }

    private <T> List<T> runParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- inside a transaction ----------------

    @Test
    void aColleagueCheckInsideATransactionThrowsAndCountsNothing() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        PinRow before = pinRow(clinic, c.p().membershipId());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> check(caller, c.p(), wrongFor(c.pin()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside any transaction");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> check(caller, c.p(), c.pin()))).isInstanceOf(IllegalStateException.class);
        assertThat(pinRow(clinic, c.p().membershipId())).isEqualTo(before);
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).isEmpty();
    }

    // ---------------- ids and logs ----------------

    @Test
    void theAuditRowKeepsIdsWholeEvenWhenTheLastGroupOfAnIdIsTwelveDigits() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        // sahaj_test is never cleaned: a new random head every run, the tail of twelve digits is the point of the test
        String head = UUID.randomUUID().toString();
        String id = head.substring(0, 18) + "-8" + head.substring(20, 23) + "-" + (100000000000L + Math.floorMod(System.nanoTime(), 899999999999L));
        Session session = signUp(uniqueMobile());
        try (Connection connection = db().app()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement insert = connection.prepareStatement("insert into platform.membership (id, tenant_id, user_id, role, "
                    + "display_name) values (?::uuid, ?::uuid, ?::uuid, 'NURSE', 'Digits Nurse')")) {
                insert.setString(1, id);
                insert.setString(2, clinic.tenantId().toString());
                insert.setString(3, session.userId().toString());
                insert.executeUpdate();
            }
            connection.commit();
        }
        Pn digits = pn(clinic, new Staff(session.userId(), "Digits Nurse", NURSE, tokenOf(clinic, session.userId(), NURSE)));
        assertThat(digits.membershipId()).hasToString(id);
        String pin = goodPin(digits);
        setPin(digits, pin);
        assertThat(check(caller, digits, wrongFor(pin)).outcome()).isEqualTo(Outcome.WRONG);
        List<String[]> rows = audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(id);
        assertThat(after(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", 0).get("membershipId").asText())
                .as("not masked as a mobile number").isEqualTo(id);
        // and the same id as the checker
        Pn digitsCaller = digits;
        Colleague c = colleague(clinic);
        assertThat(check(digitsCaller, c.p(), wrongFor(c.pin())).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(after(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", 1).get("checkedByMembershipId").asText()).isEqualTo(id);
    }

    @Test
    void noPinNoMobileAndNoNameIsLoggedByAColleagueCheckAndTheLogLineHoldsIdsAndTheOutcomeOnly() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, NURSE);
        Colleague c = colleague(clinic);
        String bad = wrongFor(c.pin());
        try (LogCapture logs = new LogCapture()) {
            check(caller, c.p(), bad);
            check(caller, c.p(), c.pin());
            check(caller, newPn(clinic, NURSE), "4827");
            wrongTries(caller, c, 1);
            String text = logs.allText();
            assertThat(hasBarePin(text, bad)).isFalse();
            assertThat(hasBarePin(text, c.pin())).isFalse();
            assertThat(text).doesNotContain(c.p().mobile()).doesNotContain(caller.mobile()).doesNotContain(nameOf(clinic, c.p().userId()))
                    .doesNotContain(nameOf(clinic, caller.userId()));
            assertThat(text).contains("staff-pin-colleague-check");
        }
        assertThat(allAuditText(clinic)).doesNotContain(c.p().mobile());
        assertThat(hasBarePin(allAuditText(clinic), bad)).isFalse();
        assertThat(hasBarePin(allAuditText(clinic), c.pin())).isFalse();
    }
}
