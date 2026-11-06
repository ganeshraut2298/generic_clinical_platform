package com.sahaj.billing.web;

import com.sahaj.billing.application.RevenueService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SAH-16 Revenue report. Read-only and owner only; the tenant always comes from the signed-in user. */
@RestController
@RequestMapping("/api/v1/billing")
public class RevenueController {

    private final RevenueService revenueService;

    public RevenueController(RevenueService revenueService) {
        this.revenueService = revenueService;
    }

    @GetMapping("/revenue")
    @PreAuthorize(BillingRoles.OWNER)
    public RevenueResponse revenue(@RequestParam("range") String range) {
        ClinicUser user = ClinicUser.current();
        return RevenueResponse.from(revenueService.report(user.tenantId(), user.userId(), range));
    }
}
