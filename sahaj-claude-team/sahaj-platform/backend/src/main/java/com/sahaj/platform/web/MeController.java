package com.sahaj.platform.web;

import com.sahaj.platform.application.MeService;
import com.sahaj.platform.infrastructure.security.CurrentRole;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-5: the app shell's own user and preferences endpoint. Any signed-in role may call it. */
@RestController
@RequestMapping("/api/v1/platform/me")
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    public MeResponse getMe(@AuthenticationPrincipal TenantPrincipal principal) {
        String role = CurrentRole.role().orElseThrow();
        return MeResponse.from(meService.getMe(principal.tenantId(), principal.userId(), role));
    }

    @PatchMapping("/preferences")
    public MeResponse updatePreferences(@Valid @RequestBody PreferencesRequest request,
                                         @AuthenticationPrincipal TenantPrincipal principal) {
        String role = CurrentRole.role().orElseThrow();
        return MeResponse.from(
                meService.updatePreferences(principal.tenantId(), principal.userId(), role, request.toInput()));
    }
}
