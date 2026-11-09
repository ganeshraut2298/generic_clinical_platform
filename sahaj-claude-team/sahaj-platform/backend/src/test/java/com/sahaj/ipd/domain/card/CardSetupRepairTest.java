package com.sahaj.ipd.domain.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-37 (ADR-015 D3): repair of a stored setup on read; a repaired setup always passes the validator. */
class CardSetupRepairTest {

    private static CardSetup standard() {
        return CardSetupDefaults.standard();
    }

    private static List<String> keys(Section section) {
        return section.fields().stream().map(FieldPlacement::key).toList();
    }

    private static Section section(CardSetup setup, String key) {
        return setup.sections().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static FieldPlacement field(CardSetup setup, String key) {
        return setup.sections().stream().flatMap(s -> s.fields().stream()).filter(f -> f.key().equals(key)).findFirst()
                .orElseThrow();
    }

    private static void assertSafe(CardSetup repaired) {
        assertThat(CardSetupValidator.validate(repaired, CardSetupValidatorTest.TEXT)).isEmpty();
    }

    @Test
    void theStandardSetupIsAFixedPointOfRepair() {
        assertThat(CardSetupRepair.repair(standard())).isEqualTo(standard());
    }

    @Test
    void aStoredSetupThatLacksALockedFieldGetsItBackOn() {
        for (String lacking : new String[] {"allergies", "ews", "uhid", "alerts", "name"}) {
            List<Section> sections = new ArrayList<>();
            for (Section s : standard().sections()) {
                List<FieldPlacement> fields = new ArrayList<>(s.fields());
                fields.removeIf(f -> f.key().equals(lacking));
                sections.add(new Section(s.key(), s.on(), fields));
            }
            CardSetup repaired = CardSetupRepair.repair(
                    new CardSetup("compact", 4, sections, standard().actions()));

            assertThat(field(repaired, lacking).on()).as(lacking).isTrue();
            assertThat(repaired.layout()).isEqualTo("compact");
            assertSafe(repaired);
        }
    }

    @Test
    void aNewCatalogueFieldArrivesOffInItsStandardSectionAndALockedOneArrivesOn() {
        List<Section> sections = new ArrayList<>();
        for (Section s : standard().sections()) {
            List<FieldPlacement> fields = new ArrayList<>(s.fields());
            fields.removeIf(f -> f.key().equals("diagnosis") || f.key().equals("allergies"));
            sections.add(new Section(s.key(), s.on(), fields));
        }
        CardSetup repaired = CardSetupRepair.repair(new CardSetup("standard", 3, sections, standard().actions()));

        assertThat(keys(section(repaired, "clinical"))).containsExactlyInAnyOrder("diagnosis", "allergies");
        assertThat(field(repaired, "diagnosis").on()).isFalse();
        assertThat(field(repaired, "allergies").on()).isTrue();
    }

    @Test
    void unknownKeysAreDroppedAndRepeatedKeysKeptOnce() {
        List<Section> sections = new ArrayList<>(standard().sections());
        sections.add(new Section("wing", true, List.of(new FieldPlacement("ward", true, "Again"))));
        sections.add(new Section("stay", false, List.of()));
        sections.set(3, new Section("care", true, List.of(new FieldPlacement("doctor", true, ""),
                new FieldPlacement("doctor", false, "x"), new FieldPlacement("nurse", true, ""))));
        List<ActionPlacement> actions = new ArrayList<>(standard().actions());
        actions.add(new ActionPlacement("print", true));
        actions.add(new ActionPlacement("emr", false));

        CardSetup repaired = CardSetupRepair.repair(new CardSetup("standard", 3, sections, actions));

        assertThat(repaired.sections()).hasSize(7);
        assertThat(keys(section(repaired, "care"))).containsExactly("doctor");
        assertThat(field(repaired, "doctor").on()).isTrue();
        assertThat(field(repaired, "ward").label()).isEmpty();
        assertThat(repaired.actions()).hasSize(5);
        assertThat(repaired.actions().stream().filter(a -> a.key().equals("emr")).findFirst().get().on()).isTrue();
        assertSafe(repaired);
    }

    @Test
    void lockedSectionsAreForcedOnAndTheHeaderGoesFirstWithTheNameFirstInIt() {
        List<Section> sections = new ArrayList<>();
        for (Section s : standard().sections()) {
            boolean lockedOrSafety = List.of("header", "status", "vitals", "clinical").contains(s.key());
            sections.add(new Section(s.key(), lockedOrSafety ? false : s.on(), s.fields()));
        }
        sections.add(sections.remove(0));
        CardSetup repaired = CardSetupRepair.repair(new CardSetup("standard", 3, sections, standard().actions()));

        assertThat(repaired.sections().get(0).key()).isEqualTo("header");
        assertThat(repaired.sections()).filteredOn(s -> List.of("header", "status", "vitals", "clinical")
                .contains(s.key())).allMatch(Section::on);
        assertSafe(repaired);

        List<Section> nameMoved = new ArrayList<>();
        for (Section s : standard().sections()) {
            List<FieldPlacement> fields = new ArrayList<>(s.fields());
            if (s.key().equals("header")) {
                fields.removeIf(f -> f.key().equals("name"));
                fields.add(new FieldPlacement("name", false, "Who"));
            }
            nameMoved.add(new Section(s.key(), s.on(), fields));
        }
        CardSetup fixed = CardSetupRepair.repair(new CardSetup("standard", 3, nameMoved, standard().actions()));
        assertThat(keys(section(fixed, "header")).get(0)).isEqualTo("name");
        assertThat(field(fixed, "name")).isEqualTo(new FieldPlacement("name", true, ""));
    }

    @Test
    void aLockedFieldInASwitchedOffSectionGoesBackToItsStandardSectionAndLabelsAreCleared() {
        CardSetup moved = CardSetupValidatorTest.move(standard(), "allergies", "billing");
        moved = CardSetupValidatorTest.field(moved, "allergies", f -> new FieldPlacement(f.key(), false, "Pets"));
        assertThat(section(moved, "billing").on()).isFalse();

        CardSetup repaired = CardSetupRepair.repair(moved);

        assertThat(keys(section(repaired, "clinical"))).contains("allergies");
        assertThat(keys(section(repaired, "billing"))).doesNotContain("allergies");
        assertThat(field(repaired, "allergies")).isEqualTo(new FieldPlacement("allergies", true, ""));
        assertSafe(repaired);
    }

    @Test
    void aLockedFieldInASectionThatIsOnStaysWhereTheOwnerPutIt() {
        CardSetup moved = CardSetupValidatorTest.move(standard(), "allergies", "care");
        CardSetup repaired = CardSetupRepair.repair(moved);
        assertThat(keys(section(repaired, "care"))).contains("allergies");
    }

    @Test
    void badValuesFallBackToTheStandardOnesAndLongOrControlLabelsAreCut() {
        String forty = "b".repeat(40);
        CardSetup stored = CardSetupValidatorTest.field(
                new CardSetup("wide", 9, standard().sections(), standard().actions()), "ward",
                f -> new FieldPlacement(f.key(), true, "  " + "a".repeat(60) + "\u0007 "));
        CardSetup repaired = CardSetupRepair.repair(stored);

        assertThat(repaired.layout()).isEqualTo("standard");
        assertThat(repaired.cardsPerRow()).isEqualTo(3);
        assertThat(field(repaired, "ward").label()).isEqualTo("a".repeat(40));
        CardSetup keep = CardSetupRepair.repair(CardSetupValidatorTest.field(standard(), "ward",
                f -> new FieldPlacement(f.key(), true, forty)));
        assertThat(field(keep, "ward").label()).isEqualTo(forty);
    }

    @Test
    void emptyOrNullPartsGiveAFullValidSetup() {
        assertSafe(CardSetupRepair.repair(new CardSetup(null, 0, null, null)));
        assertSafe(CardSetupRepair.repair(new CardSetup("compact", 2, List.of(new Section("header", true, null)), List.of())));
    }
}
