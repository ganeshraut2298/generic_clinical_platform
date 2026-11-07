package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-32 the second nurse of a high-alert dose through the API (AC6, ADR-011 D4 steps 4 to 7 and D7), on the real
 * {@code sahaj_test} database: a right PIN of the named nurse records the dose with the nurse's USER id as the second check,
 * a wrong PIN is counted on the colleague's own row although the request fails, the colleague's lockout (5 wrong tries lock
 * for 15 minutes, the third lock in 24 hours switches the PIN off), the per-caller limits (5 failed colleague checks in 15
 * minutes, 10 in 24 hours), who may be named (only an ACTIVE NURSE of the clinic, never the caller), parallel wrong tries,
 * what is ignored for a medicine that is not high-alert, and the audit rows with the checking nurse's membership. The port
 * itself is tested in {@link StaffPinColleagueIT}. Test plan: docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class DoseSecondNurseIT extends MarSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private com.sahaj.platform.api.StaffPinPort port;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(IST);

    private record Fix(Ward w, String hep) {
    }

    private Fix fix() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "od");
        clockAt(8, 5);
        return new Fix(w, hep);
    }

    private static String wrong(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    private JsonNode auditRow(Clinic clinic, String action, int index) throws Exception {
        return objectMapper.readTree(audit(clinic, action).get(index)[3]);
    }

    // ---------------- a right PIN ----------------

    @Test
    void aRightPinOfAnotherNurseRecordsTheHighAlertDoseWithTheSecondNursesUserIdAndEverythingTheCriterionNames() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        PinRow pinBefore = pinRow(w.clinic(), w.nurse2().membershipId());
        DSnap before = dsnap(w);
        JsonNode a = doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));

        assertThat(a.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(a.get("dose").get("recordedByName").asText()).isEqualTo(displayName(w.nurse()));
        assertThat(a.get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(w.nurse2()));
        DoseDb row = doseDb(w.clinic(), f.hep(), ist(8, 0));
        assertThat(row.highAlert()).isTrue();
        assertThat(row.secondCheckBy()).as("the user id, not the membership id").isEqualTo(w.nurse2().userId().toString());
        assertThat(row.secondCheckBy()).isNotEqualTo(w.nurse2().membershipId().toString());
        assertThat(row.recordedBy()).isEqualTo(w.nurse().userId().toString());
        assertThat(row.scan()).isEqualTo(w.uhid());
        assertThat(eventLines(w.clinic(), w.adm().id())).contains("give|Gave Inj. Heparin 5,000 IU (8 AM dose) · identity checked against UHID"
                + " · second check by " + displayName(w.nurse2()));
        JsonNode after = auditRow(w.clinic(), "IPD_DOSE_GIVEN", 0);
        assertThat(after.get("highAlert").asBoolean()).isTrue();
        assertThat(after.get("secondCheckUserId").asText()).isEqualTo(w.nurse2().userId().toString());
        assertThat(slot(a.get("mar"), f.hep(), 0).get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(w.nurse2()));
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses() + 1);
        assertThat(now.events()).isEqualTo(before.events() + 1);
        assertThat(now.audits()).as("one dose audit row, no PIN audit row for a right PIN").isEqualTo(before.audits() + 1);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId())).as("a right PIN with no wrong tries writes nothing").isEqualTo(pinBefore);
        assertThat(pinRow(w.clinic(), w.nurse().membershipId()).exists()).as("the giver needs no PIN of their own").isFalse();
    }

    /** R38-1: a second nurse with a 200-character name and the longest high-alert medicine never break ck_clinical_event_text. */
    @Test
    void aSecondNurseWithA200CharacterNameAndTheLongestHighAlertMedicineStillRecordsTheDoseAndTheEventTextFitsThe300Characters()
            throws Exception {
        Ward w = ward();
        StringBuilder name = new StringBuilder();
        for (int i = 0; name.length() < 200; i++) {
            name.append(i % 2 == 0 ? "Priyadarshini " : "Venkataraghavan ");
        }
        String longName = name.substring(0, 200).stripTrailing();
        while (longName.length() < 200) {
            longName = longName + "a";
        }
        assertThat(longName).hasSize(200);
        Pn longNurse = pn(w.clinic(), staff(w.clinic(), "NURSE", longName));
        String pin = goodPin(longNurse);
        setPin(longNurse, pin);
        assertThat(displayName(longNurse)).isEqualTo(longName);
        List<String> drugs = values(w.clinic(), "select key || '|' || char_length(name) from ipd.drug where high_alert is not null "
                + "order by char_length(name) desc, key limit 1");
        String drugKey = drugs.get(0).split("\\|")[0];
        int drugNameLength = Integer.parseInt(drugs.get(0).split("\\|")[1]);
        String order = activeOrder(w, drugKey, "od");
        clockAt(8, 5);

        JsonNode a = doseOk(w.nurse().auth(), order, givenHighAlert(w, ist(8, 0), longNurse, pin));

        assertThat(a.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(doseDb(w.clinic(), order, ist(8, 0)).secondCheckBy()).isEqualTo(longNurse.userId().toString());
        List<String> gave = eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("give|")).toList();
        assertThat(gave).hasSize(1);
        String text = gave.get(0).substring("give|".length());
        assertThat(text.length()).as("the longest medicine name is " + drugNameLength + " characters").isLessThanOrEqualTo(300);
        assertThat(text).startsWith("Gave ").contains(" · identity checked against UHID · second check by Priyadarshini ");
        assertThat(text).as("the name is shortened with an ellipsis, the rest of the wording is not cut").endsWith("…");
        assertThat(count(w.clinic(), "select count(*) from ipd.clinical_event where admission_id = ?::uuid and char_length(text) > 300",
                w.adm().id())).isZero();
        // the audit row still names the checking nurse's USER id, whatever the event text shows
        assertThat(auditRow(w.clinic(), "IPD_DOSE_GIVEN", 0).get("secondCheckUserId").asText()).isEqualTo(longNurse.userId().toString());
    }

    @Test
    void theOwnerMayGiveAHighAlertDoseWithANursesPinAndEveryOtherNurseOfTheClinicMayBeTheSecondCheck() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        JsonNode a = doseOk(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
        assertThat(a.get("dose").get("recordedByName").asText()).isEqualTo(ownerStaff(w.clinic()).name());
        assertThat(doseDb(w.clinic(), f.hep(), ist(8, 0)).secondCheckBy()).isEqualTo(w.nurse2().userId().toString());
        // a nurse who is the second check of one dose may give another dose with the first nurse as the check
        String ins = activeOrder(w, "ins", "od");
        NurseWithPin third = nurseWithPin(w.clinic());
        JsonNode b = doseOk(third.nurse().auth(), ins, givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
        assertThat(b.get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(w.nurse2()));
    }

    // ---------------- a wrong PIN counts on the colleague (AC6, D7) ----------------

    @Test
    void aWrongPinIs403WrongPinCountedOnTheSecondNursesRowAndAuditedAlthoughTheRequestFailsAndNoDoseIsWritten() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        DSnap before = dsnap(w);
        String bad = wrong(w.nurse2Pin());
        JsonNode p = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "wrong-pin",
                "That PIN is not right.");
        assertThat(p.toString()).doesNotContain(displayName(w.nurse2())).doesNotContain(bad).doesNotContain(w.nurse2().mobile());
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).as("counted on the colleague's row").isOne();
        assertThat(pinRow(w.clinic(), w.nurse().membershipId()).exists()).as("not on the caller's").isFalse();
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.events()).isEqualTo(before.events());
        assertThat(now.access()).isEqualTo(before.access());
        assertThat(now.audits()).as("exactly the colleague-check audit row").isEqualTo(before.audits() + 1);
        List<String[]> rows = audit(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(w.nurse2().membershipId().toString());
        JsonNode after = auditRow(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", 0);
        assertThat(keys(after)).containsExactlyInAnyOrder("membershipId", "checkedByMembershipId", "triesLeft");
        assertThat(after.get("membershipId").asText()).isEqualTo(w.nurse2().membershipId().toString());
        assertThat(after.get("checkedByMembershipId").asText()).isEqualTo(w.nurse().membershipId().toString());
        assertThat(after.get("triesLeft").asInt()).isEqualTo(4);
        assertThat(rows.get(0)[3]).doesNotContain(bad).doesNotContain(w.nurse2Pin());
        // the slot is still open and the right PIN then works and clears the wrong try
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), f.hep(), 0).get("state").asText()).isEqualTo("DUE");
        doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isZero();
    }

    @Test
    void theSecondNursesAnswerNeverCountsTriesButTheAuditDoesAndTheFifthWrongTryLocksThePinForFifteenMinutesWithTheLockAuditNamingTheCheckingNurse()
            throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        int[] left = {4, 3, 2, 1};
        for (int i = 0; i < left.length; i++) {
            JsonNode answer = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403,
                    "wrong-pin", "That PIN is not right.");
            // no count, no digit and no word about tries in the answer (review S4): a guesser is not told when to stop
            assertThat(answer.get("detail").asText()).doesNotContainPattern("[0-9]").doesNotContain("tries").doesNotContain("try left");
            assertThat(answer.toString()).doesNotContainPattern("triesLeft");
            // the audit row still holds the count for the clinic owner
            assertThat(auditRow(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", i).get("triesLeft").asInt()).isEqualTo(left[i]);
        }
        JsonNode locked = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403,
                "pin-locked", null);
        PinRow row = pinRow(w.clinic(), w.nurse2().membershipId());
        assertThat(row.lockedNow()).isTrue();
        // SAH-38 (ADR-014 D6): a lock caused by colleagues locks for 15 minutes but never moves the lock count
        assertThat(row.lockCount()).isZero();
        assertThat(values(w.clinic(), "select lock_by_colleagues::text || run_has_colleague_try::text from platform.staff_pin "
                + "where membership_id = ?::uuid", w.nurse2().membershipId().toString()).get(0)).isEqualTo("truefalse");
        Instant until = Instant.parse(values(w.clinic(), "select to_char(locked_until at time zone 'UTC', "
                + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from platform.staff_pin where membership_id = ?::uuid",
                w.nurse2().membershipId().toString()).get(0));
        assertThat(locked.get("detail").asText()).isEqualTo("The second nurse's PIN is locked after 5 wrong tries. Choose another "
                + "nurse, or try again at " + CLOCK.format(until) + ".");
        assertThat(Pattern.matches("The second nurse's PIN is locked after 5 wrong tries\\. Choose another nurse, or try again at "
                + "\\d{1,2}:\\d{2} (AM|PM)\\.", locked.get("detail").asText())).isTrue();
        assertThat(locked.get("retryAfterSeconds").asInt()).isBetween(800, 900);
        assertThat(locked.toString()).doesNotContain(displayName(w.nurse2()));
        assertThat(audit(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).as("every wrong try is audited").hasSize(5);
        List<String[]> lock = audit(w.clinic(), "PLATFORM_PIN_LOCKED");
        assertThat(lock).hasSize(1);
        JsonNode lockAfter = auditRow(w.clinic(), "PLATFORM_PIN_LOCKED", 0);
        assertThat(lockAfter.get("checkedByMembershipId").asText()).isEqualTo(w.nurse().membershipId().toString());
        assertThat(lockAfter.get("membershipId").asText()).isEqualTo(w.nurse2().membershipId().toString());
        assertThat(lockAfter.get("causedBy").asText()).isEqualTo("COLLEAGUES");
        assertThat(lockAfter.get("lockCount").asInt()).isZero();
        assertThat(lock.get(0)[3]).doesNotContain(bad).doesNotContain(w.nurse2Pin());
        assertThat(doseRows(w.clinic(), w.adm().id())).isZero();

        // while it is locked the right PIN is refused as locked too, for the same caller (after the caller's own allowance
        // is reset: the 5 failed tries used it up) and for any other caller; nothing is counted
        freshPinAllowance(w.nurse());
        JsonNode still = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), 403,
                "pin-locked", null);
        assertThat(still.get("retryAfterSeconds").asInt()).isPositive();
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), 403, "pin-locked", null);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).lockCount()).isZero();
        assertThat(audit(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).hasSize(5);
        // a lock that is over lets the right PIN through again for another caller; the caller who made the five wrong tries is at
        // the pair cap now (SAH-38, ADR-014 D3): 429 colleague-pin-limit, the PIN is not even compared
        lockEnded(w.nurse2());
        freshPinAllowance(w.nurse());
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), 429,
                "colleague-pin-limit", "Too many wrong PIN tries for this nurse. Choose another nurse.");
        doseOk(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
    }

    @Test
    void aSecondNurseWhoseLockWasCausedByAnotherPersonIsStillLockedForEveryoneAndTheirOwnVerifyTextsAreUnchanged() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        // 3 wrong tries by the nurse, 2 by the owner: 5 on the colleague's row
        for (int i = 0; i < 3; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "wrong-pin", null);
        }
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "wrong-pin",
                "That PIN is not right.");
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "pin-locked", null);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).lockedNow()).isTrue();
        // the colleague's own PIN endpoint shows a lock
        assertThat(pinStatus(w.nurse2()).get("state").asText()).isEqualTo("LOCKED");
    }

    @Test
    void aThirdLockInTwentyFourHoursCausedByAColleagueIsOnlyALockAndNeverSwitchesThePinOff() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        // SAH-38 (ADR-014 D6, amends ADR-010 D5): the state that used to switch the PIN off (4 wrong tries, 2 locks in the
        // window) now ends in a colleague lock when the fifth try is a colleague's: locked for 15 minutes, count unchanged
        forceCounters(w.nurse2(), 4, 2);
        JsonNode p = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 403,
                "pin-locked", null);
        assertThat(p.toString()).doesNotContain(displayName(w.nurse2()));
        PinRow row = pinRow(w.clinic(), w.nurse2().membershipId());
        assertThat(row.hasPin()).as("the PIN is not switched off").isTrue();
        assertThat(row.lockedNow()).isTrue();
        assertThat(row.lockCount()).as("the lock count is unchanged").isEqualTo(2);
        assertThat(audit(w.clinic(), "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).isEmpty();
        assertThat(doseRows(w.clinic(), w.adm().id())).isZero();
    }

    @Test
    void thirdOwnSessionLockInTwentyFourHoursStillSwitchesThePinOffAndTheCallerGetsPinNotSet() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        forceCounters(w.nurse2(), 4, 2);
        // the fifth wrong try is typed in the second nurse's own session: the SAH-31 switch-off
        assertThat(port.check(w.clinic().tenantId(), w.nurse2().userId(), wrong(w.nurse2Pin())).outcome())
                .isEqualTo(com.sahaj.platform.api.PinCheck.Outcome.NOT_SET);
        JsonNode p = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), 409,
                "pin-not-set", COLLEAGUE_NOT_SET);
        assertThat(p.toString()).doesNotContain(displayName(w.nurse2()));
        PinRow row = pinRow(w.clinic(), w.nurse2().membershipId());
        assertThat(row.hasPin()).isFalse();
        assertThat(row.reason()).isEqualTo("TOO_MANY_LOCKS");
        List<String[]> cleared = audit(w.clinic(), "PLATFORM_PIN_CLEARED_AFTER_LOCKS");
        assertThat(cleared).hasSize(1);
        assertThat(auditRow(w.clinic(), "PLATFORM_PIN_CLEARED_AFTER_LOCKS", 0).get("causedBy").asText()).isEqualTo("SELF");
        // from now on the same text, whatever the PIN, and the right one too
        freshPinAllowance(w.nurse());
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 409,
                "pin-not-set", COLLEAGUE_NOT_SET);
        assertThat(doseRows(w.clinic(), w.adm().id())).isZero();
    }

    @Test
    void aSecondNurseWhoHasNoPinIs409PinNotSetForEveryReasonWithTheSameTextAndNothingCountedOrAudited() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        Pn never = newPn(w.clinic(), "NURSE");
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), never, "4827")), 409, "pin-not-set", COLLEAGUE_NOT_SET);
        assertThat(pinRow(w.clinic(), never.membershipId()).exists()).isFalse();
        // reset by the owner
        resetPin(w.clinic(), w.nurse2().membershipId());
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), 409, "pin-not-set",
                COLLEAGUE_NOT_SET);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).reason()).isEqualTo("RESET_BY_OWNER");
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.events()).isEqualTo(before.events());
        assertThat(now.audits()).as("only the owner's reset is audited").isEqualTo(before.audits() + 1);
        assertThat(colleagueAudits(w.clinic())).isZero();
        // it is the colleague's state, never the caller's: the caller's own PIN status is untouched
        assertThat(pinStatus(w.nurse()).get("state").asText()).isEqualTo("NOT_SET");
        assertThat(pinRow(w.clinic(), w.nurse().membershipId()).exists()).isFalse();
    }

    // ---------------- the per-caller limits ----------------

    @Test
    void fiveFailedColleagueChecksInFifteenMinutesStopTheCallerWith429BeforeAnyHashAndOtherCallersAreNotAffected() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        NurseWithPin n3 = nurseWithPin(w.clinic());
        NurseWithPin n4 = nurseWithPin(w.clinic());
        freshPinAllowance(w.nurse());
        for (int i = 0; i < 3; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), wrong(w.nurse2Pin()))), 403, "wrong-pin", null);
        }
        for (int i = 0; i < 2; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), n3.nurse(), wrong(n3.pin()))), 403, "wrong-pin", null);
        }
        DSnap before = dsnap(w);
        // the sixth try, even with the right PIN of a third nurse and even with a wrong one: 429, no hash, no count
        JsonNode limited = refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), n4.nurse(), n4.pin())), 429,
                "rate-limit", PIN_RATE);
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), n4.nurse(), wrong(n4.pin()))), 429, "rate-limit", PIN_RATE);
        assertThat(dsnap(w)).as("nothing counted, nothing audited, nothing written").isEqualTo(before);
        assertThat(pinRow(w.clinic(), n4.nurse().membershipId()).failed()).isZero();
        assertThat(colleagueAudits(w.clinic())).isEqualTo(5);
        // another person is not limited by this caller's tries; and the first caller is still limited after that
        doseOk(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), n4.nurse(), n4.pin()));
        // after 15 minutes the short bucket is free again
        forgetColleagueShortWindow(w.nurse());
        String ins = activeOrder(w, "ins", "od");
        doseOk(w.nurse().auth(), ins, givenHighAlert(w, ist(8, 0), n4.nurse(), n4.pin()));
    }

    @Test
    void tenFailedColleagueChecksInTwentyFourHoursStopTheCallerEvenAfterTheFifteenMinuteBucketIsFree() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        NurseWithPin n3 = nurseWithPin(w.clinic());
        NurseWithPin n4 = nurseWithPin(w.clinic());
        NurseWithPin n5 = nurseWithPin(w.clinic());
        NurseWithPin n6 = nurseWithPin(w.clinic());
        freshPinAllowance(w.nurse());
        Pn[] first = {w.nurse2(), w.nurse2(), w.nurse2(), n3.nurse(), n3.nurse()};
        String[] firstPins = {w.nurse2Pin(), w.nurse2Pin(), w.nurse2Pin(), n3.pin(), n3.pin()};
        for (int i = 0; i < 5; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), first[i], wrong(firstPins[i]))), 403, "wrong-pin", null);
        }
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), n6.nurse(), n6.pin())), 429, "rate-limit", PIN_RATE);
        forgetColleagueShortWindow(w.nurse());
        Pn[] second = {n4.nurse(), n4.nurse(), n4.nurse(), n5.nurse(), n5.nurse()};
        String[] secondPins = {n4.pin(), n4.pin(), n4.pin(), n5.pin(), n5.pin()};
        for (int i = 0; i < 5; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), second[i], wrong(secondPins[i]))), 403, "wrong-pin", null);
        }
        // ten in the day: the short bucket is full again as well; free it, the day bucket still stops the caller
        forgetColleagueShortWindow(w.nurse());
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), n6.nurse(), n6.pin())), 429, "rate-limit", PIN_RATE);
        assertThat(dsnap(w)).isEqualTo(before);
        assertThat(colleagueAudits(w.clinic())).isEqualTo(10);
        // with one colleague that is only three of the ten: nobody was locked or switched off by this caller alone
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).hasPin()).isTrue();
        assertThat(pinRow(w.clinic(), n3.nurse().membershipId()).hasPin()).isTrue();
        assertThat(pinRow(w.clinic(), n4.nurse().membershipId()).hasPin()).isTrue();
        assertThat(pinRow(w.clinic(), n5.nurse().membershipId()).hasPin()).isTrue();
        // another caller is not stopped
        doseOk(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), n6.nurse(), n6.pin()));
    }

    @Test
    void wrongTriesOfTwoDifferentCallersAddUpOnTheSameColleagueRowAndTheFifthLocksIt() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        for (int i = 0; i < 3; i++) {
            refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "wrong-pin", null);
        }
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "wrong-pin",
                "That PIN is not right.");
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 403, "pin-locked", null);
        assertThat(colleagueAudits(w.clinic())).isEqualTo(5);
        assertThat(audit(w.clinic(), "PLATFORM_PIN_LOCKED")).hasSize(1);
        assertThat(auditRow(w.clinic(), "PLATFORM_PIN_LOCKED", 0).get("checkedByMembershipId").asText())
                .as("the fifth try was the owner's").isEqualTo(ownerPn(w.clinic()).membershipId().toString());
    }

    // ---------------- who may be named (D4 steps 4 to 6, D7) ----------------

    @Test
    void onlyAnActiveNurseOfTheClinicOtherThanTheCallerCanBeNamedEveryOtherCaseIs400AndWritesNoCounterNoAuditAndNoDose()
            throws Exception {
        Fix f = fix();
        Ward w = f.w();
        Pn owner = ownerPn(w.clinic());
        Pn doctor = pn(w.clinic(), w.doctor());
        String ownerPin = goodPin(owner);
        setPin(owner, ownerPin);
        String doctorPin = goodPin(doctor);
        setPin(doctor, doctorPin);
        NurseWithPin inactive = nurseWithPin(w.clinic());
        setMembershipStatus(w.clinic().tenantId(), inactive.nurse().userId(), "INACTIVE");
        Ward other = ward();
        DSnap before = dsnap(w);
        String otherPins = values(other.clinic(), "select coalesce(string_agg(failed_count::text, ','), '') from platform.staff_pin").get(0);

        Object[][] cases = {
                {owner.membershipId().toString(), ownerPin, "the owner (not a nurse)"},
                {doctor.membershipId().toString(), doctorPin, "a doctor"},
                {w.pharmacist().membershipId().toString(), w.pharmacistPin(), "a pharmacist"},
                {inactive.nurse().membershipId().toString(), inactive.pin(), "an inactive nurse"},
                {other.nurse2().membershipId().toString(), other.nurse2Pin(), "a nurse of another clinic"},
                {UUID.randomUUID().toString(), "4827", "a random id"},
                {"not-a-uuid", "4827", "text that is not an id"},
                {"00000000-0000-0000-0000-000000000000", "4827", "the nil uuid"}};
        for (Object[] c : cases) {
            Map<String, Object> body = givenHighAlert(w, ist(8, 0), w.nurse2(), "1234");
            body.put("secondNurseId", c[0]);
            body.put("secondNursePin", c[1]);
            invalid(doseCall(w.nurse().auth(), f.hep(), body), "secondNurseId", CHOOSE_NURSE);
        }
        assertThat(dsnap(w)).as("no counter, no audit, no dose").isEqualTo(before);
        assertThat(values(other.clinic(), "select coalesce(string_agg(failed_count::text, ','), '') from platform.staff_pin").get(0))
                .isEqualTo(otherPins);
        assertThat(colleagueAudits(w.clinic())).isZero();
        assertThat(colleagueAudits(other.clinic())).isZero();
    }

    @Test
    void theCallerCannotBeTheirOwnSecondCheckWhichEverNurseOrOwnerTheyAreAndNoPinIsTried() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        Pn owner = ownerPn(w.clinic());
        DSnap before = dsnap(w);
        Map<String, Object> self = givenHighAlert(w, ist(8, 0), w.nurse(), "1234");
        invalid(doseCall(w.nurse().auth(), f.hep(), self), "secondNurseId", DIFFERENT_NURSE);
        invalid(doseCall(w.nurse2().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())), "secondNurseId",
                DIFFERENT_NURSE);
        invalid(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), owner, "1234")), "secondNurseId", DIFFERENT_NURSE);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    @Test
    void aHighAlertDoseWithoutASecondNurseOrWithABadPinFormatIs400OneMessageAtATimeAndNothingIsTriedOrWritten() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        DSnap before = dsnap(w);
        Map<String, Object> base = given(w, ist(8, 0));
        // both missing: both fields, the first message as detail
        JsonNode both = refused(doseCall(w.nurse().auth(), f.hep(), base), 400, "validation", HIGH_ALERT_TEXT);
        assertThat(fieldNames(both)).containsExactly("secondNurseId", "secondNursePin");
        // only the nurse missing / only the PIN missing / blank nurse
        Map<String, Object> noNurse = set(base, "secondNursePin", "4827");
        assertThat(fieldNames(refused(doseCall(w.nurse().auth(), f.hep(), noNurse), 400, "validation", HIGH_ALERT_TEXT)))
                .containsExactly("secondNurseId");
        Map<String, Object> noPin = set(base, "secondNurseId", w.nurse2().membershipId().toString());
        assertThat(fieldNames(refused(doseCall(w.nurse().auth(), f.hep(), noPin), 400, "validation", HIGH_ALERT_TEXT)))
                .containsExactly("secondNursePin");
        Map<String, Object> blank = set(set(base, "secondNurseId", ""), "secondNursePin", "4827");
        assertThat(fieldNames(refused(doseCall(w.nurse().auth(), f.hep(), blank), 400, "validation", HIGH_ALERT_TEXT)))
                .containsExactly("secondNurseId");
        for (String pin : new String[] {"123", "12345", "12a4", "١٢٣٤", "1234 ", " 1234", "12 34", "+123", "1.23", ""}) {
            Map<String, Object> body = set(noPin, "secondNursePin", pin);
            assertThat(fieldNames(refused(doseCall(w.nurse().auth(), f.hep(), body), 400, "validation", HIGH_ALERT_TEXT)))
                    .as("pin '" + pin + "'").containsExactly("secondNursePin");
        }
        assertThat(dsnap(w)).isEqualTo(before);
        // the statement is judged before the second nurse, the wristband before the statement
        invalid(doseCall(w.nurse().auth(), f.hep(), set(base, "identityConfirmed", false)), "identityConfirmed", CONFIRM_TEXT);
        refused(doseCall(w.nurse().auth(), f.hep(), set(set(base, "identityConfirmed", false), "wristband", "WRONG-00000")), 409,
                "wristband-mismatch", MISMATCH_TEXT);
    }

    @Test
    void aPinWithLeadingZerosIsKeptAsAStringAndChecked() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        Pn zero = newPn(w.clinic(), "NURSE");
        String pin = "0481";
        if (zero.mobile().contains(pin)) {
            pin = "0539";
        }
        setPin(zero, pin);
        JsonNode a = doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), zero, pin));
        assertThat(a.get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(zero));
    }

    // ---------------- not high-alert: ignored ----------------

    @Test
    void forAMedicineThatIsNotHighAlertTheSecondNurseAndPinAreIgnoredNotCheckedNotStoredAndNeverSentToTheCheck() throws Exception {
        Ward w = ward();
        String pcm = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        DSnap before = dsnap(w);
        Map<String, Object> body = given(w, ist(8, 0));
        body.put("secondNurseId", UUID.randomUUID().toString());
        body.put("secondNursePin", "not a pin");
        JsonNode a = doseOk(w.nurse().auth(), pcm, body);
        assertThat(a.get("dose").get("secondCheckByName").isNull()).isTrue();
        DoseDb row = doseDb(w.clinic(), pcm, ist(8, 0));
        assertThat(row.secondCheckBy()).isEmpty();
        assertThat(row.highAlert()).isFalse();
        assertThat(dsnap(w).pins()).isEqualTo(before.pins());
        assertThat(colleagueAudits(w.clinic())).isZero();
        assertThat(eventLines(w.clinic(), w.adm().id()).stream().anyMatch(l -> l.contains("second check"))).isFalse();
        assertThat(auditRow(w.clinic(), "IPD_DOSE_GIVEN", 0).get("secondCheckUserId").isNull()).isTrue();
        // even the nurse's own correct PIN is not looked at: a wrong PIN with a real nurse changes nothing
        String panto = activeOrder(w, "panto", "od");
        Map<String, Object> real = given(w, ist(8, 0));
        real.put("secondNurseId", w.nurse2().membershipId().toString());
        real.put("secondNursePin", wrong(w.nurse2Pin()));
        doseOk(w.nurse().auth(), panto, real);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isZero();
        assertThat(doseDb(w.clinic(), panto, ist(8, 0)).secondCheckBy()).isEmpty();
    }

    // ---------------- parallel wrong tries ----------------

    @Test
    void parallelWrongTriesOfOneCallerAreAllCountedOnTheColleagueWithDistinctTriesLeft() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        List<Req> reqs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reqs.add(new Req(HttpMethod.POST, dosesUrl(f.hep()), w.nurse().auth(),
                    js(givenHighAlert(w, ist(8, 0), w.nurse2(), bad))));
        }
        List<Resp> answers = parallel(reqs.toArray(new Req[0]));
        List<String> details = new ArrayList<>();
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isEqualTo(403);
            assertThat(r.type(objectMapper)).isEqualTo("wrong-pin");
            details.add(r.detail(objectMapper));
        }
        assertThat(details).as("every answer is the same plain text, no count").containsExactly("That PIN is not right.",
                "That PIN is not right.", "That PIN is not right.");
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            counts.add(auditRow(w.clinic(), "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED", i).get("triesLeft").asInt());
        }
        assertThat(counts).as("the audit keeps distinct counts").containsExactlyInAnyOrder(4, 3, 2);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isEqualTo(3);
        assertThat(colleagueAudits(w.clinic())).isEqualTo(3);
        assertThat(doseRows(w.clinic(), w.adm().id())).isZero();
    }

    @Test
    void parallelWrongTriesOfTwoCallersOnTheSameColleagueAreBothCountedOnTheRow() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        List<Resp> answers = parallel(
                new Req(HttpMethod.POST, dosesUrl(f.hep()), w.nurse().auth(), js(givenHighAlert(w, ist(8, 0), w.nurse2(), bad))),
                new Req(HttpMethod.POST, dosesUrl(f.hep()), w.clinic().owner(), js(givenHighAlert(w, ist(8, 0), w.nurse2(), bad))));
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isEqualTo(403);
        }
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isEqualTo(2);
        assertThat(colleagueAudits(w.clinic())).isEqualTo(2);
    }

    // ---------------- the checks that come before the PIN ----------------

    @Test
    void theOrderAndSlotAndWristbandAreCheckedBeforeAnyPinSoARefusedRequestNeverCountsAWrongTry() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        String bad = wrong(w.nurse2Pin());
        DSnap before = dsnap(w);
        // wrong band, wrong statement, not due, not a dose time: each is answered first and no PIN is tried
        refused(doseCall(w.nurse().auth(), f.hep(), set(givenHighAlert(w, ist(8, 0), w.nurse2(), bad), "wristband", "WRONG-00000")),
                409, "wristband-mismatch", MISMATCH_TEXT);
        invalid(doseCall(w.nurse().auth(), f.hep(), set(givenHighAlert(w, ist(8, 0), w.nurse2(), bad), "identityConfirmed", false)),
                "identityConfirmed", CONFIRM_TEXT);
        invalid(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(9, 0), w.nurse2(), bad)), "slotAt", NOT_A_DOSE_TIME);
        clockAt(6, 0);
        refused(doseCall(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 409, "dose-not-due", NOT_DUE);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isZero();
        assertThat(colleagueAudits(w.clinic())).isZero();
        DSnap now = dsnap(w);
        assertThat(now.pins()).isEqualTo(before.pins());
        assertThat(now.doses()).isEqualTo(before.doses());
    }

    @Test
    void aDoseAlreadyRecordedIsRefusedBeforeThePinSoARepeatNeverCostsATryOfTheColleague() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        doseOk(w.nurse().auth(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin()));
        String bad = wrong(w.nurse2Pin());
        refused(doseCall(w.clinic().owner(), f.hep(), givenHighAlert(w, ist(8, 0), w.nurse2(), bad)), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId()).failed()).isZero();
        assertThat(colleagueAudits(w.clinic())).isZero();
    }

    @Test
    void theReplayOfAHighAlertDoseNeedsNoPinAndIs409WhenTheSecondNurseIsDifferent() throws Exception {
        Fix f = fix();
        Ward w = f.w();
        NurseWithPin n3 = nurseWithPin(w.clinic());
        Map<String, Object> body = givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin());
        doseOk(w.nurse().auth(), f.hep(), body);
        PinRow pin = pinRow(w.clinic(), w.nurse2().membershipId());
        // no PIN, a wrong PIN, no second nurse: all the same replay
        for (Map<String, Object> variant : List.of(without(body, "secondNursePin"), set(body, "secondNursePin", "0000"),
                without(body, "secondNursePin", "wristband"))) {
            JsonNode again = ok(HttpMethod.POST, dosesUrl(f.hep()), w.nurse().auth(), js(variant), 200);
            assertThat(again.get("replayed").asBoolean()).isTrue();
        }
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId())).isEqualTo(pin);
        assertThat(colleagueAudits(w.clinic())).isZero();
        refused(doseCall(w.nurse().auth(), f.hep(), set(body, "secondNurseId", n3.nurse().membershipId().toString())), 409,
                "client-key-reused", DOSE_KEY_REUSED);
        assertThat(pinRow(w.clinic(), n3.nurse().membershipId()).failed()).isZero();
    }

    // ---------------- the second nurse after the PIN (D6 step 4) ----------------

    @Test
    void whenTheSecondNurseIsSwitchedOffWhileTheRequestWaitsForTheAdmissionLockTheAnswerIs400ChooseANurseAndNoDoseIsWritten()
            throws Exception {
        Fix f = fix();
        Ward w = f.w();
        NurseWithPin n3 = nurseWithPin(w.clinic());
        DSnap before = dsnap(w);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection holder = holdAdmissionLock(w.clinic(), w.adm().id())) {
            java.util.concurrent.Future<Resp> answer = pool.submit(() -> {
                var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(java.net.URI.create(dosesUrl(f.hep()))).with(w.nurse().auth())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(js(givenHighAlert(w, ist(8, 0), n3.nurse(), n3.pin())))).andReturn().getResponse();
                return new Resp(res.getStatus(), res.getContentAsString());
            });
            assertThat(awaitLockWaiters("%from ipd.admission%update%", 1, 60_000)).as("the request passed the PIN check and waits").isTrue();
            setMembershipStatus(w.clinic().tenantId(), n3.nurse().userId(), "INACTIVE");
            holder.rollback();
            Resp r = answer.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r.status()).as(r.body()).isEqualTo(400);
            assertThat(r.type(objectMapper)).isEqualTo("validation");
            assertThat(r.detail(objectMapper)).isEqualTo(CHOOSE_NURSE);
        } finally {
            pool.shutdownNow();
        }
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.events()).isEqualTo(before.events());
        assertThat(now.audits()).isEqualTo(before.audits());
        assertThat(pinRow(w.clinic(), n3.nurse().membershipId()).failed()).as("the PIN was right").isZero();
    }
}
