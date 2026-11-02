package com.sahaj.opd.web;

import com.sahaj.opd.application.ServiceCatalogService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SAH-7 price list. Any clinic user reads; every change is owner-only (AC4). The clinic comes from the signed-in user. */
@RestController
@RequestMapping("/api/v1/opd/services")
public class ServiceController {

    private final ServiceCatalogService service;

    public ServiceController(ServiceCatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<ServiceResponse> list(@RequestParam(required = false) Boolean active) {
        return service.list(ClinicUser.current().tenantId(), active).stream().map(ServiceResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OpdRoles.OWNER)
    public ServiceResponse add(@RequestBody ServiceRequest request) {
        ClinicUser user = ClinicUser.current();
        return ServiceResponse.from(service.add(user.tenantId(), user.userId(), request.toInput()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(OpdRoles.OWNER)
    public ServiceResponse update(@PathVariable UUID id, @RequestBody ServiceRequest request) {
        ClinicUser user = ClinicUser.current();
        return ServiceResponse.from(service.update(user.tenantId(), user.userId(), id, request.toInput()));
    }
}
