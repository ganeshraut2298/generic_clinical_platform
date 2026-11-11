package com.sahaj.ipd.web;

import com.sahaj.ipd.application.ScopeViews.WardAssignmentInput;
import com.sahaj.ipd.application.ScopeViews.WardAssignments;
import com.sahaj.ipd.application.ScopeViews.WardScopeInput;
import com.sahaj.ipd.application.WardAssignmentService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-34 story 34A, the data part: nurse ward assignments and the clinic switch (contract {@code docs/api/ipd.yaml}). OWNER
 * only. The nurse-to-ward screen is not in the mockup yet, so no screen calls these operations (they carry
 * {@code x-needs-human}). The clinic, the person and the role come from the signed-in user.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class WardAssignmentController {

    static final String OWNER = "hasRole('OWNER')";

    private final WardAssignmentService service;

    public WardAssignmentController(WardAssignmentService service) {
        this.service = service;
    }

    @ScopeExempt("Owner configuration of nurse wards: no patient data")
    @GetMapping("/ward-assignments")
    @PreAuthorize(OWNER)
    public WardAssignments get() {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), user.role());
    }

    @ScopeExempt("Owner configuration of nurse wards: no patient data")
    @PutMapping("/ward-assignments/{wardId}")
    @PreAuthorize(OWNER)
    public WardAssignments setWard(@PathVariable UUID wardId, @RequestBody WardAssignmentInput request) {
        ClinicUser user = ClinicUser.current();
        return service.setWard(user.tenantId(), user.userId(), user.role(), wardId, request);
    }

    @ScopeExempt("Owner configuration of the clinic switch: no patient data")
    @PutMapping("/ward-scope")
    @PreAuthorize(OWNER)
    public WardAssignments setScope(@RequestBody WardScopeInput request) {
        ClinicUser user = ClinicUser.current();
        return service.setScope(user.tenantId(), user.userId(), user.role(), request);
    }
}
