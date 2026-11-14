package com.sahaj.opd.application.emr.finish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrEncounterSource.EmergencyCaseFacts;
import com.sahaj.opd.api.EmrIpdOrderPort;
import com.sahaj.opd.api.EmrRoundPort;
import com.sahaj.opd.api.events.EmrRecordSigned;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.FollowUpService;
import com.sahaj.opd.application.FollowUpService.VisitFollowUpResult;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.VisitService.FinishMove;
import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.BillSummary;
import com.sahaj.opd.application.emr.EmrRecordService.Encounter;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrValueKind;
import com.sahaj.opd.application.emr.EmrViews;
import com.sahaj.opd.application.emr.EmrUsageRecorder;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.EmrViews.StillNeededItem;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.opd.application.emr.finish.FinishModels.ErOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBlocker;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishChoices;
import com.sahaj.opd.application.emr.finish.FinishModels.Share;
import com.sahaj.opd.application.emr.finish.FinishModels.SignInput;
import com.sahaj.opd.application.emr.finish.FinishModels.SignResult;
import com.sahaj.opd.application.emr.EmrModel;
import com.sahaj.opd.application.emr.specialty.ProfileEffects;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientClinicalUpdatePort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * SAH-51-2, SAH-67-1, SAH-67-2 (ADR-025 D1, D6, D8, D9): the sign is one transaction in a fixed order, idempotent by key and
 * body, and any refusal anywhere reaches the caller as an exception (the transaction rolls back). Ports are mocked: this
 * checks the sequence, the refusals and the outcome, not the other modules.
 */
@SuppressWarnings("unchecked")
class EmrSignServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final String KEY = "key-0001-abcd";
    private static final Instant NOW = Instant.parse("2026-11-13T05:30:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository recordRows = mock(EmrRecordRepository.class);
    private final EmrFinishRepository finish = mock(EmrFinishRepository.class);
    private final VisitService visits = mock(VisitService.class);
    private final FollowUpService followUps = mock(FollowUpService.class);
    private final AppointmentService appointments = mock(AppointmentService.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final EmrIpdOrderPort ipdOrders = mock(EmrIpdOrderPort.class);
    private final EmrRoundPort roundPort = mock(EmrRoundPort.class);
    private final EmrEmergencyPort emergencyPort = mock(EmrEmergencyPort.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final PatientClinicalUpdatePort patientUpdates = mock(PatientClinicalUpdatePort.class);
    private final EmrUsageRecorder usage = mock(EmrUsageRecorder.class);
    private final EmrAdmissionService admissions = mock(EmrAdmissionService.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SpecialtyFinishContributor contributor = mock(SpecialtyFinishContributor.class);
    private final TrackingTransactions transactions = new TrackingTransactions();
    private EmrSignService service;
    private TeamMember member;

    /** A transaction manager that counts the transactions begun, committed and rolled back, and the ones open now. */
    static final class TrackingTransactions implements PlatformTransactionManager {
        int begun;
        int committed;
        int rolledBack;
        int open;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            begun++;
            open++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            committed++;
            open--;
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            rolledBack++;
            open--;
        }
    }

    @BeforeEach
    void setUp() {
        member = new TeamMember(MEMBERSHIP, "Dr Test", "DOCTOR", true, 50000);
        when(team.find(TENANT, MEMBERSHIP)).thenReturn(Optional.of(member));
        when(contributor.profile()).thenReturn("dental");
        service = new EmrSignService(records, recordRows, finish, visits, followUps, appointments, courses,
                provider(ipdOrders), provider(roundPort), provider(emergencyPort), patients, patientUpdates, usage,
                admissions, team, audit, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), events, transactions, List.of(contributor));
        when(finish.state(eq(TENANT), eq(RECORD))).thenReturn(Optional.of(state("DRAFT", null, null, false)));
        when(recordRows.findById(eq(TENANT), eq(RECORD))).thenReturn(Optional.of(row("OPD_VISIT", "DRAFT", 3)));
        when(finish.markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(records.viewOf(any(), any())).thenReturn(view(List.of(), List.of()));
        when(emergencyPort.close(any(), any(), any(), any(), any(), any())).thenReturn(new EmrEmergencyPort.CloseOutcome(true));
        when(recordRows.setReferralLine(any(), any(), anyLong(), anyString(), any())).thenReturn(true);
        when(finish.linkPatient(any(), any(), any())).thenReturn(true);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static RecordState state(String status, String key, String hash, boolean holdActive) {
        return new RecordState(RECORD, status, "SIGNED".equals(status) ? MEMBERSHIP : null, null, key, hash,
                "SIGNED".equals(status) ? "{\"stage\":null,\"followUp\":null,\"billLines\":2,\"billTotalPaise\":900,"
                        + "\"ipdOrders\":[],\"round\":null,\"emergency\":null,\"patientRecord\":\"NOT_CHANGED\","
                        + "\"course\":null}" : null,
                holdActive ? UUID.randomUUID() : null, null, holdActive ? NOW.plusSeconds(60) : null, holdActive, null);
    }

    private static RecordRow row(String type, String status, long revision) {
        return rowWithStarter(type, status, revision, "gm");
    }

    private static RecordRow rowWithStarter(String type, String status, long revision, String starter) {
        boolean opd = "OPD_VISIT".equals(type);
        return new RecordRow(RECORD, type, opd ? VISIT : null, "IPD_ROUND".equals(type) ? ADMISSION : null,
                "IPD_ROUND".equals(type) ? LocalDate.of(2026, 11, 13) : null,
                "ER_ASSESSMENT".equals(type) ? CASE : null, opd ? PATIENT : null, MEMBERSHIP, "general", "General", starter,
                "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", status, null, "{}", "ASSESSMENT", null, false, null, revision,
                NOW, null, null, "[]", null);
    }

    /** A real view with only what the sign reads: the footer items and the blockers (no mock inside a stubbing). */
    private static RecordView view(List<StillNeededItem> items, List<String> blockers) {
        return new RecordView(RECORD, "OPD_VISIT", VISIT, null, "DRAFT", false, null, 3, null, null, List.of(), List.of(),
                List.of(), items.isEmpty() && blockers.isEmpty(), null, false, null, List.of(), null, List.of(), blockers,
                null, null, false, items, null);
    }

    private static VisitRow visit() {
        return new VisitRow(VISIT, PATIENT, MEMBERSHIP, "NEW", "WITH_DOCTOR", 5, 50000, NOW.minusSeconds(3600), 0, 1);
    }

    private SignContext opdContext(EmrRecordDocument document, RecordView view) {
        return new SignContext(member, row("OPD_VISIT", "DRAFT", 3),
                new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"), view, document);
    }

    private void contextIs(SignContext ctx) {
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(ctx);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(false))).thenReturn(ctx);
    }

    private static EmrRecordDocument documentWithFollowUp(String interval) {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode next = values.putObject("next_visit");
        next.put("component", "FOLLOW_UP");
        next.put("schemaVersion", 1);
        next.put("interval", interval);
        document.put("followup", values, false, NOW, MEMBERSHIP);
        return document;
    }

    private SignInput input(FinishChoices choices) {
        return new SignInput(3L, choices);
    }

    // ---- the happy path and its order ----

    @Test
    void anOpdSignDoesFollowUpBillMoveAndRankingInOrderThenStoresTheSignature() {
        contextIs(opdContext(documentWithFollowUp("D7"), view(List.of(), List.of())));
        UUID followUpId = UUID.randomUUID();
        when(followUps.setForVisit(eq(TENANT), eq(USER), any(VisitRow.class), eq(LocalDate.of(2026, 11, 20)),
                eq("Review after treatment"))).thenReturn(new VisitFollowUpResult(followUpId, LocalDate.of(2026, 11, 20),
                true, false, null));
        when(appointments.bookFirstFree(any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(2, 900, List.of()));
        when(visits.finishConsultation(TENANT, USER, VISIT)).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.outcome().stage().outcome()).isEqualTo("MOVED");
        assertThat(result.outcome().followUp().noFreeSlot()).isTrue();
        assertThat(result.outcome().followUp().dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(result.outcome().billLines()).isEqualTo(2);
        assertThat(signedAudit()).containsEntry("appointmentId", null).containsEntry("followUpId", followUpId);
        InOrder order = inOrder(followUps, records, visits, usage, finish, audit, events);
        order.verify(followUps).setForVisit(any(), any(), any(), any(), any());
        order.verify(records).syncBillLines(any(), any(), any());
        order.verify(visits).finishConsultation(TENANT, USER, VISIT);
        order.verify(usage).recordSignedVisit(eq(TENANT), eq(MEMBERSHIP), eq(NOW), any(Set.class));
        order.verify(finish).markSigned(eq(TENANT), eq(RECORD), eq(USER), eq(MEMBERSHIP), eq(KEY), anyString(), anyString());
        order.verify(audit).record(eq("EMR_RECORD_SIGNED"), eq("EMR_RECORD"), eq(RECORD), any(), any());
        order.verify(events).publishEvent(any(EmrRecordSigned.class));
    }

    // ---- step 9: the linked course sitting (SAH-75-10, ADR-026 D4: one state, one charge) ----

    private void signWithLinkedSitting(SittingRow sitting) {
        contextIs(opdContext(documentWithFollowUp("D7"), view(List.of(), List.of())));
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(
                UUID.randomUUID(), LocalDate.of(2026, 11, 20), true, false, null));
        when(appointments.bookFirstFree(any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(TENANT, USER, VISIT)).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting));
        service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));
    }

    private static SittingRow linkedSitting(Instant doneAt, Instant removedAt) {
        return new SittingRow(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), UUID.randomUUID(), 3, "OPD",
                "OPD_SITTING", null, List.of(), 100_000, null, VISIT, null, false, doneAt, null, null, removedAt,
                doneAt == null ? null : "MARK_DONE", null);
    }

    @Test
    void theSignMarksALinkedSittingDoneWhenItIsNotDoneYet() {
        SittingRow sitting = linkedSitting(null, null);

        signWithLinkedSitting(sitting);

        verify(courses).markDone(eq(TENANT), eq(sitting.id()), eq(NOW), eq(USER));
        verify(courses).bumpRevision(TENANT, sitting.courseId(), USER);
    }

    @Test
    void aSittingMarkedDoneByHandFirstIsNotMarkedAgainSoItsChargeIsCountedOnce() {
        SittingRow sitting = linkedSitting(Instant.parse("2026-11-12T05:00:00Z"), null);

        signWithLinkedSitting(sitting);

        verify(courses, never()).markDone(any(), any(), any(), any());
        verify(courses, never()).bumpRevision(any(), any(), any());
    }

    @Test
    void aRemovedLinkedSittingIsSkippedBecauseRemovedAndDoneExcludeEachOtherInTheTable() {
        SittingRow sitting = linkedSitting(null, Instant.parse("2026-11-11T05:00:00Z"));

        signWithLinkedSitting(sitting);

        verify(courses, never()).markDone(any(), any(), any(), any());
        verify(courses, never()).bumpRevision(any(), any(), any());
        verify(finish).markSigned(eq(TENANT), eq(RECORD), eq(USER), eq(MEMBERSHIP), eq(KEY), anyString(), anyString());
    }

    @Test
    void theFollowUpAppointmentAndTheOutcomeCarryTheBookedSlot() {
        contextIs(opdContext(documentWithFollowUp("M1"), view(List.of(), List.of())));
        UUID followUpId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(followUpId,
                LocalDate.of(2026, 12, 13), true, false, null));
        when(appointments.bookFirstFree(eq(TENANT), eq(USER), eq(PATIENT), eq(MEMBERSHIP), eq(LocalDate.of(2026, 12, 13)),
                eq(7), eq("FOLLOW_UP"), eq("EMR"))).thenReturn(Optional.of(new AppointmentService.BookedSlot(appointmentId,
                LocalDate.of(2026, 12, 14), java.time.LocalTime.of(17, 0))));
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(TENANT, USER, VISIT)).thenReturn(new FinishMove("ALREADY_MOVED", "BILLING"));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        assertThat(result.outcome().followUp().appointmentDate()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(result.outcome().followUp().appointmentTime()).isEqualTo("17:00");
        assertThat(result.outcome().followUp().noFreeSlot()).isFalse();
        verify(followUps).attachAppointment(TENANT, USER, followUpId, appointmentId, "M1");
        assertThat(result.outcome().stage().outcome()).isEqualTo("ALREADY_MOVED");
        assertThat(signedAudit().get("appointmentId")).as("ADR-025 D18: the booked appointment is audited")
                .isEqualTo(appointmentId);
    }

    @Test
    void aRefusalInTheMoveReachesTheCallerSoTheTransactionRollsBackAndNothingIsSigned() {
        contextIs(opdContext(documentWithFollowUp("D3"), view(List.of(), List.of())));
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(UUID.randomUUID(),
                LocalDate.of(2026, 11, 16), true, false, null));
        when(appointments.bookFirstFree(any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(any(), any(), any())).thenThrow(EmrProblemException.workflowMoveRefused());

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOf(EmrProblemException.class).extracting(e -> ((EmrProblemException) e).slug())
                .isEqualTo("workflow-move-refused");
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
        verify(usage, never()).recordSignedVisit(any(), any(), any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void theProfileIsAppliedFirstAndItsFollowUpDateWinsOverTheInterval() {
        contextIs(new SignContext(member, rowWithStarter("OPD_VISIT", "DRAFT", 3, "dental"),
                new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"), view(List.of(), List.of()),
                documentWithFollowUp("M3")));
        UUID appointmentId = UUID.randomUUID();
        when(contributor.apply(any())).thenReturn(new SpecialtyFinishContributor.ProfileResult(
                new FinishModels.CourseOutcome(null, UUID.randomUUID(), LocalDate.of(2026, 11, 20), "10:00", false), 1, 300,
                LocalDate.of(2026, 11, 20), appointmentId));
        when(followUps.setForVisit(any(), any(), any(), eq(LocalDate.of(2026, 11, 20)), any())).thenReturn(
                new VisitFollowUpResult(UUID.randomUUID(), LocalDate.of(2026, 11, 20), true, false, null));
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(1, 500, List.of()));
        when(visits.finishConsultation(any(), any(), any())).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(any(), any())).thenReturn(Optional.empty());

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        verify(appointments, never()).bookFirstFree(any(), any(), any(), any(), any(), anyInt(), anyString(), anyString());
        assertThat(result.outcome().billLines()).isEqualTo(2);
        assertThat(result.outcome().billTotalPaise()).isEqualTo(800);
        assertThat(result.outcome().course().nextAppointmentTime()).isEqualTo("10:00");
        assertThat(signedAudit().get("appointmentId")).as("the profile's appointment is audited").isEqualTo(appointmentId);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> signedAudit() {
        ArgumentCaptor<java.util.Map<String, Object>> after = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq("EMR_RECORD_SIGNED"), eq("EMR_RECORD"), eq(RECORD), any(), after.capture());
        return after.getValue();
    }

    // ---- idempotency and refusals ----

    @Test
    void theSameKeyAndBodyAfterSuccessReplaysTheFirstResultAndDoesNothingAgain() {
        contextIs(opdContext(EmrRecordDocument.empty(MAPPER), view(List.of(), List.of())));
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("OPD_VISIT", "SIGNED", 4), new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", "SIGNED", 4)));
        SignInput body = input(FinishChoices.none());
        String hash = hashOf(body);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, hash, false)));

        SignResult result = service.sign(DOCTOR, RECORD, KEY, body);

        assertThat(result.replayed()).isTrue();
        assertThat(result.outcome().billLines()).isEqualTo(2);
        verify(visits, never()).finishConsultation(any(), any(), any());
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void theSameKeyWithAnotherBodyIsAnIdempotencyConflictAndAnotherKeyNamesTheSigner() {
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("OPD_VISIT", "SIGNED", 4), new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", "SIGNED", 4)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, "0".repeat(64), false)));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("idempotency-conflict");
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, "other-key-9999", input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("emr-record-signed");
                    assertThat(e.getMessage()).isEqualTo("Already signed by Dr. Dr Test.");
                });
    }

    @Test
    void aStaleRevisionAndAnotherDoctorsEditHoldAreRefused() {
        contextIs(opdContext(EmrRecordDocument.empty(MAPPER), view(List.of(), List.of())));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, new SignInput(2L, FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-changed");

        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", null, null, true)));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-held");
    }

    @Test
    void aRecordThatIsNotReadyAnswersNotReadyWithTheBlockersAndWritesNothing() {
        contextIs(opdContext(EmrRecordDocument.empty(MAPPER), view(List.of(new StillNeededItem("complaints", "complaints",
                "complaints", null), new StillNeededItem("diagnosis", "diagnosis", "diagnosis", null)),
                List.of("ALLERGY_ALERT"))));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("emr-not-ready");
                    List<FinishBlocker> blockers = (List<FinishBlocker>) e.extensions().get("blockers");
                    assertThat(blockers).extracting(FinishBlocker::key).containsExactly("STILL_NEEDED", "ALLERGY_ALERT");
                    assertThat(blockers.get(0).sectionKeys()).containsExactly("complaints", "diagnosis");
                });
        verify(followUps, never()).setForVisit(any(), any(), any(), any(), any());
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void sendingThePrescriptionOnWhatsappIsRefusedBeforeAnythingIsRead() {
        FinishChoices choices = new FinishChoices(null, null, null, null, null, new Share(true));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(choices)))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("needs-whatsapp-service");
        verify(records, never()).signContext(any(), any(), anyBoolean());
    }

    @Test
    void aBadIdempotencyKeyIsAValidationError() {
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, "short", input(FinishChoices.none())))
                .isInstanceOf(com.sahaj.platform.api.InvalidFieldsException.class);
    }

    // ---- Emergency ----

    private SignContext erContext() {
        EmergencyCaseFacts facts = new EmergencyCaseFacts(CASE, null, "SEEN", 1, "Red", "Unknown", 40, "M", Access.ALLOWED);
        return new SignContext(member, row("ER_ASSESSMENT", "DRAFT", 3), new Encounter("ER_ASSESSMENT", CASE, null, null,
                facts, false, "on"), view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER));
    }

    @Test
    void anEmergencySignLocksTheCaseBeforeTheRecordAndDischargeClosesTheCaseAsTreatAndRelease() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("DISCHARGE", null,
                null, null, null), null, null, null, null, null)));

        InOrder order = inOrder(emergencyPort, records, finish);
        order.verify(emergencyPort).lockCase(TENANT, CASE);
        order.verify(records).signContext(any(ClinicUser.class), eq(RECORD), eq(true));
        order.verify(finish).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
        verify(emergencyPort).close(TENANT, USER, CASE, EmrEmergencyPort.CloseKind.RELEASE, null, null);
        assertThat(result.outcome().emergency().outcome()).isEqualTo("DISCHARGE");
    }

    @Test
    void aCaseClosedByAnotherUserAnswersCaseClosedAndNothingIsWritten() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "RELEASED", false, 1));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("DISCHARGE", null,
                null, null, null), null, null, null, null, null))))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("case-closed");
        verify(records, never()).signContext(any(), any(), anyBoolean());
        verify(emergencyPort, never()).close(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendToOpdWithoutAPatientAndAnEmergencyWithoutAnOutcomeAreNotReady() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> assertThat(((List<FinishBlocker>) e.extensions()
                        .get("blockers")).get(0).key()).isEqualTo("ER_OUTCOME_REQUIRED"));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("SEND_TO_OPD",
                null, null, null, null), null, null, null, null, null))))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> assertThat(((List<FinishBlocker>) e.extensions()
                        .get("blockers")).get(0).key()).isEqualTo("PATIENT_REQUIRED"));
    }

    @Test
    void sendToOpdIssuesATokenForTheSigningDoctorClosesTheCaseAndLinksThePatient() {
        UUID opdPatient = UUID.randomUUID();
        UUID newVisit = UUID.randomUUID();
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());
        VisitView token = mock(VisitView.class);
        when(token.id()).thenReturn(newVisit);
        when(token.tokenNo()).thenReturn(12);
        when(visits.issueTokenForDoctor(TENANT, USER, opdPatient, MEMBERSHIP)).thenReturn(token);

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("SEND_TO_OPD", null,
                null, opdPatient, null), null, null, null, null, null)));

        verify(emergencyPort).close(TENANT, USER, CASE, EmrEmergencyPort.CloseKind.SENT_TO_OPD, opdPatient, newVisit);
        verify(finish).linkPatient(TENANT, RECORD, opdPatient);
        assertThat(result.outcome().emergency().tokenNo()).isEqualTo(12);
    }

    // ---- IPD round ----

    private SignContext roundContext(String ipdOrdersJson) throws Exception {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        if (ipdOrdersJson != null) {
            ObjectNode values = MAPPER.createObjectNode();
            values.set("drug", MAPPER.readTree(ipdOrdersJson));
            document.put("rx", values, false, NOW, MEMBERSHIP);
        }
        AdmissionFacts admission = new AdmissionFacts(ADMISSION, PATIENT, "IP/26-27/0001", "ADMITTED", 2, MEMBERSHIP,
                Access.ALLOWED);
        return new SignContext(member, row("IPD_ROUND", "DRAFT", 3), new Encounter("IPD_ROUND", ADMISSION, null, admission,
                null, false, "on"), view(List.of(), List.of()), document);
    }

    private static final String ONE_ROW = "{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[{\"id\":\""
            + "11111111-1111-1111-1111-111111111111\",\"drugKey\":\"paracetamol\",\"frequencyKey\":\"tds\"}]}";

    @Test
    void aRoundPlacesItsNewMedicinesThroughIpdMarksTheRoundDoneAndKeepsNoBillLine() throws Exception {
        UUID rowId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID orderId = UUID.randomUUID();
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.place(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList())).thenReturn(new EmrIpdOrderPort.PlaceResult(
                true, List.of(new EmrIpdOrderPort.Placed(rowId, orderId, NOW)), List.of()));
        when(roundPort.recordSignedRound(eq(TENANT), eq(USER), any())).thenReturn(EmrRoundPort.RoundOutcome.RECORDED);
        when(recordRows.saveSectionsLocked(any(), any(), anyString(), any())).thenReturn(true);

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        assertThat(result.outcome().ipdOrders()).extracting(FinishModels.IpdOrderPlaced::orderId).containsExactly(orderId);
        assertThat(result.outcome().billLines()).isZero();
        ArgumentCaptor<EmrRoundPort.RoundNote> note = ArgumentCaptor.forClass(EmrRoundPort.RoundNote.class);
        verify(roundPort).recordSignedRound(eq(TENANT), eq(USER), note.capture());
        assertThat(note.getValue().ordersPlaced()).isEqualTo(1);
        verify(visits, never()).finishConsultation(any(), any(), any());
        verify(records, never()).syncBillLines(any(), any(), any());
    }

    @Test
    void aMedicineIpdDoesNotPlaceRefusesTheWholeSignWithTheRowOutcomes() throws Exception {
        UUID rowId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.place(any(), any(), any(), any(), anyList())).thenReturn(new EmrIpdOrderPort.PlaceResult(false, List.of(),
                List.of(new EmrIpdOrderPort.LineCheck(rowId, "Paracetamol", "Three times a day", "BLOCKED",
                        List.of("Allergy"), null))));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("ipd-order-not-placed");
                    assertThat((List<?>) e.extensions().get("rows")).hasSize(1);
                });
        verify(roundPort, never()).recordSignedRound(any(), any(), any());
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void theRefusedRowsNameTheDrugTheOutcomeTheCheckKindAndAFixedReasonAndNeverTheAllergyTextOrTheToken() throws Exception {
        // DEC-051 D3: opd.yaml 0.6.3 EmrIpdOrderNotPlacedRow
        UUID blockedRow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID warnedRow = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.place(any(), any(), any(), any(), anyList())).thenReturn(new EmrIpdOrderPort.PlaceResult(false, List.of(),
                List.of(new EmrIpdOrderPort.LineCheck(blockedRow, "Amoxicillin 500 mg", "Twice a day", "BLOCKED",
                        List.of("Documented allergy: Penicillin rash"), null, "ALLERGY_PENICILLIN"),
                        new EmrIpdOrderPort.LineCheck(warnedRow, "Aspirin 75 mg", "Once a day", "NEEDS_REASON",
                                List.of("Major: Penicillin rash, interacts"), "T".repeat(22), "INTERACTION"))));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    @SuppressWarnings("unchecked")
                    List<FinishModels.IpdOrderNotPlacedRow> rows =
                            (List<FinishModels.IpdOrderNotPlacedRow>) e.extensions().get("rows");
                    assertThat(rows).containsExactly(
                            new FinishModels.IpdOrderNotPlacedRow(blockedRow, "Amoxicillin 500 mg", "BLOCKED",
                                    "ALLERGY_PENICILLIN", "Not ordered: a safety check stopped this medicine."),
                            new FinishModels.IpdOrderNotPlacedRow(warnedRow, "Aspirin 75 mg", "NEEDS_REASON", "INTERACTION",
                                    "Not ordered: a reason to continue is needed."));
                    assertThat(MAPPER.valueToTree(e.extensions()).toString()).contains("ALLERGY_PENICILLIN").doesNotContain("Penicillin rash").doesNotContain("Twice a day")
                            .doesNotContain("TTTT");
                });
    }

    @Test
    void aRefusedRowWithNoFindingHasANullCheckKind() {
        EmrIpdOrderPort.LineCheck refused = new EmrIpdOrderPort.LineCheck(UUID.randomUUID(), "Paracetamol", "Once a day",
                "BLOCKED", List.of(), null);

        assertThat(EmrSignService.refusalRow(refused).checkKind()).isNull();
        assertThat(EmrSignService.refusalRow(refused).reason()).isEqualTo(EmrSignService.REFUSED_BLOCKED_TEXT);
    }

    @Test
    void aFinishCheckOnAClosedEncounterAnswers409EncounterClosedBeforeAnyReadinessIsWorkedOut() {
        // DEC-051 D2: kept as built (the signContext refusal), not "ready false"
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(false)))
                .thenThrow(EmrProblemException.encounterClosed());

        assertThatThrownBy(() -> service.check(DOCTOR, RECORD, FinishChoices.none()))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("encounter-closed");
                });
        verify(finish, never()).state(any(), any());
    }

    @Test
    void aDischargedAdmissionRefusesTheRoundSignAndAnotherDoctorsRoundIsNotTheirs() throws Exception {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(null));
        when(roundPort.recordSignedRound(any(), any(), any())).thenReturn(EmrRoundPort.RoundOutcome.NOT_ADMITTED);

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("encounter-closed");

        TeamMember other = new TeamMember(UUID.randomUUID(), "Dr Other", "DOCTOR", true, 0);
        AdmissionFacts admission = new AdmissionFacts(ADMISSION, PATIENT, "IP/1", "ADMITTED", 2, MEMBERSHIP, Access.ALLOWED);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(other,
                row("IPD_ROUND", "DRAFT", 3), new Encounter("IPD_ROUND", ADMISSION, null, admission, null, false, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("not-own-patient");
    }

    // ---- code and security review fixes (DEC-048 H30, H31; code 1, 2, 4; security L2, L4) ----

    private static final FinishChoices DISCHARGE = new FinishChoices(new ErOutcome("DISCHARGE", null, null, null, null),
            null, null, null, null, null);

    @Test
    void anEmergencyRetryThatWaitedOnTheCaseLockAndFindsTheRecordSignedReplaysTheFirstAnswer() {
        // the first sign closed the case and committed while the retry waited: the case is no longer open, the record is signed
        // the first read is the pre-check plan's (DEC-048 H37), the second the sign's own
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)),
                Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)), Optional.of(row("ER_ASSESSMENT", "SIGNED", 4)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "RELEASED", false, 1));
        EmergencyCaseFacts facts = new EmergencyCaseFacts(CASE, null, "RELEASED", 1, "Red", "Unknown", 40, "M", Access.ALLOWED);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("ER_ASSESSMENT", "SIGNED", 4), new Encounter("ER_ASSESSMENT", CASE, null, null, facts, true, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        SignInput body = input(DISCHARGE);
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, hashOf(body), false)));

        SignResult result = service.sign(DOCTOR, RECORD, KEY, body);

        assertThat(result.replayed()).isTrue();
        verify(emergencyPort, never()).close(any(), any(), any(), any(), any(), any());
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void anotherDoctorsEmergencyRetryAfterTheCaseClosedIsToldWhoSignedNotThatTheCaseLeftTheQueue() {
        // the first read is the pre-check plan's (DEC-048 H37), the second the sign's own
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)),
                Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)), Optional.of(row("ER_ASSESSMENT", "SIGNED", 4)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "RELEASED", false, 1));
        EmergencyCaseFacts facts = new EmergencyCaseFacts(CASE, null, "RELEASED", 1, "Red", "Unknown", 40, "M", Access.ALLOWED);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("ER_ASSESSMENT", "SIGNED", 4), new Encounter("ER_ASSESSMENT", CASE, null, null, facts, true, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", "first-key-0001", "h", false)));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(DISCHARGE)))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("emr-record-signed");
                    assertThat(e.getMessage()).isEqualTo("Already signed by Dr. Dr Test.");
                });
    }

    @Test
    void theCallersAccessIsProvenBeforeTheCaseLocksAreTaken() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());
        service.sign(DOCTOR, RECORD, KEY, input(DISCHARGE));
        InOrder order = inOrder(records, emergencyPort);
        order.verify(records).authorize(any(ClinicUser.class), eq(RECORD));
        order.verify(emergencyPort).lockCase(TENANT, CASE);
    }

    @Test
    void aCallerWhoCannotSignAnEmergencyRecordNeverWaitsOnTheCaseLocks() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(records.authorize(any(ClinicUser.class), eq(RECORD))).thenThrow(EmrProblemException.outsideScope());

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(DISCHARGE)))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("outside-scope");
        verify(emergencyPort, never()).lockCase(any(), any());
    }

    @Test
    void theVisitRowIsLockedRightAfterTheRecordLockBeforeAnyFollowUpOrBillWrite() {
        contextIs(opdContext(documentWithFollowUp("D7"), view(List.of(), List.of())));
        stubOpdSign();

        service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        InOrder order = inOrder(records, visits, followUps);
        order.verify(records).signContext(any(ClinicUser.class), eq(RECORD), eq(true));
        order.verify(visits).lockForSign(TENANT, VISIT);
        order.verify(followUps).setForVisit(any(), any(), any(), any(), any());
        order.verify(records).syncBillLines(any(), any(), any());
        order.verify(visits).finishConsultation(TENANT, USER, VISIT);
    }

    @Test
    void aReplayOfASignedVisitTakesNoVisitLock() {
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("OPD_VISIT", "SIGNED", 4), new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        SignInput body = input(FinishChoices.none());
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, hashOf(body), false)));

        assertThat(service.sign(DOCTOR, RECORD, KEY, body).replayed()).isTrue();
        verify(visits, never()).lockForSign(any(), any());
    }

    @Test
    void aReferralLineThatIsNotStoredAPatientLinkThatIsNotMadeAndACaseThatDoesNotCloseRefuseTheSign() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        when(recordRows.setReferralLine(any(), any(), anyLong(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("REFER_OUT",
                "District hospital", null, null, null), null, null, null, null, null))))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-changed");
        verify(emergencyPort, never()).close(any(), any(), any(), any(), any(), any());

        when(emergencyPort.close(any(), any(), any(), any(), any(), any())).thenReturn(new EmrEmergencyPort.CloseOutcome(false));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(DISCHARGE)))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("case-closed");

        UUID opdPatient = UUID.randomUUID();
        VisitView token = mock(VisitView.class);
        when(token.id()).thenReturn(UUID.randomUUID());
        when(visits.issueTokenForDoctor(TENANT, USER, opdPatient, MEMBERSHIP)).thenReturn(token);
        when(emergencyPort.close(any(), any(), any(), any(), any(), any())).thenReturn(new EmrEmergencyPort.CloseOutcome(true));
        when(finish.linkPatient(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("SEND_TO_OPD",
                null, null, opdPatient, null), null, null, null, null, null))))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-changed");
    }

    @Test
    void aRoundOrAnEmergencyAssessmentIsNotSignedWhileIpdIsReadOnly() throws Exception {
        AdmissionFacts admission = new AdmissionFacts(ADMISSION, PATIENT, "IP/1", "ADMITTED", 2, MEMBERSHIP, Access.ALLOWED);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), anyBoolean())).thenReturn(new SignContext(member,
                row("IPD_ROUND", "DRAFT", 3), new Encounter("IPD_ROUND", ADMISSION, null, admission, null, false,
                        "readonly"), view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("ipd-read-only");
                });
        verify(roundPort, never()).recordSignedRound(any(), any(), any());

        EmergencyCaseFacts facts = new EmergencyCaseFacts(CASE, null, "SEEN", 1, "Red", "Unknown", 40, "M", Access.ALLOWED);
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        when(records.signContext(any(ClinicUser.class), eq(RECORD), anyBoolean())).thenReturn(new SignContext(member,
                row("ER_ASSESSMENT", "DRAFT", 3), new Encounter("ER_ASSESSMENT", CASE, null, null, facts, false,
                        "readonly"), view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(DISCHARGE)))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("ipd-read-only");
        verify(emergencyPort, never()).close(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aHardStopMetAtSignIsRecordedThroughTheCommittedCheckBeforeTheSignIsRefusedAndNothingIsPlaced() throws Exception {
        UUID rowId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.checkRecorded(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList())).thenReturn(List.of(
                new EmrIpdOrderPort.LineCheck(rowId, "Paracetamol", "Three times a day", "BLOCKED", List.of("Allergy"),
                        null)));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("ipd-order-not-placed");
                    assertThat((List<?>) e.extensions().get("rows")).hasSize(1);
                });
        verify(ipdOrders).checkRecorded(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList());
        verify(ipdOrders, never()).place(any(), any(), any(), any(), anyList());
        verify(roundPort, never()).recordSignedRound(any(), any(), any());
        verify(finish, never()).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ---- DEC-048 H37: the pre-check runs before the sign transaction opens ----

    private static final UUID ROW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private void roundIsOpen() throws Exception {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(ONE_ROW));
    }

    @Test
    void theIpdOrderPreCheckRunsWithNoTransactionOpenAndTheSignTransactionOpensAfterIt() throws Exception {
        roundIsOpen();
        int[] openDuringCheck = {-1};
        int[] begunDuringCheck = {-1};
        when(ipdOrders.checkRecorded(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList())).thenAnswer(call -> {
            openDuringCheck[0] = transactions.open;
            begunDuringCheck[0] = transactions.begun;
            return List.of(new EmrIpdOrderPort.LineCheck(ROW_ID, "Paracetamol", "Three times a day", "PASS", List.of(), null));
        });
        when(ipdOrders.place(any(), any(), any(), any(), anyList())).thenAnswer(call -> {
            assertThat(transactions.open).as("the sign transaction is open when the orders are placed").isEqualTo(1);
            return new EmrIpdOrderPort.PlaceResult(true,
                    List.of(new EmrIpdOrderPort.Placed(ROW_ID, UUID.randomUUID(), NOW)), List.of());
        });
        when(roundPort.recordSignedRound(eq(TENANT), eq(USER), any())).thenReturn(EmrRoundPort.RoundOutcome.RECORDED);
        when(recordRows.saveSectionsLocked(any(), any(), anyString(), any())).thenReturn(true);

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        assertThat(openDuringCheck[0]).as("no connection is held while the order port opens its own").isZero();
        assertThat(begunDuringCheck[0]).as("the short plan transaction ended before the check").isEqualTo(1);
        assertThat(transactions.begun).as("the plan and the sign").isEqualTo(2);
        assertThat(transactions.committed).isEqualTo(2);
        assertThat(transactions.open).isZero();
        assertThat(result.outcome().ipdOrders()).hasSize(1);
        InOrder order = inOrder(ipdOrders);
        order.verify(ipdOrders).checkRecorded(any(), any(), any(), any(), anyList());
        order.verify(ipdOrders).place(any(), any(), any(), any(), anyList());
    }

    @Test
    void aBlockedRowRefusesBeforeTheSignTransactionEverOpens() throws Exception {
        roundIsOpen();
        when(ipdOrders.checkRecorded(any(), any(), any(), any(), anyList())).thenReturn(List.of(
                new EmrIpdOrderPort.LineCheck(ROW_ID, "Paracetamol", "Three times a day", "BLOCKED", List.of("Allergy"),
                        null)));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("ipd-order-not-placed");
                });
        assertThat(transactions.begun).as("only the short plan transaction was opened").isEqualTo(1);
        assertThat(transactions.committed).isEqualTo(1);
        assertThat(transactions.open).isZero();
        verify(records, never()).signContext(any(ClinicUser.class), eq(RECORD), eq(true));
        verify(ipdOrders, never()).place(any(), any(), any(), any(), anyList());
    }

    @Test
    void aWarningRowStillGoesOnToTheSignWhichChecksAgainWhenItPlaces() throws Exception {
        roundIsOpen();
        when(ipdOrders.checkRecorded(any(), any(), any(), any(), anyList())).thenReturn(List.of(
                new EmrIpdOrderPort.LineCheck(ROW_ID, "Paracetamol", "Three times a day", "NEEDS_REASON", List.of("Warn"),
                        "token")));
        when(ipdOrders.place(any(), any(), any(), any(), anyList())).thenReturn(new EmrIpdOrderPort.PlaceResult(false,
                List.of(), List.of(new EmrIpdOrderPort.LineCheck(ROW_ID, "Paracetamol", "Three times a day",
                        "NEEDS_REASON", List.of("Warn"), "token"))));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("ipd-order-not-placed");
        assertThat(transactions.begun).isEqualTo(2);
        assertThat(transactions.rolledBack).as("the sign rolled back").isEqualTo(1);
        verify(ipdOrders).place(any(), any(), any(), any(), anyList());
    }

    @Test
    void aCallerWhoWouldBeRefusedIsNeverPreCheckedAndGetsTheSignsOwnRefusal() throws Exception {
        roundIsOpen();
        when(records.signContext(any(ClinicUser.class), eq(RECORD), anyBoolean()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("no"));

        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(ipdOrders, never()).checkRecorded(any(), any(), any(), any(), anyList());

        org.mockito.Mockito.doThrow(new com.sahaj.platform.api.NotFoundException("Record not found")).when(records)
                .signContext(any(ClinicUser.class), eq(RECORD), anyBoolean());
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        verify(ipdOrders, never()).checkRecorded(any(), any(), any(), any(), anyList());
        assertThat(transactions.open).isZero();
    }

    @Test
    void aStaleRevisionAnotherDoctorsRoundAndAReadOnlyIpdAreNotPreCheckedEither() throws Exception {
        roundIsOpen();
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, new SignInput(2L, FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-changed");

        TeamMember other = new TeamMember(UUID.randomUUID(), "Dr Other", "DOCTOR", true, 0);
        AdmissionFacts admission = new AdmissionFacts(ADMISSION, PATIENT, "IP/1", "ADMITTED", 2, MEMBERSHIP, Access.ALLOWED);
        contextIs(new SignContext(other, row("IPD_ROUND", "DRAFT", 3), new Encounter("IPD_ROUND", ADMISSION, null, admission,
                null, false, "on"), view(List.of(), List.of()), roundContext(ONE_ROW).document()));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("not-own-patient");

        contextIs(new SignContext(member, row("IPD_ROUND", "DRAFT", 3), new Encounter("IPD_ROUND", ADMISSION, null,
                admission, null, false, "readonly"), view(List.of(), List.of()), roundContext(ONE_ROW).document()));
        assertThatThrownBy(() -> service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none())))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("ipd-read-only");
        verify(ipdOrders, never()).checkRecorded(any(), any(), any(), any(), anyList());
    }

    @Test
    void aReplayOfAnAlreadySignedRoundDoesNotCheckOrAuditTheMedicinesAgain() throws Exception {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "SIGNED", 4)));
        SignContext signed = new SignContext(member, row("IPD_ROUND", "SIGNED", 4), roundContext(ONE_ROW).encounter(),
                view(List.of(), List.of()), roundContext(ONE_ROW).document());
        when(records.signContext(any(ClinicUser.class), eq(RECORD), anyBoolean())).thenReturn(signed);
        SignInput body = input(FinishChoices.none());
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, hashOf(body), false)));

        SignResult result = service.sign(DOCTOR, RECORD, KEY, body);

        assertThat(result.replayed()).isTrue();
        verify(ipdOrders, never()).checkRecorded(any(), any(), any(), any(), anyList());
        verify(ipdOrders, never()).check(any(), any(), any(), any(), anyList());
        verify(ipdOrders, never()).place(any(), any(), any(), any(), anyList());
        verify(audit, never()).record(eq("EMR_RECORD_SIGNED"), any(), any(), any(), any());
    }

    @Test
    void anOpdOrEmergencySignOpensNoPreCheckAndASignWithNoNewMedicineRowsChecksNothing() throws Exception {
        contextIs(opdContext(EmrRecordDocument.empty(MAPPER), view(List.of(), List.of())));
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(UUID.randomUUID(),
                LocalDate.of(2026, 11, 16), true, false, null));
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(any(), any(), any())).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(any(), any())).thenReturn(Optional.empty());
        service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));
        verify(records, never()).signContext(any(ClinicUser.class), eq(RECORD), eq(false));

        roundWithNoRows();
        service.sign(DOCTOR, RECORD, "key-0002-abcd", input(FinishChoices.none()));
        verify(ipdOrders, never()).checkRecorded(any(), any(), any(), any(), anyList());
        assertThat(transactions.open).isZero();
    }

    private void roundWithNoRows() throws Exception {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(null));
        when(roundPort.recordSignedRound(any(), any(), any())).thenReturn(EmrRoundPort.RoundOutcome.RECORDED);
    }

    @Test
    void theLongTermConditionsAreAddedToThePatientsListAndFrontDeskTextIsKept() {
        PatientClinicalUpdatePort.FactsChange change = signWithConditions("Asthma; Seen by a healer", "dm", "htn");

        assertThat(change.chronicText()).isEqualTo("Asthma; Seen by a healer; DM; HTN");
    }

    @Test
    void aConditionAlreadyOnThePatientsListIsNotAddedAgainAndAnUnchangedListWritesNothing() {
        assertThat(signWithConditions("htn; DM", "dm", "htn")).isNull();
        PatientClinicalUpdatePort.FactsChange change = signWithConditions("dm", "dm", "htn");
        assertThat(change.chronicText()).isEqualTo("dm; HTN");
    }

    private PatientClinicalUpdatePort.FactsChange signWithConditions(String patientConditions, String... chosen) {
        EmrRecordDocument document = documentWithFollowUp("D3");
        ObjectNode history = MAPPER.createObjectNode();
        var picked = history.putArray("past_illness");
        for (String key : chosen) {
            picked.add(key);
        }
        document.put("history", history, false, NOW, MEMBERSHIP);
        FieldDefinition illness = new FieldDefinition("past_illness", "Past illness", "MULTI_SELECT",
                EmrValueKind.MULTI_CHOICE, "", false, "FIELD", null, false,
                List.of(new EmrModel.FieldOption("dm", "DM", false), new EmrModel.FieldOption("htn", "HTN", false)),
                List.of(), null, null);
        LayoutSection section = new LayoutSection("history", "LIBRARY", "History", "COMMON", "", "ASSESSMENT", 1, true,
                false, false, false, false, List.of("DOCTOR"), false, null, List.of(illness));
        RecordView withLayout = new RecordView(RECORD, "OPD_VISIT", VISIT, null, "DRAFT", false, null, 3, null,
                new EmrViews.Snapshot("gm", "General", "gm", "STARTER", 1, "CLINIC_DEFAULT", NOW, List.of(section)),
                List.of(), List.of(), List.of(), true, null, false, null, List.of(), null, List.of(), List.of(), null, null,
                false, List.of(), null);
        return signWithFacts(document, new PatientClinical(PATIENT, "U1", "Pt", 30, "F", null, patientConditions),
                FinishChoices.none(), withLayout);
    }

    // ---- patient record write-back ----

    @Test
    void aChangedAllergyListIsWrittenBackAndAChangedRecordIsReportedAsConflictWithoutStoppingTheSign() {
        EmrRecordDocument document = documentWithFollowUp("D3");
        ObjectNode allergy = MAPPER.createObjectNode();
        allergy.putObject("drug_allergy").put("component", "EMR_LIST").put("kind", "ALLERGY")
                .putArray("items").addObject().put("text", "Penicillin").put("active", true);
        document.put("allergy", allergy, false, NOW, MEMBERSHIP);
        contextIs(opdContext(document, view(List.of(), List.of())));
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(UUID.randomUUID(),
                LocalDate.of(2026, 11, 16), true, false, null));
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(any(), any(), any())).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(any(), any())).thenReturn(Optional.empty());
        PatientClinical patient = new PatientClinical(PATIENT, "U1", "Pt", 30, "F", "Dust", null);
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(patient));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "DRAFT", null, null, null, null,
                null, null, null, null, false, patient.factsFingerprint())));
        when(patientUpdates.updateFacts(eq(TENANT), eq(USER), eq(PATIENT), eq(patient.factsFingerprint()), any()))
                .thenReturn(PatientClinicalUpdatePort.FactsOutcome.UPDATED);

        SignResult updated = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));
        assertThat(updated.outcome().patientRecord()).isEqualTo("UPDATED");
        ArgumentCaptor<PatientClinicalUpdatePort.FactsChange> change = ArgumentCaptor
                .forClass(PatientClinicalUpdatePort.FactsChange.class);
        verify(patientUpdates).updateFacts(any(), any(), any(), any(), change.capture());
        // the removed allergy "Dust" stays unless the doctor ticks the removal
        assertThat(change.getValue().allergyText()).isEqualTo("Penicillin; Dust");

        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "DRAFT", null, null, null, null,
                null, null, null, null, false, "f".repeat(64))));
        SignResult conflict = service.sign(DOCTOR, RECORD, "key-0002-abcd", input(FinishChoices.none()));
        assertThat(conflict.outcome().patientRecord()).isEqualTo("CONFLICT");
        verify(finish, org.mockito.Mockito.times(2)).markSigned(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ---- prints, the profile block (opd.yaml 0.6.1) ----

    private void stubOpdSign() {
        when(followUps.setForVisit(any(), any(), any(), any(), any())).thenReturn(new VisitFollowUpResult(UUID.randomUUID(),
                LocalDate.of(2026, 11, 16), true, false, null));
        when(appointments.bookFirstFree(any(), any(), any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(records.syncBillLines(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(records.billPreview(any(), any(), any())).thenReturn(new BillSummary(0, 0, List.of()));
        when(visits.finishConsultation(any(), any(), any())).thenReturn(new FinishMove("MOVED", "BILLING"));
        when(courses.sittingByVisit(any(), any())).thenReturn(Optional.empty());
    }

    private SignContext dentalContext() {
        return new SignContext(member, rowWithStarter("OPD_VISIT", "DRAFT", 3, "dental"),
                new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"), view(List.of(), List.of()),
                documentWithFollowUp("D3"));
    }

    @Test
    void theFinishCheckNamesTheProfileBlockAndTheDocumentsThatWillPrint() {
        contextIs(dentalContext());
        stubOpdSign();
        ProfileEffects.Onco block = new ProfileEffects.Onco(2, "GIVE_AS_PLANNED", "SENT", 3, "NEXT_CHEMO",
                LocalDate.of(2026, 12, 4), null, null, false);
        when(contributor.preview(any())).thenReturn(block);

        FinishModels.FinishCheck check = service.check(DOCTOR, RECORD, FinishChoices.none());

        assertThat(check.effects().profile()).isSameAs(block);
        assertThat(check.effects().prints()).containsExactly(FinishPrint.PRESCRIPTION, FinishPrint.CYCLE_RECORD,
                FinishPrint.CHEMO_CALENDAR);
    }

    @Test
    void aFinishCheckOfARoundOnADischargedAdmissionAnswers409EncounterClosedNotIpdsNotAdmitted() throws Exception {
        // B-2: ipd's 409 not-admitted from the order check is mapped like the sign path (opd.yaml 0.6.3, DEC-051 D2)
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.check(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList())).thenThrow(
                new com.sahaj.platform.api.ConflictException("not-admitted", "Patient is not admitted", "Not admitted."));

        assertThatThrownBy(() -> service.check(DOCTOR, RECORD, FinishChoices.none()))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("encounter-closed");
                });
    }

    @Test
    void aFinishCheckLetsAnyOtherConflictFromTheOrderCheckThrough() throws Exception {
        contextIs(roundContext(ONE_ROW));
        when(ipdOrders.check(eq(TENANT), eq(USER), eq("DOCTOR"), eq(ADMISSION), anyList())).thenThrow(
                new com.sahaj.platform.api.ConflictException("ipd-read-only", "Read-only", "Read-only."));

        assertThatThrownBy(() -> service.check(DOCTOR, RECORD, FinishChoices.none()))
                .isInstanceOfSatisfying(com.sahaj.platform.api.ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("ipd-read-only"));
    }

    @Test
    void aRecordWithoutAProfileHasANullBlockAndOnlyThePrescriptionToPrint() {
        contextIs(opdContext(documentWithFollowUp("D3"), view(List.of(), List.of())));
        stubOpdSign();

        FinishModels.FinishCheck check = service.check(DOCTOR, RECORD, FinishChoices.none());

        assertThat(check.effects().profile()).isNull();
        assertThat(check.effects().prints()).containsExactly(FinishPrint.PRESCRIPTION);
    }

    @Test
    void theEmergencyPrescriptionPrintsOnlyForADischarge() {
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        FinishChoices discharge = new FinishChoices(new ErOutcome("DISCHARGE", null, null, null, null), null, null, null,
                null, null);
        FinishChoices refer = new FinishChoices(new ErOutcome("REFER_OUT", "District hospital", null, null, null), null, null,
                null, null, null);

        assertThat(service.check(DOCTOR, RECORD, discharge).effects().prints()).containsExactly(FinishPrint.PRESCRIPTION);
        assertThat(service.check(DOCTOR, RECORD, refer).effects().prints()).isEmpty();
        assertThat(service.check(DOCTOR, RECORD, FinishChoices.none()).effects().prints()).isEmpty();
    }

    @Test
    void thePrintRulesAreInPrintOrderWithoutDuplicatesAndNothingForARound() {
        RecordRow opd = row("OPD_VISIT", "DRAFT", 3);
        RecordRow round = row("IPD_ROUND", "DRAFT", 3);
        RecordRow er = row("ER_ASSESSMENT", "DRAFT", 3);

        assertThat(EmrSignService.printsOf(opd, null, List.of(FinishPrint.PREOP_SHEET, FinishPrint.ADMISSION_LETTER,
                FinishPrint.ADMISSION_LETTER))).containsExactly(FinishPrint.PRESCRIPTION, FinishPrint.ADMISSION_LETTER,
                FinishPrint.PREOP_SHEET);
        assertThat(EmrSignService.printsOf(round, null, List.of(FinishPrint.CYCLE_RECORD))).isEmpty();
        assertThat(EmrSignService.printsOf(er, "DISCHARGE", List.of())).containsExactly(FinishPrint.PRESCRIPTION);
        assertThat(EmrSignService.printsOf(er, "ADMIT", List.of())).isEmpty();
        assertThat(EmrSignService.printsOf(er, "SEND_TO_OPD", List.of())).isEmpty();
    }

    @Test
    void theSignAnswerHasThePrintsButTheStoredResultHasNone() {
        contextIs(dentalContext());
        stubOpdSign();
        when(contributor.prints(any())).thenReturn(List.of(FinishPrint.CYCLE_RECORD, FinishPrint.CHEMO_CALENDAR));

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(FinishChoices.none()));

        assertThat(result.outcome().prints()).containsExactly(FinishPrint.PRESCRIPTION, FinishPrint.CYCLE_RECORD,
                FinishPrint.CHEMO_CALENDAR);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(finish).markSigned(any(), any(), any(), any(), anyString(), anyString(), stored.capture());
        assertThat(stored.getValue()).doesNotContain("prints");
    }

    @Test
    void aReplayComputesThePrintsAgainFromTheSignedRecord() {
        contextIs(opdContext(EmrRecordDocument.empty(MAPPER), view(List.of(), List.of())));
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member,
                row("OPD_VISIT", "SIGNED", 4), new Encounter("OPD_VISIT", VISIT, visit(), null, null, false, "on"),
                view(List.of(), List.of()), EmrRecordDocument.empty(MAPPER)));
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", "SIGNED", 4)));
        SignInput body = input(FinishChoices.none());
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", KEY, hashOf(body), false)));

        SignResult result = service.sign(DOCTOR, RECORD, KEY, body);

        assertThat(result.replayed()).isTrue();
        assertThat(result.outcome().prints()).containsExactly(FinishPrint.PRESCRIPTION);
    }

    @Test
    void anEmergencyDischargePrintsThePrescriptionAndARoundPrintsNothing() throws Exception {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        SignResult discharged = service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("DISCHARGE", null,
                null, null, null), null, null, null, null, null)));
        assertThat(discharged.outcome().prints()).containsExactly(FinishPrint.PRESCRIPTION);

        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("IPD_ROUND", "DRAFT", 3)));
        contextIs(roundContext(null));
        when(roundPort.recordSignedRound(any(), any(), any())).thenReturn(EmrRoundPort.RoundOutcome.RECORDED);
        SignResult round = service.sign(DOCTOR, RECORD, "key-0003-abcd", input(FinishChoices.none()));
        assertThat(round.outcome().prints()).isEmpty();
    }

    // ---- refer out (DEC-048 H22) ----

    @Test
    void aReferOutWritesTheReferralLineWithoutAdvisingAdmissionAndClosesTheCaseAsReferred() {
        when(recordRows.findById(TENANT, RECORD)).thenReturn(Optional.of(row("ER_ASSESSMENT", "DRAFT", 3)));
        when(emergencyPort.lockCase(TENANT, CASE)).thenReturn(new EmrEmergencyPort.CaseLock(CASE, "SEEN", true, 1));
        contextIs(erContext());

        SignResult result = service.sign(DOCTOR, RECORD, KEY, input(new FinishChoices(new ErOutcome("REFER_OUT",
                "District hospital", "For CT head", null, null), null, null, null, null, null)));

        verify(recordRows).setReferralLine(TENANT, RECORD, 3L, "Referred to District hospital. For CT head", USER);
        verify(recordRows, never()).adviseAdmission(any(), any(), anyLong(), any(), any());
        verify(emergencyPort).close(TENANT, USER, CASE, EmrEmergencyPort.CloseKind.REFER, null, null);
        assertThat(result.outcome().emergency().outcome()).isEqualTo("REFER_OUT");
        assertThat(result.outcome().prints()).isEmpty();
    }

    // ---- patient record: "None" and the long-term conditions ----

    private PatientClinicalUpdatePort.FactsChange signWithAllergyItems(String patientAllergy, FinishChoices choices,
                                                                       String... typed) {
        EmrRecordDocument document = documentWithFollowUp("D3");
        ObjectNode allergy = MAPPER.createObjectNode();
        var items = allergy.putArray("drug_allergy");
        for (String text : typed) {
            items.addObject().put("itemKey", (String) null).put("text", text).put("newToList", true);
        }
        document.put("allergy", allergy, false, NOW, MEMBERSHIP);
        return signWithFacts(document, new PatientClinical(PATIENT, "U1", "Pt", 30, "F", patientAllergy, null), choices,
                view(List.of(), List.of()));
    }

    private PatientClinicalUpdatePort.FactsChange signWithFacts(EmrRecordDocument document, PatientClinical patient,
                                                                FinishChoices choices, RecordView view) {
        contextIs(opdContext(document, view));
        stubOpdSign();
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(patient));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "DRAFT", null, null, null, null,
                null, null, null, null, false, patient.factsFingerprint())));
        when(patientUpdates.updateFacts(eq(TENANT), eq(USER), eq(PATIENT), any(), any()))
                .thenReturn(PatientClinicalUpdatePort.FactsOutcome.UPDATED);
        service.sign(DOCTOR, RECORD, KEY, input(choices));
        ArgumentCaptor<PatientClinicalUpdatePort.FactsChange> change = ArgumentCaptor
                .forClass(PatientClinicalUpdatePort.FactsChange.class);
        try {
            verify(patientUpdates).updateFacts(any(), any(), any(), any(), change.capture());
        } catch (AssertionError notCalled) {
            return null;
        }
        return change.getValue();
    }

    @Test
    void anExplicitNoneIsTheOnlyWayTheTextNoKnownAllergyIsWritten() {
        PatientClinicalUpdatePort.FactsChange change = signWithAllergyItems(null, FinishChoices.none(), "None");

        assertThat(change).isNotNull();
        assertThat(change.allergyText()).isEqualTo("No known allergy");
        assertThat(EmrSignService.isNoneToken("  NKDA. ")).isTrue();
        assertThat(EmrSignService.isNoneToken("No known drug allergies")).isTrue();
        assertThat(EmrSignService.isNoneToken("none known")).isTrue();
        assertThat(EmrSignService.isNoneToken("No Known Allergies.")).isTrue();
        assertThat(EmrSignService.isNoneToken("Penicillin; none known otherwise")).isFalse();
        assertThat(EmrSignService.isNoneToken("Penicillin")).isFalse();
        assertThat(EmrSignService.isNoneToken(null)).isFalse();
    }

    @Test
    void noneBesideARealAllergyIsIgnoredAndAnAllergyAfterNoneReplacesIt() {
        PatientClinicalUpdatePort.FactsChange mixed = signWithAllergyItems(null, FinishChoices.none(), "None", "Penicillin");
        assertThat(mixed.allergyText()).isEqualTo("Penicillin");
    }

    @Test
    void noneIsNotWrittenOverAnAllergyThatStaysAndIsWrittenWhenTheRemovalIsConfirmed() {
        // the patient has "Dust"; the doctor says None but did not tick "Remove from the patient record"
        assertThat(signWithAllergyItems("Dust", FinishChoices.none(), "None")).isNull();

        FinishChoices confirmed = new FinishChoices(null, null, null, null, true, null);
        PatientClinicalUpdatePort.FactsChange change = signWithAllergyItems("Dust", confirmed, "None");
        assertThat(change.allergyText()).isEqualTo("No known allergy");
    }

    @Test
    void anAllergyAddedAfterNoKnownAllergyDoesNotNeedARemovalTick() {
        PatientClinicalUpdatePort.FactsChange change = signWithAllergyItems("No known allergy", FinishChoices.none(),
                "Penicillin");

        assertThat(change.allergyText()).isEqualTo("Penicillin");
    }

    @Test
    void theLongTermConditionsAreWrittenAsTheLabelsOfTheChosenOptionsFromTheRecordsLayout() {
        EmrRecordDocument document = documentWithFollowUp("D3");
        ObjectNode history = MAPPER.createObjectNode();
        history.putArray("past_illness").add("dm").add("htn");
        document.put("history", history, false, NOW, MEMBERSHIP);
        FieldDefinition illness = new FieldDefinition("past_illness", "Past illness", "MULTI_SELECT",
                EmrValueKind.MULTI_CHOICE, "", false, "FIELD", null, false,
                List.of(new EmrModel.FieldOption("dm", "DM", false), new EmrModel.FieldOption("htn", "HTN", false)),
                List.of(), null, null);
        LayoutSection section = new LayoutSection("history", "LIBRARY", "History", "COMMON", "", "ASSESSMENT", 1, true,
                false, false, false, false, List.of("DOCTOR"), false, null, List.of(illness));
        RecordView withLayout = new RecordView(RECORD, "OPD_VISIT", VISIT, null, "DRAFT", false, null, 3, null,
                new EmrViews.Snapshot("gm", "General", "gm", "STARTER", 1, "CLINIC_DEFAULT", NOW, List.of(section)),
                List.of(), List.of(), List.of(), true, null, false, null, List.of(), null, List.of(), List.of(), null, null,
                false, List.of(), null);

        PatientClinicalUpdatePort.FactsChange change = signWithFacts(document, new PatientClinical(PATIENT, "U1", "Pt", 30,
                "F", null, null), FinishChoices.none(), withLayout);

        assertThat(change.chronicText()).isEqualTo("DM; HTN");
    }

    private String hashOf(SignInput body) {
        try {
            String text = body.revision() + "|" + MAPPER.writeValueAsString(body.choices());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
