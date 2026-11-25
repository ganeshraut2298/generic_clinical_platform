package com.sahaj.opd.application;

import com.sahaj.opd.application.AppointmentRepository.AppointmentRow;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-11 AC2, AC3, AC4: book, see, cancel, mark no-show and turn an appointment into a SAH-9
 * token. Enforcement of the clinic's booking rules (close-before window, overbook) lives here,
 * not on the controller, so a direct service call is checked the same way a request is.
 */
@Service
public class AppointmentService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String ENTITY_TYPE = "APPOINTMENT";

    private static final Set<String> WRITE_ROLES = Set.of("OWNER", "FRONT_DESK");
    private static final Set<String> VISIT_TYPES = Set.of("NEW", "FOLLOW_UP");
    private static final Set<String> SOURCES = Set.of("PHONE", "WHATSAPP", "FRONT_DESK");
    private static final Set<String> PATCHABLE_STATUSES = Set.of("CANCELLED", "NOSHOW");
    private static final String BOOKED = "BOOKED";
    private static final String ARRIVED = "ARRIVED";
    private static final String NOSHOW = "NOSHOW";
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");
    /** The free slots bookFirstFree reads and tries in turn (each costs one select inside the sign transaction). */
    private static final int BOOK_CANDIDATES = 10;

    private final AppointmentRepository appointmentRepository;
    private final BookingRulesRepository bookingRulesRepository;
    private final VisitRepository visitRepository;
    private final VisitService visitService;
    private final PatientLookupPort patientLookup;
    private final TeamMemberPort teamMemberPort;
    private final DoctorBlockedDates blockedDates;
    private final AuditLog auditLog;
    private final Clock clock;
    /** SAH-51 (ADR-025 D6): the doctor's slot pattern for the EMR's follow-up booking; null in a plain unit test. */
    private final DoctorSlotSettingsRepository slotSettings;
    /** SAH-92-2: the token panel of a check-in; null in a plain unit test. */
    private final DeskReadService deskReads;

    public AppointmentService(AppointmentRepository appointmentRepository, BookingRulesRepository bookingRulesRepository,
                               VisitRepository visitRepository, VisitService visitService,
                               PatientLookupPort patientLookup, TeamMemberPort teamMemberPort,
                               DoctorBlockedDates blockedDates, AuditLog auditLog, Clock clock) {
        this(appointmentRepository, bookingRulesRepository, visitRepository, visitService, patientLookup, teamMemberPort,
                blockedDates, auditLog, clock, null);
    }

    public AppointmentService(AppointmentRepository appointmentRepository, BookingRulesRepository bookingRulesRepository,
                               VisitRepository visitRepository, VisitService visitService,
                               PatientLookupPort patientLookup, TeamMemberPort teamMemberPort,
                               DoctorBlockedDates blockedDates, AuditLog auditLog, Clock clock,
                               DoctorSlotSettingsRepository slotSettings) {
        this(appointmentRepository, bookingRulesRepository, visitRepository, visitService, patientLookup, teamMemberPort,
                blockedDates, auditLog, clock, slotSettings, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AppointmentService(AppointmentRepository appointmentRepository, BookingRulesRepository bookingRulesRepository,
                               VisitRepository visitRepository, VisitService visitService,
                               PatientLookupPort patientLookup, TeamMemberPort teamMemberPort,
                               DoctorBlockedDates blockedDates, AuditLog auditLog, Clock clock,
                               DoctorSlotSettingsRepository slotSettings, DeskReadService deskReads) {
        this.deskReads = deskReads;
        this.slotSettings = slotSettings;
        this.appointmentRepository = appointmentRepository;
        this.bookingRulesRepository = bookingRulesRepository;
        this.visitRepository = visitRepository;
        this.visitService = visitService;
        this.patientLookup = patientLookup;
        this.teamMemberPort = teamMemberPort;
        this.blockedDates = blockedDates;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AppointmentView> list(UUID tenantId, LocalDate apptDate, UUID doctorMembershipId) {
        if (apptDate == null) {
            throw new InvalidInputException("date", "Choose a date");
        }
        List<AppointmentRow> rows = appointmentRepository.findByDate(tenantId, apptDate, doctorMembershipId);
        Map<UUID, PatientSummary> patients = new HashMap<>();
        Map<UUID, TeamMember> doctors = new HashMap<>();
        List<AppointmentView> views = new ArrayList<>();
        for (AppointmentRow row : rows) {
            PatientSummary patient = patients.computeIfAbsent(row.patientId(),
                    id -> patientLookup.find(tenantId, id).orElse(null));
            TeamMember doctor = doctors.computeIfAbsent(row.doctorMembershipId(),
                    id -> teamMemberPort.find(tenantId, id).orElse(null));
            if (patient == null || doctor == null) {
                continue;
            }
            views.add(toView(tenantId, row, patient, doctor));
        }
        return views;
    }

    /**
     * AC2, AC3: book a patient into a doctor's slot, refusing the close-before window and a clash
     * per the booking rules. The role gate here is the API contract's role list for this call
     * (OWNER, FRONT_DESK) checked a second time, already enforced by the controller's
     * {@code @PreAuthorize} - except for the close-before and overbook checks just below, which
     * intentionally do not gate on role up front: AC2 says those two checks are refused for
     * everyone except a staff override (OWNER/FRONT_DESK can always book). Today every caller of
     * this method is already OWNER or FRONT_DESK, so the override always applies and neither
     * check can actually refuse a booking; the distinction is kept so a future self-book channel
     * (Phase 2, no schema change needed) can call this same service with a patient-facing role and
     * be refused correctly, without the blanket gate hiding that behaviour.
     */
    @Transactional
    public AppointmentView create(UUID tenantId, UUID actorUserId, String actorRole, AppointmentCreateInput input) {
        // ADR-032 D4 check 1: the role. The controller gates it too; a direct service call is refused the same way.
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can book an appointment.");
        }
        if (input.patientId() == null) {
            throw new InvalidInputException("patientId", "Choose a patient");
        }
        if (input.doctorMembershipId() == null) {
            throw new InvalidInputException("doctorMembershipId", "Choose a doctor");
        }
        if (input.apptDate() == null) {
            throw new InvalidInputException("apptDate", "Choose a date");
        }
        LocalTime apptTime = parseTime(input.apptTime());
        if (input.visitType() == null || !VISIT_TYPES.contains(input.visitType())) {
            throw new InvalidInputException("visitType", "Choose New or Follow-up");
        }
        if (input.source() == null || !SOURCES.contains(input.source())) {
            throw new InvalidInputException("source", "Choose how this booking came in");
        }

        PatientSummary patient = patientLookup.find(tenantId, input.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = activeDoctor(tenantId, input.doctorMembershipId());

        BookingRulesRow rules = bookingRulesRepository.find(tenantId).orElse(BookingRulesService.DEFAULTS);
        requireBookable(tenantId, actorRole, rules, doctor.membershipId(), input.apptDate(), apptTime);

        // check 9: the slot lock; 10: the same patient already holds the slot; 11: someone else does
        appointmentRepository.lockSlot(tenantId, doctor.membershipId(), input.apptDate(), apptTime);
        appointmentRepository.findActiveForPatient(tenantId, patient.id(), doctor.membershipId(), input.apptDate(),
                apptTime, null).ifPresent(existing -> {
                    throw OpdProblemException.appointmentDuplicate(existing.id());
                });
        requireSlotNotTaken(actorRole, rules, tenantId, doctor.membershipId(), input.apptDate(), apptTime, null);

        UUID id = UUID.randomUUID();
        appointmentRepository.insert(id, patient.id(), doctor.membershipId(), input.apptDate(), apptTime,
                input.visitType(), input.source(), input.note(), actorUserId);
        AppointmentRow created = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_BOOKED", ENTITY_TYPE, id, null, snapshot(created));
        return toView(tenantId, created, patient, doctor);
    }

    /**
     * SAH-91-3 (ADR-032 D5): moves an appointment in place to another doctor, date and time and sets it BOOKED. The row is
     * locked first; a stale version is 409 appointment-changed; only BOOKED and NOSHOW can move; then the checks of
     * {@link #create} for the new slot, the appointment's own row never being a clash. The same doctor, date and time of a
     * BOOKED appointment answers the stored row and writes nothing.
     */
    @Transactional
    public AppointmentView reschedule(UUID tenantId, UUID actorUserId, String actorRole, UUID id,
                                      AppointmentRescheduleInput input) {
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can move an appointment.");
        }
        if (input.version() == null || input.version() < 0) {
            throw new InvalidInputException("version", "Reload the page and try again.");
        }
        if (input.doctorMembershipId() == null) {
            throw new InvalidInputException("doctorMembershipId", "Choose a doctor");
        }
        if (input.apptDate() == null) {
            throw new InvalidInputException("apptDate", "Choose a date");
        }
        LocalTime apptTime = parseTime(input.apptTime());

        AppointmentRow before = appointmentRepository.findByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (before.version() != input.version()) {
            throw OpdProblemException.appointmentChanged();
        }
        if (!BOOKED.equals(before.status()) && !NOSHOW.equals(before.status())) {
            throw OpdProblemException.appointmentNotReschedulable();
        }
        PatientSummary patient = patientLookup.find(tenantId, before.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = activeDoctor(tenantId, input.doctorMembershipId());
        if (BOOKED.equals(before.status()) && before.doctorMembershipId().equals(doctor.membershipId())
                && before.apptDate().equals(input.apptDate()) && before.apptTime().equals(apptTime)) {
            return toView(tenantId, before, patient, doctor);
        }

        BookingRulesRow rules = bookingRulesRepository.find(tenantId).orElse(BookingRulesService.DEFAULTS);
        requireBookable(tenantId, actorRole, rules, doctor.membershipId(), input.apptDate(), apptTime);
        appointmentRepository.lockSlot(tenantId, doctor.membershipId(), input.apptDate(), apptTime);
        appointmentRepository.findActiveForPatient(tenantId, patient.id(), doctor.membershipId(), input.apptDate(),
                apptTime, id).ifPresent(existing -> {
                    throw OpdProblemException.appointmentDuplicate(existing.id());
                });
        requireSlotNotTaken(actorRole, rules, tenantId, doctor.membershipId(), input.apptDate(), apptTime, id);

        if (!appointmentRepository.reschedule(id, input.version(), doctor.membershipId(), input.apptDate(), apptTime,
                actorUserId)) {
            throw OpdProblemException.appointmentChanged();
        }
        AppointmentRow after = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_RESCHEDULED", ENTITY_TYPE, id, placement(before), placement(after));
        return toView(tenantId, after, patient, doctor);
    }

    private TeamMember activeDoctor(UUID tenantId, UUID doctorMembershipId) {
        return teamMemberPort.find(tenantId, doctorMembershipId)
                .filter(member -> member.active() && DOCTOR_ROLES.contains(member.role()))
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
    }

    /**
     * ADR-032 D4 checks 4 to 8 for one doctor, date and time: approved leave (409 doctor-unavailable), then the shared
     * {@link BookingPolicy} (working day, grid, walk-in reserve, past, cut-off; the cut-off never refuses staff).
     */
    private void requireBookable(UUID tenantId, String actorRole, BookingRulesRow rules, UUID doctorMembershipId,
                                 LocalDate date, LocalTime time) {
        if (blockedDates.blocked(tenantId, doctorMembershipId, date, date).contains(date)) {
            throw new ConflictException("doctor-unavailable", "Doctor is not taking bookings that day",
                    "This doctor is not taking bookings on that date.");
        }
        DoctorSlotSettingsRepository.SlotSettingsRow plan = slotSettings == null ? DoctorSlotSettingsService.DEFAULTS
                : slotSettings.find(tenantId, doctorMembershipId).orElse(DoctorSlotSettingsService.DEFAULTS);
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        BookingPolicy.Refusal refusal = BookingPolicy.firstRefusal(plan, rules.closeBeforeHours(), date, time, now,
                BookingPolicy.isStaff(actorRole));
        if (refusal == null) {
            return;
        }
        switch (refusal) {
            case NOT_WORKING -> throw OpdProblemException.doctorNotWorking();
            case NOT_ON_GRID -> throw OpdProblemException.slotNotOnGrid();
            case WALK_IN_ONLY -> throw OpdProblemException.slotWalkInOnly();
            case IN_PAST -> throw OpdProblemException.slotInPast();
            default -> throw OpdProblemException.bookingClosed(
                    BookingPolicy.effectiveCloseBefore(plan.closeBeforeHours(), rules.closeBeforeHours()));
        }
    }

    /** Check 11: another BOOKED or ARRIVED appointment holds the slot; overbook on lets only staff add to it. */
    private void requireSlotNotTaken(String actorRole, BookingRulesRow rules, UUID tenantId, UUID doctorMembershipId,
                                     LocalDate date, LocalTime time, UUID excludeId) {
        appointmentRepository.findActiveConflict(tenantId, doctorMembershipId, date, time, excludeId)
                .ifPresent(existing -> {
                    if (!rules.overbook()) {
                        throw new ConflictException("appointment-slot-taken", "That slot is already booked",
                                "That slot was just booked. Pick another time.");
                    }
                    if (!WRITE_ROLES.contains(actorRole)) {
                        throw new AccessDeniedException("Only the owner or front desk can double-book a slot.");
                    }
                });
    }

    /** Doctor, date, time and status words only: never a name (ADR-032 D5). */
    private static Map<String, Object> placement(AppointmentRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("doctorMembershipId", row.doctorMembershipId());
        values.put("apptDate", row.apptDate());
        values.put("apptTime", row.apptTime());
        values.put("status", row.status());
        return values;
    }

    /** One free slot of a doctor (ADR-025 D6): the date and the start time. */
    public record FreeSlot(LocalDate date, LocalTime time) {
    }

    /** The appointment booked by {@link #bookFirstFree}. */
    public record BookedSlot(UUID appointmentId, LocalDate date, LocalTime time) {
    }

    /**
     * The doctor's next free slots from {@code from} for {@code days} days, oldest first, at most {@code limit} (SAH-11 slot
     * settings; leave, walk-in slots, taken slots and times already past today are left out). Reads only.
     */
    @Transactional(readOnly = true)
    public List<FreeSlot> freeSlots(UUID tenantId, UUID doctorMembershipId, LocalDate from, int days, int limit) {
        List<FreeSlot> out = new ArrayList<>();
        if (slotSettings == null || from == null) {
            return out;
        }
        DoctorSlotSettingsRepository.SlotSettingsRow settings = slotSettings.find(tenantId, doctorMembershipId)
                .orElse(DoctorSlotSettingsService.DEFAULTS);
        LocalDate lastDay = from.plusDays(Math.max(days, 1) - 1L);
        Set<LocalDate> blocked = blockedDates.blocked(tenantId, doctorMembershipId, from, lastDay);
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        for (LocalDate day = from; !day.isAfter(lastDay) && out.size() < limit; day = day.plusDays(1)) {
            // the staff view with no cut-off: the EMR choice is the doctor's own (DEC-097 addendum 2 item 3)
            for (BookingPolicy.OpenSlot slot : BookingPolicy.openSlots(settings, 0, day, now, true,
                    blocked.contains(day), Set.of())) {
                if (appointmentRepository.findActiveConflict(tenantId, doctorMembershipId, day, slot.time())
                        .isEmpty()) {
                    out.add(new FreeSlot(day, slot.time()));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * True when {@code time} on {@code date} is a slot the doctor can take bookings in: on the grid of the doctor's slot
     * settings, on a working day, not on leave, not reserved for walk-ins and not already past. A slot someone else has booked
     * still counts (use {@link #freeSlots} for the open ones). Reads only.
     */
    @Transactional(readOnly = true)
    public boolean isOpenSlot(UUID tenantId, UUID doctorMembershipId, LocalDate date, LocalTime time) {
        if (slotSettings == null || date == null || time == null) {
            return false;
        }
        if (blockedDates.blocked(tenantId, doctorMembershipId, date, date).contains(date)) {
            return false;
        }
        DoctorSlotSettingsRepository.SlotSettingsRow settings = slotSettings.find(tenantId, doctorMembershipId)
                .orElse(DoctorSlotSettingsService.DEFAULTS);
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        return BookingPolicy.firstRefusal(settings, 0, date, time, now, true) == null;
    }

    /**
     * SAH-51-6 (ADR-025 D6): books the first free slot of the doctor from {@code from} for up to {@code maxDays} days for the
     * follow-up the doctor's sign chose (visit type FOLLOW_UP, source EMR), in the sign transaction. Empty when there is none
     * (the follow-up then keeps the date only, ADR-025 H6). The slot is locked and checked again before it is written.
     */
    @Transactional
    public Optional<BookedSlot> bookFirstFree(UUID tenantId, UUID actorUserId, UUID patientId, UUID doctorMembershipId,
                                              LocalDate from, int maxDays, String visitType, String source) {
        // a few candidates are enough: each is a select and the sign holds its row locks meanwhile (code review 9)
        for (FreeSlot slot : freeSlots(tenantId, doctorMembershipId, from, maxDays, BOOK_CANDIDATES)) {
            appointmentRepository.lockSlot(tenantId, doctorMembershipId, slot.date(), slot.time());
            if (appointmentRepository.findActiveConflict(tenantId, doctorMembershipId, slot.date(), slot.time())
                    .isPresent()) {
                continue;
            }
            UUID id = UUID.randomUUID();
            appointmentRepository.insert(id, patientId, doctorMembershipId, slot.date(), slot.time(), visitType, source,
                    null, actorUserId);
            AppointmentRow created = findRow(tenantId, id);
            auditLog.record("APPOINTMENT_BOOKED", ENTITY_TYPE, id, null, snapshot(created));
            return Optional.of(new BookedSlot(id, slot.date(), slot.time()));
        }
        return Optional.empty();
    }

    /**
     * SAH-63-3 (ADR-025 D7): books the slot the doctor chose in the finish dialog (physiotherapy session n+1). Empty when the
     * slot was taken, the doctor is on leave, or {@link BookingPolicy#firstRefusal} refuses it as staff (not a working day, off
     * the grid, walk-in only, in the past; there is no cut-off for this call) - the sitting then keeps the date only.
     */
    @Transactional
    public Optional<BookedSlot> bookAt(UUID tenantId, UUID actorUserId, UUID patientId, UUID doctorMembershipId,
                                       LocalDate date, LocalTime time, String visitType, String source) {
        if (blockedDates.blocked(tenantId, doctorMembershipId, date, date).contains(date)) {
            return Optional.empty();
        }
        DoctorSlotSettingsRepository.SlotSettingsRow plan = slotSettings == null ? DoctorSlotSettingsService.DEFAULTS
                : slotSettings.find(tenantId, doctorMembershipId).orElse(DoctorSlotSettingsService.DEFAULTS);
        // working day, grid, walk-in reserve and past, as staff (no cut-off): the same rule POST /appointments applies
        if (BookingPolicy.firstRefusal(plan, 0, date, time, LocalDateTime.now(clock.withZone(INDIA)), true) != null) {
            return Optional.empty();
        }
        appointmentRepository.lockSlot(tenantId, doctorMembershipId, date, time);
        if (appointmentRepository.findActiveConflict(tenantId, doctorMembershipId, date, time).isPresent()) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        appointmentRepository.insert(id, patientId, doctorMembershipId, date, time, visitType, source, null, actorUserId);
        AppointmentRow created = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_BOOKED", ENTITY_TYPE, id, null, snapshot(created));
        return Optional.of(new BookedSlot(id, date, time));
    }

    /** AC3, AC4, AC7: cancel or mark no-show; only a still-booked appointment can change this way. */
    @Transactional
    public AppointmentView changeStatus(UUID tenantId, UUID actorUserId, String actorRole, UUID id, String status) {
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can change an appointment's status.");
        }
        if (status == null || !PATCHABLE_STATUSES.contains(status)) {
            throw new InvalidInputException("status", "Choose Cancelled or No-show");
        }
        // ADR-032 D6: the row is locked before its status is read, so a cancel racing a check-in ends in one outcome.
        AppointmentRow before = appointmentRepository.findByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (!BOOKED.equals(before.status())) {
            throw OpdProblemException.appointmentNotBooked();
        }
        appointmentRepository.updateStatus(id, status, actorUserId);
        AppointmentRow after = findRow(tenantId, id);
        auditLog.record("CANCELLED".equals(status) ? "APPOINTMENT_CANCELLED" : "APPOINTMENT_NOSHOW", ENTITY_TYPE, id,
                Map.of("status", before.status()), Map.of("status", after.status()));
        return toView(tenantId, after);
    }

    /** AC3, AC4, AC7: "Create token" issues a SAH-9 visit and marks this appointment Arrived with its token number. */
    @Transactional
    public AppointmentView createToken(UUID tenantId, UUID actorUserId, String actorRole, UUID id) {
        return checkIn(tenantId, actorUserId, actorRole, id).appointment();
    }

    /** The answer of the check-in: the appointment and the token panel (null only in a unit test without the desk reads). */
    public record CheckIn(AppointmentView appointment, DeskViews.TokenPanel tokenPanel) {
    }

    /**
     * SAH-92-1, SAH-92-2 (ADR-032 D6), one transaction: the appointment row is locked first (appointment row, then the
     * token counter inside the visit insert). ARRIVED is 409 appointment-already-checked-in with the visit and token;
     * CANCELLED or NOSHOW is 409 appointment-not-bookable; another day is 400 check-in-not-today. The token day is today in
     * IST. The panel is computed after the insert.
     */
    @Transactional
    public CheckIn checkIn(UUID tenantId, UUID actorUserId, String actorRole, UUID id) {
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can create a token.");
        }
        AppointmentRow appointment = appointmentRepository.findByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (ARRIVED.equals(appointment.status()) && appointment.visitId() != null) {
            int tokenNo = visitRepository.findById(tenantId, appointment.visitId()).map(VisitRow::tokenNo).orElse(0);
            throw OpdProblemException.appointmentAlreadyCheckedIn(appointment.visitId(), tokenNo);
        }
        if (appointment.visitId() != null || !BOOKED.equals(appointment.status())) {
            throw OpdProblemException.appointmentNotBookable();
        }
        if (!appointment.apptDate().equals(LocalDate.now(clock.withZone(INDIA)))) {
            throw OpdProblemException.checkInNotToday();
        }
        VisitView visit = visitService.issueToken(tenantId, actorUserId, actorRole,
                new VisitCreateInput(appointment.patientId(), appointment.doctorMembershipId()));
        appointmentRepository.setVisit(id, visit.id(), ARRIVED, actorUserId);
        AppointmentRow after = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_TOKEN_ISSUED", ENTITY_TYPE, id, Map.of("status", appointment.status()),
                Map.of("status", after.status(), "visitId", visit.id(), "tokenNo", visit.tokenNo()));
        DeskViews.TokenPanel panel = null;
        if (deskReads != null) {
            VisitRow visitRow = visitRepository.findById(tenantId, visit.id())
                    .orElseThrow(() -> new NotFoundException("Visit not found"));
            panel = deskReads.tokenPanel(tenantId, visitRow);
        }
        return new CheckIn(toView(tenantId, after), panel);
    }

    private AppointmentRow findRow(UUID tenantId, UUID id) {
        return appointmentRepository.findById(tenantId, id).orElseThrow(() -> new NotFoundException("Appointment not found"));
    }

    private AppointmentView toView(UUID tenantId, AppointmentRow row) {
        PatientSummary patient = patientLookup.find(tenantId, row.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = teamMemberPort.find(tenantId, row.doctorMembershipId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        return toView(tenantId, row, patient, doctor);
    }

    private AppointmentView toView(UUID tenantId, AppointmentRow row, PatientSummary patient, TeamMember doctor) {
        Integer tokenNo = null;
        if (row.visitId() != null) {
            tokenNo = visitRepository.findById(tenantId, row.visitId()).map(VisitRow::tokenNo).orElse(null);
        }
        return new AppointmentView(row.id(), patient.id(), patient.name(), patient.age(), patient.sex(),
                doctor.membershipId(), doctor.name(), row.apptDate(), row.apptTime(), row.status(), row.visitType(),
                row.source(), row.note(), row.visitId(), tokenNo, row.version());
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException | NullPointerException ex) {
            throw new InvalidInputException("apptTime", "Enter the slot time as HH:mm");
        }
    }

    /** Never the patient's or doctor's name (SAH-8/9/10 audit rule): only ids and business facts. */
    private static Map<String, Object> snapshot(AppointmentRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("patientId", row.patientId());
        values.put("doctorMembershipId", row.doctorMembershipId());
        values.put("apptDate", row.apptDate());
        values.put("apptTime", row.apptTime());
        values.put("status", row.status());
        values.put("visitType", row.visitType());
        values.put("source", row.source());
        return values;
    }
}
