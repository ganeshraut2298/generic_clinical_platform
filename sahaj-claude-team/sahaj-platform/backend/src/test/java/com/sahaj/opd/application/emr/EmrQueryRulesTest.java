package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.opd.application.SearchPrefix;
import com.sahaj.platform.api.InvalidInputException;
import org.junit.jupiter.api.Test;

/** ADR-019 D3, D10: the input rules of the EMR searches. */
class EmrQueryRulesTest {

    @Test
    void searchTextIsTrimmedAndNullIsEmpty() {
        assertThat(EmrQueryRules.searchText("  fev ")).isEqualTo("fev");
        assertThat(EmrQueryRules.searchText("   ")).isEmpty();
        assertThat(EmrQueryRules.searchText(null)).isEmpty();
    }

    @Test
    void aHundredCharactersPassAndOneMoreIsRefusedOnFieldQ() {
        assertThat(EmrQueryRules.searchText("a".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> EmrQueryRules.searchText("a".repeat(101)))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> {
                    assertThat(ex.field()).isEqualTo("q");
                    assertThat(ex.getMessage()).isEqualTo("Search text must be 100 characters or fewer.");
                });
    }

    @Test
    void theLengthIsMeasuredAfterTrimming() {
        assertThat(EmrQueryRules.searchText(" ".repeat(500) + "fever" + " ".repeat(500))).isEqualTo("fever");
    }

    @Test
    void onlyTheFourTemplateSpecialtiesAreAccepted() {
        assertThat(EmrQueryRules.specialty(null)).isNull();
        assertThat(EmrQueryRules.specialty("")).isNull();
        for (String code : new String[] {"gp", "dental", "paeds", "homeo"}) {
            assertThat(EmrQueryRules.specialty(code)).isEqualTo(code);
        }
        assertThatThrownBy(() -> EmrQueryRules.specialty("ayur"))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("specialty"));
        assertThatThrownBy(() -> EmrQueryRules.specialty("GP")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void pageIsZeroToFiveHundred() {
        assertThat(EmrQueryRules.page(null)).isZero();
        assertThat(EmrQueryRules.page(0)).isZero();
        assertThat(EmrQueryRules.page(500)).isEqualTo(500);
        assertThatThrownBy(() -> EmrQueryRules.page(-1))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("page"));
        assertThatThrownBy(() -> EmrQueryRules.page(501)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void limitIsOneToFiftyAndDefaultsToTen() {
        assertThat(EmrQueryRules.limit(null)).isEqualTo(10);
        assertThat(EmrQueryRules.limit(1)).isEqualTo(1);
        assertThat(EmrQueryRules.limit(50)).isEqualTo(50);
        assertThatThrownBy(() -> EmrQueryRules.limit(0))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("limit"));
        assertThatThrownBy(() -> EmrQueryRules.limit(51)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void wildcardCharactersAreMatchedLiterally() {
        assertThat(SearchPrefix.lowerPattern("50%")).isEqualTo("50\\%%");
        assertThat(SearchPrefix.lowerPattern("a_b")).isEqualTo("a\\_b%");
        assertThat(SearchPrefix.lowerPattern("a\\b")).isEqualTo("a\\\\b%");
        assertThat(SearchPrefix.lowerPattern("%%%")).isEqualTo("\\%\\%\\%%");
        assertThat(SearchPrefix.lowerPattern("  ")).isEqualTo("%");
    }

    @Test
    void keyFormatsFollowTheContract() {
        assertThat(EmrKeys.isWellFormedTemplateKey("gp")).isTrue();
        assertThat(EmrKeys.isWellFormedTemplateKey("ayur")).isTrue();
        assertThat(EmrKeys.isWellFormedTemplateKey("G")).isFalse();
        assertThat(EmrKeys.isWellFormedTemplateKey("1gp")).isFalse();
        assertThat(EmrKeys.isWellFormedTemplateKey("a".repeat(21))).isFalse();
        assertThat(EmrKeys.isWellFormedTemplateKey("gp'--")).isFalse();
        assertThat(EmrKeys.isWellFormedSectionKey("chief_complaints")).isTrue();
        assertThat(EmrKeys.isWellFormedSectionKey("Chief")).isFalse();
        assertThat(EmrKeys.isWellFormedSectionKey("a".repeat(41))).isFalse();
    }
}
