package com.sahaj.ipd.application;

import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.opd.api.EmrAdmissionRequestPort;
import com.sahaj.opd.api.EmrAdmissionRequestPort.OpenRequest;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.RequestedSitting;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-3 (ADR-025 D10, H16): the list "Admission requests" on Beds and admissions. Open EMR requests (read through
 * {@code opd.api.EmrAdmissionRequestPort}) and planned day-care or IPD course sittings waiting for admission (read through
 * {@code opd.api.TreatmentCoursePort}), newest first, at most 200. OWNER, FRONT_DESK and NURSE only (the admit roles); a list:
 * it writes no record-access row and is not filtered by ward scope (a request has no bed yet). Diagnoses and the note are only
 * for OWNER and NURSE (DEC-048 H33). Names, diagnoses and notes are
 * for the screen only and are never logged.
 */
@Service
public class AdmissionRequestService {

    static final int MAX_ROWS = 200;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Set<String> ROLES = AdmissionService.FLOOR_ROLES;

    private final ObjectProvider<EmrAdmissionRequestPort> requests;
    private final ObjectProvider<TreatmentCoursePort> courses;
    private final PatientLookupPort patients;
    private final TeamMemberPort team;
    private final EmergencyCaseRepository cases;
    private final AdmissionRepository admissions;
    private final IpdGate gate;

    AdmissionRequestService(ObjectProvider<EmrAdmissionRequestPort> requests, ObjectProvider<TreatmentCoursePort> courses,
                            PatientLookupPort patients, TeamMemberPort team, EmergencyCaseRepository cases,
                            AdmissionRepository admissions, IpdGate gate) {
        this.requests = requests;
        this.courses = courses;
        this.patients = patients;
        this.team = team;
        this.cases = cases;
        this.admissions = admissions;
        this.gate = gate;
    }

    /** {@code kind} is EMR or COURSE_SITTING. Clinical text inside: {@code toString} shows ids only. */
    public record Row(String kind, UUID requestId, UUID sittingId, String courseNo, UUID emergencyCaseId, UUID patientId,
                      String patientName, Integer age, String sex, String admissionType, Integer plannedStayDays,
                      String doctorName, String diagnoses, String note, Instant requestedAt, OpenAdmission openAdmission) {

        @Override
        public String toString() {
            return "Row[kind=" + kind + ", requestId=" + requestId + ", sittingId=" + sittingId + "]";
        }
    }

    public record OpenAdmission(UUID admissionId, String ipNumber) {
    }

    public record RequestList(boolean canAdmit, List<Row> items) {
    }

    @Transactional(readOnly = true)
    public RequestList list(UUID tenantId, String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new AccessDeniedException("You cannot see admission requests.");
        }
        ModuleEntitlement ent = gate.check(tenantId, false);
        boolean canAdmit = !"readonly".equals(ent.ipdState());
        List<Row> rows = new ArrayList<>();
        Map<UUID, PatientSummary> patientCache = new HashMap<>();
        Map<UUID, String> doctorNames = new HashMap<>();
        EmrAdmissionRequestPort requestPort = requests.getIfAvailable();
        if (requestPort != null) {
            for (OpenRequest request : requestPort.listOpen(tenantId)) {
                rows.add(emrRow(tenantId, role, request, patientCache, doctorNames));
            }
        }
        TreatmentCoursePort coursePort = courses.getIfAvailable();
        if (coursePort != null) {
            for (RequestedSitting sitting : coursePort.openAdmissionSittings(tenantId)) {
                Row row = courseRow(tenantId, sitting, patientCache, doctorNames);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparing(Row::requestedAt).reversed());
        List<Row> limited = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
        return new RequestList(canAdmit, List.copyOf(limited));
    }

    private Row emrRow(UUID tenantId, String role, OpenRequest request, Map<UUID, PatientSummary> patientCache,
                       Map<UUID, String> doctorNames) {
        String name = "Unknown patient";
        Integer age = null;
        String sex = null;
        OpenAdmission open = null;
        if (request.patientId() != null) {
            PatientSummary patient = patient(tenantId, request.patientId(), patientCache);
            if (patient != null) {
                name = patient.name();
                age = patient.age();
                sex = patient.sex();
            }
            open = admissions.findAdmittedOf(tenantId, request.patientId())
                    .map(ref -> new OpenAdmission(ref.admissionId(), ref.ipNumber())).orElse(null);
        } else if (request.emergencyCaseId() != null) {
            Optional<CaseRow> found = cases.find(tenantId, request.emergencyCaseId());
            if (found.isPresent()) {
                name = found.get().displayName() == null ? name : found.get().displayName();
                age = found.get().age();
                sex = found.get().sex();
            }
        }
        // DEC-048 H33: the doctor's diagnoses and note go to the owner and the nurse; the front desk admits without them
        boolean clinicalText = "OWNER".equals(role) || "NURSE".equals(role);
        return new Row("EMR", request.requestId(), null, null, request.emergencyCaseId(), request.patientId(), name, age,
                sex, request.admissionType(), request.plannedStayDays(),
                doctorName(tenantId, request.requestedByMembershipId(), doctorNames), clinicalText ? request.diagnoses() : null,
                clinicalText ? request.note() : null,
                request.requestedAt(), open);
    }

    private Row courseRow(UUID tenantId, RequestedSitting sitting, Map<UUID, PatientSummary> patientCache,
                          Map<UUID, String> doctorNames) {
        PatientSummary patient = patient(tenantId, sitting.patientId(), patientCache);
        if (patient == null) {
            return null;
        }
        OpenAdmission open = admissions.findAdmittedOf(tenantId, sitting.patientId())
                .map(ref -> new OpenAdmission(ref.admissionId(), ref.ipNumber())).orElse(null);
        LocalDate planned = sitting.plannedDate();
        Instant at = planned == null ? Instant.EPOCH : planned.atStartOfDay(INDIA).toInstant();
        String type = "DAY_CARE".equals(sitting.encounterKind()) ? "daycare" : "planned";
        return new Row("COURSE_SITTING", null, sitting.sittingId(), sitting.courseNo(), null, patient.id(), patient.name(),
                patient.age(), patient.sex(), type, null, doctorName(tenantId, sitting.doctorMembershipId(), doctorNames),
                null, null, at, open);
    }

    private PatientSummary patient(UUID tenantId, UUID patientId, Map<UUID, PatientSummary> cache) {
        return cache.computeIfAbsent(patientId, id -> patients.find(tenantId, id).orElse(null));
    }

    private String doctorName(UUID tenantId, UUID membershipId, Map<UUID, String> cache) {
        if (membershipId == null) {
            return "Doctor";
        }
        return cache.computeIfAbsent(membershipId,
                id -> team.find(tenantId, id).map(TeamMember::name).orElse("Doctor"));
    }
}
