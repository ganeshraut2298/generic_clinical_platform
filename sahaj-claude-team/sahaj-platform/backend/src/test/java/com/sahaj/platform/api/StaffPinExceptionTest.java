package com.sahaj.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SAH-31, ADR-010 D9 and D11: PinCheck.requireOk() and the exact problem types, statuses and texts. */
class StaffPinExceptionTest {

    private static StaffPinException thrownBy(PinCheck check) {
        return (StaffPinException) catchThrowable(check);
    }

    private static Throwable catchThrowable(PinCheck check) {
        try {
            check.requireOk();
        } catch (RuntimeException ex) {
            return ex;
        }
        return null;
    }

    @Test
    void okReturnsNormally() {
        assertThatCode(() -> new PinCheck(Outcome.OK, 5, null, 0).requireOk()).doesNotThrowAnyException();
        assertThat(new PinCheck(Outcome.OK, 5, null, 0).isOk()).isTrue();
    }

    @Test
    void aWrongPinWithSeveralTriesLeftIs403WrongPin() {
        StaffPinException ex = thrownBy(new PinCheck(Outcome.WRONG, 2, null, 0));

        assertThat(ex.slug()).isEqualTo("wrong-pin");
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.title()).isEqualTo("Wrong PIN");
        assertThat(ex.getMessage()).isEqualTo("That PIN is not right. 2 tries left.");
        assertThat(ex.retryAfterSeconds()).isZero();
    }

    @Test
    void oneTryLeftUsesTheSingular() {
        assertThat(thrownBy(new PinCheck(Outcome.WRONG, 1, null, 0)).getMessage())
                .isEqualTo("That PIN is not right. 1 try left.");
        assertThat(thrownBy(new PinCheck(Outcome.WRONG, 4, null, 0)).getMessage())
                .isEqualTo("That PIN is not right. 4 tries left.");
    }

    @Test
    void aLockedPinIs403PinLockedWithTheEndOfTheLockInIndianTime() {
        // 05:50 UTC is 11:20 AM IST
        StaffPinException ex = thrownBy(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 840));

        assertThat(ex.slug()).isEqualTo("pin-locked");
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.title()).isEqualTo("PIN locked");
        assertThat(ex.getMessage()).isEqualTo("Your PIN is locked after 5 wrong tries. Try again at 11:20 AM.");
        assertThat(ex.retryAfterSeconds()).isEqualTo(840);
    }

    @Test
    void theLockTimeUsesPmAndNoLeadingZeroHour() {
        assertThat(thrownBy(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T08:35:00Z"), 60)).getMessage())
                .endsWith("Try again at 2:05 PM.");
        assertThat(thrownBy(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T18:30:00Z"), 60)).getMessage())
                .endsWith("Try again at 12:00 AM.");
    }

    @Test
    void aLockWithNoEndTimeStillAnswersWithoutFailing() {
        assertThat(thrownBy(new PinCheck(Outcome.LOCKED, 0, null, 0)).getMessage())
                .isEqualTo("Your PIN is locked after 5 wrong tries. Try again later.");
    }

    @Test
    void aMissingPinIs409PinNotSetWithTheTextForTheReason() {
        StaffPinException never = thrownBy(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.NEVER_SET));
        assertThat(never.slug()).isEqualTo("pin-not-set");
        assertThat(never.status()).isEqualTo(409);
        assertThat(never.title()).isEqualTo("No PIN set");
        assertThat(never.getMessage()).isEqualTo("You have not set your PIN yet. Set your PIN, then try again.");

        assertThat(thrownBy(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.RESET_BY_OWNER)).getMessage())
                .isEqualTo("The clinic owner reset your PIN. Set a new one, then try again.");
        assertThat(thrownBy(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.TOO_MANY_LOCKS)).getMessage())
                .isEqualTo("Your PIN was switched off after too many wrong tries. Set a new one, then try again.");
        assertThat(thrownBy(new PinCheck(Outcome.NOT_SET, 0, null, 0)).getMessage())
                .as("the four-value form reads as never set").isEqualTo(never.getMessage());
    }

    @Test
    void tooManyTriesIs429RateLimitWithRetryAfter() {
        StaffPinException ex = thrownBy(new PinCheck(Outcome.RATE_LIMITED, 0, null, 120));

        assertThat(ex.slug()).isEqualTo("rate-limit");
        assertThat(ex.status()).isEqualTo(429);
        assertThat(ex.getMessage()).isEqualTo("Too many PIN tries in a short time. Try again in a few minutes.");
        assertThat(ex.retryAfterSeconds()).isEqualTo(120);
    }

    @Test
    void aMissingPepperIs503PinUnavailable() {
        StaffPinException ex = thrownBy(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0));

        assertThat(ex.slug()).isEqualTo("pin-unavailable");
        assertThat(ex.status()).isEqualTo(503);
        assertThat(ex.title()).isEqualTo("PIN checks are off");
        assertThat(ex.getMessage()).isEqualTo("PIN checks are not available right now. Try again later.");
        assertThat(StaffPinException.unavailable().getMessage()).isEqualTo(ex.getMessage());
    }

    @Test
    void aNegativeWaitIsNeverReported() {
        assertThat(StaffPinException.rateLimited(-5).retryAfterSeconds()).isZero();
    }

    @Test
    void theCheckHoldsNumbersOnlyAndItsToStringHasNoSecretsAndNoTime() {
        PinCheck check = new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 840);

        assertThat(check.toString()).isEqualTo("PinCheck[outcome=LOCKED, triesLeft=0, retryAfterSeconds=840]");
    }

    @Test
    void anOutcomeIsRequired() {
        assertThatThrownBy(() -> new PinCheck(null, 0, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theExceptionNeverCarriesAPin() {
        // every text is fixed; the only variable parts are a tries-left number and a clock time
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.OK || outcome == Outcome.NOT_ELIGIBLE) {
                continue;
            }
            StaffPinException ex = thrownBy(new PinCheck(outcome, 3, Instant.parse("2026-11-06T05:50:00Z"), 60,
                    NotSetReason.NEVER_SET));
            assertThat(ex.getMessage()).doesNotContainPattern("[0-9]{4,}");
        }
    }

    // ---- SAH-32, ADR-011 D7: the colleague (second nurse) texts ----

    private static StaffPinException colleague(PinCheck check) {
        return StaffPinException.of(check, StaffPinException.Subject.COLLEAGUE);
    }

    @Test
    void anOwnPinProblemHasTheSelfSubjectAndAColleagueOneTheColleagueSubject() {
        assertThat(thrownBy(new PinCheck(Outcome.WRONG, 2, null, 0)).subject())
                .isEqualTo(StaffPinException.Subject.SELF);
        assertThat(StaffPinException.unavailable().subject()).isEqualTo(StaffPinException.Subject.SELF);
        assertThat(StaffPinException.rateLimited(5).subject()).isEqualTo(StaffPinException.Subject.SELF);
        assertThat(colleague(new PinCheck(Outcome.WRONG, 2, null, 0)).subject())
                .isEqualTo(StaffPinException.Subject.COLLEAGUE);
    }

    @Test
    void aColleagueWrongPinKeepsTheTypeAndStatusButNeverSaysHowManyTriesAreLeft() {
        StaffPinException ex = colleague(new PinCheck(Outcome.WRONG, 3, null, 0));

        assertThat(ex.slug()).isEqualTo("wrong-pin");
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.getMessage()).isEqualTo("That PIN is not right.");
        assertThat(colleague(new PinCheck(Outcome.WRONG, 1, null, 0)).getMessage())
                .isEqualTo("That PIN is not right.");
        assertThat(colleague(new PinCheck(Outcome.WRONG, 4, null, 0)).getMessage())
                .doesNotContain("tries").doesNotContain("try left");
    }

    @Test
    void aLockedColleagueIs403PinLockedWithTheSecondNurseTextAndTheEndTimeInIndianTime() {
        // 05:50 UTC is 11:20 AM IST
        StaffPinException ex = colleague(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 840));

        assertThat(ex.slug()).isEqualTo("pin-locked");
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.getMessage()).isEqualTo("The second nurse's PIN is locked after 5 wrong tries. "
                + "Choose another nurse, or try again at 11:20 AM.");
        assertThat(ex.retryAfterSeconds()).isEqualTo(840);
        assertThat(colleague(new PinCheck(Outcome.LOCKED, 0, null, 0)).getMessage())
                .endsWith("Choose another nurse, or try again later.");
    }

    @Test
    void aColleagueWithoutAPinIs409PinNotSetWithOneTextForEveryReason() {
        String text = "The second nurse has not set a PIN yet. Choose another nurse.";
        for (NotSetReason reason : NotSetReason.values()) {
            StaffPinException ex = colleague(new PinCheck(Outcome.NOT_SET, 0, null, 0, reason));

            assertThat(ex.slug()).isEqualTo("pin-not-set");
            assertThat(ex.status()).isEqualTo(409);
            assertThat(ex.getMessage()).as(String.valueOf(reason)).isEqualTo(text);
        }
        assertThat(colleague(new PinCheck(Outcome.NOT_SET, 0, null, 0)).getMessage()).isEqualTo(text);
    }

    @Test
    void rateLimitAndUnavailableReadTheSameForAColleague() {
        StaffPinException limited = colleague(new PinCheck(Outcome.RATE_LIMITED, 0, null, 300));
        assertThat(limited.slug()).isEqualTo("rate-limit");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.getMessage()).isEqualTo("Too many PIN tries in a short time. Try again in a few minutes.");
        assertThat(limited.retryAfterSeconds()).isEqualTo(300);

        StaffPinException off = colleague(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0));
        assertThat(off.slug()).isEqualTo("pin-unavailable");
        assertThat(off.status()).isEqualTo(503);
        assertThat(off.getMessage()).isEqualTo(StaffPinException.unavailable().getMessage());
    }

    @Test
    void noColleagueTextEverCarriesANameAnIdOrAPin() {
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.OK || outcome == Outcome.NOT_ELIGIBLE) {
                continue;
            }
            String text = colleague(new PinCheck(outcome, 3, Instant.parse("2026-11-06T05:50:00Z"), 60,
                    NotSetReason.TOO_MANY_LOCKS)).getMessage();

            assertThat(text).as(String.valueOf(outcome)).doesNotContainPattern("[0-9]{4,}")
                    .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}");
            assertThat(text).doesNotContain("Your ");
        }
    }

    @Test
    void notEligibleIsNotAPinProblemAndAnOwnCheckNeverAnswersIt() {
        assertThatThrownBy(() -> StaffPinException.of(new PinCheck(Outcome.NOT_ELIGIBLE, 0, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinCheck(Outcome.NOT_ELIGIBLE, 0, null, 0).requireOk())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StaffPinException.of(new PinCheck(Outcome.OK, 5, null, 0), StaffPinException.Subject.COLLEAGUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
