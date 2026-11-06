package com.sahaj.platform.web;

import com.sahaj.platform.application.PrivacyService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-17 privacy settings. Any clinic user reads (view-only page); only the owner saves. The clinic comes from the signed-in user. */
@RestController
@RequestMapping("/api/v1/platform/privacy")
public class PrivacyController {

    private final PrivacyService privacyService;

    public PrivacyController(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @GetMapping
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public PrivacySettingsDto get(@AuthenticationPrincipal TenantPrincipal principal) {
        return PrivacySettingsDto.from(privacyService.get(principal.tenantId()));
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public PrivacySettingsDto save(@RequestBody PrivacySettingsDto request,
                                    @AuthenticationPrincipal TenantPrincipal principal) {
        return PrivacySettingsDto.from(privacyService.save(principal.tenantId(), principal.userId(),
                request.consentRequired(), request.promoConsent(), request.recordCopy(), request.hfrId()));
    }
}
