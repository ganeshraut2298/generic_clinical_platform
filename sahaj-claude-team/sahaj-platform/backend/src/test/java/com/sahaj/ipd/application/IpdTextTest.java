package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The control and text-direction check shared by names and reasons (security review L4). */
class IpdTextTest {

    @Test
    void rejectsControlBidiZeroWidthAndSeparatorCharacters() {
        int[] bad = {0x00, 0x07, 0x09, 0x0A, 0x1F, 0x7F, 0x85, 0x9F, 0x061C, 0x200B, 0x200E, 0x200F,
                0x2028, 0x2029, 0x202A, 0x202E, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x2066, 0x2069, 0xFEFF};
        for (int codePoint : bad) {
            String text = "ab" + (char) codePoint + "cd";
            assertThat(IpdText.hasControlOrBidi(text)).as("U+%04X", codePoint).isTrue();
            assertThat(IpdText.hasControlOrBidi(text + "\u0007")).isTrue();
        }
    }

    // ---- strip (ADR-007 R6) ----

    @Test
    void stripRemovesWhiteSpaceAndEverySpaceSeparatorAtBothEnds() {
        int[] blanks = {0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C, 0x1C, 0x1F, 0xA0, 0x1680, 0x2000, 0x2001, 0x2005, 0x2007,
                0x2008, 0x200A, 0x202F, 0x205F, 0x3000};
        for (int blank : blanks) {
            String s = new String(Character.toChars(blank));
            assertThat(IpdText.strip(s + "word" + s)).as("U+%04X", blank).isEqualTo("word");
            assertThat(IpdText.strip(s + s + s)).as("only U+%04X", blank).isEmpty();
        }
    }

    @Test
    void stripHandlesNoBreakSpacesMixedWithLineBreaksAndKeepsInnerSpaces() {
        assertThat(IpdText.strip("\u00a0\u2007\u202f\u3000\n \r\n\u00a0\u2007\u202f\u3000")).isEmpty();
        assertThat(IpdText.strip("\u00a0\u2007\u202f\u3000\n  two  words \u00a0\n\u00a0\u2007\u202f\u3000")).isEqualTo("two  words");
        assertThat(IpdText.strip("a\u00a0b")).isEqualTo("a\u00a0b");
        assertThat(IpdText.strip("a\nb")).isEqualTo("a\nb");
    }

    @Test
    void stripOfNothingOrOrdinaryTextChangesNothing() {
        assertThat(IpdText.strip("")).isEmpty();
        assertThat(IpdText.strip("word")).isEqualTo("word");
        assertThat(IpdText.strip("\u0915\u0930")).isEqualTo("\u0915\u0930");
    }

    @Test
    void stripKeepsSupplementaryCharactersWhole() {
        assertThat(IpdText.strip("\u00a0\uD83D\uDE00\u00a0")).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void acceptsOrdinaryTextIncludingIndianScriptsAndPunctuation() {
        assertThat(IpdText.hasControlOrBidi("Dengue, plt 80k (कर) - O'Neil / 2x")).isFalse();
        assertThat(IpdText.hasControlOrBidi("")).isFalse();
        assertThat(IpdText.hasControlOrBidi(" ")).isFalse();
        assertThat(IpdText.hasControlOrBidi("a b")).isFalse();
        assertThat(IpdText.hasControlOrBidi("a‧b")).isFalse();
        assertThat(IpdText.hasControlOrBidi("a b")).isFalse();
        assertThat(IpdText.hasControlOrBidi("a b")).isFalse();
    }

    @Test
    void acceptsTheZeroWidthJoinerAndNonJoinerThatIndianScriptsNeedForConjuncts() {
        // Devanagari: the conjunct "kshna" written with a zero-width joiner (U+200D), and a half-form kept apart
        // with a zero-width non-joiner (U+200C), as in Hindi and Marathi spellings.
        assertThat(IpdText.hasControlOrBidi("क्‍ष")).isFalse();
        assertThat(IpdText.hasControlOrBidi("क्‌ष")).isFalse();
        assertThat(IpdText.hasControlOrBidi("रेखा क्‍षत्रिय"))
                .isFalse();
        // Bengali ya-phala spelling with a zero-width non-joiner.
        assertThat(IpdText.hasControlOrBidi("স্‌য")).isFalse();
    }

    @Test
    void acceptsAJoinerAfterEachVirama() {
        String viramas = "\u094D\u09CD\u0A4D\u0ACD\u0B4D\u0BCD\u0C4D\u0CCD\u0D4D\u0DCA";
        for (char virama : viramas.toCharArray()) {
            assertThat(IpdText.hasControlOrBidi("\u0915" + virama + "\u200D\u0937")).as("ZWJ after U+%04X", (int) virama)
                    .isFalse();
            assertThat(IpdText.hasControlOrBidi("\u0915" + virama + "\u200C\u0937")).as("ZWNJ after U+%04X", (int) virama)
                    .isFalse();
        }
    }

    @Test
    void refusesAJoinerThatIsFirstLastDoubledOrNotAfterAVirama() {
        // Latin look-alikes: IC<ZWJ>U would read as ICU
        assertThat(IpdText.hasControlOrBidi("IC\u200DU")).isTrue();
        assertThat(IpdText.hasControlOrBidi("IC\u200CU")).isTrue();
        // first and last
        assertThat(IpdText.hasControlOrBidi("\u200D\u0915")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u200C\u0915")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200D")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200C")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u200D")).isTrue();
        // doubled, mixed, or a joiner kept apart from its virama
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200D\u200D\u0937")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200C\u200C\u0937")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200D\u200C\u0937")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D \u200D\u0937")).isTrue();
        // after a consonant that is not a virama, and before a space
        assertThat(IpdText.hasControlOrBidi("\u0915\u200D\u0937")).isTrue();
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200D \u0937")).isTrue();
        // a joiner after the virama of a word does not spare the next one
        assertThat(IpdText.hasControlOrBidi("\u0915\u094D\u200D\u0937 \u200D")).isTrue();
    }

    @Test
    void composesToNfcSoTheSameWordTypedTwoWaysIsOne() {
        assertThat(IpdText.nfc("Cafe\u0301")).isEqualTo("Caf\u00E9");
        assertThat(IpdText.nfc("Caf\u00E9")).isEqualTo("Caf\u00E9");
        assertThat(IpdText.nfc("ICU")).isEqualTo("ICU");
        // joiners are kept
        assertThat(IpdText.nfc("\u0915\u094D\u200D\u0937")).isEqualTo("\u0915\u094D\u200D\u0937");
    }

    @Test
    void stillRejectsTheOtherInvisibleCharactersNextToTheJoiners() {
        assertThat(IpdText.hasControlOrBidi("क्‍ष​")).isTrue();
        assertThat(IpdText.hasControlOrBidi("क्‍ष‎")).isTrue();
        assertThat(IpdText.hasControlOrBidi("‍⁠")).isTrue();
        assertThat(IpdText.hasControlOrBidi("‌﻿")).isTrue();
    }


    // ---- SAH-28 security review L2: code points, not UTF-16 chars ----

    private static String cps(int... codePoints) {
        StringBuilder text = new StringBuilder();
        for (int codePoint : codePoints) {
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }

    private static String around(int codePoint) {
        return "ab" + cps(codePoint) + "cd";
    }

    @Test
    void refusesTheTagCharactersAndOtherFormatCharactersOutsideTheBasicPlane() {
        for (int codePoint : new int[] {0xE0001, 0xE0020, 0xE0041, 0xE007F}) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
    }

    @Test
    void refusesEveryFormatCharacterTheOldListMissed() {
        // soft hyphen, Arabic number signs, deprecated format characters, interlinear marks, supplementary format
        int[] formats = {0x00AD, 0x0600, 0x06DD, 0x070F, 0x180E, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F,
                0xFFF9, 0xFFFA, 0xFFFB, 0x110BD, 0x1D173};
        for (int codePoint : formats) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
    }

    @Test
    void refusesTheBlankLookingHangulFillersBrailleBlankAndMongolianSeparator() {
        for (int codePoint : new int[] {0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x180E}) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
    }

    @Test
    void refusesTheCombiningGraphemeJoinerAndTheVariationSelectors() {
        assertThat(IpdText.hasControlOrBidi(around(0x034F))).isTrue();
        for (int codePoint = 0xFE00; codePoint <= 0xFE0F; codePoint++) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
    }

    @Test
    void refusesPrivateUseUnassignedAndLoneSurrogateCodePoints() {
        for (int codePoint : new int[] {0xE000, 0xF8FF, 0xF0000, 0x10FFFD, 0x0378, 0x2065, 0xFFFF, 0x1FFFE}) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
        char high = (char) 0xD800;
        char low = (char) 0xDC00;
        assertThat(IpdText.hasControlOrBidi("ab" + high + "cd")).as("lone high surrogate").isTrue();
        assertThat(IpdText.hasControlOrBidi("ab" + low + "cd")).as("lone low surrogate").isTrue();
        assertThat(IpdText.hasControlOrBidi("ab" + high)).as("high surrogate at the end").isTrue();
        assertThat(IpdText.hasControlOrBidi("" + low + high)).as("pair in the wrong order").isTrue();
        assertThat(IpdText.hasControlOrBidi("ab" + high + low + "cd")).as("a valid pair is a supplementary letter or symbol")
                .isFalse();
    }

    @Test
    void refusesTheLineAndParagraphSeparatorsAndEveryControlCharacter() {
        for (int codePoint : new int[] {0x2028, 0x2029, 0x0000, 0x001F, 0x007F, 0x0085, 0x009F}) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isTrue();
        }
    }

    @Test
    void acceptsLettersFromOtherPlanesEmojiAndOrdinarySpaces() {
        // mathematical bold A, a smiling face, a no-break space, a thin space, an ideographic space, e acute
        for (int codePoint : new int[] {0x1D400, 0x1F600, 0x00A0, 0x2009, 0x3000, 0x00E9}) {
            assertThat(IpdText.hasControlOrBidi(around(codePoint))).as("U+%04X", codePoint).isFalse();
        }
        assertThat(IpdText.hasControlOrBidi("Ramesh Kulkarni, 52 M - chest pain (108)")).isFalse();
    }

    @Test
    void theJoinerRuleStillWorksNextToSupplementaryCharacters() {
        // Devanagari ka, virama, joiner, then a Brahmi letter (a supplementary letter) or an emoji
        assertThat(IpdText.hasControlOrBidi(cps(0x0915, 0x094D, 0x200D, 0x11005))).isFalse();
        assertThat(IpdText.hasControlOrBidi(cps(0x0915, 0x094D, 0x200D, 0x1F600))).isTrue();
    }

    @Test
    void hasLetterNeedsOneLetterOfAnyScript() {
        assertThat(IpdText.hasLetter("Ramesh")).isTrue();
        assertThat(IpdText.hasLetter("-- 12 .")).isFalse();
        assertThat(IpdText.hasLetter("")).isFalse();
        assertThat(IpdText.hasLetter(cps('4', '2', ' ', 0x0930))).isTrue();
        assertThat(IpdText.hasLetter(cps(0x1D400))).isTrue();
        assertThat(IpdText.hasLetter(cps(0x1F600))).isFalse();
    }

    @Test
    void hasLetterOrDigitAcceptsADigitAlone() {
        assertThat(IpdText.hasLetterOrDigit("108")).isTrue();
        assertThat(IpdText.hasLetterOrDigit("?!  ...")).isFalse();
        assertThat(IpdText.hasLetterOrDigit("")).isFalse();
        assertThat(IpdText.hasLetterOrDigit(cps(0x0966))).as("Devanagari digit zero").isTrue();
    }
}
