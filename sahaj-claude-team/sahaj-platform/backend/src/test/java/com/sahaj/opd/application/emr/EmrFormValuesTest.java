package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Context;
import com.sahaj.opd.application.emr.EmrComponentValues.Lookups;
import com.sahaj.opd.application.emr.EmrComponentValues.MedicineRef;
import com.sahaj.opd.application.emr.EmrFormModel.Band;
import com.sahaj.opd.application.emr.EmrFormModel.BandSetup;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Option;
import com.sahaj.opd.application.emr.EmrFormModel.Score;
import com.sahaj.opd.application.emr.EmrFormModel.ScoreRule;
import com.sahaj.opd.application.emr.EmrFormModel.ShowIf;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-59-5: the values of a clinic form in an EMR record (rule C-9, scores and bands, is tagged and not reviewed). */
class EmrFormValuesTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private final EmrComponentValues components = new EmrComponentValues(EmrTestData.MAPPER);
    private final Lookups lookups = new Lookups() {
        @Override
        public Optional<MedicineRef> medicine(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean activeService(UUID id) {
            return false;
        }
    };

    private static Field field(String key, String type, boolean required, BigDecimal min, BigDecimal max,
                               BigDecimal from, BigDecimal to, List<Option> options, boolean copy, ShowIf showIf,
                               Score score, BandSetup band) {
        return new Field(key, type, "Label " + key, null, null, null, required, 3, "cm", min, max, from, to, options,
                true, copy, List.of("DOCTOR"), null, showIf, score, band);
    }

    private static Field simple(String key, String type) {
        return field(key, type, false, null, null, null, null, List.of(), false, null, null, null);
    }

    private static Field required(String key, String type) {
        return field(key, type, true, null, null, null, null, List.of(), false, null, null, null);
    }

    private static Field number(String key, String min, String max, String from, String to) {
        return field(key, "NUMBER", false, min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                from == null ? null : new BigDecimal(from), to == null ? null : new BigDecimal(to), List.of(), false,
                null, null, null);
    }

    private static Field choice(String key, String type, int... scores) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            options.add(new Option("o" + i, "Option " + i, null, null, scores[i]));
        }
        return field(key, type, false, null, null, null, null, options, false, null, null, null);
    }

    private static Definition form(Field... fields) {
        return new Definition(1, "Test form", null, List.of(fields));
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode clean(Definition form, String values, List<FieldMessage> errors) {
        return EmrFormValues.clean(EmrTestData.MAPPER, form, "sections[0]", json(values), null, components,
                new Context(NOW, DOCTOR, null, lookups), errors, new ArrayList<>());
    }

    // ---- save ----

    @Test
    void validAnswersOfEveryPlainTypeAreKept() {
        Definition form = form(simple("a", "SHORT_TEXT"), simple("b", "LONG_TEXT"), simple("c", "NUMBER"),
                simple("d", "DATE"), simple("e", "TIME"), simple("f", "YES_NO"), choice("g", "SINGLE_CHOICE", 0, 1),
                choice("h", "MULTI_CHOICE", 0, 1), choice("i", "DROPDOWN", 0), simple("j", "VITALS_BLOCK"));
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form, "{\"a\":\"x\",\"b\":\"long\",\"c\":12.5,\"d\":\"2026-11-12\",\"e\":\"14:30\","
                + "\"f\":false,\"g\":\"o1\",\"h\":[\"o0\",\"o1\"],\"i\":\"o0\",\"j\":{\"bpSystolic\":120,\"pulse\":72}}",
                errors);

        assertThat(errors).isEmpty();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.path("f").asBoolean(true)).isFalse();
    }

    @Test
    void aNumberOutsideMinAndMaxIsRefusedWithTheRangeInTheMessage() {
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form(number("n", "0", "10", null, null)), "{\"n\":11}", errors);

        assertThat(result).isNull();
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("sections[0].values.n");
            assertThat(e.message()).isEqualTo("Outside 0–10");
        });
    }

    @Test
    void aNumberInsideTheLimitsButOutsideTheNormalRangeIsAcceptedAndFlaggedAbnormal() {
        Field monofilament = number("n", "0", "10", "8", "10");
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form(monofilament), "{\"n\":6}", errors);

        assertThat(errors).isEmpty();
        assertThat(result.path("n").asInt()).isEqualTo(6);
        assertThat(EmrFormValues.abnormal(monofilament, result.get("n"))).isTrue();
        assertThat(EmrFormValues.abnormal(monofilament, json("9"))).isFalse();
    }

    @Test
    void aFieldHiddenByItsShowIfRuleHasItsValueDroppedAndAHiddenSourceHidesTheChain() {
        Field ulcer = simple("ulcer", "YES_NO");
        Field site = field("site", "SHORT_TEXT", false, null, null, null, null, List.of(), false,
                new ShowIf("ulcer", "yes"), null, null);
        Field depth = field("depth", "SHORT_TEXT", false, null, null, null, null, List.of(), false,
                new ShowIf("site", "yes"), null, null);
        Definition form = form(ulcer, site, depth);
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form, "{\"ulcer\":false,\"site\":\"toe\",\"depth\":\"deep\"}", errors);

        assertThat(errors).isEmpty();
        assertThat(result.fieldNames()).toIterable().containsExactly("ulcer");
        assertThat(EmrFormValues.hiddenKeys(form, json("{\"ulcer\":true}"))).containsExactly("depth");
    }

    @Test
    void aMultiChoiceSourceShowsTheFieldWhenTheOptionIsAmongThoseChosen() {
        Field source = choice("src", "MULTI_CHOICE", 0, 1);
        Field dependent = field("dep", "SHORT_TEXT", false, null, null, null, null, List.of(), false,
                new ShowIf("src", "o1"), null, null);

        assertThat(EmrFormValues.hiddenKeys(form(source, dependent), json("{\"src\":[\"o0\",\"o1\"]}"))).isEmpty();
        assertThat(EmrFormValues.hiddenKeys(form(source, dependent), json("{\"src\":[\"o0\"]}")))
                .containsExactly("dep");
        assertThat(EmrFormValues.hiddenKeys(form(source, dependent), json("{}"))).containsExactly("dep");
    }

    @Test
    void wrongValuesAreRefusedWithFixedWordsThatNeverRepeatTheValue() {
        Definition form = form(simple("t", "SHORT_TEXT"), simple("time", "TIME"), simple("day", "DATE"),
                choice("one", "SINGLE_CHOICE", 0), choice("many", "MULTI_CHOICE", 0, 1), simple("yn", "YES_NO"));
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form, "{\"t\":\"<script>secret</script>\",\"time\":\"25:99\",\"day\":\"12/11/2026\","
                + "\"one\":\"nope\",\"many\":[\"o0\",\"o0\"],\"yn\":\"yes\"}", errors);

        assertThat(result).isNull();
        assertThat(errors).hasSize(6);
        assertThat(errors).noneMatch(e -> e.message().contains("secret") || e.message().contains("nope"));
    }

    @Test
    void aTextOverItsLimitAndAnUnknownFieldAreRefused() {
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form(simple("t", "SHORT_TEXT")), "{\"t\":\"" + "x".repeat(201) + "\",\"ghost\":1}",
                errors);

        assertThat(result).isNull();
        assertThat(errors).extracting(FieldMessage::field).contains("sections[0].values.t", "sections[0].values");
    }

    @Test
    void displayAndLaterTypesStoreNothingAndRefuseAValue() {
        Definition form = form(simple("h", "SECTION_HEADING"), simple("s", "SCORE"), simple("sig", "SIGNATURE"),
                simple("tooth", "TOOTH_CHART"));
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form, "{\"h\":\"x\",\"s\":3,\"sig\":\"ink\",\"tooth\":{}}", errors);

        assertThat(result).isNull();
        assertThat(errors).hasSize(4);
    }

    @Test
    void anExplicitNullClearsAFieldAndAnEmptyTextIsNotStored() {
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form(simple("a", "SHORT_TEXT"), simple("b", "SHORT_TEXT")),
                "{\"a\":null,\"b\":\"  \"}", errors);

        assertThat(errors).isEmpty();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void aSharedComponentFieldUsesTheComponentRules() {
        Definition form = form(simple("tests", "LAB_TESTS"));
        UUID id = UUID.randomUUID();
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form, "{\"tests\":{\"component\":\"EMR_LIST\",\"kind\":\"INVESTIGATIONS\","
                + "\"schemaVersion\":1,\"items\":[{\"id\":\"" + id + "\",\"itemKey\":null,\"text\":\"cbc\","
                + "\"newToList\":true}]}}", errors);

        assertThat(errors).isEmpty();
        JsonNode item = result.path("tests").path("items").get(0);
        assertThat(item.path("text").asText()).isEqualTo("Cbc");
        assertThat(item.path("addedByMembershipId").asText()).isEqualTo(DOCTOR.toString());
        assertThat(EmrFormValues.componentKeys(form, EmrComponentBinding.PRESCRIPTION)).isEmpty();
        assertThat(EmrFormValues.componentKeys(form(simple("rx", "MEDICINE_LIST")), EmrComponentBinding.PRESCRIPTION))
                .containsExactly("rx");
    }

    @Test
    void aWrongComponentShapeIsRefusedAsAFieldError() {
        List<FieldMessage> errors = new ArrayList<>();

        ObjectNode result = clean(form(simple("pins", "BODY_MAP")), "{\"pins\":{\"component\":\"EMR_LIST\"}}", errors);

        assertThat(result).isNull();
        assertThat(errors).extracting(FieldMessage::field).containsExactly("sections[0].values.pins");
    }

    // ---- Done ----

    @Test
    void aFormIsDoneWhenEveryShownRequiredFieldHasAValue() {
        Definition form = form(required("a", "SHORT_TEXT"), required("b", "YES_NO"), simple("c", "SHORT_TEXT"));

        assertThat(EmrFormValues.isDone(form, json("{\"a\":\"x\"}"))).isFalse();
        assertThat(EmrFormValues.isDone(form, json("{\"a\":\"x\",\"b\":false}"))).isTrue();
        assertThat(EmrFormValues.isDone(form, json("{}"))).isFalse();
    }

    @Test
    void aFormWithNoRequiredFieldIsDoneWhenAnyFillableFieldHasAValue() {
        Definition form = form(simple("h", "SECTION_HEADING"), simple("a", "SHORT_TEXT"), simple("s", "SCORE"));

        assertThat(EmrFormValues.isDone(form, json("{}"))).isFalse();
        assertThat(EmrFormValues.isDone(form, json("{\"a\":\"x\"}"))).isTrue();
    }

    @Test
    void aRequiredFieldHiddenByItsRuleDoesNotCountAndLaterTypesAreNotFillable() {
        Field gate = simple("gate", "YES_NO");
        Field hiddenRequired = field("why", "SHORT_TEXT", true, null, null, null, null, List.of(), false,
                new ShowIf("gate", "yes"), null, null);
        Definition form = form(gate, hiddenRequired, field("sig", "SIGNATURE", true, null, null, null, null,
                List.of(), false, null, null, null));

        assertThat(EmrFormValues.isDone(form, json("{\"gate\":false}"))).isTrue();
        assertThat(EmrFormValues.isDone(form, json("{\"gate\":true}"))).isFalse();
    }

    // ---- score and band ----

    private static Definition scored() {
        Field eye = choice("eye", "SINGLE_CHOICE", 1, 2, 3);
        Field motor = choice("motor", "MULTI_CHOICE", 1, 2);
        Field sites = number("sites", null, null, null, null);
        Field total = field("total", "SCORE", false, null, null, null, null, List.of(), false, null,
                new Score(List.of("eye", "motor"), List.of(new ScoreRule("NUMBER_BELOW", "sites", new BigDecimal("8"), 1),
                        new ScoreRule("CHOSEN_COUNT_BELOW", "motor", new BigDecimal("2"), 2))), null);
        Field band = field("band", "SCORE_BAND", false, null, null, null, null, List.of(), false, null, null,
                new BandSetup("total", List.of(new Band(new BigDecimal("0"), "Low"), new Band(new BigDecimal("3"), "High"))));
        return form(eye, motor, sites, total, band);
    }

    @Test
    void theTotalAddsChosenOptionsAndRulePointsAndIsBlankUntilASourceIsAnswered() {
        Definition form = scored();
        Field total = form.fields().get(3);

        assertThat(EmrFormValues.total(form, total, json("{}"))).isNull();
        // eye o1 = 2, motor o0 + o1 = 1 + 2 = 3, sites 5 below 8 = +1, motor count 2 is not below 2
        assertThat(EmrFormValues.total(form, total, json("{\"eye\":\"o1\",\"motor\":[\"o0\",\"o1\"],\"sites\":5}")))
                .isEqualByComparingTo("6");
        // only one motor option chosen: count 1 is below 2, +2
        assertThat(EmrFormValues.total(form, total, json("{\"motor\":[\"o0\"]}"))).isEqualByComparingTo("3");
    }

    @Test
    void theBandIsTheOneWithTheHighestFromNotAboveTheTotal() {
        Definition form = scored();
        Field band = form.fields().get(4);

        assertThat(EmrFormValues.band(form, band, json("{}"))).isNull();
        assertThat(EmrFormValues.band(form, band, json("{\"eye\":\"o0\"}"))).isEqualTo("Low");
        assertThat(EmrFormValues.band(form, band, json("{\"eye\":\"o2\"}"))).isEqualTo("High");
    }

    @Test
    void aHiddenSourceAddsNoScore() {
        Field gate = simple("gate", "YES_NO");
        Field eye = field("eye", "SINGLE_CHOICE", false, null, null, null, null,
                List.of(new Option("o0", "Zero", null, null, 5)), false, new ShowIf("gate", "yes"), null, null);
        Field total = field("total", "SCORE", false, null, null, null, null, List.of(), false, null,
                new Score(List.of("eye"), List.of()), null);
        Definition form = form(gate, eye, total);

        assertThat(EmrFormValues.total(form, total, json("{\"gate\":false,\"eye\":\"o0\"}"))).isNull();
        assertThat(EmrFormValues.total(form, total, json("{\"gate\":true,\"eye\":\"o0\"}"))).isEqualByComparingTo("5");
    }

    // ---- history lines ----

    @Test
    void historyLinesShowOptionLabelsUnitsAbnormalFlagsTheScoreAndTheBand() {
        Definition form = scored();

        List<EmrFormValues.Line> lines = EmrFormValues.lines(form, json("{\"eye\":\"o2\",\"sites\":5}"));

        assertThat(lines).extracting(EmrFormValues.Line::label).contains("Label eye", "Label sites", "Label total",
                "Label band");
        assertThat(lines).anySatisfy(l -> assertThat(l.value()).isEqualTo("Option 2"));
        assertThat(lines).anySatisfy(l -> assertThat(l.value()).isEqualTo("5 cm"));
        assertThat(lines).anySatisfy(l -> assertThat(l.label()).isEqualTo("Label band"));
    }

    @Test
    void historyLinesLeaveOutHiddenAndEmptyFieldsAndMarkAbnormalNumbers() {
        Field gate = simple("gate", "YES_NO");
        Field hidden = field("why", "SHORT_TEXT", false, null, null, null, null, List.of(), false,
                new ShowIf("gate", "yes"), null, null);
        Field count = number("count", "0", "10", "8", "10");

        List<EmrFormValues.Line> lines = EmrFormValues.lines(form(gate, hidden, count),
                json("{\"gate\":false,\"why\":\"x\",\"count\":6}"));

        assertThat(lines).extracting(EmrFormValues.Line::value).containsExactly("No", "6 cm (Abnormal)");
    }
}
