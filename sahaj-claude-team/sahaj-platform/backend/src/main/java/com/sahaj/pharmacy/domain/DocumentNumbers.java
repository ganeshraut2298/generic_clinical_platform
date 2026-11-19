package com.sahaj.pharmacy.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a document number is written (ADR-028 D8; docs/api/pharmacy.yaml DocumentSeries): prefix, then the financial year
 * "26-27/" when the series shows it, then the sequence padded with zeros to the sequence length. The whole number is at
 * most 16 characters (DEC-062 Q4). The financial year runs from 1 April to 31 March (IST dates).
 */
public final class DocumentNumbers {

    public static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9/-]{1,6}$");
    public static final int MAX_LENGTH = 16;
    public static final int MIN_SEQUENCE = 4;
    public static final int MAX_SEQUENCE = 8;
    /** "26-27/" */
    public static final int FINANCIAL_YEAR_LENGTH = 6;

    private DocumentNumbers() {
    }

    /** The calendar year in which the April-to-March year of {@code date} starts (2026 for 1 Apr 2026 to 31 Mar 2027). */
    public static int financialYearStart(LocalDate date) {
        return date.getMonthValue() < 4 ? date.getYear() - 1 : date.getYear();
    }

    /** "26-27/" for the financial year that starts in {@code startYear}. */
    public static String financialYearPart(int startYear) {
        return String.format(Locale.ROOT, "%02d-%02d/", startYear % 100, (startYear + 1) % 100);
    }

    /** The number of the given sequence in the financial year of {@code date}. */
    public static String render(String prefix, boolean financialYearPart, int sequenceLength, long sequence,
                                LocalDate date) {
        StringBuilder number = new StringBuilder(prefix);
        if (financialYearPart) {
            number.append(financialYearPart(financialYearStart(date)));
        }
        number.append(String.format(Locale.ROOT, "%0" + sequenceLength + "d", sequence));
        return number.toString();
    }

    /** The length of a rendered number whatever the sequence (the sequence is never longer than its padding in the example). */
    public static int renderedLength(String prefix, boolean financialYearPart, int sequenceLength) {
        return prefix.length() + (financialYearPart ? FINANCIAL_YEAR_LENGTH : 0) + sequenceLength;
    }
}
