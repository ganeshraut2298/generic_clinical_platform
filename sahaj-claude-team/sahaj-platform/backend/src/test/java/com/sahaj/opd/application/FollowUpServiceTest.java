package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.FollowUpPort;
import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.opd.application.FollowUpRepository.Counts;
import com.sahaj.opd.application.FollowUpRepository.FollowUpRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.web.FollowUpResponse;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-14 Follow-up lists: create, close, computed status, counts. */
class FollowUpServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final String MOBILE = "9000000001";

    private FollowUpRepository repository;
    private FeeSettingsRepository feeSettingsRepository;
    private PatientLookupPort patientLookup;
    private TeamMemberPort teamMemberPort;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        repository = mock(FollowUpRepository.class);
        feeSettingsRepository = mock(FeeSettingsRepository.class);
        patientLookup = mock(PatientLookupPort.class);
        teamMemberPort = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        when(feeSettingsRepository.find(TENANT_ID)).thenReturn(Optional.empty());
        when(patientLookup.find(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(new PatientSummary(PATIENT_ID, "Asha Rao", 30, "F", MOBILE)));
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
    }

    private FollowUpService serviceAt(String instant) {
        return new FollowUpService(repository, feeSettingsRepository, patientLookup, teamMemberPort, auditLog,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static FollowUpRow row(LocalDate due, UUID returnedVisitId) {
        return new FollowUpRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, UUID.randomUUID(), null, due,
                "Review after treatment", returnedVisitId);
    }

    // --- create ---

    @Test
    void dueDateIsTheVisitsIstDayPlusTheClinicsFollowUpDays() {
        when(feeSettingsRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new FeeRow("before", 10, "free", Map.of(), true)));
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        // 20:00 UTC on 1 Nov is 01:30 IST on 2 Nov.
        VisitRow visit = new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 0,
                Instant.parse("2026-11-01T20:00:00Z"), 1);

        serviceAt("2026-11-01T20:00:00Z").createForConsultDone(TENANT_ID, USER_ID, visit);

        verify(repository).insertIfAbsent(any(), eq(PATIENT_ID), eq(DOCTOR_ID), eq(visit.id()),
                eq(LocalDate.of(2026, 11, 12)), eq("Review after treatment"), eq(USER_ID));
    }

    @Test
    void withNoFeeSettingsRowTheDefaultWindowIsUsed() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        VisitRow visit = new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 0,
                Instant.parse("2026-11-01T06:00:00Z"), 1);

        serviceAt("2026-11-01T06:00:00Z").createForConsultDone(TENANT_ID, USER_ID, visit);

        verify(repository).insertIfAbsent(any(), any(), any(), any(),
                eq(LocalDate.of(2026, 11, 1).plusDays(FeeSettingsService.DEFAULTS.followUpDays())), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void creatingAuditsIdsOnly() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        VisitRow visit = new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 0,
                Instant.parse("2026-11-01T06:00:00Z"), 1);

        serviceAt("2026-11-01T06:00:00Z").createForConsultDone(TENANT_ID, USER_ID, visit);

        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("FOLLOW_UP_CREATED"), eq("FOLLOW_UP"), any(UUID.class), eq(null), after.capture());
        Map<String, Object> values = after.getValue();
        assertThat(values).containsEntry("sourceVisitId", visit.id()).containsEntry("patientId", PATIENT_ID)
                .doesNotContainKeys("reason", "mobile", "patientName", "name");
        assertThat(values.values()).doesNotContain(MOBILE, "Review after treatment");
    }

    @Test
    void aRetriedMoveHitsTheConflictAndAuditsNothing() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        VisitRow visit = new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 0,
                Instant.parse("2026-11-01T06:00:00Z"), 1);

        serviceAt("2026-11-01T06:00:00Z").createForConsultDone(TENANT_ID, USER_ID, visit);

        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // --- close ---

    @Test
    void closingAuditsTheReturnWithIdsOnlyWhenOneWasClosed() {
        UUID closed = UUID.randomUUID();
        UUID newVisit = UUID.randomUUID();
        when(repository.closeOldestOpen(TENANT_ID, PATIENT_ID, DOCTOR_ID, newVisit, USER_ID))
                .thenReturn(Optional.of(closed));

        serviceAt("2026-11-01T06:00:00Z").closeOnNewVisit(TENANT_ID, USER_ID, PATIENT_ID, DOCTOR_ID, newVisit);

        verify(auditLog).record("FOLLOW_UP_RETURNED", "FOLLOW_UP", closed, null, Map.of("returnedVisitId", newVisit));
    }

    @Test
    void closingWithNothingOpenAuditsNothing() {
        when(repository.closeOldestOpen(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        serviceAt("2026-11-01T06:00:00Z").closeOnNewVisit(TENANT_ID, USER_ID, PATIENT_ID, DOCTOR_ID,
                UUID.randomUUID());

        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // --- list and computed status ---

    @Test
    void statusIsComputedAroundMidnightIst() {
        // 18:29 UTC on 3 Nov is 23:59 IST on 3 Nov; 18:30 UTC is 00:00 IST on 4 Nov.
        LocalDate due = LocalDate.of(2026, 11, 3);
        LocalDate beforeMidnight = LocalDate.of(2026, 11, 3);
        LocalDate afterMidnight = LocalDate.of(2026, 11, 4);
        assertThat(FollowUpService.statusOf(row(due, null), beforeMidnight)).isEqualTo("DUE");
        assertThat(FollowUpService.statusOf(row(due, null), afterMidnight)).isEqualTo("MISSED");
        assertThat(FollowUpService.statusOf(row(afterMidnight, null), beforeMidnight)).isEqualTo("UPCOMING");
        assertThat(FollowUpService.statusOf(row(due, UUID.randomUUID()), afterMidnight)).isEqualTo("RETURNED");
    }

    @Test
    void listUsesTodayInKolkataNotUtc() {
        when(repository.counts(any(), any())).thenReturn(new Counts(0, 0, 0, 0));
        when(repository.findByStatus(any(), any(), any())).thenReturn(List.of());

        serviceAt("2026-11-03T18:30:00Z").list(TENANT_ID, "OWNER", "DUE");

        verify(repository).counts(TENANT_ID, LocalDate.of(2026, 11, 4));
        verify(repository).findByStatus(TENANT_ID, "DUE", LocalDate.of(2026, 11, 4));
    }

    @Test
    void missedRowsCarryDaysLateAndNamesComeFromThePorts() {
        FollowUpRow missed = row(LocalDate.of(2026, 11, 1), null);
        when(repository.counts(any(), any())).thenReturn(new Counts(1, 1, 2, 3));
        when(repository.findByStatus(TENANT_ID, "MISSED", LocalDate.of(2026, 11, 4))).thenReturn(List.of(missed));

        FollowUpView view = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, "OWNER", "MISSED");

        assertThat(view.counts()).isEqualTo(new FollowUpView.Counts(1, 1, 2, 3));
        assertThat(view.items()).hasSize(1);
        FollowUpView.Item item = view.items().get(0);
        assertThat(item.status()).isEqualTo("MISSED");
        assertThat(item.daysLate()).isEqualTo(3);
        assertThat(item.patientName()).isEqualTo("Asha Rao");
        assertThat(item.mobile()).isEqualTo(MOBILE);
        assertThat(item.doctorName()).isEqualTo("Dr Rao");
    }

    @Test
    void rolesThatCannotReadPatientsGetNoMobileButStillSeeNames() {
        when(repository.counts(any(), any())).thenReturn(new Counts(0, 1, 0, 0));
        when(repository.findByStatus(TENANT_ID, "MISSED", LocalDate.of(2026, 11, 4)))
                .thenReturn(List.of(row(LocalDate.of(2026, 11, 1), null)));

        for (String role : List.of("PHARMACIST", "BILLING", "COUNSELLOR", "SOMETHING_NEW")) {
            FollowUpView.Item item = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, role, "MISSED").items().get(0);
            assertThat(item.mobile()).as(role).isNull();
            assertThat(item.patientName()).as(role).isEqualTo("Asha Rao");
        }
        for (String role : List.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR")) {
            FollowUpView.Item item = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, role, "MISSED").items().get(0);
            assertThat(item.mobile()).as(role).isEqualTo(MOBILE);
        }
    }

    @Test
    void aMissingRoleGetsNoMobile() {
        when(repository.counts(any(), any())).thenReturn(new Counts(0, 1, 0, 0));
        when(repository.findByStatus(TENANT_ID, "MISSED", LocalDate.of(2026, 11, 4)))
                .thenReturn(List.of(row(LocalDate.of(2026, 11, 1), null)));

        assertThat(serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, null, "MISSED").items().get(0).mobile()).isNull();
    }

    @Test
    void daysLateIsZeroForNonMissedRows() {
        FollowUpRow due = row(LocalDate.of(2026, 11, 4), null);
        when(repository.counts(any(), any())).thenReturn(new Counts(1, 0, 0, 0));
        when(repository.findByStatus(any(), eq("DUE"), any())).thenReturn(List.of(due));

        FollowUpView view = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, "OWNER", "DUE");

        assertThat(view.items().get(0).status()).isEqualTo("DUE");
        assertThat(view.items().get(0).daysLate()).isZero();
    }

    @Test
    void countsEqualTheNumberOfItemsOfEachTab() {
        // The counts come from the same today-relative predicates as the lists; here the service passes
        // one "today" to both so they cannot disagree.
        when(repository.counts(any(), any())).thenReturn(new Counts(2, 0, 0, 0));
        when(repository.findByStatus(any(), eq("DUE"), any()))
                .thenReturn(List.of(row(LocalDate.of(2026, 11, 4), null), row(LocalDate.of(2026, 11, 4), null)));

        FollowUpView view = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, "OWNER", "DUE");

        assertThat(view.items()).hasSize(view.counts().due());
    }

    @Test
    void returnedTodayCountsFromTheStartOfTheKolkataDay() {
        when(repository.countReturnedSince(any(), any())).thenReturn(3);

        int result = serviceAt("2026-11-03T18:30:00Z").returnedToday(TENANT_ID);

        assertThat(result).isEqualTo(3);
        // 4 Nov 00:00 IST is 3 Nov 18:30 UTC.
        verify(repository).countReturnedSince(TENANT_ID, Instant.parse("2026-11-03T18:30:00Z"));
    }

    @Test
    void eachPatientAndDoctorIsLookedUpOnceHoweverManyRows() {
        when(repository.counts(any(), any())).thenReturn(new Counts(0, 0, 0, 3));
        when(repository.findByStatus(any(), eq("RETURNED"), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 11, 1), UUID.randomUUID()), row(LocalDate.of(2026, 11, 2), UUID.randomUUID()),
                row(LocalDate.of(2026, 11, 3), UUID.randomUUID())));

        FollowUpView view = serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, "OWNER", "RETURNED");

        assertThat(view.items()).hasSize(3);
        verify(patientLookup, org.mockito.Mockito.times(1)).find(TENANT_ID, PATIENT_ID);
        verify(teamMemberPort, org.mockito.Mockito.times(1)).find(TENANT_ID, DOCTOR_ID);
    }

    @Test
    void aBadOrMissingStatusIsRejected() {
        FollowUpService service = serviceAt("2026-11-04T05:00:00Z");

        assertThatThrownBy(() -> service.list(TENANT_ID, "OWNER", "SOON")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.list(TENANT_ID, "OWNER", null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.list(TENANT_ID, "OWNER", "due")).isInstanceOf(InvalidInputException.class);
    }

    // --- SAH-27 follow-ups added by an IPD discharge (ADR-004) ---

    private static final UUID ADMISSION_ID = UUID.randomUUID();

    private FollowUpPort.DischargeFollowUp dischargeFollowUp(String reason) {
        return new FollowUpPort.DischargeFollowUp(ADMISSION_ID, PATIENT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 18),
                reason);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDischargeFollowUpIsInsertedAndAuditedWithIdsOnly() {
        when(repository.insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        UUID id = serviceAt("2026-11-11T06:00:00Z").createForDischarge(TENANT_ID, USER_ID,
                dischargeFollowUp("Review after discharge · Chest pain"));

        verify(repository).insertForAdmissionIfAbsent(eq(id), eq(PATIENT_ID), eq(DOCTOR_ID), eq(ADMISSION_ID),
                eq(LocalDate.of(2026, 11, 18)), eq("Review after discharge · Chest pain"), eq(USER_ID));
        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("FOLLOW_UP_CREATED"), eq("FOLLOW_UP"), eq(id), eq(null), after.capture());
        Map<String, Object> values = after.getValue();
        assertThat(values).containsEntry("sourceAdmissionId", ADMISSION_ID).containsEntry("patientId", PATIENT_ID)
                .containsEntry("doctorMembershipId", DOCTOR_ID).containsEntry("dueDate", "2026-11-18")
                .doesNotContainKeys("reason", "mobile", "patientName", "name");
        assertThat(values.values()).doesNotContain("Review after discharge · Chest pain");
    }

    @Test
    void aSecondCallForTheSameAdmissionWritesAndAuditsNothingAndReturnsTheFirstId() {
        UUID first = UUID.randomUUID();
        when(repository.insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(repository.findIdBySourceAdmission(TENANT_ID, ADMISSION_ID)).thenReturn(Optional.of(first));

        UUID id = serviceAt("2026-11-11T06:00:00Z").createForDischarge(TENANT_ID, USER_ID,
                dischargeFollowUp("Review after discharge · Chest pain"));

        assertThat(id).isEqualTo(first);
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aNullFieldOrABlankOrTooLongReasonIsACallerBug() {
        FollowUpService service = serviceAt("2026-11-11T06:00:00Z");
        String twoHundredAndOne = "x".repeat(201);
        String twoHundred = "x".repeat(200);

        assertThatThrownBy(() -> service.createForDischarge(TENANT_ID, USER_ID, dischargeFollowUp(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createForDischarge(TENANT_ID, USER_ID, dischargeFollowUp("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createForDischarge(TENANT_ID, USER_ID, dischargeFollowUp(twoHundredAndOne)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createForDischarge(TENANT_ID, USER_ID,
                new FollowUpPort.DischargeFollowUp(null, PATIENT_ID, DOCTOR_ID, LocalDate.of(2026, 11, 18), "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createForDischarge(TENANT_ID, USER_ID,
                new FollowUpPort.DischargeFollowUp(ADMISSION_ID, PATIENT_ID, DOCTOR_ID, null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any());
        // 200 characters is the longest allowed.
        when(repository.insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        service.createForDischarge(TENANT_ID, USER_ID, dischargeFollowUp(twoHundred));
    }

    @Test
    void theReasonLimitCountsCodePointsNotUtf16Units() {
        when(repository.insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        // 200 emoji are 400 UTF-16 units but 200 code points: allowed.
        serviceAt("2026-11-11T06:00:00Z").createForDischarge(TENANT_ID, USER_ID,
                dischargeFollowUp("\uD83D\uDE00".repeat(200)));
        verify(repository).insertForAdmissionIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theFeeRuleIsTheOpdFeeSettingsOrTheDefaultsWhenNeverSaved() {
        FollowUpService service = serviceAt("2026-11-11T06:00:00Z");

        assertThat(service.feeRule(TENANT_ID)).isEqualTo(new FollowUpPort.FeeRule(7, "free"));

        when(feeSettingsRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new FeeRow("before", 6, "half", Map.of(), true)));
        assertThat(service.feeRule(TENANT_ID)).isEqualTo(new FollowUpPort.FeeRule(6, "half"));
    }

    @Test
    void theAdapterJoinsTheServiceAndPassesEverythingThrough() {
        FollowUpService service = mock(FollowUpService.class);
        FollowUpPortAdapter adapter = new FollowUpPortAdapter(service);
        FollowUpPort.DischargeFollowUp followUp = dischargeFollowUp("Review after discharge · x");
        UUID id = UUID.randomUUID();
        when(service.createForDischarge(TENANT_ID, USER_ID, followUp)).thenReturn(id);
        when(service.feeRule(TENANT_ID)).thenReturn(new FollowUpPort.FeeRule(7, "free"));

        assertThat(adapter.createForDischarge(TENANT_ID, USER_ID, followUp)).isEqualTo(id);
        assertThat(adapter.feeRule(TENANT_ID)).isEqualTo(new FollowUpPort.FeeRule(7, "free"));
    }

    @Test
    void aDischargeFollowUpIsListedLikeAnyOtherWithTheAdmissionAndNoVisit() {
        LocalDate today = LocalDate.of(2026, 11, 18);
        FollowUpRow row = new FollowUpRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, null, ADMISSION_ID, today,
                "Review after discharge · Chest pain", null);
        when(repository.counts(TENANT_ID, today)).thenReturn(new Counts(1, 0, 0, 0));
        when(repository.findByStatus(TENANT_ID, "DUE", today)).thenReturn(List.of(row));

        FollowUpView view = serviceAt("2026-11-18T05:00:00Z").list(TENANT_ID, "FRONT_DESK", "DUE");

        FollowUpView.Item item = view.items().get(0);
        assertThat(item.status()).isEqualTo("DUE");
        assertThat(item.sourceVisitId()).isNull();
        assertThat(item.sourceAdmissionId()).isEqualTo(ADMISSION_ID);
        assertThat(item.patientName()).isEqualTo("Asha Rao");
        assertThat(item.reason()).isEqualTo("Review after discharge · Chest pain");
    }

    @Test
    void theClinicalTailOfADischargeReasonIsShownOnlyToTheRolesWhoSeeIpd() {
        LocalDate today = LocalDate.of(2026, 11, 18);
        FollowUpRow discharge = new FollowUpRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, null, ADMISSION_ID, today,
                "Review after discharge · Chest pain", null);
        when(repository.counts(TENANT_ID, today)).thenReturn(new Counts(1, 0, 0, 0));
        when(repository.findByStatus(TENANT_ID, "DUE", today)).thenReturn(List.of(discharge));

        for (String role : List.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR")) {
            assertThat(serviceAt("2026-11-18T05:00:00Z").list(TENANT_ID, role, "DUE").items().get(0).reason())
                    .as(role).isEqualTo("Review after discharge · Chest pain");
        }
        for (String role : Arrays.asList("BILLING", "PHARMACIST", "COUNSELLOR", "SOMETHING_NEW", null)) {
            assertThat(serviceAt("2026-11-18T05:00:00Z").list(TENANT_ID, role, "DUE").items().get(0).reason())
                    .as(String.valueOf(role)).isEqualTo("Review after discharge");
        }
    }

    @Test
    void aVisitFollowUpReasonIsNeverMaskedWhateverTheRole() {
        when(repository.counts(any(), any())).thenReturn(new Counts(0, 1, 0, 0));
        when(repository.findByStatus(TENANT_ID, "MISSED", LocalDate.of(2026, 11, 4)))
                .thenReturn(List.of(row(LocalDate.of(2026, 11, 1), null)));

        assertThat(serviceAt("2026-11-04T05:00:00Z").list(TENANT_ID, "BILLING", "MISSED").items().get(0).reason())
                .isEqualTo("Review after treatment");
    }

    @Test
    void theRecordsOfAFollowUpListShowOnlyIdsDueDateAndStatusInToString() {
        UUID id = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 11, 18);
        FollowUpRow row = new FollowUpRow(id, PATIENT_ID, DOCTOR_ID, null, ADMISSION_ID, due,
                "Review after discharge · Chest pain", null);
        FollowUpView.Item item = new FollowUpView.Item(id, PATIENT_ID, "Asha Rao", MOBILE, DOCTOR_ID, "Dr Rao", due,
                "Review after discharge · Chest pain", "DUE", 0, null, ADMISSION_ID, null);
        FollowUpResponse.Item response = new FollowUpResponse.Item(id, PATIENT_ID, "Asha Rao", MOBILE, DOCTOR_ID,
                "Dr Rao", due, "Review after discharge · Chest pain", "DUE", 0, null, ADMISSION_ID, null);

        for (String text : List.of(row.toString(), item.toString(), response.toString(),
                new FollowUpView(new FollowUpView.Counts(1, 0, 0, 0), List.of(item)).toString(),
                FollowUpResponse.from(new FollowUpView(new FollowUpView.Counts(1, 0, 0, 0), List.of(item)))
                        .toString())) {
            assertThat(text).contains(id.toString()).contains("2026-11-18")
                    .doesNotContain("Asha").doesNotContain(MOBILE).doesNotContain("Chest pain")
                    .doesNotContain("Review after");
        }
        assertThat(item.toString()).contains("DUE");
        assertThat(response.toString()).contains("DUE");
    }

    @Test
    void theLastDischargeAtComesFromTheRepository() {
        Instant at = Instant.parse("2026-11-11T06:00:00Z");
        when(repository.lastDischargeAt(TENANT_ID, PATIENT_ID, DOCTOR_ID)).thenReturn(Optional.of(at));

        assertThat(serviceAt("2026-11-11T06:00:00Z").lastDischargeAt(TENANT_ID, PATIENT_ID, DOCTOR_ID))
                .contains(at);
    }
}
