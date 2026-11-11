package com.sahaj.ipd.web;

import com.sahaj.ipd.application.EmergencyAccessService;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessGrant;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessInput;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPicker;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewResult;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-34 break-glass emergency access (stories 34E and 34F; contract {@code docs/api/ipd.yaml}, ADR-017 D4 to D6). The
 * clinic, the person and the role always come from the signed-in user. The picker and the grant are open to every IPD role
 * at this layer so that a caller who may not use them (anyone but a NURSE whose scope is narrower than all) gets the
 * contract's 403 forbidden text from the service; the owner's list and the review are OWNER only. The picker, the grant
 * and the review carry names and are never logged.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class EmergencyAccessController {

    /** Every role that has an IPD screen; the service refuses all but a scoped NURSE with the contract's text. */
    static final String ANY_IPD_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";
    static final String OWNER = "hasRole('OWNER')";

    private final EmergencyAccessService service;

    public EmergencyAccessController(EmergencyAccessService service) {
        this.service = service;
    }

    @ScopeGuarded(ScopeGuarded.Kind.GRANT)
    @GetMapping("/emergency-access/patients")
    @PreAuthorize(ANY_IPD_ROLE)
    public EmergencyAccessPicker picker() {
        ClinicUser user = ClinicUser.current();
        return service.picker(user.tenantId(), user.userId(), user.role());
    }

    @ScopeGuarded(ScopeGuarded.Kind.GRANT)
    @PostMapping("/emergency-access")
    @PreAuthorize(ANY_IPD_ROLE)
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyAccessGrant open(@RequestBody EmergencyAccessInput request) {
        ClinicUser user = ClinicUser.current();
        return service.open(user.tenantId(), user.userId(), user.role(), request);
    }

    /** The limit and the cursor are read as text so that the service gives the contract's own 400 for a bad one. */
    @ScopeGuarded(ScopeGuarded.Kind.GRANT)
    @GetMapping("/emergency-access")
    @PreAuthorize(OWNER)
    public EmergencyAccessReviewPage list(@RequestParam(name = "limit", required = false) String limit,
                                          @RequestParam(name = "cursor", required = false) String cursor) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), user.role(), limit, cursor);
    }

    @ScopeGuarded(ScopeGuarded.Kind.GRANT)
    @PostMapping("/emergency-access/{id}/review")
    @PreAuthorize(OWNER)
    public EmergencyAccessReviewResult review(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.review(user.tenantId(), user.userId(), user.role(), id);
    }
}
