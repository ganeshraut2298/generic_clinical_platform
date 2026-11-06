package com.sahaj.ipd.web;

import com.sahaj.ipd.application.VitalsService;
import com.sahaj.ipd.application.VitalsViews.Sheet;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.application.VitalsViews.VitalsSaved;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-30 Nursing sheet and vitals (contract {@code docs/api/ipd.yaml}, ADR-006). The clinic, the person and the role
 * always come from the signed-in user. The role is checked again in the service, and so are the module-off,
 * read-only and every business answer.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class VitalsController {

    /** OQ-30-3: who may open the Nursing sheet. */
    static final String VIEWERS = "hasAnyRole('OWNER','NURSE','DOCTOR')";
    /** OQ-30-3: who may save vitals and a note. */
    static final String RECORDERS = "hasAnyRole('OWNER','NURSE')";

    private final VitalsService service;

    public VitalsController(VitalsService service) {
        this.service = service;
    }

    @GetMapping("/admissions/{id}/nursing")
    @PreAuthorize(VIEWERS)
    public Sheet sheet(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.sheet(user.tenantId(), user.userId(), user.role(), id);
    }

    /** 201 for a new record; 200 with the first record for a repeat of the same client key. */
    @PostMapping("/admissions/{id}/vitals")
    @PreAuthorize(RECORDERS)
    public ResponseEntity<VitalsSaved> record(@PathVariable UUID id, @RequestBody VitalsInput request) {
        ClinicUser user = ClinicUser.current();
        VitalsSaved saved = service.record(user.tenantId(), user.userId(), user.role(), id, request);
        return ResponseEntity.status(saved.saved().replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }
}
