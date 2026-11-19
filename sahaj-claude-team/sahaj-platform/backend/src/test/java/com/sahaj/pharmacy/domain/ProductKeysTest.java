package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The duplicate key of design note SAH-78 section 3.1, the same rule as the SQL of the sample seed. */
class ProductKeysTest {

    @Test
    void saltAndFormAreTrimmedLowerCaseWithSpacesCollapsed() {
        assertThat(ProductKeys.dupKey("  Amoxicillin  +   Clavulanic Acid ", "500mg + 125mg", " TABLET  ")).isEqualTo(
                "amoxicillin + clavulanic acid|500mg+125mg|tablet");
    }

    @Test
    void aValueInGramsIsWrittenAsMilligrams() {
        assertThat(ProductKeys.dupKey("Ceftriaxone", "1 g", "injection")).isEqualTo("ceftriaxone|1000mg|injection");
        assertThat(ProductKeys.dupKey("Ceftriaxone", "1000 MG", "Injection")).isEqualTo("ceftriaxone|1000mg|injection");
        assertThat(ProductKeys.strength("21 g")).isEqualTo("21000mg");
        assertThat(ProductKeys.strength("0.5 G")).isEqualTo("500mg");
        assertThat(ProductKeys.strength("1.25g")).isEqualTo("1250mg");
        assertThat(ProductKeys.strength("100 g")).isEqualTo("100000mg");
    }

    @Test
    void otherStrengthsLoseTheirSpacesAndKeepTheirUnit() {
        assertThat(ProductKeys.strength(" 5 mg / 5 ml ")).isEqualTo("5mg/5ml");
        assertThat(ProductKeys.strength("650 mg")).isEqualTo("650mg");
        assertThat(ProductKeys.strength("1 kit")).isEqualTo("1kit");
        assertThat(ProductKeys.strength("10 IU")).isEqualTo("10iu");
        // text that merely ends in g is not grams
        assertThat(ProductKeys.strength("1 mg")).isEqualTo("1mg");
        assertThat(ProductKeys.strength("big")).isEqualTo("big");
    }

    /** Code review N-8: text pasted from a spreadsheet or a PDF carries no-break spaces. */
    @Test
    void aNonBreakingSpaceCountsAsASpaceInSaltAndForm() {
        String nbsp = " ";
        assertThat(ProductKeys.words(nbsp + "Amoxicillin" + nbsp + nbsp + "+ Clavulanic" + nbsp + "Acid" + nbsp))
                .isEqualTo("amoxicillin + clavulanic acid");
        assertThat(ProductKeys.dupKey("Ceftriaxone" + nbsp, "1" + nbsp + "g", nbsp + "Injection"))
                .isEqualTo("ceftriaxone|1000mg|injection");
        assertThat(ProductKeys.dupKey("Amoxicillin" + nbsp + "+" + nbsp + "Clavulanic Acid", "500mg", "Tablet"))
                .isEqualTo(ProductKeys.dupKey("Amoxicillin + Clavulanic Acid", "500mg", "Tablet"));
    }

    @Test
    void aNonBreakingSpaceIsRemovedFromTheStrengthAndDoesNotHideGrams() {
        String nbsp = " ";
        assertThat(ProductKeys.strength("5" + nbsp + "mg" + nbsp + "/" + nbsp + "5" + nbsp + "ml")).isEqualTo("5mg/5ml");
        assertThat(ProductKeys.strength(nbsp + "1" + nbsp + "g" + nbsp)).isEqualTo("1000mg");
        assertThat(ProductKeys.strength("0.5" + nbsp + "G")).isEqualTo("500mg");
        assertThat(ProductKeys.strength("1000" + nbsp + "MG")).isEqualTo("1000mg");
        assertThat(ProductKeys.dupKey("Ceftriaxone", "1" + nbsp + "g", "injection"))
                .isEqualTo(ProductKeys.dupKey("Ceftriaxone", "1000 mg", "injection"));
    }

    @Test
    void theOtherUnicodeSpacesAndTabsAreSpacesToo() {
        // narrow no-break space, figure space, ideographic space, em space, line separator, tab and new line
        for (String space : new String[] {" ", " ", "　", " ", " ", "\t", "\n", "\u0085"}) {
            assertThat(ProductKeys.words(space + "Salt" + space + space + "Name" + space)).as("words " + (int) space.charAt(0))
                    .isEqualTo("salt name");
            assertThat(ProductKeys.strength("2" + space + "g")).as("strength " + (int) space.charAt(0)).isEqualTo("2000mg");
        }
    }

    @Test
    void aSeededSampleRowMatchesTheSameProductTypedByHand() {
        // G-000101 in the sample seed: Ceftriaxone, 1 g, injection
        assertThat(ProductKeys.dupKey("Ceftriaxone", "1 g", "injection"))
                .isEqualTo(ProductKeys.dupKey(" ceftriaxone", "1000 mg ", "Injection"));
    }
}
