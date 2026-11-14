package com.sahaj.patient.api;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * SAH-75 (ADR-026 D12, opd.yaml 0.7.1): what the patient file's allergy text says, worked out in one place so that
 * {@code opd} (the record header chips), {@code ipd} (the medicine screen chip) and the sign write-back agree.
 *
 * <ul>
 *   <li>{@link #NOT_RECORDED}: no text (null or blank); nothing was asked or written.</li>
 *   <li>{@link #NONE_RECORDED}: the whole text, trimmed, ignoring case and trailing full stops, is one
 *       {@linkplain #isNoneWord none word}.</li>
 *   <li>{@link #RECORDED}: any other text.</li>
 * </ul>
 *
 * <p>Pure and stateless. The text is clinical data: it is read here and never logged or put in a message.
 */
public enum AllergyStatus {
    RECORDED, NONE_RECORDED, NOT_RECORDED;

    /**
     * The shared none-word list (ADR-026 D12; H19): what a doctor types, picks or writes to say there is no allergy.
     * Written in lower case; {@link #isNoneWord} ignores case, outer space and trailing full stops.
     */
    private static final Set<String> NONE_WORDS = Set.of("none", "nil", "nka", "nkda", "none known",
            "no known allergy", "no known allergies", "no known drug allergy", "no known drug allergies");

    /** The status of an allergy text (null allowed). */
    public static AllergyStatus of(String allergyText) {
        if (allergyText == null || strip(allergyText).isEmpty()) {
            return NOT_RECORDED;
        }
        return isNoneWord(allergyText) ? NONE_RECORDED : RECORDED;
    }

    /**
     * True when the whole text is one none word: trimmed, any case, trailing full stops ignored. "None", "NKDA.",
     * "No known allergy" and "none known" are none words; "None of the usual" and "Penicillin, none known otherwise"
     * are not.
     */
    public static boolean isNoneWord(String text) {
        if (text == null) {
            return false;
        }
        String normal = strip(Normalizer.normalize(text, Normalizer.Form.NFC)).toLowerCase(Locale.ROOT);
        int end = normal.length();
        while (end > 0 && normal.charAt(end - 1) == '.') {
            end--;
        }
        return NONE_WORDS.contains(strip(normal.substring(0, end)));
    }

    /** White space and Unicode space separators (such as a non-breaking space) removed at both ends. */
    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.charAt(start))) {
            start++;
        }
        while (end > start && space(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
