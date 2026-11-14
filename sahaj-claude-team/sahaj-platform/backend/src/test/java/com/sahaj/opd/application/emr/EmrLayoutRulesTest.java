package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.opd.application.emr.EmrInputs.SectionSettingsInput;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmrLayoutRulesTest {

    private static final List<LayoutSection> BASE = List.of(
            EmrTestData.section("vitals", true, false),
            EmrTestData.section("allergy", "COMMON", "ASSESSMENT", true, true, true),
            EmrTestData.section("rx", "COMMON", "TREATMENT", true, false, false));

    private static SectionSettingsInput in(String key, String part, boolean shown, boolean required) {
        return new SectionSettingsInput(key, part, shown, required, false, false, false, List.of("DOCTOR"));
    }

    private static List<SectionSettingsInput> valid() {
        return new ArrayList<>(List.of(in("vitals", "ASSESSMENT", true, false), in("allergy", "ASSESSMENT", true, true),
                in("rx", "TREATMENT", true, false)));
    }

    @Test
    void aWholeValidBodyIsAcceptedInBodyOrderWithRolesInFixedOrder() {
        List<SectionSettingsInput> body = valid();
        body.set(0, new SectionSettingsInput("vitals", "TREATMENT", true, false, true, true, true,
                List.of("RECEPTION", "DOCTOR")));
        body.add(0, body.remove(2));

        List<SectionSettings> settings = EmrLayoutRules.validate(body, BASE);

        assertThat(settings).extracting(SectionSettings::key).containsExactly("rx", "vitals", "allergy");
        assertThat(settings.get(1).whoCanFill()).containsExactly("DOCTOR", "RECEPTION");
        assertThat(settings.get(1).print()).isTrue();
    }

    @Test
    void anUnknownSectionKeyIs404ButAMissingOrRepeatedKeyIsASetMismatch() {
        List<SectionSettingsInput> unknown = valid();
        unknown.set(2, in("nope", "TREATMENT", true, false));
        assertThatThrownBy(() -> EmrLayoutRules.validate(unknown, BASE)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("section-not-found"));

        List<SectionSettingsInput> missing = valid();
        missing.remove(2);
        assertThatThrownBy(() -> EmrLayoutRules.validate(missing, BASE)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("section-set-mismatch"));

        List<SectionSettingsInput> repeated = valid();
        repeated.set(2, in("vitals", "TREATMENT", true, false));
        assertThatThrownBy(() -> EmrLayoutRules.validate(repeated, BASE)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("section-set-mismatch"));
    }

    @Test
    void aHiddenSectionCannotBeRequiredAndALockedSectionCannotBeHidden() {
        List<SectionSettingsInput> hiddenRequired = valid();
        hiddenRequired.set(0, in("vitals", "ASSESSMENT", false, true));
        assertThatThrownBy(() -> EmrLayoutRules.validate(hiddenRequired, BASE)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("hidden-section-required");
                    assertThat(ex.getMessage()).isEqualTo("A hidden section cannot be required.");
                });

        List<SectionSettingsInput> locked = valid();
        locked.set(1, in("allergy", "ASSESSMENT", false, false));
        assertThatThrownBy(() -> EmrLayoutRules.validate(locked, BASE)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("section-not-hideable");
                    assertThat(ex.getMessage()).isEqualTo("ALLERGY cannot be hidden.");
                });
    }

    @Test
    void aBodyThatIsNotWellFormedListsEveryFieldError() {
        List<SectionSettingsInput> body = valid();
        body.set(0, new SectionSettingsInput("vitals", "MIDDLE", null, false, false, false, false, List.of("DOCTOR")));
        body.set(1, new SectionSettingsInput("allergy", "ASSESSMENT", true, true, false, false, false,
                List.of("DOCTOR", "DOCTOR")));

        assertThatThrownBy(() -> EmrLayoutRules.validate(body, BASE)).isInstanceOfSatisfying(
                InvalidFieldsException.class, ex -> assertThat(ex.errors()).extracting(e -> e.field())
                        .containsExactly("sections[0].part", "sections[0].shown", "sections[1].whoCanFill"));
        assertThatThrownBy(() -> EmrLayoutRules.validate(null, BASE)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> EmrLayoutRules.validate(List.of(), BASE)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void changesListEveryPropertyThatDiffersAndMovedListsReorderedKeys() {
        List<SectionSettings> before = EmrLayoutRules.settings(BASE);
        List<SectionSettings> after = new ArrayList<>(before);
        SectionSettings vitals = after.get(0);
        after.set(0, new SectionSettings("vitals", "TREATMENT", true, true, vitals.print(), vitals.copy(),
                vitals.collapsed(), vitals.whoCanFill()));
        after.add(0, after.remove(2));

        assertThat(EmrLayoutRules.changes(before, after)).extracting(c -> c.get("sectionKey") + "." + c.get("property"))
                .containsExactlyInAnyOrder("vitals.part", "vitals.required");
        assertThat(EmrLayoutRules.moved(before, after)).containsExactlyInAnyOrder("rx", "vitals", "allergy");
        assertThat(EmrLayoutRules.changes(before, before)).isEmpty();
        assertThat(EmrLayoutRules.moved(before, before)).isEmpty();
    }

    @Test
    void countsAreOfShownSectionsOnlyAndClearPrintClearsEveryFlag() {
        List<SectionSettings> settings = List.of(
                new SectionSettings("a", "ASSESSMENT", true, true, true, false, false, List.of("DOCTOR")),
                new SectionSettings("b", "ASSESSMENT", false, false, true, false, false, List.of("DOCTOR")),
                new SectionSettings("c", "TREATMENT", true, false, true, false, false, List.of("DOCTOR")));

        assertThat(EmrLayoutRules.sectionsOn(settings)).isEqualTo(2);
        assertThat(EmrLayoutRules.requiredOn(settings)).isEqualTo(1);
        assertThat(EmrLayoutRules.clearPrint(settings)).allMatch(s -> !s.print());
    }

    @Test
    void applyKeepsDefinitionsAndNumbersThePositionsFromOne() {
        List<SectionSettings> reversed = new ArrayList<>(EmrLayoutRules.settings(BASE));
        java.util.Collections.reverse(reversed);

        List<LayoutSection> applied = EmrLayoutRules.apply(reversed, BASE);

        assertThat(applied).extracting(LayoutSection::key).containsExactly("rx", "allergy", "vitals");
        assertThat(applied).extracting(LayoutSection::position).containsExactly(1, 2, 3);
        assertThat(applied.get(1).hideLocked()).isTrue();
    }
}
