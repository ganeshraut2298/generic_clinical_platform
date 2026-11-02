package com.sahaj.opd.web;

import com.sahaj.opd.application.FeeSettingsService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-7 Fees and billing settings. Any clinic user reads; only the owner saves. */
@RestController
@RequestMapping("/api/v1/opd/fee-settings")
public class FeeSettingsController {

    private final FeeSettingsService service;

    public FeeSettingsController(FeeSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public FeeSettingsDto get() {
        return FeeSettingsDto.from(service.get(ClinicUser.current().tenantId()));
    }

    @PutMapping
    @PreAuthorize(OpdRoles.OWNER)
    public FeeSettingsDto save(@RequestBody FeeSettingsDto request) {
        ClinicUser user = ClinicUser.current();
        return FeeSettingsDto.from(service.save(user.tenantId(), user.userId(), request.toInput()));
    }
}
