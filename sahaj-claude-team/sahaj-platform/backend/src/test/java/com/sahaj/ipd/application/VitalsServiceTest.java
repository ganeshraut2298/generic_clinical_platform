package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EscalationRepository.EscalationRow;
import com.sahaj.ipd.application.VitalsRepository.NewVitals;
import com.sahaj.ipd.application.VitalsRepository.NoteRow;
import com.sahaj.ipd.application.VitalsRepository.VitalsRow;
import com.sahaj.ipd.application.VitalsViews.Outcome;
import com.sahaj.ipd.application.VitalsViews.Sheet;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.application.VitalsViews.VitalsSaved;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-30 vitals service with in-memory tables that enforce the same unique rules as the database (one record per
 * client key, one open escalation per admission, a score only rises), so that the escalation and replay rules are
 * tested end to end in the service. The database itself is tested by the QA integration tests.
 */
class VitalsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER_ACTOR = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    /** The clock reading as the gate gives it: cut to microseconds. */
    private static final Instant TICK = NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    private static final Instant TX = Instant.parse("2026-11-11T05:30:00.123456Z");
    /** The database clock the fake reports: a few seconds after the app clock, so the two cannot be mixed up. */
    private static final Instant DB_NOW = Instant.parse("2026-11-11T05:30:07.654321Z");
    private static final String SECRET_NAME = "Ramesh Kulkarni";
    private static final String SECRET_NOTE = "Patient restless, complains of chest tightness";

    private FakeVitals vitals;
    private FakeEscalations escalations;
    private FakeEvents events;
    private AdmissionRepository admissions;
    private PatientLookupPort patients;
    private PatientAccessPort patientAccess;
    private ActorDirectoryPort actors;
    private TeamMemberPort team;
    /** The role the membership has now; null means no active membership. */
    private String memberRole = "NURSE";
    private boolean memberActive = true;
    private Instant dbNow = DB_NOW;
    private AuditLog auditLog;
    private MarAssembler marAssembler;
    private ModuleEntitlementPort entitlements;
    private VitalsService service;
    private ScopeGuard guard;
    private String status = "ADMITTED";
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vitals = new FakeVitals();
        escalations = new FakeEscalations();
        events = new FakeEvents();
        admissions = mock(AdmissionRepository.class);
        patients = mock(PatientLookupPort.class);
        patientAccess = mock(PatientAccessPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        when(admissions.find(eq(TENANT), any())).thenAnswer(call -> admission(call.getArgument(1)));
        when(admissions.findForNoKeyUpdate(eq(TENANT), any())).thenAnswer(call -> {
            calls.add("lock");
            return admission(call.getArgument(1));
        });
        when(team.findActiveByUserId(eq(TENANT), any())).thenAnswer(call -> {
            calls.add("roleReread");
            return memberRole == null ? Optional.empty()
                    : Optional.of(new TeamMember(UUID.randomUUID(), "Member", memberRole, memberActive, 0L));
        });
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, SECRET_NAME, 52,
                "M", "9876543210")));
        when(actors.displayName(TENANT, ACTOR)).thenReturn(Optional.of("Sr. Anjali Pawar"));
        when(actors.displayName(TENANT, OTHER_ACTOR)).thenReturn(Optional.empty());
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        marAssembler = mock(MarAssembler.class);
        guard = ScopeStubs.unscoped();
        service = new VitalsService(admissions, vitals, escalations, events, patients, patientAccess, actors, team,
                auditLog, gate, marAssembler, guard);
        state("on");
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    private Optional<AdmissionRow> admission(UUID id) {
        if (!ADMISSION.equals(id)) {
            return Optional.empty();
        }
        return Optional.of(new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, UUID.randomUUID(), "202",
                "general", "General ward", UUID.randomUUID(), "planned", "Self", "Chest pain", status,
                Instant.parse("2026-11-09T05:30:00Z"), 0L));
    }

    private static VitalsInput input(UUID key, String bp, String pulse, String spo2, String temp, String rr,
                                     String note) {
        return new VitalsInput(key.toString(), bp, pulse, spo2, temp, rr, note);
    }

    /** Normal vitals: score 0. */
    private static VitalsInput normal(UUID key) {
        return input(key, "120/80", "80", "98", "37.0", "16", null);
    }

    /** Pulse 115 (2), RR 22 (2), SpO2 94 (1): score 5, medium. */
    private static VitalsInput medium(UUID key) {
        return input(key, "120/80", "115", "94", "37.0", "22", null);
    }

    /** Systolic 85 (3), pulse 135 (3), RR 26 (3) and SpO2 90 (3): score 12, high. */
    private static VitalsInput high(UUID key) {
        return input(key, "85/50", "135", "90", "37.0", "26", null);
    }

    private VitalsSaved save(VitalsInput input) {
        return service.record(TENANT, ACTOR, "NURSE", ADMISSION, input);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastAfter() {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_VITALS_RECORDED"), eq("IPD_VITALS"), any(), isNull(), after.capture());
        return after.getValue();
    }

    // ---- a low score ----

    @Test
    void aLowSaveStoresTheRecordAndTheEventAndRaisesNothing() {
        UUID key = UUID.randomUUID();

        VitalsSaved result = save(normal(key));

        assertThat(vitals.rows).hasSize(1);
        VitalsRow row = vitals.rows.get(0);
        assertThat(row.ewsScore()).isZero();
        assertThat(row.ewsRisk()).isEqualTo(Risk.LOW);
        assertThat(row.recordedBy()).isEqualTo(ACTOR);
        assertThat(row.bpSystolic()).isEqualTo(120);
        assertThat(row.bpDiastolic()).isEqualTo(80);
        assertThat(row.tempC()).isEqualByComparingTo("37.0");
        assertThat(vitals.clientKeys).containsExactly(key);
        assertThat(escalations.rows).isEmpty();
        assertThat(result.saved().replayed()).isFalse();
        assertThat(result.saved().ewsScore()).isZero();
        assertThat(result.saved().ewsRisk()).isEqualTo(Risk.LOW);
        assertThat(result.saved().noteAdded()).isFalse();
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.NONE);
        assertThat(result.escalation().open()).isNull();
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).kind()).isEqualTo("info");
        assertThat(events.rows.get(0).text()).isEqualTo("Recorded vitals \u00b7 EWS 0 (low)");
        assertThat(events.rows.get(0).actor()).isEqualTo(ACTOR);
    }

    @Test
    void theAnswerIsTheSheetAfterTheSaveWithTheNewRecordAsLatestAndTheTimeOfTheSave() {
        VitalsSaved result = save(input(UUID.randomUUID(), "118/76", "72", "97", "36.8", "18", SECRET_NOTE));

        assertThat(result.patientName()).isEqualTo(SECRET_NAME);
        assertThat(result.bedCode()).isEqualTo("202");
        assertThat(result.status()).isEqualTo("ADMITTED");
        assertThat(result.state()).isEqualTo("on");
        assertThat(result.canRecord()).isTrue();
        assertThat(result.readOnlyReason()).isNull();
        assertThat(result.asOf()).isEqualTo(DB_NOW);
        assertThat(result.latest().bp()).isEqualTo("118/76");
        assertThat(result.latest().bpSystolic()).isEqualTo(118);
        assertThat(result.latest().bpDiastolic()).isEqualTo(76);
        assertThat(result.latest().pulse()).isEqualTo(72);
        assertThat(result.latest().spo2()).isEqualTo(97);
        assertThat(result.latest().tempC()).isEqualTo(new BigDecimal("36.8"));
        assertThat(result.latest().rr()).isEqualTo(18);
        assertThat(result.latest().recordedAt()).isEqualTo(TX);
        assertThat(result.latest().recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(result.vitalsDue()).isFalse();
        assertThat(result.recentNotes()).hasSize(1);
        assertThat(result.recentNotes().get(0).text()).isEqualTo(SECRET_NOTE);
        assertThat(result.recentNotes().get(0).recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(result.saved().recordedAt()).isEqualTo(TX);
        assertThat(result.saved().vitalsId()).isEqualTo(result.latest().id());
    }

    @Test
    void theTemperatureIsAlwaysShownWithOneDecimal() {
        VitalsSaved result = save(input(UUID.randomUUID(), "120/80", "80", "98", "37", "16", null));

        assertThat(result.latest().tempC().toPlainString()).isEqualTo("37.0");
    }

    // ---- check order and the lock ----

    @Test
    void theAdmissionRowIsLockedForNoKeyUpdateBeforeTheKeyLookupAndNoOtherLockIsTaken() {
        save(normal(UUID.randomUUID()));

        verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        verify(admissions, never()).findForUpdate(any(), any());
        verify(admissions, never()).find(any(), any());
        assertThat(calls).containsExactly("roleReread", "lock", "keyLookup", "vitals", "escalationRead", "clock");
    }

    @Test
    void theEscalationIsReadAndWrittenOnlyAfterTheLockAndTheVitalsInsert() {
        save(medium(UUID.randomUUID()));

        assertThat(calls).containsExactly("roleReread", "lock", "keyLookup", "vitals", "escalationRead",
                "escalationInsert", "clock");
    }

    @Test
    void aSaveOfAnAdmissionThatDoesNotExistAnswers404AndWritesNothing() {
        assertThatThrownBy(() -> service.record(TENANT, ACTOR, "NURSE", UUID.randomUUID(), normal(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);

        assertNothingWritten();
    }

    @Test
    void theRoleIsCheckedFirstEvenWhenIpdIsOffAndNoLockIsTaken() {
        state("off");

        for (String role : new String[] {"DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", null}) {
            assertThatThrownBy(() -> service.record(TENANT, ACTOR, role, ADMISSION, normal(UUID.randomUUID())))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(admissions, auditLog);
        assertNothingWritten();
    }

    @Test
    void onlyOwnerAndNurseMayRecord() {
        for (String role : new String[] {"OWNER", "NURSE"}) {
            assertThat(service.record(TENANT, ACTOR, role, ADMISSION, normal(UUID.randomUUID())).saved().replayed())
                    .isFalse();
        }
        assertThat(vitals.rows).hasSize(2);
    }

    @Test
    void ipdOffAnswersModuleOffBeforeAnyLock() {
        state("off");

        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(ModuleOffException.class);

        verifyNoInteractions(admissions);
        assertNothingWritten();
    }

    @Test
    void readOnlyAnswersIpdReadonlyBeforeTheBodyIsCheckedAndBeforeAnyLock() {
        state("readonly");

        assertThatThrownBy(() -> save(input(UUID.randomUUID(), "bad", null, null, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("read-only");

        verifyNoInteractions(admissions);
        assertNothingWritten();
    }

    @Test
    void aMissingOrBrokenClientKeyIs400OnTheFieldClientKeyBeforeTheBodyAndBeforeAnyLock() {
        for (String key : new String[] {null, "", "not-a-uuid"}) {
            assertThatThrownBy(() -> save(new VitalsInput(key, "bad", "x", "x", "x", "x", null)))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("clientKey");
                        assertThat(e.getMessage()).isEqualTo("Reload the page and try again.");
                    });
        }
        assertThatThrownBy(() -> service.record(TENANT, ACTOR, "NURSE", ADMISSION, null))
                .isInstanceOf(InvalidInputException.class);

        verifyNoInteractions(admissions);
        assertNothingWritten();
    }

    @Test
    void aBadBodyAnswers400BeforeTheLockEvenForAnAdmissionThatDoesNotExist() {
        assertThatThrownBy(() -> service.record(TENANT, ACTOR, "NURSE", UUID.randomUUID(),
                input(UUID.randomUUID(), "bad", "80", "98", "37.0", "16", null)))
                .isInstanceOf(InvalidFieldsException.class);

        verifyNoInteractions(admissions);
        assertNothingWritten();
    }

    @Test
    void aDischargedAdmissionAnswers409NotAdmittedAndWritesNothing() {
        status = "DISCHARGED";

        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOfSatisfying(ConflictException.class,
                e -> {
                    assertThat(e.getMessage()).isEqualTo("This patient is not admitted any more. Vitals cannot be "
                            + "saved.");
                });

        assertNothingWritten();
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- escalation ----

    @Test
    void aMediumScoreRaisesAnEscalationWithAnHourTargetAndLogsTheTwoEventsInOrder() {
        VitalsSaved result = save(medium(UUID.randomUUID()));

        assertThat(result.saved().ewsScore()).isEqualTo(5);
        assertThat(result.saved().ewsRisk()).isEqualTo(Risk.MEDIUM);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.RAISED);
        assertThat(result.escalation().open().ewsScore()).isEqualTo(5);
        assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.MEDIUM);
        assertThat(result.escalation().open().targetMinutes()).isEqualTo(60);
        assertThat(result.escalation().open().status()).isEqualTo("OPEN");
        assertThat(result.escalation().open().raisedAt()).isEqualTo(TX);
        EscalationRow stored = escalations.rows.get(0);
        assertThat(stored.admissionId()).isEqualTo(ADMISSION);
        assertThat(stored.id()).isEqualTo(result.escalation().open().id());
        assertThat(escalations.raisedBy).containsExactly(ACTOR);
        assertThat(events.rows).extracting(FakeEvents.Event::kind).containsExactly("info", "esc");
        assertThat(events.rows).extracting(FakeEvents.Event::text).containsExactly(
                "Recorded vitals \u00b7 EWS 5 (medium)",
                "Raised early warning escalation \u00b7 EWS 5 (medium) \u00b7 doctor review within 1 hour");
    }

    @Test
    void aHighScoreRaisesAnEscalationWithA15MinuteTarget() {
        VitalsSaved result = save(high(UUID.randomUUID()));

        assertThat(result.saved().ewsScore()).isEqualTo(12);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.RAISED);
        assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.HIGH);
        assertThat(result.escalation().open().targetMinutes()).isEqualTo(15);
        assertThat(events.rows).extracting(FakeEvents.Event::text).containsExactly(
                "Recorded vitals \u00b7 EWS 12 (high)",
                "Raised early warning escalation \u00b7 EWS 12 (high) \u00b7 doctor review within 15 min");
    }

    @Test
    void aSingleParameterOfThreeRaisesAMediumEscalationEvenWithALowTotal() {
        // SpO2 91 = 3, everything else normal: total 3, still medium
        VitalsSaved result = save(input(UUID.randomUUID(), "120/80", "80", "91", "37.0", "16", null));

        assertThat(result.saved().ewsScore()).isEqualTo(3);
        assertThat(result.saved().ewsRisk()).isEqualTo(Risk.MEDIUM);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.RAISED);
        assertThat(result.escalation().open().targetMinutes()).isEqualTo(60);
    }

    @Test
    void aHigherScoreUpdatesTheOpenEscalationInOneStepAndKeepsTheOriginalRaiseTime() {
        Instant raisedAt = Instant.parse("2026-11-11T04:00:00Z");
        UUID openId = escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "OPEN", raisedAt);

        VitalsSaved result = save(high(UUID.randomUUID()));

        assertThat(escalations.rows).hasSize(1);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.escalation().open().id()).isEqualTo(openId);
        assertThat(result.escalation().open().ewsScore()).isEqualTo(12);
        assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.HIGH);
        assertThat(result.escalation().open().targetMinutes()).isEqualTo(15);
        assertThat(result.escalation().open().status()).isEqualTo("OPEN");
        assertThat(result.escalation().open().raisedAt()).isEqualTo(raisedAt);
        assertThat(escalations.updates).isEqualTo(1);
        assertThat(events.rows).extracting(FakeEvents.Event::kind).containsExactly("info", "esc");
        assertThat(events.rows.get(1).text()).isEqualTo("Escalation updated \u00b7 EWS now 12 (high)");
    }

    // ---- SAH-33 AC12: re-open ----

    @Test
    void aHighReadingReopensAnAcknowledgedMediumEscalationAndKeepsTheOriginalRaiseTime() {
        Instant raisedAt = Instant.parse("2026-11-11T04:00:00Z");
        UUID openId = escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "ACK", raisedAt);

        VitalsSaved result = save(high(UUID.randomUUID()));

        assertThat(escalations.rows).hasSize(1);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.REOPENED);
        assertThat(result.escalation().open().id()).isEqualTo(openId);
        assertThat(result.escalation().open().ewsScore()).isEqualTo(12);
        assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.HIGH);
        assertThat(result.escalation().open().targetMinutes()).isEqualTo(15);
        assertThat(result.escalation().open().status()).isEqualTo("OPEN");
        assertThat(result.escalation().open().raisedAt()).isEqualTo(raisedAt);
        assertThat(escalations.reopens).isEqualTo(1);
        assertThat(escalations.updates).isZero();
        assertThat(events.rows).extracting(FakeEvents.Event::kind).containsExactly("info", "esc");
        assertThat(events.rows.get(1).text()).isEqualTo(
                "Escalation re-opened · EWS now 12 (high) · doctor review within 15 min");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReopenIsAuditedWithIdsScoresAndRisksOnlyAndTheSaveAuditNamesTheOutcome() {
        UUID openId = escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "ACK", Instant.parse("2026-11-11T04:00:00Z"));

        save(high(UUID.randomUUID()));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ESCALATION_REOPENED"), eq("IPD_EWS_ESCALATION"), eq(openId), isNull(),
                after.capture());
        assertThat(after.getValue()).containsOnlyKeys("escalationId", "admissionId", "ewsScore", "ewsRisk",
                "previousScore", "previousRisk");
        assertThat(after.getValue()).containsEntry("escalationId", openId).containsEntry("admissionId", ADMISSION)
                .containsEntry("ewsScore", 12).containsEntry("ewsRisk", "HIGH").containsEntry("previousScore", 5)
                .containsEntry("previousRisk", "MEDIUM");
        assertThat(lastAfter()).containsEntry("escalationOutcome", "REOPENED").containsEntry("escalationId", openId);
        assertThat(after.getValue().toString()).doesNotContain(SECRET_NAME);
    }

    @Test
    void aHigherScoreInsideTheSameRiskUpdatesAnAcknowledgedEscalationAndKeepsItAcknowledged() {
        escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "ACK", Instant.parse("2026-11-11T04:00:00Z"));

        // systolic 105 (1), pulse 115 (2), SpO2 94 (1), RR 22 (2): score 6, still medium
        VitalsSaved result = save(input(UUID.randomUUID(), "105/70", "115", "94", "37.0", "22", null));

        assertThat(result.saved().ewsScore()).isEqualTo(6);
        assertThat(result.escalation().outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.escalation().open().status()).isEqualTo("ACK");
        assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.MEDIUM);
        assertThat(escalations.updates).isEqualTo(1);
        assertThat(escalations.reopens).isZero();
        assertThat(events.rows.get(1).text()).isEqualTo("Escalation updated · EWS now 6 (medium)");
    }

    @Test
    void aHigherHighScoreUpdatesAnAcknowledgedHighEscalationAndKeepsItAcknowledged() {
        escalations.seed(ADMISSION, 8, Risk.HIGH, 15, "ACK", Instant.parse("2026-11-11T04:00:00Z"));

        VitalsSaved result = save(high(UUID.randomUUID()));

        assertThat(result.escalation().outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.escalation().open().status()).isEqualTo("ACK");
        assertThat(escalations.reopens).isZero();
    }

    @Test
    void aLowOrEqualOrLowerReadingNeverClosesOrReopensAnAcknowledgedEscalation() {
        escalations.seed(ADMISSION, 12, Risk.HIGH, 15, "ACK", Instant.parse("2026-11-11T04:00:00Z"));

        VitalsSaved low = save(normal(UUID.randomUUID()));
        VitalsSaved equal = save(high(UUID.randomUUID()));

        for (VitalsSaved result : List.of(low, equal)) {
            assertThat(result.escalation().outcome()).isEqualTo(Outcome.NONE);
            assertThat(result.escalation().open().status()).isEqualTo("ACK");
        }
        assertThat(escalations.updates).isZero();
        assertThat(escalations.reopens).isZero();
        verify(auditLog, never()).record(eq("IPD_ESCALATION_REOPENED"), any(), any(), any(), any());
    }

    @Test
    void aFailedReopenRollsTheWholeSaveBack() {
        escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "ACK", Instant.parse("2026-11-11T04:00:00Z"));
        escalations.refuseReopen = true;

        assertThatThrownBy(() -> save(high(UUID.randomUUID()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEqualOrLowerScoreChangesNothingAndReturnsTheOpenEscalationAsItIs() {
        Instant raisedAt = Instant.parse("2026-11-11T04:00:00Z");
        escalations.seed(ADMISSION, 12, Risk.HIGH, 15, "OPEN", raisedAt);

        VitalsSaved lower = save(medium(UUID.randomUUID()));
        VitalsSaved equal = save(high(UUID.randomUUID()));

        for (VitalsSaved result : List.of(lower, equal)) {
            assertThat(result.escalation().outcome()).isEqualTo(Outcome.NONE);
            assertThat(result.escalation().open().ewsScore()).isEqualTo(12);
            assertThat(result.escalation().open().ewsRisk()).isEqualTo(Risk.HIGH);
            assertThat(result.escalation().open().targetMinutes()).isEqualTo(15);
        }
        assertThat(escalations.updates).isZero();
        assertThat(events.rows).extracting(FakeEvents.Event::kind).containsExactly("info", "info");
    }

    @Test
    void aLowScoreNeverUpdatesOrClosesAnOpenEscalationAndStillReportsIt() {
        escalations.seed(ADMISSION, 6, Risk.MEDIUM, 60, "OPEN", Instant.parse("2026-11-11T04:00:00Z"));

        VitalsSaved result = save(normal(UUID.randomUUID()));

        assertThat(result.escalation().outcome()).isEqualTo(Outcome.NONE);
        assertThat(result.escalation().open()).isNotNull();
        assertThat(result.escalation().open().ewsScore()).isEqualTo(6);
        assertThat(escalations.updates).isZero();
        assertThat(escalations.rows.get(0).status()).isEqualTo("OPEN");
    }

    @Test
    void aRaiseAfterTheOldEscalationIsDoneStartsANewOne() {
        escalations.seed(ADMISSION, 6, Risk.MEDIUM, 60, "DONE", Instant.parse("2026-11-11T03:00:00Z"));

        VitalsSaved result = save(medium(UUID.randomUUID()));

        assertThat(result.escalation().outcome()).isEqualTo(Outcome.RAISED);
        assertThat(escalations.rows).hasSize(2);
    }

    @Test
    void aUniqueIndexClashOnTheEscalationIsNotSwallowedAndCarriesNoData() {
        escalations.hideOpenFromReads = true;
        escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "OPEN", Instant.parse("2026-11-11T04:00:00Z"));

        assertThatThrownBy(() -> save(medium(UUID.randomUUID()))).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(ADMISSION.toString()).hasMessageNotContaining("120");

        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- the note ----

    @Test
    void aNoteIsStoredWithTheSameUserAndTheAnswerShowsItFirst() {
        vitals.notes.add(new NoteRow(UUID.randomUUID(), "earlier note", TX.minusSeconds(3600), ACTOR));
        vitals.noteAdmissions.add(ADMISSION);

        VitalsSaved result = save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", SECRET_NOTE));

        assertThat(result.saved().noteAdded()).isTrue();
        assertThat(vitals.rows.get(0).noteAdded()).isTrue();
        assertThat(vitals.notes).hasSize(2);
        assertThat(result.recentNotes()).extracting(n -> n.text()).containsExactly(SECRET_NOTE, "earlier note");
        assertThat(vitals.noteBy).containsExactly(ACTOR);
    }

    @Test
    void anEmptyOrBlankNoteStoresNothing() {
        for (String note : new String[] {null, "", "   ", " \n \r\n "}) {
            VitalsSaved result = save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", note));
            assertThat(result.saved().noteAdded()).isFalse();
        }
        assertThat(vitals.notes).isEmpty();
        assertThat(vitals.rows).extracting(VitalsRow::noteAdded).containsOnly(false);
    }

    @Test
    void aNoteIsStrippedAndLineBreaksAreStoredAsLf() {
        save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", "  Line one\r\nLine two\rLine three  "));

        assertThat(vitals.notes.get(0).note()).isEqualTo("Line one\nLine two\nLine three");
    }

    @Test
    void aNoteWithAControlCharacterIsRefusedOnTheFieldNoteAndTheMessageDoesNotEchoIt() {
        assertThatThrownBy(() -> save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", "bad\u0007note")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).hasSize(1);
                    assertThat(e.errors().get(0).field()).isEqualTo("note");
                    assertThat(e.errors().get(0).message())
                            .isEqualTo("Note cannot contain control or text-direction characters");
                });

        assertNothingWritten();
    }

    @Test
    void aNoteOf1000CharactersIsAcceptedAnd1001IsRefused() {
        save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", "a".repeat(1000)));
        assertThat(vitals.notes).hasSize(1);

        assertThatThrownBy(() -> save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", "a".repeat(1001))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors().get(0).field()).isEqualTo("note");
                    assertThat(e.errors().get(0).message()).isEqualTo("Keep the note to 1,000 characters.");
                });
    }

    // ---- replay ----

    @Test
    void aRepeatOfTheClientKeyAnswers200WithTheFirstRecordAndWritesNothingMore() {
        UUID key = UUID.randomUUID();
        VitalsSaved first = save(medium(key));
        int audits = 1;

        VitalsSaved again = save(medium(key));

        assertThat(again.saved().replayed()).isTrue();
        assertThat(again.saved().vitalsId()).isEqualTo(first.saved().vitalsId());
        assertThat(again.saved().ewsScore()).isEqualTo(5);
        assertThat(again.saved().ewsRisk()).isEqualTo(Risk.MEDIUM);
        assertThat(again.escalation().outcome()).isEqualTo(Outcome.NONE);
        assertThat(again.escalation().open().id()).isEqualTo(first.escalation().open().id());
        assertThat(again.latest().id()).isEqualTo(first.saved().vitalsId());
        assertThat(vitals.rows).hasSize(1);
        assertThat(escalations.rows).hasSize(1);
        assertThat(events.rows).hasSize(2);
        verify(auditLog, times(audits)).record(eq("IPD_VITALS_RECORDED"), any(), any(), any(), any());
    }

    @Test
    void aRepeatIsAnsweredEvenIfThePatientHasBeenDischargedSince() {
        UUID key = UUID.randomUUID();
        save(normal(key));
        status = "DISCHARGED";

        VitalsSaved again = save(normal(key));

        assertThat(again.saved().replayed()).isTrue();
        assertThat(again.canRecord()).isFalse();
        assertThat(again.readOnlyReason()).isEqualTo("NOT_ADMITTED");
        assertThat(vitals.rows).hasSize(1);
    }

    @Test
    void aClientKeyUsedForAnotherAdmissionIs400OnTheFieldClientKey() {
        UUID key = UUID.randomUUID();
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), UUID.randomUUID(), 120, 80, 80, 98, new BigDecimal("37.0"),
                16, 0, Risk.LOW, false, TX.minusSeconds(60), ACTOR));
        vitals.clientKeys.add(key);

        assertThatThrownBy(() -> save(normal(key))).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.field()).isEqualTo("clientKey"));

        assertThat(vitals.rows).hasSize(1);
        assertThat(events.rows).isEmpty();
    }

    @Test
    void aNewKeyWithNewValuesIsANewRecord() {
        save(normal(UUID.randomUUID()));
        save(normal(UUID.randomUUID()));

        assertThat(vitals.rows).hasSize(2);
        assertThat(events.rows).hasSize(2);
    }

    // ---- audit ----

    @Test
    void theAuditRowHoldsIdsScoreRiskNoteFlagAndEscalationOnly() {
        VitalsSaved result = save(input(UUID.randomUUID(), "85/50", "135", "90", "36.5", "26", SECRET_NOTE));

        Map<String, Object> after = lastAfter();
        assertThat(after.keySet()).containsExactly("admissionId", "vitalsId", "ewsScore", "ewsRisk", "noteAdded",
                "escalationId", "escalationOutcome");
        assertThat(after.get("admissionId")).isEqualTo(ADMISSION);
        assertThat(after.get("vitalsId")).isEqualTo(result.saved().vitalsId());
        assertThat(after.get("ewsScore")).isEqualTo(12);
        assertThat(after.get("ewsRisk")).isEqualTo("HIGH");
        assertThat(after.get("noteAdded")).isEqualTo(true);
        assertThat(after.get("escalationId")).isEqualTo(result.escalation().open().id());
        assertThat(after.get("escalationOutcome")).isEqualTo("RAISED");
        String text = after.toString();
        assertThat(text).doesNotContain(SECRET_NOTE).doesNotContain(SECRET_NAME).doesNotContain("85/50")
                .doesNotContain("36.5");
    }

    @Test
    void theAuditRowOfALowSaveHasNoEscalation() {
        save(normal(UUID.randomUUID()));

        Map<String, Object> after = lastAfter();
        assertThat(after.get("escalationId")).isNull();
        assertThat(after.get("escalationOutcome")).isEqualTo("NONE");
        assertThat(after.get("noteAdded")).isEqualTo(false);
    }

    @Test
    void theAuditRowOfAnUpdateNamesTheUpdatedEscalation() {
        UUID openId = escalations.seed(ADMISSION, 5, Risk.MEDIUM, 60, "OPEN", Instant.parse("2026-11-11T04:00:00Z"));

        save(high(UUID.randomUUID()));

        Map<String, Object> after = lastAfter();
        assertThat(after.get("escalationId")).isEqualTo(openId);
        assertThat(after.get("escalationOutcome")).isEqualTo("UPDATED");
    }

    @Test
    void noEventTextHoldsTheNameTheNoteOrAVitalValue() {
        save(input(UUID.randomUUID(), "85/50", "135", "90", "36.5", "26", SECRET_NOTE));

        for (FakeEvents.Event event : events.rows) {
            assertThat(event.text()).doesNotContain(SECRET_NAME).doesNotContain(SECRET_NOTE).doesNotContain("85/50")
                    .doesNotContain("135").doesNotContain("36.5");
            assertThat(event.text().length()).isLessThanOrEqualTo(300);
        }
    }

    @Test
    void theRecordsThatCarryVitalsOrNotesHideThemFromToString() {
        VitalsSaved result = save(input(UUID.randomUUID(), "85/50", "135", "90", "36.5", "26", SECRET_NOTE));

        VitalsInput input = input(UUID.randomUUID(), "85/50", "135", "90", "36.5", "26", SECRET_NOTE);
        for (String text : List.of(input.toString(), result.toString(), result.latest().toString(),
                result.recentNotes().get(0).toString(), vitals.rows.get(0).toString(),
                new NewVitals(ADMISSION, UUID.randomUUID(), 85, 50, 135, 90, new BigDecimal("36.5"), 26, 12,
                        Risk.HIGH, true, ACTOR).toString(),
                new NoteRow(UUID.randomUUID(), SECRET_NOTE, TX, ACTOR).toString())) {
            assertThat(text).doesNotContain(SECRET_NOTE).doesNotContain(SECRET_NAME).doesNotContain("85/50")
                    .doesNotContain("36.5");
        }
    }

    // ---- the sheet ----

    @Test
    void theSheetCarriesTheMedicineCountOfTheTabFromTheMarRuleAtTheSameClock() {
        when(marAssembler.doseCount(TENANT, ADMISSION, true, DB_NOW)).thenReturn(3);

        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).doseCount()).isEqualTo(3);
        assertThat(service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION).doseCount()).isEqualTo(3);
    }

    @Test
    void theSheetOfADischargedAdmissionAsksForTheCountOfAnAdmissionThatIsNotAdmitted() {
        status = "DISCHARGED";

        service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);

        verify(marAssembler).doseCount(TENANT, ADMISSION, false, DB_NOW);
    }

    @Test
    void theAnswerOfASaveAndOfAReplayCarryTheCountToo() {
        when(marAssembler.doseCount(eq(TENANT), eq(ADMISSION), eq(true), any())).thenReturn(2);
        UUID key = UUID.randomUUID();

        VitalsSaved first = save(input(key, "118/76", "72", "97", "36.8", "18", null));
        VitalsSaved again = save(input(key, "118/76", "72", "97", "36.8", "18", null));

        assertThat(first.doseCount()).isEqualTo(2);
        assertThat(again.saved().replayed()).isTrue();
        assertThat(again.doseCount()).isEqualTo(2);
    }

    @Test
    void theSheetOfAnAdmissionWithNoVitalsHasNoLatestNoNotesAndIsDue() {
        Sheet sheet = service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);

        assertThat(sheet.latest()).isNull();
        assertThat(sheet.recentNotes()).isEmpty();
        assertThat(sheet.vitalsDue()).isTrue();
        assertThat(sheet.canRecord()).isTrue();
        assertThat(sheet.readOnlyReason()).isNull();
        assertThat(sheet.asOf()).isEqualTo(DB_NOW);
        assertThat(sheet.patientName()).isEqualTo(SECRET_NAME);
        assertThat(sheet.bedCode()).isEqualTo("202");
        assertThat(sheet.state()).isEqualTo("on");
    }

    @Test
    void theSheetShowsTheLatestRecordTheLastThreeNotesNewestFirstAndTheRecorderNames() {
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), ADMISSION, 120, 80, 80, 98, new BigDecimal("37.0"), 16, 0,
                Risk.LOW, true, NOW.minusSeconds(35 * 60), OTHER_ACTOR));
        for (int i = 1; i <= 4; i++) {
            vitals.notes.add(new NoteRow(UUID.randomUUID(), "note " + i, NOW.minusSeconds(3600L * (5 - i)),
                    i % 2 == 0 ? ACTOR : OTHER_ACTOR));
            vitals.noteAdmissions.add(ADMISSION);
        }

        Sheet sheet = service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.latest().recordedByName()).isEqualTo("Team member");
        assertThat(sheet.latest().recordedAt()).isEqualTo(NOW.minusSeconds(35 * 60));
        assertThat(sheet.recentNotes()).extracting(n -> n.text()).containsExactly("note 4", "note 3", "note 2");
        assertThat(sheet.recentNotes()).extracting(n -> n.recordedByName())
                .containsExactly("Sr. Anjali Pawar", "Team member", "Sr. Anjali Pawar");
        assertThat(sheet.vitalsDue()).isFalse();
    }

    @Test
    void vitalsBecomeDueAfterMoreThanFourHours() {
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), ADMISSION, 120, 80, 80, 98, new BigDecimal("37.0"), 16, 0,
                Risk.LOW, false, TICK.minusSeconds(240 * 60), ACTOR));
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).vitalsDue()).isFalse();

        vitals.rows.clear();
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), ADMISSION, 120, 80, 80, 98, new BigDecimal("37.0"), 16, 0,
                Risk.LOW, false, TICK.minusSeconds(241 * 60), ACTOR));
        assertThat(service.sheet(TENANT, ACTOR, "OWNER", ADMISSION).vitalsDue()).isTrue();
    }

    @Test
    void aDoctorMayReadButNotRecordAndSeesTheRoleReason() {
        Sheet sheet = service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION);

        assertThat(sheet.canRecord()).isFalse();
        assertThat(sheet.readOnlyReason()).isEqualTo("ROLE");
    }

    @Test
    void theRoleReasonComesBeforeTheReadOnlyAndNotAdmittedReasons() {
        state("readonly");
        status = "DISCHARGED";

        assertThat(service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION).readOnlyReason()).isEqualTo("ROLE");
        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).readOnlyReason()).isEqualTo("IPD_READONLY");
        state("on");
        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).readOnlyReason()).isEqualTo("NOT_ADMITTED");
    }

    @Test
    void theSheetOpensInReadOnlyStateAndForADischargedPatientButCannotRecordAndIsNeverDue() {
        state("readonly");
        Sheet readonly = service.sheet(TENANT, ACTOR, "OWNER", ADMISSION);
        assertThat(readonly.canRecord()).isFalse();
        assertThat(readonly.state()).isEqualTo("readonly");

        state("trial");
        status = "DISCHARGED";
        Sheet discharged = service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);
        assertThat(discharged.canRecord()).isFalse();
        assertThat(discharged.status()).isEqualTo("DISCHARGED");
        assertThat(discharged.vitalsDue()).isFalse();
    }

    @Test
    void theSheetIsRefusedForEveryOtherRoleAndWhenIpdIsOff() {
        for (String role : new String[] {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", null}) {
            assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, role, ADMISSION))
                    .isInstanceOf(AccessDeniedException.class);
        }
        state("off");
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(ModuleOffException.class);

        verifyNoInteractions(patientAccess);
    }

    @Test
    void openingTheSheetWritesOneRecordAccessRowAfterThe404CheckAndARefusedOpenWritesNone() {
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(patientAccess);

        service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);

        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, ACTOR);
        assertNothingWritten();
    }

    @Test
    void savingWritesNoRecordAccessRow() {
        save(normal(UUID.randomUUID()));

        verifyNoInteractions(patientAccess);
    }

    @Test
    void theSheetTakesNoRowLockAndChangesNothing() {
        service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);

        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verify(admissions, never()).findForUpdate(any(), any());
        assertNothingWritten();
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- the role read again from the membership (security review SAH-30 M1) ----

    @Test
    void theSaveReadsTheRoleAgainAfterTheBodyCheckAndBeforeTheLockAndTheSheetBeforeTheAdmissionRead() {
        save(normal(UUID.randomUUID()));
        assertThat(calls.subList(0, 2)).containsExactly("roleReread", "lock");
        verify(team).findActiveByUserId(TENANT, ACTOR);

        calls.clear();
        service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);
        assertThat(calls).containsExactly("roleReread", "clock");
    }

    @Test
    void aSaveWithABadBodyOrKeyNeverReachesTheMembershipRead() {
        assertThatThrownBy(() -> save(input(UUID.randomUUID(), "bad", "80", "98", "37.0", "16", null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> save(new VitalsInput("nope", "120/80", "80", "98", "37.0", "16", null)))
                .isInstanceOf(InvalidInputException.class);

        verifyNoInteractions(team);
    }

    @Test
    void aSaveIsRefusedWhenTheMembershipRoleIsNotOwnerOrNurseWhateverTheTokenSays() {
        for (String now : new String[] {"FRONT_DESK", "DOCTOR", "BILLING", "VISITING_DOCTOR", "PHARMACIST"}) {
            memberRole = now;
            for (String token : new String[] {"NURSE", "OWNER"}) {
                assertThatThrownBy(() -> service.record(TENANT, ACTOR, token, ADMISSION, normal(UUID.randomUUID())))
                        .as(token + " demoted to " + now).isInstanceOf(AccessDeniedException.class);
            }
        }
        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verifyNoInteractions(auditLog, patientAccess);
        assertNothingWritten();
    }

    @Test
    void aSaveIsRefusedWhenThereIsNoActiveMembershipOrItWasDeactivated() {
        memberRole = null;
        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(AccessDeniedException.class);
        memberRole = "NURSE";
        memberActive = false;
        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(AccessDeniedException.class);

        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verifyNoInteractions(auditLog, patientAccess);
        assertNothingWritten();
    }

    @Test
    void aSaveStillWorksForAMembershipThatIsOwnerOrNurse() {
        for (String now : new String[] {"OWNER", "NURSE"}) {
            memberRole = now;
            assertThat(save(normal(UUID.randomUUID())).saved().replayed()).isFalse();
        }
        assertThat(vitals.rows).hasSize(2);
    }

    @Test
    void theSheetIsRefusedWhenTheMembershipRoleIsNotOwnerNurseOrDoctorOrThereIsNoActiveMembership() {
        for (String now : new String[] {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST"}) {
            memberRole = now;
            assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                    .as("demoted to " + now).isInstanceOf(AccessDeniedException.class);
        }
        memberRole = null;
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION))
                .isInstanceOf(AccessDeniedException.class);
        memberRole = "NURSE";
        memberActive = false;
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(patientAccess);
        verify(admissions, never()).find(any(), any());
    }

    @Test
    void theSheetOpensForAMembershipThatIsOwnerNurseOrDoctor() {
        for (String now : new String[] {"OWNER", "NURSE", "DOCTOR"}) {
            memberRole = now;
            assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).patientName()).isEqualTo(SECRET_NAME);
        }
    }

    @Test
    void theTokenRoleIsCheckedBeforeAndTheModuleStateBeforeTheMembershipRead() {
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "FRONT_DESK", ADMISSION))
                .isInstanceOf(AccessDeniedException.class);
        state("off");
        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(ModuleOffException.class);

        verifyNoInteractions(team);
    }

    @Test
    void canRecordNeedsBothTheTokenRoleAndTheMembershipRoleToBeOwnerOrNurse() {
        memberRole = "DOCTOR";
        Sheet tokenNurse = service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);
        assertThat(tokenNurse.canRecord()).isFalse();
        assertThat(tokenNurse.readOnlyReason()).isEqualTo("ROLE");

        memberRole = "NURSE";
        Sheet tokenDoctor = service.sheet(TENANT, ACTOR, "DOCTOR", ADMISSION);
        assertThat(tokenDoctor.canRecord()).isFalse();
        assertThat(tokenDoctor.readOnlyReason()).isEqualTo("ROLE");

        memberRole = "OWNER";
        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).canRecord()).isTrue();
    }

    // ---- the database clock (code review M1) ----

    @Test
    void theSheetUsesTheDatabaseClockForAsOfAndForVitalsDueNotTheApplicationClock() {
        // 241 whole minutes before the database clock, but only 240 minutes 53 seconds before the app clock
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), ADMISSION, 120, 80, 80, 98, new BigDecimal("37.0"), 16, 0,
                Risk.LOW, false, DB_NOW.minusSeconds(241 * 60), ACTOR));

        Sheet sheet = service.sheet(TENANT, ACTOR, "NURSE", ADMISSION);

        assertThat(sheet.asOf()).isEqualTo(DB_NOW).isNotEqualTo(TICK);
        assertThat(sheet.vitalsDue()).isTrue();

        dbNow = DB_NOW.minusSeconds(60);
        assertThat(service.sheet(TENANT, ACTOR, "NURSE", ADMISSION).vitalsDue()).isFalse();
    }

    @Test
    void aSavedAnswerReadsTheDatabaseClockAfterTheWritesSoAsOfIsNeverBeforeRecordedAt() {
        VitalsSaved result = save(normal(UUID.randomUUID()));

        assertThat(calls.indexOf("clock")).isGreaterThan(calls.indexOf("vitals"));
        assertThat(calls.stream().filter("clock"::equals)).hasSize(1);
        assertThat(result.asOf()).isEqualTo(DB_NOW);
        assertThat(result.asOf()).isAfterOrEqualTo(result.latest().recordedAt());
        assertThat(result.asOf()).isAfterOrEqualTo(result.saved().recordedAt());
    }

    @Test
    void aReplayReadsTheDatabaseClockToo() {
        UUID key = UUID.randomUUID();
        save(normal(key));
        calls.clear();
        dbNow = DB_NOW.plusSeconds(600);

        VitalsSaved again = save(normal(key));

        assertThat(again.asOf()).isEqualTo(DB_NOW.plusSeconds(600));
        assertThat(calls).contains("clock");
    }

    // ---- replay: compare with the stored record (security review SAH-30 L1, L2) ----

    @Test
    void aRepeatWithAnyDifferentValueIs409ClientKeyReusedAndWritesNothingNotEvenAnAccessRow() {
        UUID key = UUID.randomUUID();
        save(input(key, "120/80", "80", "98", "37.0", "16", SECRET_NOTE));
        int events0 = events.rows.size();
        VitalsInput[] changed = {
            input(key, "121/80", "80", "98", "37.0", "16", SECRET_NOTE),
            input(key, "120/81", "80", "98", "37.0", "16", SECRET_NOTE),
            input(key, "120/80", "81", "98", "37.0", "16", SECRET_NOTE),
            input(key, "120/80", "80", "97", "37.0", "16", SECRET_NOTE),
            input(key, "120/80", "80", "98", "37.1", "16", SECRET_NOTE),
            input(key, "120/80", "80", "98", "37.0", "17", SECRET_NOTE),
            input(key, "120/80", "80", "98", "37.0", "16", null),
            input(key, "120/80", "80", "98", "37.0", "16", "   "),
        };

        for (VitalsInput other : changed) {
            assertThatThrownBy(() -> save(other)).isInstanceOfSatisfying(ConflictException.class, e -> {
                assertThat(e.problemType()).isEqualTo("client-key-reused");
                assertThat(e.getMessage()).isEqualTo("This reading was already saved with different values. Reload "
                        + "the page and try again.");
            });
        }

        verifyNoInteractions(patientAccess);
        assertThat(vitals.rows).hasSize(1);
        assertThat(vitals.notes).hasSize(1);
        assertThat(events.rows).hasSize(events0);
        verify(auditLog, times(1)).record(eq("IPD_VITALS_RECORDED"), any(), any(), any(), any());
    }

    @Test
    void aRepeatThatAddsANoteToAFirstSaveWithoutOneIsDifferent() {
        UUID key = UUID.randomUUID();
        save(normal(key));

        assertThatThrownBy(() -> save(input(key, "120/80", "80", "98", "37.0", "16", "a note")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aRepeatWithTheSameValuesIs200EvenIfTheNoteTextIsNotTheSameOrTheTemperatureIsTypedDifferently() {
        UUID key = UUID.randomUUID();
        save(input(key, "120/80", "80", "98", "37.0", "16", "first text"));

        VitalsSaved again = save(input(key, " 120/80 ", "080", "98", "37", "16", "another text"));

        assertThat(again.saved().replayed()).isTrue();
        assertThat(again.saved().noteAdded()).isTrue();
        assertThat(vitals.notes).hasSize(1);
    }

    @Test
    void aRepeatWithDifferentValuesAfterTheDischargeIsStill409AndASameValueRepeatStill200() {
        UUID key = UUID.randomUUID();
        save(normal(key));
        status = "DISCHARGED";

        assertThatThrownBy(() -> save(medium(key))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.problemType()).isEqualTo("client-key-reused"));
        assertThat(save(normal(key)).saved().replayed()).isTrue();
    }

    @Test
    void aRepeatForAnotherAdmissionIsStill400BeforeAnyComparison() {
        UUID key = UUID.randomUUID();
        vitals.rows.add(new VitalsRow(UUID.randomUUID(), UUID.randomUUID(), 99, 60, 60, 90, new BigDecimal("36.0"), 12,
                0, Risk.LOW, false, TX.minusSeconds(60), ACTOR));
        vitals.clientKeys.add(key);

        assertThatThrownBy(() -> save(normal(key))).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.field()).isEqualTo("clientKey"));
        verifyNoInteractions(patientAccess);
    }

    @Test
    void aReplayWritesExactlyOneRecordAccessRowAndAFirstSaveWritesNone() {
        UUID key = UUID.randomUUID();
        save(normal(key));
        verifyNoInteractions(patientAccess);

        save(normal(key));

        verify(patientAccess, times(1)).recordOpened(TENANT, PATIENT, ACTOR);
        save(normal(key));
        verify(patientAccess, times(2)).recordOpened(TENANT, PATIENT, ACTOR);
        assertThat(vitals.rows).hasSize(1);
        verify(auditLog, times(1)).record(eq("IPD_VITALS_RECORDED"), any(), any(), any(), any());
    }

    // ---- the patient (code review N6) ----

    @Test
    void aMissingPatientIs404OnTheSheetWithoutAnAccessRowAndNeverAPlaceholderName() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(patientAccess);
    }

    @Test
    void aMissingPatientIs404OnTheSaveUnderTheLockBeforeTheKeyLookupAndWritesNothing() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);

        assertThat(calls).containsExactly("roleReread", "lock");
        assertNothingWritten();
        verifyNoInteractions(patientAccess);
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- the note text (ADR-006 D7b) ----

    @Test
    void aTabInTheNoteIsStoredAsASpace() {
        save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16", "BP\tchecked\tagain"));

        assertThat(vitals.notes.get(0).note()).isEqualTo("BP checked again");
    }

    @Test
    void aRawNoteOverFourThousandCharactersIsRefusedWithTheLengthMessage() {
        assertThatThrownBy(() -> save(input(UUID.randomUUID(), "120/80", "80", "98", "37.0", "16",
                "a".repeat(4001)))).isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).hasSize(1);
                    assertThat(e.errors().get(0).field()).isEqualTo("note");
                    assertThat(e.errors().get(0).message()).isEqualTo("Keep the note to 1,000 characters.");
                });

        assertNothingWritten();
    }

    // ---- helpers ----

    private void assertNothingWritten() {
        assertThat(vitals.rows).isEmpty();
        assertThat(vitals.notes).isEmpty();
        assertThat(escalations.rows).isEmpty();
        assertThat(events.rows).isEmpty();
    }

    // ---- in-memory tables ----

    private final class FakeVitals implements VitalsRepository {
        final List<VitalsRow> rows = new ArrayList<>();
        final List<UUID> clientKeys = new ArrayList<>();
        final List<NoteRow> notes = new ArrayList<>();
        final List<UUID> noteAdmissions = new ArrayList<>();
        final List<UUID> noteBy = new ArrayList<>();

        @Override
        public Instant databaseNow() {
            calls.add("clock");
            return dbNow;
        }

        @Override
        public Optional<VitalsRow> findLatest(UUID tenantId, UUID admissionId) {
            return rows.stream().filter(r -> r.admissionId().equals(admissionId))
                    .max(Comparator.comparing(VitalsRow::recordedAt));
        }

        @Override
        public java.util.Map<UUID, LatestScore> findLatestOfAdmitted(UUID tenantId) {
            throw new UnsupportedOperationException("not used by the vitals service");
        }

        @Override
        public java.util.Map<UUID, LatestScore> findLatestReadingsOfAdmitted(UUID tenantId) {
            throw new UnsupportedOperationException("not used by the vitals service");
        }

        @Override
        public Optional<VitalsRow> findByClientKey(UUID tenantId, UUID clientKey) {
            calls.add("keyLookup");
            int index = clientKeys.indexOf(clientKey);
            return index < 0 ? Optional.empty() : Optional.of(rows.get(index));
        }

        @Override
        public Stored insert(UUID tenantId, NewVitals v) {
            if (clientKeys.contains(v.clientKey())) {
                throw new DuplicateKeyException("uq_vitals_tenant_client_key");
            }
            calls.add("vitals");
            UUID id = UUID.randomUUID();
            rows.add(new VitalsRow(id, v.admissionId(), v.bpSystolic(), v.bpDiastolic(), v.pulse(), v.spo2(),
                    v.tempC(), v.rr(), v.ewsScore(), v.ewsRisk(), v.noteAdded(), TX, v.recordedBy()));
            clientKeys.add(v.clientKey());
            return new Stored(id, TX);
        }

        @Override
        public UUID insertNote(UUID tenantId, UUID admissionId, String note, UUID recordedBy) {
            UUID id = UUID.randomUUID();
            notes.add(new NoteRow(id, note, TX, recordedBy));
            noteAdmissions.add(admissionId);
            noteBy.add(recordedBy);
            return id;
        }

        @Override
        public List<NoteRow> findRecentNotes(UUID tenantId, UUID admissionId, int limit) {
            List<NoteRow> result = new ArrayList<>();
            for (int i = notes.size() - 1; i >= 0 && result.size() < limit; i--) {
                result.add(notes.get(i));
            }
            result.sort(Comparator.comparing(NoteRow::recordedAt).reversed());
            return result;
        }
    }

    private final class FakeEscalations implements EscalationRepository {
        final List<EscalationRow> rows = new ArrayList<>();
        final List<UUID> raisedBy = new ArrayList<>();
        int updates;
        int reopens;
        boolean refuseReopen;
        boolean hideOpenFromReads;

        UUID seed(UUID admissionId, int score, Risk risk, int target, String status, Instant raisedAt) {
            UUID id = UUID.randomUUID();
            rows.add(new EscalationRow(id, admissionId, score, risk, target, status, raisedAt, null));
            return id;
        }

        @Override
        public Optional<EscalationRow> findOpen(UUID tenantId, UUID admissionId) {
            calls.add("escalationRead");
            if (hideOpenFromReads) {
                return Optional.empty();
            }
            return rows.stream().filter(r -> r.admissionId().equals(admissionId) && !"DONE".equals(r.status()))
                    .findFirst();
        }

        @Override
        public UUID insert(UUID tenantId, UUID admissionId, int ewsScore, Risk ewsRisk, int targetMinutes,
                           UUID by) {
            calls.add("escalationInsert");
            if (rows.stream().anyMatch(r -> r.admissionId().equals(admissionId) && !"DONE".equals(r.status()))) {
                throw new DuplicateKeyException("ux_ews_escalation_tenant_admission_open");
            }
            UUID id = seed(admissionId, ewsScore, ewsRisk, targetMinutes, "OPEN", TX);
            raisedBy.add(by);
            return id;
        }

        @Override
        public boolean raiseScore(UUID tenantId, UUID id, int ewsScore, Risk ewsRisk, int targetMinutes) {
            for (int i = 0; i < rows.size(); i++) {
                EscalationRow r = rows.get(i);
                if (r.id().equals(id) && !"DONE".equals(r.status()) && r.ewsScore() < ewsScore) {
                    rows.set(i, new EscalationRow(id, r.admissionId(), ewsScore, ewsRisk, targetMinutes, r.status(),
                            r.raisedAt(), r.reopenedAt()));
                    updates++;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean reopen(UUID tenantId, UUID id, int ewsScore) {
            if (refuseReopen) {
                return false;
            }
            for (int i = 0; i < rows.size(); i++) {
                EscalationRow r = rows.get(i);
                if (r.id().equals(id) && "ACK".equals(r.status()) && r.ewsRisk() == Risk.MEDIUM) {
                    rows.set(i, new EscalationRow(id, r.admissionId(), ewsScore, Risk.HIGH, 15, "OPEN",
                            r.raisedAt(), Instant.now()));
                    reopens++;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean closeAtDischarge(UUID tenantId, UUID id, UUID closedBy) {
            return false;
        }
    }

    private static final class FakeEvents implements ClinicalEventRepository {
        record Event(UUID admissionId, String kind, String text, UUID actor) {
        }

        final List<Event> rows = new ArrayList<>();

        @Override
        public void insert(UUID tenantId, UUID admissionId, String kind, String text, UUID actor) {
            rows.add(new Event(admissionId, kind, text, actor));
        }

        @Override
        public List<EventRow> findPage(UUID tenantId, UUID onlyForNurse, Position after, int limit) {
            return List.of();
        }

        @Override
        public void insertForGrant(UUID tenantId, UUID admissionId, String text, UUID actor, UUID grantId) {
            rows.add(new Event(admissionId, KIND_BG, text, actor));
        }

        @Override
        public List<EventRow> findPageInScope(UUID tenantId, UUID nurse, java.util.Set<UUID> wardIds,
                                              java.util.Set<UUID> grantAdmissionIds, Position after, int limit) {
            return List.of();
        }
    }

    // ---- SAH-34 patient scope ----

    @Test
    void sheetOutsideScopeWritesNoRecordAccess() {
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmission(eq(TENANT), eq(ACTOR),
                eq("NURSE"), any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.sheet(TENANT, ACTOR, "NURSE", ADMISSION))
                .isInstanceOf(OutsideScopeException.class);

        verifyNoInteractions(patientAccess);
        assertThat(vitals.rows).isEmpty();
    }

    @Test
    void recordOutsideScopeWritesNothing() {
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT),
                eq(ACTOR), eq("NURSE"), any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> save(normal(UUID.randomUUID()))).isInstanceOf(OutsideScopeException.class);

        assertThat(vitals.rows).isEmpty();
        assertThat(vitals.notes).isEmpty();
        assertThat(escalations.rows).isEmpty();
        assertThat(events.rows).isEmpty();
        verifyNoInteractions(patientAccess, auditLog);
    }

    @Test
    void theOutsideScopeAnswerIsAfterTheLockAndTheNotFoundAndBeforeTheRepeatCheck() {
        UUID key = UUID.randomUUID();
        save(normal(key));
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT),
                eq(ACTOR), eq("NURSE"), any(), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> save(normal(key))).isInstanceOf(OutsideScopeException.class);

        assertThatThrownBy(() -> service.record(TENANT, ACTOR, "NURSE", UUID.randomUUID(), normal(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }
}
