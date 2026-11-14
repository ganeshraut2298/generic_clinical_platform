package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrInputs.SectionSettingsInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-75-4 and SAH-75-6 (ADR-026 D2): a department's section set is the latest version's sections, plus the current
 * starter layout's sections, plus live forms. A starter section a published department lacks is offered hidden and not
 * required, at its starter position; a department never published gets it with the starter's own settings. Pure reads.
 */
class EmrLayoutsUnionTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private final EmrDepartmentRepository departments = mock(EmrDepartmentRepository.class);
    private EmrLayouts layouts;

    private static LayoutSection lib(String key, int position, boolean shown, boolean required) {
        return new LayoutSection(key, "LIBRARY", key, "SPECIALTY", "", "ASSESSMENT", position, shown, required, true,
                false, false, List.of("DOCTOR"), false, null, List.of());
    }

    private static LayoutSection locked(String key, int position) {
        return new LayoutSection(key, "LIBRARY", key, "COMMON", "", "ASSESSMENT", position, true, false, true, false,
                false, List.of("DOCTOR"), true, "Cannot be hidden.", List.of());
    }

    /** Starter version 2: vitals, complaints, local_exam (shown, optional), allergy (locked), rx. */
    private static List<LayoutSection> starterV2() {
        return List.of(lib("vitals", 1, true, true), lib("complaints", 2, true, true), lib("local_exam", 3, true, false),
                locked("allergy", 4), lib("rx", 5, true, true));
    }

    @BeforeEach
    void setUp() {
        String v2 = EmrTestData.JSON.writeLayout(starterV2());
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(new StarterLayoutRow("gm", 2, v2)));
        when(library.starterLayout("gm", 2)).thenReturn(Optional.of(new StarterLayoutRow("gm", 2, v2)));
        layouts = new EmrLayouts(library, departments, EmrTestData.MAPPER);
    }

    private Department published(UUID id, String draft) {
        return new Department(new EmrDepartmentRepository.DepartmentRow(id, "gm", "General medicine", "GM", "#1D5FA8",
                "gm", "STARTER", null, List.of(), draft, 1, 4), "gm", "General medicine", "GM", "#1D5FA8", "gm",
                "STARTER", null, List.of(), 1, 4, draft);
    }

    private void version1(UUID id) {
        String v1 = EmrTestData.JSON.writeLayout(List.of(lib("vitals", 1, true, true), lib("complaints", 2, true, false),
                locked("allergy", 3), lib("rx", 4, true, false)));
        when(departments.latestVersion(TENANT, id)).thenReturn(Optional.of(new EmrDepartmentRepository.VersionRow(
                UUID.randomUUID(), id, 1, v1, Instant.now(), UUID.randomUUID())));
    }

    @Test
    void aPublishedDepartmentsSetHoldsTheStarterSectionItLacksHiddenOptionalAndAtItsStarterPosition() {
        UUID id = UUID.randomUUID();
        version1(id);

        List<LayoutSection> base = layouts.base(TENANT, published(id, null));

        assertThat(base).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "local_exam", "allergy",
                "rx");
        LayoutSection offered = base.get(2);
        assertThat(offered.shown()).isFalse();
        assertThat(offered.required()).isFalse();
        assertThat(offered.print()).isTrue();
        assertThat(base).extracting(LayoutSection::position).containsExactly(1, 2, 3, 4, 5);
        // what the owner already set in the version is not touched by the union
        assertThat(base.get(1).required()).isFalse();
        assertThat(base.get(4).required()).isFalse();
    }

    @Test
    void doctorsStillSeeOnlyTheLatestVersion() {
        UUID id = UUID.randomUUID();
        version1(id);

        EmrLayouts.Published seen = layouts.published(TENANT, published(id, null)).orElseThrow();

        assertThat(seen.sections()).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "allergy",
                "rx");
        assertThat(seen.layoutSource()).isEqualTo("PUBLISHED");
    }

    @Test
    void theConfiguratorOfAPublishedDepartmentWithNoDraftListsTheOfferedRowToo() {
        UUID id = UUID.randomUUID();
        version1(id);

        EmrLayouts.Working working = layouts.working(TENANT, published(id, null));

        assertThat(working.source()).isEqualTo("PUBLISHED");
        assertThat(working.sections()).extracting(LayoutSection::key).containsExactly("vitals", "complaints",
                "local_exam", "allergy", "rx");
        assertThat(working.sections().get(2).shown()).isFalse();
        assertThat(working.sections()).extracting(LayoutSection::position).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void aDraftSavedBeforeTheStarterGainedTheSectionGetsItAddedWithoutLosingTheOwnersChanges() {
        UUID id = UUID.randomUUID();
        version1(id);
        String draft = EmrTestData.JSON.writeSettings(List.of(
                new SectionSettings("rx", "TREATMENT", true, true, true, false, false, List.of("DOCTOR")),
                lib("vitals", 1, true, true).settings(), lib("complaints", 2, true, false).settings(),
                locked("allergy", 3).settings()));

        EmrLayouts.Working working = layouts.working(TENANT, published(id, draft));

        assertThat(working.source()).isEqualTo("DRAFT");
        assertThat(working.sections()).extracting(LayoutSection::key).containsExactly("rx", "vitals", "complaints",
                "local_exam", "allergy");
        assertThat(working.sections().get(0).part()).isEqualTo("TREATMENT");
        assertThat(working.sections().get(3).shown()).isFalse();
    }

    @Test
    void aDepartmentNeverPublishedReadsTheStarterAndAnOldDraftGetsTheNewSectionWithTheStartersOwnSettings() {
        Department starter = new Department(null, "gm", "General medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of(), 0, 0, EmrTestData.JSON.writeSettings(List.of(lib("vitals", 1, true, true).settings(),
                        lib("complaints", 2, true, true).settings(), locked("allergy", 3).settings(),
                        lib("rx", 4, true, true).settings())));

        EmrLayouts.Working working = layouts.working(TENANT, starter);

        assertThat(working.sections()).extracting(LayoutSection::key).containsExactly("vitals", "complaints",
                "local_exam", "allergy", "rx");
        assertThat(working.sections().get(2).shown()).isTrue();
        assertThat(layouts.base(TENANT, starter)).extracting(LayoutSection::key).containsExactly("vitals",
                "complaints", "local_exam", "allergy", "rx");
    }

    @Test
    void theSaveCheckAcceptsTheFullUnionAndRefusesTheOldSetAsASectionSetMismatch() {
        UUID id = UUID.randomUUID();
        version1(id);
        List<LayoutSection> base = layouts.base(TENANT, published(id, null));
        List<SectionSettingsInput> full = base.stream().map(s -> input(s.settings())).toList();
        List<SectionSettingsInput> old = full.stream().filter(s -> !"local_exam".equals(s.key())).toList();

        assertThat(EmrLayoutRules.validate(full, base)).hasSize(5);
        assertThatThrownBy(() -> EmrLayoutRules.validate(old, base)).isInstanceOfSatisfying(EmrProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("section-set-mismatch"));
    }

    private static SectionSettingsInput input(SectionSettings s) {
        return new SectionSettingsInput(s.key(), s.part(), s.shown(), s.required(), s.print(), s.copy(), s.collapsed(),
                s.whoCanFill());
    }

    // ---- the insertion rule ----

    private static List<String> keys(List<LayoutSection> sections) {
        return sections.stream().map(LayoutSection::key).toList();
    }

    @Test
    void aMissingSectionGoesAfterTheNearestEarlierStarterSectionThatIsPresentElseBeforeTheNearestLaterOne() {
        List<LayoutSection> starter = List.of(lib("a", 1, true, false), lib("b", 2, true, false),
                lib("c", 3, true, false), lib("d", 4, true, false));

        assertThat(keys(EmrLayouts.withStarterSections(List.of(lib("c", 1, true, false)), starter)))
                .containsExactly("a", "b", "c", "d");
        assertThat(keys(EmrLayouts.withStarterSections(List.of(lib("x", 1, true, false), lib("b", 2, true, false)),
                starter))).containsExactly("x", "a", "b", "c", "d");
        assertThat(keys(EmrLayouts.withStarterSections(List.of(lib("d", 1, true, false), lib("a", 2, true, false)),
                starter))).containsExactly("d", "a", "b", "c");
    }

    @Test
    void aSectionThatCannotBeHiddenIsOfferedShownAndEveryOtherOneHiddenAndOptional() {
        List<LayoutSection> merged = EmrLayouts.withStarterSections(List.of(lib("vitals", 1, true, true)),
                List.of(lib("vitals", 1, true, true), locked("allergy", 2), lib("rx", 3, true, true)));

        assertThat(merged.get(1).shown()).isTrue();
        assertThat(merged.get(2).shown()).isFalse();
        assertThat(merged.get(2).required()).isFalse();
    }

    // ---- presentation and alert (the library flags of 0.7.0) ----

    @Test
    void aStoredLayoutWithNoPresentationOrAlertReadsDefaultAndNotAlert() {
        String old = "{\"schemaVersion\":1,\"sections\":[{\"key\":\"x\",\"source\":\"LIBRARY\",\"name\":\"X\","
                + "\"kind\":\"SPECIALTY\",\"fields\":[{\"key\":\"f\",\"label\":\"F\",\"type\":\"SINGLE_CHOICE\","
                + "\"options\":[{\"key\":\"o1\",\"label\":\"One\",\"isNone\":false}]}]}]}";

        FieldDefinition field = EmrTestData.JSON.readLayout(old).get(0).fields().get(0);

        assertThat(field.presentation()).isEqualTo("DEFAULT");
        assertThat(field.options().get(0).alert()).isFalse();
    }

    @Test
    void chipsAndAlertOptionsSurviveAWriteAndARead() {
        FieldDefinition habits = new FieldDefinition("habits", "Habits", "MULTI_CHOICE", EmrFieldType.valueKind("MULTI_CHOICE"),
                "", false, "FIELD", null, false,
                List.of(new FieldOption("gutkha", "Gutkha", false, true), new FieldOption("none", "None", true, false)),
                List.of(), null, null, null, "CHIPS");
        LayoutSection section = new LayoutSection("dental_hx", "LIBRARY", "Dental history and habits", "SPECIALTY", "",
                "ASSESSMENT", 1, true, false, false, false, false, List.of("DOCTOR"), false, null, List.of(habits));

        FieldDefinition read = EmrTestData.JSON.readLayout(EmrTestData.JSON.writeLayout(List.of(section))).get(0)
                .fields().get(0);

        assertThat(read.presentation()).isEqualTo("CHIPS");
        assertThat(read.options().get(0).alert()).isTrue();
        assertThat(read.options().get(1).isNone()).isTrue();
        assertThat(read.options().get(1).alert()).isFalse();
    }

    @Test
    void aPresentationOutsideTheTwoKnownOnesReadsDefault() {
        FieldDefinition field = new FieldDefinition("f", "F", "TEXT", EmrFieldType.valueKind("TEXT"), "", false, "NONE",
                null, false, List.of(), List.of(), null, null, null, "SPARKLES");

        assertThat(field.presentation()).isEqualTo("DEFAULT");
        assertThat(field.withPresentation("CHIPS").presentation()).isEqualTo("CHIPS");
    }
}
