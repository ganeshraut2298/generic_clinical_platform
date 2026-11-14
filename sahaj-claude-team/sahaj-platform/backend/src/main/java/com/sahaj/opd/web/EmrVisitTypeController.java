package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrInputs.VisitTypeInput;
import com.sahaj.opd.application.emr.EmrVisitTypeService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-52-2 (ADR-020 D10): the doctor changes the OPD visit type from the EMR. Front desk still sets it at registration
 * only.
 */
@RestController
@RequestMapping("/api/v1/opd/visits")
public class EmrVisitTypeController implements EmrApi {

    private final EmrVisitTypeService service;

    public EmrVisitTypeController(EmrVisitTypeService service) {
        this.service = service;
    }

    @PatchMapping("/{id}/visit-type")
    @PreAuthorize("hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')")
    public VisitResponse change(@PathVariable UUID id, @RequestBody VisitTypeInput input) {
        return VisitResponse.from(service.change(ClinicUser.current(), id, input));
    }
}
