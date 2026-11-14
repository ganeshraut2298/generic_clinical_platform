package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.application.emr.EmrFormModel.Band;
import com.sahaj.opd.application.emr.EmrFormModel.BandSetup;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Option;
import com.sahaj.opd.application.emr.EmrFormModel.Score;
import com.sahaj.opd.application.emr.EmrFormModel.ScoreRule;
import com.sahaj.opd.application.emr.EmrFormModel.Settings;
import com.sahaj.opd.application.emr.EmrFormModel.ShowIf;
import com.sahaj.opd.application.emr.EmrFormModel.UseIn;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmrFormRulesTest {

    private static final Set<String> DEPARTMENTS = Set.of("gm", "dental");

    private static Settings settings(List<String> departments, List<String> places, boolean approval) {
        return new Settings(new UseIn(departments, places), "ASSESSMENT", List.of("NEW_CONSULT"), List.of("DOCTOR"),
                "EN", approval);
    }

    private static Option option(String key, int score) {
        return new Option(key, key.toUpperCase(), null, null, score);
    }

    private static Field field(String key, String type, List<Option> options) {
        return new Field(key, type, "Label " + key, null, null, null, false, 3, null, null, null, null, null, options,
                true, false, List.of("DOCTOR"), null, null, null, null);
    }

    private static Field withShowIf(Field f, ShowIf showIf) {
        return new Field(f.key(), f.type(), f.label(), null, null, null, false, 3, null, null, null, null, null,
                f.options(), true, false, List.of("DOCTOR"), null, showIf, null, null);
    }

    private static Field score(String key, List<String> sources, List<ScoreRule> rules) {
        return new Field(key, "SCORE", "Total", null, null, null, false, 3, null, null, null, null, null, List.of(),
                true, false, List.of("DOCTOR"), null, null, new Score(sources, rules), null);
    }

    private static Field band(String key, String source, List<Band> bands) {
        return new Field(key, "SCORE_BAND", "Risk", null, null, null, false, 3, null, null, null, null, null, List.of(),
                true, false, List.of("DOCTOR"), null, null, null, new BandSetup(source, bands));
    }

    private static List<Option> gcsEye() {
        return List.of(option("e1", 1), option("e2", 2), option("e3", 3), option("e4", 4));
    }

    private static List<String> fieldsOf(List<FieldMessage> errors) {
        return errors.stream().map(FieldMessage::field).toList();
    }

    @Test
    void aWellFormedDraftHasNoProblemsAndADraftMayBeEmptyAndHaveNoDepartment() {
        List<Field> fields = List.of(field("eye", "SINGLE_CHOICE", gcsEye()), field("notes", "LONG_TEXT", List.of()));

        assertThat(EmrFormRules.checkDraft("Eye check", settings(List.of("gm"), List.of(), true), fields, DEPARTMENTS))
                .isEmpty();
        assertThat(EmrFormRules.checkDraft("Empty", settings(List.of(), List.of(), true), List.of(), DEPARTMENTS))
                .isEmpty();
    }

    @Test
    void duplicateKeysBadTypesAndMissingOptionsAreNamedByPosition() {
        List<Field> fields = List.of(field("eye", "SINGLE_CHOICE", gcsEye()), field("eye", "SHORT_TEXT", List.of()),
                field("pick", "DROPDOWN", List.of()), field("odd", "RAINBOW", List.of()));

        List<String> where = fieldsOf(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true), fields,
                DEPARTMENTS));

        assertThat(where).contains("fields[1].key", "fields[2].options", "fields[3].type");
    }

    @Test
    void unknownDepartmentsLongNamesAndMarkupAreRefused() {
        List<String> where = fieldsOf(EmrFormRules.checkDraft("<b>x</b>" + "y".repeat(80),
                settings(List.of("ghost"), List.of("MORGUE"), true), List.of(), DEPARTMENTS));

        assertThat(where).contains("name", "settings.useIn.departmentKeys", "settings.useIn.places");
    }

    @Test
    void aShowIfMustPointToAnEarlierChoiceFieldAndAnExistingOption() {
        Field yesNo = field("diabetic", "YES_NO", List.of());
        Field early = withShowIf(field("foot", "SHORT_TEXT", List.of()), new ShowIf("diabetic", "yes"));
        Field later = withShowIf(field("first", "SHORT_TEXT", List.of()), new ShowIf("diabetic", "yes"));
        Field badOption = withShowIf(field("x", "SHORT_TEXT", List.of()), new ShowIf("diabetic", "maybe"));
        Field fromText = withShowIf(field("y", "SHORT_TEXT", List.of()), new ShowIf("foot", "yes"));

        assertThat(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true), List.of(yesNo, early),
                DEPARTMENTS)).isEmpty();
        assertThat(fieldsOf(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true),
                List.of(later, yesNo), DEPARTMENTS))).containsExactly("fields[0].showIf");
        assertThat(fieldsOf(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true),
                List.of(yesNo, badOption, early, fromText), DEPARTMENTS))).containsExactly("fields[1].showIf",
                "fields[3].showIf");
    }

    @Test
    void scoreSourcesMustExistAndRulesMustFitTheirField() {
        Field eye = field("eye", "SINGLE_CHOICE", gcsEye());
        Field count = field("tests", "MULTI_CHOICE", List.of(option("a", 0), option("b", 0)));
        Field good = score("total", List.of("eye"), List.of(
                new ScoreRule("CHOSEN_COUNT_BELOW", "tests", new BigDecimal("2"), 1)));
        Field missing = score("t2", List.of("ghost"), List.of());
        Field misfit = score("t3", List.of("eye"), List.of(
                new ScoreRule("NUMBER_BELOW", "eye", new BigDecimal("2"), 1)));

        assertThat(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true), List.of(eye, count, good),
                DEPARTMENTS)).isEmpty();
        assertThat(fieldsOf(EmrFormRules.checkDraft("F", settings(List.of("gm"), List.of(), true),
                List.of(eye, missing, misfit), DEPARTMENTS))).containsExactly("fields[1].score",
                "fields[2].score.rules[0]");
    }

    @Test
    void aFieldThatAnotherFieldReadsIsListedWithItsUsers() {
        Field yesNo = field("diabetic", "YES_NO", List.of());
        Field dependent = withShowIf(field("foot", "SHORT_TEXT", List.of()), new ShowIf("diabetic", "yes"));
        Field total = score("total", List.of("diabetic"), List.of());

        Map<String, List<String>> used = EmrFormRules.usedBy(List.of(yesNo, dependent, total));

        assertThat(used.get("diabetic")).containsExactlyInAnyOrder("foot", "total");
        assertThat(used).doesNotContainKey("foot");
    }

    // ---- publish ----

    private static Definition definition(List<String> departments, List<Field> fields) {
        return new Definition(1, "F", settings(departments, List.of(), true), fields);
    }

    @Test
    void publishNeedsAFieldAndAPlaceToUseTheForm() {
        assertThat(fieldsOf(EmrFormRules.checkPublishable(definition(List.of(), List.of()))))
                .containsExactly("fields", "settings.useIn");
        assertThat(EmrFormRules.checkPublishable(definition(List.of("gm"), List.of(field("a", "SHORT_TEXT",
                List.of()))))).isEmpty();
        Definition place = new Definition(1, "F", settings(List.of(), List.of("EMERGENCY"), false),
                List.of(field("a", "SHORT_TEXT", List.of())));
        assertThat(EmrFormRules.checkPublishable(place)).isEmpty();
    }

    @Test
    void bandsMustAscendAndStartAtOrBelowTheLowestPossibleTotal() {
        Field eye = field("eye", "SINGLE_CHOICE", gcsEye());
        Field verbal = field("verbal", "SINGLE_CHOICE", List.of(option("v1", 1), option("v5", 5)));
        Field total = score("total", List.of("eye", "verbal"), List.of());
        List<Band> good = List.of(new Band(new BigDecimal("2"), "Severe"), new Band(new BigDecimal("9"), "Moderate"));
        Field okBand = band("risk", "total", good);
        Field notAscending = band("risk2", "total", List.of(new Band(new BigDecimal("2"), "A"),
                new Band(new BigDecimal("2"), "B")));
        Field startsTooHigh = band("risk3", "total", List.of(new Band(new BigDecimal("3"), "A")));
        Field noScore = band("risk4", "eye", List.of(new Band(BigDecimal.ZERO, "A")));

        assertThat(EmrFormRules.lowestTotal(total, byKey(eye, verbal, total))).isEqualByComparingTo("2");
        assertThat(EmrFormRules.checkPublishable(definition(List.of("gm"), List.of(eye, verbal, total, okBand))))
                .isEmpty();
        assertThat(fieldsOf(EmrFormRules.checkPublishable(definition(List.of("gm"),
                List.of(eye, verbal, total, notAscending, startsTooHigh, noScore)))))
                .containsExactly("fields[3].band", "fields[4].band", "fields[5].band");
    }

    private static Map<String, Field> byKey(Field... fields) {
        Map<String, Field> map = new HashMap<>();
        for (Field field : fields) {
            map.put(field.key(), field);
        }
        return map;
    }

    // ---- scores (C-9) ----

    @Test
    void aTotalIsBlankUntilOneSourceIsAnsweredThenSumsChosenScoresAndFiredRules() {
        Field eye = field("eye", "SINGLE_CHOICE", gcsEye());
        Field tests = field("tests", "MULTI_CHOICE", List.of(option("a", 2), option("b", 3)));
        Field age = field("age", "NUMBER", List.of());
        Field total = score("total", List.of("eye", "tests"), List.of(
                new ScoreRule("NUMBER_BELOW", "age", new BigDecimal("18"), 5),
                new ScoreRule("CHOSEN_COUNT_BELOW", "tests", new BigDecimal("2"), 10)));
        Map<String, Field> byKey = byKey(eye, tests, age, total);

        assertThat(EmrFormRules.total(total, byKey, Map.of(), Map.of())).isNull();
        assertThat(EmrFormRules.total(total, byKey, Map.of("eye", List.of("e3")), Map.of())).isEqualByComparingTo("3");
        assertThat(EmrFormRules.total(total, byKey,
                Map.of("eye", List.of("e4"), "tests", List.of("a", "b")), Map.of("age", new BigDecimal("10"))))
                .isEqualByComparingTo("14");
        assertThat(EmrFormRules.total(total, byKey, Map.of("eye", List.of("e1"), "tests", List.of("a")), Map.of()))
                .isEqualByComparingTo("13");
    }

    @Test
    void theBandIsTheOneWithTheHighestFromNotAboveTheTotal() {
        BandSetup setup = new BandSetup("total", List.of(new Band(new BigDecimal("3"), "Severe"),
                new Band(new BigDecimal("9"), "Moderate"), new Band(new BigDecimal("13"), "Mild")));

        assertThat(EmrFormRules.bandLabel(setup, null)).isNull();
        assertThat(EmrFormRules.bandLabel(setup, new BigDecimal("2"))).isNull();
        assertThat(EmrFormRules.bandLabel(setup, new BigDecimal("9"))).isEqualTo("Moderate");
        assertThat(EmrFormRules.bandLabel(setup, new BigDecimal("15"))).isEqualTo("Mild");
    }

    @Test
    void countsGiveFieldsLogicAndScored() {
        Field yesNo = field("diabetic", "YES_NO", List.of());
        Field dependent = withShowIf(field("foot", "SHORT_TEXT", List.of()), new ShowIf("diabetic", "yes"));
        Field total = score("total", List.of("diabetic"), List.of());

        assertThat(EmrFormRules.counts(List.of(yesNo, dependent, total))).containsExactly(3, 1, 1);
    }
}
