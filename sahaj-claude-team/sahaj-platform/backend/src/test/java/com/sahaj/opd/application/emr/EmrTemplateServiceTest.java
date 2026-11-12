package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.emr.EmrSetupRepository.OverrideRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.OptionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.TemplateHeader;
import com.sahaj.opd.application.emr.EmrViews.SectionView;
import com.sahaj.opd.application.emr.EmrViews.TemplateSummary;
import com.sahaj.opd.application.emr.EmrViews.TemplateView;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-44-1, SAH-44-2: templates with this clinic's overrides; the owner's changes, revision, reset and audit. */
class EmrTemplateServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    /** A small in-memory stand-in for the override tables, with the same compare-and-set rule on the revision. */
    private static final class FakeSetup implements EmrSetupRepository {
        final Map<String, Long> revisions = new LinkedHashMap<>();
        final Map<String, Map<String, OverrideRow>> overrides = new LinkedHashMap<>();

        List<OverrideRow> overrides(String templateKey) {
            return overrides(null, templateKey);
        }

        @Override
        public long revision(UUID tenantId, String templateKey) {
            return revisions.getOrDefault(templateKey, 0L);
        }

        @Override
        public Map<String, List<OverrideRow>> allOverrides(UUID tenantId) {
            Map<String, List<OverrideRow>> all = new LinkedHashMap<>();
            overrides.forEach((key, rows) -> all.put(key, new ArrayList<>(rows.values())));
            return all;
        }

        @Override
        public List<OverrideRow> overrides(UUID tenantId, String templateKey) {
            return new ArrayList<>(overrides.getOrDefault(templateKey, Map.of()).values());
        }

        @Override
        public boolean bumpRevision(UUID tenantId, String templateKey, long expected, UUID actorUserId) {
            if (revision(tenantId, templateKey) != expected) {
                return false;
            }
            revisions.put(templateKey, expected + 1);
            return true;
        }

        @Override
        public void saveOverride(UUID tenantId, String templateKey, String sectionKey, boolean shown,
                                 boolean required, UUID actorUserId) {
            overrides.computeIfAbsent(templateKey, key -> new LinkedHashMap<>())
                    .put(sectionKey, new OverrideRow(sectionKey, shown, required));
        }

        @Override
        public void deleteOverride(UUID tenantId, String templateKey, String sectionKey) {
            overrides.getOrDefault(templateKey, new LinkedHashMap<>()).remove(sectionKey);
        }

        @Override
        public int deleteOverrides(UUID tenantId, String templateKey) {
            Map<String, OverrideRow> removed = overrides.remove(templateKey);
            return removed == null ? 0 : removed.size();
        }
    }

    private static final TemplateHeader GP = new TemplateHeader("gp", 1, "General practice");
    private static final List<SectionRow> GP_SECTIONS = List.of(
            new SectionRow("chief_complaints", 1, "Chief complaints", "ASSESSMENT", true, true, "COMPLAINTS", null,
                    "MASTER", "complaints"),
            new SectionRow("local_exam", 2, "Local examination", "ASSESSMENT", false, true, "CHIP_LIST", null,
                    "TEMPLATE", null),
            new SectionRow("vitals", 3, "Vitals", "ASSESSMENT", true, true, "VITALS_STEPPERS", null, "NONE", null),
            new SectionRow("prescription", 4, "Prescription", "TREATMENT", true, false, "PRESCRIPTION", null,
                    "MASTER", "medicines"));

    private EmrTemplateRepository templates;
    private FakeSetup setup;
    private ServiceRepository services;
    private ClinicSpecialtyPort clinicSpecialty;
    private EmrAccess access;
    private AuditLog auditLog;
    private EmrTemplateService service;
    private ClinicUser owner;

    @BeforeEach
    void setUp() {
        templates = mock(EmrTemplateRepository.class);
        setup = new FakeSetup();
        services = mock(ServiceRepository.class);
        clinicSpecialty = mock(ClinicSpecialtyPort.class);
        access = mock(EmrAccess.class);
        auditLog = mock(AuditLog.class);
        service = new EmrTemplateService(templates, setup, services, clinicSpecialty, access, auditLog);
        owner = new ClinicUser(OWNER, TENANT, "OWNER");

        when(templates.newest("gp")).thenReturn(Optional.of(GP));
        when(templates.newestTemplates()).thenReturn(List.of(GP,
                new TemplateHeader("dental", 1, "Dental")));
        when(templates.sections("gp", 1)).thenReturn(GP_SECTIONS);
        when(templates.sections("dental", 1)).thenReturn(List.of(
                new SectionRow("chief_complaints", 1, "Chief complaints", "ASSESSMENT", true, true, "COMPLAINTS",
                        null, "MASTER", "complaints")));
        when(templates.options("gp", 1)).thenReturn(List.<OptionRow>of());
        when(templates.masterCounts()).thenReturn(Map.of(EmrMaster.COMPLAINTS, 59L, EmrMaster.MEDICINES, 60L));
        when(clinicSpecialty.specialtyCode(TENANT)).thenReturn(Optional.of("dental"));
        when(services.findAll(TENANT, true)).thenReturn(List.of());
        when(access.canEditSetup(any())).thenReturn(true);
    }

    // ---- reads ----

    @Test
    void theListHasTheCountsOfThisClinicAndMarksTheClinicsOwnSpecialty() {
        setup.saveOverride(TENANT, "gp", "local_exam", false, false, OWNER);

        List<TemplateSummary> list = service.list(owner);

        assertThat(list).extracting(TemplateSummary::templateKey).containsExactly("gp", "dental");
        TemplateSummary gp = list.get(0);
        assertThat(gp.sectionCount()).isEqualTo(4);
        assertThat(gp.sectionsOn()).isEqualTo(3);
        assertThat(gp.requiredOn()).isEqualTo(3);
        assertThat(gp.hasOverrides()).isTrue();
        assertThat(gp.clinicSpecialty()).isFalse();
        assertThat(list.get(1).clinicSpecialty()).isTrue();
        assertThat(list.get(1).hasOverrides()).isFalse();
    }

    @Test
    void theTemplateViewListsEverySectionIncludingHiddenOnes() {
        setup.saveOverride(TENANT, "gp", "local_exam", false, false, OWNER);

        TemplateView view = service.get(owner, "gp");

        assertThat(view.sections()).hasSize(4);
        SectionView local = view.sections().get(1);
        assertThat(local.shown()).isFalse();
        assertThat(local.overridden()).isTrue();
        assertThat(view.sectionsOn()).isEqualTo(3);
        assertThat(view.canEdit()).isTrue();
        assertThat(view.revision()).isZero();
    }

    @Test
    void anUnknownWellFormedKeyIs404AndAMalformedKeyIs400() {
        assertThatThrownBy(() -> service.get(owner, "ayur")).isInstanceOfSatisfying(EmrProblemException.class, ex -> {
            assertThat(ex.status()).isEqualTo(404);
            assertThat(ex.slug()).isEqualTo("template-not-found");
            assertThat(ex.getMessage()).isEqualTo("There is no EMR template for this specialty yet.");
        });
        assertThatThrownBy(() -> service.get(owner, "GP;drop")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }

    // ---- override ----

    @Test
    void hidingASectionSavesItHiddenAndNotRequiredAndAuditsOnce() {
        TemplateView view = service.setOverride(owner, "gp", "vitals", false, false, 0L);

        assertThat(setup.overrides("gp")).containsExactly(new OverrideRow("vitals", false, false));
        assertThat(view.revision()).isEqualTo(1);
        assertThat(view.sections().get(2).shown()).isFalse();
        assertThat(view.sections().get(2).required()).isFalse();
        assertThat(view.sectionsOn()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("EMR_TEMPLATE_OVERRIDE_CHANGED"), eq("EMR_TEMPLATE_OVERRIDE"), eq(null),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("templateKey", "gp").containsEntry("sectionKey", "vitals")
                .containsEntry("shown", true).containsEntry("required", true);
        assertThat(after.getValue()).containsEntry("shown", false).containsEntry("required", false);
    }

    @Test
    void makingARequiredSectionOptionalOrAnOptionalOneRequiredIsStored() {
        service.setOverride(owner, "gp", "vitals", true, false, 0L);
        service.setOverride(owner, "gp", "local_exam", true, true, 1L);

        assertThat(setup.overrides("gp")).containsExactlyInAnyOrder(new OverrideRow("vitals", true, false),
                new OverrideRow("local_exam", true, true));
        assertThat(setup.revision(TENANT, "gp")).isEqualTo(2);
    }

    @Test
    void aValueEqualToTheTemplateDefaultRemovesTheOverride() {
        service.setOverride(owner, "gp", "vitals", false, false, 0L);
        TemplateView view = service.setOverride(owner, "gp", "vitals", true, true, 1L);

        assertThat(setup.overrides("gp")).isEmpty();
        assertThat(view.sections().get(2).overridden()).isFalse();
        assertThat(view.revision()).isEqualTo(2);
    }

    @Test
    void aHiddenSectionCannotBeRequired() {
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "vitals", false, true, 0L))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.slug()).isEqualTo("hidden-section-required");
                    assertThat(ex.getMessage()).isEqualTo("A hidden section cannot be required.");
                });
        assertThat(setup.overrides("gp")).isEmpty();
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aSectionThatCannotBeHiddenIsRefusedAndNothingChanges() {
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "prescription", false, false, 0L))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.slug()).isEqualTo("section-not-hideable");
                    assertThat(ex.getMessage()).isEqualTo("Prescription cannot be hidden.");
                });
        assertThat(setup.overrides("gp")).isEmpty();
        assertThat(setup.revision(TENANT, "gp")).isZero();
    }

    @Test
    void anUnknownSectionIs404AndAMalformedOneIs400() {
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "not_a_section", true, true, 0L))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("section-not-found"));
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "Bad Key", true, true, 0L))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
        assertThatThrownBy(() -> service.setOverride(owner, "ayur", "vitals", true, true, 0L))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("template-not-found"));
    }

    @Test
    void aStaleRevisionIs409AndNothingIsApplied() {
        service.setOverride(owner, "gp", "vitals", false, false, 0L);

        assertThatThrownBy(() -> service.setOverride(owner, "gp", "local_exam", false, false, 0L))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("emr-setup-changed");
                    assertThat(ex.getMessage()).isEqualTo("Someone else changed this template. Reload and try again.");
                });
        assertThat(setup.overrides("gp")).extracting(OverrideRow::sectionKey).containsExactly("vitals");
    }

    @Test
    void whenTheRevisionStepLosesTheRaceTheAnswerIs409AndNoOverrideIsWritten() {
        EmrSetupRepository racing = mock(EmrSetupRepository.class);
        when(racing.revision(TENANT, "gp")).thenReturn(3L);
        when(racing.overrides(TENANT, "gp")).thenReturn(List.of());
        when(racing.bumpRevision(TENANT, "gp", 3L, OWNER)).thenReturn(false);
        EmrTemplateService racingService = new EmrTemplateService(templates, racing, services, clinicSpecialty,
                access, auditLog);

        assertThatThrownBy(() -> racingService.setOverride(owner, "gp", "vitals", false, false, 3L))
                .isInstanceOf(ConflictException.class);
        verify(racing, never()).saveOverride(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aRequestThatChangesNothingKeepsTheRevisionAndWritesNoAudit() {
        TemplateView view = service.setOverride(owner, "gp", "vitals", true, true, 0L);

        assertThat(view.revision()).isZero();
        assertThat(setup.overrides("gp")).isEmpty();
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingFieldIsAValidationErrorAndAWrongRoleIsRefusedFirst() {
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "vitals", null, true, 0L))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        ex -> assertThat(ex.errors()).extracting(InvalidFieldsException.FieldMessage::field)
                                .containsExactly("shown"));
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "vitals", true, true, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "vitals", true, true, -1L))
                .isInstanceOf(InvalidFieldsException.class);

        doThrow(new AccessDeniedException("no")).when(access).requireSetupEdit(any());
        assertThatThrownBy(() -> service.setOverride(owner, "gp", "vitals", null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reset(owner, "gp", 0L)).isInstanceOf(AccessDeniedException.class);
    }

    // ---- reset ----

    @Test
    void resetRemovesEveryOverrideBumpsTheRevisionAndAuditsOnceWithBeforeAndAfterCounts() {
        service.setOverride(owner, "gp", "vitals", false, false, 0L);
        service.setOverride(owner, "gp", "local_exam", true, true, 1L);

        TemplateView view = service.reset(owner, "gp", 2L);

        assertThat(setup.overrides("gp")).isEmpty();
        assertThat(view.revision()).isEqualTo(3);
        assertThat(view.sectionsOn()).isEqualTo(4);
        assertThat(view.requiredOn()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("EMR_TEMPLATE_RESET"), eq("EMR_TEMPLATE_SETUP"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("sectionsOn", 3).containsEntry("requiredOn", 3)
                .containsEntry("overrides", 2);
        assertThat(after.getValue()).containsEntry("sectionsOn", 4).containsEntry("requiredOn", 3)
                .containsEntry("overrides", 0);
    }

    @Test
    void resetWithNoOverridesIsASuccessThatChangesNothing() {
        TemplateView view = service.reset(owner, "gp", 0L);

        assertThat(view.revision()).isZero();
        verify(auditLog, never()).record(eq("EMR_TEMPLATE_RESET"), any(), any(), any(), any());
    }

    @Test
    void resetWithAStaleRevisionIs409() {
        service.setOverride(owner, "gp", "vitals", false, false, 0L);

        assertThatThrownBy(() -> service.reset(owner, "gp", 0L)).isInstanceOf(ConflictException.class);
        assertThat(setup.overrides("gp")).hasSize(1);
    }

    @Test
    void resetOfAnUnknownTemplateIs404() {
        assertThatThrownBy(() -> service.reset(owner, "physio", 0L)).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("template-not-found"));
        assertThatThrownBy(() -> service.reset(owner, "gp", null)).isInstanceOf(InvalidFieldsException.class);
    }
}
