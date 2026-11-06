package com.sahaj.platform.web;

import com.sahaj.platform.application.StaffPinService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-31 (ADR-010): the signed-in person's own staff PIN. Any clinic role may set their own PIN; nobody can set,
 * read or check another person's PIN here. The clinic and the person come from the session only. The PIN and the
 * one-time code are never logged or echoed.
 */
@RestController
@RequestMapping("/api/v1/platform/me/pin")
public class MePinController {

    private final StaffPinService staffPinService;

    public MePinController(StaffPinService staffPinService) {
        this.staffPinService = staffPinService;
    }

    @GetMapping
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public PinStatusResponse status(@AuthenticationPrincipal TenantPrincipal principal) {
        return PinStatusResponse.from(staffPinService.status(principal.tenantId(), principal.userId()));
    }

    @PostMapping("/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public PinCodeSentResponse sendCode(@AuthenticationPrincipal TenantPrincipal principal) {
        return PinCodeSentResponse.from(staffPinService.sendCode(principal.tenantId(), principal.userId()));
    }

    @PutMapping
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public PinStatusResponse set(@RequestBody PinSetRequest request,
                                 @AuthenticationPrincipal TenantPrincipal principal) {
        return PinStatusResponse.from(
                staffPinService.set(principal.tenantId(), principal.userId(), request.toInput()));
    }
}
