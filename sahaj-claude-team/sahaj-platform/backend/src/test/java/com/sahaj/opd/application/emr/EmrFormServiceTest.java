package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.DraftInput;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.RevisionInput;
import com.sahaj.opd.application.emr.EmrFormModel.SendBackInput;
import com.sahaj.opd.application.emr.EmrFormModel.Settings;
import com.sahaj.opd.application.emr.EmrFormModel.UseIn;
import com.sahaj.opd.application.emr.EmrFormRepository.FormRow;
import com.sahaj.opd.application.emr.EmrFormRepository.FormState;
import com.sahaj.opd.application.emr.EmrFormRepository.StarterRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmrFormServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID FORM = UUID.randomUUID();
    private static final String KEY = "f_abcdefghij";
    private static final ClinicUser OWNER = new ClinicUser(USER, TENANT, "OWNER");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), ZoneOffset.UTC);

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrFormRepository forms = mock(EmrFormRepository.class);
    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private final EmrDepartmentRepository departments = mock(EmrDepartmentRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private EmrFormService service;

    @BeforeEach
    void setUp() {
        when(access.canEditSetup(OWNER)).thenReturn(true);
        EmrLibraryRepository.StarterRow gm = mock(EmrLibraryRepository.StarterRow.class);
        when(gm.starterKey()).thenReturn("gm");
        when(library.starters()).thenReturn(List.of(gm));
        service = new EmrFormService(access, forms, library, departments, team, audit, EmrTestData.MAPPER,
                new SecureRandom(), CLOCK);
    }

    private static Settings settings(boolean approval) {
        return new Settings(new UseIn(List.of("gm"), List.of()), "ASSESSMENT", List.of("NEW_CONSULT"),
                List.of("DOCTOR"), "EN", approval);
    }

    private static Field text(String key) {
        return new Field(key, "SHORT_TEXT", "Label", null, null, null, false, 3, null, null, null, null, null,
                List.of(), true, false, List.of("DOCTOR"), null, null, null, null);
    }

    private String json(boolean approval, List<Field> fields) throws Exception {
        return EmrTestData.MAPPER.writeValueAsString(new Definition(1, "Foot check", settings(approval), fields));
    }

    private FormRow row(String state, int live, int draftNo, String definition, long revision) {
        return new FormRow(FORM, KEY, "Foot check", null, "ACTIVE", live, draftNo, state, definition, null, null, null,
                revision, Instant.parse("2026-11-12T05:00:00Z"), USER, null);
    }

    private void found(FormRow row) {
        when(forms.find(TENANT, KEY)).thenReturn(Optional.of(row));
    }

    @Test
    void savingADraftChecksTheStructureAndAnUnknownDepartmentIsFormInvalidWithTheFieldNamed() {
        found(row("DRAFT", 0, 1, "{}", 2));
        Settings bad = new Settings(new UseIn(List.of("ghost"), List.of()), "ASSESSMENT", List.of(), List.of(), "EN",
                true);

        assertThatThrownBy(() -> service.saveDraft(OWNER, KEY, new DraftInput(2L, "Foot check", bad, List.of())))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("form-invalid");
                    assertThat(ex.fieldErrors()).extracting(f -> f.field())
                            .contains("settings.useIn.departmentKeys");
                });
        verify(forms, never()).update(any(), any(), anyLong(), any(), any());
    }

    @Test
    void aSaveWithAStaleRevisionIs409FormChangedAndNothingIsWritten() {
        found(row("DRAFT", 0, 1, "{}", 5));

        assertThatThrownBy(() -> service.saveDraft(OWNER, KEY, new DraftInput(4L, "Foot check", settings(true),
                List.of(text("a"))))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("form-changed"));
        verify(forms, never()).update(any(), any(), anyLong(), any(), any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void savingALiveFormMakesDraftVersionLivePlusOneAndAuditsWithoutLabels() throws Exception {
        found(row("NONE", 2, 3, null, 7));
        when(forms.update(eq(TENANT), eq(FORM), eq(7L), any(FormState.class), eq(USER))).thenReturn(true);
        when(forms.version(eq(TENANT), eq(FORM), eq(2))).thenReturn(Optional.of(new EmrFormRepository.VersionRow(
                UUID.randomUUID(), FORM, 2, json(true, List.of(text("a"))), null, null, Instant.now(), USER)));

        service.saveDraft(OWNER, KEY, new DraftInput(7L, "Foot check", settings(true), List.of(text("a"), text("b"))));

        ArgumentCaptor<FormState> state = ArgumentCaptor.forClass(FormState.class);
        verify(forms).update(eq(TENANT), eq(FORM), eq(7L), state.capture(), eq(USER));
        assertThat(state.getValue().draftState()).isEqualTo("DRAFT");
        assertThat(state.getValue().draftVersionNo()).isEqualTo(3);
        assertThat(state.getValue().liveVersionNo()).isEqualTo(2);
        ArgumentCaptor<java.util.Map<String, Object>> after = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq("EMR_FORM_DRAFT_SAVED"), eq("EMR_FORM"), eq(FORM), any(), after.capture());
        assertThat(after.getValue()).containsEntry("fieldCount", 2).doesNotContainKey("fields");
    }

    @Test
    void aFieldThatAnotherFieldReadsCannotBeRemoved() throws Exception {
        Field yesNo = new Field("diabetic", "YES_NO", "Diabetic", null, null, null, false, 3, null, null, null, null,
                null, List.of(), true, false, List.of("DOCTOR"), null, null, null, null);
        Field dependent = new Field("foot", "SHORT_TEXT", "Foot", null, null, null, false, 3, null, null, null, null,
                null, List.of(), true, false, List.of("DOCTOR"), null, new EmrFormModel.ShowIf("diabetic", "yes"),
                null, null);
        found(row("DRAFT", 0, 1, json(true, List.of(yesNo, dependent)), 1));

        assertThatThrownBy(() -> service.saveDraft(OWNER, KEY, new DraftInput(1L, "Foot check", settings(true),
                List.of(dependent)))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("form-invalid"));
    }

    @Test
    void submitNeedsApprovalOnAndPublishableDefinitionThenWaitsForApproval() throws Exception {
        found(row("DRAFT", 0, 1, json(true, List.of(text("a"))), 3));
        when(forms.update(eq(TENANT), eq(FORM), eq(3L), any(FormState.class), eq(USER))).thenReturn(true);

        service.submit(OWNER, KEY, new RevisionInput(3L));

        ArgumentCaptor<FormState> state = ArgumentCaptor.forClass(FormState.class);
        verify(forms).update(eq(TENANT), eq(FORM), eq(3L), state.capture(), eq(USER));
        assertThat(state.getValue().draftState()).isEqualTo("WAITING_APPROVAL");
        assertThat(state.getValue().submittedBy()).isEqualTo(USER);
        verify(audit).record(eq("EMR_FORM_SUBMITTED"), eq("EMR_FORM"), eq(FORM), any(), any());
    }

    @Test
    void anEmptyFormCannotBeSubmittedOrPublished() throws Exception {
        found(row("DRAFT", 0, 1, json(true, List.of()), 3));

        assertThatThrownBy(() -> service.submit(OWNER, KEY, new RevisionInput(3L)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("form-not-publishable");
                    assertThat(ex.fieldErrors()).isNotEmpty();
                });
        found(row("DRAFT", 0, 1, json(false, List.of()), 3));
        assertThatThrownBy(() -> service.publish(OWNER, KEY, new RevisionInput(3L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("form-not-publishable"));
    }

    @Test
    void approvalOffMeansSubmitIsFormStateAndApprovalOnMeansPublishIsFormState() throws Exception {
        found(row("DRAFT", 0, 1, json(false, List.of(text("a"))), 3));
        assertThatThrownBy(() -> service.submit(OWNER, KEY, new RevisionInput(3L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("form-state"));
        found(row("DRAFT", 0, 1, json(true, List.of(text("a"))), 3));
        assertThatThrownBy(() -> service.publish(OWNER, KEY, new RevisionInput(3L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("form-state"));
    }

    @Test
    void approveMakesAnImmutableVersionLivePlusOneAndClearsTheDraft() throws Exception {
        found(row("WAITING_APPROVAL", 2, 3, json(true, List.of(text("a"))), 9));
        when(forms.update(eq(TENANT), eq(FORM), eq(9L), any(FormState.class), eq(USER))).thenReturn(true);

        service.approve(OWNER, KEY, new RevisionInput(9L));

        verify(forms).insertVersion(eq(TENANT), eq(FORM), eq(3), anyString(), eq(USER), eq(USER));
        ArgumentCaptor<FormState> state = ArgumentCaptor.forClass(FormState.class);
        verify(forms).update(eq(TENANT), eq(FORM), eq(9L), state.capture(), eq(USER));
        assertThat(state.getValue().liveVersionNo()).isEqualTo(3);
        assertThat(state.getValue().draftVersionNo()).isEqualTo(4);
        assertThat(state.getValue().draftState()).isEqualTo("NONE");
        assertThat(state.getValue().draftDefinition()).isNull();
        verify(audit).record(eq("EMR_FORM_APPROVED"), eq("EMR_FORM"), eq(FORM), any(), any());
    }

    @Test
    void approveOnlyWorksWhileWaitingAndSendBackNeedsAReason() throws Exception {
        found(row("DRAFT", 0, 1, json(true, List.of(text("a"))), 3));
        assertThatThrownBy(() -> service.approve(OWNER, KEY, new RevisionInput(3L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("form-state"));
        found(row("WAITING_APPROVAL", 0, 1, json(true, List.of(text("a"))), 3));
        assertThatThrownBy(() -> service.sendBack(OWNER, KEY, new SendBackInput(3L, "  ")))
                .isInstanceOf(InvalidFieldsException.class);
        when(forms.update(eq(TENANT), eq(FORM), eq(3L), any(FormState.class), eq(USER))).thenReturn(true);
        service.sendBack(OWNER, KEY, new SendBackInput(3L, "Add the units"));
        ArgumentCaptor<FormState> state = ArgumentCaptor.forClass(FormState.class);
        verify(forms).update(eq(TENANT), eq(FORM), eq(3L), state.capture(), eq(USER));
        assertThat(state.getValue().draftState()).isEqualTo("SENT_BACK");
        assertThat(state.getValue().sentBackReason()).isEqualTo("Add the units");
    }

    @Test
    void publishWithoutApprovalGoesLiveAtOnceWithNoApprover() throws Exception {
        found(row("DRAFT", 0, 1, json(false, List.of(text("a"))), 4));
        when(forms.update(eq(TENANT), eq(FORM), eq(4L), any(FormState.class), eq(USER))).thenReturn(true);

        service.publish(OWNER, KEY, new RevisionInput(4L));

        verify(forms).insertVersion(eq(TENANT), eq(FORM), eq(1), anyString(), eq(null), eq(USER));
        verify(audit).record(eq("EMR_FORM_PUBLISHED"), eq("EMR_FORM"), eq(FORM), any(), any());
    }

    @Test
    void aRetiredFormCannotBeSavedOrSubmittedAndRetireIsIdempotent() throws Exception {
        FormRow retired = new FormRow(FORM, KEY, "Foot check", null, "RETIRED", 1, 2, "NONE", null, null, null, null, 6,
                Instant.now(), USER, null);
        found(retired);
        when(forms.version(eq(TENANT), eq(FORM), anyInt())).thenReturn(Optional.of(new EmrFormRepository.VersionRow(
                UUID.randomUUID(), FORM, 1, json(true, List.of(text("a"))), null, null, Instant.now(), USER)));

        assertThatThrownBy(() -> service.saveDraft(OWNER, KEY, new DraftInput(6L, "Foot check", settings(true),
                List.of(text("a"))))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("form-state"));
        assertThat(service.retire(OWNER, KEY, new RevisionInput(6L)).state()).isEqualTo("RETIRED");
        verify(forms, never()).update(any(), any(), anyLong(), any(), any());
    }

    @Test
    void everyChangeIsRefusedForAnyoneButTheOwnerBeforeTheFormIsRead() {
        org.mockito.Mockito.doThrow(new ForbiddenException("forbidden", "Not allowed", "Only the owner."))
                .when(access).requireSetupEdit(OWNER);

        assertThatThrownBy(() -> service.publish(OWNER, KEY, new RevisionInput(1L))).isInstanceOf(ForbiddenException.class);
        verify(forms, never()).find(any(), anyString());
    }

    @Test
    void useThisStarterCopiesItIntoANewDraftAndTheSameKeyReturnsTheFirstForm() throws Exception {
        Definition starter = new Definition(1, "Glasgow Coma Scale", settings(true), List.of(text("a")));
        when(forms.starter("gcs")).thenReturn(Optional.of(new StarterRow("gcs", "Glasgow Coma Scale", "d", "s",
                EmrTestData.MAPPER.writeValueAsString(starter))));
        when(forms.findByIdempotencyKey(TENANT, "key-12345678")).thenReturn(Optional.empty());
        when(forms.insert(eq(TENANT), any(EmrFormRepository.NewForm.class), eq(USER))).thenReturn(FORM);
        when(forms.nameTaken(eq(TENANT), anyString(), any())).thenReturn(false);
        when(forms.find(eq(TENANT), anyString())).thenAnswer(call -> Optional.of(new FormRow(FORM, call.getArgument(1),
                "Glasgow Coma Scale", "gcs", "ACTIVE", 0, 1, "DRAFT",
                EmrTestData.MAPPER.valueToTree(starter).toString(), null, null, null, 0, Instant.now(), USER, "h")));

        EmrFormService.Created created = service.create(OWNER, "key-12345678", new EmrFormModel.CreateInput("gcs", null));

        assertThat(created.created()).isTrue();
        assertThat(created.form().state()).isEqualTo("DRAFT");
        ArgumentCaptor<EmrFormRepository.NewForm> newForm = ArgumentCaptor.forClass(EmrFormRepository.NewForm.class);
        verify(forms).insert(eq(TENANT), newForm.capture(), eq(USER));
        assertThat(newForm.getValue().formKey()).matches("^f_[a-z2-7]{10}$");
        assertThat(newForm.getValue().draftVersionNo()).isEqualTo(1);

        String hash = newForm.getValue().requestHash();
        when(forms.findByIdempotencyKey(TENANT, "key-12345678")).thenReturn(Optional.of(new FormRow(FORM, KEY, "G", "gcs",
                "ACTIVE", 0, 1, "DRAFT", EmrTestData.MAPPER.valueToTree(starter).toString(), null, null, null, 0,
                Instant.now(), USER, hash)));
        assertThat(service.create(OWNER, "key-12345678", new EmrFormModel.CreateInput("gcs", null)).created()).isFalse();
        assertThatThrownBy(() -> service.create(OWNER, "key-12345678", new EmrFormModel.CreateInput("morse", null)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("idempotency-conflict"));
    }

    @Test
    void aHundredFormsIsTheLimitAndAMissingIdempotencyKeyIs400() {
        when(forms.findByIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());
        when(forms.count(TENANT)).thenReturn(100);
        when(forms.starter("gcs")).thenReturn(Optional.of(new StarterRow("gcs", "G", "d", "s", "{}")));

        assertThatThrownBy(() -> service.create(OWNER, "key-12345678", new EmrFormModel.CreateInput("gcs", null)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("form-limit"));
        assertThatThrownBy(() -> service.create(OWNER, null, new EmrFormModel.CreateInput("gcs", null)))
                .isInstanceOf(InvalidFieldsException.class);
    }
}
