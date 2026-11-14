package com.sahaj.patient.application;

import java.time.LocalDate;

/**
 * SAH-73 (ADR-024 D5): the three states of {@code dateOfBirth} in a request, plus the one that could not be read.
 * {@code ABSENT} (property missing) leaves the date unchanged on update; {@code NULL} (explicit null) clears it on
 * update; {@code DATE} sets it; {@code INVALID} (not a string in the form YYYY-MM-DD, or a date that does not exist) is
 * refused with "Enter a valid date of birth." The unreadable text is never kept, so it cannot reach a log or an
 * answer. Personal data: {@code toString} shows the state only.
 */
public record DateOfBirthInput(State state, LocalDate date) {

    public enum State { ABSENT, NULL, DATE, INVALID }

    public static final DateOfBirthInput ABSENT = new DateOfBirthInput(State.ABSENT, null);
    public static final DateOfBirthInput CLEARED = new DateOfBirthInput(State.NULL, null);
    public static final DateOfBirthInput INVALID = new DateOfBirthInput(State.INVALID, null);

    public static DateOfBirthInput of(LocalDate date) {
        return date == null ? CLEARED : new DateOfBirthInput(State.DATE, date);
    }

    /** True when the request said anything about the date (a date, null, or something unreadable). */
    public boolean sent() {
        return state != State.ABSENT;
    }

    @Override
    public String toString() {
        return "DateOfBirthInput[" + state + "]";
    }
}
