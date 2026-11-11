package com.sahaj.ipd.web;

import com.sahaj.ipd.application.PackageService;
import com.sahaj.ipd.application.PackageViews.PackageList;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-29 the read-only Packages list (contract {@code docs/api/ipd.yaml}, {@code GET /packages}). The clinic always
 * comes from the signed-in user. No create, change or attach endpoint exists in this slice (DEC-030).
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class PackageController {

    private final PackageService service;

    public PackageController(PackageService service) {
        this.service = service;
    }

    @ScopeExempt("Configuration: no patient data")
    @GetMapping("/packages")
    @PreAuthorize(AdmissionController.READERS)
    public PackageList list() {
        return service.list(ClinicUser.current().tenantId());
    }
}
