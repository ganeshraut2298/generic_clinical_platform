package com.sahaj.opd.web;

import com.sahaj.opd.application.WorkflowService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-12: the OPD workflow. Any clinic user reads; only the owner saves the draft or publishes. */
@RestController
@RequestMapping("/api/v1/opd/workflow")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/draft")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public WorkflowStepsDto draft() {
        ClinicUser user = ClinicUser.current();
        return WorkflowStepsDto.from(service.draft(user.tenantId(), user.userId()));
    }

    @PutMapping("/draft")
    @PreAuthorize(OpdRoles.OWNER)
    public WorkflowStepsDto saveDraft(@RequestBody WorkflowStepsDto request) {
        ClinicUser user = ClinicUser.current();
        return WorkflowStepsDto.from(service.saveDraft(user.tenantId(), user.userId(), request.toInput()));
    }

    @GetMapping("/current")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public WorkflowVersionResponse current() {
        ClinicUser user = ClinicUser.current();
        return WorkflowVersionResponse.from(service.current(user.tenantId(), user.userId()));
    }

    @GetMapping("/versions")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<WorkflowVersionResponse> versions() {
        ClinicUser user = ClinicUser.current();
        return service.versions(user.tenantId(), user.userId()).stream().map(WorkflowVersionResponse::from).toList();
    }

    @PostMapping("/publish")
    @PreAuthorize(OpdRoles.OWNER)
    public WorkflowVersionResponse publish() {
        ClinicUser user = ClinicUser.current();
        return WorkflowVersionResponse.from(service.publish(user.tenantId(), user.userId()));
    }
}
