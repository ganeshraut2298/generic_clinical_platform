package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingSettingsRules.ProfileValues;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-76-6 the ranges, the profile name and the key made from it (ADR-027 D8). Pure Java. */
class NursingSettingsRulesTest {

    private static SettingsInput settings(Object verification, Object before, Object after, Object stat, Object unack,
                                          Object unackStat, Object undo) {
        return new SettingsInput(verification, before, after, stat, unack, unackStat, undo);
    }

    private static ProfileInput profile(String name, Object verification, Object vitals, Object before, Object after) {
        return new ProfileInput(name, verification, vitals, before, after);
    }

    // ---- clinic settings ----

    @Test
    void validSettingsAreAcceptedAtTheEdgesOfEveryRange() {
        NursingSettings low = NursingSettingsRules.settings(settings(false, 1, 1, 1, 1, 1, 1));
        NursingSettings high = NursingSettingsRules.settings(settings(true, 180, 180, 120, 240, 60, 10));

        assertThat(low).isEqualTo(new NursingSettings(false, 1, 1, 1, 1, 1, 1));
        assertThat(high).isEqualTo(new NursingSettings(true, 180, 180, 120, 240, 60, 10));
    }

    @Test
    void theBuiltDefaultsAreInsideTheRanges() {
        NursingSettings d = NursingSettings.DEFAULTS;

        assertThat(NursingSettingsRules.settings(settings(d.medVerificationRequired(), d.doseWindowBeforeMin(),
                d.doseWindowAfterMin(), d.statWindowMin(), d.unackFlagMin(), d.unackFlagStatMin(), d.undoSeconds())))
                .isEqualTo(d);
    }

    @Test
    void everyFailingFieldIsReportedTogetherWithTheContractText() {
        assertThatThrownBy(() -> NursingSettingsRules.settings(settings(null, 0, 181, 121, 241, 61, 11)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).containsExactly(
                            new FieldMessage("medVerificationRequired", "Choose On or Off."),
                            new FieldMessage("doseWindowBeforeMinutes", "Enter a number from 1 to 180."),
                            new FieldMessage("doseWindowAfterMinutes", "Enter a number from 1 to 180."),
                            new FieldMessage("statWindowMinutes", "Enter a number from 1 to 120."),
                            new FieldMessage("unackFlagMinutes", "Enter a number from 1 to 240."),
                            new FieldMessage("unackFlagStatMinutes", "Enter a number from 1 to 60."),
                            new FieldMessage("undoSeconds", "Enter a number from 1 to 10."));
                    assertThat(e.firstMessageAsDetail()).isTrue();
                });
    }

    @Test
    void theUndoWindowNeverGoesAboveTenSeconds() {
        assertThatThrownBy(() -> NursingSettingsRules.settings(settings(true, 60, 60, 15, 30, 5, 11)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aTextOrAFractionIsNotANumber() {
        assertThatThrownBy(() -> NursingSettingsRules.settings(settings(true, "60", 60.5, 15, 30, 5, 10)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(FieldMessage::field)
                        .containsExactly("doseWindowBeforeMinutes", "doseWindowAfterMinutes"));
        assertThat(NursingSettingsRules.settings(settings(true, 60.0, 60, 15, 30, 5, 10)).doseWindowBeforeMin())
                .isEqualTo(60);
    }

    @Test
    void aMissingBodyReportsEveryField() {
        assertThatThrownBy(() -> NursingSettingsRules.settings(null)).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).hasSize(7));
    }

    // ---- profiles ----

    @Test
    void aValidProfileKeepsTheNameTrimmedAndTheOptionalWindows() {
        ProfileValues values = NursingSettingsRules.profile(profile("  Step-down  ", true, 120, null, 45));

        assertThat(values.name()).isEqualTo("Step-down");
        assertThat(values.medVerificationRequired()).isTrue();
        assertThat(values.vitalsEveryMinutes()).isEqualTo(120);
        assertThat(values.doseWindowBeforeMin()).isNull();
        assertThat(values.doseWindowAfterMin()).isEqualTo(45);
    }

    @Test
    void theVitalsIntervalIsFifteenToFourteenFortyMinutes() {
        assertThat(NursingSettingsRules.profile(profile("A1", false, 15, null, null)).vitalsEveryMinutes()).isEqualTo(15);
        assertThat(NursingSettingsRules.profile(profile("A1", false, 1440, null, null)).vitalsEveryMinutes())
                .isEqualTo(1440);
        assertThatThrownBy(() -> NursingSettingsRules.profile(profile("A1", false, 14, null, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("vitalsEveryMinutes", "Enter a number from 15 to 1440.")));
        assertThatThrownBy(() -> NursingSettingsRules.profile(profile("A1", false, 1441, null, null)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aProfileNameIsOneToSixtyCharactersWithNoControlCharacter() {
        assertThat(NursingSettingsRules.name("ICU")).isEqualTo("ICU");
        assertThat(NursingSettingsRules.name("A".repeat(60))).hasSize(60);
        assertThat(NursingSettingsRules.name("A".repeat(61))).isNull();
        assertThat(NursingSettingsRules.name("   ")).isNull();
        assertThat(NursingSettingsRules.name(null)).isNull();
        assertThat(NursingSettingsRules.name("ICU\u0007")).isNull();
        assertThat(NursingSettingsRules.name("---")).as("a name needs a letter or a digit").isNull();
        assertThatThrownBy(() -> NursingSettingsRules.profile(profile("", true, 60, null, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldMessage("name", "Enter a profile name.")));
    }

    @Test
    void theWindowOverridesAreOneToOneEighty() {
        assertThatThrownBy(() -> NursingSettingsRules.profile(profile("A1", true, 60, 0, 181)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(FieldMessage::field)
                        .containsExactly("doseWindowBeforeMinutes", "doseWindowAfterMinutes"));
    }

    // ---- the key ----

    @Test
    void theKeyIsLowerCaseLettersDigitsAndHyphens() {
        assertThat(NursingSettingsRules.key("General ward", k -> false)).isEqualTo("general-ward");
        assertThat(NursingSettingsRules.key("  ICU / Level 2 ", k -> false)).isEqualTo("icu-level-2");
        assertThat(NursingSettingsRules.key("Maternité", k -> false)).isEqualTo("maternite");
    }

    @Test
    void aTakenKeyGetsANumber() {
        Set<String> taken = Set.of("icu", "icu-2");

        assertThat(NursingSettingsRules.key("ICU", taken::contains)).isEqualTo("icu-3");
    }

    @Test
    void aNameWithNoUsableLetterGivesProfileAndTheKeyStaysInTheDatabaseShape() {
        assertThat(NursingSettingsRules.key("१२३", k -> false)).isEqualTo("profile");
        String longKey = NursingSettingsRules.key("a".repeat(60), k -> false);
        assertThat(longKey).hasSize(40);
        assertThat(NursingSettingsRules.validKey(longKey)).isTrue();
        String numbered = NursingSettingsRules.key("a".repeat(60), longKey::equals);
        assertThat(numbered).hasSize(40).endsWith("-2");
        assertThat(NursingSettingsRules.validKey(numbered)).isTrue();
    }

    @Test
    void aKeyInTheUrlMustHaveTheDatabaseShape() {
        assertThat(NursingSettingsRules.validKey("icu")).isTrue();
        assertThat(NursingSettingsRules.validKey("general-ward")).isTrue();
        assertThat(NursingSettingsRules.validKey("a")).isFalse();
        assertThat(NursingSettingsRules.validKey("ICU")).isFalse();
        assertThat(NursingSettingsRules.validKey("a_b")).isFalse();
        assertThat(NursingSettingsRules.validKey(null)).isFalse();
    }
}
