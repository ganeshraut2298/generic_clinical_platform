package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-026 D13: copy from last visit keeps only the values that still fit the new record's field definitions. */
class EmrCopyFitTest {

    private final LayoutSection section = EmrTestData.section("menstrual", "SPECIALTY", "ASSESSMENT", true, false,
            false, EmrTestData.choice("cycle", false, "regular", "irregular"),
            EmrTestData.choice("flow", false, "scanty", "normal", "heavy", "with_clots"),
            EmrTestData.field("lmp", "DATE", false), EmrTestData.field("note", "TEXT", false),
            EmrTestData.field("trend", "CHART", false));

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ObjectNode fit(String old) {
        return EmrCopyFit.fit(EmrTestData.MAPPER, section, json(old));
    }

    @Test
    void aChoiceKeyOfTheNewOptionsIsKept() {
        assertThat(fit("{\"flow\":\"with_clots\",\"cycle\":\"regular\"}").toString())
                .isEqualTo("{\"flow\":\"with_clots\",\"cycle\":\"regular\"}");
    }

    @Test
    void aChoiceKeyThatIsNotAnOptionOfTheNewDefinitionIsDropped() {
        ObjectNode kept = fit("{\"flow\":\"spotting\",\"cycle\":\"irregular\"}");
        assertThat(kept.has("flow")).isFalse();
        assertThat(kept.path("cycle").asText()).isEqualTo("irregular");
    }

    @Test
    void aValueOfAnotherKindIsDropped() {
        ObjectNode kept = fit("{\"cycle\":\"Regular, 28 days\",\"flow\":true,\"lmp\":\"2026-09-20\","
                + "\"note\":42}");
        assertThat(kept.fieldNames()).toIterable().containsExactly("lmp");
    }

    @Test
    void aMultiChoiceListKeepsNothingWhenOneKeyDoesNotFit() {
        LayoutSection multi = EmrTestData.section("habits", "SPECIALTY", "ASSESSMENT", true, false, false,
                new EmrModel.FieldDefinition("habits", "Habits", "MULTI_CHOICE", EmrValueKind.MULTI_CHOICE, "", false,
                        "FIELD", null, false, List.of(new EmrModel.FieldOption("a", "A", false),
                        new EmrModel.FieldOption("b", "B", false)), List.of(), null, null));
        assertThat(EmrCopyFit.fit(EmrTestData.MAPPER, multi, json("{\"habits\":[\"a\",\"gone\"]}"))).isNull();
        assertThat(EmrCopyFit.fit(EmrTestData.MAPPER, multi, json("{\"habits\":[\"a\",\"b\"]}"))
                .path("habits")).hasSize(2);
    }

    @Test
    void aValueForAFieldThatIsNoLongerInTheLayoutAndEmptyValuesAreDropped() {
        ObjectNode kept = fit("{\"gone\":\"x\",\"note\":\"\",\"lmp\":\"2026-09-20\",\"cycle\":null}");
        assertThat(kept.fieldNames()).toIterable().containsExactly("lmp");
    }

    @Test
    void aDisplayOnlyFieldTakesNoValue() {
        assertThat(fit("{\"trend\":\"x\"}")).isNull();
    }

    @Test
    void whenNothingFitsTheResultIsNullSoTheSectionIsNotCopied() {
        assertThat(fit("{\"cycle\":true,\"flow\":\"spotting\"}")).isNull();
        assertThat(fit("{}")).isNull();
        assertThat(EmrCopyFit.fit(EmrTestData.MAPPER, section, null)).isNull();
        assertThat(EmrCopyFit.fit(EmrTestData.MAPPER, section, json("[1]"))).isNull();
    }

    @Test
    void aSharedComponentValueIsKeptAsItWas() {
        LayoutSection withComponent = EmrTestData.section("rx", "COMMON", "TREATMENT", true, false, false,
                new EmrModel.FieldDefinition("meds", "Medicines", "COMPONENT", EmrValueKind.COMPONENT, "", false, "NONE",
                        null, false, List.of(), List.of(), null, null));
        ObjectNode kept = EmrCopyFit.fit(EmrTestData.MAPPER, withComponent,
                json("{\"meds\":{\"component\":\"EMR_LIST\",\"items\":[{\"id\":\"1\"}]}}"));
        assertThat(kept.has("meds")).isTrue();
    }
}
