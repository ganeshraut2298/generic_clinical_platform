package com.sahaj.opd.application.emr.finish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrEmergencyPort.CloseKind;
import com.sahaj.opd.api.EmrIpdOrderPort;
import com.sahaj.opd.api.EmrIpdOrderPort.LineCheck;
import com.sahaj.opd.api.EmrIpdOrderPort.OrderLine;
import com.sahaj.opd.api.EmrIpdOrderPort.PlaceResult;
import com.sahaj.opd.api.EmrRoundPort;
import com.sahaj.opd.api.events.EmrRecordSigned;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentService.BookedSlot;
import com.sahaj.opd.application.FollowUpService;
import com.sahaj.opd.application.FollowUpService.VisitFollowUpResult;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.VisitService.FinishMove;
import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrAllergyCheck;
import com.sahaj.opd.application.emr.EmrComponentBinding;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.BillSummary;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.EmrUsageRecorder;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.EmrViews.StillNeededItem;
import com.sahaj.opd.application.emr.MasterItemRef;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestInput;
import com.sahaj.opd.application.emr.finish.FinishModels.CourseOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.EmergencyEffect;
import com.sahaj.opd.application.emr.finish.FinishModels.EmergencyOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.Effects;
import com.sahaj.opd.application.emr.finish.FinishModels.ErOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBillLine;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBlocker;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishCheck;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishChoices;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishOrderCheck;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import com.sahaj.opd.application.emr.finish.FinishModels.FollowUpEffect;
import com.sahaj.opd.application.emr.finish.FinishModels.FollowUpOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import com.sahaj.opd.application.emr.finish.FinishModels.IpdOrderNotPlacedRow;
import com.sahaj.opd.application.emr.finish.FinishModels.IpdOrderPlaced;
import com.sahaj.opd.application.emr.finish.FinishModels.IpdOrderReason;
import com.sahaj.opd.application.emr.finish.FinishModels.PatientRecordEffect;
import com.sahaj.opd.application.emr.finish.FinishModels.RoundOutcomeView;
import com.sahaj.opd.application.emr.finish.FinishModels.Shares;
import com.sahaj.opd.application.emr.finish.FinishModels.SignInput;
import com.sahaj.opd.application.emr.finish.FinishModels.SignOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.SignResult;
import com.sahaj.opd.application.emr.finish.FinishModels.StageEffect;
import com.sahaj.opd.application.emr.finish.FinishModels.StageOutcome;
import com.sahaj.opd.application.emr.specialty.ProfileEffects;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor.FinishContext;
import com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor.ProfileResult;
import com.sahaj.opd.domain.emr.specialty.DentalRules;
import com.sahaj.patient.api.AllergyStatus;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientClinicalUpdatePort;
import com.sahaj.patient.api.PatientClinicalUpdatePort.FactsChange;
import com.sahaj.patient.api.PatientClinicalUpdatePort.FactsOutcome;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The finish of a Doctor EMR record: "Check before completing" and the sign (SAH-51, SAH-67, SAH-68; ADR-025 D1, D2, D6 to D9,
 * D13). The sign is ONE database transaction: every other module is reached through a port that runs in this transaction, so
 * a refusal anywhere rolls back everything (the record stays a draft; no bill line, follow-up, appointment, order, round mark
 * or case close survives). Lock order, fixed: the Emergency case (clinic IPD lock, then the case row) before the record row,
 * because an ipd admission from the case takes the IPD lock first and then reaches opd. Idempotent: the Idempotency-Key and a
 * hash of the body are stored with the record; the same key and body replay the first result, and every side effect is also
 * idempotent by its own key.
 *
 * <p>This is the only class that calls {@link EmrUsageRecorder} and the {@link SpecialtyFinishContributor} beans (a
 * convention test checks it). It writes ids, counts and fixed words to the audit trail and nothing to a log: no patient name,
 * clinical text, referral text or vital value anywhere.
 */
@Service
public class EmrSignService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final String ENTITY = "EMR_RECORD";
    private static final String FOLLOW_UP_REASON = "Review after treatment";
    private static final int FOLLOW_UP_SEARCH_DAYS = 7;
    private static final int MAX_FACTS = 400;
    private static final Set<String> ER_KINDS = Set.of("DISCHARGE", "ADMIT", "REFER_OUT", "SEND_TO_OPD");
    /** The only text the visit writes for "no allergy" (OQ-45-23). */
    static final String NO_KNOWN_ALLERGY = "No known allergy";

    private final EmrRecordService records;
    private final EmrRecordRepository recordRows;
    private final EmrFinishRepository finish;
    private final VisitService visits;
    private final FollowUpService followUps;
    private final AppointmentService appointments;
    private final TreatmentCourseRepository courses;
    private final ObjectProvider<EmrIpdOrderPort> ipdOrders;
    private final ObjectProvider<EmrRoundPort> rounds;
    private final ObjectProvider<EmrEmergencyPort> emergency;
    private final PatientClinicalPort patients;
    private final PatientClinicalUpdatePort patientUpdates;
    private final EmrUsageRecorder usage;
    private final EmrAdmissionService admissions;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Map<String, SpecialtyFinishContributor> contributors = new HashMap<>();

    public EmrSignService(EmrRecordService records, EmrRecordRepository recordRows, EmrFinishRepository finish,
                          VisitService visits, FollowUpService followUps, AppointmentService appointments,
                          TreatmentCourseRepository courses, ObjectProvider<EmrIpdOrderPort> ipdOrders,
                          ObjectProvider<EmrRoundPort> rounds, ObjectProvider<EmrEmergencyPort> emergency,
                          PatientClinicalPort patients, PatientClinicalUpdatePort patientUpdates,
                          EmrUsageRecorder usage, EmrAdmissionService admissions, TeamMemberPort teamMembers,
                          AuditLog auditLog, ObjectMapper mapper, Clock clock, ApplicationEventPublisher events,
                          PlatformTransactionManager transactionManager,
                          List<SpecialtyFinishContributor> profileContributors) {
        this.records = records;
        this.recordRows = recordRows;
        this.finish = finish;
        this.visits = visits;
        this.followUps = followUps;
        this.appointments = appointments;
        this.courses = courses;
        this.ipdOrders = ipdOrders;
        this.rounds = rounds;
        this.emergency = emergency;
        this.patients = patients;
        this.patientUpdates = patientUpdates;
        this.usage = usage;
        this.admissions = admissions;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.clock = clock;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        for (SpecialtyFinishContributor contributor : profileContributors) {
            contributors.put(contributor.profile(), contributor);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Check before completing
    // ------------------------------------------------------------------------------------------------------------

    /**
     * What is missing and what the sign would do, computed now (ADR-025 D2). Writes nothing for an OPD or Emergency record. For
     * an IPD round it runs SAH-31's own check on each new order row, which writes its own deny event and audit row (hard stop)
     * or record-access row (warning); nothing is ordered.
     */
    @Transactional
    public FinishCheck check(ClinicUser user, UUID recordId, FinishChoices given) {
        FinishChoices choices = given == null ? FinishChoices.none() : given;
        SignContext ctx = records.signContext(user, recordId, false);
        RecordRow row = ctx.row();
        RecordState state = finish.state(user.tenantId(), recordId).orElseThrow(EmrProblemException::recordNotFound);
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.alreadySignedBy(doctorName(user.tenantId(), state.signedByMembershipId()));
        }
        validateChoices(ctx, choices);
        Assessment assessment = assess(user, ctx, state, choices, true);
        return new FinishCheck(assessment.blockers.isEmpty(), List.copyOf(assessment.blockers), row.encounterType(),
                assessment.effects, List.copyOf(assessment.info), Shares.unavailable());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sign
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The sign. Not transactional itself (DEC-048 H37): an IPD round's medicine pre-check runs first in short committed
     * transactions of its own, and only then does the one sign transaction open, so a sign never holds a pooled connection
     * while it waits for a second one. See {@link #signInTransaction}.
     */
    public SignResult sign(ClinicUser user, UUID recordId, String key, SignInput input) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("Idempotency-Key",
                    "Send a key of 8 to 64 letters, digits, dashes or underscores.")));
        }
        if (input == null || input.revision() == null || input.revision() < 0 || input.choices() == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage(input == null || input.revision() == null
                    || input.revision() < 0 ? "revision" : "choices", "Reload the page and try again.")));
        }
        FinishChoices given = input.choices();
        if (given.share() != null && Boolean.TRUE.equals(given.share().whatsappPrescription())) {
            throw EmrProblemException.needsWhatsappService();
        }
        recordIpdOrderStops(user, recordId, input);
        return transactions.execute(status -> signInTransaction(user, recordId, key, input));
    }

    /**
     * DEC-048 H31 and H37: a SAH-31 hard stop met at sign time must leave its deny event and IPD_MED_ORDER_BLOCKED audit row
     * although the sign is then refused. The rows are checked here, before the sign transaction opens: the plan is read in a
     * short transaction that ends before the order port is called, and the port commits each line's audit in a transaction of
     * its own. Nothing is checked for a caller who would be refused (access, a stale revision, a replay, a blocker): the
     * sign transaction then gives the usual answer.
     */
    private void recordIpdOrderStops(ClinicUser user, UUID recordId, SignInput input) {
        IpdPreCheck plan;
        try {
            plan = transactions.execute(status -> planIpdPreCheck(user, recordId, input));
        } catch (AccessDeniedException | NotFoundException | InvalidFieldsException | EmrProblemException refused) {
            return;
        }
        if (plan == null) {
            return;
        }
        List<LineCheck> stops = plan.port.checkRecorded(user.tenantId(), user.userId(), plan.role, plan.admissionId,
                plan.lines).stream().filter(c -> "BLOCKED".equals(c.outcome())).toList();
        if (!stops.isEmpty()) {
            throw EmrProblemException.ipdOrderNotPlaced(stops.stream().map(EmrSignService::refusalRow).toList());
        }
    }

    /** What the pre-check runs on. */
    private static final class IpdPreCheck {
        EmrIpdOrderPort port;
        String role;
        UUID admissionId;
        List<OrderLine> lines;
    }

    /** The lines of an open IPD round whose sign could reach the placing of orders; null when it cannot. */
    private IpdPreCheck planIpdPreCheck(ClinicUser user, UUID recordId, SignInput input) {
        UUID tenantId = user.tenantId();
        FinishChoices choices = input.choices();
        RecordRow pre = recordRows.findById(tenantId, recordId).orElse(null);
        EmrIpdOrderPort port = ipdOrders.getIfAvailable();
        if (pre == null || port == null || !"IPD_ROUND".equals(pre.encounterType()) || !"DRAFT".equals(pre.status())) {
            return null;
        }
        // the caller's access first: a refusal (403, 404) leaves through the catch of the caller, nothing is checked
        SignContext ctx = records.signContext(user, recordId, false);
        RecordRow row = ctx.row();
        TeamMember member = ctx.member();
        RecordState state = finish.state(tenantId, recordId).orElse(null);
        if (state == null || !"DRAFT".equals(row.status()) || input.revision() != row.revision()) {
            return null;
        }
        if (state.holdActive() && state.holdMembershipId() != null && !state.holdMembershipId().equals(member.membershipId())) {
            return null;
        }
        if (ctx.encounter().ipdBased() && "readonly".equals(ctx.encounter().ipdState())) {
            return null;
        }
        if (!"OWNER".equals(member.role())
                && !member.membershipId().equals(ctx.encounter().admission().admittingDoctorMembershipId())) {
            return null;
        }
        validateChoices(ctx, choices);
        if (!assess(user, ctx, state, choices, false).blockers.isEmpty()) {
            return null;
        }
        List<IpdRow> toPlace = ipdOrderRows(ctx.document()).stream().filter(r -> r.orderId == null).toList();
        if (toPlace.isEmpty()) {
            return null;
        }
        Map<UUID, IpdOrderReason> reasons = reasonsByRow(choices);
        List<OrderLine> lines = new ArrayList<>();
        for (IpdRow r : toPlace) {
            IpdOrderReason reason = reasons.get(r.id);
            lines.add(new OrderLine(r.id, r.drugKey, r.frequencyKey, reason == null ? null : reason.overrideReasonKey(),
                    reason == null ? null : reason.checkToken()));
        }
        IpdPreCheck plan = new IpdPreCheck();
        plan.port = port;
        plan.role = member.role();
        plan.admissionId = row.ipdAdmissionId();
        plan.lines = List.copyOf(lines);
        return plan;
    }

    /** The sign itself: ONE transaction, opened after the pre-check above has committed. */
    private SignResult signInTransaction(ClinicUser user, UUID recordId, String key, SignInput input) {
        FinishChoices choices = input.choices();
        UUID tenantId = user.tenantId();
        String hash = requestHash(input);
        // Step 1 (Emergency only): the case before the record, the lock order of ADR-025 D1. The caller's access and the
        // IPD scope are proven first (security L4), so a refused caller never waits on the case locks.
        RecordRow pre = recordRows.findById(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        EmrEmergencyPort emergencyPort = emergency.getIfAvailable();
        if ("ER_ASSESSMENT".equals(pre.encounterType()) && "DRAFT".equals(pre.status()) && emergencyPort != null) {
            records.authorize(user, recordId);
            if (!emergencyPort.lockCase(tenantId, pre.erCaseId()).open()
                    && !signedMeanwhile(tenantId, recordId)) {
                throw EmrProblemException.caseClosed();
            }
        }
        // Step 2: the record row, then (OPD) the visit row, the order of ADR-025 D1 steps 2 and 5: a card move locks the visit
        // and then writes the follow-up, so the sign takes the visit before any follow-up or bill write.
        SignContext ctx = records.signContext(user, recordId, true);
        RecordRow row = ctx.row();
        if ("OPD_VISIT".equals(row.encounterType()) && "DRAFT".equals(row.status())) {
            visits.lockForSign(tenantId, row.opdVisitId());
        }
        RecordState state = finish.state(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        if ("SIGNED".equals(row.status())) {
            if (key.equals(state.signKey())) {
                if (!hash.equals(state.signHash())) {
                    throw EmrProblemException.idempotencyConflict();
                }
                SignOutcome first = readOutcome(state.signResult());
                return new SignResult(true, first == null ? null : first.withPrints(printsAfter(row,
                        context(user, ctx, FinishChoices.none(), key), first)), records.viewOf(user, recordId));
            }
            throw EmrProblemException.alreadySignedBy(doctorName(tenantId, state.signedByMembershipId()));
        }
        if (input.revision() != row.revision()) {
            throw EmrProblemException.emrRecordChanged();
        }
        TeamMember member = ctx.member();
        if (state.holdActive() && state.holdMembershipId() != null && !state.holdMembershipId().equals(member.membershipId())) {
            throw EmrProblemException.emrRecordHeld(doctorName(tenantId, state.holdMembershipId()));
        }
        // IPD Read-only (security L2): a round or an Emergency assessment is not signed while IPD is Read-only
        if (ctx.encounter().ipdBased() && "readonly".equals(ctx.encounter().ipdState())) {
            throw EmrProblemException.ipdReadOnly();
        }
        // Step 3: access. The caller's own patient or the IPD scope is proven by the context; an IPD round is signed by the
        // admitting doctor or an owner (ADR-025 H8).
        if ("IPD_ROUND".equals(row.encounterType()) && !"OWNER".equals(member.role())
                && !member.membershipId().equals(ctx.encounter().admission().admittingDoctorMembershipId())) {
            throw EmrProblemException.notOwnPatient();
        }
        validateChoices(ctx, choices);
        // Step 4: readiness, recomputed by the server.
        Assessment assessment = assess(user, ctx, state, choices, false);
        for (FinishBlocker blocker : assessment.blockers) {
            if ("ENCOUNTER_CLOSED".equals(blocker.key())) {
                throw EmrProblemException.encounterClosed();
            }
            if ("CASE_CLOSED".equals(blocker.key())) {
                throw EmrProblemException.caseClosed();
            }
        }
        if (!assessment.blockers.isEmpty()) {
            throw EmrProblemException.emrNotReady(List.copyOf(assessment.blockers));
        }
        // Steps 5 and 6: the encounter's own work, then the profile, then the patient record.
        Instant now = clock.instant();
        Work work = new Work();
        work.now = now;
        switch (row.encounterType()) {
            case "OPD_VISIT":
                signOpd(user, ctx, choices, key, work);
                break;
            case "IPD_ROUND":
                signRound(user, ctx, choices, work);
                break;
            default:
                signEmergency(user, ctx, choices, work);
                break;
        }
        work.patientRecord = writeBack(user, ctx, state, choices);
        // Step 7: the ranking counts, the only call site.
        usage.recordSignedVisit(tenantId, member.membershipId(), now, masterItems(ctx.document()));
        // Step 8: the signature and the stored result, in one update that also clears the hold.
        SignOutcome outcome = work.outcome();
        if (!finish.markSigned(tenantId, recordId, user.userId(), member.membershipId(), key, hash, writeJson(outcome))) {
            throw EmrProblemException.emrRecordChanged();
        }
        // Step 9 is part of the OPD work (the linked course sitting). Step 10: one audit entry, then the event.
        auditSigned(row, ctx, work, outcome);
        RecordRow signed = recordRows.findById(tenantId, recordId).orElseThrow();
        events.publishEvent(new EmrRecordSigned(tenantId, recordId, row.encounterType(), row.encounterId(),
                signed.patientId(), member.membershipId(), row.departmentKey(),
                signed.signedAt() == null ? now : signed.signedAt()));
        return new SignResult(false, outcome.withPrints(printsAfter(row, context(user, ctx, choices, key), outcome)),
                records.viewOf(user, recordId));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Work done by the encounter type
    // ------------------------------------------------------------------------------------------------------------

    /** What the encounter's work collected, for the outcome and the audit entry. */
    private static final class Work {
        Instant now;
        StageOutcome stage;
        FollowUpOutcome followUp;
        int billLines;
        long billTotalPaise;
        List<IpdOrderPlaced> ipdOrders = List.of();
        RoundOutcomeView round;
        EmergencyOutcome emergency;
        String patientRecord = "NOT_CHANGED";
        CourseOutcome course;
        UUID admissionRequestId;
        /** The appointment the finish booked or attached (ADR-025 D18); null when there is none. */
        UUID appointmentId;

        SignOutcome outcome() {
            return new SignOutcome(stage, followUp, billLines, billTotalPaise, ipdOrders, round, emergency,
                    patientRecord, course);
        }
    }

    private void signOpd(ClinicUser user, SignContext ctx, FinishChoices choices, String key, Work work) {
        UUID tenantId = user.tenantId();
        VisitRow visit = ctx.encounter().visit();
        FinishContext fc = context(user, ctx, choices, key);
        // the profile first: the dental next sitting decides the follow-up date and the only appointment (H7)
        ProfileResult profile = contributorOf(ctx.row()).map(c -> c.apply(fc)).orElse(ProfileResult.none());
        // the follow-up and the appointment
        String interval = followUpInterval(ctx.document());
        LocalDate due = profile.followUpDate() != null ? profile.followUpDate()
                : FollowUpService.dueDateFor(fc.visitDay(), interval);
        if (due != null) {
            work.followUp = setFollowUp(user, visit, due, interval, profile, work);
        }
        // the visit bill's EMR lines made equal to the ticked services; the dental plan lines were added by the profile
        BillSummary bill = records.syncBillLines(user, ctx.encounter(), ctx.row());
        work.billLines = bill.lines() + profile.billLines();
        work.billTotalPaise = bill.totalPaise() + profile.billTotalPaise();
        // the workflow move
        FinishMove move = visits.finishConsultation(tenantId, user.userId(), visit.id());
        work.stage = new StageOutcome(move.outcome(), move.to());
        // step 9: the linked treatment course sitting is done
        Optional<SittingRow> sitting = courses.sittingByVisit(tenantId, visit.id());
        UUID doneId = null;
        // ADR-026 D4: ONE charge. done_at is set only when it is empty ("Mark done" may have come first, then this is a
        // no-op), and a REMOVED sitting is skipped (removed and done exclude each other in the table).
        if (sitting.isPresent() && !sitting.get().removed()) {
            if (sitting.get().doneAt() == null) {
                courses.markDone(tenantId, sitting.get().id(), work.now, user.userId());
                courses.bumpRevision(tenantId, sitting.get().courseId(), user.userId());
            }
            doneId = sitting.get().id();
        }
        CourseOutcome next = profile.course();
        if (doneId != null || next != null) {
            work.course = new CourseOutcome(doneId, next == null ? null : next.nextSittingId(),
                    next == null ? null : next.nextAppointmentDate(), next == null ? null : next.nextAppointmentTime(),
                    next != null && next.packageComplete());
        }
    }

    private FollowUpOutcome setFollowUp(ClinicUser user, VisitRow visit, LocalDate due, String interval,
                                        ProfileResult profile, Work work) {
        UUID tenantId = user.tenantId();
        VisitFollowUpResult result = followUps.setForVisit(tenantId, user.userId(), visit, due, FOLLOW_UP_REASON);
        if (result.returned()) {
            return new FollowUpOutcome(result.id(), result.dueDate(), null, null, false);
        }
        UUID appointmentId = profile.appointmentId() != null ? profile.appointmentId() : result.appointmentId();
        LocalDate apptDate = null;
        String apptTime = null;
        if (profile.course() != null && profile.appointmentId() != null) {
            apptDate = profile.course().nextAppointmentDate();
            apptTime = profile.course().nextAppointmentTime();
        }
        if (appointmentId == null) {
            Optional<BookedSlot> booked = appointments.bookFirstFree(tenantId, user.userId(), visit.patientId(),
                    visit.doctorMembershipId(), due, FOLLOW_UP_SEARCH_DAYS, "FOLLOW_UP", "EMR");
            if (booked.isPresent()) {
                appointmentId = booked.get().appointmentId();
                apptDate = booked.get().date();
                apptTime = booked.get().time().toString();
            }
        }
        if (appointmentId != null && !appointmentId.equals(result.appointmentId())) {
            followUps.attachAppointment(tenantId, user.userId(), result.id(), appointmentId, interval);
        }
        work.appointmentId = appointmentId;
        return new FollowUpOutcome(result.id(), due, apptDate, apptTime, appointmentId == null);
    }

    private void signRound(ClinicUser user, SignContext ctx, FinishChoices choices, Work work) {
        UUID tenantId = user.tenantId();
        RecordRow row = ctx.row();
        List<IpdRow> orderRows = ipdOrderRows(ctx.document());
        List<IpdRow> toPlace = orderRows.stream().filter(r -> r.orderId == null).toList();
        EmrIpdOrderPort port = ipdOrders.getIfAvailable();
        List<IpdOrderPlaced> placedRows = new ArrayList<>();
        if (!toPlace.isEmpty()) {
            if (port == null) {
                throw EmrProblemException.notApplicable();
            }
            Map<UUID, IpdOrderReason> reasons = reasonsByRow(choices);
            List<OrderLine> lines = new ArrayList<>();
            for (IpdRow r : toPlace) {
                IpdOrderReason reason = reasons.get(r.id);
                lines.add(new OrderLine(r.id, r.drugKey, r.frequencyKey, reason == null ? null : reason.overrideReasonKey(),
                        reason == null ? null : reason.checkToken()));
            }
            // DEC-048 H31, H37: the hard-stop pre-check already ran and committed before this transaction opened; placing
            // checks every row again under the admission lock
            PlaceResult result = port.place(tenantId, user.userId(), ctx.member().role(), row.ipdAdmissionId(), lines);
            if (!result.ok()) {
                throw EmrProblemException.ipdOrderNotPlaced(result.notPlaced().stream().map(EmrSignService::refusalRow).toList());
            }
            EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
            for (EmrIpdOrderPort.Placed placed : result.placed()) {
                placedRows.add(new IpdOrderPlaced(placed.clientKey(), placed.orderId()));
                stampOrder(document, placed);
            }
            if (!recordRows.saveSectionsLocked(tenantId, row.id(), document.toJson(), user.userId())) {
                throw EmrProblemException.emrRecordChanged();
            }
        }
        EmrRoundPort roundPort = rounds.getIfAvailable();
        if (roundPort != null) {
            EmrRoundPort.RoundOutcome outcome = roundPort.recordSignedRound(tenantId, user.userId(),
                    new EmrRoundPort.RoundNote(row.ipdAdmissionId(), row.roundDate(), row.id(), work.now,
                            placedRows.size(), investigationsAdvised(ctx.document())));
            if (outcome == EmrRoundPort.RoundOutcome.NOT_ADMITTED) {
                throw EmrProblemException.encounterClosed();
            }
        }
        work.ipdOrders = List.copyOf(placedRows);
        work.round = new RoundOutcomeView(row.ipdAdmissionId(), row.roundDate());
    }

    private void signEmergency(ClinicUser user, SignContext ctx, FinishChoices choices, Work work) {
        UUID tenantId = user.tenantId();
        RecordRow row = ctx.row();
        ErOutcome outcome = choices.erOutcome();
        EmrEmergencyPort port = emergency.getIfAvailable();
        if (port == null) {
            throw EmrProblemException.notApplicable();
        }
        UUID caseId = row.erCaseId();
        switch (outcome.kind()) {
            case "DISCHARGE":
                closeCase(port, tenantId, user.userId(), caseId, CloseKind.RELEASE, null, null);
                work.emergency = new EmergencyOutcome("DISCHARGE", null, null, null);
                break;
            case "REFER_OUT":
                String line = "Referred to " + outcome.referTo().strip()
                        + (outcome.referNote() == null || outcome.referNote().isBlank() ? "" : ". " + outcome.referNote().strip());
                // DEC-048 H22: the referral line is written without "admission advised" (the check allows it on an ER record)
                if (!recordRows.setReferralLine(tenantId, row.id(), row.revision(), line.length() > 300
                        ? line.substring(0, 300) : line, user.userId())) {
                    throw EmrProblemException.emrRecordChanged();
                }
                closeCase(port, tenantId, user.userId(), caseId, CloseKind.REFER, null, null);
                work.emergency = new EmergencyOutcome("REFER_OUT", null, null, null);
                break;
            case "ADMIT":
                AdmissionRequestInput request = outcome.admission() == null
                        ? new AdmissionRequestInput("emergency", null, null) : outcome.admission();
                UUID requestId = admissions.createInSign(user, ctx, request);
                work.admissionRequestId = requestId;
                work.emergency = new EmergencyOutcome("ADMIT", requestId, null, null);
                break;
            default:
                VisitView visit = visits.issueTokenForDoctor(tenantId, user.userId(), outcome.opdPatientId(),
                        ctx.member().membershipId());
                closeCase(port, tenantId, user.userId(), caseId, CloseKind.SENT_TO_OPD, outcome.opdPatientId(), visit.id());
                if (!finish.linkPatient(tenantId, row.id(), outcome.opdPatientId())) {
                    throw EmrProblemException.emrRecordChanged();
                }
                work.emergency = new EmergencyOutcome("SEND_TO_OPD", null, visit.id(), visit.tokenNo());
                break;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Readiness and the effects shown in the dialog
    // ------------------------------------------------------------------------------------------------------------

    private static final class Assessment {
        final List<FinishBlocker> blockers = new ArrayList<>();
        final List<String> info = new ArrayList<>();
        Effects effects;
    }

    private Assessment assess(ClinicUser user, SignContext ctx, RecordState state, FinishChoices choices,
                              boolean withEffects) {
        Assessment a = new Assessment();
        RecordRow row = ctx.row();
        UUID tenantId = user.tenantId();
        RecordView view = ctx.view();
        FinishContext fc = context(user, ctx, choices, "check");
        // the encounter must still be open
        if (ctx.encounter().closed()) {
            if ("ER_ASSESSMENT".equals(row.encounterType())) {
                a.blockers.add(FinishBlocker.of("CASE_CLOSED", "This patient is no longer in the queue."));
            } else {
                a.blockers.add(FinishBlocker.of("ENCOUNTER_CLOSED", "This admission is closed"));
            }
        }
        if (state.holdActive() && state.holdMembershipId() != null
                && !state.holdMembershipId().equals(ctx.member().membershipId())) {
            a.blockers.add(FinishBlocker.of("HELD_BY_OTHER", "Open for editing by "
                    + doctorName(tenantId, state.holdMembershipId()) + "."));
        }
        if (!view.stillNeededItems().isEmpty()) {
            List<String> sections = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (StillNeededItem item : view.stillNeededItems()) {
                labels.add(item.label());
                if (item.sectionKey() != null && !sections.contains(item.sectionKey())) {
                    sections.add(item.sectionKey());
                }
            }
            a.blockers.add(new FinishBlocker("STILL_NEEDED", "Still needed: " + String.join(", ", labels) + ".",
                    List.copyOf(sections), List.of()));
        }
        if (view.completeBlockers().contains("ALLERGY_ALERT")) {
            a.blockers.add(FinishBlocker.of("ALLERGY_ALERT",
                    "Remove the drug or confirm the allergy alert before completing."));
        }
        if ("ER_ASSESSMENT".equals(row.encounterType())) {
            ErOutcome outcome = choices.erOutcome();
            if (outcome == null) {
                a.blockers.add(FinishBlocker.of("ER_OUTCOME_REQUIRED", "Choose the ER outcome."));
            } else if ("SEND_TO_OPD".equals(outcome.kind()) && outcome.opdPatientId() == null) {
                a.blockers.add(FinishBlocker.of("PATIENT_REQUIRED", "Choose the patient to send to OPD."));
            }
        }
        List<FinishOrderCheck> orderChecks = new ArrayList<>();
        if ("IPD_ROUND".equals(row.encounterType()) && withEffects) {
            orderChecks.addAll(checkOrders(user, ctx, choices, a));
        }
        PatientFacts facts = null;
        if ("OPD_VISIT".equals(row.encounterType()) || "ER_ASSESSMENT".equals(row.encounterType())) {
            facts = factsPlan(user, ctx, state, choices);
            if (facts != null && facts.tooLong) {
                a.blockers.add(FinishBlocker.of("PATIENT_FACTS_TOO_LONG", "Shorten the list to 400 characters"));
            }
        }
        Optional<SpecialtyFinishContributor> contributor = contributorOf(row);
        contributor.ifPresent(c -> a.blockers.addAll(c.blockers(fc)));
        if (withEffects) {
            a.effects = effects(user, ctx, choices, fc, facts, orderChecks, contributor);
        }
        return a;
    }

    private Effects effects(ClinicUser user, SignContext ctx, FinishChoices choices, FinishContext fc, PatientFacts facts,
                            List<FinishOrderCheck> orderChecks, Optional<SpecialtyFinishContributor> contributor) {
        UUID tenantId = user.tenantId();
        RecordRow row = ctx.row();
        StageEffect stage = null;
        FollowUpEffect followUp = null;
        List<FinishBillLine> lines = new ArrayList<>();
        long total = 0;
        ProfileEffects profile = contributor.map(c -> c.preview(fc)).orElse(null);
        if ("OPD_VISIT".equals(row.encounterType())) {
            VisitRow visit = ctx.encounter().visit();
            Optional<String> next = visits.nextStageAfterConsultation(tenantId, visit.id());
            stage = next.isPresent() ? new StageEffect("WILL_MOVE", next.get()) : new StageEffect("ALREADY_MOVED", null);
            String interval = followUpInterval(ctx.document());
            LocalDate profileDate = profile == null ? null : profile.followUpDate();
            LocalDate due = profileDate != null ? profileDate : FollowUpService.dueDateFor(fc.visitDay(), interval);
            if (due != null) {
                List<AppointmentService.FreeSlot> free = appointments.freeSlots(tenantId, visit.doctorMembershipId(), due,
                        FOLLOW_UP_SEARCH_DAYS, 1);
                followUp = new FollowUpEffect(due, interval, free.isEmpty() ? null
                        : new FreeSlot(free.get(0).date(), free.get(0).time().toString()), profileDate != null
                                && interval != null);
            }
            BillSummary bill = records.billPreview(user, ctx.encounter(), row);
            lines.addAll(bill.items());
            total += bill.totalPaise();
            for (FinishBillLine line : profile == null ? List.<FinishBillLine>of() : profile.billLines()) {
                lines.add(line);
                total += line.amountPaise() * line.qty();
            }
        }
        PatientRecordEffect patientRecord = facts == null || facts.fields.isEmpty() ? null
                : new PatientRecordEffect(List.copyOf(facts.fields), List.copyOf(facts.removals), facts.conflict);
        EmergencyEffect emergencyEffect = null;
        if ("ER_ASSESSMENT".equals(row.encounterType()) && ctx.encounter().emergency() != null) {
            emergencyEffect = new EmergencyEffect(ctx.encounter().emergency().triageLevel(),
                    ctx.encounter().emergency().triageLevelName(),
                    choices.erOutcome() == null ? null : choices.erOutcome().kind());
        }
        String investigations = "IPD_ROUND".equals(row.encounterType()) ? investigationsAdvised(ctx.document()) : null;
        List<FinishPrint> prints = printsOf(row, choices.erOutcome() == null ? null : choices.erOutcome().kind(),
                profile == null ? List.of() : profile.prints());
        return new Effects(stage, followUp, List.copyOf(lines), total, patientRecord, List.copyOf(orderChecks),
                investigations, emergencyEffect, profile, prints);
    }

    /**
     * The documents that print after the sign, in print order (opd.yaml 0.6.1, EmrFinishPrint; ADR-025 D19): the
     * prescription of every OPD visit and of an Emergency assessment that discharges, plus what the profile adds; nothing for
     * an IPD round or another Emergency outcome. Pure: computed from the record each time, never stored.
     */
    static List<FinishPrint> printsOf(RecordRow row, String erOutcomeKind, Collection<FinishPrint> profilePrints) {
        EnumSet<FinishPrint> prints = EnumSet.noneOf(FinishPrint.class);
        if ("OPD_VISIT".equals(row.encounterType())) {
            prints.add(FinishPrint.PRESCRIPTION);
            prints.addAll(profilePrints);
        } else if ("ER_ASSESSMENT".equals(row.encounterType()) && "DISCHARGE".equals(erOutcomeKind)) {
            prints.add(FinishPrint.PRESCRIPTION);
        }
        return List.copyOf(prints);
    }

    /** The prints of an answer to the sign (also a replay): the same rules over the signed record and its stored outcome. */
    private List<FinishPrint> printsAfter(RecordRow row, FinishContext fc, SignOutcome outcome) {
        List<FinishPrint> profile = List.of();
        if ("OPD_VISIT".equals(row.encounterType())) {
            profile = contributorOf(row).map(c -> c.prints(fc)).orElse(List.of());
        }
        return printsOf(row, outcome.emergency() == null ? null : outcome.emergency().outcome(), profile);
    }

    private List<FinishOrderCheck> checkOrders(ClinicUser user, SignContext ctx, FinishChoices choices, Assessment a) {
        List<FinishOrderCheck> out = new ArrayList<>();
        EmrIpdOrderPort port = ipdOrders.getIfAvailable();
        List<IpdRow> toCheck = ipdOrderRows(ctx.document()).stream().filter(r -> r.orderId == null).toList();
        if (port == null || toCheck.isEmpty()) {
            return out;
        }
        Map<UUID, IpdOrderReason> reasons = reasonsByRow(choices);
        List<OrderLine> lines = new ArrayList<>();
        for (IpdRow r : toCheck) {
            lines.add(new OrderLine(r.id, r.drugKey, r.frequencyKey, null, null));
        }
        List<UUID> blocked = new ArrayList<>();
        List<UUID> needReason = new ArrayList<>();
        String firstStop = null;
        List<LineCheck> checked;
        try {
            checked = port.check(user.tenantId(), user.userId(), ctx.member().role(), ctx.row().ipdAdmissionId(), lines);
        } catch (ConflictException closed) {
            // opd.yaml 0.6.3, DEC-051 D2: a discharged admission is 409 encounter-closed, as on the sign path
            if ("not-admitted".equals(closed.problemType())) {
                throw EmrProblemException.encounterClosed();
            }
            throw closed;
        }
        for (LineCheck check : checked) {
            out.add(orderCheck(check));
            if ("BLOCKED".equals(check.outcome())) {
                blocked.add(check.clientKey());
                if (firstStop == null && !check.texts().isEmpty()) {
                    firstStop = check.texts().get(0);
                }
            } else if ("NEEDS_REASON".equals(check.outcome())) {
                IpdOrderReason given = reasons.get(check.clientKey());
                if (given == null || !Objects.equals(given.checkToken(), check.checkToken())) {
                    needReason.add(check.clientKey());
                }
            }
        }
        if (!blocked.isEmpty()) {
            a.blockers.add(new FinishBlocker("IPD_ORDER_BLOCKED", "Not ordered: " + (firstStop == null ? "a medicine"
                    : firstStop), List.of(), List.copyOf(blocked)));
        }
        if (!needReason.isEmpty()) {
            a.blockers.add(new FinishBlocker("IPD_ORDER_NEEDS_REASON", "Choose a reason to continue.", List.of(),
                    List.copyOf(needReason)));
        }
        return out;
    }

    static final String REFUSED_BLOCKED_TEXT = "Not ordered: a safety check stopped this medicine.";
    static final String REFUSED_NEEDS_REASON_TEXT = "Not ordered: a reason to continue is needed.";

    /**
     * A row of the 409 {@code ipd-order-not-placed} problem (opd.yaml 0.6.3 EmrIpdOrderNotPlacedRow, DEC-051 D3): the row id,
     * the drug, the outcome, the check kind and one fixed reason chosen by the outcome, and nothing else. The SAH-31 lines are
     * left out because they can hold the patient's allergy text, and a problem detail never carries clinical text; the doctor
     * sees them by checking again.
     */
    static IpdOrderNotPlacedRow refusalRow(LineCheck check) {
        boolean needsReason = "NEEDS_REASON".equals(check.outcome());
        return new IpdOrderNotPlacedRow(check.clientKey(), check.drugName(), needsReason ? "NEEDS_REASON" : "BLOCKED",
                check.checkKind(), needsReason ? REFUSED_NEEDS_REASON_TEXT : REFUSED_BLOCKED_TEXT);
    }

    private FinishOrderCheck orderCheck(LineCheck check) {
        return new FinishOrderCheck(check.clientKey(), check.drugName(), check.frequencyLabel(), check.outcome(),
                check.texts(), check.checkToken());
    }

    // ------------------------------------------------------------------------------------------------------------
    // The patient record (SAH-68-3)
    // ------------------------------------------------------------------------------------------------------------

    /** What the visit would write to the patient's allergy and long-term conditions texts; null when nothing is shown. */
    private static final class PatientFacts {
        final List<String> fields = new ArrayList<>();
        final List<String> removals = new ArrayList<>();
        String allergyText;
        String chronicText;
        boolean conflict;
        boolean tooLong;
    }

    private PatientFacts factsPlan(ClinicUser user, SignContext ctx, RecordState state, FinishChoices choices) {
        UUID patientId = ctx.encounter().patientId();
        if (patientId == null) {
            return null;
        }
        Optional<PatientClinical> found = patients.find(user.tenantId(), patientId);
        if (found.isEmpty()) {
            return null;
        }
        PatientClinical patient = found.get();
        PatientFacts plan = new PatientFacts();
        EmrRecordDocument document = ctx.document();
        // OQ-45-23: "No known allergy" is written only when the doctor recorded "None" explicitly (and nothing else)
        List<String> typed = allergyTexts(document);
        List<String> real = typed.stream().filter(t -> !isNoneToken(t)).toList();
        boolean none = real.isEmpty() && !typed.isEmpty();
        List<String> allergies = none ? List.of(NO_KNOWN_ALLERGY) : real;
        if (!allergies.isEmpty()) {
            Set<String> before = new LinkedHashSet<>(splitList(patient.allergyText()));
            before.removeIf(b -> NO_KNOWN_ALLERGY.equalsIgnoreCase(b));
            Set<String> now = new LinkedHashSet<>(allergies);
            List<String> removed = before.stream().filter(b -> now.stream().noneMatch(n -> n.equalsIgnoreCase(b))).toList();
            List<String> kept = new ArrayList<>(allergies);
            if (!choices.removeAllergies() && !removed.isEmpty()) {
                // an allergy removed without the doctor's tick stays; "None" cannot be written beside an allergy that stays
                if (none) {
                    kept = new ArrayList<>(removed);
                } else {
                    kept.addAll(removed);
                }
            }
            plan.removals.addAll(removed);
            String text = String.join("; ", kept);
            if (text.length() > MAX_FACTS) {
                plan.tooLong = true;
            }
            if (!text.equalsIgnoreCase(nullToEmpty(patient.allergyText()).strip())) {
                plan.fields.add("ALLERGIES");
                plan.allergyText = text;
            }
        }
        // DEC-048 H30: the doctor's conditions are added to the patient's list (a union, nothing removed); text the front desk
        // wrote stays
        List<String> chosen = chronicTexts(document, optionLabels(ctx, "history", "past_illness"));
        if (!chosen.isEmpty()) {
            List<String> existing = splitList(patient.chronicText());
            List<String> merged = new ArrayList<>(existing);
            for (String condition : chosen) {
                if (merged.stream().noneMatch(m -> m.equalsIgnoreCase(condition))) {
                    merged.add(condition);
                }
            }
            String text = String.join("; ", merged);
            if (!text.equalsIgnoreCase(String.join("; ", existing))) {
                if (text.length() > MAX_FACTS) {
                    plan.tooLong = true;
                }
                plan.fields.add("LONG_TERM_CONDITIONS");
                plan.chronicText = text;
            }
        }
        plan.conflict = state.factsFingerprint() != null && !state.factsFingerprint().equals(patient.factsFingerprint());
        return plan;
    }

    private String writeBack(ClinicUser user, SignContext ctx, RecordState state, FinishChoices choices) {
        if ("IPD_ROUND".equals(ctx.row().encounterType())) {
            return "NOT_CHANGED";
        }
        PatientFacts plan = factsPlan(user, ctx, state, choices);
        UUID patientId = ctx.encounter().patientId();
        if (plan == null || plan.fields.isEmpty() || patientId == null) {
            return "NOT_CHANGED";
        }
        if (plan.conflict) {
            return "CONFLICT";
        }
        FactsOutcome outcome = patientUpdates.updateFacts(user.tenantId(), user.userId(), patientId,
                state.factsFingerprint() == null ? patients.find(user.tenantId(), patientId)
                        .map(PatientClinical::factsFingerprint).orElse(null) : state.factsFingerprint(),
                new FactsChange(plan.allergyText, plan.chronicText));
        return outcome.name();
    }

    /**
     * The active Allergies items and the free text, as the visit confirmed them, plus (SAH-75-6, OQ-75-16, ADR-025 D13
     * extended) the allergy chips of the Dental "Medical history and alerts" card as their labels; its None chip counts as
     * the token "None", so the H25 rule decides: it is an explicit None only when no allergy is recorded in either place.
     * Repeats (ignoring case) are written once.
     */
    static List<String> allergyTexts(EmrRecordDocument document) {
        List<String> out = new ArrayList<>();
        JsonNode values = document.values("allergy");
        collectTexts(values.path("drug_allergy"), out);
        JsonNode other = values.path("food_other");
        if (other.isTextual() && !other.asText().isBlank()) {
            out.add(other.asText().strip());
        }
        JsonNode medhx = document.values(EmrAllergyCheck.DENTAL_MEDHX_SECTION).path("specialty");
        if ("DENTAL_MED_HISTORY".equals(medhx.path("component").asText())) {
            for (JsonNode chip : medhx.path("allergies")) {
                String label = DentalRules.MED_LABELS.get(chip.asText());
                if (label != null) {
                    out.add(label);
                }
            }
        }
        List<String> distinct = new ArrayList<>();
        for (String text : out) {
            if (distinct.stream().noneMatch(d -> d.equalsIgnoreCase(text))) {
                distinct.add(text);
            }
        }
        return distinct;
    }

    /**
     * The long-term conditions chosen in Past, family and personal history: the field is a multiple choice, so the record holds
     * option keys; each is written as its label from the record's frozen layout (a key with no option stays as it is).
     */
    private static List<String> chronicTexts(EmrRecordDocument document, Map<String, String> labels) {
        List<String> out = new ArrayList<>();
        collectTexts(document.values("history").path("past_illness"), out, labels);
        return out;
    }

    /** The label of each option of a field in the record's layout snapshot (empty when the layout is not at hand). */
    private static Map<String, String> optionLabels(SignContext ctx, String sectionKey, String fieldKey) {
        Map<String, String> labels = new HashMap<>();
        RecordView view = ctx.view();
        if (view == null || view.layout() == null || view.layout().sections() == null) {
            return labels;
        }
        for (LayoutSection section : view.layout().sections()) {
            if (!sectionKey.equals(section.key()) || section.fields() == null) {
                continue;
            }
            for (FieldDefinition field : section.fields()) {
                if (fieldKey.equals(field.key()) && field.options() != null) {
                    field.options().forEach(option -> labels.put(option.key(), option.label()));
                }
            }
        }
        return labels;
    }

    /**
     * True for what a doctor types or picks to say there is no allergy ("None", "NKDA", "No known allergy"): the one
     * shared none-word list of patient.api (ADR-026 D12), the same that the record header and the IPD medicine chip use.
     */
    static boolean isNoneToken(String text) {
        return AllergyStatus.isNoneWord(text);
    }

    private static void collectTexts(JsonNode node, List<String> out) {
        collectTexts(node, out, Map.of());
    }

    private static void collectTexts(JsonNode node, List<String> out, Map<String, String> labels) {
        JsonNode items = node.isObject() ? node.path("items") : node;
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(labels.getOrDefault(item.asText(), item.asText()).strip());
            } else if (item.isObject() && item.path("active").asBoolean(true) && item.path("text").isTextual()
                    && !item.path("text").asText().isBlank()) {
                out.add(item.path("text").asText().strip());
            }
        }
    }

    private static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split(";")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Values read from the record
    // ------------------------------------------------------------------------------------------------------------

    /** One new medicine row of an IPD round. */
    private static final class IpdRow {
        UUID id;
        String drugKey;
        String frequencyKey;
        UUID orderId;
    }

    private static List<IpdRow> ipdOrderRows(EmrRecordDocument document) {
        List<IpdRow> out = new ArrayList<>();
        for (String section : document.sectionKeys()) {
            for (JsonNode value : document.values(section)) {
                if (!EmrComponentBinding.IPD_ORDERS.equals(value.path("component").asText())) {
                    continue;
                }
                for (JsonNode node : value.path("rows")) {
                    IpdRow row = new IpdRow();
                    row.id = UUID.fromString(node.path("id").asText());
                    row.drugKey = node.path("drugKey").asText();
                    row.frequencyKey = node.path("frequencyKey").asText();
                    row.orderId = node.path("orderId").isTextual() ? UUID.fromString(node.path("orderId").asText()) : null;
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static void stampOrder(EmrRecordDocument document, EmrIpdOrderPort.Placed placed) {
        for (String section : document.sectionKeys()) {
            JsonNode values = document.values(section);
            boolean touched = false;
            ObjectNode mutable = null;
            for (JsonNode value : values) {
                if (!EmrComponentBinding.IPD_ORDERS.equals(value.path("component").asText())) {
                    continue;
                }
                for (JsonNode node : value.path("rows")) {
                    if (placed.clientKey().toString().equalsIgnoreCase(node.path("id").asText())) {
                        if (mutable == null) {
                            mutable = document.mutableValues(section);
                        }
                        touched = true;
                    }
                }
            }
            if (touched) {
                for (JsonNode value : mutable) {
                    if (!EmrComponentBinding.IPD_ORDERS.equals(value.path("component").asText())) {
                        continue;
                    }
                    for (JsonNode node : value.path("rows")) {
                        if (placed.clientKey().toString().equalsIgnoreCase(node.path("id").asText())) {
                            ((ObjectNode) node).put("orderId", placed.orderId().toString());
                            ((ObjectNode) node).put("orderedAt", placed.orderedAt() == null ? null
                                    : placed.orderedAt().toString());
                        }
                    }
                }
            }
        }
    }

    private static Map<UUID, IpdOrderReason> reasonsByRow(FinishChoices choices) {
        Map<UUID, IpdOrderReason> out = new HashMap<>();
        if (choices.ipdOrderReasons() != null) {
            for (IpdOrderReason reason : choices.ipdOrderReasons()) {
                if (reason != null && reason.rowId() != null) {
                    out.put(reason.rowId(), reason);
                }
            }
        }
        return out;
    }

    /** The active investigation texts joined with "; " (at most 1,000): the only clinical text ipd receives. */
    private static String investigationsAdvised(EmrRecordDocument document) {
        List<String> texts = new ArrayList<>();
        for (String section : document.sectionKeys()) {
            for (JsonNode value : document.values(section)) {
                if (EmrComponentBinding.EMR_LIST.equals(value.path("component").asText())
                        && "INVESTIGATIONS".equals(value.path("kind").asText())) {
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true) && item.path("text").isTextual()) {
                            texts.add(item.path("text").asText().strip());
                        }
                    }
                }
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", texts);
        return joined.length() > 1000 ? joined.substring(0, 1000) : joined;
    }

    /** The follow-up interval the doctor chose (followup.next_visit), or null. */
    private static String followUpInterval(EmrRecordDocument document) {
        JsonNode value = document.values("followup").path("next_visit");
        return EmrComponentBinding.FOLLOW_UP.equals(value.path("component").asText()) && value.path("interval").isTextual()
                ? value.path("interval").asText() : null;
    }

    /** The distinct master items of the record: complaints, diagnoses, investigations, advice, medicines with a master id. */
    static Set<MasterItemRef> masterItems(EmrRecordDocument document) {
        Set<MasterItemRef> out = new LinkedHashSet<>();
        for (String section : document.sectionKeys()) {
            for (JsonNode value : document.values(section)) {
                String component = value.path("component").asText();
                if (EmrComponentBinding.EMR_LIST.equals(component)) {
                    EmrMaster master = switch (value.path("kind").asText()) {
                        case "COMPLAINTS" -> EmrMaster.COMPLAINTS;
                        case "DIAGNOSIS" -> EmrMaster.DIAGNOSES;
                        case "INVESTIGATIONS" -> EmrMaster.INVESTIGATIONS;
                        case "ADVICE" -> EmrMaster.ADVICE;
                        default -> null;
                    };
                    if (master == null) {
                        continue;
                    }
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true)) {
                            addMaster(out, master, item.path("itemKey"));
                        }
                    }
                } else if (EmrComponentBinding.PRESCRIPTION.equals(component)) {
                    for (JsonNode row : value.path("rows")) {
                        addMaster(out, EmrMaster.MEDICINES, row.path("medicineId"));
                    }
                }
            }
        }
        return out;
    }

    private static void addMaster(Set<MasterItemRef> out, EmrMaster master, JsonNode key) {
        if (key.isTextual() && !key.asText().isEmpty() && key.asText().length() <= MasterItemRef.MAX_KEY_LENGTH) {
            out.add(new MasterItemRef(master, key.asText()));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Validation, context, hash, audit
    // ------------------------------------------------------------------------------------------------------------

    /** Fields that do not apply to the record's encounter or profile must be absent (400 validation). */
    private void validateChoices(SignContext ctx, FinishChoices choices) {
        List<FieldMessage> errors = new ArrayList<>();
        String type = ctx.row().encounterType();
        ErOutcome outcome = choices.erOutcome();
        if (outcome != null && !"ER_ASSESSMENT".equals(type)) {
            errors.add(new FieldMessage("choices.erOutcome", "The ER outcome is only for an emergency assessment."));
        }
        if (outcome != null && "ER_ASSESSMENT".equals(type)) {
            if (outcome.kind() == null || !ER_KINDS.contains(outcome.kind())) {
                errors.add(new FieldMessage("choices.erOutcome.kind", "Choose the ER outcome."));
            } else if ("REFER_OUT".equals(outcome.kind())) {
                String to = outcome.referTo() == null ? "" : outcome.referTo().strip();
                if (to.isEmpty() || to.length() > 120) {
                    errors.add(new FieldMessage("choices.erOutcome.referTo", "Enter where the patient is referred (1 to 120 characters)."));
                }
                if (outcome.referNote() != null && outcome.referNote().length() > 300) {
                    errors.add(new FieldMessage("choices.erOutcome.referNote", "Enter at most 300 characters."));
                }
            } else if ("ADMIT".equals(outcome.kind()) && outcome.admission() != null) {
                errors.addAll(admissions.validate(outcome.admission(), "choices.erOutcome.admission"));
            }
        }
        if (choices.ipdOrderReasons() != null && !choices.ipdOrderReasons().isEmpty() && !"IPD_ROUND".equals(type)) {
            errors.add(new FieldMessage("choices.ipdOrderReasons", "Reasons are only for a round's medicines."));
        }
        String profile = ctx.row().starterKey();
        if (choices.dental() != null && !"dental".equals(profile)) {
            errors.add(new FieldMessage("choices.dental", "This is only for a dental visit."));
        }
        if (choices.physio() != null && !"physio".equals(profile)) {
            errors.add(new FieldMessage("choices.physio", "This is only for a physiotherapy visit."));
        }
        if (choices.dental() != null && choices.dental().nextInterval() != null
                && !Set.of("D3", "D7", "D14", "M1").contains(choices.dental().nextInterval())) {
            errors.add(new FieldMessage("choices.dental.nextInterval", "Choose 3 days, 7 days, 14 days or 1 month."));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private FinishContext context(ClinicUser user, SignContext ctx, FinishChoices choices, String key) {
        Instant now = clock.instant();
        return new FinishContext(user.tenantId(), user.userId(), ctx.member().membershipId(), ctx.member().role(),
                ctx.row(), ctx.document(), ctx.encounter().visit(), choices, key, now.atZone(INDIA).toLocalDate(), now);
    }

    /**
     * True when the record was signed by a request that held the case lock before this one (an Emergency retry waited on the
     * case lock; the first sign closed the case and committed). Read again after the wait: the retry then replays the first
     * answer (or learns who signed) instead of "no longer in the queue".
     */
    private boolean signedMeanwhile(UUID tenantId, UUID recordId) {
        return recordRows.findById(tenantId, recordId).map(r -> "SIGNED".equals(r.status())).orElse(false);
    }

    /** Closes the Emergency case through ipd; a case that did not close (closed by someone else) refuses the sign. */
    private static void closeCase(EmrEmergencyPort port, UUID tenantId, UUID actorUserId, UUID caseId, CloseKind kind,
                                  UUID patientId, UUID visitId) {
        if (!port.close(tenantId, actorUserId, caseId, kind, patientId, visitId).closed()) {
            throw EmrProblemException.caseClosed();
        }
    }

    private Optional<SpecialtyFinishContributor> contributorOf(RecordRow row) {
        if (!"OPD_VISIT".equals(row.encounterType()) || row.starterKey() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(contributors.get(row.starterKey()));
    }

    private String doctorName(UUID tenantId, UUID membershipId) {
        if (membershipId == null) {
            return "Dr. Unknown";
        }
        return "Dr. " + teamMembers.find(tenantId, membershipId).map(TeamMember::name).orElse("Unknown");
    }

    private String requestHash(SignInput input) {
        try {
            String body = input.revision() + "|" + mapper.writeValueAsString(input.choices());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8))).toLowerCase(Locale.ROOT);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("The sign request could not be hashed", e);
        }
    }

    private String writeJson(SignOutcome outcome) {
        try {
            return mapper.writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The sign result could not be stored", e);
        }
    }

    private SignOutcome readOutcome(String json) {
        try {
            return json == null ? null : mapper.readValue(json, SignOutcome.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The stored sign result could not be read", e);
        }
    }

    private void auditSigned(RecordRow row, SignContext ctx, Work work, SignOutcome outcome) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("encounterType", row.encounterType());
        after.put("encounterId", row.encounterId());
        after.put("departmentKey", row.departmentKey());
        after.put("stageOutcome", outcome.stage() == null ? null : outcome.stage().outcome());
        after.put("followUpId", outcome.followUp() == null ? null : outcome.followUp().id());
        after.put("appointmentId", work.appointmentId);
        after.put("billLines", outcome.billLines());
        after.put("billTotalPaise", outcome.billTotalPaise());
        after.put("ordersPlaced", outcome.ipdOrders().size());
        after.put("erOutcome", outcome.emergency() == null ? null : outcome.emergency().outcome());
        after.put("admissionRequestId", work.admissionRequestId);
        after.put("patientRecord", outcome.patientRecord());
        auditLog.record("EMR_RECORD_SIGNED", ENTITY, row.id(), null, after);
    }
}
