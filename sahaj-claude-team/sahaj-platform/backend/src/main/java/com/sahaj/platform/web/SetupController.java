package com.sahaj.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.application.SetupService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-4: the clinic setup wizard. Every route needs the OWNER role (AC14) - the menu is never
 * the protection, so this is enforced here, not only in the sidebar (SAH-5 AC1, AC11).
 */
@RestController
@RequestMapping("/api/v1/platform/setup")
@Validated
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public SetupStateResponse getState(@AuthenticationPrincipal TenantPrincipal principal) {
        return SetupStateResponse.from(setupService.getState(principal.tenantId()));
    }

    @PutMapping("/steps/{step}")
    @PreAuthorize("hasRole('OWNER')")
    public SetupStateResponse putStep(@PathVariable @Min(1) @Max(5) int step, @RequestBody JsonNode body,
                                       @AuthenticationPrincipal TenantPrincipal principal) {
        return SetupStateResponse.from(setupService.saveStep(principal.tenantId(), step, body));
    }

    @GetMapping("/slug-availability")
    @PreAuthorize("hasRole('OWNER')")
    public SlugAvailabilityResponse slugAvailability(
            @RequestParam @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,23}$", message = "3 to 24 lowercase letters, numbers or hyphens")
            String slug,
            @AuthenticationPrincipal TenantPrincipal principal) {
        var result = setupService.checkSlugAvailability(principal.tenantId(), slug);
        return new SlugAvailabilityResponse(result.available(), result.alternatives());
    }

    @PostMapping("/complete")
    @PreAuthorize("hasRole('OWNER')")
    public SetupCompleteResponse complete(@AuthenticationPrincipal TenantPrincipal principal) {
        var result = setupService.complete(principal.tenantId(), principal.userId());
        return SetupCompleteResponse.of(result.brand());
    }
}
