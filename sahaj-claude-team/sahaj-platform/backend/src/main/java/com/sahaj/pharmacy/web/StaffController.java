package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.StaffMappingService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78-4 (docs/api/pharmacy.yaml): mapping team members to stores. The store handlers are {@code MASTER}; the
 * candidates list has no store in its path (the store is a query value). Path ids, query values and bodies are taken as
 * text and checked by the service after the module and access-level checks (contract order: 403, then 400, then 404).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StaffController implements PharmacyApi {

    private final StaffMappingService staff;
    private final ObjectMapper mapper;

    public StaffController(StaffMappingService staff, ObjectMapper mapper) {
        this.staff = staff;
        this.mapper = mapper;
    }

    @GetMapping("/staff-candidates")
    public PharmacyViews.StaffCandidates candidates(
            @RequestParam(name = "storeId", required = false) String storeId,
            @RequestParam(name = "storeRole", required = false) String storeRole) {
        ClinicUser user = ClinicUser.current();
        return staff.candidates(user.tenantId(), user.userId(), storeId, storeRole);
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @GetMapping("/stores/{storeId}/staff")
    public PharmacyViews.StaffMappings list(@PathVariable String storeId,
            @RequestParam(name = "includeRemoved", required = false) String includeRemoved) {
        ClinicUser user = ClinicUser.current();
        return staff.list(user.tenantId(), user.userId(), storeId, includeRemoved);
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PostMapping(value = "/stores/{storeId}/staff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyViews.StaffMapping map(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return staff.map(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.MapStaff.class));
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PutMapping(value = "/stores/{storeId}/staff/{mappingId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.StaffMapping change(@PathVariable String storeId, @PathVariable String mappingId,
                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return staff.change(user.tenantId(), user.userId(), storeId, mappingId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.ChangeMapping.class));
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PostMapping("/stores/{storeId}/staff/{mappingId}/remove")
    public PharmacyViews.StaffMapping remove(@PathVariable String storeId, @PathVariable String mappingId) {
        ClinicUser user = ClinicUser.current();
        return staff.remove(user.tenantId(), user.userId(), storeId, mappingId);
    }
}
