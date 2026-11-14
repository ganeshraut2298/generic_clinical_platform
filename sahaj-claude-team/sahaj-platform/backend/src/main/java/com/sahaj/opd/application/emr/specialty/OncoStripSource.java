package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.SittingStatuses;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.specialty.CancerJourneyRepository.JourneyRow;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Cycle;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.JourneyRecord;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.OncoExtra;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.PlanInfo;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the oncology screen reads beyond the order sheet (SAH-75-7, SAH-75-11; ADR-026 D7): the patient's Cancer journey
 * (the highest version), the diagnosis text (this record's first active diagnosis, else the patient's latest SIGNED
 * oncology record's), the newest ACTIVE chemotherapy course with the state of each cycle (the same function the course
 * view uses), the doxorubicin given before this record's own order (the watch chip), and the bed of today's day-care cycle
 * (read from ipd through {@code opd.api.CourseAdmissionSource}). Reads only, in the caller's transaction; clinical values
 * are never logged.
 */
@Component
public class OncoStripSource {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String CYCLE_OPTION = "CHEMO_CYCLE";
    private static final int SIGNED_SCAN = 20;
    private static final int COURSE_SCAN = 50;

    private final CancerJourneyRepository journeys;
    private final TreatmentCourseRepository courses;
    private final VisitRepository visits;
    private final EmrRecordRepository records;
    private final ObjectProvider<CourseAdmissionSource> admissionSource;
    private final TeamMemberPort team;
    private final ChemoOrderRepository chemoOrders;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OncoStripSource(CancerJourneyRepository journeys, TreatmentCourseRepository courses,
                           VisitRepository visits, EmrRecordRepository records,
                           ObjectProvider<CourseAdmissionSource> admissionSource, TeamMemberPort team,
                           ChemoOrderRepository chemoOrders, ObjectMapper mapper, Clock clock) {
        this.journeys = journeys;
        this.courses = courses;
        this.visits = visits;
        this.records = records;
        this.admissionSource = admissionSource;
        this.team = team;
        this.chemoOrders = chemoOrders;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** All of it for one oncology record; every part is empty (never half filled) when the record has no patient. */
    public OncoExtra load(UUID tenantId, RecordRow row, EmrRecordDocument document) {
        UUID patientId = row.patientId();
        if (patientId == null) {
            return new OncoExtra(null, firstDiagnosis(document), null, null, null);
        }
        JourneyRecord journey = journeys.latest(tenantId, patientId).map(j -> record(tenantId, j)).orElse(null);
        String diagnosis = firstDiagnosis(document);
        if (diagnosis == null) {
            diagnosis = diagnosisOfLatestSigned(tenantId, patientId, row.id());
        }
        Plan plan = plan(tenantId, patientId);
        return new OncoExtra(journey, diagnosis, plan == null ? null : plan.info, doxorubicinGiven(tenantId, patientId,
                row.id()), plan == null ? null : plan.dayCareBed);
    }

    private JourneyRecord record(UUID tenantId, JourneyRow j) {
        String name = j.recordedByMembershipId() == null ? null
                : team.find(tenantId, j.recordedByMembershipId()).map(TeamMember::name).orElse(null);
        return new JourneyRecord(j.version(), j.stageGroup(), j.tnmText(), j.markersText(), j.ki67Pct(),
                j.geneticsText(), j.intent(), j.setting(), j.lineNo(), j.boardDate(), j.recordedAt(),
                name == null ? "Someone" : name);
    }

    // ---- the plan ----

    private record Plan(PlanInfo info, String dayCareBed) {
    }

    private Plan plan(UUID tenantId, UUID patientId) {
        Optional<CourseRow> course = courses.forPatient(tenantId, patientId, COURSE_SCAN).stream()
                .filter(c -> "CHEMO".equals(c.kind()) && "ACTIVE".equals(c.status())).findFirst();
        if (course.isEmpty()) {
            return null;
        }
        List<SittingRow> cycles = courses.sittingsOf(tenantId, List.of(course.get().id())).stream()
                .filter(s -> CYCLE_OPTION.equals(s.addOptionKey())).toList();
        Set<UUID> admissionIds = new LinkedHashSet<>();
        for (SittingRow s : cycles) {
            if (s.admissionId() != null) {
                admissionIds.add(s.admissionId());
            }
        }
        CourseAdmissionSource source = admissionSource.getIfAvailable();
        Map<UUID, CourseAdmission> admissions = source == null || admissionIds.isEmpty() ? Map.of()
                : source.admissions(tenantId, admissionIds);
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        List<Cycle> chips = new ArrayList<>();
        String bed = null;
        for (SittingRow s : cycles) {
            VisitRow visit = s.opdVisitId() == null ? null : visits.findById(tenantId, s.opdVisitId()).orElse(null);
            RecordRow linked = s.opdVisitId() == null ? null : records.findOpd(tenantId, s.opdVisitId()).orElse(null);
            CourseAdmission admission = s.admissionId() == null ? null : admissions.get(s.admissionId());
            String status = SittingStatuses.status(s, today, visit, linked, admission);
            chips.add(new Cycle(s.id(), s.regimenKey(), "DONE".equals(status) ? "GIVEN" : status));
            if (bed == null && "TODAY".equals(status) && admission != null) {
                bed = admission.bedLabel();
            }
        }
        return new Plan(new PlanInfo(course.get().id(), course.get().name(), List.copyOf(chips)), bed);
    }

    // ---- the watch chip ----

    /**
     * The outside dose entered on the latest SENT order plus the doxorubicin of the patient's SENT orders, excluding this
     * record's own order (the drawn 180 / 450 is before today's cycle); null when no order was sent.
     */
    private Double doxorubicinGiven(UUID tenantId, UUID patientId, UUID ownRecordId) {
        List<ChemoOrderRow> sent = chemoOrders.sentForPatient(tenantId, patientId);
        if (sent.isEmpty()) {
            return null;
        }
        BigDecimal given = sent.get(sent.size() - 1).priorOutsideMgM2;
        for (ChemoOrderRow order : sent) {
            if (!ownRecordId.equals(order.recordId)) {
                given = given.add(order.doxorubicinMgM2);
            }
        }
        return given.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    // ---- the diagnosis ----

    private String diagnosisOfLatestSigned(UUID tenantId, UUID patientId, UUID ownRecordId) {
        for (SignedCopy signed : records.signedForPatient(tenantId, patientId, SIGNED_SCAN)) {
            if (ownRecordId.equals(signed.recordId())) {
                continue;
            }
            EmrRecordDocument document = EmrRecordDocument.parse(mapper, signed.sections());
            boolean oncology = false;
            for (String key : document.sectionKeys()) {
                oncology = oncology || key.startsWith("onc_");
            }
            if (!oncology) {
                continue;
            }
            String text = firstDiagnosis(document);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** The first active item of a DIAGNOSIS list in the document, or null. */
    static String firstDiagnosis(EmrRecordDocument document) {
        for (String key : document.sectionKeys()) {
            var fields = document.values(key).fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value.isObject() && "EMR_LIST".equals(value.path("component").asText())
                        && "DIAGNOSIS".equals(value.path("kind").asText())) {
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true) && item.path("text").isTextual()
                                && !item.path("text").asText().isBlank()) {
                            String text = item.path("text").asText().strip();
                            return text.length() > 200 ? text.substring(0, 200) : text;
                        }
                    }
                }
            }
        }
        return null;
    }
}
