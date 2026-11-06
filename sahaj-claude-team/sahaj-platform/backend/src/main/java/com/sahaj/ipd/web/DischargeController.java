package com.sahaj.ipd.web;

import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.DischargeService;
import com.sahaj.ipd.application.DischargeViews.DischargeInput;
import com.sahaj.ipd.application.DischargeViews.Settings;
import com.sahaj.ipd.application.DischargeViews.SettingsInput;
import com.sahaj.ipd.application.DischargeViews.Sheet;
import com.sahaj.ipd.application.DischargeViews.SignoffInput;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-27 Discharge setup, the Discharge sheet, the sign-off and the discharge (contract
 * {@code docs/api/ipd.yaml}). The clinic, the person and the role always come from the signed-in user. Module-off,
 * read-only and every business answer come from the service.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class DischargeController {

    /** OQ-27-1: only a doctor or the owner signs the summary. */
    static final String SIGNERS = "hasAnyRole('OWNER','DOCTOR')";
    /** OQ-27-1: only the front desk or the owner completes a discharge. */
    static final String DISCHARGERS = "hasAnyRole('OWNER','FRONT_DESK')";

    private final DischargeService service;

    public DischargeController(DischargeService service) {
        this.service = service;
    }

    @GetMapping("/discharge-settings")
    @PreAuthorize(AdmissionController.READERS)
    public Settings settings() {
        ClinicUser user = ClinicUser.current();
        return service.settings(user.tenantId(), user.role());
    }

    @PutMapping("/discharge-settings")
    @PreAuthorize(AdmissionController.OWNER)
    public Settings updateSettings(@RequestBody SettingsInput request) {
        ClinicUser user = ClinicUser.current();
        return service.updateSettings(user.tenantId(), user.userId(), request);
    }

    @GetMapping("/admissions/{id}/discharge")
    @PreAuthorize(AdmissionController.READERS)
    public Sheet sheet(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.sheet(user.tenantId(), user.userId(), user.role(), id);
    }

    @PutMapping("/admissions/{id}/discharge/signoff")
    @PreAuthorize(SIGNERS)
    public Sheet signoff(@PathVariable UUID id, @RequestBody SignoffInput request) {
        ClinicUser user = ClinicUser.current();
        return service.signoff(user.tenantId(), user.userId(), user.role(), id, request);
    }

    @PostMapping("/admissions/{id}/discharge")
    @PreAuthorize(DISCHARGERS)
    public Detail discharge(@PathVariable UUID id, @RequestBody DischargeInput request) {
        ClinicUser user = ClinicUser.current();
        return service.discharge(user.tenantId(), user.userId(), user.role(), id, request);
    }
}
