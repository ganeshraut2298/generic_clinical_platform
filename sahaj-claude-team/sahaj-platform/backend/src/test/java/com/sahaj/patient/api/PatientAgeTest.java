package com.sahaj.patient.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.patient.api.PatientAge.Unit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * SAH-73 (ADR-024 D1, D2): the one age rule. Born 29 Feb 2020 is 6 on 28 Feb 2027 and 7 on 1 Mar 2027; the unit
 * switches at 2 years, 3 months and 14 days; a list filter bound agrees with the completed years for every day.
 */
class PatientAgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    private static PatientAge age(String dateOfBirth, String asOf) {
        return PatientAge.fromDate(LocalDate.parse(dateOfBirth), LocalDate.parse(asOf));
    }

    // ---- the day: India, from the injected clock ----

    @Test
    void todayIsTheDateInIndiaNotInTheClocksZone() {
        // 18:45 UTC on 12 Nov is 12:15 AM on 13 Nov in India (UTC+5:30)
        Clock utc = Clock.fixed(Instant.parse("2026-11-12T18:45:00Z"), ZoneOffset.UTC);
        assertThat(PatientAge.today(utc)).isEqualTo(LocalDate.of(2026, 11, 13));
        // 18:15 UTC is 11:45 PM in India: still the 12th
        Clock earlier = Clock.fixed(Instant.parse("2026-11-12T18:15:00Z"), ZoneOffset.UTC);
        assertThat(PatientAge.today(earlier)).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    // ---- completed years and 29 Feb (SO-73-9) ----

    @Test
    void bornOn29FebIsSixOn28FebAndSevenOn1MarOfTheNextYears() {
        LocalDate born = LocalDate.of(2020, 2, 29);
        assertThat(PatientAge.completedYears(born, LocalDate.of(2027, 2, 28))).isEqualTo(6);
        assertThat(PatientAge.completedYears(born, LocalDate.of(2027, 3, 1))).isEqualTo(7);
        assertThat(PatientAge.completedYears(born, LocalDate.of(2028, 2, 28))).isEqualTo(7);
        assertThat(PatientAge.completedYears(born, LocalDate.of(2028, 2, 29))).isEqualTo(8);
        assertThat(age("2020-02-29", "2027-02-28").value()).isEqualTo(6);
        assertThat(age("2020-02-29", "2027-03-01").value()).isEqualTo(7);
    }

    @Test
    void aBirthdayIsCountedOnTheDayItself() {
        assertThat(PatientAge.completedYears(LocalDate.of(2000, 11, 13), TODAY)).isEqualTo(26);
        assertThat(PatientAge.completedYears(LocalDate.of(2000, 11, 14), TODAY)).isEqualTo(25);
    }

    // ---- the unit and value (BR73-7) ----

    @Test
    void twoYearsAndMoreIsShownInYears() {
        PatientAge exactlyTwo = age("2024-11-13", "2026-11-13");
        assertThat(exactlyTwo.unit()).isEqualTo(Unit.YEARS);
        assertThat(exactlyTwo.value()).isEqualTo(2);
        PatientAge almostTwo = age("2024-11-14", "2026-11-13");
        assertThat(almostTwo.unit()).isEqualTo(Unit.MONTHS);
        assertThat(almostTwo.value()).isEqualTo(23);
        assertThat(almostTwo.years()).as("completed years stay 1 while the screen shows months").isEqualTo(1);
    }

    @Test
    void threeMonthsUpToTwoYearsIsShownInMonths() {
        PatientAge eighteen = age("2025-05-13", "2026-11-13");
        assertThat(eighteen.unit()).isEqualTo(Unit.MONTHS);
        assertThat(eighteen.value()).isEqualTo(18);
        assertThat(eighteen.totalMonths()).isEqualTo(18);
        assertThat(eighteen.daysAfterMonths()).isZero();
        // the mockup case: 10 Jun 2025 on 13 Nov 2026 is 17 months
        assertThat(age("2025-06-10", "2026-11-13").value()).isEqualTo(17);
        PatientAge exactlyThree = age("2026-08-13", "2026-11-13");
        assertThat(exactlyThree.unit()).isEqualTo(Unit.MONTHS);
        assertThat(exactlyThree.value()).isEqualTo(3);
    }

    @Test
    void twoWeeksUpToThreeMonthsIsShownInWeeksFlooredFromTheDays() {
        PatientAge justUnderThreeMonths = age("2026-08-14", "2026-11-13");
        assertThat(justUnderThreeMonths.unit()).isEqualTo(Unit.WEEKS);
        assertThat(justUnderThreeMonths.totalDays()).isEqualTo(91);
        assertThat(justUnderThreeMonths.value()).isEqualTo(13);
        PatientAge tenWeeks = age("2026-09-03", "2026-11-13");
        assertThat(tenWeeks.unit()).isEqualTo(Unit.WEEKS);
        assertThat(tenWeeks.value()).isEqualTo(10);
        PatientAge fourteenDays = age("2026-10-30", "2026-11-13");
        assertThat(fourteenDays.unit()).isEqualTo(Unit.WEEKS);
        assertThat(fourteenDays.value()).isEqualTo(2);
        assertThat(age("2026-10-23", "2026-11-13").value()).as("21 days").isEqualTo(3);
        assertThat(age("2026-10-24", "2026-11-13").value()).as("20 days floors to 2").isEqualTo(2);
    }

    @Test
    void underTwoWeeksIsShownInDaysFromZeroToThirteen() {
        PatientAge born = age("2026-11-13", "2026-11-13");
        assertThat(born.unit()).isEqualTo(Unit.DAYS);
        assertThat(born.value()).as("a baby born today is accepted").isZero();
        PatientAge nine = age("2026-11-04", "2026-11-13");
        assertThat(nine.unit()).isEqualTo(Unit.DAYS);
        assertThat(nine.value()).isEqualTo(9);
        PatientAge thirteen = age("2026-10-31", "2026-11-13");
        assertThat(thirteen.unit()).isEqualTo(Unit.DAYS);
        assertThat(thirteen.value()).isEqualTo(13);
    }

    @Test
    void theAgeOfADatedChildCarriesMonthsAndDaysForPaediatrics() {
        // born 10 May 2025 on 13 Nov 2026: 18 months and 3 days
        PatientAge detail = age("2025-05-10", "2026-11-13");
        assertThat(detail.fromDateOfBirth()).isTrue();
        assertThat(detail.years()).isEqualTo(1);
        assertThat(detail.totalMonths()).isEqualTo(18);
        assertThat(detail.daysAfterMonths()).isEqualTo(3);
        assertThat(detail.totalDays()).isEqualTo(552);
        assertThat(detail.asOf()).isEqualTo(TODAY);
    }

    // ---- no date of birth ----

    @Test
    void withoutADateOfBirthTheAgeIsTheStoredNumberInYearsAndDoesNotChangeOverTime() {
        PatientAge now = PatientAge.of(null, 34, TODAY);
        PatientAge later = PatientAge.of(null, 34, TODAY.plusYears(5));
        for (PatientAge detail : new PatientAge[] {now, later}) {
            assertThat(detail.unit()).isEqualTo(Unit.YEARS);
            assertThat(detail.value()).isEqualTo(34);
            assertThat(detail.years()).isEqualTo(34);
            assertThat(detail.fromDateOfBirth()).isFalse();
            assertThat(detail.totalMonths()).isNull();
            assertThat(detail.daysAfterMonths()).isNull();
            assertThat(detail.totalDays()).isNull();
        }
    }

    @Test
    void aDateOfBirthWinsOverAStaleStoredAge() {
        // the column says 5 (written years ago); the date says 6 today
        PatientAge detail = PatientAge.of(LocalDate.of(2020, 3, 4), 5, TODAY);
        assertThat(detail.years()).isEqualTo(6);
        assertThat(detail.fromDateOfBirth()).isTrue();
    }

    @Test
    void aDateAfterTheDayCountsAsBornThatDayNeverNegative() {
        PatientAge detail = PatientAge.fromDate(TODAY.plusDays(3), TODAY);
        assertThat(detail.value()).isZero();
        assertThat(detail.unit()).isEqualTo(Unit.DAYS);
        assertThat(PatientAge.completedYears(TODAY.plusYears(1), TODAY)).isZero();
        assertThat(PatientAge.completedMonths(TODAY.plusYears(1), TODAY)).isZero();
        assertThat(PatientAge.totalDays(TODAY.plusDays(2), TODAY)).isZero();
    }

    // ---- validation helpers (SO-73-7) ----

    @Test
    void todayIsNotTheFutureAndTomorrowIs() {
        assertThat(PatientAge.isFuture(TODAY, TODAY)).isFalse();
        assertThat(PatientAge.isFuture(TODAY.plusDays(1), TODAY)).isTrue();
    }

    @Test
    void the120YearLimitIsOnCompletedYears() {
        assertThat(PatientAge.exceedsMaxYears(LocalDate.of(1906, 11, 13), TODAY)).as("exactly 120 years").isFalse();
        assertThat(PatientAge.exceedsMaxYears(LocalDate.of(1906, 11, 12), TODAY)).as("120 years and a day").isFalse();
        assertThat(PatientAge.exceedsMaxYears(LocalDate.of(1905, 11, 14), TODAY)).as("120 years 11 months").isFalse();
        assertThat(PatientAge.exceedsMaxYears(LocalDate.of(1905, 11, 13), TODAY)).as("121 years").isTrue();
        assertThat(PatientAge.exceedsMaxYears(TODAY.plusDays(1), TODAY)).as("the future is another message").isFalse();
    }

    // ---- filter bounds (ADR-024 D1) ----

    @Test
    void aChildTurningThirteenLeavesChildrenOnTheBirthdayAndA29FebChildOn1Mar() {
        LocalDate bound = PatientAge.yearsBound(LocalDate.of(2026, 11, 13), 13);
        assertThat(bound).isEqualTo(LocalDate.of(2013, 11, 13));
        // dob after the bound is under 13; on or before the bound is 13 or over
        assertThat(LocalDate.of(2013, 11, 14).isAfter(bound)).isTrue();
        assertThat(LocalDate.of(2013, 11, 13).isAfter(bound)).isFalse();
        // born 29 Feb 2024: still under 13 on 28 Feb 2037, 13 on 1 Mar 2037
        LocalDate leapBorn = LocalDate.of(2024, 2, 29);
        assertThat(leapBorn.isAfter(PatientAge.yearsBound(LocalDate.of(2037, 2, 28), 13))).isTrue();
        assertThat(leapBorn.isAfter(PatientAge.yearsBound(LocalDate.of(2037, 3, 1), 13))).isFalse();
        assertThat(PatientAge.completedYears(leapBorn, LocalDate.of(2037, 2, 28))).isEqualTo(12);
        assertThat(PatientAge.completedYears(leapBorn, LocalDate.of(2037, 3, 1))).isEqualTo(13);
    }

    @Test
    void theBoundAgreesWithCompletedYearsForEveryDayOverAFourYearCycle() {
        // "completed years >= N" is exactly "dob <= today.minusYears(N)", leap days included
        for (int years : new int[] {13, 60}) {
            for (LocalDate today = LocalDate.of(2027, 1, 1); today.isBefore(LocalDate.of(2029, 1, 1));
                    today = today.plusDays(1)) {
                LocalDate bound = PatientAge.yearsBound(today, years);
                for (LocalDate dob = bound.minusDays(3); !dob.isAfter(bound.plusDays(3)); dob = dob.plusDays(1)) {
                    boolean byBound = !dob.isAfter(bound);
                    boolean byYears = PatientAge.completedYears(dob, today) >= years;
                    assertThat(byBound).as("dob %s on %s for %d", dob, today, years).isEqualTo(byYears);
                }
            }
        }
    }

    @Test
    void theSeniorsBoundIsSixtyYearsBack() {
        assertThat(PatientAge.yearsBound(TODAY, 60)).isEqualTo(LocalDate.of(1966, 11, 13));
    }

    // ---- the growth point position (chart only) ----

    @Test
    void monthsAtAPointAreCompletedMonthsPlusDaysOver30Point4375OneDecimal() {
        LocalDate born = LocalDate.of(2025, 5, 12);
        assertThat(PatientAge.monthsAt(born, LocalDate.of(2026, 11, 12))).isEqualByComparingTo(new BigDecimal("18.0"));
        // 17 months and 8 days: 17 + 8 / 30.4375 = 17.26 -> 17.3
        assertThat(PatientAge.monthsAt(born, LocalDate.of(2026, 10, 20))).isEqualByComparingTo(new BigDecimal("17.3"));
        assertThat(PatientAge.monthsAt(born, born)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(PatientAge.monthsAt(born, LocalDate.of(2026, 10, 20)).scale()).isEqualTo(1);
    }

    @Test
    void monthsAtIsNullWithoutADateOfBirthOrBeforeIt() {
        assertThat(PatientAge.monthsAt(null, TODAY)).isNull();
        assertThat(PatientAge.monthsAt(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 11))).isNull();
        assertThat(PatientAge.monthsAt(LocalDate.of(2025, 5, 12), null)).isNull();
    }

    // ---- privacy ----

    @Test
    void toStringPrintsTheUnitAndTheValueOnlyNeverADate() {
        String text = age("2025-05-10", "2026-11-13").toString();
        assertThat(text).isEqualTo("PatientAge[MONTHS 18]");
        assertThat(text).doesNotContain("2025").doesNotContain("2026").doesNotContain("552");
        assertThat(PatientAge.of(null, 34, TODAY).toString()).isEqualTo("PatientAge[YEARS 34]");
    }
}
