package com.sahaj.patient.infrastructure.persistence;

import java.util.Locale;

/**
 * Turns what a person typed into a safe "contains" pattern for LIKE (with a backslash as the
 * escape character): the typed text never acts as a wildcard. Unlike {@code opd.application.SearchPrefix}
 * (a "starts with" match), the patient list's search box is one free-text field clinic staff use
 * with name fragments, so a substring match is used instead; module boundaries keep this a small
 * copy here rather than a cross-module import.
 */
final class SearchPattern {

    private SearchPattern() {
    }

    /** Lower-case "contains" pattern; null or blank input gives null (meaning: no filter). */
    static String contains(String typed) {
        if (typed == null) {
            return null;
        }
        String trimmed = typed.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : "%" + escape(trimmed) + "%";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
