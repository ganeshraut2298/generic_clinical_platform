package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrTemplateService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-44: the specialty templates (global, read by every clinic role) with this clinic's Show and Required
 * overrides (changed by the owner only). The clinic is always the signed-in user's.
 */
@RestController
@RequestMapping("/api/v1/opd/emr/templates")
public class EmrTemplateController {

    private final EmrTemplateService service;

    public EmrTemplateController(EmrTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<EmrResponses.TemplateSummary> list() {
        return service.list(ClinicUser.current()).stream().map(EmrResponses.TemplateSummary::from).toList();
    }

    @GetMapping("/{templateKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public EmrResponses.Template get(@PathVariable String templateKey) {
        return EmrResponses.Template.from(service.get(ClinicUser.current(), templateKey));
    }

    @PutMapping("/{templateKey}/sections/{sectionKey}/override")
    @PreAuthorize(OpdRoles.OWNER)
    public EmrResponses.Template setOverride(@PathVariable String templateKey, @PathVariable String sectionKey,
                                             @RequestBody EmrRequests.SectionOverride body) {
        return EmrResponses.Template.from(service.setOverride(ClinicUser.current(), templateKey, sectionKey,
                body.shown(), body.required(), body.revision()));
    }

    @PostMapping("/{templateKey}/reset")
    @PreAuthorize(OpdRoles.OWNER)
    public EmrResponses.Template reset(@PathVariable String templateKey,
                                       @RequestBody EmrRequests.TemplateReset body) {
        return EmrResponses.Template.from(service.reset(ClinicUser.current(), templateKey, body.revision()));
    }
}
