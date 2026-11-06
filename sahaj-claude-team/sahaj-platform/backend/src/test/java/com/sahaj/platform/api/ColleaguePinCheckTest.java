package com.sahaj.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-32, ADR-011 D7: ColleaguePinCheck, requireOk() and the NOT_ELIGIBLE outcome. */
class ColleaguePinCheckTest {

    private static final UUID COLLEAGUE_USER = UUID.randomUUID();

    @Test
    void aRightPinReturnsTheColleaguesUserId() {
        ColleaguePinCheck check = ColleaguePinCheck.ok(COLLEAGUE_USER);

        assertThat(check.requireOk()).isEqualTo(COLLEAGUE_USER);
        assertThat(check.isOk()).isTrue();
        assertThat(check.outcome()).isEqualTo(Outcome.OK);
        assertThat(check.pin().triesLeft()).isEqualTo(com.sahaj.platform.domain.StaffPinRow.MAX_TRIES);
    }

    @Test
    void theUserIdIsPresentExactlyWhenThePinIsOk() {
        PinCheck ok = new PinCheck(Outcome.OK, 5, null, 0);
        PinCheck wrong = new PinCheck(Outcome.WRONG, 3, null, 0);

        assertThatThrownBy(() -> new ColleaguePinCheck(ok, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColleaguePinCheck(wrong, COLLEAGUE_USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColleaguePinCheck(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyFailureThrowsAColleagueProblemWithNoUserIdToReturn() {
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.OK || outcome == Outcome.NOT_ELIGIBLE) {
                continue;
            }
            ColleaguePinCheck check = ColleaguePinCheck.failed(new PinCheck(outcome, 2,
                    Instant.parse("2026-11-06T05:50:00Z"), 60, NotSetReason.NEVER_SET));

            assertThat(check.colleagueUserId()).isNull();
            assertThatThrownBy(check::requireOk).as(String.valueOf(outcome))
                    .isInstanceOfSatisfying(StaffPinException.class,
                            ex -> assertThat(ex.subject()).isEqualTo(StaffPinException.Subject.COLLEAGUE));
        }
    }

    @Test
    void wrongPinAndLockedAndNotSetAndRateLimitAndUnavailableUseTheContractTexts() {
        assertThatThrownBy(() -> ColleaguePinCheck.failed(new PinCheck(Outcome.WRONG, 4, null, 0)).requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("wrong-pin");
                    assertThat(ex.getMessage()).isEqualTo("That PIN is not right.");
                });
        assertThatThrownBy(() -> ColleaguePinCheck.failed(
                new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T08:35:00Z"), 840)).requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("pin-locked");
                    assertThat(ex.getMessage()).isEqualTo("The second nurse's PIN is locked after 5 wrong tries. "
                            + "Choose another nurse, or try again at 2:05 PM.");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(840);
                });
        assertThatThrownBy(() -> ColleaguePinCheck.failed(
                new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.TOO_MANY_LOCKS)).requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("pin-not-set");
                    assertThat(ex.getMessage()).isEqualTo("The second nurse has not set a PIN yet. Choose another nurse.");
                });
        assertThatThrownBy(() -> ColleaguePinCheck.failed(new PinCheck(Outcome.RATE_LIMITED, 0, null, 900)).requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("rate-limit");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(900);
                });
        assertThatThrownBy(() -> ColleaguePinCheck.failed(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0)).requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> assertThat(ex.slug()).isEqualTo("pin-unavailable"));
    }

    @Test
    void notEligibleIsA400ValidationOnSecondNurseIdWithTheMessageAsDetail() {
        ColleaguePinCheck check = ColleaguePinCheck.notEligible();

        assertThat(check.outcome()).isEqualTo(Outcome.NOT_ELIGIBLE);
        assertThat(check.colleagueUserId()).isNull();
        assertThatThrownBy(check::requireOk).isInstanceOfSatisfying(InvalidFieldsException.class, ex -> {
            assertThat(ex.errors()).containsExactly(new FieldMessage("secondNurseId", "Choose a nurse from the list."));
            assertThat(ex.firstMessageAsDetail()).isTrue();
        });
    }

    @Test
    void toStringHoldsTheOutcomeAndNumbersOnlyNeverIds() {
        assertThat(ColleaguePinCheck.ok(COLLEAGUE_USER).toString())
                .isEqualTo("ColleaguePinCheck[outcome=OK, triesLeft=5, retryAfterSeconds=0]")
                .doesNotContain(COLLEAGUE_USER.toString());
        assertThat(ColleaguePinCheck.failed(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 840))
                .toString()).isEqualTo("ColleaguePinCheck[outcome=LOCKED, triesLeft=0, retryAfterSeconds=840]");
    }
}
