package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrDepartmentRepository.DepartmentRow;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.NewDepartment;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.VersionRow;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentCreateInput;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentSource;
import com.sahaj.opd.application.emr.EmrInputs.LayoutInput;
import com.sahaj.opd.application.emr.EmrInputs.SectionSettingsInput;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrViews.DepartmentList;
import com.sahaj.opd.application.emr.EmrViews.DepartmentView;
import com.sahaj.opd.application.emr.EmrViews.DraftSaveResult;
import com.sahaj.opd.application.emr.EmrViews.PublishResult;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

class EmrDepartmentServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID DEPT_ID = UUID.randomUUID();
    private static final ClinicUser OWNER = new ClinicUser(OWNER_USER, TENANT, "OWNER");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private final EmrDepartmentRepository departments = mock(EmrDepartmentRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ActorDirectoryPort actors = mock(ActorDirectoryPort.class);
    private final AuditLog audit = mock(AuditLog.class);

    private EmrDepartmentService service;
    private List<LayoutSection> gm;

    @BeforeEach
    void setUp() {
        gm = new ArrayList<>(List.of(
                EmrTestData.section("vitals", true, false, EmrTestData.field("pulse", "NUMBER", false)),
                EmrTestData.section("allergy", "COMMON", "ASSESSMENT", true, true, true,
                        EmrTestData.field("allergen", "TEXT", true)),
                EmrTestData.section("rx", "COMMON", "TREATMENT", true, false, false,
                        EmrTestData.field("drugs", "TABLE", false))));
        gm = EmrLayoutRules.apply(EmrLayoutRules.settings(gm), gm);
        List<LayoutSection> dental = List.of(EmrTestData.section("vitals", true, false));
        when(library.starters()).thenReturn(List.of(starter("gm", "General Medicine", 1),
                starter("dental", "Dental", 2)));
        when(library.starter("gm")).thenReturn(Optional.of(starter("gm", "General Medicine", 1)));
        when(library.starter("dental")).thenReturn(Optional.of(starter("dental", "Dental", 2)));
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(
                new StarterLayoutRow("gm", 1, EmrTestData.JSON.writeLayout(gm))));
        when(library.currentStarterLayout("dental")).thenReturn(Optional.of(
                new StarterLayoutRow("dental", 1, EmrTestData.JSON.writeLayout(dental))));
        when(library.sections()).thenReturn(List.of(
                new LibrarySection("vitals", "VITALS", "COMMON", "", false, List.of(), List.of("gm")),
                new LibrarySection("allergy", "ALLERGY", "COMMON", "", true, List.of(), List.of("gm")),
                new LibrarySection("rx", "RX", "COMMON", "", false, List.of(), List.of("gm"))));
        when(access.canEditSetup(OWNER)).thenReturn(true);
        EmrLayouts layouts = new EmrLayouts(library, departments, EmrTestData.MAPPER);
        service = new EmrDepartmentService(access, library, departments, layouts, team, actors, audit,
                new SecureRandom());
    }

    private static StarterRow starter(String key, String name, int position) {
        return new StarterRow(key, name, key.substring(0, 2).toUpperCase(), "#1D5FA8", null, false, false, position, 1);
    }

    private static SectionSettingsInput in(String key, String part, boolean shown, boolean required) {
        return new SectionSettingsInput(key, part, shown, required, false, false, false, List.of("DOCTOR"));
    }

    private List<SectionSettingsInput> asInput(List<EmrModel.SectionSettings> settings) {
        List<SectionSettingsInput> input = new ArrayList<>();
        for (EmrModel.SectionSettings s : settings) {
            input.add(new SectionSettingsInput(s.key(), s.part(), s.shown(), s.required(), s.print(), s.copy(),
                    s.collapsed(), s.whoCanFill()));
        }
        return input;
    }

    private List<SectionSettingsInput> starterBodyWithVitalsHidden() {
        List<SectionSettingsInput> body = asInput(EmrLayoutRules.settings(gm));
        body.set(0, in("vitals", "ASSESSMENT", false, false));
        return body;
    }

    private DepartmentRow row(String key, String name, int version, long revision, String draft) {
        return new DepartmentRow(DEPT_ID, key, name, "GM", "#1D5FA8", "gm", "STARTER", null, List.of(), draft, version,
                revision);
    }

    // ---- list and get ----

    @Test
    void aNewClinicSeesTheStarterDepartmentsAsTemplatesWithCounts() {
        when(departments.findAll(TENANT)).thenReturn(List.of());

        DepartmentList list = service.list(OWNER);

        assertThat(list.canEdit()).isTrue();
        assertThat(list.departments()).extracting(EmrViews.DepartmentSummary::departmentKey)
                .containsExactly("gm", "dental");
        EmrViews.DepartmentSummary first = list.departments().get(0);
        assertThat(first.status()).isEqualTo("TEMPLATE");
        assertThat(first.publishedVersion()).isZero();
        assertThat(first.hasDraft()).isFalse();
        assertThat(first.sectionsTotal()).isEqualTo(3);
        assertThat(first.sectionsOn()).isEqualTo(3);
        assertThat(first.requiredOn()).isEqualTo(1);
        assertThat(first.doctorCount()).isZero();
    }

    @Test
    void theListShowsADraftCountsAndAddedDepartmentsAfterTheStarters() {
        List<EmrModel.SectionSettings> draft = new ArrayList<>(EmrLayoutRules.settings(gm));
        draft.set(0, new EmrModel.SectionSettings("vitals", "ASSESSMENT", false, false, false, false, false,
                List.of("DOCTOR")));
        DepartmentRow saved = row("gm", "General Medicine", 0, 1, EmrTestData.JSON.writeSettings(draft));
        DepartmentRow added = new DepartmentRow(UUID.randomUUID(), "d_abcdefghij", "Annexe", "AN", "#2E7D32", "gm",
                "COPY", "gm", List.of("Room 1"), EmrTestData.JSON.writeSettings(EmrLayoutRules.settings(gm)), 0, 1);
        when(departments.findAll(TENANT)).thenReturn(List.of(added, saved));
        when(departments.doctorsByDepartment(TENANT)).thenReturn(Map.of(added.id(), Set.of(UUID.randomUUID())));

        DepartmentList list = service.list(OWNER);

        assertThat(list.departments()).extracting(EmrViews.DepartmentSummary::departmentKey)
                .containsExactly("gm", "dental", "d_abcdefghij");
        EmrViews.DepartmentSummary gmSummary = list.departments().get(0);
        assertThat(gmSummary.status()).isEqualTo("DRAFT");
        assertThat(gmSummary.hasDraft()).isTrue();
        assertThat(gmSummary.sectionsOn()).isEqualTo(2);
        EmrViews.DepartmentSummary annexe = list.departments().get(2);
        assertThat(annexe.rooms()).containsExactly("Room 1");
        assertThat(annexe.doctorCount()).isEqualTo(1);
        assertThat(annexe.hasDraft()).isTrue();
    }

    @Test
    void oneDepartmentShowsEverySectionHiddenOnesToo() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());

        DepartmentView view = service.get(OWNER, "gm");

        assertThat(view.layoutSource()).isEqualTo("STARTER");
        assertThat(view.revision()).isZero();
        assertThat(view.sections()).extracting(LayoutSection::key).containsExactly("vitals", "allergy", "rx");
        assertThat(view.sections().get(1).hideLocked()).isTrue();
        assertThat(view.canEdit()).isTrue();
    }

    @Test
    void anUnknownKeyIs404AndAMalformedKeyIs400() {
        when(departments.find(TENANT, "d_zzzzzzzzzz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OWNER, "d_zzzzzzzzzz")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("department-not-found"));
        assertThatThrownBy(() -> service.get(OWNER, "GM")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }

    // ---- Save draft ----

    @Test
    void theFirstSaveOfAStarterDepartmentCreatesItsRowAndAuditsTheChange() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty(),
                Optional.of(row("gm", "General Medicine", 0, 1, null)));
        when(departments.insert(eq(TENANT), any(NewDepartment.class), eq(OWNER_USER))).thenReturn(DEPT_ID);

        DraftSaveResult result = service.saveDraft(OWNER, "gm",
                new LayoutInput(0L, "EDIT", starterBodyWithVitalsHidden()));

        assertThat(result.outcome()).isEqualTo("SAVED");
        ArgumentCaptor<NewDepartment> created = ArgumentCaptor.forClass(NewDepartment.class);
        verify(departments).insert(eq(TENANT), created.capture(), eq(OWNER_USER));
        assertThat(created.getValue().key()).isEqualTo("gm");
        assertThat(created.getValue().sourceKind()).isEqualTo("STARTER");
        assertThat(created.getValue().starterKey()).isEqualTo("gm");
        assertThat(created.getValue().revision()).isEqualTo(1);
        assertThat(EmrTestData.JSON.readSettings(created.getValue().draftLayout()).get(0).shown()).isFalse();
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_DEPARTMENT_DRAFT_SAVED"), eq("EMR_DEPARTMENT"), eq(DEPT_ID), any(), after.capture());
        assertThat(after.getValue()).containsEntry("departmentKey", "gm").containsEntry("revision", 1L);
        assertThat((List<?>) after.getValue().get("changes")).hasSize(1);
    }

    @Test
    void aBodyEqualToWhatDoctorsSeeSavesNothingAndAuditsNothing() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());

        DraftSaveResult result = service.saveDraft(OWNER, "gm",
                new LayoutInput(0L, "EDIT", asInput(EmrLayoutRules.settings(gm))));

        assertThat(result.outcome()).isEqualTo("NOTHING_NEW");
        verify(departments, never()).insert(any(), any(), any());
        verify(departments, never()).saveDraft(any(), any(), anyLong(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void aSaveOnAnExistingDepartmentIsACompareAndSetOnTheRevision() {
        DepartmentRow existing = row("gm", "General Medicine", 0, 3, null);
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(existing));
        when(departments.saveDraft(eq(TENANT), eq(DEPT_ID), eq(3L), anyString(), eq(OWNER_USER))).thenReturn(true);

        service.saveDraft(OWNER, "gm", new LayoutInput(3L, "EDIT", starterBodyWithVitalsHidden()));

        verify(departments).saveDraft(eq(TENANT), eq(DEPT_ID), eq(3L), anyString(), eq(OWNER_USER));
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_DEPARTMENT_DRAFT_SAVED"), eq("EMR_DEPARTMENT"), eq(DEPT_ID), before.capture(),
                any());
        assertThat(before.getValue()).containsEntry("revision", 3L);
    }

    @Test
    void aStaleRevisionIs409AndNothingIsWritten() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 0, 3, null)));

        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm",
                new LayoutInput(2L, "EDIT", starterBodyWithVitalsHidden())))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("emr-setup-changed");
                });
        verify(departments, never()).saveDraft(any(), any(), anyLong(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void aWriteLostToAnotherOwnerBetweenReadAndUpdateIs409() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 0, 3, null)));
        when(departments.saveDraft(any(), any(), anyLong(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm",
                new LayoutInput(3L, "EDIT", starterBodyWithVitalsHidden())))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> assertThat(ex.slug())
                        .isEqualTo("emr-setup-changed"));
        verifyNoInteractions(audit);
    }

    @Test
    void twoFirstSavesAtOnceCollideOnTheKeyAndTheSecondIs409() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());
        when(departments.insert(any(), any(), any())).thenThrow(new DuplicateKeyException("uq"));

        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm",
                new LayoutInput(0L, "EDIT", starterBodyWithVitalsHidden())))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> assertThat(ex.status()).isEqualTo(409));
    }

    @Test
    void aResetSaveIsAuditedAsAResetWithTheCountsBeforeAndAfter() {
        List<EmrModel.SectionSettings> draft = new ArrayList<>(EmrLayoutRules.settings(gm));
        draft.set(0, new EmrModel.SectionSettings("vitals", "ASSESSMENT", false, false, false, false, false,
                List.of("DOCTOR")));
        DepartmentRow existing = row("gm", "General Medicine", 0, 2, EmrTestData.JSON.writeSettings(draft));
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(existing));
        when(departments.saveDraft(any(), any(), anyLong(), anyString(), any())).thenReturn(true);

        service.saveDraft(OWNER, "gm", new LayoutInput(2L, "RESET", asInput(EmrLayoutRules.settings(gm))));

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_DEPARTMENT_RESET"), eq("EMR_DEPARTMENT"), eq(DEPT_ID), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("sectionsOn", 2).containsEntry("requiredOn", 1);
        assertThat(after.getValue()).containsEntry("sectionsOn", 3).containsEntry("requiredOn", 1);
    }

    @Test
    void aBodyThatBreaksTheRulesIsRefusedAsAWholeBeforeAnyWrite() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());
        List<SectionSettingsInput> hideAllergy = asInput(EmrLayoutRules.settings(gm));
        hideAllergy.set(1, in("allergy", "ASSESSMENT", false, false));

        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm", new LayoutInput(0L, "EDIT", hideAllergy)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("section-not-hideable"));
        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm", new LayoutInput(null, "EDIT", hideAllergy)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm", new LayoutInput(0L, "WHATEVER", hideAllergy)))
                .isInstanceOf(InvalidFieldsException.class);
        verify(departments, never()).insert(any(), any(), any());
    }

    @Test
    void onlyTheOwnerWrites() {
        doThrow(new AccessDeniedException("owner only")).when(access).requireSetupEdit(any());

        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm", new LayoutInput(0L, "EDIT", List.of())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.publish(OWNER, "gm", new LayoutInput(0L, "EDIT", List.of())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(OWNER, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.doctorOptions(OWNER)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(departments, audit);
    }

    // ---- Publish ----

    @Test
    void publishingAChangedStarterDepartmentMakesVersionOneWithCopiedDefinitions() {
        when(departments.findForUpdate(TENANT, "gm")).thenReturn(Optional.empty());
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 1, null)));
        when(departments.insert(eq(TENANT), any(NewDepartment.class), eq(OWNER_USER))).thenReturn(DEPT_ID);
        when(departments.markPublished(TENANT, DEPT_ID, 0, 1, OWNER_USER)).thenReturn(true);

        PublishResult result = service.publish(OWNER, "gm",
                new LayoutInput(0L, "EDIT", starterBodyWithVitalsHidden()));

        assertThat(result.outcome()).isEqualTo("PUBLISHED");
        assertThat(result.publishedVersion()).isEqualTo(1);
        ArgumentCaptor<String> layout = ArgumentCaptor.forClass(String.class);
        verify(departments).insertVersion(eq(TENANT), eq(DEPT_ID), eq(1), layout.capture(), eq(OWNER_USER));
        List<LayoutSection> stored = EmrTestData.JSON.readLayout(layout.getValue());
        assertThat(stored).extracting(LayoutSection::shown).containsExactly(false, true, true);
        assertThat(stored.get(1).hideLocked()).isTrue();
        assertThat(stored.get(1).hideLockReason()).isEqualTo("Allergies are always shown for patient safety.");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_DEPARTMENT_PUBLISHED"), eq("EMR_DEPARTMENT"), eq(DEPT_ID), any(), after.capture());
        assertThat(after.getValue()).containsEntry("versionBefore", 0).containsEntry("versionAfter", 1);
    }

    @Test
    void publishingWhatDoctorsAlreadySeeWithNoDraftWritesNothing() {
        when(departments.findForUpdate(TENANT, "gm")).thenReturn(Optional.empty());
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());

        PublishResult result = service.publish(OWNER, "gm",
                new LayoutInput(0L, "EDIT", asInput(EmrLayoutRules.settings(gm))));

        assertThat(result.outcome()).isEqualTo("NOTHING_TO_PUBLISH");
        verify(departments, never()).insert(any(), any(), any());
        verify(departments, never()).insertVersion(any(), any(), anyInt(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void theNextVersionIsOneMoreAndTheDraftIsPublishedEvenWhenItEqualsTheBaseline() {
        List<EmrModel.SectionSettings> settings = EmrLayoutRules.settings(gm);
        DepartmentRow live = row("gm", "General Medicine", 4, 7, EmrTestData.JSON.writeSettings(settings));
        VersionRow v4 = new VersionRow(UUID.randomUUID(), DEPT_ID, 4, EmrTestData.JSON.writeLayout(gm),
                Instant.parse("2026-11-01T00:00:00Z"), OWNER_USER);
        when(departments.findForUpdate(TENANT, "gm")).thenReturn(Optional.of(live));
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(live));
        when(departments.latestVersion(TENANT, DEPT_ID)).thenReturn(Optional.of(v4));
        when(departments.markPublished(TENANT, DEPT_ID, 7, 5, OWNER_USER)).thenReturn(true);

        PublishResult result = service.publish(OWNER, "gm", new LayoutInput(7L, "EDIT", asInput(settings)));

        assertThat(result.outcome()).isEqualTo("PUBLISHED");
        verify(departments).insertVersion(eq(TENANT), eq(DEPT_ID), eq(5), anyString(), eq(OWNER_USER));
    }

    @Test
    void twoPublishesAtOnceMakeOneVersionAndTheSecondIs409() {
        DepartmentRow live = row("gm", "General Medicine", 1, 7, null);
        VersionRow v1 = new VersionRow(UUID.randomUUID(), DEPT_ID, 1, EmrTestData.JSON.writeLayout(gm),
                Instant.now(), OWNER_USER);
        when(departments.findForUpdate(TENANT, "gm")).thenReturn(Optional.of(live));
        when(departments.latestVersion(TENANT, DEPT_ID)).thenReturn(Optional.of(v1));
        when(departments.markPublished(any(), any(), anyLong(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.publish(OWNER, "gm",
                new LayoutInput(7L, "EDIT", starterBodyWithVitalsHidden())))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("emr-setup-changed"));
        verifyNoInteractions(audit);
    }

    // ---- reset target, doctors' layout, versions ----

    @Test
    void theResetTargetIsTheStarterSettingsForExactlyTheDepartmentsSections() {
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());

        EmrViews.ResetTarget target = service.resetTarget(OWNER, "gm");

        assertThat(target.basis()).isEqualTo("STARTER");
        assertThat(target.sections()).isEqualTo(EmrLayoutRules.settings(gm));
    }

    @Test
    void aBlankDepartmentResetsToTheBlankLayout() {
        DepartmentRow blank = new DepartmentRow(DEPT_ID, "d_abcdefghij", "Blank one", "BO", "#2E7D32", null, "BLANK",
                null, List.of(), EmrTestData.JSON.writeSettings(EmrLayoutRules.settings(gm)), 0, 1);
        when(departments.find(TENANT, "d_abcdefghij")).thenReturn(Optional.of(blank));

        EmrViews.ResetTarget target = service.resetTarget(OWNER, "d_abcdefghij");

        assertThat(target.basis()).isEqualTo("BLANK");
        assertThat(target.sections()).extracting(EmrModel.SectionSettings::key).containsExactly("vitals", "allergy", "rx");
    }

    @Test
    void doctorsSeeShownSectionsOfTheStarterWhenNothingIsPublishedAndAnAddedDepartmentIsHidden() {
        List<LayoutSection> withHidden = new ArrayList<>(gm);
        withHidden.set(0, withHidden.get(0).withSettings(new EmrModel.SectionSettings("vitals", "ASSESSMENT", false,
                false, false, false, false, List.of("DOCTOR")), 1));
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(
                new StarterLayoutRow("gm", 1, EmrTestData.JSON.writeLayout(withHidden))));
        when(departments.find(TENANT, "gm")).thenReturn(Optional.empty());
        DepartmentRow added = new DepartmentRow(UUID.randomUUID(), "d_abcdefghij", "Annexe", "AN", "#2E7D32", "gm",
                "STARTER", null, List.of(), EmrTestData.JSON.writeSettings(EmrLayoutRules.settings(gm)), 0, 1);
        when(departments.find(TENANT, "d_abcdefghij")).thenReturn(Optional.of(added));

        EmrViews.PublishedLayout layout = service.publishedLayout(OWNER, "gm");

        assertThat(layout.layoutSource()).isEqualTo("STARTER");
        assertThat(layout.versionNo()).isNull();
        assertThat(layout.sections()).extracting(LayoutSection::key).containsExactly("allergy", "rx");
        assertThatThrownBy(() -> service.publishedLayout(OWNER, "d_abcdefghij"))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("department-not-published"));
    }

    @Test
    void aVersionIsReadAsPublishedWithTheNameOfWhoPublishedIt() {
        DepartmentRow live = row("gm", "General Medicine", 2, 4, null);
        VersionRow v2 = new VersionRow(UUID.randomUUID(), DEPT_ID, 2, EmrTestData.JSON.writeLayout(gm),
                Instant.parse("2026-11-05T05:00:00Z"), OWNER_USER);
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(live));
        when(departments.version(TENANT, DEPT_ID, 2)).thenReturn(Optional.of(v2));
        when(actors.displayName(TENANT, OWNER_USER)).thenReturn(Optional.of("Dr Owner"));

        EmrViews.DepartmentVersion version = service.version(OWNER, "gm", 2);

        assertThat(version.publishedByName()).isEqualTo("Dr Owner");
        assertThat(version.sections()).hasSize(3);
        assertThatThrownBy(() -> service.version(OWNER, "gm", 3)).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("version-not-found"));
        assertThatThrownBy(() -> service.version(OWNER, "gm", 0)).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }

    // ---- Add department ----

    private DepartmentCreateInput createInput(String name, DepartmentSource source, boolean printFlags,
                                              List<UUID> doctors, List<String> rooms) {
        return new DepartmentCreateInput(name, null, null, source, printFlags, null, doctors, rooms);
    }

    @Test
    void addingADepartmentFromAStarterCreatesADraftWithAServerMadeKeyDoctorsAndRooms() {
        UUID doctor = UUID.randomUUID();
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of(
                new TeamMember(doctor, "Dr A", "DOCTOR", true, 0)));
        when(departments.countAdded(eq(TENANT), any())).thenReturn(0);
        when(departments.nameTaken(TENANT, "Dental chair 2")).thenReturn(false);
        when(departments.insertIfAbsent(eq(TENANT), any(NewDepartment.class), eq(OWNER_USER))).thenReturn(Optional.of(DEPT_ID));
        when(departments.find(eq(TENANT), anyString())).thenReturn(Optional.of(row("d_x", "Dental chair 2", 0, 1,
                EmrTestData.JSON.writeSettings(EmrLayoutRules.settings(gm)))));

        service.create(OWNER, createInput("  Dental chair 2 ", new DepartmentSource("STARTER", "gm", null), true,
                List.of(doctor), List.of("Chair 2")));

        ArgumentCaptor<NewDepartment> created = ArgumentCaptor.forClass(NewDepartment.class);
        verify(departments).insertIfAbsent(eq(TENANT), created.capture(), eq(OWNER_USER));
        NewDepartment d = created.getValue();
        assertThat(d.key()).matches("^d_[a-z2-7]{10}$");
        assertThat(d.name()).isEqualTo("Dental chair 2");
        assertThat(d.initials()).isEqualTo("DC2");
        assertThat(d.colour()).matches("^#[0-9A-F]{6}$");
        assertThat(d.sourceKind()).isEqualTo("STARTER");
        assertThat(d.starterKey()).isEqualTo("gm");
        assertThat(d.copiedFromKey()).isNull();
        assertThat(d.rooms()).containsExactly("Chair 2");
        assertThat(d.revision()).isEqualTo(1);
        verify(departments).insertDoctor(TENANT, DEPT_ID, doctor, OWNER_USER);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_DEPARTMENT_CREATED"), eq("EMR_DEPARTMENT"), eq(DEPT_ID), eq(null),
                after.capture());
        assertThat(after.getValue()).containsEntry("sourceKind", "STARTER").containsKey("doctorMembershipIds");
    }

    @Test
    void anUntickedPrintTemplateClearsEveryPrintFlag() {
        when(departments.countAdded(eq(TENANT), any())).thenReturn(0);
        when(departments.insertIfAbsent(any(), any(NewDepartment.class), any())).thenReturn(Optional.of(DEPT_ID));
        when(departments.find(eq(TENANT), anyString())).thenReturn(Optional.of(row("d_x", "X", 0, 1, null)));
        List<LayoutSection> printing = new ArrayList<>(gm);
        printing.set(0, printing.get(0).withSettings(new EmrModel.SectionSettings("vitals", "ASSESSMENT", true, false,
                true, false, false, List.of("DOCTOR")), 1));
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(
                new StarterLayoutRow("gm", 1, EmrTestData.JSON.writeLayout(printing))));

        service.create(OWNER, createInput("Annexe", new DepartmentSource("STARTER", "gm", null), false, List.of(),
                List.of()));

        ArgumentCaptor<NewDepartment> created = ArgumentCaptor.forClass(NewDepartment.class);
        verify(departments).insertIfAbsent(any(), created.capture(), any());
        assertThat(EmrTestData.JSON.readSettings(created.getValue().draftLayout())).allMatch(s -> !s.print());
    }

    @Test
    void theNameMustBeUniqueIgnoringCaseIncludingTheStarterNamesAndTheLimitIsFifty() {
        assertThatThrownBy(() -> service.create(OWNER, createInput("general MEDICINE",
                new DepartmentSource("BLANK", null, null), true, List.of(), List.of())))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("department-name-taken"));
        when(departments.nameTaken(TENANT, "Mine")).thenReturn(true);
        assertThatThrownBy(() -> service.create(OWNER, createInput("Mine", new DepartmentSource("BLANK", null, null),
                true, List.of(), List.of())))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("department-name-taken"));
        when(departments.countAdded(eq(TENANT), any())).thenReturn(50);
        assertThatThrownBy(() -> service.create(OWNER, createInput("Another", new DepartmentSource("BLANK", null, null),
                true, List.of(), List.of())))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("department-limit"));
        verify(departments, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void doctorsMustBeActiveDoctorsOfThisClinicAndRoomsMustBeValid() {
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of());
        DepartmentSource blank = new DepartmentSource("BLANK", null, null);

        assertThatThrownBy(() -> service.create(OWNER, createInput("A", blank, true, List.of(UUID.randomUUID()),
                List.of()))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(OWNER, createInput("A", blank, true, List.of(),
                List.of("Room 1", "room 1")))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(OWNER, createInput("A", blank, true, List.of(),
                List.of("x".repeat(41))))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(OWNER, createInput(" ", blank, true, List.of(), List.of())))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(OWNER, createInput("A", new DepartmentSource("STARTER", null, null),
                true, List.of(), List.of()))).isInstanceOf(InvalidFieldsException.class);
        verify(departments, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void aCopySourceMustBeASavedDepartment() {
        when(departments.find(TENANT, "dental")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(OWNER, createInput("Copy", new DepartmentSource("COPY", null, "dental"),
                true, List.of(), List.of()))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("department-not-found"));
        assertThatThrownBy(() -> service.create(OWNER, createInput("Copy", new DepartmentSource("STARTER", "ghost", null),
                true, List.of(), List.of()))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("starter-not-found"));
    }


    @Test
    void aBlankDepartmentWithAStarterOrCopyKeyIs400AndNothingIsInserted() {
        assertThatThrownBy(() -> service.create(OWNER, createInput("Mine", new DepartmentSource("BLANK", "gm", null),
                true, List.of(), List.of()))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(OWNER, createInput("Mine", new DepartmentSource("BLANK", null, "dental"),
                true, List.of(), List.of()))).isInstanceOf(InvalidFieldsException.class);
        verify(departments, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void whenTheInsertLosesTheNameRaceTheAnswerIsNameTakenWithoutAnotherInsert() {
        when(departments.insertIfAbsent(eq(TENANT), any(NewDepartment.class), eq(OWNER_USER)))
                .thenReturn(Optional.empty());
        when(departments.nameTaken(TENANT, "Mine")).thenReturn(false, true);

        assertThatThrownBy(() -> service.create(OWNER, createInput("Mine", new DepartmentSource("BLANK", null, null),
                true, List.of(), List.of())))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("department-name-taken"));
        verify(departments, org.mockito.Mockito.times(1)).insertIfAbsent(any(), any(), any());
    }

    @Test
    void aKeyCollisionRetriesWithAnotherKey() {
        when(departments.insertIfAbsent(eq(TENANT), any(NewDepartment.class), eq(OWNER_USER)))
                .thenReturn(Optional.empty(), Optional.of(DEPT_ID));
        when(departments.find(eq(TENANT), anyString())).thenReturn(Optional.of(row("d_x", "Mine", 0, 1, null)));

        service.create(OWNER, createInput("Mine", new DepartmentSource("BLANK", null, null), true, List.of(),
                List.of()));

        verify(departments, org.mockito.Mockito.times(2)).insertIfAbsent(any(), any(), any());
    }
    @Test
    void initialsComeFromTheFirstLettersOfTheWords() {
        assertThat(EmrDepartmentService.deriveInitials("Dental chair 2")).isEqualTo("DC2");
        assertThat(EmrDepartmentService.deriveInitials("Physiotherapy")).isEqualTo("PH");
        assertThat(EmrDepartmentService.deriveInitials("a b c d")).isEqualTo("ABC");
        assertThat(EmrDepartmentService.deriveInitials("---")).isEqualTo("D");
        assertThatThrownBy(() -> EmrDepartmentService.cleanInitials("abc", "x")).isInstanceOf(InvalidFieldsException.class);
        assertThat(EmrDepartmentService.cleanInitials("AB1", "x")).isEqualTo("AB1");
    }

    // ---- SAH-75-4 (ADR-026 D2): the set is the latest version plus the current starter plus live forms ----

    /** The starter has since gained local_exam (after vitals); the published version does not hold it. */
    private void starterGainsLocalExam() {
        List<LayoutSection> v2 = new ArrayList<>(gm);
        v2.add(1, EmrTestData.section("local_exam", "SPECIALTY", "ASSESSMENT", true, false, false));
        when(library.currentStarterLayout("gm")).thenReturn(Optional.of(
                new StarterLayoutRow("gm", 2, EmrTestData.JSON.writeLayout(v2))));
    }

    private void publishedVersionOfGm() {
        when(departments.latestVersion(TENANT, DEPT_ID)).thenReturn(Optional.of(new EmrDepartmentRepository.VersionRow(
                UUID.randomUUID(), DEPT_ID, 1, EmrTestData.JSON.writeLayout(gm), java.time.Instant.now(), OWNER_USER)));
    }

    @Test
    void aPublishedDepartmentIsShownTheNewStarterSectionAsAHiddenRowItCanSwitchOn() {
        starterGainsLocalExam();
        publishedVersionOfGm();
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 4, null)));

        DepartmentView view = service.get(OWNER, "gm");

        assertThat(view.sections()).extracting(LayoutSection::key).containsExactly("vitals", "local_exam", "allergy", "rx");
        assertThat(view.sections().get(1).shown()).isFalse();
        assertThat(view.sections().get(1).required()).isFalse();
        assertThat(view.hasDraft()).isFalse();
        assertThat(view.sectionsTotal()).isEqualTo(4);
    }

    @Test
    void theSaveCheckAcceptsTheSetWithTheOfferedRowAndTheOldSetIsASectionSetMismatch() {
        starterGainsLocalExam();
        publishedVersionOfGm();
        when(departments.findForUpdate(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 4, null)));
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 4, null)));
        when(departments.saveDraft(eq(TENANT), eq(DEPT_ID), eq(4L), any(), eq(OWNER_USER))).thenReturn(true);
        List<SectionSettingsInput> full = new ArrayList<>(asInput(EmrLayoutRules.settings(gm)));
        full.add(1, in("local_exam", "ASSESSMENT", true, false));

        DraftSaveResult saved = service.saveDraft(OWNER, "gm", new LayoutInput(4L, "EDIT", full));

        assertThat(saved.outcome()).isEqualTo("SAVED");
        assertThatThrownBy(() -> service.saveDraft(OWNER, "gm",
                new LayoutInput(4L, "EDIT", asInput(EmrLayoutRules.settings(gm)))))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("section-set-mismatch"));
    }

    @Test
    void resetToTemplateReturnsTheCurrentStarterSettingsIncludingTheNewSectionAndPassesTheSetCheck() {
        starterGainsLocalExam();
        publishedVersionOfGm();
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 4, null)));

        EmrViews.ResetTarget target = service.resetTarget(OWNER, "gm");

        assertThat(target.basis()).isEqualTo("STARTER");
        assertThat(target.sections()).extracting(EmrModel.SectionSettings::key).containsExactly("vitals", "local_exam",
                "allergy", "rx");
        assertThat(target.sections().get(1).shown()).isTrue();
        List<SectionSettingsInput> body = asInput(target.sections());
        assertThat(EmrLayoutRules.validate(body, new EmrLayouts(library, departments, EmrTestData.MAPPER)
                .base(TENANT, new EmrLayouts(library, departments, EmrTestData.MAPPER).find(TENANT, "gm").orElseThrow())))
                .hasSize(4);
    }

    @Test
    void doctorsStillSeeOnlyThePublishedVersionAfterTheStarterGainsASection() {
        starterGainsLocalExam();
        publishedVersionOfGm();
        when(departments.find(TENANT, "gm")).thenReturn(Optional.of(row("gm", "General Medicine", 1, 4, null)));

        EmrViews.PublishedLayout layout = service.publishedLayout(OWNER, "gm");

        assertThat(layout.sections()).extracting(LayoutSection::key).containsExactly("vitals", "allergy", "rx");
    }
}
