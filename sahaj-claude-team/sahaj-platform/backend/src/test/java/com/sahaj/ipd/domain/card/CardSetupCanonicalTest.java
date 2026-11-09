package com.sahaj.ipd.domain.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-37 (ADR-015 D3): canonical form, equality and the four changed parts. */
class CardSetupCanonicalTest {

    private static CardSetup standard() {
        return CardSetupDefaults.standard();
    }

    private static CardSetup withActions(CardSetup setup, List<ActionPlacement> actions) {
        return new CardSetup(setup.layout(), setup.cardsPerRow(), setup.sections(), actions);
    }

    @Test
    void labelsAreTrimmedNormalisedAndTheStandardNameBecomesEmpty() {
        // "e" followed by a combining acute accent is composed to one code point
        CardSetup typed = CardSetupValidatorTest.field(standard(), "ward",
                f -> new FieldPlacement(f.key(), true, "  Wingé  "));
        typed = CardSetupValidatorTest.field(typed, "room", f -> new FieldPlacement(f.key(), true, "Room"));
        typed = CardSetupValidatorTest.field(typed, "bed", f -> new FieldPlacement(f.key(), true, null));

        CardSetup canonical = CardSetupCanonical.canonical(typed, CardSetupValidatorTest.TEXT);

        assertThat(canonical.sections().get(2).fields().get(2).label()).isEqualTo("Wingé");
        assertThat(canonical.sections().get(2).fields().get(3).label()).as("the standard name").isEmpty();
        assertThat(canonical.sections().get(0).fields().get(3).label()).as("null").isEmpty();
    }

    @Test
    void aSwitchChangedAndChangedBackIsEqualToTheOriginal() {
        CardSetup flipped = CardSetupValidatorTest.field(standard(), "room",
                f -> new FieldPlacement(f.key(), false, f.label()));
        CardSetup back = CardSetupValidatorTest.field(flipped, "room",
                f -> new FieldPlacement(f.key(), true, f.label()));

        assertThat(CardSetupCanonical.equal(CardSetupCanonical.canonical(back, CardSetupValidatorTest.TEXT), standard()))
                .isTrue();
        assertThat(CardSetupCanonical.changedParts(standard(), back)).isEmpty();
        assertThat(CardSetupCanonical.equal(flipped, standard())).isFalse();
    }

    @Test
    void layoutAndCardsPerRowAreOnePart() {
        assertThat(CardSetupCanonical.changedParts(standard(),
                new CardSetup("compact", 3, standard().sections(), standard().actions()))).containsExactly("layout");
        assertThat(CardSetupCanonical.changedParts(standard(),
                new CardSetup("standard", 4, standard().sections(), standard().actions()))).containsExactly("layout");
    }

    @Test
    void sectionOrderOrSwitchIsTheSectionsPartAndNotTheFieldsPart() {
        List<Section> swapped = new ArrayList<>(standard().sections());
        swapped.add(3, swapped.remove(2));
        assertThat(CardSetupCanonical.changedParts(standard(),
                new CardSetup("standard", 3, swapped, standard().actions()))).containsExactly("sections");

        CardSetup billingOn = CardSetupValidatorTest.section(standard(), "billing",
                s -> new Section(s.key(), true, s.fields()));
        assertThat(CardSetupCanonical.changedParts(standard(), billingOn)).containsExactly("sections");
    }

    @Test
    void aFieldSwitchLabelOrMoveIsTheFieldsPart() {
        assertThat(CardSetupCanonical.changedParts(standard(), CardSetupValidatorTest.field(standard(), "bp",
                f -> new FieldPlacement(f.key(), true, ""))))
                .containsExactly("fields");
        assertThat(CardSetupCanonical.changedParts(standard(), CardSetupValidatorTest.field(standard(), "ward",
                f -> new FieldPlacement(f.key(), true, "Wing")))).containsExactly("fields");
        assertThat(CardSetupCanonical.changedParts(standard(), CardSetupValidatorTest.move(standard(), "doctor", "stay")))
                .containsExactly("fields");
    }

    @Test
    void quickActionOrderOrSwitchIsTheQuickActionsPart() {
        List<ActionPlacement> reordered = new ArrayList<>(standard().actions());
        reordered.add(0, reordered.remove(1));
        assertThat(CardSetupCanonical.changedParts(standard(), withActions(standard(), reordered)))
                .containsExactly("quickActions");
        List<ActionPlacement> deposit = new ArrayList<>(standard().actions());
        deposit.set(4, new ActionPlacement("deposit", true));
        assertThat(CardSetupCanonical.changedParts(standard(), withActions(standard(), deposit)))
                .containsExactly("quickActions");
    }

    @Test
    void partsComeInTheOrderLayoutSectionsFieldsQuickActions() {
        CardSetupDefaults.Template template = CardSetupDefaults.templates().get(2);
        assertThat(template.key()).isEqualTo("hospital");
        CardSetup applied = new CardSetup(template.layout(), 3, template.sections(), template.actions());

        assertThat(CardSetupCanonical.changedParts(standard(), applied))
                .containsExactly("layout", "sections", "fields", "quickActions");
    }
}
