package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrSettingsRepository.SettingsRow;
import com.sahaj.opd.application.emr.EmrViews.Settings;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-44-3: EMR settings with defaults before the first save, an optimistic version and one audit entry per change. */
class EmrSettingsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private EmrSettingsRepository repository;
    private EmrAccess access;
    private AuditLog auditLog;
    private EmrSettingsService service;
    private ClinicUser owner;

    @BeforeEach
    void setUp() {
        repository = mock(EmrSettingsRepository.class);
        access = mock(EmrAccess.class);
        auditLog = mock(AuditLog.class);
        service = new EmrSettingsService(repository, access, auditLog);
        owner = new ClinicUser(OWNER, TENANT, "OWNER");
        when(repository.find(TENANT)).thenReturn(Optional.empty());
        when(access.canEditSetup(any())).thenReturn(true);
    }

    @Test
    void beforeTheFirstSaveTheDefaultsAreReturnedAtVersionZero() {
        Settings settings = service.get(owner);

        assertThat(settings.sectionPanel()).isEqualTo("COLLAPSED");
        assertThat(settings.rightPanelFirst()).isEqualTo("CASE_PAPER");
        assertThat(settings.defaultPart()).isEqualTo("ASSESSMENT");
        assertThat(settings.version()).isZero();
        assertThat(settings.canEdit()).isTrue();
    }

    @Test
    void canEditFollowsTheFreshOwnerCheck() {
        when(access.canEditSetup(any())).thenReturn(false);
        assertThat(service.get(new ClinicUser(OWNER, TENANT, "DOCTOR")).canEdit()).isFalse();
    }

    @Test
    void theFirstSaveInsertsTheRowAndAuditsOnlyWhatChanged() {
        when(repository.insert(TENANT, "OPEN", "CASE_PAPER", OWNER)).thenReturn(true);
        when(repository.find(TENANT)).thenReturn(Optional.empty(), Optional.of(new SettingsRow("OPEN", "CASE_PAPER", 1)));

        Settings saved = service.save(owner, "OPEN", "CASE_PAPER", 0L);

        assertThat(saved.sectionPanel()).isEqualTo("OPEN");
        assertThat(saved.version()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("EMR_SETTINGS_UPDATED"), eq("EMR_SETTINGS"), eq(null), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsExactly(Map.entry("sectionPanel", "COLLAPSED"));
        assertThat(after.getValue()).containsExactly(Map.entry("sectionPanel", "OPEN"));
    }

    @Test
    void aLaterSaveUpdatesAtTheVersionTheCallerRead() {
        when(repository.find(TENANT)).thenReturn(Optional.of(new SettingsRow("OPEN", "CASE_PAPER", 4)),
                Optional.of(new SettingsRow("OPEN", "PAST_VISITS", 5)));
        when(repository.update(TENANT, "OPEN", "PAST_VISITS", 4L, OWNER)).thenReturn(true);

        Settings saved = service.save(owner, "OPEN", "PAST_VISITS", 4L);

        assertThat(saved.version()).isEqualTo(5);
        verify(repository, never()).insert(any(), any(), any(), any());
        verify(auditLog).record(eq("EMR_SETTINGS_UPDATED"), eq("EMR_SETTINGS"), eq(null),
                eq(Map.of("rightPanelFirst", "CASE_PAPER")), eq(Map.of("rightPanelFirst", "PAST_VISITS")));
    }

    @Test
    void aStaleVersionIs409AndNothingIsWritten() {
        when(repository.find(TENANT)).thenReturn(Optional.of(new SettingsRow("OPEN", "CASE_PAPER", 4)));

        assertThatThrownBy(() -> service.save(owner, "COLLAPSED", "CASE_PAPER", 3L))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("emr-setup-changed"));
        verify(repository, never()).update(any(), any(), any(), anyLong(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aSaveSentAtVersionZeroAfterSomeoneElsesFirstSaveIs409() {
        when(repository.find(TENANT)).thenReturn(Optional.of(new SettingsRow("OPEN", "CASE_PAPER", 1)));

        assertThatThrownBy(() -> service.save(owner, "COLLAPSED", "CASE_PAPER", 0L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void whenTheCompareAndSetLosesTheRaceTheAnswerIs409AndNoAuditIsWritten() {
        when(repository.insert(any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.save(owner, "OPEN", "CASE_PAPER", 0L)).isInstanceOf(ConflictException.class);

        when(repository.find(TENANT)).thenReturn(Optional.of(new SettingsRow("COLLAPSED", "CASE_PAPER", 2)));
        when(repository.update(TENANT, "OPEN", "CASE_PAPER", 2L, OWNER)).thenReturn(false);
        assertThatThrownBy(() -> service.save(owner, "OPEN", "CASE_PAPER", 2L)).isInstanceOf(ConflictException.class);

        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aSaveThatChangesNothingKeepsTheVersionAndWritesNoAudit() {
        when(repository.find(TENANT)).thenReturn(Optional.of(new SettingsRow("OPEN", "CASE_PAPER", 4)));

        Settings settings = service.save(owner, "OPEN", "CASE_PAPER", 4L);

        assertThat(settings.version()).isEqualTo(4);
        verify(repository, never()).update(any(), any(), any(), anyLong(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void savingTheDefaultsBeforeAnySaveCreatesNoRow() {
        Settings settings = service.save(owner, "COLLAPSED", "CASE_PAPER", 0L);

        assertThat(settings.version()).isZero();
        verify(repository, never()).insert(any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anyOtherValueIsAValidationErrorOnItsField() {
        assertThatThrownBy(() -> service.save(owner, "HALF", "CASE_PAPER", 0L))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field).containsExactly("sectionPanel"));
        assertThatThrownBy(() -> service.save(owner, "OPEN", "TOP", 0L))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field).containsExactly("rightPanelFirst"));
        assertThatThrownBy(() -> service.save(owner, null, null, null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field)
                        .containsExactly("sectionPanel", "rightPanelFirst", "version"));
        assertThatThrownBy(() -> service.save(owner, "OPEN", "CASE_PAPER", -1L))
                .isInstanceOf(InvalidFieldsException.class);
        verify(repository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void onlyTheOwnerSaves() {
        doThrow(new AccessDeniedException("no")).when(access).requireSetupEdit(any());

        assertThatThrownBy(() -> service.save(owner, "OPEN", "CASE_PAPER", 0L))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).insert(any(), any(), any(), any());
    }
}
