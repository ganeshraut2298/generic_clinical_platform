package com.sahaj.platform.web;

import com.sahaj.platform.application.SetupOverviewService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SAH-7 setup overview and change history (AC6, AC12). Owner only. */
@RestController
@RequestMapping("/api/v1/platform")
public class SetupOverviewController {

    private final SetupOverviewService overviewService;

    public SetupOverviewController(SetupOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/setup/overview")
    @PreAuthorize("hasRole('OWNER')")
    public SetupOverviewResponse overview(@AuthenticationPrincipal TenantPrincipal principal) {
        return SetupOverviewResponse.from(overviewService.overview(principal.tenantId(), principal.userId()));
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasRole('OWNER')")
    public List<AuditEventResponse> auditEvents(@RequestParam(required = false) Integer limit,
                                                 @AuthenticationPrincipal TenantPrincipal principal) {
        return overviewService.auditEvents(principal.tenantId(), principal.userId(), limit).stream()
                .map(AuditEventResponse::from).toList();
    }
}
