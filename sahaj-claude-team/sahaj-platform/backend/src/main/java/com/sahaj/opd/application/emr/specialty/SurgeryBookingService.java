package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.course.CourseKinds;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OT booking of a General Surgery visit (SAH-64-3, SAH-71-1, SAH-71-2; ADR-023 D8). The booking rule (tagged C-7, L-3, B-3)
 * is checked on the stored values: an active diagnosis item, every consent item ticked and, when the payer is not Self,
 * pre-authorisation sent. In one transaction the booking is stamped into the plan, the patient's SURGERY course started
 * from this record is used (or made) and, with IPD on or in trial, one DAY_CARE or IPD sitting is added (or updated while
 * no admission is linked) with the admission requested. With IPD off no sitting is made (H14). The OT date and slot are
 * text: there is no OT calendar (OQ-45-28); no SMS is sent (OQ-45-27). The same Idempotency-Key gives the first result.
 * The audit entry carries ids only, never the procedure. This class never logs.
 */
@Service
public class SurgeryBookingService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final EmrRecordService records;
    private final EmrRecordRepository recordRepository;
    private final TreatmentCourseRepository courses;
    private final ModuleEntitlementPort entitlements;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SurgeryBookingService(EmrRecordService records, EmrRecordRepository recordRepository,
                                 TreatmentCourseRepository courses, ModuleEntitlementPort entitlements,
                                 AuditLog auditLog, ObjectMapper mapper, Clock clock) {
        this.records = records;
        this.recordRepository = recordRepository;
        this.courses = courses;
        this.entitlements = entitlements;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The answer: the record as it now stands and what the booking made. */
    public record Result(RecordView record, UUID courseId, UUID sittingId, boolean admissionRequested, String message) {
    }

    @Transactional
    public Result book(ClinicUser user, UUID recordId, String idempotencyKey, Long revision) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw invalid("Idempotency-Key", "Send a key of 8 to 64 letters, digits, dashes or underscores.");
        }
        if (revision == null || revision < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        UUID tenantId = user.tenantId();
        courses.lockIdempotency(tenantId, "booking:" + idempotencyKey);
        Authorized authorized = records.authorize(user, recordId);
        RecordRow stored = authorized.row();
        if (SpecialtyProfiles.profileOf(stored.encounterType(), stored.starterKey()).filter("surgery"::equals)
                .isEmpty()) {
            throw EmrProblemException.notApplicable();
        }
        if ("SIGNED".equals(stored.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        RecordRow row = recordRepository.findByIdForUpdate(tenantId, recordId)
                .orElseThrow(EmrProblemException::recordNotFound);
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        JsonNode plan = SpecialtyRecordSupport.componentValue("surgery", document, "SURGERY_PLAN");
        JsonNode earlier = plan == null ? null : plan.path("booking");
        if (earlier != null && earlier.isObject() && idempotencyKey.equals(earlier.path("requestKey").asText())) {
            if (earlier.path("requestRevision").asLong(-1) != revision) {
                throw EmrProblemException.of(409, "idempotency-conflict", "Key already used",
                        "This key was used for a different request.");
            }
            return first(user, recordId, earlier, plan);
        }
        if (revision != row.revision()) {
            throw EmrProblemException.emrRecordChanged();
        }
        if (plan == null || !plan.path("procedureText").isTextual()) {
            throw invalid("procedureText", "Choose the procedure first.");
        }
        if (!plan.path("otDate").isTextual()) {
            throw invalid("otDate", "Choose the OT date first.");
        }
        JsonNode consent = SpecialtyRecordSupport.componentValue("surgery", document, "CONSENT_CHECKLIST");
        JsonNode preauth = SpecialtyRecordSupport.componentValue("surgery", document, "PREAUTH");
        int ticked = 0;
        int total = 0;
        if (consent != null) {
            for (JsonNode item : consent.path("items")) {
                total++;
                ticked += item.path("confirmed").asBoolean(false) ? 1 : 0;
            }
        }
        boolean sent = preauth != null && preauth.path("sent").asBoolean(false);
        List<String> missing = SurgeryRules.bookMissing(SpecialtyRecordSupport.hasActiveDiagnosis(document), ticked,
                total, plan.path("payer").asText("SELF"), sent);
        if (!missing.isEmpty()) {
            throw EmrProblemException.of(409, "booking-not-ready", "Not ready to book",
                    SurgeryRules.bookMissingDetail(missing));
        }
        TeamMember member = authorized.member();
        Instant now = clock.instant();
        LocalDate today = now.atZone(INDIA).toLocalDate();
        UUID patientId = authorized.patientId();
        boolean ipd = Set.of("trial", "on").contains(entitlements.forTenant(tenantId).ipdState());
        String admission = plan.path("admission").asText(null);
        String encounterKind = "DAY_CARE".equals(admission) ? CourseKinds.ENCOUNTER_DAY_CARE : CourseKinds.ENCOUNTER_IPD;
        String optionKey = "DAY_CARE".equals(admission) ? "day_care_procedure" : "re_admission";
        String otDate = plan.path("otDate").asText();
        String slot = plan.path("slotText").asText(null);
        String procedure = plan.path("procedureText").asText();
        long charge = plan.path("packagePaise").asLong(0);

        UUID courseId = earlier != null && earlier.path("courseId").isTextual()
                ? UUID.fromString(earlier.path("courseId").asText()) : null;
        Optional<CourseRow> course = courseId == null ? Optional.empty() : courses.find(tenantId, courseId);
        if (course.isEmpty()) {
            course = courses.forPatient(tenantId, patientId, 50).stream().filter(c -> "SURGERY".equals(c.kind())
                    && "ACTIVE".equals(c.status()) && recordId.equals(c.originRecordId())).findFirst();
        }
        if (course.isEmpty()) {
            // ADR-026 D3, H11: an episode started at the first consult carries to the pre-op visit.
            boolean owner = "OWNER".equals(member.role());
            course = courses.forPatient(tenantId, patientId, 50).stream().filter(c -> "SURGERY".equals(c.kind())
                    && "ACTIVE".equals(c.status())
                    && (owner || member.membershipId().equals(c.doctorMembershipId()))).findFirst();
        }
        if (course.isEmpty()) {
            CourseKinds.Kind kind = CourseKinds.kind("SURGERY").orElseThrow();
            UUID created = courses.insertCourse(tenantId, new NewCourse(courses.nextCourseNo(tenantId), patientId,
                    kind.kind(), kind.unit(), kind.defaultName(), null, member.membershipId(), today, recordId,
                    row.createdAt(), null, null), user.userId());
            course = courses.find(tenantId, created);
        }
        UUID sittingId = null;
        if (ipd) {
            sittingId = upsertSitting(tenantId, user.userId(), course.get().id(), earlier, encounterKind, optionKey,
                    LocalDate.parse(otDate), procedure, charge, "OT " + otDate + (slot == null ? "" : ", " + slot));
            courses.bumpRevision(tenantId, course.get().id(), user.userId());
        }
        ObjectNode booking = mapper.createObjectNode();
        ObjectNode stamp = booking.putObject("stamp");
        stamp.put("at", now.toString());
        stamp.put("byMembershipId", member.membershipId().toString());
        booking.put("courseId", course.get().id().toString());
        if (sittingId == null) {
            booking.putNull("sittingId");
        } else {
            booking.put("sittingId", sittingId.toString());
        }
        booking.put("admissionRequested", ipd);
        booking.put("planFingerprint", SpecialtyDone.bookingFingerprint(plan, consent, preauth));
        booking.put("requestKey", idempotencyKey);
        booking.put("requestRevision", revision);
        ObjectNode values = document.mutableValues(SpecialtyProfiles.sectionOf("surgery", "SURGERY_PLAN"));
        ((ObjectNode) values.path("specialty")).set("booking", booking);
        document.put(SpecialtyProfiles.sectionOf("surgery", "SURGERY_PLAN"), values, false, now, member.membershipId());
        if (!recordRepository.saveSectionsLocked(tenantId, recordId, document.toJson(), user.userId())) {
            throw EmrProblemException.emrRecordChanged();
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", recordId);
        audit.put("courseId", course.get().id());
        audit.put("sittingId", sittingId);
        auditLog.record("EMR_SURGERY_BOOKED", "EMR_RECORD", recordId, null, audit);
        String message = SurgeryRules.bookedMessage(procedure, LocalDate.parse(otDate).format(DATE), slot, ipd);
        return new Result(records.get(user, recordId), course.get().id(), sittingId, ipd, message);
    }

    private Result first(ClinicUser user, UUID recordId, JsonNode booking, JsonNode plan) {
        UUID courseId = UUID.fromString(booking.path("courseId").asText());
        UUID sittingId = booking.path("sittingId").isTextual() ? UUID.fromString(booking.path("sittingId").asText()) : null;
        boolean ipd = booking.path("admissionRequested").asBoolean(false);
        String otDate = plan.path("otDate").asText();
        String message = SurgeryRules.bookedMessage(plan.path("procedureText").asText(),
                LocalDate.parse(otDate).format(DATE), plan.path("slotText").asText(null), ipd);
        return new Result(records.get(user, recordId), courseId, sittingId, ipd, message);
    }

    /** Adds the sitting, or updates the booked one while no admission or visit is linked to it. */
    private UUID upsertSitting(UUID tenantId, UUID actor, UUID courseId, JsonNode earlier, String encounterKind,
                               String optionKey, LocalDate date, String procedure, long charge, String note) {
        if (earlier != null && earlier.path("sittingId").isTextual()) {
            UUID existingId = UUID.fromString(earlier.path("sittingId").asText());
            Optional<SittingRow> existing = courses.sittingForUpdate(tenantId, existingId);
            if (existing.isPresent() && !existing.get().removed()) {
                SittingRow sitting = existing.get();
                if (sitting.admissionId() == null && sitting.opdVisitId() == null) {
                    courses.updateSitting(tenantId, existingId, new SittingChange(encounterKind, date,
                            List.of(procedure), charge, note), actor);
                }
                return existingId;
            }
        }
        int number = courses.nextSittingNo(tenantId, courseId);
        return courses.insertSitting(tenantId, new NewSitting(courseId, number, encounterKind,
                optionKey.toUpperCase(Locale.ROOT), date, List.of(procedure), charge, note, null, null), actor);
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }

}
