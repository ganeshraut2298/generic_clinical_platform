package com.sahaj.opd.web;

import com.sahaj.opd.application.course.CourseInputs.AddSittingInput;
import com.sahaj.opd.application.course.CourseInputs.AdmissionRequestInput;
import com.sahaj.opd.application.course.CourseInputs.DateInput;
import com.sahaj.opd.application.course.CourseInputs.MarkDoneInput;
import com.sahaj.opd.application.course.CourseInputs.RevisionInput;
import com.sahaj.opd.application.course.CourseInputs.ChangeSittingInput;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseInputs.LinkVisitInput;
import com.sahaj.opd.application.course.CourseViews.Changed;
import com.sahaj.opd.application.course.CourseViews.CourseView;
import com.sahaj.opd.application.course.CourseViews.SummaryView;
import com.sahaj.opd.application.course.TreatmentCourseService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-58 (ADR-022 D12): the treatment course plan. OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK and BILLING read; create,
 * add, change and link need {@code clinic.consultation.write}, which the service re-reads from the membership (so a
 * demoted doctor is refused at once). The clinic is always the signed-in user's. Money is in billing
 * ({@code /api/v1/billing/courses/**}).
 */
@RestController
@RequestMapping("/api/v1/opd/emr/courses")
public class TreatmentCourseController implements EmrApi {

    private static final String READ_ROLES = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','FRONT_DESK','BILLING')";
    private static final String WRITE_ROLES = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";

    private final TreatmentCourseService service;

    public TreatmentCourseController(TreatmentCourseService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ_ROLES)
    public List<SummaryView> list(@RequestParam(name = "patientId", required = false) UUID patientId,
                                  @RequestParam(name = "kind", required = false) String kind) {
        return service.list(ClinicUser.current(), patientId, kind);
    }

    @PostMapping
    @PreAuthorize(WRITE_ROLES)
    public ResponseEntity<CourseView> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestBody CreateInput input) {
        Changed result = service.create(ClinicUser.current(), key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.course());
    }

    @GetMapping("/{courseId}")
    @PreAuthorize(READ_ROLES)
    public CourseView get(@PathVariable UUID courseId) {
        return service.get(ClinicUser.current(), courseId);
    }

    @PostMapping("/{courseId}/sittings")
    @PreAuthorize(WRITE_ROLES)
    public ResponseEntity<CourseView> addSitting(@PathVariable UUID courseId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @RequestBody AddSittingInput input) {
        Changed result = service.addSitting(ClinicUser.current(), courseId, key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.course());
    }

    @PutMapping("/{courseId}/sittings/{sittingId}")
    @PreAuthorize(WRITE_ROLES)
    public CourseView changeSitting(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                                    @RequestBody ChangeSittingInput input) {
        return service.changeSitting(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/link-visit")
    @PreAuthorize(WRITE_ROLES)
    public CourseView linkVisit(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                                @RequestBody LinkVisitInput input) {
        return service.linkVisit(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/mark-done")
    @PreAuthorize(WRITE_ROLES)
    public CourseView markDone(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                               @RequestBody MarkDoneInput input) {
        return service.markDone(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/missed")
    @PreAuthorize(WRITE_ROLES)
    public CourseView missed(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                             @RequestBody RevisionInput input) {
        return service.markMissed(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/rebook")
    @PreAuthorize(WRITE_ROLES)
    public CourseView rebook(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                             @RequestBody DateInput input) {
        return service.rebook(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/reschedule")
    @PreAuthorize(WRITE_ROLES)
    public CourseView reschedule(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                                 @RequestBody DateInput input) {
        return service.reschedule(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/remove")
    @PreAuthorize(WRITE_ROLES)
    public CourseView remove(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                             @RequestBody RevisionInput input) {
        return service.remove(ClinicUser.current(), courseId, sittingId, input);
    }

    @PostMapping("/{courseId}/sittings/{sittingId}/admission-request")
    @PreAuthorize(WRITE_ROLES)
    public CourseView admissionRequest(@PathVariable UUID courseId, @PathVariable UUID sittingId,
                                       @RequestBody AdmissionRequestInput input) {
        return service.requestAdmission(ClinicUser.current(), courseId, sittingId, input);
    }
}
