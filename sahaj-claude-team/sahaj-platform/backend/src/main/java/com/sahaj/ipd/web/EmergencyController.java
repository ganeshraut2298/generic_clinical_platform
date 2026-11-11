package com.sahaj.ipd.web;

import com.sahaj.ipd.application.EmergencyService;
import com.sahaj.ipd.application.EmergencyViews.CaseInput;
import com.sahaj.ipd.application.EmergencyViews.Counts;
import com.sahaj.ipd.application.EmergencyViews.LevelInput;
import com.sahaj.ipd.application.EmergencyViews.Queue;
import com.sahaj.ipd.application.EmergencyViews.Registration;
import com.sahaj.ipd.application.EmergencyViews.Settings;
import com.sahaj.ipd.application.EmergencyViews.SettingsInput;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-28 emergency queue, triage and Emergency setup (contract {@code docs/api/ipd.yaml}, ADR-005). The clinic, the
 * person and the role always come from the signed-in user. The role is checked again in the service, and so are the
 * module-off, read-only and every business answer.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class EmergencyController {

    /** Open the queue, register and re-triage (the service checks the role again). */
    static final String READERS = "hasAnyRole('OWNER','FRONT_DESK','NURSE','DOCTOR')";
    /** Emergency setup writes. */
    static final String OWNER = "hasRole('OWNER')";
    /** Doctor has seen, Treat and release, Refer out. */
    static final String ACTORS = "hasAnyRole('OWNER','NURSE','DOCTOR')";

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @GetMapping("/emergency")
    @PreAuthorize(READERS)
    public Queue queue() {
        ClinicUser user = ClinicUser.current();
        return service.queue(user.tenantId(), user.role());
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @GetMapping("/emergency/summary")
    @PreAuthorize(READERS)
    public Counts summary() {
        ClinicUser user = ClinicUser.current();
        return service.summary(user.tenantId(), user.role());
    }

    /** 201 for a new case; 200 with the first case for a repeat of the same client key. */
    @ScopeExempt("Emergency queue: not admitted, no ward")
    @PostMapping("/emergency/cases")
    @PreAuthorize(READERS)
    public ResponseEntity<Queue> register(@RequestBody CaseInput request) {
        ClinicUser user = ClinicUser.current();
        Registration result = service.register(user.tenantId(), user.userId(), user.role(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.queue());
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @PostMapping("/emergency/cases/{id}/seen")
    @PreAuthorize(ACTORS)
    public Queue seen(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.seen(user.tenantId(), user.userId(), user.role(), id);
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @PutMapping("/emergency/cases/{id}/level")
    @PreAuthorize(READERS)
    public Queue retriage(@PathVariable UUID id, @RequestBody LevelInput request) {
        ClinicUser user = ClinicUser.current();
        return service.retriage(user.tenantId(), user.userId(), user.role(), id, request);
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @PostMapping("/emergency/cases/{id}/release")
    @PreAuthorize(ACTORS)
    public Queue release(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.release(user.tenantId(), user.userId(), user.role(), id);
    }

    @ScopeExempt("Emergency queue: not admitted, no ward")
    @PostMapping("/emergency/cases/{id}/refer")
    @PreAuthorize(ACTORS)
    public Queue refer(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.refer(user.tenantId(), user.userId(), user.role(), id);
    }

    @ScopeExempt("Configuration: no patient data")
    @GetMapping("/emergency-settings")
    @PreAuthorize(READERS)
    public Settings settings() {
        ClinicUser user = ClinicUser.current();
        return service.settings(user.tenantId(), user.role());
    }

    @ScopeExempt("Configuration: no patient data")
    @PutMapping("/emergency-settings")
    @PreAuthorize(OWNER)
    public Settings updateSettings(@RequestBody SettingsInput request) {
        ClinicUser user = ClinicUser.current();
        return service.updateSettings(user.tenantId(), user.userId(), user.role(), request);
    }
}
