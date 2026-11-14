package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrFormModel.CreateInput;
import com.sahaj.opd.application.emr.EmrFormModel.DraftInput;
import com.sahaj.opd.application.emr.EmrFormModel.RevisionInput;
import com.sahaj.opd.application.emr.EmrFormModel.SendBackInput;
import com.sahaj.opd.application.emr.EmrFormService;
import com.sahaj.opd.application.emr.EmrFormService.Created;
import com.sahaj.opd.application.emr.EmrFormService.FormList;
import com.sahaj.opd.application.emr.EmrFormService.FormView;
import com.sahaj.opd.application.emr.EmrFormService.StarterDetail;
import com.sahaj.opd.application.emr.EmrFormService.StarterSummary;
import com.sahaj.opd.application.emr.EmrFormService.VersionView;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-59 (ADR-022 D11): the form builder and the six starter forms. Every clinic role reads; only the owner changes
 * anything, and the service re-reads the membership (so a demoted owner is refused at once). The clinic is always the
 * signed-in user's. The 256 KB body limit of the draft save is set in {@code RequestBodySizeLimitFilter}.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrFormController implements EmrApi {

    private final EmrFormService service;

    public EmrFormController(EmrFormService service) {
        this.service = service;
    }

    @GetMapping("/form-starters")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<StarterSummary> starters() {
        return service.starters(ClinicUser.current());
    }

    @GetMapping("/form-starters/{starterKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public StarterDetail starter(@PathVariable String starterKey) {
        return service.starter(ClinicUser.current(), starterKey);
    }

    @GetMapping("/forms")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormList list() {
        return service.list(ClinicUser.current());
    }

    @PostMapping("/forms")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public ResponseEntity<FormView> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody CreateInput input) {
        Created result = service.create(ClinicUser.current(), key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.form());
    }

    @GetMapping("/forms/{formKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView get(@PathVariable String formKey) {
        return service.get(ClinicUser.current(), formKey);
    }

    @PutMapping("/forms/{formKey}/draft")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView saveDraft(@PathVariable String formKey, @RequestBody DraftInput input) {
        return service.saveDraft(ClinicUser.current(), formKey, input);
    }

    @PostMapping("/forms/{formKey}/submit")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView submit(@PathVariable String formKey, @RequestBody RevisionInput input) {
        return service.submit(ClinicUser.current(), formKey, input);
    }

    @PostMapping("/forms/{formKey}/approve")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView approve(@PathVariable String formKey, @RequestBody RevisionInput input) {
        return service.approve(ClinicUser.current(), formKey, input);
    }

    @PostMapping("/forms/{formKey}/send-back")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView sendBack(@PathVariable String formKey, @RequestBody SendBackInput input) {
        return service.sendBack(ClinicUser.current(), formKey, input);
    }

    @PostMapping("/forms/{formKey}/publish")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView publish(@PathVariable String formKey, @RequestBody RevisionInput input) {
        return service.publish(ClinicUser.current(), formKey, input);
    }

    @PostMapping("/forms/{formKey}/retire")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FormView retire(@PathVariable String formKey, @RequestBody RevisionInput input) {
        return service.retire(ClinicUser.current(), formKey, input);
    }

    @GetMapping("/forms/{formKey}/versions/{versionNo}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public VersionView version(@PathVariable String formKey, @PathVariable int versionNo) {
        return service.version(ClinicUser.current(), formKey, versionNo);
    }
}
