package com.sahaj.opd.application.course;

import com.sahaj.opd.api.TreatmentCoursePort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-58 (ADR-022 D1, D12): implements {@link TreatmentCoursePort} so {@code billing} (the course account) and
 * {@code ipd} (an admission for a course sitting) can read and link a course without {@code opd} depending on either.
 * Every method runs in the caller's transaction with the clinic bound; another clinic's course is simply absent.
 */
@Component
class TreatmentCoursePortAdapter implements TreatmentCoursePort {

    private final TreatmentCourseService service;

    TreatmentCoursePortAdapter(TreatmentCourseService service) {
        this.service = service;
    }

    @Override
    public Optional<CourseFacts> course(UUID tenantId, UUID courseId) {
        return service.facts(tenantId, courseId);
    }

    @Override
    public Optional<String> courseNo(UUID tenantId, UUID courseId) {
        return service.courseNoOf(tenantId, courseId);
    }

    @Override
    public Optional<AdmittableSitting> admittableSitting(UUID tenantId, UUID patientId, UUID sittingId) {
        return service.admittable(tenantId, patientId, sittingId);
    }

    @Override
    public List<RequestedSitting> openAdmissionSittings(UUID tenantId) {
        return service.openAdmissionSittings(tenantId);
    }

    @Override
    public void linkAdmission(UUID tenantId, UUID actorUserId, UUID sittingId, UUID admissionId) {
        service.linkAdmission(tenantId, actorUserId, sittingId, admissionId);
    }
}
