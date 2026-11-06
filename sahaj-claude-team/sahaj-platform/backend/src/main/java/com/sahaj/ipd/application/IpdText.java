package com.sahaj.ipd.application;

import java.text.Normalizer;

/** Free-text checks shared by the IPD services (security reviews L5 and SAH-26 L1, SAH-28 L2). */
final class IpdText {

    private static final int ZWNJ = 0x200C;
    private static final int ZWJ = 0x200D;

    /**
     * The viramas of the Indian scripts (IDNA CONTEXTJ): Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil,
     * Telugu, Kannada, Malayalam and Sinhala. A joiner is only meaningful straight after one of them.
     */
    private static final String VIRAMAS = "\u094D\u09CD\u0A4D\u0ACD\u0B4D\u0BCD\u0C4D\u0CCD\u0D4D\u0DCA";

    private IpdText() {
    }

    /**
     * Composes the text to Unicode NFC, so that the same word typed in two ways is stored, compared and checked
     * once. Run it first, on the raw text.
     */
    static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /**
     * True when the text holds a character that is invisible, blank-looking or able to disguise the text (display
     * spoofing). The text is read by Unicode code point, so characters outside the Basic Multilingual Plane (such as
     * the tag characters U+E0001 to U+E007F) and a lone surrogate are seen as what they are. Refused:
     * <ul>
     *   <li>every character of the general category Format (Cf): the soft hyphen U+00AD, U+061C, zero-width space
     *       U+200B, the direction marks and overrides U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069, the
     *       invisible operators U+2060 to U+2064, the deprecated U+206A to U+206F, the interlinear marks U+FFF9 to
     *       U+FFFB, the byte order mark U+FEFF and the tag characters;</li>
     *   <li>Control (C0, DEL, C1), Unassigned, Private use and Surrogate characters, and the line and paragraph
     *       separators U+2028 and U+2029;</li>
     *   <li>the blank-looking letters and symbols: Hangul fillers U+115F, U+1160, U+3164 and U+FFA0, the braille
     *       blank U+2800 and the Mongolian vowel separator U+180E;</li>
     *   <li>the combining grapheme joiner U+034F and the variation selectors U+FE00 to U+FE0F, which have no use in a
     *       name or a complaint and change nothing a reader can see;</li>
     *   <li>a zero-width non-joiner (U+200C) or joiner (U+200D) in the wrong place. Hindi, Marathi and Bengali
     *       spellings need the two joiners to form conjuncts (tech lead decision, SAH-26), so each is allowed only
     *       directly after a virama and directly before a letter: never first, never last, never doubled, never next
     *       to a space. Without that rule {@code IC<ZWJ>U} would look like {@code ICU} and be a look-alike duplicate
     *       (security review L1).</li>
     * </ul>
     * Run it on the raw, NFC text, before any trimming, so that a stray character at either end is not dropped
     * silently. The Unicode tables are those of the running Java 17, so a character added to Unicode later counts as
     * Unassigned until Java is updated.
     */
    static boolean hasControlOrBidi(String text) {
        int previous = -1;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            if (codePoint == ZWNJ || codePoint == ZWJ) {
                if (!joinerInPlace(previous, text, next)) {
                    return true;
                }
            } else if (refused(codePoint)) {
                return true;
            }
            previous = codePoint;
            index = next;
        }
        return false;
    }

    /**
     * Removes at both ends every code point that is white space ({@link Character#isWhitespace(int)}, which covers the
     * line breaks) or a Unicode space separator (category Zs: U+0020, U+00A0, U+1680, U+2000 to U+200A, U+202F,
     * U+205F, U+3000). {@code String.strip()} alone keeps U+00A0, U+2007 and U+202F, so a text made only of
     * no-break spaces would look empty on screen and not be empty for the server (SAH-33 code review 3, ADR-007 R6).
     * Run it after {@link #hasControlOrBidi}; nothing is changed inside the text.
     */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && blank(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && blank(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }

    /** True when the text has at least one letter (any script). A name made of punctuation or digits has none. */
    static boolean hasLetter(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    /** True when the text has at least one letter or digit (any script). */
    static boolean hasLetterOrDigit(String text) {
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean refused(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.FORMAT:
            case Character.CONTROL:
            case Character.UNASSIGNED:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return true;
            default:
                break;
        }
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0
                || codePoint == 0x2800 || codePoint == 0x180E || codePoint == 0x034F
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F);
    }

    /** The joiner at hand follows a virama (the code point just before it) and is followed by a letter. */
    private static boolean joinerInPlace(int previous, String text, int next) {
        return previous >= 0 && VIRAMAS.indexOf(previous) >= 0
                && next < text.length() && Character.isLetter(text.codePointAt(next));
    }
}
