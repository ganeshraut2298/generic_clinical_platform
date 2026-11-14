package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Settings;
import com.sahaj.opd.application.emr.EmrFormModel.UseIn;
import com.sahaj.opd.application.emr.EmrFormRepository.LiveForm;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-59 / ADR-022 D11: live clinic forms as sections of department layouts and of records. */
class EmrLayoutsFormsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String FOOT = "f_abcdefghij";
    private static final String PRE = "f_klmnopqrst";

    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private final EmrDepartmentRepository departments = mock(EmrDepartmentRepository.class);
    private final EmrFormRepository forms = mock(EmrFormRepository.class);
    private EmrLayouts layouts;
    private final Department gm = new Department(null, "gm", "General medicine", "GM", "#1D5FA8", "gm", "STARTER",
            null, List.of(), 0, 0, null);

    private static LayoutSection lib(String key, int position, String part) {
        return new LayoutSection(key, "LIBRARY", key, "COMMON", "", part, position, true, false, true, false, false,
                List.of("DOCTOR"), false, null, List.of());
    }

    private static Field text(String key) {
        return new Field(key, "SHORT_TEXT", "Label", null, null, null, false, 3, null, null, null, null, null,
                List.of(), true, false, List.of("DOCTOR"), null, null, null, null);
    }

    private static LiveForm live(String key, String name, int version, String part, List<String> roles)
            throws Exception {
        Settings settings = new Settings(new UseIn(List.of("gm"), List.of()), part, List.of("NEW_CONSULT"), roles, "EN",
                true);
        return new LiveForm(key, name, version, EmrTestData.MAPPER.writeValueAsString(
                new Definition(1, name, settings, List.of(text("a")))));
    }

    @BeforeEach
    void setUp() throws Exception {
        String starter = EmrTestData.JSON.writeLayout(List.of(lib("vitals", 1, "ASSESSMENT"),
                lib("complaints", 2, "ASSESSMENT"), lib("rx", 3, "TREATMENT")));
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(new StarterLayoutRow("gm", 1, starter)));
        when(library.starterLayout("gm", 1)).thenReturn(Optional.of(new StarterLayoutRow("gm", 1, starter)));
        layouts = new EmrLayouts(library, departments, EmrTestData.MAPPER, forms);
    }

    @Test
    void aLiveFormThatTheLayoutDoesNotHoldIsAppendedAtTheEndWithTheDefaults() throws Exception {
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of(live(FOOT, "Foot check", 2, "TREATMENT",
                List.of("PATIENT", "NURSE", "DOCTOR"))));

        List<LayoutSection> base = layouts.base(TENANT, gm);

        assertThat(base).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "rx", FOOT);
        LayoutSection form = base.get(3);
        assertThat(form.formSection()).isTrue();
        assertThat(form.source()).isEqualTo("FORM");
        assertThat(form.formKey()).isEqualTo(FOOT);
        assertThat(form.name()).isEqualTo("Foot check");
        assertThat(form.kind()).isEqualTo("SPECIALTY");
        assertThat(form.description()).isEqualTo("Custom form");
        assertThat(form.part()).isEqualTo("TREATMENT");
        assertThat(form.position()).isEqualTo(4);
        assertThat(form.shown()).isTrue();
        assertThat(form.required()).isFalse();
        assertThat(form.print()).isTrue();
        assertThat(form.copy()).isFalse();
        assertThat(form.collapsed()).isFalse();
        assertThat(form.hideLocked()).isFalse();
        assertThat(form.whoCanFill()).containsExactly("DOCTOR", "NURSE");
        assertThat(form.fields()).isEmpty();
        assertThat(form.form()).isNull();
        assertThat(form.formVersionNo()).isNull();
    }

    @Test
    void whatDoctorsSeeCarriesTheLiveVersionAndItsDefinition() throws Exception {
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of(live(FOOT, "Foot check", 3, "ASSESSMENT",
                List.of("DOCTOR"))));

        Published published = layouts.published(TENANT, gm).orElseThrow();

        LayoutSection form = published.sections().get(3);
        assertThat(form.formVersionNo()).isEqualTo(3);
        assertThat(form.form().name()).isEqualTo("Foot check");
        assertThat(form.form().fields()).hasSize(1);
    }

    @Test
    void aFormSectionTheVersionHoldsKeepsTheOwnersSettingsAndAFormNoLongerLiveIsDropped() throws Exception {
        UUID departmentId = UUID.randomUUID();
        Department added = new Department(new EmrDepartmentRepository.DepartmentRow(departmentId, "d_aaaaaaaaaa",
                "Clinic", "CL", "#2E7D32", "gm", "COPY", null, List.of(), null, 2, 3), "d_aaaaaaaaaa", "Clinic", "CL",
                "#2E7D32", "gm", "COPY", null, List.of(), 2, 3, null);
        LayoutSection kept = new LayoutSection(FOOT, "FORM", "Old name", "SPECIALTY", "Custom form", "ASSESSMENT", 2,
                false, true, true, true, true, List.of("DOCTOR", "NURSE"), false, null, List.of(), FOOT, null, null);
        LayoutSection retired = new LayoutSection(PRE, "FORM", "Pre-op", "SPECIALTY", "Custom form", "ASSESSMENT", 3,
                true, false, true, false, false, List.of("DOCTOR"), false, null, List.of(), PRE, null, null);
        String layout = EmrTestData.JSON.writeLayout(List.of(lib("vitals", 1, "ASSESSMENT"), kept, retired));
        when(departments.latestVersion(TENANT, departmentId)).thenReturn(Optional.of(
                new EmrDepartmentRepository.VersionRow(UUID.randomUUID(), departmentId, 2, layout, Instant.now(),
                        UUID.randomUUID())));
        when(forms.liveFor(TENANT, "d_aaaaaaaaaa", null)).thenReturn(List.of(live(FOOT, "Foot check", 2, "ASSESSMENT",
                List.of("DOCTOR"))));

        List<LayoutSection> base = layouts.base(TENANT, added);

        // ADR-026 D2: the set is the version's sections plus the starter's, so the form sits after the starter sections
        assertThat(base).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "rx", FOOT);
        LayoutSection form = base.get(3);
        assertThat(form.name()).isEqualTo("Foot check");
        assertThat(form.shown()).isFalse();
        assertThat(form.required()).isTrue();
        assertThat(form.copy()).isTrue();
        assertThat(form.collapsed()).isTrue();
        assertThat(form.whoCanFill()).containsExactly("DOCTOR", "NURSE");
        assertThat(form.position()).isEqualTo(4);
    }

    @Test
    void aFormSectionIsStoredAsSettingsOnlyAndReadBackAsAFormSection() throws Exception {
        LayoutSection withDefinition = new LayoutSection(FOOT, "FORM", "Foot check", "SPECIALTY", "Custom form",
                "ASSESSMENT", 1, true, false, true, false, false, List.of("DOCTOR"), false, null, List.of(), FOOT, 4,
                EmrTestData.MAPPER.readValue(live(FOOT, "Foot check", 4, "ASSESSMENT", List.of("DOCTOR"))
                        .definition(), Definition.class));

        String stored = EmrTestData.JSON.writeLayout(List.of(withDefinition, lib("vitals", 2, "ASSESSMENT")));

        assertThat(stored).contains("\"formKey\":\"" + FOOT + "\"").doesNotContain("formVersionNo")
                .doesNotContain("\"form\":");
        // library sections carry no formKey at all
        assertThat(EmrTestData.MAPPER.readTree(stored).path("sections").get(1).has("formKey")).isFalse();
        List<LayoutSection> read = EmrTestData.JSON.readLayout(stored);
        assertThat(read.get(0).formSection()).isTrue();
        assertThat(read.get(0).formKey()).isEqualTo(FOOT);
        assertThat(read.get(0).form()).isNull();
        assertThat(read.get(1).formSection()).isFalse();
    }

    @Test
    void aSavedDraftIsReconciledWithTodaysForms() throws Exception {
        List<SectionSettings> draft = List.of(lib("vitals", 1, "ASSESSMENT").settings(),
                new SectionSettings(PRE, "ASSESSMENT", true, false, true, false, false, List.of("DOCTOR")),
                lib("complaints", 2, "ASSESSMENT").settings());
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of(live(FOOT, "Foot check", 1, "ASSESSMENT",
                List.of("DOCTOR"))));

        List<SectionSettings> reconciled = layouts.reconcileForms(draft, layouts.base(TENANT, gm));

        assertThat(reconciled).extracting(SectionSettings::key).containsExactly("vitals", "complaints", FOOT);
    }

    @Test
    void theWorkingLayoutOfADraftShowsTheFormWithoutItsDefinition() throws Exception {
        String draft = EmrTestData.JSON.writeSettings(List.of(lib("vitals", 1, "ASSESSMENT").settings(),
                lib("complaints", 2, "ASSESSMENT").settings(), lib("rx", 3, "TREATMENT").settings()));
        Department withDraft = new Department(null, "gm", "General medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of(), 0, 0, draft);
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of(live(FOOT, "Foot check", 1, "ASSESSMENT",
                List.of("DOCTOR"))));

        EmrLayouts.Working working = layouts.working(TENANT, withDraft);

        assertThat(working.source()).isEqualTo("DRAFT");
        assertThat(working.sections()).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "rx",
                FOOT);
        assertThat(working.sections().get(3).form()).isNull();
    }

    private static EmrRecordRepository.RecordRow record(String formRefs) {
        return new EmrRecordRepository.RecordRow(UUID.randomUUID(), "OPD_VISIT", UUID.randomUUID(), null, null, null,
                UUID.randomUUID(), UUID.randomUUID(), "gm", "General medicine", "gm", "STARTER_LAYOUT", null, 1,
                "CLINIC_DEFAULT", "DRAFT", null, "{}", null, null, false, null, 0, Instant.now(), null, null,
                formRefs);
    }

    @Test
    void aRecordSnapshotUsesItsOwnFormVersionAndKeepsTheOrderByPosition() throws Exception {
        String spread = EmrTestData.JSON.writeLayout(List.of(lib("vitals", 1, "ASSESSMENT"),
                lib("complaints", 3, "ASSESSMENT"), lib("rx", 5, "TREATMENT")));
        when(library.starterLayout("gm", 1)).thenReturn(Optional.of(new StarterLayoutRow("gm", 1, spread)));
        String refs = EmrFormRefs.write(EmrTestData.MAPPER, List.of(new EmrFormRefs.Ref(FOOT, 2, "ASSESSMENT", 2,
                true, true, false, false, List.of("DOCTOR"))));
        when(forms.formVersion(TENANT, FOOT, 2)).thenReturn(Optional.of(live(FOOT, "Foot check", 2, "ASSESSMENT",
                List.of("DOCTOR"))));

        Snapshot snapshot = layouts.snapshot(TENANT, record(refs));

        assertThat(snapshot.sections()).extracting(LayoutSection::key).containsExactly("vitals", FOOT, "complaints",
                "rx");
        assertThat(snapshot.sections()).extracting(LayoutSection::position).containsExactly(1, 2, 3, 4);
        LayoutSection form = snapshot.sections().get(1);
        assertThat(form.required()).isTrue();
        assertThat(form.formVersionNo()).isEqualTo(2);
        assertThat(form.form().name()).isEqualTo("Foot check");
    }

    @Test
    void aRecordWithoutFormsGetsTheStoredLayoutUnchanged() {
        Snapshot snapshot = layouts.snapshot(TENANT, record("[]"));

        assertThat(snapshot.sections()).extracting(LayoutSection::position).containsExactly(1, 2, 3);
        verify(forms, never()).formVersion(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aRecordThatPointsAtAMissingFormVersionIsAServerError() {
        String refs = EmrFormRefs.write(EmrTestData.MAPPER, List.of(new EmrFormRefs.Ref(FOOT, 9, "ASSESSMENT", 2,
                false, true, false, false, List.of())));
        when(forms.formVersion(TENANT, FOOT, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layouts.snapshot(TENANT, record(refs))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNewIpdRoundAlsoGetsTheFormsMadeForIpdWardsAndAnOpdVisitDoesNot() throws Exception {
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of());
        when(forms.liveFor(TENANT, null, "IPD_WARD")).thenReturn(List.of(live(PRE, "Morse fall risk", 1, "ASSESSMENT",
                List.of("NURSE"))));
        List<LayoutSection> published = layouts.published(TENANT, gm).orElseThrow().sections();

        List<LayoutSection> ipd = layouts.sectionsAtOpen(TENANT, published, "IPD_ROUND");
        List<LayoutSection> opd = layouts.sectionsAtOpen(TENANT, published, "OPD_VISIT");

        assertThat(ipd).extracting(LayoutSection::key).contains(PRE);
        assertThat(opd).extracting(LayoutSection::key).doesNotContain(PRE);
        List<EmrFormRefs.Ref> refs = EmrFormRefs.fromSections(ipd);
        assertThat(refs).singleElement().satisfies(ref -> {
            assertThat(ref.formKey()).isEqualTo(PRE);
            assertThat(ref.versionNo()).isEqualTo(1);
            assertThat(ref.position()).isEqualTo(4);
        });
    }

    @Test
    void aFormAlreadyInTheDepartmentIsNotAddedAgainForItsPlace() throws Exception {
        LiveForm both = live(FOOT, "Foot check", 1, "ASSESSMENT", List.of("DOCTOR"));
        when(forms.liveFor(TENANT, "gm", null)).thenReturn(List.of(both));
        when(forms.liveFor(TENANT, null, "EMERGENCY")).thenReturn(List.of(both));
        List<LayoutSection> published = layouts.published(TENANT, gm).orElseThrow().sections();

        List<LayoutSection> er = layouts.sectionsAtOpen(TENANT, published, "ER_ASSESSMENT");

        assertThat(er.stream().filter(s -> s.key().equals(FOOT)).count()).isEqualTo(1);
    }

    @Test
    void withoutTheFormRepositoryLayoutsAreUnchanged() {
        EmrLayouts plain = new EmrLayouts(library, departments, EmrTestData.MAPPER);

        assertThat(plain.base(TENANT, gm)).extracting(LayoutSection::key).containsExactly("vitals", "complaints", "rx");
        assertThat(plain.placeForms(TENANT, "IPD_WARD", List.of())).isEmpty();
    }

    @Test
    void formRefsRoundTripAsSmallJson() {
        List<EmrFormRefs.Ref> refs = List.of(new EmrFormRefs.Ref(FOOT, 2, "TREATMENT", 5, true, false, true, true,
                List.of("DOCTOR", "NURSE")));

        String json = EmrFormRefs.write(EmrTestData.MAPPER, refs);

        assertThat(EmrFormRefs.parse(EmrTestData.MAPPER, json)).isEqualTo(refs);
        assertThat(EmrFormRefs.parse(EmrTestData.MAPPER, "[]")).isEmpty();
        assertThat(EmrFormRefs.parse(EmrTestData.MAPPER, null)).isEmpty();
        assertThatThrownBy(() -> EmrFormRefs.parse(EmrTestData.MAPPER, "{}")).isInstanceOf(IllegalStateException.class);
    }
}
