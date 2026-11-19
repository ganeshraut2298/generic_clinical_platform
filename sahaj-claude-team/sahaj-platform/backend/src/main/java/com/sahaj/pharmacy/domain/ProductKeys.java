package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The duplicate key of a product (design note SAH-78 section 3.1, ADR-028 D3): {@code salt|strength|form}. Salt and form are
 * trimmed, lower case, runs of white space collapsed to one. The strength has all white space removed and is lower case,
 * and a value in grams is written as milligrams ("1 g" and "1000 mg" both give {@code 1000mg}). The sample seed computes the same key in
 * SQL, so a hand-typed own product matches a seeded catalogue row. The key is advice for the duplicate check only: the
 * database never rejects on it.
 *
 * <p>White space is Unicode white space (code review N-8): a non-breaking space (U+00A0, common in text pasted from a
 * spreadsheet or a PDF), a narrow no-break space and the other Unicode spaces count like a plain space. {@code String.trim()}
 * and {@code Character.isWhitespace} do not treat the no-break spaces as white space, so the code does not use them.
 */
public final class ProductKeys {

    private static final int UNICODE_SPACE = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern GRAMS = Pattern.compile("^[0-9]+(\\.[0-9]+)?\\s*g$",
            Pattern.CASE_INSENSITIVE | UNICODE_SPACE);
    private static final Pattern GRAMS_SUFFIX = Pattern.compile("\\s*g$", Pattern.CASE_INSENSITIVE | UNICODE_SPACE);
    private static final Pattern SPACES = Pattern.compile("\\s+", UNICODE_SPACE);
    private static final Pattern EDGES = Pattern.compile("^\\s+|\\s+$", UNICODE_SPACE);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private ProductKeys() {
    }

    public static String dupKey(String salt, String strength, String form) {
        return words(salt) + "|" + strength(strength) + "|" + words(form);
    }

    /** Trimmed, lower case, runs of white space (Unicode) collapsed to one plain space. */
    static String words(String text) {
        return SPACES.matcher(trim(text)).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** The text without its leading and trailing Unicode white space. */
    private static String trim(String text) {
        return EDGES.matcher(text).replaceAll("");
    }

    /** No white space, lower case; a plain value in grams becomes milligrams. */
    static String strength(String text) {
        String trimmed = trim(text);
        if (GRAMS.matcher(trimmed).matches()) {
            BigDecimal grams = new BigDecimal(GRAMS_SUFFIX.matcher(trimmed).replaceAll(""));
            return grams.multiply(THOUSAND).stripTrailingZeros().toPlainString() + "mg";
        }
        return SPACES.matcher(trimmed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
