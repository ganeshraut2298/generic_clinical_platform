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
    void onlyTheFifteenStarterDepartmentKeysAreAccepted() {
        assertThat(EmrQueryRules.specialty(null)).isNull();
        assertThat(EmrQueryRules.specialty("")).isNull();
        assertThat(EmrKeys.STARTER_KEYS).hasSize(15);
        for (String code : EmrKeys.STARTER_KEYS) {
            assertThat(EmrQueryRules.specialty(code)).isEqualTo(code);
        }
        for (String retired : new String[] {"gp", "homeo", "ayur", "GM"}) {
            assertThatThrownBy(() -> EmrQueryRules.specialty(retired)).isInstanceOfSatisfying(
                    InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("specialty"));
        }
    }

    @Test
    void aStarterKeyIsMappedToTheSah7DiagnosisCode() {
        assertThat(EmrKeys.diagnosisSpecialty("gm")).isEqualTo("gp");
        assertThat(EmrKeys.diagnosisSpecialty("og")).isEqualTo("gynae");
        assertThat(EmrKeys.diagnosisSpecialty("derm")).isEqualTo("derma");
        assertThat(EmrKeys.diagnosisSpecialty("ayush")).isEqualTo("ayur");
        assertThat(EmrKeys.diagnosisSpecialty("dental")).isEqualTo("dental");
        assertThat(EmrKeys.diagnosisSpecialty("paeds")).isEqualTo("paeds");
        assertThat(EmrKeys.diagnosisSpecialty("physio")).isEqualTo("physio");
        assertThat(EmrKeys.diagnosisSpecialty("cardio")).isNull();
        assertThat(EmrKeys.diagnosisSpecialty(null)).isNull();
    }

    @Test
    void theClinicSpecialtyMapsToTheDefaultStarter() {
        assertThat(EmrKeys.clinicDefaultStarter("gp")).isEqualTo("gm");
        assertThat(EmrKeys.clinicDefaultStarter("dental")).isEqualTo("dental");
        assertThat(EmrKeys.clinicDefaultStarter("paeds")).isEqualTo("paeds");
        assertThat(EmrKeys.clinicDefaultStarter("homeo")).isEqualTo("gm");
        assertThat(EmrKeys.clinicDefaultStarter(null)).isEqualTo("gm");
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
        assertThat(EmrKeys.isWellFormedDepartmentKey("gp")).isTrue();
        assertThat(EmrKeys.isWellFormedDepartmentKey("ayur")).isTrue();
        assertThat(EmrKeys.isWellFormedDepartmentKey("G")).isFalse();
        assertThat(EmrKeys.isWellFormedDepartmentKey("1gp")).isFalse();
        assertThat(EmrKeys.isWellFormedDepartmentKey("a".repeat(41))).isFalse();
        assertThat(EmrKeys.isWellFormedDepartmentKey("d_k7m2q9x4ab")).isTrue();
        assertThat(EmrKeys.isWellFormedDepartmentKey("gp'--")).isFalse();
        assertThat(EmrKeys.isWellFormedSectionKey("chief_complaints")).isTrue();
        assertThat(EmrKeys.isWellFormedSectionKey("Chief")).isFalse();
        assertThat(EmrKeys.isWellFormedSectionKey("a".repeat(41))).isFalse();
    }
}
