package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrSettingsService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-44-3: how the EMR opens in this clinic. Every clinic role reads; only the owner saves. */
@RestController
@RequestMapping("/api/v1/opd/emr/settings")
public class EmrSettingsController {

    private final EmrSettingsService service;

    public EmrSettingsController(EmrSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public EmrResponses.Settings get() {
        return EmrResponses.Settings.from(service.get(ClinicUser.current()));
    }

    @PutMapping
    @PreAuthorize(OpdRoles.OWNER)
    public EmrResponses.Settings save(@RequestBody EmrRequests.Settings body) {
        return EmrResponses.Settings.from(
                service.save(ClinicUser.current(), body.sectionPanel(), body.rightPanelFirst(), body.version()));
    }
}
