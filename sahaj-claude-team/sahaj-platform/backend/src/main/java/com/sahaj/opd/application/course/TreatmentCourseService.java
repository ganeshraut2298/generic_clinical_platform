package com.sahaj.opd.application.course;

import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.opd.api.TreatmentCoursePort.SittingFacts;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.CourseInputs.AddSittingInput;
import com.sahaj.opd.application.course.CourseInputs.ChangeSittingInput;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseInputs.LinkVisitInput;
import com.sahaj.opd.application.course.CourseInputs.SittingPlanInput;
import com.sahaj.opd.application.course.CourseKinds.AddOption;
import com.sahaj.opd.application.course.CourseKinds.Kind;
import com.sahaj.opd.application.course.CourseViews.AddOptionView;
import com.sahaj.opd.application.course.CourseViews.Changed;
import com.sahaj.opd.application.course.CourseViews.CourseView;
import com.sahaj.opd.application.course.CourseViews.SittingView;
import com.sahaj.opd.application.course.CourseViews.SummaryView;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.DentalCoursePlanner;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleOffException;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The treatment course plan (SAH-58; ADR-022 D12): a patient's multi-visit course, its sittings, the add choices of each
 * kind, and the links to an OPD visit or an IPD admission. Money is not here: payments, receipts and credit are
 * {@code billing}'s (it reads the plan through {@code opd.api.TreatmentCoursePort}).
 *
 * <p>Who: reads are for OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK and BILLING (the controller and this class both check);
 * create, add, change and link need {@code clinic.consultation.write} and, for an existing course, the course's own doctor
 * or an owner with doctor details (403 not-own-patient). A sitting's status is computed on every read, never stored: DONE
 * when its visit's record is signed (SAH-51) or its admission is discharged, TODAY when its visit was made today or its
 * admission is still admitted, else PLANNED. Adds, changes and links run under the course row lock, so two adds at once
 * are both kept and numbered in order; the same Idempotency-Key makes one change. Audits carry ids, dates, kinds and money
 * only: never the items or notes. This class never logs.
 */
@Service
public class TreatmentCourseService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String ENTITY = "TREATMENT_COURSE";
    private static final Set<String> READ_ROLES = Set.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING");
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");
    private static final Set<String> ADMIT_ROLES = Set.of("OWNER", "FRONT_DESK");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final long MAX_CHARGE = 10_000_000_000L;
    private static final int MAX_SITTINGS = 50;
    private static final int MAX_LIST = 200;

    private final EmrAccess access;
    private final EmrRecordService records;
    private final TreatmentCourseRepository courses;
    private final VisitRepository visits;
    private final EmrRecordRepository emrRecords;
    private final TeamMemberPort team;
    private final ModuleEntitlementPort entitlements;
    private final ObjectProvider<CourseAdmissionSource> admissionSource;
    private final AuditLog auditLog;
    private final Clock clock;
    /** ADR-023 D7: builds the sittings of a dental course from the record's plan; null in a plain unit test. */
    private final DentalCoursePlanner planner;

    public TreatmentCourseService(EmrAccess access, EmrRecordService records, TreatmentCourseRepository courses,
                                  VisitRepository visits, EmrRecordRepository emrRecords, TeamMemberPort team,
                                  ModuleEntitlementPort entitlements,
                                  ObjectProvider<CourseAdmissionSource> admissionSource, AuditLog auditLog,
                                  Clock clock) {
        this(access, records, courses, visits, emrRecords, team, entitlements, admissionSource, auditLog, clock, null);
    }

    @Autowired
    public TreatmentCourseService(EmrAccess access, EmrRecordService records, TreatmentCourseRepository courses,
                                  VisitRepository visits, EmrRecordRepository emrRecords, TeamMemberPort team,
                                  ModuleEntitlementPort entitlements,
                                  ObjectProvider<CourseAdmissionSource> admissionSource, AuditLog auditLog,
                                  Clock clock, DentalCoursePlanner planner) {
        this.planner = planner;
        this.access = access;
        this.records = records;
        this.courses = courses;
        this.visits = visits;
        this.emrRecords = emrRecords;
        this.team = team;
        this.entitlements = entitlements;
        this.admissionSource = admissionSource;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public List<SummaryView> list(ClinicUser user, UUID patientId) {
        requireRead(user);
        if (patientId == null) {
            throw invalid("patientId", "Choose a patient.");
        }
        UUID tenantId = user.tenantId();
        List<CourseRow> rows = courses.forPatient(tenantId, patientId, MAX_LIST);
        Map<UUID, List<SittingRow>> sittings = group(courses.sittingsOf(tenantId, ids(rows)));
        List<SummaryView> result = new ArrayList<>();
        for (CourseRow row : rows) {
            Computed computed = compute(tenantId, sittings.getOrDefault(row.id(), List.of()));
            result.add(summary(row, computed));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public CourseView get(ClinicUser user, UUID courseId) {
        requireRead(user);
        CourseRow row = courses.find(user.tenantId(), courseId).orElseThrow(TreatmentCourseService::courseNotFound);
        return view(user, row);
    }

    // ---- create ----

    @Transactional
    public Changed create(ClinicUser user, String idempotencyKey, CreateInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        requireKey(idempotencyKey);
        Validated valid = validateCreate(input);
        courses.lockIdempotency(tenantId, idempotencyKey);
        boolean fromPlan = Boolean.TRUE.equals(input.fromPlan());
        String hash = fromPlan
                ? hash(String.valueOf(input.fromRecordId()), input.kind(), nullToEmpty(valid.name),
                        nullToEmpty(valid.note), planDigest(valid.plans), "fromPlan")
                : hash(String.valueOf(input.fromRecordId()), input.kind(), nullToEmpty(valid.name),
                        nullToEmpty(valid.note), planDigest(valid.plans));
        Optional<CourseRow> earlier = courses.findByIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isPresent()) {
            if (!hash.equals(earlier.get().requestHash())) {
                throw idempotencyConflict();
            }
            return new Changed(false, view(user, earlier.get()));
        }
        Authorized authorized = records.authorize(user, input.fromRecordId());
        if (authorized.patientId() == null) {
            throw invalid("fromRecordId", "Start a course from an EMR that has a patient.");
        }
        Kind kind = CourseKinds.kind(input.kind()).orElseThrow();
        if (fromPlan) {
            planFromRecord(valid, authorized);
        }
        if (valid.plans.stream().anyMatch(p -> CourseKinds.isAdmission(p.option.encounterKind())) && ipdOff(tenantId)) {
            throw moduleOff();
        }
        int courseNo = courses.nextCourseNo(tenantId);
        UUID courseId = courses.insertCourse(tenantId,
                new NewCourse(courseNo, authorized.patientId(), kind.kind(), kind.unit(),
                        valid.name == null ? kind.defaultName() : valid.name, valid.note, member.membershipId(),
                        LocalDate.now(clock.withZone(INDIA)), authorized.row().id(), authorized.row().createdAt(),
                        idempotencyKey, hash), user.userId());
        if (fromPlan) {
            planner.linkCourse(tenantId, user.userId(), authorized.row(), courseId, member.membershipId(),
                    clock.instant());
        }
        int number = 1;
        long estimate = 0;
        for (Plan plan : valid.plans) {
            String encounter = plan.option.encounterKind();
            String note = plan.note == null && CourseKinds.isAdmission(encounter)
                    ? CourseKinds.ADMISSION_REQUEST_NOTE : plan.note;
            courses.insertSitting(tenantId, new NewSitting(courseId, number++, encounter, plan.option.storedKey(),
                    plan.plannedDate, plan.items, plan.chargePaise, note, null, null), user.userId());
            estimate += plan.chargePaise;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("courseNo", courseNo(courseNo));
        after.put("kind", kind.kind());
        after.put("sittingCount", valid.plans.size());
        after.put("estimatePaise", estimate);
        auditLog.record("TREATMENT_COURSE_CREATED", ENTITY, courseId, null, after);
        return new Changed(true, view(user, courses.find(tenantId, courseId).orElseThrow()));
    }

    // ---- sittings ----

    @Transactional
    public Changed addSitting(ClinicUser user, UUID courseId, String idempotencyKey, AddSittingInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        requireKey(idempotencyKey);
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        if (input.addOptionKey() == null || input.addOptionKey().isBlank()) {
            throw invalid("addOptionKey", "Choose what to add.");
        }
        CourseRow course = courses.findForUpdate(tenantId, courseId).orElseThrow(TreatmentCourseService::courseNotFound);
        requireCourseDoctor(user, member, course);
        String hash = hash(courseId.toString(), input.addOptionKey().toLowerCase(java.util.Locale.ROOT),
                String.valueOf(input.plannedDate()));
        Optional<SittingRow> earlier = courses.sittingByIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isPresent()) {
            if (!courseId.equals(earlier.get().courseId()) || !hash.equals(earlier.get().requestHash())) {
                throw idempotencyConflict();
            }
            return new Changed(false, view(user, course));
        }
        AddOption option = CourseKinds.option(course.kind(), input.addOptionKey())
                .orElseThrow(() -> invalid("addOptionKey", "Choose one of the options for this course."));
        if (CourseKinds.isAdmission(option.encounterKind()) && ipdOff(tenantId)) {
            throw moduleOff();
        }
        List<SittingRow> existing = group(courses.sittingsOf(tenantId, List.of(courseId))).getOrDefault(courseId, List.of());
        if (existing.size() >= MAX_SITTINGS) {
            throw invalid("addOptionKey", "A course can have at most 50 entries.");
        }
        boolean admission = CourseKinds.isAdmission(option.encounterKind());
        long charge = admission || existing.isEmpty() ? 0 : existing.get(existing.size() - 1).chargePaise();
        int number = courses.nextSittingNo(tenantId, courseId);
        courses.insertSitting(tenantId, new NewSitting(courseId, number, option.encounterKind(), option.storedKey(),
                input.plannedDate(), List.of(), charge, admission ? CourseKinds.ADMISSION_REQUEST_NOTE : null,
                idempotencyKey, hash), user.userId());
        courses.bumpRevision(tenantId, courseId, user.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sittingNo", number);
        after.put("encounterKind", option.encounterKind());
        after.put("plannedDate", input.plannedDate() == null ? null : input.plannedDate().toString());
        after.put("chargePaise", charge);
        auditLog.record("COURSE_SITTING_ADDED", ENTITY, courseId, null, after);
        return new Changed(true, view(user, courses.find(tenantId, courseId).orElseThrow()));
    }

    @Transactional
    public CourseView changeSitting(ClinicUser user, UUID courseId, UUID sittingId, ChangeSittingInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        CourseRow course = courses.findForUpdate(tenantId, courseId).orElseThrow(TreatmentCourseService::courseNotFound);
        requireCourseDoctor(user, member, course);
        SittingRow sitting = sittingOf(tenantId, courseId, sittingId);
        SittingChange change = validateChange(input);
        requireRevision(course, input.revision());
        Computed computed = compute(tenantId, List.of(sitting));
        String status = computed.status(sitting);
        if ("DONE".equals(status)) {
            throw EmrProblemException.of(409, "sitting-done", "This entry is done",
                    "This entry is done and can no longer be changed.");
        }
        boolean kindChanged = !change.encounterKind().equals(sitting.encounterKind());
        if (kindChanged && (sitting.opdVisitId() != null || sitting.admissionId() != null)) {
            throw sittingNotAvailable();
        }
        if (kindChanged && CourseKinds.isAdmission(change.encounterKind()) && ipdOff(tenantId)) {
            throw moduleOff();
        }
        courses.updateSitting(tenantId, sittingId, change, user.userId());
        courses.bumpRevision(tenantId, courseId, user.userId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("sittingNo", sitting.sittingNo());
        before.put("plannedDate", sitting.plannedDate() == null ? null : sitting.plannedDate().toString());
        before.put("encounterKind", sitting.encounterKind());
        before.put("chargePaise", sitting.chargePaise());
        before.put("status", status);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sittingNo", sitting.sittingNo());
        after.put("plannedDate", change.plannedDate() == null ? null : change.plannedDate().toString());
        after.put("encounterKind", change.encounterKind());
        after.put("chargePaise", change.chargePaise());
        after.put("status", status);
        auditLog.record("COURSE_SITTING_CHANGED", ENTITY, courseId, before, after);
        return view(user, courses.find(tenantId, courseId).orElseThrow());
    }

    @Transactional
    public CourseView linkVisit(ClinicUser user, UUID courseId, UUID sittingId, LinkVisitInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        if (input == null || input.visitId() == null) {
            throw invalid("visitId", "Choose today's visit.");
        }
        if (input.revision() == null || input.revision() < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        CourseRow course = courses.findForUpdate(tenantId, courseId).orElseThrow(TreatmentCourseService::courseNotFound);
        requireCourseDoctor(user, member, course);
        SittingRow sitting = sittingOf(tenantId, courseId, sittingId);
        VisitRow visit = visits.findById(tenantId, input.visitId()).filter(v -> v.patientId().equals(course.patientId()))
                .orElseThrow(EmrProblemException::encounterNotFound);
        if (input.visitId().equals(sitting.opdVisitId())) {
            return view(user, course);
        }
        requireRevision(course, input.revision());
        Optional<SittingRow> other = courses.sittingByVisit(tenantId, visit.id());
        if (other.isPresent()) {
            throw EmrProblemException.of(409, "visit-already-linked", "Visit already linked",
                    "This visit is already linked to an entry of a course.");
        }
        if (!"OPD".equals(sitting.encounterKind()) || sitting.opdVisitId() != null || sitting.admissionId() != null
                || !"PLANNED".equals(compute(tenantId, List.of(sitting)).status(sitting))) {
            throw sittingNotAvailable();
        }
        courses.linkVisit(tenantId, sittingId, visit.id(), user.userId());
        courses.bumpRevision(tenantId, courseId, user.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sittingNo", sitting.sittingNo());
        after.put("visitId", visit.id());
        auditLog.record("COURSE_SITTING_VISIT_LINKED", ENTITY, courseId, null, after);
        return view(user, courses.find(tenantId, courseId).orElseThrow());
    }

    // ---- what billing and ipd read (TreatmentCoursePort) ----

    @Transactional(readOnly = true)
    public Optional<CourseFacts> facts(UUID tenantId, UUID courseId) {
        return courses.find(tenantId, courseId).map(row -> {
            Computed computed = compute(tenantId, courses.sittingsOf(tenantId, List.of(row.id())));
            List<SittingFacts> sittings = new ArrayList<>();
            for (SittingRow s : computed.sittings) {
                CourseAdmission admission = s.admissionId() == null ? null : computed.admissions.get(s.admissionId());
                sittings.add(new SittingFacts(s.id(), s.sittingNo(), s.encounterKind(), s.plannedDate(),
                        s.chargePaise(), s.note(), computed.status(s), s.opdVisitId(), s.admissionId(),
                        admission == null ? null : admission.ipNumber(), s.admissionId() != null, computed.doneAt(s)));
            }
            return new CourseFacts(row.id(), courseNo(row.courseNo()), row.patientId(), row.kind(), row.unit(),
                    row.name(), row.doctorMembershipId(), List.copyOf(sittings));
        });
    }

    @Transactional(readOnly = true)
    public Optional<String> courseNoOf(UUID tenantId, UUID courseId) {
        return courses.find(tenantId, courseId).map(row -> courseNo(row.courseNo()));
    }

    /** A planned, unlinked DAY_CARE or IPD sitting of an active course of the patient; the sitting row is locked. */
    @Transactional
    public Optional<com.sahaj.opd.api.TreatmentCoursePort.AdmittableSitting> admittable(UUID tenantId,
                                                                                         UUID patientId,
                                                                                         UUID sittingId) {
        Optional<SittingRow> found = courses.sittingForUpdate(tenantId, sittingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SittingRow sitting = found.get();
        Optional<CourseRow> course = courses.find(tenantId, sitting.courseId());
        if (course.isEmpty() || !"ACTIVE".equals(course.get().status()) || !course.get().patientId().equals(patientId)
                || !CourseKinds.isAdmission(sitting.encounterKind()) || sitting.admissionId() != null
                || sitting.doneAt() != null) {
            return Optional.empty();
        }
        return Optional.of(new com.sahaj.opd.api.TreatmentCoursePort.AdmittableSitting(course.get().id(),
                courseNo(course.get().courseNo()), sitting.id(), sitting.sittingNo()));
    }

    /** ADR-025 D10: the planned sittings waiting for an admission, ids and numbers only. */
    @Transactional(readOnly = true)
    public List<com.sahaj.opd.api.TreatmentCoursePort.RequestedSitting> openAdmissionSittings(UUID tenantId) {
        List<com.sahaj.opd.api.TreatmentCoursePort.RequestedSitting> out = new ArrayList<>();
        Map<UUID, CourseRow> byId = new java.util.HashMap<>();
        for (SittingRow s : courses.openAdmissionSittings(tenantId)) {
            CourseRow course = byId.computeIfAbsent(s.courseId(), id -> courses.find(tenantId, id).orElse(null));
            if (course != null) {
                out.add(new com.sahaj.opd.api.TreatmentCoursePort.RequestedSitting(course.id(),
                        courseNo(course.courseNo()), s.id(), s.sittingNo(), course.patientId(), s.encounterKind(),
                        s.plannedDate(), course.doctorMembershipId()));
            }
        }
        return out;
    }

    @Transactional
    public void linkAdmission(UUID tenantId, UUID actorUserId, UUID sittingId, UUID admissionId) {
        SittingRow sitting = courses.sittingForUpdate(tenantId, sittingId)
                .orElseThrow(() -> new IllegalStateException("The course sitting to link was not found"));
        courses.linkAdmission(tenantId, sittingId, admissionId, actorUserId);
        courses.bumpRevision(tenantId, sitting.courseId(), actorUserId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sittingNo", sitting.sittingNo());
        after.put("admissionId", admissionId);
        auditLog.record("COURSE_SITTING_ADMISSION_LINKED", ENTITY, sitting.courseId(), null, after);
    }

    // ---- computing statuses ----

    /** The sittings with the facts their status needs, read once. */
    final class Computed {
        final List<SittingRow> sittings;
        final Map<UUID, VisitRow> visitOf = new HashMap<>();
        final Map<UUID, EmrRecordRepository.RecordRow> recordOf = new HashMap<>();
        final Map<UUID, CourseAdmission> admissions = new HashMap<>();

        Computed(UUID tenantId, List<SittingRow> sittings) {
            this.sittings = sittings;
            Set<UUID> admissionIds = new LinkedHashSet<>();
            for (SittingRow s : sittings) {
                if (s.opdVisitId() != null) {
                    visits.findById(tenantId, s.opdVisitId()).ifPresent(v -> visitOf.put(s.id(), v));
                    emrRecords.findOpd(tenantId, s.opdVisitId()).ifPresent(r -> recordOf.put(s.id(), r));
                }
                if (s.admissionId() != null) {
                    admissionIds.add(s.admissionId());
                }
            }
            CourseAdmissionSource source = admissionSource.getIfAvailable();
            if (source != null && !admissionIds.isEmpty()) {
                admissions.putAll(source.admissions(tenantId, admissionIds));
            }
        }

        String status(SittingRow s) {
            if (s.doneAt() != null) {
                return "DONE";
            }
            EmrRecordRepository.RecordRow record = recordOf.get(s.id());
            if (record != null && "SIGNED".equals(record.status())) {
                return "DONE";
            }
            if (s.admissionId() != null) {
                CourseAdmission admission = admissions.get(s.admissionId());
                return admission != null && "DISCHARGED".equals(admission.status()) ? "DONE" : "TODAY";
            }
            VisitRow visit = visitOf.get(s.id());
            if (visit != null && visit.createdAt().atZone(INDIA).toLocalDate().equals(LocalDate.now(clock.withZone(INDIA)))) {
                return "TODAY";
            }
            return "PLANNED";
        }

        Instant doneAt(SittingRow s) {
            if (s.doneAt() != null) {
                return s.doneAt();
            }
            EmrRecordRepository.RecordRow record = recordOf.get(s.id());
            return record != null && "SIGNED".equals(record.status()) ? record.signedAt() : null;
        }
    }

    private Computed compute(UUID tenantId, List<SittingRow> sittings) {
        return new Computed(tenantId, sittings);
    }

    // ---- views ----

    private SummaryView summary(CourseRow row, Computed computed) {
        int done = (int) computed.sittings.stream().filter(s -> "DONE".equals(computed.status(s))).count();
        return new SummaryView(row.id(), courseNo(row.courseNo()), row.kind(), row.unit(), row.name(),
                row.startedOn(), row.status(), computed.sittings.size(), done);
    }

    private CourseView view(ClinicUser user, CourseRow row) {
        UUID tenantId = user.tenantId();
        Computed computed = compute(tenantId, courses.sittingsOf(tenantId, List.of(row.id())));
        List<SittingView> sittings = new ArrayList<>();
        for (SittingRow s : computed.sittings) {
            sittings.add(sittingView(row, s, computed));
        }
        String ipd = entitlements.forTenant(tenantId).ipdState();
        boolean ipdOff = ipd == null || "off".equals(ipd);
        List<AddOptionView> options = new ArrayList<>();
        for (AddOption option : CourseKinds.options(row.kind())) {
            if (!(ipdOff && CourseKinds.isAdmission(option.encounterKind()))) {
                options.add(new AddOptionView(option.key(), option.label(), option.encounterKind()));
            }
        }
        boolean canAdmit = ADMIT_ROLES.contains(user.role()) && ("trial".equals(ipd) || "on".equals(ipd));
        String doctorName = team.find(tenantId, row.doctorMembershipId()).map(TeamMember::name).orElse("Doctor");
        SummaryView s = summary(row, computed);
        return new CourseView(row.id(), s.courseNo(), row.kind(), row.unit(), row.name(), row.startedOn(),
                row.status(), s.sittingCount(), s.doneCount(), row.patientId(), row.doctorMembershipId(), doctorName,
                row.note(), row.revision(), List.copyOf(sittings), List.copyOf(options), canPlan(user, row), canAdmit,
                CourseKinds.ACCOUNT_NOTE);
    }

    private SittingView sittingView(CourseRow course, SittingRow s, Computed computed) {
        CourseAdmission admission = s.admissionId() == null ? null : computed.admissions.get(s.admissionId());
        VisitRow visit = computed.visitOf.get(s.id());
        String ref = admission != null ? admission.ipNumber() : visit != null ? "Token " + visit.tokenNo() : null;
        String label = CourseKinds.option(course.kind(), s.addOptionKey()).map(AddOption::label)
                .orElse(switch (s.encounterKind()) {
                    case "DAY_CARE" -> "Day care";
                    case "IPD" -> "IPD";
                    default -> "OPD";
                });
        boolean settled = s.admissionId() != null;
        return new SittingView(s.id(), s.sittingNo(), s.encounterKind(), label, ref, s.plannedDate(),
                computed.status(s), s.items(), s.chargePaise(), s.note(), s.admissionRequested(), settled,
                settled && admission != null ? admission.billTotalPaise() : null, s.opdVisitId(), s.admissionId());
    }

    private boolean canPlan(ClinicUser user, CourseRow course) {
        Optional<TeamMember> member = team.findActiveByUserId(user.tenantId(), user.userId());
        if (member.isEmpty()) {
            return false;
        }
        if (DOCTOR_ROLES.contains(member.get().role())) {
            return member.get().membershipId().equals(course.doctorMembershipId());
        }
        return "OWNER".equals(member.get().role())
                && team.doctorDetails(user.tenantId(), member.get().membershipId()).isPresent();
    }

    // ---- checks ----

    private void requireRead(ClinicUser user) {
        if (!READ_ROLES.contains(user.role())) {
            throw new AccessDeniedException("You cannot see treatment courses.");
        }
    }

    /** The course's own doctor, or an owner with doctor details (the caller already passed consultation write). */
    private void requireCourseDoctor(ClinicUser user, TeamMember member, CourseRow course) {
        if ("OWNER".equals(member.role()) || member.membershipId().equals(course.doctorMembershipId())) {
            return;
        }
        throw EmrProblemException.notOwnPatient();
    }

    private static void requireRevision(CourseRow course, Long revision) {
        if (revision == null || revision < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        if (revision != course.revision()) {
            throw EmrProblemException.of(409, "course-changed", "Course changed",
                    "This course was changed elsewhere. Reload to see the latest.");
        }
    }

    private SittingRow sittingOf(UUID tenantId, UUID courseId, UUID sittingId) {
        return courses.sittingForUpdate(tenantId, sittingId).filter(s -> s.courseId().equals(courseId))
                .orElseThrow(() -> EmrProblemException.of(404, "sitting-not-found", "Entry not found",
                        "There is no such entry in this course."));
    }

    private boolean ipdOff(UUID tenantId) {
        String state = entitlements.forTenant(tenantId).ipdState();
        return state == null || "off".equals(state);
    }

    private static ModuleOffException moduleOff() {
        return new ModuleOffException("In-patients (IPD) is not switched on for your clinic.");
    }

    static EmrProblemException courseNotFound() {
        return EmrProblemException.of(404, "course-not-found", "Course not found", "There is no such treatment course.");
    }

    private static EmrProblemException sittingNotAvailable() {
        return EmrProblemException.of(409, "sitting-not-available", "Entry not available",
                "This entry cannot take that change now.");
    }

    private static EmrProblemException idempotencyConflict() {
        return EmrProblemException.of(409, "idempotency-conflict", "Request already used",
                "This request key was used for a different request.");
    }

    private static void requireKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw invalid("Idempotency-Key", "Send an Idempotency-Key of 8 to 64 letters, digits, dashes or underscores.");
        }
    }

    // ---- input validation ----

    private record Plan(AddOption option, LocalDate plannedDate, List<String> items, long chargePaise, String note) {
    }

    private static final class Validated {
        String name;
        String note;
        final List<Plan> plans = new ArrayList<>();
    }

    /**
     * ADR-023 D7, H11: a DENTAL course starts from the record's treatment plan: one sitting per planned sitting of each
     * open row, plan order, the charge the row's share of the estimate; the note says so when the estimate is signed.
     */
    private void planFromRecord(Validated valid, Authorized authorized) {
        if (planner == null || !planner.isDentalRecord(authorized.row())) {
            throw invalid("fromRecordId", "Start from a dental EMR.");
        }
        DentalCoursePlanner.Plan plan = planner.plan(authorized.row());
        if (plan.sittings().isEmpty()) {
            throw invalid("fromPlan", "Add a treatment to the plan first.");
        }
        if (plan.sittings().size() > MAX_SITTINGS) {
            throw invalid("fromPlan", "A course can have at most 50 entries.");
        }
        AddOption option = CourseKinds.option("DENTAL", "opd_sitting").orElseThrow();
        for (DentalCoursePlanner.Sitting sitting : plan.sittings()) {
            valid.plans.add(new Plan(option, null, List.of(sitting.item()), sitting.chargePaise(), null));
        }
        if (valid.note == null && plan.estimateSigned()) {
            valid.note = DentalCoursePlanner.SIGNED_NOTE;
        }
    }

    private Validated validateCreate(CreateInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Validated valid = new Validated();
        if (input == null || input.fromRecordId() == null) {
            throw invalid("fromRecordId", "Choose the EMR the course starts from.");
        }
        if (CourseKinds.kind(input.kind()).isEmpty()) {
            throw invalid("kind", "Choose the kind of course.");
        }
        if (Boolean.TRUE.equals(input.fromPlan())) {
            if (!"DENTAL".equals(input.kind())) {
                throw invalid("fromPlan", "Only a dental course starts from a plan.");
            }
            if (input.sittings() != null && !input.sittings().isEmpty()) {
                throw invalid("sittings", "Send no sittings when the course starts from the plan.");
            }
        }
        if (input.name() != null) {
            String name = input.name().strip();
            if (name.isEmpty() || name.length() > 120 || HTML.matcher(name).find()) {
                errors.add(new FieldMessage("name", "Enter a name of 1 to 120 characters, without markup."));
            } else {
                valid.name = name;
            }
        }
        valid.note = cleanNote("note", input.note(), errors);
        List<SittingPlanInput> plans = input.sittings() == null ? List.of() : input.sittings();
        if (plans.size() > MAX_SITTINGS) {
            errors.add(new FieldMessage("sittings", "A course can have at most 50 entries."));
        }
        for (int i = 0; i < plans.size() && i < MAX_SITTINGS && errors.size() < 50; i++) {
            SittingPlanInput plan = plans.get(i);
            String where = "sittings[" + i + "]";
            if (plan == null) {
                errors.add(new FieldMessage(where, "Send every entry."));
                continue;
            }
            Optional<AddOption> option = CourseKinds.option(input.kind(), plan.addOptionKey());
            if (option.isEmpty()) {
                errors.add(new FieldMessage(where + ".addOptionKey", "Choose one of the options for this course."));
                continue;
            }
            List<String> items = cleanItems(where + ".items", plan.items() == null ? List.of() : plan.items(), errors);
            long charge = cleanCharge(where + ".chargePaise", plan.chargePaise(), errors);
            String note = cleanNote(where + ".note", plan.note(), errors);
            valid.plans.add(new Plan(option.get(), plan.plannedDate(), items, charge, note));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return valid;
    }

    private SittingChange validateChange(ChangeSittingInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        if (input == null) {
            throw invalid("revision", "Reload the page and try again.");
        }
        if (input.revision() == null || input.revision() < 0) {
            errors.add(new FieldMessage("revision", "Reload the page and try again."));
        }
        if (input.encounterKind() == null || !Set.of("OPD", "DAY_CARE", "IPD").contains(input.encounterKind())) {
            errors.add(new FieldMessage("encounterKind", "Choose OPD, day care or IPD."));
        }
        List<String> items = cleanItems("items", input.items(), errors);
        long charge = cleanCharge("chargePaise", input.chargePaise(), errors);
        String note = cleanNote("note", input.note(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new SittingChange(input.encounterKind(), input.plannedDate(), items, charge, note);
    }

    private static List<String> cleanItems(String where, List<String> items, List<FieldMessage> errors) {
        List<String> result = new ArrayList<>();
        if (items == null) {
            errors.add(new FieldMessage(where, "Send the items as a list."));
            return result;
        }
        if (items.size() > 20) {
            errors.add(new FieldMessage(where, "Add at most 20 items."));
            return result;
        }
        for (String item : items) {
            String text = item == null ? "" : item.strip();
            if (text.isEmpty() || text.length() > 200 || HTML.matcher(text).find()) {
                errors.add(new FieldMessage(where, "Each item is 1 to 200 characters, without markup."));
                return result;
            }
            result.add(text);
        }
        return result;
    }

    private static long cleanCharge(String where, Long charge, List<FieldMessage> errors) {
        if (charge == null) {
            return 0;
        }
        if (charge < 0 || charge > MAX_CHARGE || charge % 100 != 0) {
            errors.add(new FieldMessage(where, "Enter whole rupees, from ₹0 to ₹10,00,00,000."));
            return 0;
        }
        return charge;
    }

    private static String cleanNote(String where, String note, List<FieldMessage> errors) {
        if (note == null) {
            return null;
        }
        String text = note.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > 300 || HTML.matcher(text).find()) {
            errors.add(new FieldMessage(where, "Enter a note of up to 300 characters, without markup."));
            return null;
        }
        return text;
    }

    // ---- helpers ----

    static String courseNo(int number) {
        return String.format("TC-%04d", number);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String planDigest(List<Plan> plans) {
        StringBuilder digest = new StringBuilder();
        for (Plan p : plans) {
            digest.append(p.option.key()).append('|').append(p.plannedDate).append('|').append(p.chargePaise).append('|')
                    .append(nullToEmpty(p.note)).append('|').append(String.join("\u001f", p.items)).append(';');
        }
        return digest.toString();
    }

    static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(String.join("\u001e", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Map<UUID, List<SittingRow>> group(List<SittingRow> rows) {
        Map<UUID, List<SittingRow>> grouped = new LinkedHashMap<>();
        for (SittingRow row : rows) {
            grouped.computeIfAbsent(row.courseId(), id -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static Collection<UUID> ids(List<CourseRow> rows) {
        List<UUID> ids = new ArrayList<>();
        for (CourseRow row : rows) {
            ids.add(row.id());
        }
        return ids;
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }
}
