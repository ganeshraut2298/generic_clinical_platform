package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrDepartmentService;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentCreateInput;
import com.sahaj.opd.application.emr.EmrInputs.LayoutInput;
import com.sahaj.opd.application.emr.EmrViews.DepartmentList;
import com.sahaj.opd.application.emr.EmrViews.DepartmentVersion;
import com.sahaj.opd.application.emr.EmrViews.DepartmentView;
import com.sahaj.opd.application.emr.EmrViews.DoctorOption;
import com.sahaj.opd.application.emr.EmrViews.DraftSaveResult;
import com.sahaj.opd.application.emr.EmrViews.PublishResult;
import com.sahaj.opd.application.emr.EmrViews.PublishedLayout;
import com.sahaj.opd.application.emr.EmrViews.ResetTarget;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-45, SAH-53, SAH-54 (ADR-020 D5, D6): EMR configuration by department. Any clinic role reads; only the owner writes
 * (the service checks this again from the membership). The clinic is always the signed-in user's: a tenantId or clinicId
 * in a body is ignored.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrDepartmentController implements EmrApi {

    private static final String OWNER = "hasRole('OWNER')";

    private final EmrDepartmentService service;

    public EmrDepartmentController(EmrDepartmentService service) {
        this.service = service;
    }

    @GetMapping("/departments")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public DepartmentList list() {
        return service.list(ClinicUser.current());
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OWNER)
    public DepartmentView create(@RequestBody DepartmentCreateInput input) {
        return service.create(ClinicUser.current(), input);
    }

    @GetMapping("/departments/{departmentKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public DepartmentView get(@PathVariable String departmentKey) {
        return service.get(ClinicUser.current(), departmentKey);
    }

    @PutMapping("/departments/{departmentKey}/draft")
    @PreAuthorize(OWNER)
    public DraftSaveResult saveDraft(@PathVariable String departmentKey, @RequestBody LayoutInput input) {
        return service.saveDraft(ClinicUser.current(), departmentKey, input);
    }

    @PostMapping("/departments/{departmentKey}/publish")
    @PreAuthorize(OWNER)
    public PublishResult publish(@PathVariable String departmentKey, @RequestBody LayoutInput input) {
        return service.publish(ClinicUser.current(), departmentKey, input);
    }

    @GetMapping("/departments/{departmentKey}/reset-target")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public ResetTarget resetTarget(@PathVariable String departmentKey) {
        return service.resetTarget(ClinicUser.current(), departmentKey);
    }

    @GetMapping("/departments/{departmentKey}/layout")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public PublishedLayout layout(@PathVariable String departmentKey) {
        return service.publishedLayout(ClinicUser.current(), departmentKey);
    }

    @GetMapping("/departments/{departmentKey}/versions/{versionNo}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public DepartmentVersion version(@PathVariable String departmentKey, @PathVariable int versionNo) {
        return service.version(ClinicUser.current(), departmentKey, versionNo);
    }

    @GetMapping("/department-doctor-options")
    @PreAuthorize(OWNER)
    public List<DoctorOption> doctorOptions() {
        return service.doctorOptions(ClinicUser.current());
    }
}
