package com.sahaj.opd.web;

import com.sahaj.opd.application.FollowUpService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SAH-14 Follow-up lists: any clinic role reads; there are no writes from this screen in Phase 1. */
@RestController
@RequestMapping("/api/v1/opd/follow-ups")
public class FollowUpController {

    private final FollowUpService followUpService;

    public FollowUpController(FollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FollowUpResponse list(@RequestParam(required = false) String status) {
        ClinicUser user = ClinicUser.current();
        return FollowUpResponse.from(followUpService.list(user.tenantId(), user.role(), status));
    }
}
