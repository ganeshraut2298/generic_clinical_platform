package com.sahaj.patient.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * SAH-73 (ADR-024 D1, D2): the one place where a patient's age is worked out. Pure Java 17, no Spring, no database.
 * A patient's age is never read from a stored number when a date of birth exists: it is computed here, on every read,
 * as of today in India ({@link #INDIA}, from an injected {@link Clock} so that tests fix the day). All values come from
 * {@link Period} and {@link ChronoUnit}, which give the 29 February rule (a 29 Feb birthday is on 1 Mar in other
 * years) for free.
 *
 * <p>Value object ({@code years}, {@code unit}, {@code value}, {@code fromDateOfBirth}, and the months and days kept for
 * Paediatrics) plus the static functions that both {@code patient} and {@code opd} call. {@code asOf} and the day
 * counts are carried so that opd needs no second implementation, but a date of birth can be recovered from them: the
 * record is never serialised, never logged, and {@link #toString()} prints the unit and the value only.
 *
 * <p>Without a date of birth the age is the stored whole number (YEARS) and the months, the days after the months and
 * the total days are null.
 */
public record PatientAge(LocalDate asOf, int years, Unit unit, int value, boolean fromDateOfBirth,
                         Integer totalMonths, Integer daysAfterMonths, Long totalDays) {

    /** The unit the screen shows the age in (BR73-7). */
    public enum Unit { YEARS, MONTHS, WEEKS, DAYS }

    /** The clinics' day: India, not the database server's or the browser's zone. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A date of birth with more completed years than this is refused (SO-73-7). */
    public static final int MAX_YEARS = 120;

    private static final int YEARS_FROM = 2;
    private static final int MONTHS_FROM = 3;
    private static final int WEEKS_FROM_DAYS = 14;
    private static final BigDecimal AVERAGE_DAYS_PER_MONTH = new BigDecimal("30.4375");

    /** Today in India from the given clock, whatever zone the clock itself has. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(INDIA));
    }

    /** The age as of {@code asOf}: from the date of birth when there is one, else the stored whole number. */
    public static PatientAge of(LocalDate dateOfBirth, int storedAge, LocalDate asOf) {
        return dateOfBirth == null ? undated(storedAge, asOf) : fromDate(dateOfBirth, asOf);
    }

    /** No date of birth: YEARS, the stored number, unchanged over time (BR73-5). */
    public static PatientAge undated(int storedAge, LocalDate asOf) {
        return new PatientAge(asOf, storedAge, Unit.YEARS, storedAge, false, null, null, null);
    }

    /** Worked out from a date of birth (a date after {@code asOf} counts as born on {@code asOf}). */
    public static PatientAge fromDate(LocalDate dateOfBirth, LocalDate asOf) {
        LocalDate born = clamp(dateOfBirth, asOf);
        Period period = Period.between(born, asOf);
        int years = period.getYears();
        int months = Math.toIntExact(period.toTotalMonths());
        long days = ChronoUnit.DAYS.between(born, asOf);
        Unit unit;
        int value;
        if (years >= YEARS_FROM) {
            unit = Unit.YEARS;
            value = years;
        } else if (months >= MONTHS_FROM) {
            unit = Unit.MONTHS;
            value = months;
        } else if (days >= WEEKS_FROM_DAYS) {
            unit = Unit.WEEKS;
            value = Math.toIntExact(days / 7);
        } else {
            unit = Unit.DAYS;
            value = Math.toIntExact(days);
        }
        return new PatientAge(asOf, years, unit, value, true, months, period.getDays(), days);
    }

    /** Completed years as of {@code asOf}: born 29 Feb 2020 is 6 on 28 Feb 2027 and 7 on 1 Mar 2027. */
    public static int completedYears(LocalDate dateOfBirth, LocalDate asOf) {
        return Period.between(clamp(dateOfBirth, asOf), asOf).getYears();
    }

    /** Completed months as of {@code asOf}. */
    public static int completedMonths(LocalDate dateOfBirth, LocalDate asOf) {
        return Math.toIntExact(Period.between(clamp(dateOfBirth, asOf), asOf).toTotalMonths());
    }

    /** Days after the completed months as of {@code asOf}. */
    public static int daysAfterMonths(LocalDate dateOfBirth, LocalDate asOf) {
        return Period.between(clamp(dateOfBirth, asOf), asOf).getDays();
    }

    /** Whole days from the date of birth to {@code asOf}. */
    public static long totalDays(LocalDate dateOfBirth, LocalDate asOf) {
        return ChronoUnit.DAYS.between(clamp(dateOfBirth, asOf), asOf);
    }

    /** True when the date is later than {@code today} (a baby born today is not in the future). */
    public static boolean isFuture(LocalDate dateOfBirth, LocalDate today) {
        return dateOfBirth.isAfter(today);
    }

    /** True when the completed years as of {@code today} are above {@link #MAX_YEARS}. */
    public static boolean exceedsMaxYears(LocalDate dateOfBirth, LocalDate today) {
        return !isFuture(dateOfBirth, today) && completedYears(dateOfBirth, today) > MAX_YEARS;
    }

    /**
     * The filter bound of "completed years at least {@code years}": a date of birth on or before this date has that
     * many completed years as of {@code today}. A list filter uses this bound, never a function of the column.
     */
    public static LocalDate yearsBound(LocalDate today, int years) {
        return today.minusYears(years);
    }

    /**
     * The age at {@code date}, in months with one decimal, for the position of a growth point on the chart only (not a
     * rule): completed months plus the days after them divided by 30.4375. Null when there is no date of birth or the
     * date is before it.
     */
    public static BigDecimal monthsAt(LocalDate dateOfBirth, LocalDate date) {
        if (dateOfBirth == null || date == null || date.isBefore(dateOfBirth)) {
            return null;
        }
        BigDecimal days = new BigDecimal(daysAfterMonths(dateOfBirth, date));
        return new BigDecimal(completedMonths(dateOfBirth, date))
                .add(days.divide(AVERAGE_DAYS_PER_MONTH, 6, RoundingMode.HALF_UP))
                .setScale(1, RoundingMode.HALF_UP);
    }

    private static LocalDate clamp(LocalDate dateOfBirth, LocalDate asOf) {
        return dateOfBirth.isAfter(asOf) ? asOf : dateOfBirth;
    }

    /** The unit and the value only: never the date, never the day counts. */
    @Override
    public String toString() {
        return "PatientAge[" + unit + " " + value + "]";
    }
}
