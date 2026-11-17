package com.sahaj.ipd.web;

import com.sahaj.ipd.application.NursingSettingsService;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.Settings;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.ipd.application.NursingSettingsViews.WardProfileInput;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-76-6 nursing settings, ward profiles and the profile of each ward (contract {@code docs/api/ipd.yaml} 0.4.0). The
 * clinic, the person and the role always come from the signed-in user. The service re-checks the role and answers
 * module-off, read-only and every business refusal. Configuration only: no patient data.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class NursingSettingsController {

    /** Every clinic role passes here on purpose: the service gives each refused role the contract's own 403 text. */
    private static final String ANY = IpdController.ANY_CLINIC_ROLE;

    private final NursingSettingsService service;

    public NursingSettingsController(NursingSettingsService service) {
        this.service = service;
    }

    @ScopeExempt("Configuration: no patient data")
    @GetMapping("/nursing-settings")
    @PreAuthorize(ANY)
    public Settings read() {
        ClinicUser user = ClinicUser.current();
        return service.read(user.tenantId(), user.userId(), user.role());
    }

    @ScopeExempt("Configuration: no patient data")
    @PutMapping("/nursing-settings")
    @PreAuthorize(ANY)
    public Settings save(@RequestBody SettingsInput request) {
        ClinicUser user = ClinicUser.current();
        return service.saveSettings(user.tenantId(), user.userId(), user.role(), request);
    }

    @ScopeExempt("Configuration: no patient data")
    @PostMapping("/nursing-profiles")
    @PreAuthorize(ANY)
    @ResponseStatus(HttpStatus.CREATED)
    public Settings addProfile(@RequestBody ProfileInput request) {
        ClinicUser user = ClinicUser.current();
        return service.addProfile(user.tenantId(), user.userId(), user.role(), request);
    }

    @ScopeExempt("Configuration: no patient data")
    @PutMapping("/nursing-profiles/{key}")
    @PreAuthorize(ANY)
    public Settings changeProfile(@PathVariable String key, @RequestBody ProfileInput request) {
        ClinicUser user = ClinicUser.current();
        return service.changeProfile(user.tenantId(), user.userId(), user.role(), key, request);
    }

    @ScopeExempt("Configuration: no patient data")
    @PutMapping("/wards/{id}/nursing-profile")
    @PreAuthorize(ANY)
    public Settings setWardProfile(@PathVariable UUID id, @RequestBody WardProfileInput request) {
        ClinicUser user = ClinicUser.current();
        return service.setWardProfile(user.tenantId(), user.userId(), user.role(), id, request);
    }
}
