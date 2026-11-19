package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.MedicineCodeCheck.Result;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * SAH-76-4 the medicine code box (ADR-027 D7, OQ-76-16): normalised as the wristband, matched against the drug's codes.
 * DEC-065: a box that is no code of any drug, for a drug with no codes loaded, is NO_CODE_LOADED and not a mismatch.
 */
class MedicineCodeCheckTest {

    private static final Set<String> CODES = Set.of("8901234567890", "HEP-5000");
    private static final Predicate<String> UNKNOWN_TO_ALL = code -> false;
    private static final Predicate<String> KNOWN_TO_ANOTHER_DRUG = code -> true;

    @Test
    void anEmptyOrMissingBoxIsEmpty() {
        assertThat(MedicineCodeCheck.check(null, CODES, UNKNOWN_TO_ALL)).isEqualTo(Result.EMPTY);
        assertThat(MedicineCodeCheck.check("", CODES, UNKNOWN_TO_ALL)).isEqualTo(Result.EMPTY);
        assertThat(MedicineCodeCheck.check(" \t\r\n ", CODES, UNKNOWN_TO_ALL)).isEqualTo(Result.EMPTY);
        assertThat(MedicineCodeCheck.check(" \t\r\n ", Set.of(), UNKNOWN_TO_ALL)).as("no codes loaded, empty box")
                .isEqualTo(Result.EMPTY);
    }

    @Test
    void aScannerSuffixAndCaseAreIgnored() {
        assertThat(MedicineCodeCheck.check("8901234567890\r\n", CODES, UNKNOWN_TO_ALL)).isEqualTo(Result.MATCH);
        assertThat(MedicineCodeCheck.check(" hep-5000 ", CODES, UNKNOWN_TO_ALL)).isEqualTo(Result.MATCH);
        assertThat(MedicineCodeCheck.check("８９０１２３４５６７８９０", CODES, UNKNOWN_TO_ALL))
                .as("full-width digits fold (NFKC)").isEqualTo(Result.MATCH);
    }

    @Test
    void anotherCodeIsAMismatch() {
        assertThat(MedicineCodeCheck.check("8901234567891", CODES, UNKNOWN_TO_ALL)).as("the drug has codes")
                .isEqualTo(Result.MISMATCH);
        assertThat(MedicineCodeCheck.check("8901234567891", CODES, KNOWN_TO_ANOTHER_DRUG)).isEqualTo(Result.MISMATCH);
        assertThat(MedicineCodeCheck.check("HEP-9999", Set.of(), KNOWN_TO_ANOTHER_DRUG))
                .as("no codes loaded, but the code belongs to another drug").isEqualTo(Result.MISMATCH);
    }

    @Test
    void aCodeNoDrugHasForADrugWithNoCodesIsNoCodeLoaded() {
        assertThat(MedicineCodeCheck.check("HEP", Set.of(), UNKNOWN_TO_ALL)).isEqualTo(Result.NO_CODE_LOADED);
        assertThat(MedicineCodeCheck.check(" 8901234567891\r\n", Set.of(), UNKNOWN_TO_ALL))
                .isEqualTo(Result.NO_CODE_LOADED);
    }

    @Test
    void theNormalisedCodeIsWhatTheCatalogueIsAsked() {
        java.util.List<String> asked = new java.util.ArrayList<>();

        MedicineCodeCheck.check(" hep-9999\r\n", Set.of(), code -> {
            asked.add(code);
            return false;
        });

        assertThat(asked).containsExactly("HEP-9999");
    }

    @Test
    void aBoxOverTheLimitNeverMatches() {
        String longBox = "A".repeat(MedicineCodeCheck.MAX_BOX + 1);

        assertThat(MedicineCodeCheck.check(longBox, Set.of(longBox), UNKNOWN_TO_ALL)).as("the drug has codes")
                .isEqualTo(Result.MISMATCH);
        assertThat(MedicineCodeCheck.check(longBox, Set.of(), KNOWN_TO_ANOTHER_DRUG)).as("the drug has no codes")
                .isEqualTo(Result.NO_CODE_LOADED);
    }

    @Test
    void theCatalogueIsAskedOnlyWhenTheDrugHasNoCodes() {
        AtomicInteger asked = new AtomicInteger();
        Predicate<String> counting = code -> {
            asked.incrementAndGet();
            return false;
        };

        MedicineCodeCheck.check("8901234567890", CODES, counting);
        MedicineCodeCheck.check("8901234567891", CODES, counting);
        MedicineCodeCheck.check("A".repeat(MedicineCodeCheck.MAX_BOX + 1), Set.of(), counting);
        MedicineCodeCheck.check("", Set.of(), counting);
        MedicineCodeCheck.check(null, Set.of(), counting);

        assertThat(asked).hasValue(0);
    }

    @Test
    void theEventLogHoldsAKnownCodeOnlyAndNeverAnythingTyped() {
        assertThat(MedicineCodeCheck.codeForEvent(" hep-5000\n", true)).isEqualTo("HEP-5000");
        assertThat(MedicineCodeCheck.codeForEvent("9876543210", false)).isEqualTo(MedicineCodeCheck.UNKNOWN_CODE);
        assertThat(MedicineCodeCheck.codeForEvent("A".repeat(101), true)).isEqualTo(MedicineCodeCheck.UNKNOWN_CODE);
        assertThat(MedicineCodeCheck.codeForEvent(null, true)).isEqualTo(MedicineCodeCheck.UNKNOWN_CODE);
    }

    @Test
    void onlyTextInTheShapeOfACodeIsLookedUp() {
        assertThat(MedicineCodeCheck.looksLikeCode("8901234567890")).isTrue();
        assertThat(MedicineCodeCheck.looksLikeCode("ABC")).isFalse();
        assertThat(MedicineCodeCheck.looksLikeCode("AB CD")).isFalse();
        assertThat(MedicineCodeCheck.looksLikeCode("A".repeat(65))).isFalse();
        assertThat(MedicineCodeCheck.looksLikeCode(null)).isFalse();
    }
}
