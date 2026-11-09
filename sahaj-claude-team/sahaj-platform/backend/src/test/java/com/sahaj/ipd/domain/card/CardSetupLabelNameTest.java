package com.sahaj.ipd.domain.card;

import static com.sahaj.ipd.domain.card.CardSetupValidatorTest.check;
import static com.sahaj.ipd.domain.card.CardSetupValidatorTest.field;
import static com.sahaj.ipd.domain.card.CardSetupValidatorTest.section;
import static com.sahaj.ipd.domain.card.CardSetupValidatorTest.standard;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import com.sahaj.ipd.domain.card.CardSetupValidator.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SAH-37 audit G-4 (DEC-032): rule V15, a label may not duplicate or impersonate another field. The examples are the
 * table of design note section 4.1, then the boundary cases: the field's own name, another case or spacing, a field
 * that is off, two fields with the same new label, a label that fails V12, and V15 together with other errors.
 *
 * <p>Positions in the standard setup (section index . field index): Patient name 0.0, UHID 0.1, Age and gender 0.2,
 * Bed 0.3, IP number 0.4, Mobile 0.5, Alerts 1.0, Admitted on 2.0, Length of stay 2.1, Ward 2.2, Room 2.3, Admission
 * type 2.4, Doctor 3.0, BP 4.0, SpO2 4.2, Diagnosis 5.0, Allergies 5.1, Running bill 6.0, Deposit 6.1, Payer 6.2.
 */
class CardSetupLabelNameTest {

    private static final String MESSAGE = "This name is already used by another field.";

    private static String path(int section, int field) {
        return "setup.sections[" + section + "].fields[" + field + "].label";
    }

    /** The standard setup with these labels: key, label, key, label, ... */
    private static CardSetup labelled(String... pairs) {
        CardSetup setup = standard();
        for (int i = 0; i < pairs.length; i += 2) {
            String label = pairs[i + 1];
            setup = field(setup, pairs[i], f -> new FieldPlacement(f.key(), f.on(), label));
        }
        return setup;
    }

    private static List<String> v15Paths(CardSetup setup) {
        List<Problem> problems = check(setup);
        problems.stream().filter(p -> p.rule() == 15).forEach(p -> assertThat(p.message()).isEqualTo(MESSAGE));
        return problems.stream().filter(p -> p.rule() == 15).map(Problem::path).toList();
    }

    /** One row of the table: a name, the labels to set and the label paths that must carry V15. */
    private record Case(String name, String[] labels, String... v15) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // the examples table of design note 4.1
                new Case("Diagnosis named Allergies", new String[] {"diagnosis", "Allergies"}, path(5, 0)),
                new Case("IP number named ' uhid '", new String[] {"ipNumber", " uhid "}, path(0, 4)),
                new Case("Room named Bed while Bed is called Cot", new String[] {"bed", "Cot", "room", "Bed"},
                        path(2, 3)),
                new Case("Room 'COT' after Ward 'Cot'", new String[] {"ward", "Cot", "room", "COT"}, path(2, 3)),
                new Case("Bed named Bed", new String[] {"bed", "Bed"}),
                new Case("Bed named bed", new String[] {"bed", "bed"}),
                new Case("Bed named 'Bed' with a no-break space", new String[] {"bed", "Bed "}),
                new Case("Doctor named Consultant", new String[] {"doctor", "Consultant"}),
                // standard names are reserved in any case, spacing or Unicode form
                new Case("Spacing and case of another name", new String[] {"ward", "  admission    TYPE "},
                        path(2, 2)),
                new Case("Ideographic and no-break spaces", new String[] {"ward", "Admission　 type"},
                        path(2, 2)),
                new Case("Name held by a field in a switched-off section", new String[] {"doctor", "Payer"},
                        path(3, 0)),
                new Case("Capital sharp s and ss", new String[] {"ward", "ß", "room", "SS"}, path(2, 3)),
                new Case("Final sigma", new String[] {"ward", "ας", "room", "ΑΣ"}, path(2, 3)),
                new Case("Composed and decomposed accent", new String[] {"ward", "café", "room", "café"},
                        path(2, 3)),
                // accepted limits of the key (NFC only, no look-alike letters)
                new Case("SpO2 with a plain two is not SpO₂", new String[] {"spo2", "SpO2"}),
                new Case("Cyrillic letter is not the Latin one", new String[] {"ward", "А", "room", "A"}),
                // own name
                new Case("SpO₂ in lower case on its own field", new String[] {"spo2", "spo₂"}),
                new Case("Own name with extra spaces", new String[] {"lengthOfStay", " length   of stay "}),
                // two fields with the same new label: the later one only
                new Case("Same new label in two sections", new String[] {"ward", "Zed", "doctor", "zed"}, path(3, 0)),
                new Case("Same new label three times", new String[] {"ward", "X", "room", "x", "admissionType", "X"},
                        path(2, 3), path(2, 4)),
                new Case("Same new label in the header and the billing section",
                        new String[] {"bed", "Ref", "payer", " REF"}, path(6, 2)),
                // only one error per label path
                new Case("Name of two other fields is still one error", new String[] {"diagnosis", "Allergies",
                    "room", "Allergies"}, path(2, 3), path(5, 0)),
                // a label that fails another rule gets that error only
                new Case("Direction mark inside another field's name", new String[] {"diagnosis", "Allergies‮"}),
                new Case("Zero-width space inside another field's name", new String[] {"diagnosis", "All​ergies"}),
                new Case("Patient name given another field's name", new String[] {"name", "Bed"}),
                new Case("Safety field given another field's name", new String[] {"allergies", "Bed"}),
                new Case("Label too long", new String[] {"diagnosis", "a".repeat(41)}),
                new Case("Empty label", new String[] {"diagnosis", ""}),
                new Case("Only white space", new String[] {"diagnosis", "   "}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void v15ByTheExamplesTable(Case row) {
        assertThat(v15Paths(labelled(row.labels()))).containsExactlyInAnyOrder(row.v15());
    }

    @Test
    void theErrorIsOnTheLaterFieldAndTheFirstKeepsItsLabel() {
        List<Problem> problems = check(labelled("ward", "Zed", "doctor", "zed"));
        assertThat(problems).containsExactly(new Problem(15, path(3, 0), MESSAGE));
    }

    @Test
    void theOrderInTheBodyDecidesNotTheOrderOfTheCatalogue() {
        // Doctor moves into the header, ahead of Ward: now Ward is the later field
        CardSetup moved = CardSetupValidatorTest.move(labelled("ward", "Zed", "doctor", "zed"), "doctor", "header");
        List<Problem> problems = check(moved);
        assertThat(problems).extracting(Problem::path).containsExactly(path(2, 2));
    }

    @Test
    void aSwitchedOffFieldStillReservesItsStandardNameAndStillCarriesItsOwnError() {
        CardSetup setup = field(labelled("diagnosis", "Mobile"), "mobile",
                f -> new FieldPlacement(f.key(), false, ""));
        assertThat(check(setup)).containsExactly(new Problem(15, path(5, 0), MESSAGE));

        // the label of a field that is off is still taken: Diagnosis (later) may not reuse the label of Room (off)
        CardSetup offField = field(labelled("room", "Gone"), "room", f -> new FieldPlacement(f.key(), false, f.label()));
        CardSetup clash = field(offField, "diagnosis", f -> new FieldPlacement(f.key(), true, "gone"));
        assertThat(check(clash)).containsExactly(new Problem(15, path(5, 0), MESSAGE));
    }

    @Test
    void aSwitchedOffSectionDoesNotHideItsFieldsFromTheRule() {
        CardSetup billingOff = section(labelled("doctor", "Pay"), "billing",
                s -> new Section(s.key(), false, s.fields()));
        CardSetup clash = field(billingOff, "payer", f -> new FieldPlacement(f.key(), true, "PAY"));
        assertThat(check(clash)).containsExactly(new Problem(15, path(6, 2), MESSAGE));
    }

    @Test
    void theMessageNeverEchoesWhatWasTyped() {
        List<Problem> problems = check(labelled("diagnosis", "Allergies"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).isEqualTo(MESSAGE).doesNotContain("Allergies");
    }

    @Test
    void v15SortsLastAndComesTogetherWithTheOtherErrors() {
        CardSetup base = labelled("diagnosis", "Allergies", "ward", "x".repeat(41), "name", "Bed");
        CardSetup bad = field(new CardSetup("wide", 9, base.sections(), base.actions()), "uhid",
                f -> new FieldPlacement(f.key(), false, "x"));
        List<Problem> problems = check(bad);
        assertThat(problems).extracting(Problem::rule).isSorted().containsExactly(1, 2, 7, 8, 10, 11, 15);
        assertThat(problems.get(problems.size() - 1)).isEqualTo(new Problem(15, path(5, 0), MESSAGE));
        // one error per label path
        assertThat(problems).filteredOn(p -> p.path().endsWith(".label")).extracting(Problem::path).doesNotHaveDuplicates();
    }

    @Test
    void v15IsTheOnlyErrorOfASetupThatBreaksNothingElse() {
        List<Problem> problems = check(labelled("ipNumber", "UHID"));
        assertThat(problems).containsExactly(new Problem(15, path(0, 4), MESSAGE));
        assertThat(problems.get(0).rule()).isEqualTo(15);
    }

    @Test
    void v15ErrorsKeepTheOrderOfTheBody() {
        List<Problem> problems = check(labelled("payer", "Bed", "diagnosis", "Doctor", "ipNumber", "Ward"));
        assertThat(problems).extracting(Problem::path).containsExactly(path(0, 4), path(5, 0), path(6, 2));
    }

    @Test
    void anUnknownOrRepeatedFieldIsNotTakenAsALabelHolder() {
        // V6 duplicate: the second Doctor sits later with a label that names Ward; it is V6, not V15
        List<Section> sections = new ArrayList<>(standard().sections());
        List<FieldPlacement> care = new ArrayList<>(sections.get(3).fields());
        care.add(new FieldPlacement("doctor", true, "Ward"));
        care.add(new FieldPlacement("nurse", true, "Bed"));
        sections.set(3, new Section("care", true, care));
        List<Problem> problems = check(new CardSetup("standard", 3, sections, standard().actions()));
        assertThat(problems).extracting(Problem::rule).containsOnly(6);
    }

    @Test
    void aStandardSetupAndTheTemplatesHaveNoV15Error() {
        assertThat(check(standard())).isEmpty();
        for (CardSetupDefaults.Template template : CardSetupDefaults.templates()) {
            CardSetup applied = new CardSetup(template.layout(), standard().cardsPerRow(), template.sections(),
                    template.actions());
            assertThat(check(applied)).as(template.key()).isEmpty();
        }
    }

    @Test
    void storedSetupsAreNotRepairedForV15() {
        // repair on read keeps both labels (ADR-015 D3 and A4): the stored duplicate stays readable
        CardSetup stored = labelled("diagnosis", "Allergies", "room", "Allergies");
        CardSetup repaired = CardSetupRepair.repair(stored, CardSetupValidatorTest.TEXT);
        List<String> labels = repaired.sections().stream().flatMap(s -> s.fields().stream())
                .filter(f -> f.key().equals("diagnosis") || f.key().equals("room")).map(FieldPlacement::label).toList();
        assertThat(labels).containsExactlyInAnyOrder("Allergies", "Allergies");
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("keys")
    void theComparisonKey(String typed, String key) {
        assertThat(CardSetupValidator.nameKey(typed)).isEqualTo(key);
    }

    static Stream<Arguments> keys() {
        return Stream.of(
                Arguments.of("Bed", "bed"),
                Arguments.of("  Admission \t\t TYPE  ", "admission type"),
                Arguments.of("A\u000B\u000C\r\nB", "a b"),
                Arguments.of("　x ", "x"),
                Arguments.of("a  b", "a b"),
                Arguments.of("a  b", "a b"),
                Arguments.of("ß", "ss"),
                Arguments.of("SS", "ss"),
                Arguments.of("ΑΣ", "ας"),
                Arguments.of("ας", "ας"),
                Arguments.of("café", "café"),
                Arguments.of("SpO₂", "spo₂"),
                Arguments.of("   ", ""));
    }
}
