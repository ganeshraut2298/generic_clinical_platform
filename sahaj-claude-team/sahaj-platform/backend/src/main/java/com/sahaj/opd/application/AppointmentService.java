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

    public AppointmentService(AppointmentRepository appointmentRepository, BookingRulesRepository bookingRulesRepository,
                               VisitRepository visitRepository, VisitService visitService,
                               PatientLookupPort patientLookup, TeamMemberPort teamMemberPort,
                               DoctorBlockedDates blockedDates, AuditLog auditLog, Clock clock) {
        this(appointmentRepository, bookingRulesRepository, visitRepository, visitService, patientLookup, teamMemberPort,
                blockedDates, auditLog, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AppointmentService(AppointmentRepository appointmentRepository, BookingRulesRepository bookingRulesRepository,
                               VisitRepository visitRepository, VisitService visitService,
                               PatientLookupPort patientLookup, TeamMemberPort teamMemberPort,
                               DoctorBlockedDates blockedDates, AuditLog auditLog, Clock clock,
                               DoctorSlotSettingsRepository slotSettings) {
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
        TeamMember doctor = teamMemberPort.find(tenantId, input.doctorMembershipId())
                .filter(member -> member.active() && DOCTOR_ROLES.contains(member.role()))
                .orElseThrow(() -> new NotFoundException("Doctor not found"));

        if (blockedDates.blocked(tenantId, doctor.membershipId(), input.apptDate(), input.apptDate())
                .contains(input.apptDate())) {
            throw new ConflictException("doctor-unavailable", "Doctor is not taking bookings that day",
                    "This doctor is not taking bookings on that date.");
        }

        BookingRulesRow rules = bookingRulesRepository.find(tenantId).orElse(BookingRulesService.DEFAULTS);

        if (!WRITE_ROLES.contains(actorRole)) {
            requireOutsideCloseBeforeWindow(rules, input.apptDate(), apptTime);
        }

        appointmentRepository.lockSlot(tenantId, input.doctorMembershipId(), input.apptDate(), apptTime);
        appointmentRepository.findActiveConflict(tenantId, input.doctorMembershipId(), input.apptDate(), apptTime)
                .ifPresent(existing -> {
                    if (!rules.overbook()) {
                        throw new ConflictException("appointment-slot-taken", "That slot is already booked",
                                "This doctor already has a booked or arrived patient in this slot.");
                    }
                    if (!WRITE_ROLES.contains(actorRole)) {
                        throw new AccessDeniedException("Only the owner or front desk can double-book a slot.");
                    }
                });

        UUID id = UUID.randomUUID();
        appointmentRepository.insert(id, patient.id(), doctor.membershipId(), input.apptDate(), apptTime,
                input.visitType(), input.source(), input.note(), actorUserId);
        AppointmentRow created = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_BOOKED", ENTITY_TYPE, id, null, snapshot(created));
        return toView(tenantId, created, patient, doctor);
    }

    /** AC2: a patient's booked slot refuses a new booking in the same slot within the close-before window, staff excepted. */
    private void requireOutsideCloseBeforeWindow(BookingRulesRow rules, LocalDate apptDate, LocalTime apptTime) {
        LocalDateTime slotAt = LocalDateTime.of(apptDate, apptTime);
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        if (slotAt.isBefore(now.plusHours(rules.closeBeforeHours()))) {
            throw new InvalidInputException("apptTime",
                    "Booking closes " + rules.closeBeforeHours() + " hour(s) before the slot");
        }
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
        List<SlotComputation.Session> sessions = settings.sessions().stream()
                .map(s -> new SlotComputation.Session(s.sessionIndex(), s.startTime(), s.endTime())).toList();
        LocalDate lastDay = from.plusDays(Math.max(days, 1) - 1L);
        Set<LocalDate> blocked = blockedDates.blocked(tenantId, doctorMembershipId, from, lastDay);
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        for (LocalDate day = from; !day.isAfter(lastDay) && out.size() < limit; day = day.plusDays(1)) {
            if (blocked.contains(day)) {
                continue;
            }
            for (SlotComputation.Slot slot : SlotComputation.slotsFor(day, settings.slotMinutes(),
                    new java.util.HashSet<>(settings.workingDays()), settings.walkInEveryNth(), sessions)) {
                if (slot.walkInReserved() || !LocalDateTime.of(day, slot.startTime()).isAfter(now)) {
                    continue;
                }
                if (appointmentRepository.findActiveConflict(tenantId, doctorMembershipId, day, slot.startTime())
                        .isEmpty()) {
                    out.add(new FreeSlot(day, slot.startTime()));
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
        List<SlotComputation.Session> sessions = settings.sessions().stream()
                .map(s -> new SlotComputation.Session(s.sessionIndex(), s.startTime(), s.endTime())).toList();
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        for (SlotComputation.Slot slot : SlotComputation.slotsFor(date, settings.slotMinutes(),
                new java.util.HashSet<>(settings.workingDays()), settings.walkInEveryNth(), sessions)) {
            if (slot.startTime().equals(time)) {
                return !slot.walkInReserved() && LocalDateTime.of(date, time).isAfter(now);
            }
        }
        return false;
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
     * slot was taken or the doctor is not taking bookings that day meanwhile (the sitting then keeps the date only).
     */
    @Transactional
    public Optional<BookedSlot> bookAt(UUID tenantId, UUID actorUserId, UUID patientId, UUID doctorMembershipId,
                                       LocalDate date, LocalTime time, String visitType, String source) {
        if (blockedDates.blocked(tenantId, doctorMembershipId, date, date).contains(date)) {
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
        AppointmentRow before = findRow(tenantId, id);
        if (!BOOKED.equals(before.status())) {
            throw new InvalidInputException("status", "Only a booked appointment can be cancelled or marked no-show");
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
        if (!WRITE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner or front desk can create a token.");
        }
        AppointmentRow appointment = findRow(tenantId, id);
        if (appointment.visitId() != null || !BOOKED.equals(appointment.status())) {
            throw new ConflictException("appointment-not-bookable", "This appointment cannot take a token",
                    "This appointment already has a token or is not booked.");
        }
        VisitView visit = visitService.issueToken(tenantId, actorUserId, actorRole,
                new VisitCreateInput(appointment.patientId(), appointment.doctorMembershipId()));
        appointmentRepository.setVisit(id, visit.id(), ARRIVED, actorUserId);
        AppointmentRow after = findRow(tenantId, id);
        auditLog.record("APPOINTMENT_TOKEN_ISSUED", ENTITY_TYPE, id, Map.of("status", appointment.status()),
                Map.of("status", after.status(), "visitId", visit.id(), "tokenNo", visit.tokenNo()));
        return toView(tenantId, after);
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
                row.source(), row.note(), row.visitId(), tokenNo);
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
