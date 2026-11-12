package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrMasterService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-43: the global EMR masters (search, any clinic role) and the signed-in doctor's own usage-ranked quick options
 * (doctors, and an owner with doctor details). There is no doctor id parameter: a {@code doctorMembershipId} sent by
 * a client is not read. There is no write operation here; counts are written by the sign step only. The search text
 * is never logged.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrMasterController {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";

    private final EmrMasterService service;

    public EmrMasterController(EmrMasterService service) {
        this.service = service;
    }

    @GetMapping("/masters/{master}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public EmrResponses.MasterPage search(@PathVariable String master,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String specialty,
                                          @RequestParam(required = false) Integer page) {
        return EmrResponses.MasterPage.from(service.search(master, q, specialty, page));
    }

    @GetMapping("/quick-options/{master}")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrResponses.QuickOptionList quickOptions(@PathVariable String master,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String specialty) {
        return EmrResponses.QuickOptionList.from(
                service.quickOptions(ClinicUser.current(), master, limit, specialty));
    }
}
