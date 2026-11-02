package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.application.PatientRepository.PatientPage;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-8 AC1, AC2, AC3, AC4, AC5, AC6, AC7, AC9: register, search, family, merge and audit. */
class PatientServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();

    private PatientRepository repository;
    private AuditLog auditLog;
    private PatientService service;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        auditLog = mock(AuditLog.class);
        service = new PatientService(repository, auditLog);
    }

    private static PatientRow row(UUID id, String name, int age, String mobile, UUID mergedIntoId) {
        return new PatientRow(id, "PT-00001", name, age, "F", mobile, null, "mr", null, null, false,
                mergedIntoId, Instant.now(), 0);
    }

    @Test
    void registeringAPatientTrimsTheNameDefaultsTheLanguageAndAudits() {
        when(repository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), "Asha Rao", 30, "9123450001", null)));
        when(repository.findFamily(eq(TENANT_ID), eq("9123450001"), any(UUID.class))).thenReturn(List.of());

        PatientView created = service.create(TENANT_ID, ACTOR,
                new PatientInput(" Asha Rao ", 30, "F", "9123450001", null, null, null, null, null));

        assertThat(created.name()).isEqualTo("Asha Rao");
        assertThat(created.language()).isEqualTo("mr");
        assertThat(created.possibleDuplicateOfId()).isNull();
        verify(repository).insert(any(UUID.class), eq("Asha Rao"), eq(30), eq("F"), eq("9123450001"), isNull(),
                eq("mr"), isNull(), isNull(), eq(false), eq(ACTOR));
        verify(auditLog).record(eq("PATIENT_REGISTERED"), eq("PATIENT"), any(UUID.class), isNull(), any());
    }

    @Test
    void missingOrInvalidRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> service.create(TENANT_ID, ACTOR,
                new PatientInput(" ", 30, "F", "9123450001", null, null, null, null, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.create(TENANT_ID, ACTOR,
                new PatientInput("Asha", 130, "F", "9123450001", null, null, null, null, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.create(TENANT_ID, ACTOR,
                new PatientInput("Asha", 30, "X", "9123450001", null, null, null, null, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.create(TENANT_ID, ACTOR,
                new PatientInput("Asha", 30, "F", "12345", null, null, null, null, null)))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).insert(any(), anyString(), anyInt(), anyString(), anyString(), any(), anyString(),
                any(), any(), anyBoolean(), any());
    }

    @Test
    void aSecondFamilyMemberUnderTheSameMobileIsNeverBlockedEvenAsAPossibleDuplicate() {
        UUID existingId = UUID.randomUUID();
        when(repository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), "Asha Rao", 30, "9123450001", null)));
        when(repository.findFamily(eq(TENANT_ID), eq("9123450001"), any(UUID.class)))
                .thenReturn(List.of(row(existingId, "Asha R", 31, "9123450001", null)));

        PatientView created = service.create(TENANT_ID, ACTOR,
                new PatientInput("Asha Rao", 30, "F", "9123450001", null, null, null, null, null));

        assertThat(created.possibleDuplicateOfId()).isEqualTo(existingId);
        verify(repository).insert(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                any());
    }

    @Test
    void aFamilyMemberWithADifferentNameOrFarApartAgeIsNotFlaggedAsADuplicate() {
        UUID existingId = UUID.randomUUID();
        when(repository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), "Asha Rao", 30, "9123450001", null)));
        when(repository.findFamily(eq(TENANT_ID), eq("9123450001"), any(UUID.class)))
                .thenReturn(List.of(row(existingId, "Ramesh Rao", 8, "9123450001", null)));

        PatientView created = service.create(TENANT_ID, ACTOR,
                new PatientInput("Asha Rao", 30, "F", "9123450001", null, null, null, null, null));

        assertThat(created.possibleDuplicateOfId()).isNull();
    }

    @Test
    void searchFiltersThatNeedVisitDataAlwaysReturnZeroWithoutTouchingTheRepository() {
        for (String filter : List.of("due", "missed", "overdue", "lapsed")) {
            PatientPage page = service.search(TENANT_ID, null, filter, 0);
            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
        }
        verify(repository, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void anUnknownFilterBehavesLikeAll() {
        when(repository.search(TENANT_ID, null, "all", 20, 0)).thenReturn(new PatientPage(List.of(), 0));

        service.search(TENANT_ID, null, "not-a-real-filter", 0);

        verify(repository).search(TENANT_ID, null, "all", 20, 0);
    }

    @Test
    void searchLowerCasesAndTrimsTheQuery() {
        when(repository.search(eq(TENANT_ID), eq("asha"), eq("all"), anyInt(), anyInt()))
                .thenReturn(new PatientPage(List.of(), 0));

        service.search(TENANT_ID, "  Asha  ", "all", 0);

        verify(repository).search(TENANT_ID, "asha", "all", 20, 0);
    }

    @Test
    void findingAPatientOfAnotherTenantIs404() {
        when(repository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(TENANT_ID, PATIENT_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void familyExcludesTheRequestedPatientAndUsesItsMobile() {
        when(repository.findById(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(row(PATIENT_ID, "Asha Rao", 30, "9123450001", null)));
        UUID siblingId = UUID.randomUUID();
        when(repository.findFamily(TENANT_ID, "9123450001", PATIENT_ID))
                .thenReturn(List.of(row(siblingId, "Ramesh Rao", 8, "9123450001", null)));

        List<PatientSummaryView> family = service.family(TENANT_ID, PATIENT_ID);

        assertThat(family).extracting(PatientSummaryView::id).containsExactly(siblingId);
    }

    @Test
    void updatingOnlySomeFieldsKeepsTheRestAndAuditsBeforeAndAfter() {
        PatientRow before = row(PATIENT_ID, "Asha Rao", 30, "9123450001", null);
        when(repository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.of(before),
                Optional.of(new PatientRow(PATIENT_ID, "PT-00001", "Asha Rao", 31, "F", "9123450001", null, "mr",
                        null, null, false, null, before.createdAt(), 1)));

        PatientView updated = service.update(TENANT_ID, ACTOR, PATIENT_ID,
                new PatientUpdateInput(null, 31, null, null, null, null, null, null));

        assertThat(updated.age()).isEqualTo(31);
        verify(repository).update(PATIENT_ID, "Asha Rao", 31, "F", null, "mr", null, null, false, ACTOR);
        verify(auditLog).record(eq("PATIENT_UPDATED"), eq("PATIENT"), eq(PATIENT_ID), any(), any());
    }

    @Test
    void anUpdateWithNothingNewWritesNothing() {
        when(repository.findById(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(row(PATIENT_ID, "Asha Rao", 30, "9123450001", null)));

        service.update(TENANT_ID, ACTOR, PATIENT_ID,
                new PatientUpdateInput("Asha Rao", 30, "F", null, "mr", null, null, false));

        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(),
                any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void mergingMovesTheLosingRecordsDataOntoTheSurvivorAndAuditsBoth() {
        UUID keepId = UUID.randomUUID();
        UUID mergeId = UUID.randomUUID();
        PatientRow keep = row(keepId, "Asha Rao", 30, "9123450001", null);
        PatientRow merge = row(mergeId, "Asha R", 30, "9123450001", null);
        when(repository.findById(TENANT_ID, keepId)).thenReturn(Optional.of(keep),
                Optional.of(new PatientRow(keepId, "PT-00001", "Asha R", 30, "F", "9123450001", null, "mr", null,
                        null, false, null, keep.createdAt(), 1)));
        when(repository.findById(TENANT_ID, mergeId)).thenReturn(Optional.of(merge),
                Optional.of(new PatientRow(mergeId, "PT-00002", "Asha R", 30, "F", "9123450001", null, "mr", null,
                        null, false, keepId, merge.createdAt(), 1)));

        PatientView result = service.merge(TENANT_ID, ACTOR, keepId, mergeId);

        assertThat(result.id()).isEqualTo(keepId);
        verify(repository).update(keepId, "Asha R", 30, "F", null, "mr", null, null, false, ACTOR);
        verify(repository).setMergedInto(mergeId, keepId, ACTOR);
        ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
        verify(auditLog, org.mockito.Mockito.times(2)).record(actions.capture(), eq("PATIENT"), any(), any(), any());
        assertThat(actions.getAllValues()).containsExactly("PATIENT_MERGED", "PATIENT_MERGED");
    }

    @Test
    void mergingAPatientIntoItselfIsRejected() {
        assertThatThrownBy(() -> service.merge(TENANT_ID, ACTOR, PATIENT_ID, PATIENT_ID))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).setMergedInto(any(), any(), any());
    }

    @Test
    void mergingAnAlreadyMergedRecordIsRejected() {
        UUID keepId = UUID.randomUUID();
        UUID mergeId = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        when(repository.findById(TENANT_ID, keepId)).thenReturn(Optional.of(row(keepId, "Asha Rao", 30, "9123450001", null)));
        when(repository.findById(TENANT_ID, mergeId))
                .thenReturn(Optional.of(row(mergeId, "Asha R", 30, "9123450001", someoneElse)));

        assertThatThrownBy(() -> service.merge(TENANT_ID, ACTOR, keepId, mergeId))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).setMergedInto(any(), any(), any());
    }

    @Test
    void anotherTenantsMergeRequestIs404() {
        when(repository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.merge(TENANT_ID, ACTOR, PATIENT_ID, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void mapValuesAreNotMutatedBetweenSnapshots() {
        PatientRow before = row(PATIENT_ID, "Asha Rao", 30, "9123450001", null);
        when(repository.findById(TENANT_ID, PATIENT_ID)).thenReturn(Optional.of(before), Optional.of(
                new PatientRow(PATIENT_ID, "PT-00001", "Asha Rao", 30, "F", "9123450001", null, "mr", "Dust",
                        null, false, null, before.createdAt(), 1)));

        service.update(TENANT_ID, ACTOR, PATIENT_ID,
                new PatientUpdateInput(null, null, null, null, null, "Dust", null, null));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_UPDATED"), eq("PATIENT"), eq(PATIENT_ID), any(), after.capture());
        assertThat(after.getValue()).containsEntry("allergy", "Dust");
    }
}
