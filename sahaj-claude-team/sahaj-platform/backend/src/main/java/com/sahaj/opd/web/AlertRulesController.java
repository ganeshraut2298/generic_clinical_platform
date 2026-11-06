package com.sahaj.opd.web;

import com.sahaj.opd.application.AlertRulesService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-18: alert and escalation rules. Any clinic user reads; only the owner changes. */
@RestController
@RequestMapping("/api/v1/opd/alert-rules")
public class AlertRulesController {

    private final AlertRulesService service;

    public AlertRulesController(AlertRulesService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public AlertRulesDto get() {
        return AlertRulesDto.from(service.get(ClinicUser.current().tenantId()));
    }

    @PostMapping
    @PreAuthorize(OpdRoles.OWNER)
    public AlertRulesDto add(@RequestBody AlertRulesDto.AddRequest request) {
        ClinicUser user = ClinicUser.current();
        return AlertRulesDto.from(
                service.add(user.tenantId(), user.userId(), request.when(), request.cond(), request.then()));
    }

    @PostMapping("/starter")
    @PreAuthorize(OpdRoles.OWNER)
    public AlertRulesDto saveStarter() {
        ClinicUser user = ClinicUser.current();
        return AlertRulesDto.from(service.saveStarter(user.tenantId(), user.userId()));
    }

    @PostMapping("/suggestions/{index}")
    @PreAuthorize(OpdRoles.OWNER)
    public AlertRulesDto addSuggestion(@PathVariable int index) {
        ClinicUser user = ClinicUser.current();
        return AlertRulesDto.from(service.addSuggestion(user.tenantId(), user.userId(), index));
    }

    @PutMapping("/{id}")
    @PreAuthorize(OpdRoles.OWNER)
    public AlertRulesDto toggle(@PathVariable UUID id, @RequestBody AlertRulesDto.ToggleRequest request) {
        ClinicUser user = ClinicUser.current();
        return AlertRulesDto.from(service.setEnabled(user.tenantId(), user.userId(), id, request.enabled()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(OpdRoles.OWNER)
    public AlertRulesDto delete(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return AlertRulesDto.from(service.delete(user.tenantId(), user.userId(), id));
    }
}
