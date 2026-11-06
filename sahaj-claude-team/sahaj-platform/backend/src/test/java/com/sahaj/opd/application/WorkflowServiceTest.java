package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.WorkflowRepository.DraftRow;
import com.sahaj.opd.application.WorkflowRepository.VersionRow;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-12: defaults, lazy version 1, draft and publish round trip, owner-only writes, validation. */
class WorkflowServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private WorkflowRepository repository;
    private VisitWorkflowSettingsRepository settings;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        settings = mock(VisitWorkflowSettingsRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        ActorDirectoryPort directory = mock(ActorDirectoryPort.class);
        when(directory.displayName(any(), any())).thenReturn(Optional.of("Dr Owner"));
        when(settings.vitalsOn(TENANT_ID)).thenReturn(Optional.empty());
        service = new WorkflowService(repository, settings, ownerGuard, directory, auditLog);
    }

    private static List<WorkflowStepInput> inputs(List<WorkflowStep> steps) {
        return WorkflowRules.toInput(steps);
    }

    private static List<WorkflowStepInput> counselOn() {
        List<WorkflowStepInput> list = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        list.set(4, new WorkflowStepInput("counsel", true, "NURSE"));
        return list;
    }

    @Test
    void defaultStepsMatchTheDocumentedDefaults() {
        List<WorkflowStep> defaults = WorkflowRules.defaults(true);

        assertThat(defaults).extracting(WorkflowStep::key).containsExactly("token", "queue", "vitals", "consult",
                "counsel", "casepaper", "billing", "followup");
        assertThat(defaults).extracting(WorkflowStep::on)
                .containsExactly(true, true, true, true, false, true, true, true);
        assertThat(defaults.get(2).owner()).isEqualTo("NURSE");
        assertThat(defaults.get(4).owner()).isEqualTo("DOCTOR");
        assertThat(defaults.get(0).owner()).isNull();
    }

    @Test
    void firstCallCreatesVersionOneFromDefaultsCreditedToTheCaller() {
        List<WorkflowStep> defaults = WorkflowRules.defaults(true);
        when(repository.findCurrent(TENANT_ID)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new VersionRow(1, defaults, NOW, USER_ID)));

        WorkflowVersionView view = service.current(TENANT_ID, USER_ID);

        verify(repository).insertVersionIfAbsent(TENANT_ID, 1, defaults, USER_ID);
        assertThat(view.versionNo()).isEqualTo(1);
        assertThat(view.publishedByName()).isEqualTo("Dr Owner");
    }

    @Test
    void versionOneKeepsTheSetupChoiceToHaveNoVitals() {
        when(settings.vitalsOn(TENANT_ID)).thenReturn(Optional.of(false));
        when(repository.findCurrent(TENANT_ID)).thenReturn(Optional.empty());

        assertThat(service.currentSteps(TENANT_ID).get(2).on()).isFalse();
        verify(repository, never()).insertVersionIfAbsent(any(), anyInt(), any(), any());
    }

    @Test
    void draftWithNothingSavedIsACopyOfTheCurrentVersion() {
        List<WorkflowStep> steps = WorkflowRules.defaults(true);
        when(repository.findDraft(TENANT_ID)).thenReturn(Optional.empty());
        when(repository.findCurrent(TENANT_ID)).thenReturn(Optional.of(new VersionRow(3, steps, NOW, USER_ID)));

        assertThat(service.draft(TENANT_ID, USER_ID)).isEqualTo(steps);
        verify(repository, never()).upsertDraft(any(), any(), any());
    }

    @Test
    void saveDraftThenPublishBumpsTheVersionAndAuditsBeforeAndAfter() {
        List<WorkflowStep> saved = WorkflowRules.validate(counselOn());
        List<WorkflowStep> v1 = WorkflowRules.defaults(true);
        when(repository.findCurrent(TENANT_ID)).thenReturn(Optional.of(new VersionRow(1, v1, NOW, USER_ID)));
        when(repository.findDraft(TENANT_ID)).thenReturn(Optional.of(new DraftRow(saved)));
        when(repository.insertVersionIfAbsent(TENANT_ID, 2, saved, USER_ID)).thenReturn(true);
        when(repository.findVersion(TENANT_ID, 2)).thenReturn(Optional.of(new VersionRow(2, saved, NOW, USER_ID)));

        assertThat(service.saveDraft(TENANT_ID, USER_ID, counselOn())).isEqualTo(saved);
        verify(repository).upsertDraft(TENANT_ID, saved, USER_ID);

        WorkflowVersionView published = service.publish(TENANT_ID, USER_ID);

        assertThat(published.versionNo()).isEqualTo(2);
        assertThat(published.steps().get(4).on()).isTrue();
        verify(auditLog).record(eq("WORKFLOW_PUBLISHED"), eq("WORKFLOW"), eq(TENANT_ID), any(), any());
    }

    @Test
    void publishWithoutADraftOrWithAnUnchangedDraftIsAWorkflowConflict() {
        List<WorkflowStep> v1 = WorkflowRules.defaults(true);
        when(repository.findCurrent(TENANT_ID)).thenReturn(Optional.of(new VersionRow(1, v1, NOW, USER_ID)));
        when(repository.findDraft(TENANT_ID)).thenReturn(Optional.empty());

        assertWorkflowConflict();

        when(repository.findDraft(TENANT_ID)).thenReturn(Optional.of(new DraftRow(v1)));
        assertWorkflowConflict();
        verify(repository, never()).insertVersionIfAbsent(any(), eq(2), any(), any());
    }

    private void assertWorkflowConflict() {
        assertThatThrownBy(() -> service.publish(TENANT_ID, USER_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("workflow-conflict"));
    }

    @Test
    void aLostPublishRaceIs409() {
        List<WorkflowStep> saved = WorkflowRules.validate(counselOn());
        when(repository.findCurrent(TENANT_ID))
                .thenReturn(Optional.of(new VersionRow(1, WorkflowRules.defaults(true), NOW, USER_ID)));
        when(repository.findDraft(TENANT_ID)).thenReturn(Optional.of(new DraftRow(saved)));
        when(repository.insertVersionIfAbsent(TENANT_ID, 2, saved, USER_ID)).thenReturn(false);

        assertWorkflowConflict();
    }

    @Test
    void aNonOwnerCannotSaveOrPublish() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, USER_ID);

        assertThatThrownBy(() -> service.saveDraft(TENANT_ID, USER_ID, counselOn()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.publish(TENANT_ID, USER_ID)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsertDraft(any(), any(), any());
    }

    @Test
    void validationRejectsBadInput() {
        assertInvalid(withStep(0, new WorkflowStepInput("token", false, null)));
        assertInvalid(withStep(1, new WorkflowStepInput("queue", false, null)));
        assertInvalid(withStep(3, new WorkflowStepInput("consult", false, null)));
        assertInvalid(withStep(6, new WorkflowStepInput("billing", false, null)));
        assertInvalid(withStep(4, new WorkflowStepInput("counsel", true, "PHARMACIST")));
        assertInvalid(withStep(2, new WorkflowStepInput("vitals", true, "COUNSELLOR")));
        assertInvalid(withStep(2, new WorkflowStepInput("vitals", null, "NURSE")));
        assertInvalid(withStep(2, new WorkflowStepInput("nope", true, null)));
        assertInvalid(withStep(2, new WorkflowStepInput("queue", true, null)));
        assertInvalid(inputs(WorkflowRules.defaults(true)).subList(0, 7));
        assertInvalid(null);
    }

    @Test
    void validationRequiresTheStepKeysInTheFixedOrder() {
        List<WorkflowStepInput> swapped = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        WorkflowStepInput queue = swapped.get(1);
        swapped.set(1, swapped.get(3));
        swapped.set(3, queue);
        assertInvalid(swapped);

        List<WorkflowStepInput> reversed = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        java.util.Collections.reverse(reversed);
        assertInvalid(reversed);

        assertThat(WorkflowRules.validate(inputs(WorkflowRules.defaults(true)))).extracting(WorkflowStep::key)
                .containsExactlyElementsOf(WorkflowRules.KEYS);
    }

    @Test
    void validationRefusesAnOwnerOnAStepThatHasNone() {
        for (int index : new int[] {0, 1, 3, 5, 6, 7}) {
            String key = WorkflowRules.KEYS.get(index);
            assertInvalid(withStep(index, new WorkflowStepInput(key, true, "NURSE")));
        }
        assertThat(WorkflowRules.validate(withStep(2, new WorkflowStepInput("vitals", true, null))).get(2).owner())
                .isEqualTo("NURSE");
    }

    @Test
    void validationRejectsCasePaperOrFollowUpBeforeConsult() {
        List<WorkflowStepInput> list = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        WorkflowStepInput casepaper = list.remove(5);
        list.add(2, casepaper);
        assertInvalid(list);

        List<WorkflowStepInput> followup = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        WorkflowStepInput moved = followup.remove(7);
        followup.add(1, moved);
        assertInvalid(followup);
    }

    @Test
    void anOwnerMayBeFrontDeskNurseDoctorOrOwner() {
        for (String role : List.of("FRONT_DESK", "NURSE", "DOCTOR", "OWNER")) {
            List<WorkflowStepInput> list = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
            list.set(2, new WorkflowStepInput("vitals", true, role));
            assertThat(WorkflowRules.validate(list).get(2).owner()).isEqualTo(role);
        }
    }

    private static List<WorkflowStepInput> withStep(int index, WorkflowStepInput step) {
        List<WorkflowStepInput> list = new ArrayList<>(inputs(WorkflowRules.defaults(true)));
        list.set(index, step);
        return list;
    }

    private static void assertInvalid(List<WorkflowStepInput> input) {
        assertThatThrownBy(() -> WorkflowRules.validate(input)).isInstanceOf(InvalidInputException.class);
    }
}
