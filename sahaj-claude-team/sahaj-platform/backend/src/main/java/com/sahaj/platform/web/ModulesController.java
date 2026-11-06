package com.sahaj.platform.web;

import com.sahaj.platform.application.ModulesService;
import com.sahaj.platform.infrastructure.security.CurrentRole;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-19 Plan and modules. Any clinic user reads; only the owner changes anything. The clinic comes from the signed-in user. */
@RestController
@RequestMapping("/api/v1/platform/modules")
public class ModulesController {

    private final ModulesService modulesService;

    public ModulesController(ModulesService modulesService) {
        this.modulesService = modulesService;
    }

    @GetMapping
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public ModulesDto get(@AuthenticationPrincipal TenantPrincipal principal) {
        return ModulesDto.from(modulesService.get(principal.tenantId(), role()));
    }

    @PostMapping("/ipd-request")
    @PreAuthorize("hasRole('OWNER')")
    public ModulesDto requestIpd(@AuthenticationPrincipal TenantPrincipal principal) {
        return ModulesDto.from(modulesService.requestIpd(principal.tenantId(), principal.userId(), role()));
    }

    @PostMapping("/ipd-trial")
    @PreAuthorize("hasRole('OWNER')")
    public ModulesDto startIpdTrial(@AuthenticationPrincipal TenantPrincipal principal) {
        return ModulesDto.from(modulesService.startIpdTrial(principal.tenantId(), principal.userId(), role()));
    }

    @PutMapping("/hra")
    @PreAuthorize("hasRole('OWNER')")
    public ModulesDto setHra(@RequestBody HraRequest request, @AuthenticationPrincipal TenantPrincipal principal) {
        return ModulesDto.from(modulesService.setHra(principal.tenantId(), principal.userId(), role(), request.on()));
    }

    private static String role() {
        return CurrentRole.role().orElseThrow();
    }

    /** {@code on} is required; a missing value is refused by the service with a field error. */
    public record HraRequest(Boolean on) {
    }
}
