package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.MedicineCodeCheck.Result;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-76-4 the medicine code box (ADR-027 D7, OQ-76-16): normalised as the wristband, matched against the drug's codes. */
class MedicineCodeCheckTest {

    private static final Set<String> CODES = Set.of("8901234567890", "HEP-5000");

    @Test
    void anEmptyOrMissingBoxIsEmpty() {
        assertThat(MedicineCodeCheck.check(null, CODES)).isEqualTo(Result.EMPTY);
        assertThat(MedicineCodeCheck.check("", CODES)).isEqualTo(Result.EMPTY);
        assertThat(MedicineCodeCheck.check(" \t\r\n ", CODES)).isEqualTo(Result.EMPTY);
    }

    @Test
    void aScannerSuffixAndCaseAreIgnored() {
        assertThat(MedicineCodeCheck.check("8901234567890\r\n", CODES)).isEqualTo(Result.MATCH);
        assertThat(MedicineCodeCheck.check(" hep-5000 ", CODES)).isEqualTo(Result.MATCH);
        assertThat(MedicineCodeCheck.check("８９０１２３４５６７８９０", CODES)).as("full-width digits fold (NFKC)")
                .isEqualTo(Result.MATCH);
    }

    @Test
    void anotherCodeIsAMismatch() {
        assertThat(MedicineCodeCheck.check("8901234567891", CODES)).isEqualTo(Result.MISMATCH);
        assertThat(MedicineCodeCheck.check("HEP", Set.of())).as("a drug with no codes loaded").isEqualTo(Result.MISMATCH);
    }

    @Test
    void aBoxOverTheLimitNeverMatches() {
        String longBox = "A".repeat(MedicineCodeCheck.MAX_BOX + 1);

        assertThat(MedicineCodeCheck.check(longBox, Set.of(longBox))).isEqualTo(Result.MISMATCH);
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
