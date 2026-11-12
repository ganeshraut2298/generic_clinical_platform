package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrDoctorContextService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-43-3, SAH-44-4: the signed-in doctor's own details for the EMR and the template the EMR would choose. Only the
 * caller's own details; there is no operation to read another doctor's. Name and registration number are never
 * logged.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrDoctorController {

    private final EmrDoctorContextService service;

    public EmrDoctorController(EmrDoctorContextService service) {
        this.service = service;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')")
    public EmrResponses.DoctorContext me() {
        return EmrResponses.DoctorContext.from(service.me(ClinicUser.current()));
    }
}
