package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.ipd.domain.card.CardSetupRepair;
import com.sahaj.ipd.domain.card.CardSetupValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-37 (code review finding 4): the repair on read applies the same text rule as the validator, here the real IPD
 * rule ({@link IpdText}, handed in by {@link CardSetupService#TEXT}), so a control, invisible or text-direction
 * character in a damaged stored label is removed and the repaired setup passes the validator.
 */
class CardSetupRepairTextRuleTest {

    private static CardSetup labelled(String label) {
        List<Section> sections = new ArrayList<>();
        for (Section s : CardSetupDefaults.standard().sections()) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement f : s.fields()) {
                fields.add(f.key().equals("ward") ? new FieldPlacement("ward", true, label) : f);
            }
            sections.add(new Section(s.key(), s.on(), fields));
        }
        return new CardSetup("standard", 3, sections, CardSetupDefaults.standard().actions());
    }

    private static String repairedLabel(String stored) {
        CardSetup repaired = CardSetupRepair.repair(labelled(stored), CardSetupService.TEXT);
        assertThat(CardSetupValidator.validate(repaired, CardSetupService.TEXT)).as("repaired is valid").isEmpty();
        return repaired.sections().stream().flatMap(s -> s.fields().stream()).filter(f -> f.key().equals("ward"))
                .findFirst().orElseThrow().label();
    }

    @Test
    void controlInvisibleAndTextDirectionCharactersAreRemoved() {
        // right-to-left override, left-to-right isolate, zero-width space, a bell, a word joiner, the soft hyphen,
        // a byte order mark, a variation selector, a tag character and a lone surrogate
        String[] damaged = {"Wing‮", "‭ab⁦c", "a​b", "a\u0007b", "a⁠b", "a­b", "a﻿b",
                "a️b", "a󠀁b", "a\uD800b"};
        for (String stored : damaged) {
            assertThat(CardSetupValidator.validate(labelled(stored), CardSetupService.TEXT))
                    .as("the validator refuses " + stored.codePoints().boxed().toList()).isNotEmpty();
            assertThat(repairedLabel(stored)).as(stored.codePoints().boxed().toList().toString()).isNotEmpty()
                    .doesNotContain("‮", "⁦", "​", "\u0007", "⁠");
        }
        assertThat(repairedLabel("Wing‮")).isEqualTo("Wing");
        assertThat(repairedLabel("a​b")).isEqualTo("ab");
        assertThat(repairedLabel("‭ab⁦c")).isEqualTo("abc");
    }

    @Test
    void aJoinerThatIsNotInPlaceIsRemovedAndALabelOfOnlyRefusedCharactersIsTheStandardName() {
        assertThat(repairedLabel("a‍b‌")).isEqualTo("ab");
        assertThat(repairedLabel("‮​\u0007")).isEmpty();
        assertThat(repairedLabel("‮ Ward ‬")).isEmpty();
    }

    @Test
    void aCleanLabelIsKeptAsItIsIncludingAJoinerThatIsInPlace() {
        // Devanagari: KA + VIRAMA + ZWJ + SSA is a legal spelling (the validator accepts it, so repair keeps it)
        String conjunct = "क्‍ष";
        assertThat(CardSetupValidator.validate(labelled(conjunct), CardSetupService.TEXT)).isEmpty();
        assertThat(repairedLabel(conjunct)).isEqualTo(conjunct);
        assertThat(repairedLabel("  Maternity wing  ")).isEqualTo("Maternity wing");
    }

    @Test
    void theNoBreakSpacesAtBothEndsAreStrippedLikeTheValidatorDoes() {
        assertThat(repairedLabel(" Wing ")).isEqualTo("Wing");
    }

    @Test
    void theLimitStillCutsAtFortyCodePointsNotUnits() {
        String symbols = "😀".repeat(45);

        String kept = repairedLabel(symbols);

        assertThat(kept.codePointCount(0, kept.length())).isEqualTo(40);
    }
}
