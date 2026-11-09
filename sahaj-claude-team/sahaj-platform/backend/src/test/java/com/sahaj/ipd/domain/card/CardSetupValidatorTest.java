package com.sahaj.ipd.domain.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import com.sahaj.ipd.domain.card.CardSetupValidator.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * SAH-37 (ADR-015 D3): rules V1 to V14 of the validation table, each with its message and JSON path, and the rule that
 * the Sahaj standard setup and the three templates pass them.
 */
class CardSetupValidatorTest {

    /** The IPD text rule for the tests: refuses a control character, a zero-width space and a right-to-left mark. */
    static final TextRules TEXT = new TextRules() {
        @Override
        public boolean hasControlOrBidi(String text) {
            return text.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0x200B || cp == 0x202E);
        }

        @Override
        public String strip(String text) {
            return text.strip();
        }
    };

    static List<Problem> check(CardSetup setup) {
        return CardSetupValidator.validate(setup, TEXT);
    }

    /** Applies {@code change} to the section with this key. */
    static CardSetup section(CardSetup setup, String key, UnaryOperator<Section> change) {
        List<Section> sections = new ArrayList<>();
        for (Section section : setup.sections()) {
            sections.add(section.key().equals(key) ? change.apply(section) : section);
        }
        return new CardSetup(setup.layout(), setup.cardsPerRow(), sections, setup.actions());
    }

    /** Applies {@code change} to the placement of the field with this key. */
    static CardSetup field(CardSetup setup, String key, UnaryOperator<FieldPlacement> change) {
        List<Section> sections = new ArrayList<>();
        for (Section section : setup.sections()) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement field : section.fields()) {
                fields.add(field.key().equals(key) ? change.apply(field) : field);
            }
            sections.add(new Section(section.key(), section.on(), fields));
        }
        return new CardSetup(setup.layout(), setup.cardsPerRow(), sections, setup.actions());
    }

    /** Moves a field to the end of another section. */
    static CardSetup move(CardSetup setup, String fieldKey, String toSection) {
        FieldPlacement moved = null;
        for (Section section : setup.sections()) {
            for (FieldPlacement field : section.fields()) {
                if (field.key().equals(fieldKey)) {
                    moved = field;
                }
            }
        }
        final FieldPlacement placement = moved;
        List<Section> sections = new ArrayList<>();
        for (Section section : setup.sections()) {
            List<FieldPlacement> fields = new ArrayList<>(section.fields());
            fields.removeIf(f -> f.key().equals(fieldKey));
            if (section.key().equals(toSection)) {
                fields.add(placement);
            }
            sections.add(new Section(section.key(), section.on(), fields));
        }
        return new CardSetup(setup.layout(), setup.cardsPerRow(), sections, setup.actions());
    }

    static CardSetup standard() {
        return CardSetupDefaults.standard();
    }

    private static void assertOne(List<Problem> problems, int rule, String path, String message) {
        assertThat(problems).containsExactly(new Problem(rule, path, message));
    }

    @Test
    void theStandardSetupAndEveryTemplateAreValid() {
        assertThat(check(standard())).isEmpty();
        for (CardSetupDefaults.Template template : CardSetupDefaults.templates()) {
            CardSetup applied = new CardSetup(template.layout(), standard().cardsPerRow(), template.sections(),
                    template.actions());
            assertThat(check(applied)).as(template.key()).isEmpty();
        }
    }

    @Test
    void v1AnUnknownLayoutIsRefused() {
        CardSetup setup = new CardSetup("wide", 3, standard().sections(), standard().actions());
        assertOne(check(setup), 1, "setup.layout", "Choose Compact, Standard or Expanded.");
        assertOne(check(new CardSetup(null, 3, standard().sections(), standard().actions())), 1, "setup.layout",
                "Choose Compact, Standard or Expanded.");
    }

    @Test
    void v2OnlyTwoThreeOrFourCardsPerRow() {
        for (int bad : new int[] {0, 1, 5, -3}) {
            assertOne(check(new CardSetup("standard", bad, standard().sections(), standard().actions())), 2,
                    "setup.cardsPerRow", "Choose 2, 3 or 4 cards per row.");
        }
        for (int good : new int[] {2, 3, 4}) {
            assertThat(check(new CardSetup("compact", good, standard().sections(), standard().actions()))).isEmpty();
        }
    }

    @Test
    void v3AnUnknownADuplicateAndAMissingSectionAreRefused() {
        List<Section> unknown = new ArrayList<>(standard().sections());
        unknown.set(2, new Section("wing", true, List.of()));
        List<Problem> problems = check(new CardSetup("standard", 3, unknown, standard().actions()));
        assertThat(problems).contains(new Problem(3, "setup.sections[2].key", "Unknown section."),
                new Problem(3, "setup.sections", "The Admission and bed section is missing."));

        List<Section> twice = new ArrayList<>(standard().sections());
        twice.add(standard().sections().get(3));
        assertThat(check(new CardSetup("standard", 3, twice, standard().actions())))
                .contains(new Problem(3, "setup.sections[7].key", "Each section can appear only once."));

        List<Section> shorter = new ArrayList<>(standard().sections());
        shorter.remove(6);
        assertThat(check(new CardSetup("standard", 3, shorter, standard().actions())))
                .contains(new Problem(3, "setup.sections", "The Billing section is missing."));
    }

    @Test
    void v4TheCardHeaderIsFirstAndOn() {
        List<Section> swapped = new ArrayList<>(standard().sections());
        swapped.add(0, swapped.remove(1));
        assertThat(check(new CardSetup("standard", 3, swapped, standard().actions())))
                .contains(new Problem(4, "setup.sections[1].key", "The Card header section is always first and always on."));

        CardSetup off = section(standard(), "header", s -> new Section(s.key(), false, s.fields()));
        assertThat(check(off)).contains(
                new Problem(4, "setup.sections[0].on", "The Card header section is always first and always on."));
    }

    @Test
    void v5TheSafetySectionsStayOn() {
        for (String key : new String[] {"status", "vitals", "clinical"}) {
            CardSetup off = section(standard(), key, s -> new Section(s.key(), false, s.fields()));
            String name = CardCatalogue.sectionName(key);
            assertThat(check(off)).as(key).contains(new Problem(5,
                    "setup.sections[" + CardCatalogue.sectionKeys().indexOf(key) + "].on",
                    "The " + name + " section is a safety section and stays on."));
        }
        for (String key : new String[] {"stay", "care", "billing"}) {
            CardSetup off = section(standard(), key, s -> new Section(s.key(), false, s.fields()));
            assertThat(check(off)).as(key).isEmpty();
        }
    }

    @Test
    void v6AnUnknownADuplicateAndAMissingFieldAreRefused() {
        CardSetup unknown = section(standard(), "care",
                s -> new Section(s.key(), s.on(), List.of(new FieldPlacement("nurse", true, ""))));
        assertThat(check(unknown)).contains(new Problem(6, "setup.sections[3].fields[0].key", "Unknown field."),
                new Problem(6, "setup.sections", "Doctor is missing from the card setup."));

        CardSetup twice = section(standard(), "care", s -> new Section(s.key(), s.on(),
                List.of(new FieldPlacement("doctor", true, ""), new FieldPlacement("ward", true, ""))));
        assertThat(check(twice)).contains(
                new Problem(6, "setup.sections[3].fields[1].key", "Each field can appear only once."));
    }

    @Test
    void v7PatientNameStaysFirstInTheHeaderOnAndWithoutALabel() {
        String message = "Patient name stays first in the Card header.";
        assertThat(check(move(standard(), "name", "stay"))).contains(
                new Problem(7, "setup.sections[2].fields[5].key", message));
        CardSetup notFirst = section(standard(), "header", s -> {
            List<FieldPlacement> fields = new ArrayList<>(s.fields());
            fields.add(0, fields.remove(1));
            return new Section(s.key(), s.on(), fields);
        });
        assertThat(check(notFirst)).contains(new Problem(7, "setup.sections[0].fields[1].key", message));
        assertOne(check(field(standard(), "name", f -> new FieldPlacement(f.key(), false, ""))), 7,
                "setup.sections[0].fields[0].on", message);
        assertOne(check(field(standard(), "name", f -> new FieldPlacement(f.key(), true, "Who"))), 7,
                "setup.sections[0].fields[0].label", message);
    }

    @Test
    void v8EachSafetyFieldCannotBeHidden() {
        String[][] cases = {{"uhid", "UHID", "0", "1"}, {"alerts", "Alerts", "1", "0"},
            {"ews", "Early warning", "4", "5"}, {"allergies", "Allergies", "5", "1"}};
        for (String[] c : cases) {
            List<Problem> problems = check(field(standard(), c[0], f -> new FieldPlacement(f.key(), false, "")));
            assertThat(problems).as(c[0]).extracting(Problem::message)
                    .containsExactly(c[1] + " is a safety field and cannot be hidden.");
            assertThat(problems.get(0).rule()).isEqualTo(8);
        }
    }

    @Test
    void v9ASafetyFieldCannotSitInASectionThatIsSwitchedOff() {
        CardSetup moved = section(move(standard(), "allergies", "billing"), "billing",
                s -> new Section(s.key(), false, s.fields()));
        assertThat(check(moved)).contains(new Problem(9, "setup.sections[6].fields[3].key",
                "Allergies is a safety field. Move it to a section that is switched on."));
        CardSetup toStay = section(move(standard(), "uhid", "stay"), "stay",
                s -> new Section(s.key(), false, s.fields()));
        assertThat(check(toStay)).extracting(Problem::message)
                .contains("UHID is a safety field. Move it to a section that is switched on.");
        // moved into a section that is on: allowed
        assertThat(check(move(standard(), "allergies", "care"))).isEmpty();
    }

    @Test
    void v10TheLabelOfASafetyFieldCannotBeChanged() {
        for (String key : new String[] {"uhid", "alerts", "ews", "allergies"}) {
            List<Problem> problems = check(field(standard(), key, f -> new FieldPlacement(f.key(), true, "Pets")));
            assertThat(problems).as(key).hasSize(1);
            assertThat(problems.get(0).rule()).isEqualTo(10);
            assertThat(problems.get(0).message())
                    .isEqualTo("The label of " + CardCatalogue.fieldName(key) + " cannot be changed.");
        }
        // empty and the standard name itself are accepted
        assertThat(check(field(standard(), "allergies", f -> new FieldPlacement(f.key(), true, "Allergies")))).isEmpty();
        assertThat(check(field(standard(), "allergies", f -> new FieldPlacement(f.key(), true, "  ")))).isEmpty();
    }

    @Test
    void v11AFortyCharacterLabelPassesAndAFortyOneCharacterLabelIsRefused() {
        String forty = "a".repeat(40);
        assertThat(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, forty)))).isEmpty();
        assertOne(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, forty + "b"))), 11,
                "setup.sections[2].fields[2].label", "Keep the label to 40 characters.");
        // the limit counts code points, not UTF-16 units: forty emoji pass, forty-one do not
        String emoji = "😀".repeat(40);
        assertThat(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, emoji)))).isEmpty();
        assertThat(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, emoji + "😀"))))
                .extracting(Problem::message).containsExactly("Keep the label to 40 characters.");
        // spaces around the text do not count
        assertThat(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, "  " + forty + "  "))))
                .isEmpty();
    }

    @Test
    void v12AControlOrTextDirectionCharacterInALabelIsRefused() {
        for (String bad : new String[] {"a\u0000b", "a\nb", "a​b", "x‮y"}) {
            assertOne(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, bad))), 12,
                    "setup.sections[2].fields[2].label", "Label cannot contain control or text-direction characters");
        }
    }

    @Test
    void v13AnUnknownADuplicateAndAMissingQuickActionAreRefused() {
        List<ActionPlacement> unknown = new ArrayList<>(standard().actions());
        unknown.set(4, new ActionPlacement("print", true));
        assertThat(check(new CardSetup("standard", 3, standard().sections(), unknown))).contains(
                new Problem(13, "setup.actions[4].key", "Unknown quick action."),
                new Problem(13, "setup.actions", "The Deposit quick action is missing."));
        List<ActionPlacement> twice = new ArrayList<>(standard().actions());
        twice.set(4, new ActionPlacement("emr", false));
        assertThat(check(new CardSetup("standard", 3, standard().sections(), twice))).contains(
                new Problem(13, "setup.actions[4].key", "Each quick action can appear only once."),
                new Problem(13, "setup.actions", "The Deposit quick action is missing."));
    }

    @Test
    void v14ALabelOverFourHundredUnitsIsRefusedBeforeItIsNormalised() {
        String raw = "a".repeat(401);
        assertOne(check(field(standard(), "ward", f -> new FieldPlacement(f.key(), true, raw))), 14,
                "setup.sections[2].fields[2].label", "Keep the label to 40 characters.");
    }

    @Test
    void allErrorsComeTogetherInTheOrderOfTheRules() {
        CardSetup bad = field(new CardSetup("wide", 9, standard().sections(), standard().actions()), "uhid",
                f -> new FieldPlacement(f.key(), false, "x"));
        List<Problem> problems = check(bad);
        assertThat(problems).extracting(Problem::rule).isSorted().contains(1, 2, 8, 10);
        assertThat(problems.get(0).message()).isEqualTo("Choose Compact, Standard or Expanded.");
    }

    @Test
    void nullListsAndNullKeysAreErrorsNotCrashes() {
        List<Problem> problems = check(new CardSetup(null, 0, null, null));
        assertThat(problems).extracting(Problem::rule).contains(1, 2, 3, 6, 13);
        List<Section> withNull = new ArrayList<>(standard().sections());
        withNull.set(1, new Section(null, true, null));
        assertThat(check(new CardSetup("standard", 3, withNull, standard().actions()))).isNotEmpty();
    }
}
