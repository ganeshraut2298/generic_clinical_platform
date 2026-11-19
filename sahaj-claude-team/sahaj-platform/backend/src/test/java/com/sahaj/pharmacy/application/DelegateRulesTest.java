package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DEC-068 Q1 (security L3): a delegate period is at most 90 calendar days counting both its first and its last day, so
 * {@code to - from <= 89}. The text stays "A delegation can be at most 90 days".
 */
class DelegateRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final String TOO_LONG = "A delegation can be at most 90 days";

    private static List<FieldMessage> check(LocalDate from, LocalDate to) {
        List<FieldMessage> errors = new ArrayList<>();
        DelegateRules.check(from, to, TODAY, "delegateFrom", "delegateTo", errors);
        return errors;
    }

    @Test
    void ninetyCalendarDaysCountingBothEndsIsAccepted() {
        assertThat(check(TODAY, TODAY.plusDays(89))).isEmpty();
        assertThat(DelegateState.daysIncluded(TODAY, TODAY.plusDays(89))).isEqualTo(90);
    }

    @Test
    void ninetyOneCalendarDaysIsRefusedWithTheSameSentence() {
        assertThat(check(TODAY, TODAY.plusDays(90))).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("delegateTo");
            assertThat(error.message()).isEqualTo(TOO_LONG);
        });
        assertThat(check(TODAY, TODAY.plusDays(91))).extracting(FieldMessage::message).containsExactly(TOO_LONG);
        assertThat(DelegateState.tooLong(TODAY, TODAY.plusDays(90))).isTrue();
        assertThat(DelegateState.tooLong(TODAY, TODAY.plusDays(89))).isFalse();
    }

    @Test
    void aOneDayPeriodAndAStartInThePastWithAFutureEndAreAccepted() {
        assertThat(check(TODAY, TODAY)).isEmpty();
        assertThat(check(TODAY.minusDays(10), TODAY.plusDays(5))).isEmpty();
    }

    @Test
    void theOtherDateRulesAreUnchanged() {
        assertThat(check(null, TODAY)).extracting(FieldMessage::message).containsExactly("Enter the start date");
        assertThat(check(TODAY, null)).extracting(FieldMessage::message).containsExactly("Enter the end date");
        assertThat(check(TODAY, TODAY.minusDays(1))).extracting(FieldMessage::message)
                .containsExactly("The end date must be on or after the start date");
        assertThat(check(TODAY.minusDays(10), TODAY.minusDays(1))).extracting(FieldMessage::message)
                .containsExactly("The end date cannot be in the past");
    }
}
