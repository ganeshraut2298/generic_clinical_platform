package com.sahaj.ipd.domain.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.CardSetupViews.CardDraft;
import com.sahaj.ipd.application.CardSetupViews.CardSetupWrite;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-37 (security review finding 8): the labels are text the owner typed and never reach a log through toString. */
class CardSetupToStringTest {

    private static final String SECRET = "Secret wing name";

    private static CardSetup labelled() {
        return CardSetupValidatorTest.field(CardSetupDefaults.standard(), "ward",
                f -> new FieldPlacement(f.key(), true, SECRET));
    }

    @Test
    void theFieldPlacementPrintsItsKeyAndSwitchOnly() {
        assertThat(new FieldPlacement("ward", true, SECRET).toString()).isEqualTo("FieldPlacement[key=ward, on=true]");
    }

    @Test
    void theSectionPrintsItsKeySwitchAndNumberOfFieldsOnly() {
        Section section = new Section("stay", true, List.of(new FieldPlacement("ward", true, SECRET)));

        assertThat(section.toString()).isEqualTo("Section[key=stay, on=true, fields=1]").doesNotContain(SECRET);
        assertThat(new Section("stay", false, null).toString()).isEqualTo("Section[key=stay, on=false, fields=null]");
    }

    @Test
    void theSetupPrintsLayoutAndCountsOnly() {
        CardSetup setup = labelled();

        assertThat(setup.toString()).doesNotContain(SECRET).startsWith("CardSetup[layout=standard, cardsPerRow=3");
        assertThat(new CardSetup(null, 0, null, null).toString())
                .isEqualTo("CardSetup[layout=null, cardsPerRow=0, sections=null, actions=null]");
    }

    @Test
    void aRecordThatHoldsASetupDoesNotPrintTheLabelEither() {
        CardSetup setup = labelled();

        assertThat(new CardSetupWrite(1, null, setup).toString()).doesNotContain(SECRET);
        assertThat(new CardDraft(5L, 1, setup, Instant.parse("2026-11-09T05:30:00Z"), "Dr Owner", List.of("fields"))
                .toString()).doesNotContain(SECRET);
    }

    @Test
    void equalityStillComparesTheLabels() {
        assertThat(labelled()).isEqualTo(labelled()).isNotEqualTo(CardSetupDefaults.standard());
    }
}
