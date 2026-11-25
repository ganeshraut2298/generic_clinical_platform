package com.sahaj.opd.application;

import com.sahaj.opd.application.BookingPolicy.OpenSlot;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.DeskReadRepository.AppointmentCardRow;
import com.sahaj.opd.application.DeskReadRepository.DepartmentLink;
import com.sahaj.opd.application.DeskReadRepository.FollowUpCardRow;
import com.sahaj.opd.application.DeskReadRepository.RulesAndFees;
import com.sahaj.opd.application.DeskReadRepository.ToBookPage;
import com.sahaj.opd.application.DeskReadRepository.ToBookRow;
import com.sahaj.opd.application.DeskReadRepository.VisitCardRow;
import com.sahaj.opd.application.DeskReadRepository.VisitPart;
import com.sahaj.opd.application.DeskViews.Action;
import com.sahaj.opd.application.DeskViews.Board;
import com.sahaj.opd.application.DeskViews.Card;
import com.sahaj.opd.application.DeskViews.Column;
import com.sahaj.opd.application.DeskViews.Counters;
import com.sahaj.opd.application.DeskViews.DayCount;
import com.sahaj.opd.application.DeskViews.DaySlots;
import com.sahaj.opd.application.DeskViews.DepartmentSource;
import com.sahaj.opd.application.DeskViews.Doctor;
import com.sahaj.opd.application.DeskViews.Doctors;
import com.sahaj.opd.application.DeskViews.FollowUpState;
import com.sahaj.opd.application.DeskViews.GroupLabel;
import com.sahaj.opd.application.DeskViews.Kind;
import com.sahaj.opd.application.DeskViews.NextFree;
import com.sahaj.opd.application.DeskViews.Slot;
import com.sahaj.opd.application.DeskViews.SlotGroup;
import com.sahaj.opd.application.DeskViews.Snapshot;
import com.sahaj.opd.application.DeskViews.Tag;
import com.sahaj.opd.application.DeskViews.TodayStatus;
import com.sahaj.opd.application.DeskViews.TokenPanel;
import com.sahaj.opd.application.DeskViews.ToBook;
import com.sahaj.opd.application.DeskViews.ToBookItem;
import com.sahaj.opd.application.DeskViews.VisitFollowUp;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import com.sahaj.opd.application.WorkflowRepository.VersionRow;
import com.sahaj.opd.application.emr.EmrKeys;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-90 (ADR-032 D1, D3, D10, D11): the front desk console's read side. {@link #snapshot} composes the doctor rail, the
 * board, the counters and the follow-ups to book in one request with a fixed number of statements (no query per doctor,
 * slot or card): free slots are computed in memory by {@link BookingPolicy} from the slot settings, the taken set, the leave
 * set and the clock. Nothing is stored. Free counts follow the caller's role: staff (OWNER, FRONT_DESK) also count slots
 * inside the cut-off, other roles do not. Patient names are returned for the screen only and never logged.
 */
@Service
public class DeskReadService {

    static final ZoneId INDIA = VisitService.INDIA;
    static final int STRIP_DAYS = 7;
    static final int LEAVE_LOOKAHEAD_DAYS = 60;
    static final int NEXT_FREE_LOOKAHEAD_DAYS = 14;
    static final int MAX_DAY_SLOTS_AHEAD = 120;
    static final int MAX_CARDS = 2000;
    static final int MAX_TO_BOOK = 200;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> REASON_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR");
    private static final List<Column> COLUMN_ORDER = List.of(Column.BOOKED, Column.WAITING, Column.IN_CONSULTATION,
            Column.DONE);

    private final DeskReadRepository desk;
    private final DoctorSlotSettingsRepository slotSettings;
    private final TeamMemberPort teamMembers;
    private final DoctorBlockedDates blockedDates;
    private final PatientLookupPort patients;
    private final WorkflowRepository workflows;
    private final WorkflowService workflowService;
    private final ClinicSpecialtyPort clinicSpecialty;
    private final Clock clock;

    /** The visit queue for the token panel (SAH-92-2); null in a read-only unit test of the snapshot. */
    private final VisitRepository visits;

    public DeskReadService(DeskReadRepository desk, DoctorSlotSettingsRepository slotSettings,
                           TeamMemberPort teamMembers, DoctorBlockedDates blockedDates, PatientLookupPort patients,
                           WorkflowRepository workflows, WorkflowService workflowService,
                           ClinicSpecialtyPort clinicSpecialty, Clock clock) {
        this(desk, slotSettings, teamMembers, blockedDates, patients, workflows, workflowService, clinicSpecialty, clock,
                null);
    }

    @Autowired
    public DeskReadService(DeskReadRepository desk, DoctorSlotSettingsRepository slotSettings,
                           TeamMemberPort teamMembers, DoctorBlockedDates blockedDates, PatientLookupPort patients,
                           WorkflowRepository workflows, WorkflowService workflowService,
                           ClinicSpecialtyPort clinicSpecialty, Clock clock, VisitRepository visits) {
        this.visits = visits;
        this.desk = desk;
        this.slotSettings = slotSettings;
        this.teamMembers = teamMembers;
        this.blockedDates = blockedDates;
        this.patients = patients;
        this.workflows = workflows;
        this.workflowService = workflowService;
        this.clinicSpecialty = clinicSpecialty;
        this.clock = clock;
    }

    /** GET /desk/visits/{visitId}/token-panel: the current values for a visit of this clinic (404 otherwise). */
    @Transactional(readOnly = true)
    public TokenPanel tokenPanel(UUID tenantId, UUID visitId) {
        VisitRepository.VisitRow visit = visits.findById(tenantId, visitId)
                .orElseThrow(() -> new NotFoundException("Visit not found"));
        return tokenPanel(tenantId, visit);
    }

    /**
     * SAH-92-2 (ADR-032 D6): patients ahead = the same doctor's visits of the token day in WAITING or VITALS checked in
     * earlier (0 once this visit is past them); estimated wait = patients ahead x the doctor's slot length. The token day
     * is the visit's own creation day in IST. Names are returned for the screen and slip only.
     */
    @Transactional(readOnly = true)
    public TokenPanel tokenPanel(UUID tenantId, VisitRepository.VisitRow visit) {
        LocalDate tokenDate = visit.createdAt().atZone(INDIA).toLocalDate();
        PatientSummary patient = patients.find(tenantId, visit.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = teamMembers.find(tenantId, visit.doctorMembershipId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        int slotMinutes = slotSettings.find(tenantId, visit.doctorMembershipId())
                .orElse(DoctorSlotSettingsService.DEFAULTS).slotMinutes();
        boolean queued = "WAITING".equals(visit.stage()) || "VITALS".equals(visit.stage());
        int ahead = queued ? visits.countAhead(tenantId, visit.doctorMembershipId(), tokenDate, visit.createdAt(),
                visit.tokenNo()) : 0;
        DepartmentLink department = chooseDepartments(tenantId, desk.doctorDepartments(tenantId))
                .get(visit.doctorMembershipId());
        String room = department == null || department.rooms().isEmpty() ? null : String.join(", ", department.rooms());
        return new TokenPanel(visit.id(), visit.tokenNo(), tokenDate, patient.name(), doctor.membershipId(),
                doctor.name(), room, visit.stage(), visit.createdAt(), ahead, ahead * slotMinutes, slotMinutes);
    }

    /**
     * GET /desk/snapshot. {@code doctorMembershipId} and {@code date} select the finder day; the board filter is optional.
     * One read-only REPEATABLE READ transaction, so every statement sees the same moment (a card cannot show Booked for a
     * visit that the next statement already sees checked in).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(UUID tenantId, String role, UUID doctorMembershipId, LocalDate date,
                             UUID boardDoctorMembershipId) {
        Instant nowInstant = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, INDIA);
        LocalDate today = now.toLocalDate();
        boolean staff = BookingPolicy.isStaff(role);
        LocalDate finderDate = date == null ? today : date;
        if (doctorMembershipId != null && (finderDate.isBefore(today) || finderDate.isAfter(today.plusDays(STRIP_DAYS - 1L)))) {
            throw new InvalidInputException("date", "Choose a day from today to the next 6 days.");
        }

        RulesAndFees rulesAndFees = desk.rulesAndFees(tenantId);
        BookingRulesRow rules = rulesAndFees.rules() == null ? BookingRulesService.DEFAULTS : rulesAndFees.rules();
        String followUpFee = rulesAndFees.followUpFee() == null ? FeeSettingsService.DEFAULTS.followUpFee()
                : rulesAndFees.followUpFee();

        List<TeamMember> doctors = new ArrayList<>(teamMembers.findActiveDoctors(tenantId));
        doctors.sort(Comparator.comparing((TeamMember d) -> d.name() == null ? "" : d.name().toLowerCase())
                .thenComparing(TeamMember::membershipId));
        Map<UUID, TeamMember> doctorById = new HashMap<>();
        doctors.forEach(d -> doctorById.put(d.membershipId(), d));
        if (doctorMembershipId != null && !doctorById.containsKey(doctorMembershipId)) {
            throw new NotFoundException("Doctor not found");
        }
        if (boardDoctorMembershipId != null && !doctorById.containsKey(boardDoctorMembershipId)) {
            throw new NotFoundException("Doctor not found");
        }
        List<UUID> doctorIds = doctors.stream().map(TeamMember::membershipId).toList();

        Map<UUID, String> specialties = teamMembers.doctorSpecialties(tenantId, doctorIds);
        Map<UUID, SlotSettingsRow> plans = slotSettings.findAllByTenant(tenantId);
        Map<UUID, DepartmentLink> departments = chooseDepartments(tenantId, desk.doctorDepartments(tenantId));
        Map<UUID, Set<LocalDate>> leave = blockedDates.blockedForDoctors(tenantId, doctorIds, today,
                today.plusDays(LEAVE_LOOKAHEAD_DAYS));

        List<AppointmentCardRow> appointmentRows = desk.appointments(tenantId, today, today.plusDays(STRIP_DAYS - 1L));
        boolean rowsCut = appointmentRows.size() > DeskReadRepository.MAX_CARD_ROWS;
        if (rowsCut) {
            appointmentRows = appointmentRows.subList(0, DeskReadRepository.MAX_CARD_ROWS);
        }
        Map<UUID, Map<LocalDate, Set<LocalTime>>> taken = takenOf(appointmentRows);

        // ---- the doctor rail ----
        List<Doctor> rail = new ArrayList<>();
        int freeTodayAll = 0;
        for (TeamMember member : doctors) {
            SlotSettingsRow plan = plans.getOrDefault(member.membershipId(), DoctorSlotSettingsService.DEFAULTS);
            Set<LocalDate> doctorLeave = leave.getOrDefault(member.membershipId(), Set.of());
            Map<LocalDate, Set<LocalTime>> doctorTaken = taken.getOrDefault(member.membershipId(), Map.of());
            Doctor row = railRow(member, plan, rules, followUpFee, specialties.get(member.membershipId()),
                    departments.get(member.membershipId()), doctorLeave, doctorTaken, now, today, staff);
            freeTodayAll += row.freeToday();
            rail.add(row);
        }

        // ---- the board and the counters ----
        List<VisitCardRow> walkIns = desk.walkInVisits(tenantId, today.atStartOfDay(INDIA).toInstant(),
                today.plusDays(1).atStartOfDay(INDIA).toInstant(), today);
        if (walkIns.size() > DeskReadRepository.MAX_CARD_ROWS) {
            rowsCut = true;
            walkIns = walkIns.subList(0, DeskReadRepository.MAX_CARD_ROWS);
        }
        Map<UUID, FollowUpCardRow> followUps = new HashMap<>();
        for (FollowUpCardRow f : desk.followUpsOfVisits(tenantId, today.atStartOfDay(INDIA).toInstant(),
                today.plusDays(1).atStartOfDay(INDIA).toInstant())) {
            followUps.put(f.visitId(), f);
        }
        ToBookPage toBook = desk.followUpsToBook(tenantId, today, MAX_TO_BOOK);

        Set<UUID> patientIds = new HashSet<>();
        appointmentRows.stream().filter(a -> a.apptDate().equals(today)).forEach(a -> patientIds.add(a.patientId()));
        walkIns.forEach(w -> patientIds.add(w.patientId()));
        toBook.rows().forEach(r -> patientIds.add(r.patientId()));
        Map<UUID, PatientSummary> patientById = patientIds.isEmpty() ? Map.of() : patients.findAll(tenantId, patientIds);

        Map<UUID, String> extraDoctorNames = missingDoctorNames(tenantId, doctorById, appointmentRows, today, walkIns,
                toBook);
        WorkflowStages stages = new WorkflowStages(tenantId);

        List<Card> allCards = new ArrayList<>();
        for (AppointmentCardRow row : appointmentRows) {
            if (row.apptDate().equals(today)) {
                allCards.add(appointmentCard(row, now, rules, leave.getOrDefault(row.doctorMembershipId(), Set.of()),
                        patientById, doctorName(row.doctorMembershipId(), doctorById, extraDoctorNames), stages,
                        followUps.get(row.visit() == null ? null : row.visit().id())));
            }
        }
        for (VisitCardRow row : walkIns) {
            allCards.add(walkInCard(row, patientById, doctorName(row.doctorMembershipId(), doctorById, extraDoctorNames),
                    stages, followUps.get(row.visit().id())));
        }
        Counters counters = counters(allCards, freeTodayAll);
        Board board = board(today, allCards, boardDoctorMembershipId, rowsCut);

        ToBook toBookView = toBookView(toBook, today, role, patientById, doctorById, extraDoctorNames);

        DaySlots selected = null;
        if (doctorMembershipId != null) {
            SlotSettingsRow plan = plans.getOrDefault(doctorMembershipId, DoctorSlotSettingsService.DEFAULTS);
            LocalDate lookaheadEnd = lookaheadEnd(today, finderDate);
            Map<LocalDate, Set<LocalTime>> doctorTaken = desk.takenSlots(tenantId, doctorMembershipId, finderDate,
                    lookaheadEnd);
            selected = daySlots(doctorMembershipId, plan, rules.closeBeforeHours(), finderDate, today, now, staff,
                    leave.getOrDefault(doctorMembershipId, Set.of()), doctorTaken);
        }
        return new Snapshot(nowInstant, today, rules, followUpFee, counters, new Doctors(doctors.size(), rail), board,
                toBookView, selected);
    }

    /** GET /desk/doctors/{id}/day-slots: one doctor's open slots on a day (today to today + 120), for the caller's role. */
    @Transactional(readOnly = true)
    public DaySlots daySlots(UUID tenantId, String role, UUID doctorMembershipId, LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(INDIA));
        LocalDate today = now.toLocalDate();
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAY_SLOTS_AHEAD))) {
            throw new InvalidInputException("date", "Choose a day from today to 120 days ahead.");
        }
        teamMembers.find(tenantId, doctorMembershipId)
                .filter(m -> m.active() && ("DOCTOR".equals(m.role()) || "VISITING_DOCTOR".equals(m.role())))
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        RulesAndFees rulesAndFees = desk.rulesAndFees(tenantId);
        BookingRulesRow rules = rulesAndFees.rules() == null ? BookingRulesService.DEFAULTS : rulesAndFees.rules();
        SlotSettingsRow plan = slotSettings.find(tenantId, doctorMembershipId).orElse(DoctorSlotSettingsService.DEFAULTS);
        LocalDate end = lookaheadEnd(today, date);
        Set<LocalDate> leave = blockedDates.blocked(tenantId, doctorMembershipId, date, end);
        Map<LocalDate, Set<LocalTime>> taken = desk.takenSlots(tenantId, doctorMembershipId, date, end);
        return daySlots(doctorMembershipId, plan, rules.closeBeforeHours(), date, today, now,
                BookingPolicy.isStaff(role), leave, taken);
    }

    // ------------------------------------------------------------------------------------------ slots

    private static LocalDate lookaheadEnd(LocalDate today, LocalDate date) {
        LocalDate end = date.plusDays(NEXT_FREE_LOOKAHEAD_DAYS);
        LocalDate cap = today.plusDays(MAX_DAY_SLOTS_AHEAD);
        return end.isAfter(cap) ? cap : end;
    }

    static DaySlots daySlots(UUID doctorId, SlotSettingsRow plan, int clinicClose, LocalDate date, LocalDate today,
                             LocalDateTime now, boolean staff, Set<LocalDate> leave,
                             Map<LocalDate, Set<LocalTime>> taken) {
        boolean onLeave = leave.contains(date);
        List<OpenSlot> open = BookingPolicy.openSlots(plan, clinicClose, date, now, staff, onLeave,
                taken.getOrDefault(date, Set.of()));
        Map<GroupLabel, List<Slot>> byLabel = new EnumMap<>(GroupLabel.class);
        for (OpenSlot slot : open) {
            GroupLabel label = labelOf(plan, slot.sessionIndex());
            byLabel.computeIfAbsent(label, l -> new ArrayList<>()).add(new Slot(slot.time().format(HHMM), slot.insideCutOff()));
        }
        List<SlotGroup> groups = new ArrayList<>();
        byLabel.forEach((label, slots) -> groups.add(new SlotGroup(label, slots)));
        LocalDate cap = today.plusDays(MAX_DAY_SLOTS_AHEAD);
        LocalDate nextFree = null;
        for (int i = 1; i <= NEXT_FREE_LOOKAHEAD_DAYS; i++) {
            LocalDate day = date.plusDays(i);
            if (day.isAfter(cap)) {
                break;
            }
            if (!BookingPolicy.openSlots(plan, clinicClose, day, now, staff, leave.contains(day),
                    taken.getOrDefault(day, Set.of())).isEmpty()) {
                nextFree = day;
                break;
            }
        }
        return new DaySlots(doctorId, date, BookingPolicy.working(plan, date), onLeave, plan.slotMinutes(),
                BookingPolicy.effectiveCloseBefore(plan.closeBeforeHours(), clinicClose), open.size(), groups, nextFree);
    }

    /** Morning for a session starting before 12:00, else Evening (ADR-032 D3). */
    private static GroupLabel labelOf(SlotSettingsRow plan, int sessionIndex) {
        for (SessionRow session : plan.sessions()) {
            if (session.sessionIndex() == sessionIndex) {
                return BookingPolicy.morning(session.startTime()) ? GroupLabel.MORNING : GroupLabel.EVENING;
            }
        }
        return GroupLabel.EVENING;
    }

    private static Map<UUID, Map<LocalDate, Set<LocalTime>>> takenOf(List<AppointmentCardRow> rows) {
        Map<UUID, Map<LocalDate, Set<LocalTime>>> taken = new HashMap<>();
        for (AppointmentCardRow row : rows) {
            if ("BOOKED".equals(row.status()) || "ARRIVED".equals(row.status())) {
                taken.computeIfAbsent(row.doctorMembershipId(), d -> new HashMap<>())
                        .computeIfAbsent(row.apptDate(), d -> new HashSet<>()).add(row.apptTime());
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------------------------------ the rail

    private Doctor railRow(TeamMember member, SlotSettingsRow plan, BookingRulesRow rules, String followUpFee,
                           String specialty, DepartmentLink department, Set<LocalDate> leave,
                           Map<LocalDate, Set<LocalTime>> taken, LocalDateTime now, LocalDate today, boolean staff) {
        List<DayCount> days = new ArrayList<>();
        NextFree nextFree = null;
        int freeToday = 0;
        for (int i = 0; i < STRIP_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            boolean onLeave = leave.contains(day);
            int free = BookingPolicy.openSlots(plan, rules.closeBeforeHours(), day, now, staff, onLeave,
                    taken.getOrDefault(day, Set.of())).size();
            days.add(new DayCount(day, free, BookingPolicy.working(plan, day), onLeave));
            if (i == 0) {
                freeToday = free;
            }
            if (nextFree == null && free > 0) {
                nextFree = new NextFree(day, free);
            }
        }
        TodayStatus status = status(plan, leave, today, now, freeToday);
        String startsAt = null;
        if (status == TodayStatus.STARTS_LATER) {
            startsAt = nextSessionStart(plan, now).format(HHMM);
        }
        LocalDate leaveUntil = status == TodayStatus.ON_LEAVE ? leaveUntil(leave, today) : null;

        String departmentName;
        DepartmentSource source;
        String room = null;
        if (department != null) {
            departmentName = department.name();
            source = DepartmentSource.EMR_DEPARTMENT;
            room = department.rooms().isEmpty() ? null : String.join(", ", department.rooms());
        } else if (specialty != null) {
            departmentName = specialty;
            source = DepartmentSource.SPECIALTY;
        } else {
            departmentName = null;
            source = DepartmentSource.NONE;
        }
        long fee = member.feePaise();
        long followUpFeePaise = switch (followUpFee) {
            case "free" -> 0L;
            case "half" -> fee / 2;
            default -> fee;
        };
        return new Doctor(member.membershipId(), member.name(), departmentName, source, room, plan.slotMinutes(), fee,
                followUpFeePaise, BookingPolicy.effectiveCloseBefore(plan.closeBeforeHours(), rules.closeBeforeHours()),
                status, startsAt, leaveUntil, freeToday, nextFree, days);
    }

    /** ADR-032 D10, first match wins: ON_LEAVE, OFF_TODAY, FULL_TODAY, IN_OPD, STARTS_LATER. */
    static TodayStatus status(SlotSettingsRow plan, Set<LocalDate> leave, LocalDate today, LocalDateTime now,
                              int freeToday) {
        if (leave.contains(today)) {
            return TodayStatus.ON_LEAVE;
        }
        if (!BookingPolicy.working(plan, today)) {
            return TodayStatus.OFF_TODAY;
        }
        if (freeToday == 0) {
            return TodayStatus.FULL_TODAY;
        }
        LocalTime time = now.toLocalTime();
        for (SessionRow session : plan.sessions()) {
            if (!time.isBefore(session.startTime()) && time.isBefore(session.endTime())) {
                return TodayStatus.IN_OPD;
            }
        }
        for (SessionRow session : plan.sessions()) {
            if (session.startTime().isAfter(time)) {
                return TodayStatus.STARTS_LATER;
            }
        }
        return TodayStatus.FULL_TODAY;
    }

    private static LocalTime nextSessionStart(SlotSettingsRow plan, LocalDateTime now) {
        LocalTime best = null;
        for (SessionRow session : plan.sessions()) {
            if (session.startTime().isAfter(now.toLocalTime()) && (best == null || session.startTime().isBefore(best))) {
                best = session.startTime();
            }
        }
        return best;
    }

    private static LocalDate leaveUntil(Set<LocalDate> leave, LocalDate today) {
        LocalDate last = today;
        for (int i = 1; i <= LEAVE_LOOKAHEAD_DAYS && leave.contains(today.plusDays(i)); i++) {
            last = today.plusDays(i);
        }
        return last;
    }

    /** ADR-032 D10: one assigned department; several, the clinic default's starter else the first by name. */
    private Map<UUID, DepartmentLink> chooseDepartments(UUID tenantId, List<DepartmentLink> links) {
        Map<UUID, List<DepartmentLink>> byDoctor = new LinkedHashMap<>();
        for (DepartmentLink link : links) {
            byDoctor.computeIfAbsent(link.doctorMembershipId(), d -> new ArrayList<>()).add(link);
        }
        String clinicDefault = null;
        Map<UUID, DepartmentLink> chosen = new HashMap<>();
        for (Map.Entry<UUID, List<DepartmentLink>> entry : byDoctor.entrySet()) {
            List<DepartmentLink> assigned = entry.getValue();
            DepartmentLink pick = assigned.get(0);
            if (assigned.size() > 1) {
                if (clinicDefault == null) {
                    clinicDefault = EmrKeys.clinicDefaultStarter(clinicSpecialty.specialtyCode(tenantId).orElse(null));
                }
                for (DepartmentLink link : assigned) {
                    if (clinicDefault.equals(link.starterKey())) {
                        pick = link;
                        break;
                    }
                }
            }
            chosen.put(entry.getKey(), pick);
        }
        return chosen;
    }

    // ------------------------------------------------------------------------------------------ the board

    private Card appointmentCard(AppointmentCardRow row, LocalDateTime now, BookingRulesRow rules, Set<LocalDate> leave,
                                 Map<UUID, PatientSummary> patientById, String doctorName, WorkflowStages stages,
                                 FollowUpCardRow followUp) {
        VisitPart visit = row.visit();
        PatientSummary patient = patientById.get(row.patientId());
        Set<Tag> tags = EnumSet.noneOf(Tag.class);
        Column column;
        String stage = null;
        String nextStage = null;
        if (visit == null) {
            column = Column.BOOKED;
            LocalDateTime at = LocalDateTime.of(row.apptDate(), row.apptTime());
            LocalDateTime graceEnds = at.plusMinutes(rules.noShowAfterMinutes());
            boolean booked = "BOOKED".equals(row.status());
            if ("NOSHOW".equals(row.status()) || (booked && !now.isBefore(graceEnds))) {
                tags.add(Tag.NO_SHOW);
            } else if (booked && !now.isBefore(at)) {
                tags.add(Tag.DUE_NOW);
            }
            if (booked && leave.contains(row.apptDate())) {
                tags.add(Tag.DOCTOR_ON_LEAVE);
            }
        } else {
            column = columnOf(visit.stage());
            stage = visit.stage();
            nextStage = stages.next(visit);
            if ("VITALS".equals(visit.stage())) {
                tags.add(Tag.VITALS);
            }
        }
        VisitFollowUp followUpView = column == Column.DONE ? followUpView(followUp) : null;
        Action action = action(column, tags, followUpView);
        return new Card(row.id(), Kind.APPOINTMENT, row.id(), row.version(), visit == null ? null : visit.id(), column,
                row.apptTime().format(HHMM), row.patientId(), patient == null ? "Unknown patient" : patient.name(),
                row.doctorMembershipId(), doctorName, visit == null ? row.visitType() : visit.visitType(), row.status(),
                stage, nextStage, visit == null ? null : visit.tokenNo(), visit == null ? null : visit.createdAt(),
                visit == null ? null : visit.consultStartedAt(), visit == null ? null : visit.consultEndedAt(), tags,
                action, followUpView);
    }

    private Card walkInCard(VisitCardRow row, Map<UUID, PatientSummary> patientById, String doctorName,
                            WorkflowStages stages, FollowUpCardRow followUp) {
        VisitPart visit = row.visit();
        PatientSummary patient = patientById.get(row.patientId());
        Column column = columnOf(visit.stage());
        Set<Tag> tags = EnumSet.noneOf(Tag.class);
        if ("VITALS".equals(visit.stage())) {
            tags.add(Tag.VITALS);
        }
        VisitFollowUp followUpView = column == Column.DONE ? followUpView(followUp) : null;
        return new Card(visit.id(), Kind.WALK_IN, null, null, visit.id(), column,
                LocalDateTime.ofInstant(visit.createdAt(), INDIA).toLocalTime().format(HHMM), row.patientId(),
                patient == null ? "Unknown patient" : patient.name(), row.doctorMembershipId(), doctorName,
                visit.visitType(), null, visit.stage(), stages.next(visit), visit.tokenNo(), visit.createdAt(),
                visit.consultStartedAt(), visit.consultEndedAt(), tags, action(column, tags, followUpView), followUpView);
    }

    static Column columnOf(String stage) {
        return switch (stage) {
            case "WAITING", "VITALS" -> Column.WAITING;
            case "WITH_DOCTOR" -> Column.IN_CONSULTATION;
            default -> Column.DONE;
        };
    }

    /** ADR-032 D11: by state only; the client shows write actions to OWNER and FRONT_DESK. */
    static Action action(Column column, Set<Tag> tags, VisitFollowUp followUp) {
        return switch (column) {
            case BOOKED -> tags.contains(Tag.NO_SHOW) ? Action.REBOOK
                    : tags.contains(Tag.DOCTOR_ON_LEAVE) ? Action.MOVE_TO_NEW_SLOT : Action.CHECK_IN;
            case WAITING -> Action.START_CONSULTATION;
            case IN_CONSULTATION -> Action.COMPLETE_VISIT;
            case DONE -> followUp == null || followUp.state() == FollowUpState.NONE
                    || followUp.state() == FollowUpState.OPEN ? Action.SET_FOLLOW_UP : Action.NONE;
        };
    }

    private static VisitFollowUp followUpView(FollowUpCardRow row) {
        if (row == null) {
            return new VisitFollowUp(null, FollowUpState.NONE, null, null, null, null);
        }
        FollowUpState state = row.returnedVisitId() != null ? FollowUpState.RETURNED
                : row.notNeeded() ? FollowUpState.NOT_NEEDED
                : row.appointmentId() != null ? FollowUpState.BOOKED : FollowUpState.OPEN;
        return new VisitFollowUp(row.id(), state, row.dueDate(), row.appointmentId(), row.apptDate(),
                row.apptTime() == null ? null : row.apptTime().format(HHMM));
    }

    /** Today's counters over every card, never filtered (ADR-032 D11). */
    static Counters counters(List<Card> cards, int freeToday) {
        int booked = 0;
        int waiting = 0;
        int inConsult = 0;
        int done = 0;
        for (Card card : cards) {
            switch (card.column()) {
                case BOOKED -> {
                    if (!card.tags().contains(Tag.NO_SHOW)) {
                        booked++;
                    }
                }
                case WAITING -> waiting++;
                case IN_CONSULTATION -> inConsult++;
                default -> done++;
            }
        }
        return new Counters(booked, waiting, inConsult, done, freeToday);
    }

    /** The first 2,000 cards by time, then grouped by column, earliest first within a column. */
    static Board board(LocalDate today, List<Card> allCards, UUID boardDoctor) {
        return board(today, allCards, boardDoctor, false);
    }

    /** As above; {@code rowsCut} (a read hit its row limit, SAH-90 security F4) also sets {@code truncated}. */
    static Board board(LocalDate today, List<Card> allCards, UUID boardDoctor, boolean rowsCut) {
        List<Card> cards = new ArrayList<>();
        for (Card card : allCards) {
            if (boardDoctor == null || boardDoctor.equals(card.doctorMembershipId())) {
                cards.add(card);
            }
        }
        Comparator<Card> byTime = Comparator.comparing(Card::time)
                .thenComparing(c -> c.tokenNo() == null ? Integer.MAX_VALUE : c.tokenNo())
                .thenComparing(Card::key);
        cards.sort(byTime);
        boolean overCards = cards.size() > MAX_CARDS;
        boolean truncated = overCards || rowsCut;
        if (overCards) {
            cards = new ArrayList<>(cards.subList(0, MAX_CARDS));
        }
        cards.sort(Comparator.comparingInt((Card c) -> COLUMN_ORDER.indexOf(c.column())).thenComparing(byTime));
        return new Board(today, truncated, cards);
    }

    private ToBook toBookView(ToBookPage page, LocalDate today, String role, Map<UUID, PatientSummary> patientById,
                              Map<UUID, TeamMember> doctorById, Map<UUID, String> extraNames) {
        boolean showReason = role != null && REASON_ROLES.contains(role);
        List<ToBookItem> items = new ArrayList<>();
        for (ToBookRow row : page.rows()) {
            PatientSummary patient = patientById.get(row.patientId());
            boolean overdue = row.dueDate().isBefore(today);
            String reason = row.fromDischarge() && !showReason ? FollowUpService.MASKED_DISCHARGE_REASON : row.reason();
            items.add(new ToBookItem(row.id(), row.patientId(), patient == null ? "Unknown patient" : patient.name(),
                    row.doctorMembershipId(), doctorName(row.doctorMembershipId(), doctorById, extraNames),
                    row.dueDate(), overdue ? "MISSED" : "DUE",
                    overdue ? (int) ChronoUnit.DAYS.between(row.dueDate(), today) : 0, reason));
        }
        return new ToBook(page.total(), items);
    }

    private static String doctorName(UUID id, Map<UUID, TeamMember> doctorById, Map<UUID, String> extraNames) {
        TeamMember member = doctorById.get(id);
        if (member != null) {
            return member.name();
        }
        return extraNames.getOrDefault(id, "");
    }

    /** Names of doctors who are no longer active but still have cards or follow-ups: one call, only when needed. */
    private Map<UUID, String> missingDoctorNames(UUID tenantId, Map<UUID, TeamMember> doctorById,
                                                  List<AppointmentCardRow> appointments, LocalDate today,
                                                  List<VisitCardRow> walkIns, ToBookPage toBook) {
        Set<UUID> missing = new HashSet<>();
        appointments.stream().filter(a -> a.apptDate().equals(today)).map(AppointmentCardRow::doctorMembershipId)
                .filter(id -> !doctorById.containsKey(id)).forEach(missing::add);
        walkIns.stream().map(VisitCardRow::doctorMembershipId).filter(id -> !doctorById.containsKey(id))
                .forEach(missing::add);
        toBook.rows().stream().map(ToBookRow::doctorMembershipId).filter(id -> !doctorById.containsKey(id))
                .forEach(missing::add);
        Map<UUID, String> names = new HashMap<>();
        if (!missing.isEmpty()) {
            teamMembers.findAll(tenantId, missing).forEach((id, member) -> names.put(id, member.name()));
        }
        return names;
    }

    /** The enabled stages of each workflow version that today's visits were issued under (one read, then in memory). */
    private final class WorkflowStages {

        private final UUID tenantId;
        private Map<Integer, List<String>> byVersion;

        WorkflowStages(UUID tenantId) {
            this.tenantId = tenantId;
        }

        String next(VisitPart visit) {
            if (byVersion == null) {
                byVersion = new HashMap<>();
                for (VersionRow row : workflows.listVersions(tenantId)) {
                    byVersion.put(row.versionNo(), VisitService.enabledStages(row.steps()));
                }
            }
            List<String> enabled = byVersion.get(visit.workflowVersionNo());
            if (enabled == null) {
                enabled = VisitService.enabledStages(workflowService.currentSteps(tenantId));
                byVersion.put(visit.workflowVersionNo(), enabled);
            }
            int index = enabled.indexOf(visit.stage());
            return index >= 0 && index + 1 < enabled.size() ? enabled.get(index + 1) : null;
        }
    }
}
