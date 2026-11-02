package com.sahaj.opd.application;

import java.util.Locale;

/**
 * Turns what a person typed into a safe "starts with" pattern for LIKE (with a backslash as the
 * escape character): the typed text never acts as a wildcard.
 */
public final class SearchPrefix {

    private SearchPrefix() {
    }

    /** Lower-case pattern for a name column; blank gives {@code %} (everything). */
    public static String lowerPattern(String typed) {
        return escape(typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    /** Upper-case pattern for a code column such as J06.9. */
    public static String upperPattern(String typed) {
        return escape(typed == null ? "" : typed.trim().toUpperCase(Locale.ROOT)) + "%";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
