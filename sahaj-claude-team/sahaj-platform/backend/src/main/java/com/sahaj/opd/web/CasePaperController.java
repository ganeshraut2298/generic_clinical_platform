package com.sahaj.opd.web;

import com.sahaj.opd.application.CasePaperService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-7 Case paper and Rx settings. Any clinic user reads; only the owner saves. */
@RestController
@RequestMapping("/api/v1/opd/case-paper-settings")
public class CasePaperController {

    private final CasePaperService service;

    public CasePaperController(CasePaperService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public CasePaperDto get() {
        return CasePaperDto.from(service.get(ClinicUser.current().tenantId()));
    }

    @PutMapping
    @PreAuthorize(OpdRoles.OWNER)
    public CasePaperDto save(@RequestBody CasePaperDto request) {
        ClinicUser user = ClinicUser.current();
        return CasePaperDto.from(service.save(user.tenantId(), user.userId(), request.toInput()));
    }
}
