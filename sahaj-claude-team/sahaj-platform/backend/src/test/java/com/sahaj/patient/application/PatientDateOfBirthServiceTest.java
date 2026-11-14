package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientAge.Unit;
import com.sahaj.patient.application.PatientRepository.PatientPage;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-73 (ADR-024 D1, D4, D5, D6): the optional date of birth on register, update and merge: the stored age is the
 * completed years from the date, the refusals and their messages, the three states on update, the audit event that holds
 * no date, and the audit snapshots that hold no name, mobile number or date. Fixed day: 13 Nov 2026 in India.
 */
class PatientDateOfBirthServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    /** 13 Nov 2026, 11:30 AM in India. */
    private static final Instant NOW = Instant.parse("2026-11-13T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final LocalDate JUNE_2025 = LocalDate.of(2025, 6, 10);
    private static final String MOBILE = "9123450001";

    private PatientRepository repository;
    private AuditLog auditLog;
    private PatientService service;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        auditLog = mock(AuditLog.class);
        service = new PatientService(repository, mock(ConsentRepository.class), mock(RecordAccessRepository.class),
                auditLog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PatientRow row(UUID id, String name, int storedAge, LocalDate dateOfBirth, UUID mergedInto) {
        return new PatientRow(id, "PT-00001", name, storedAge, "F", MOBILE, "Kothrud", "mr", null, null, false,
                mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 1, dateOfBirth);
    }

    private static PatientRow row(UUID id, String name, int storedAge, LocalDate dateOfBirth) {
        return row(id, name, storedAge, dateOfBirth, null);
    }

    private static PatientInput input(Integer age, DateOfBirthInput dateOfBirth) {
        return new PatientInput("Asha Rao", age, "F", MOBILE, null, null, null, null, null, List.of(), dateOfBirth);
    }

    private static PatientUpdateInput patch(Integer age, DateOfBirthInput dateOfBirth) {
        return new PatientUpdateInput(null, age, null, null, null, null, null, null, dateOfBirth);
    }

    private static DateOfBirthInput on(LocalDate date) {
        return DateOfBirthInput.of(date);
    }

    private void createReturning(LocalDate storedDate, int storedAge) {
        when(repository.findById(eq(TENANT), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), "Asha Rao", storedAge, storedDate)));
        when(repository.findFamily(eq(TENANT), eq(MOBILE), any(UUID.class))).thenReturn(List.of());
    }

    private void assertRefused(PatientInput input, String field, String message) {
        assertThatThrownBy(() -> service.create(TENANT, ACTOR, input)).isInstanceOfSatisfying(
                InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
        verify(repository, never()).insert(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- register (SAH-73-1) ----

    @Test
    void registeringWithADateOfBirthStoresTheCompletedYearsAndAnswersWithTheWorkedOutAge() {
        createReturning(JUNE_2025, 1);

        PatientView created = service.create(TENANT, ACTOR, input(null, on(JUNE_2025)));

        verify(repository).insert(any(UUID.class), eq("Asha Rao"), eq(1), eq(JUNE_2025), eq("F"), eq(MOBILE), any(),
                eq("mr"), any(), any(), eq(false), eq(ACTOR));
        assertThat(created.dateOfBirth()).isEqualTo(JUNE_2025);
        assertThat(created.age()).isEqualTo(1);
        assertThat(created.ageDetail().unit()).isEqualTo(Unit.MONTHS);
        assertThat(created.ageDetail().value()).as("10 Jun 2025 on 13 Nov 2026").isEqualTo(17);
        assertThat(created.ageDetail().fromDateOfBirth()).isTrue();
    }

    @Test
    void anAgeAloneIsRegisteredAsBeforeWithNoDate() {
        createReturning(null, 34);

        PatientView created = service.create(TENANT, ACTOR, input(34, DateOfBirthInput.ABSENT));

        verify(repository).insert(any(UUID.class), eq("Asha Rao"), eq(34), org.mockito.Mockito.isNull(), eq("F"),
                eq(MOBILE), any(), eq("mr"), any(), any(), eq(false), eq(ACTOR));
        assertThat(created.dateOfBirth()).isNull();
        assertThat(created.ageDetail().unit()).isEqualTo(Unit.YEARS);
        assertThat(created.ageDetail().fromDateOfBirth()).isFalse();
    }

    @Test
    void anExplicitNullDateOfBirthOnRegistrationIsTheSameAsNoDate() {
        createReturning(null, 34);

        service.create(TENANT, ACTOR, input(34, DateOfBirthInput.CLEARED));

        verify(repository).insert(any(UUID.class), eq("Asha Rao"), eq(34), org.mockito.Mockito.isNull(), eq("F"),
                eq(MOBILE), any(), eq("mr"), any(), any(), eq(false), eq(ACTOR));
    }

    @Test
    void neitherADateNorAnAgeIsRefusedOnTheAgeWithTheSah8Text() {
        assertRefused(input(null, DateOfBirthInput.ABSENT), "age", "Enter an age between 0 and 120");
        assertRefused(input(null, DateOfBirthInput.CLEARED), "age", "Enter an age between 0 and 120");
        assertRefused(input(130, DateOfBirthInput.ABSENT), "age", "Enter an age between 0 and 120");
    }

    @Test
    void aDateThatCannotBeReadIsRefusedWithTheValidDateMessageAndNeverEchoesIt() {
        assertRefused(input(null, DateOfBirthInput.INVALID), "dateOfBirth", "Enter a valid date of birth.");
    }

    @Test
    void aDateInTheFutureIsRefusedButTodayIsAccepted() {
        assertRefused(input(null, on(TODAY.plusDays(1))), "dateOfBirth", "Date of birth cannot be in the future.");
        createReturning(TODAY, 0);

        PatientView born = service.create(TENANT, ACTOR, input(null, on(TODAY)));

        assertThat(born.age()).isZero();
        assertThat(born.ageDetail().unit()).isEqualTo(Unit.DAYS);
        assertThat(born.ageDetail().value()).isZero();
    }

    @Test
    void aDateMoreThan120YearsAgoIsRefusedAndExactly120IsAccepted() {
        assertRefused(input(null, on(LocalDate.of(1905, 11, 13))), "dateOfBirth",
                "Date of birth must be within the last 120 years.");
        createReturning(LocalDate.of(1906, 11, 13), 120);

        PatientView oldest = service.create(TENANT, ACTOR, input(null, on(LocalDate.of(1906, 11, 13))));

        assertThat(oldest.age()).isEqualTo(120);
    }

    @Test
    void anAgeThatDisagreesWithTheDateIsRefusedOnTheAgeAndAMatchingOneIsAccepted() {
        assertRefused(input(5, on(JUNE_2025)), "age", "Age does not match the date of birth.");
        createReturning(JUNE_2025, 1);

        service.create(TENANT, ACTOR, input(1, on(JUNE_2025)));

        verify(repository).insert(any(UUID.class), any(), eq(1), eq(JUNE_2025), any(), any(), any(), any(), any(),
                any(), anyBoolean(), any());
    }

    @Test
    void theDateIsCheckedBeforeTheAge() {
        assertRefused(input(500, DateOfBirthInput.INVALID), "dateOfBirth", "Enter a valid date of birth.");
    }

    @Test
    void theRegistrationAuditHoldsNoNameMobileDateOrAgeOfADatedPatient() {
        createReturning(JUNE_2025, 1);

        service.create(TENANT, ACTOR, input(null, on(JUNE_2025)));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_REGISTERED"), eq("PATIENT"), any(UUID.class), org.mockito.Mockito.isNull(),
                after.capture());
        assertThat(after.getValue()).containsEntry("dateOfBirthRecorded", true)
                .doesNotContainKeys("name", "mobile", "dateOfBirth", "age", "area");
        assertThat(after.getValue().toString()).doesNotContain("Asha").doesNotContain(MOBILE)
                .doesNotContain("2025").doesNotContain("Kothrud");
        verify(auditLog, times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    void theRegistrationAuditOfAnUndatedPatientKeepsTheAgeButNoNameOrMobile() {
        createReturning(null, 34);

        service.create(TENANT, ACTOR, input(34, DateOfBirthInput.ABSENT));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_REGISTERED"), eq("PATIENT"), any(UUID.class), org.mockito.Mockito.isNull(),
                after.capture());
        assertThat(after.getValue()).containsEntry("age", 34).containsEntry("dateOfBirthRecorded", false)
                .doesNotContainKeys("name", "mobile");
        assertThat(after.getValue().get("changedFields").toString()).doesNotContain("Asha").doesNotContain(MOBILE);
    }

    @Test
    void theDuplicateCheckComparesTheWorkedOutYearsOfBothSidesNeverTheDates() {
        UUID childId = UUID.randomUUID();
        UUID elderId = UUID.randomUUID();
        // the new patient is 1 (born 10 Jun 2025). The candidate "Asha R" is 1 by her date but her stored age is stale (30).
        createReturning(JUNE_2025, 1);
        when(repository.findFamily(eq(TENANT), eq(MOBILE), any(UUID.class))).thenReturn(List.of(
                row(elderId, "Ramesh Rao", 1, LocalDate.of(1980, 1, 1)), row(childId, "Asha R", 30, LocalDate.of(2025, 1, 1))));

        PatientView created = service.create(TENANT, ACTOR, input(null, on(JUNE_2025)));

        assertThat(created.possibleDuplicateOfId()).isEqualTo(childId);
    }

    // ---- update (SAH-73-2) ----

    private PatientRow stubUpdate(PatientRow before, PatientRow after) {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(before), Optional.of(after));
        return after;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dateEventAfter(String change) {
        ArgumentCaptor<Map<String, Object>> beforeValues = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), eq("PATIENT"), eq(PATIENT),
                beforeValues.capture(), afterValues.capture());
        Map<String, Object> after = afterValues.getValue();
        assertThat(after).containsEntry("field", "dateOfBirth").containsEntry("change", change);
        assertThat(beforeValues.getValue()).containsEntry("field", "dateOfBirth").doesNotContainKey("change");
        assertThat(beforeValues.getValue().keySet()).containsOnly("field", "dateOfBirthRecorded");
        assertThat(after.keySet()).containsOnly("field", "dateOfBirthRecorded", "change");
        // never the date, never an age (an age with a known day narrows the date)
        assertThat(beforeValues.getValue().toString() + after).doesNotContain("20").doesNotContain("Asha");
        return after;
    }

    @Test
    void addingADateToARecordWithOnlyAnAgeWritesTheWorkedOutYearsAndOneSetEvent() {
        stubUpdate(row(PATIENT, "Asha Rao", 5, null), row(PATIENT, "Asha Rao", 6, LocalDate.of(2020, 3, 4)));

        PatientView saved = service.update(TENANT, ACTOR, PATIENT, patch(null, on(LocalDate.of(2020, 3, 4))));

        verify(repository).update(eq(PATIENT), eq("Asha Rao"), eq(6), eq(LocalDate.of(2020, 3, 4)), eq("F"),
                eq("Kothrud"), eq("mr"), any(), any(), eq(false), eq(ACTOR));
        assertThat(saved.age()).isEqualTo(6);
        assertThat(saved.dateOfBirth()).isEqualTo(LocalDate.of(2020, 3, 4));
        assertThat(dateEventAfter("SET")).containsEntry("dateOfBirthRecorded", true);
        verify(auditLog, never()).record(eq("PATIENT_UPDATED"), any(), any(), any(), any());
    }

    @Test
    void correctingTheDateWritesOneChangedEventAndTheAgeFollowsTheNewDate() {
        stubUpdate(row(PATIENT, "Asha Rao", 1, JUNE_2025), row(PATIENT, "Asha Rao", 0, LocalDate.of(2026, 5, 1)));

        PatientView saved = service.update(TENANT, ACTOR, PATIENT, patch(null, on(LocalDate.of(2026, 5, 1))));

        verify(repository).update(eq(PATIENT), any(), eq(0), eq(LocalDate.of(2026, 5, 1)), any(), any(), any(), any(),
                any(), anyBoolean(), any());
        assertThat(saved.ageDetail().unit()).isEqualTo(Unit.MONTHS);
        assertThat(saved.ageDetail().value()).isEqualTo(6);
        assertThat(dateEventAfter("CHANGED")).containsEntry("dateOfBirthRecorded", true);
    }

    @Test
    void sendingTheSameDateAgainWritesNothingAndNoEvent() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 1, JUNE_2025)));

        service.update(TENANT, ACTOR, PATIENT, patch(null, on(JUNE_2025)));

        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void clearingTheDateWithoutAnAgeKeepsTheCompletedYearsWorkedOutFromTheOldDateToday() {
        stubUpdate(row(PATIENT, "Asha Rao", 5, LocalDate.of(2020, 3, 4)), row(PATIENT, "Asha Rao", 6, null));

        PatientView saved = service.update(TENANT, ACTOR, PATIENT, patch(null, DateOfBirthInput.CLEARED));

        verify(repository).update(eq(PATIENT), any(), eq(6), org.mockito.Mockito.isNull(), any(), any(), any(), any(),
                any(), anyBoolean(), any());
        assertThat(saved.dateOfBirth()).isNull();
        assertThat(saved.ageDetail().fromDateOfBirth()).isFalse();
        assertThat(dateEventAfter("CLEARED")).containsEntry("dateOfBirthRecorded", false);
    }

    @Test
    void clearingTheDateWithAnAgeStoresThatAge() {
        stubUpdate(row(PATIENT, "Asha Rao", 5, LocalDate.of(2020, 3, 4)), row(PATIENT, "Asha Rao", 7, null));

        service.update(TENANT, ACTOR, PATIENT, patch(7, DateOfBirthInput.CLEARED));

        verify(repository).update(eq(PATIENT), any(), eq(7), org.mockito.Mockito.isNull(), any(), any(), any(), any(),
                any(), anyBoolean(), any());
        dateEventAfter("CLEARED");
    }

    @Test
    void clearingAnAlreadyEmptyDateIsANoOp() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 30, null)));

        service.update(TENANT, ACTOR, PATIENT, patch(null, DateOfBirthInput.CLEARED));

        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anAgeSentWhileTheRecordKeepsItsDateMustMatchItsCompletedYears() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 1, JUNE_2025)));

        assertThatThrownBy(() -> service.update(TENANT, ACTOR, PATIENT, patch(9, DateOfBirthInput.ABSENT)))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("age");
                    assertThat(e.getMessage()).isEqualTo("Age does not match the date of birth.");
                });
        assertThatThrownBy(() -> service.update(TENANT, ACTOR, PATIENT, patch(9, on(JUNE_2025))))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());

        service.update(TENANT, ACTOR, PATIENT, patch(1, DateOfBirthInput.ABSENT));
        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
    }

    @Test
    void anUnreadableFutureOrTooOldDateOnUpdateIsRefusedWithTheSameMessagesAndNothingIsWritten() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 30, null)));
        Object[][] cases = {
                {DateOfBirthInput.INVALID, "Enter a valid date of birth."},
                {on(TODAY.plusDays(1)), "Date of birth cannot be in the future."},
                {on(LocalDate.of(1905, 1, 1)), "Date of birth must be within the last 120 years."}};
        for (Object[] c : cases) {
            assertThatThrownBy(() -> service.update(TENANT, ACTOR, PATIENT, patch(null, (DateOfBirthInput) c[0])))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("dateOfBirth");
                        assertThat(e.getMessage()).isEqualTo(c[1]);
                    });
        }
        verify(repository, never()).update(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anotherFieldChangedOnADatedPatientRefreshesTheStoredAgeAndAuditsNoDateEvent() {
        // stored 0 is stale: born 10 Jun 2025 is 1 today
        stubUpdate(row(PATIENT, "Asha Rao", 0, JUNE_2025), row(PATIENT, "Asha Kale", 1, JUNE_2025));

        service.update(TENANT, ACTOR, PATIENT,
                new PatientUpdateInput("Asha Kale", null, null, null, null, null, null, null, DateOfBirthInput.ABSENT));

        verify(repository).update(eq(PATIENT), eq("Asha Kale"), eq(1), eq(JUNE_2025), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        ArgumentCaptor<Map<String, Object>> beforeValues = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_UPDATED"), eq("PATIENT"), eq(PATIENT), beforeValues.capture(),
                afterValues.capture());
        verify(auditLog, never()).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), any(), any(), any(), any());
        assertThat(afterValues.getValue().get("changedFields")).isEqualTo(List.of("name"));
        for (Map<String, Object> values : List.of(beforeValues.getValue(), afterValues.getValue())) {
            assertThat(values).doesNotContainKeys("name", "mobile", "dateOfBirth", "age", "area")
                    .containsEntry("dateOfBirthRecorded", true);
            assertThat(values.toString()).doesNotContain("Asha").doesNotContain(MOBILE).doesNotContain("2025");
        }
    }

    @Test
    void aDateAndAnotherFieldChangedTogetherWriteBothEventsAndTheUpdateEventNamesOnlyTheOtherField() {
        stubUpdate(row(PATIENT, "Asha Rao", 5, null), row(PATIENT, "Asha Kale", 6, LocalDate.of(2020, 3, 4)));

        service.update(TENANT, ACTOR, PATIENT, new PatientUpdateInput("Asha Kale", null, null, null, null, null, null,
                null, on(LocalDate.of(2020, 3, 4))));

        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_UPDATED"), eq("PATIENT"), eq(PATIENT), any(), afterValues.capture());
        assertThat(afterValues.getValue().get("changedFields")).isEqualTo(List.of("name"));
        dateEventAfter("SET");
    }

    @Test
    void anUndatedAgeChangeIsAuditedWithTheAgeFieldNameAndTheNumber() {
        stubUpdate(row(PATIENT, "Asha Rao", 30, null), row(PATIENT, "Asha Rao", 31, null));

        service.update(TENANT, ACTOR, PATIENT, patch(31, DateOfBirthInput.ABSENT));

        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_UPDATED"), eq("PATIENT"), eq(PATIENT), any(), afterValues.capture());
        assertThat(afterValues.getValue().get("changedFields")).isEqualTo(List.of("age"));
        assertThat(afterValues.getValue()).containsEntry("age", 31);
        verify(auditLog, never()).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), any(), any(), any(), any());
    }

    // ---- merge (SAH-73-2, BR73-11) ----

    private final UUID keepId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID mergeId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private MergeOutcome merge(PatientRow keep, PatientRow merge, PatientRow keepAfter) {
        when(repository.findById(TENANT, keepId)).thenReturn(Optional.of(keep), Optional.of(keepAfter));
        when(repository.findById(TENANT, mergeId)).thenReturn(Optional.of(merge),
                Optional.of(row(mergeId, "Asha R", merge.age(), merge.dateOfBirth(), keepId)));
        return service.mergeWithNotice(TENANT, ACTOR, keepId, mergeId);
    }

    @Test
    void theKeptRecordWithNoDateTakesTheMergedRecordsDateAndItsAgeIsWorkedOutFromIt() {
        PatientRow keep = row(keepId, "Asha Rao", 5, null);
        PatientRow merged = row(mergeId, "Asha R", 5, LocalDate.of(2020, 3, 4));

        MergeOutcome outcome = merge(keep, merged, row(keepId, "Asha R", 6, LocalDate.of(2020, 3, 4)));

        verify(repository).update(eq(keepId), eq("Asha R"), eq(6), eq(LocalDate.of(2020, 3, 4)), any(), any(), any(),
                any(), any(), anyBoolean(), eq(ACTOR));
        assertThat(outcome.datesOfBirthDiffered()).isFalse();
        assertThat(outcome.patient().dateOfBirth()).isEqualTo(LocalDate.of(2020, 3, 4));
        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), eq("PATIENT"), eq(keepId), any(),
                afterValues.capture());
        assertThat(afterValues.getValue()).containsEntry("change", "SET");
        verify(auditLog, times(2)).record(eq("PATIENT_MERGED"), eq("PATIENT"), any(), any(), any());
    }

    @Test
    void whenBothRecordsHaveDifferentDatesTheKeptDateStaysAndTheAnswerSaysSo() {
        LocalDate keptDate = LocalDate.of(2020, 3, 4);
        PatientRow keep = row(keepId, "Asha Rao", 6, keptDate);
        PatientRow merged = row(mergeId, "Asha R", 3, LocalDate.of(2023, 1, 1));

        MergeOutcome outcome = merge(keep, merged, row(keepId, "Asha R", 6, keptDate));

        verify(repository).update(eq(keepId), eq("Asha R"), eq(6), eq(keptDate), any(), any(), any(), any(), any(),
                anyBoolean(), eq(ACTOR));
        assertThat(outcome.datesOfBirthDiffered()).isTrue();
        assertThat(outcome.patient().dateOfBirth()).isEqualTo(keptDate);
        verify(auditLog, never()).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), any(), any(), any(), any());
    }

    @Test
    void twoEqualDatesAreNotADifferenceAndAMergedRecordWithNoDateLeavesTheKeptDateAlone() {
        LocalDate date = LocalDate.of(2020, 3, 4);

        assertThat(merge(row(keepId, "Asha Rao", 6, date), row(mergeId, "Asha R", 6, date),
                row(keepId, "Asha R", 6, date)).datesOfBirthDiffered()).isFalse();
        verify(repository).update(eq(keepId), any(), eq(6), eq(date), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        verify(auditLog, never()).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), any(), any(), any(), any());
    }

    @Test
    void aKeptDatedRecordTakesItsAgeFromItsDateNotFromTheMergedRecordsTypedAge() {
        LocalDate date = LocalDate.of(2020, 3, 4);

        MergeOutcome outcome = merge(row(keepId, "Asha Rao", 2, date), row(mergeId, "Asha R", 40, null),
                row(keepId, "Asha R", 6, date));

        verify(repository).update(eq(keepId), any(), eq(6), eq(date), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        assertThat(outcome.datesOfBirthDiffered()).isFalse();
    }

    @Test
    void withNoDateOnEitherSideTheMergeIsTheSah8OneAndTheMergedAgeMovesAcross() {
        MergeOutcome outcome = merge(row(keepId, "Asha Rao", 30, null), row(mergeId, "Asha R", 31, null),
                row(keepId, "Asha R", 31, null));

        verify(repository).update(eq(keepId), eq("Asha R"), eq(31), org.mockito.Mockito.isNull(), any(), any(), any(),
                any(), any(), anyBoolean(), eq(ACTOR));
        assertThat(outcome.datesOfBirthDiffered()).isFalse();
        verify(auditLog, never()).record(eq("PATIENT_DATE_OF_BIRTH_CHANGED"), any(), any(), any(), any());
    }

    @Test
    void theMergeAuditSnapshotsHoldNoNameMobileDateOrAgeOfADatedRecord() {
        LocalDate date = LocalDate.of(2020, 3, 4);

        merge(row(keepId, "Asha Rao", 5, date), row(mergeId, "Asha R", 3, null), row(keepId, "Asha R", 6, date));

        ArgumentCaptor<Map<String, Object>> beforeValues = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> afterValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLog, times(2)).record(eq("PATIENT_MERGED"), eq("PATIENT"), any(), beforeValues.capture(),
                afterValues.capture());
        StringBuilder everything = new StringBuilder();
        beforeValues.getAllValues().forEach(everything::append);
        afterValues.getAllValues().forEach(everything::append);
        assertThat(everything.toString()).doesNotContain("Asha").doesNotContain(MOBILE).doesNotContain("2020")
                .doesNotContain("Kothrud");
        for (Map<String, Object> values : afterValues.getAllValues()) {
            assertThat(values).doesNotContainKeys("name", "mobile", "dateOfBirth");
        }
        // the kept record's snapshot carries no age (it is dated); the merged-away record (undated) keeps its age
        assertThat(afterValues.getAllValues().get(0)).doesNotContainKey("age");
        assertThat(afterValues.getAllValues().get(1)).containsEntry("age", 3);
    }

    // ---- reads ----

    @Test
    void aReadAnswersWithTheAgeWorkedOutFromTheDateNeverTheStaleStoredNumber() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 5,
                LocalDate.of(2020, 3, 4))));

        PatientView view = service.find(TENANT, PATIENT);

        assertThat(view.age()).isEqualTo(6);
        assertThat(view.ageDetail().value()).isEqualTo(6);
        assertThat(view.dateOfBirth()).isEqualTo(LocalDate.of(2020, 3, 4));
        assertThat(view.toString()).doesNotContain("Asha").doesNotContain("2020").doesNotContain(MOBILE);
    }

    @Test
    void anUndatedReadKeepsTheStoredAgeInYears() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Asha Rao", 34, null)));

        PatientView view = service.find(TENANT, PATIENT);

        assertThat(view.age()).isEqualTo(34);
        assertThat(view.dateOfBirth()).isNull();
        assertThat(view.ageDetail().unit()).isEqualTo(Unit.YEARS);
    }

    @Test
    void theListAndTheFamilyCarryTheWorkedOutAgeDetailAndNoDateOfBirth() {
        PatientRow child = row(UUID.randomUUID(), "Asha Rao", 1, JUNE_2025);
        PatientRow adult = row(UUID.randomUUID(), "Ramesh Rao", 34, null);
        when(repository.search(TENANT, null, "kids", 20, 0, TODAY)).thenReturn(new PatientPage(List.of(child, adult), 2));
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Meena", 30, null)));
        when(repository.findFamily(TENANT, MOBILE, PATIENT)).thenReturn(List.of(child));

        PatientSummaryPage page = service.search(TENANT, null, "kids", 0);
        List<PatientSummaryView> family = service.family(TENANT, PATIENT);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).ageDetail().unit()).isEqualTo(Unit.MONTHS);
        assertThat(page.items().get(0).ageDetail().value()).isEqualTo(17);
        assertThat(page.items().get(0).age()).isEqualTo(1);
        assertThat(page.items().get(1).ageDetail().unit()).isEqualTo(Unit.YEARS);
        assertThat(page.items().get(1).age()).isEqualTo(34);
        assertThat(family.get(0).ageDetail().value()).isEqualTo(17);
        assertThat(page.items().get(0).toString() + family.get(0)).doesNotContain("2025").doesNotContain("Asha");
        for (java.lang.reflect.RecordComponent component : PatientSummaryView.class.getRecordComponents()) {
            assertThat(component.getName()).as("a list row never has the date").isNotEqualToIgnoringCase("dateOfBirth");
        }
    }

    @Test
    void theChildrenAndSeniorsFiltersAreGivenTodayInIndiaToWorkTheDateBoundsOut() {
        when(repository.search(any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(new PatientPage(List.of(), 0));
        // 06:00 UTC on 13 Nov is 11:30 AM in India; a clock at 20:00 UTC on 12 Nov is already the 13th in India
        PatientService lateEvening = new PatientService(repository, mock(ConsentRepository.class),
                mock(RecordAccessRepository.class), auditLog,
                Clock.fixed(Instant.parse("2026-11-12T20:00:00Z"), ZoneOffset.UTC));

        lateEvening.search(TENANT, null, "seniors", 0);

        verify(repository).search(TENANT, null, "seniors", 20, 0, TODAY);
    }
}
