package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC11: size, language, letterhead, capitals and sections; Medicines (Rx) can never be switched off. */
class CasePaperServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();

    private CasePaperRepository repository;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private CasePaperService service;

    @BeforeEach
    void setUp() {
        repository = mock(CasePaperRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new CasePaperService(repository, ownerGuard, auditLog);
    }

    @Test
    void withoutARowTheDefaultsApply() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());

        PaperRow paper = service.get(TENANT_ID);

        assertThat(paper.size()).isEqualTo("A5");
        assertThat(paper.secondLanguage()).isNull();
        assertThat(paper.preprinted()).isFalse();
        assertThat(paper.caps()).isFalse();
        assertThat(paper.sections()).containsEntry("rx", true).containsEntry("history", false)
                .containsEntry("complaints", true).hasSize(9);
        assertThat(paper.sections().keySet()).containsExactly("complaints", "vitals", "history", "exam", "diagnosis",
                "inv", "rx", "advice", "next");
    }

    @Test
    void ownerSavesSettingsAndAMissingSectionKeepsItsDefault() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
        Map<String, Boolean> sections = new LinkedHashMap<>();
        sections.put("history", true);
        sections.put("advice", false);

        PaperRow saved = service.save(TENANT_ID, OWNER_USER, new CasePaperInput("A4", "mr", true, true, sections));

        assertThat(saved.size()).isEqualTo("A4");
        assertThat(saved.secondLanguage()).isEqualTo("mr");
        assertThat(saved.sections()).containsEntry("history", true).containsEntry("advice", false)
                .containsEntry("rx", true).containsEntry("vitals", true).hasSize(9);
        ArgumentCaptor<PaperRow> row = ArgumentCaptor.forClass(PaperRow.class);
        verify(repository).upsert(eq(TENANT_ID), row.capture(), eq(OWNER_USER));
        assertThat(row.getValue()).isEqualTo(saved);
        verify(auditLog).record(eq("CASE_PAPER_SETTINGS_UPDATED"), eq("CASE_PAPER_SETTINGS"), eq(TENANT_ID), any(),
                any());
    }

    @Test
    void medicinesRxCannotBeSwitchedOff() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A5", null, false, false, Map.of("rx", false))))
                .isInstanceOf(InvalidInputException.class).hasMessage("Medicines (Rx) cannot be switched off");
        verify(repository, never()).upsert(any(), any(), any());
    }

    @Test
    void sizeLanguageAndSectionKeysMustBeFromTheLists() {
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A3", null, false, false, Map.of())))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A5", "fr", false, false, Map.of())))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A5", null, false, false, Map.of("logo", true))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A5", null, null, false, Map.of())))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aNonOwnerCannotSave() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.save(TENANT_ID, OWNER_USER,
                new CasePaperInput("A5", null, false, false, Map.of())))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).upsert(any(), any(), any());
    }
}
