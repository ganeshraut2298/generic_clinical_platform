package com.sahaj.opd.application;

import com.sahaj.opd.application.FollowUpRepository.LockedFollowUp;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-93 (ADR-032 D8, E9): the front desk's follow-up writes. Lock order: the follow-up row, then (inside the booking) the
 * slot advisory lock, then the appointment insert. Audit rows carry ids, dates and the interval key only: never a name.
 */
@Service
public class DeskFollowUpService {

    static final String ENTITY_TYPE = FollowUpService.ENTITY_TYPE;
    static final String REASON = "Follow-up";
    static final String BOOKED = "BOOKED";
    static final String NOT_NEEDED = "NOT_NEEDED";

    private static final ZoneId INDIA = VisitService.INDIA;
    private static final Set<String> WRITE_ROLES = Set.of("OWNER", "FRONT_DESK");
    private static final Set<String> INTERVALS = Set.of("D7", "D15", "M1");
    private static final Set<String> VISIT_TYPES = Set.of("NEW", "FOLLOW_UP");
    private static final Set<String> PAST_DOCTOR = Set.of("COUNSEL", "BILLING", "DONE");

    /** What a desk action did: BOOKED with the appointment, or NOT_NEEDED (appointment null). */
    public record Result(UUID followUpId, String state, LocalDate dueDate, AppointmentView appointment) {
    }

    /** The body of the visit follow-up booking (VisitFollowUpBookInput). */
    public record VisitBooking(String intervalKey, LocalDate apptDate, String apptTime, String visitType) {
    }

    /** The body of the idle-list booking (FollowUpBookInput). */
    public record ListBooking(UUID doctorMembershipId, LocalDate apptDate, String apptTime, String visitType) {
    }

    private final FollowUpRepository followUps;
    private final VisitRepository visits;
    private final AppointmentService appointments;
    private final AuditLog auditLog;
    private final Clock clock;

    public DeskFollowUpService(FollowUpRepository followUps, VisitRepository visits, AppointmentService appointments,
                               AuditLog auditLog, Clock clock) {
        this.followUps = followUps;
        this.visits = visits;
        this.appointments = appointments;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** POST /visits/{visitId}/follow-up/appointment. */
    @Transactional
    public Result bookForVisit(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId, VisitBooking input) {
        requireWriter(actorRole);
        if (input == null || input.intervalKey() == null || !INTERVALS.contains(input.intervalKey())) {
            throw new InvalidInputException("intervalKey", "Choose +7 days, +15 days or +1 month");
        }
        if (input.apptDate() == null) {
            throw new InvalidInputException("apptDate", "Choose a date");
        }
        String visitType = visitTypeOf(input.visitType());
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        VisitRow visit = completedVisit(tenantId, visitId);
        LockedFollowUp followUp = lockedOrCreated(tenantId, actorUserId, visit, today);
        requireOpen(followUp);

        LocalDate dueDate = FollowUpService.dueDateFor(today, input.intervalKey());
        if (!dueDate.equals(followUp.dueDate())) {
            followUps.updateDueDate(tenantId, followUp.id(), dueDate, actorUserId);
            auditLog.record("FOLLOW_UP_DUE_CHANGED", ENTITY_TYPE, followUp.id(),
                    Map.of("dueDate", followUp.dueDate().toString()), Map.of("dueDate", dueDate.toString()));
        }
        AppointmentView appointment = appointments.create(tenantId, actorUserId, actorRole, new AppointmentCreateInput(
                visit.patientId(), visit.doctorMembershipId(), input.apptDate(), input.apptTime(), visitType,
                "FRONT_DESK", null));
        followUps.setAppointment(tenantId, followUp.id(), appointment.id(), input.intervalKey(), actorUserId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("followUpId", followUp.id());
        after.put("appointmentId", appointment.id());
        after.put("intervalKey", input.intervalKey());
        after.put("dueDate", dueDate.toString());
        auditLog.record("FOLLOW_UP_BOOKED", ENTITY_TYPE, followUp.id(), null, after);
        return new Result(followUp.id(), BOOKED, dueDate, appointment);
    }

    /** POST /visits/{visitId}/follow-up/not-needed: a second call answers the stored state and writes nothing. */
    @Transactional
    public Result markNotNeeded(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId) {
        requireWriter(actorRole);
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        VisitRow visit = completedVisit(tenantId, visitId);
        LockedFollowUp followUp = lockedOrCreated(tenantId, actorUserId, visit, today);
        if (followUp.appointmentId() != null) {
            throw OpdProblemException.followUpAlreadyBooked();
        }
        if (followUp.returnedVisitId() != null) {
            throw OpdProblemException.followUpClosed();
        }
        if (!followUp.notNeeded()) {
            followUps.markNotNeeded(tenantId, followUp.id(), actorUserId);
            auditLog.record("FOLLOW_UP_NOT_NEEDED", ENTITY_TYPE, followUp.id(), null,
                    Map.of("followUpId", followUp.id()));
        }
        return new Result(followUp.id(), NOT_NEEDED, followUp.dueDate(), null);
    }

    /** POST /follow-ups/{followUpId}/appointment: "Find slot" of the idle list; the due date is never changed. */
    @Transactional
    public Result bookFromList(UUID tenantId, UUID actorUserId, String actorRole, UUID followUpId, ListBooking input) {
        requireWriter(actorRole);
        if (input == null || input.doctorMembershipId() == null) {
            throw new InvalidInputException("doctorMembershipId", "Choose a doctor");
        }
        if (input.apptDate() == null) {
            throw new InvalidInputException("apptDate", "Choose a date");
        }
        String visitType = visitTypeOf(input.visitType());
        LockedFollowUp followUp = followUps.findByIdForUpdate(tenantId, followUpId)
                .orElseThrow(() -> new NotFoundException("Follow-up not found"));
        requireOpen(followUp);
        AppointmentView appointment = appointments.create(tenantId, actorUserId, actorRole, new AppointmentCreateInput(
                followUp.patientId(), input.doctorMembershipId(), input.apptDate(), input.apptTime(), visitType,
                "FRONT_DESK", null));
        followUps.setAppointment(tenantId, followUp.id(), appointment.id(), null, actorUserId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("followUpId", followUp.id());
        after.put("appointmentId", appointment.id());
        after.put("dueDate", followUp.dueDate().toString());
        auditLog.record("FOLLOW_UP_BOOKED", ENTITY_TYPE, followUp.id(), null, after);
        return new Result(followUp.id(), BOOKED, followUp.dueDate(), appointment);
    }

    private static void requireWriter(String actorRole) {
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can set a follow-up.");
        }
    }

    private static String visitTypeOf(String requested) {
        String visitType = requested == null ? "FOLLOW_UP" : requested;
        if (!VISIT_TYPES.contains(visitType)) {
            throw new InvalidInputException("visitType", "Choose New or Follow-up");
        }
        return visitType;
    }

    /** The visit of this clinic, past With doctor (COUNSEL, BILLING or DONE); walk-in visits included. */
    private VisitRow completedVisit(UUID tenantId, UUID visitId) {
        VisitRow visit = visits.findById(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        if (!PAST_DOCTOR.contains(visit.stage())) {
            throw OpdProblemException.visitNotDone();
        }
        return visit;
    }

    /** The visit's follow-up locked, or created (reason "Follow-up", due today) and locked when there is none. */
    private LockedFollowUp lockedOrCreated(UUID tenantId, UUID actorUserId, VisitRow visit, LocalDate today) {
        Optional<LockedFollowUp> existing = followUps.findBySourceVisitForUpdate(tenantId, visit.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        followUps.insertIfAbsent(id, visit.patientId(), visit.doctorMembershipId(), visit.id(), today, REASON,
                actorUserId);
        LockedFollowUp created = followUps.findBySourceVisitForUpdate(tenantId, visit.id())
                .orElseThrow(() -> new IllegalStateException("The follow-up of the visit was not found"));
        if (created.id().equals(id)) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("patientId", visit.patientId());
            after.put("doctorMembershipId", visit.doctorMembershipId());
            after.put("sourceVisitId", visit.id());
            after.put("dueDate", today.toString());
            auditLog.record("FOLLOW_UP_CREATED", ENTITY_TYPE, id, null, after);
        }
        return created;
    }

    private static void requireOpen(LockedFollowUp followUp) {
        if (followUp.appointmentId() != null) {
            throw OpdProblemException.followUpAlreadyBooked();
        }
        if (followUp.returnedVisitId() != null || followUp.notNeeded()) {
            throw OpdProblemException.followUpClosed();
        }
    }
}
