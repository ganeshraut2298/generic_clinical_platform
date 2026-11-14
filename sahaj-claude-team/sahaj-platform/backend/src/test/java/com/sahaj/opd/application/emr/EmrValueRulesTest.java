package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmrValueRulesTest {

    private static JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void ok(FieldDefinition field, String value) {
        EmrValueRules.check(field, json(value));
    }

    private static void bad(FieldDefinition field, String value) {
        assertThatThrownBy(() -> EmrValueRules.check(field, json(value)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void everyTypeHasTheValueKindOfTheAdr() {
        assertThat(EmrFieldType.valueKind("RICH_TEXT")).isEqualTo(EmrValueKind.TEXT);
        assertThat(EmrFieldType.valueKind("NUMBER_TREND")).isEqualTo(EmrValueKind.NUMBER);
        assertThat(EmrFieldType.valueKind("TOGGLE")).isEqualTo(EmrValueKind.BOOLEAN);
        assertThat(EmrFieldType.valueKind("CHECKLIST")).isEqualTo(EmrValueKind.MULTI_CHOICE);
        assertThat(EmrFieldType.valueKind("SEARCH_LIST")).isEqualTo(EmrValueKind.ITEM_LIST);
        assertThat(EmrFieldType.valueKind("GRID")).isEqualTo(EmrValueKind.TABLE);
        assertThat(EmrFieldType.valueKind("TOOTH_PICKER")).isEqualTo(EmrValueKind.STRUCTURED);
        assertThat(EmrFieldType.valueKind("FORMULA")).isEqualTo(EmrValueKind.DISPLAY_ONLY);
        assertThat(EmrFieldType.valueKind("SIGNATURE")).isEqualTo(EmrValueKind.LATER);
        assertThat(EmrFieldType.valueKind("SOMETHING_NEW")).isEqualTo(EmrValueKind.LATER);
        assertThat(EmrFieldType.valueKind(null)).isEqualTo(EmrValueKind.LATER);
    }

    @Test
    void textHasItsLimitsAndNoHtml() {
        FieldDefinition text = EmrTestData.field("t", "TEXT", false);
        FieldDefinition rich = EmrTestData.field("r", "RICH_TEXT", false);
        ok(text, "\"Dry cough since two days\"");
        ok(text, "\"BP < 90 on arrival\"");
        bad(text, "\"" + "a".repeat(2001) + "\"");
        ok(rich, "\"" + "a".repeat(10_000) + "\"");
        bad(rich, "\"" + "a".repeat(10_001) + "\"");
        bad(text, "\"<b>bold</b>\"");
        bad(text, "5");
    }

    @Test
    void numbersAreFiniteWithAtMostFourDecimalsAndInRange() {
        FieldDefinition any = EmrTestData.field("n", "NUMBER", false);
        FieldDefinition ranged = EmrTestData.number("n", new BigDecimal("10"), new BigDecimal("20"));
        ok(any, "98.6");
        ok(any, "-1000000");
        ok(any, "1.2300");
        bad(any, "1000001");
        bad(any, "1.23456");
        bad(any, "\"98\"");
        ok(ranged, "15");
        bad(ranged, "9.9999");
        bad(ranged, "20.0001");
    }

    @Test
    void numberWithUnitNeedsExactlyANumberAndAShortUnit() {
        FieldDefinition field = EmrTestData.field("d", "NUMBER_UNIT", false);
        ok(field, "{\"value\":3,\"unit\":\"days\"}");
        bad(field, "{\"value\":3}");
        bad(field, "{\"value\":3,\"unit\":\"" + "u".repeat(21) + "\"}");
        bad(field, "{\"value\":\"3\",\"unit\":\"days\"}");
        bad(field, "{\"value\":3,\"unit\":\"days\",\"x\":1}");
    }

    @Test
    void datesAreIsoCalendarDates() {
        FieldDefinition field = EmrTestData.field("d", "DATE", false);
        ok(field, "\"2026-11-12\"");
        bad(field, "\"12/11/2026\"");
        bad(field, "\"2026-02-30\"");
        bad(field, "20261112");
    }

    @Test
    void aToggleIsABoolean() {
        FieldDefinition field = EmrTestData.field("b", "TOGGLE", false);
        ok(field, "true");
        ok(field, "false");
        bad(field, "\"yes\"");
    }

    @Test
    void aChoiceIsAnOptionKeyOrFreeTextWhenTheFieldHasNoOptions() {
        FieldDefinition withOptions = EmrTestData.choice("s", false, "mild", "moderate", "severe");
        FieldDefinition free = EmrTestData.field("f", "DROPDOWN", false);
        ok(withOptions, "\"mild\"");
        bad(withOptions, "\"Mild\"");
        bad(withOptions, "\"anything\"");
        ok(free, "\"By region\"");
        bad(free, "\"" + "a".repeat(201) + "\"");
        bad(free, "3");
    }

    @Test
    void aMultiChoiceIsAnArrayOfAtMostFiftyChoices() {
        FieldDefinition choice = EmrTestData.choice("m", false, "pallor", "icterus");
        FieldDefinition field = new FieldDefinition("m", "m", "MULTI_SELECT", EmrValueKind.MULTI_CHOICE, "", false,
                "FIELD", null, false, choice.options(), java.util.List.of(), null, null);
        ok(field, "[\"pallor\",\"icterus\"]");
        ok(field, "[]");
        bad(field, "[\"pallor\",\"oedema\"]");
        bad(field, "\"pallor\"");
        FieldDefinition free = new FieldDefinition("m", "m", "CHIPS", EmrValueKind.MULTI_CHOICE, "", false, "NONE",
                null, true, java.util.List.of(), java.util.List.of(), null, null);
        StringBuilder fifty = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            fifty.append(i == 0 ? "" : ",").append("\"x").append(i).append('"');
        }
        bad(free, fifty.append(']').toString());
    }

    @Test
    void anItemListEntryIsListedWithAKeyOrTypedWithoutOne() {
        FieldDefinition field = EmrTestData.field("c", "SEARCH_LIST", false);
        ok(field, "[{\"itemKey\":\"3f0c2a52\",\"text\":\"Fever\",\"newToList\":false}]");
        ok(field, "[{\"itemKey\":null,\"text\":\"Odd complaint\",\"newToList\":true}]");
        bad(field, "[{\"itemKey\":null,\"text\":\"Odd complaint\",\"newToList\":false}]");
        bad(field, "[{\"itemKey\":\"k\",\"text\":\"Fever\",\"newToList\":true}]");
        bad(field, "[{\"itemKey\":\"k\",\"text\":\"\",\"newToList\":false}]");
        bad(field, "[{\"itemKey\":\"" + "k".repeat(41) + "\",\"text\":\"Fever\",\"newToList\":false}]");
        bad(field, "[{\"itemKey\":\"k\",\"text\":\"Fever\",\"newToList\":false,\"extra\":1}]");
        bad(field, "{\"itemKey\":\"k\"}");
    }

    @Test
    void aTableRowUsesOnlyItsColumnsAndAFieldWithNoColumnsHasOneCalledEntry() {
        FieldDefinition columns = EmrTestData.table("t", "drug", "dose");
        FieldDefinition none = EmrTestData.table("t");
        ok(columns, "[{\"drug\":\"Paracetamol\",\"dose\":\"500 mg\"}]");
        bad(columns, "[{\"drug\":\"Paracetamol\",\"strength\":\"500 mg\"}]");
        bad(columns, "[{\"drug\":5}]");
        ok(none, "[{\"entry\":\"one\"}]");
        bad(none, "[{\"drug\":\"one\"}]");
        bad(columns, "[{\"drug\":\"" + "a".repeat(501) + "\"}]");
    }

    @Test
    void aStructuredValueHoldsTextOnlyInPhaseTwo() {
        FieldDefinition field = EmrTestData.field("tooth", "TOOTH_PICKER", false);
        ok(field, "{\"text\":\"Caries 16, 26\"}");
        bad(field, "{\"text\":\"x\",\"teeth\":[16]}");
        bad(field, "{\"text\":5}");
        bad(field, "\"Caries\"");
    }

    @Test
    void aDisplayOnlyOrLaterFieldAcceptsNothing() {
        bad(EmrTestData.field("f", "FORMULA", false), "1");
        bad(EmrTestData.field("c", "CHART", false), "\"x\"");
        bad(EmrTestData.field("file", "FILE", false), "\"x\"");
        bad(EmrTestData.field("sig", "SIGNATURE", false), "{}");
    }

    @Test
    void emptyMeansNullBlankEmptyArrayOrNoContent() {
        assertThat(EmrValueRules.isEmpty(null)).isTrue();
        assertThat(EmrValueRules.isEmpty(json("null"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("\"  \""))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("[]"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("{\"value\":null,\"unit\":\"days\"}"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("{\"text\":\"\"}"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("[{\"drug\":\"\",\"dose\":null}]"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("\"x\""))).isFalse();
        assertThat(EmrValueRules.isEmpty(json("0"))).isFalse();
        assertThat(EmrValueRules.isEmpty(json("false"))).isFalse();
        assertThat(EmrValueRules.isEmpty(json("[\"a\"]"))).isFalse();
        assertThat(EmrValueRules.isEmpty(json("{\"value\":0,\"unit\":\"\"}"))).isFalse();
        assertThat(EmrValueRules.isEmpty(json("[{\"itemKey\":null,\"text\":\"Odd\",\"newToList\":true}]"))).isFalse();
    }
}
