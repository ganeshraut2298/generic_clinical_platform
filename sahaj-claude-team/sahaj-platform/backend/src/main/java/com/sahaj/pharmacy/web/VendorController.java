package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyMasterInputs;
import com.sahaj.pharmacy.application.PharmacyMasterViews;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.VendorService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78-8 (docs/api/pharmacy.yaml): vendors. None of these handlers works inside a store, so none carries a store marker.
 * Path ids, query values and bodies are taken as text and checked by the service after the module and access-level checks.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/vendors")
public class VendorController implements PharmacyApi {

    private final VendorService vendors;
    private final ObjectMapper mapper;

    public VendorController(VendorService vendors, ObjectMapper mapper) {
        this.vendors = vendors;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyMasterViews.VendorPage list(@RequestParam(name = "q", required = false) String q,
                                               @RequestParam(name = "status", required = false) String status,
                                               @RequestParam(name = "suppliesStoreId", required = false) String suppliesStoreId,
                                               @RequestParam(name = "page", required = false) String page,
                                               @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return vendors.list(user.tenantId(), user.userId(), q, status, suppliesStoreId, page, size);
    }

    /** 201 when the call created the vendor, 200 when the same client key was sent before by the same caller. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PharmacyMasterViews.Vendor> create(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PharmacyMasterViews.Vendor> outcome = vendors.create(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.VendorCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @GetMapping("/{vendorId}")
    public PharmacyMasterViews.Vendor get(@PathVariable String vendorId) {
        ClinicUser user = ClinicUser.current();
        return vendors.get(user.tenantId(), user.userId(), vendorId);
    }

    @PutMapping(value = "/{vendorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyMasterViews.Vendor update(@PathVariable String vendorId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return vendors.update(user.tenantId(), user.userId(), vendorId,
                PharmacyBodies.of(mapper, body, PharmacyMasterInputs.VendorUpdate.class));
    }

    @PostMapping("/{vendorId}/switch-off")
    public PharmacyMasterViews.Vendor switchOff(@PathVariable String vendorId) {
        ClinicUser user = ClinicUser.current();
        return vendors.switchOff(user.tenantId(), user.userId(), vendorId);
    }

    @PostMapping("/{vendorId}/switch-on")
    public PharmacyMasterViews.Vendor switchOn(@PathVariable String vendorId) {
        ClinicUser user = ClinicUser.current();
        return vendors.switchOn(user.tenantId(), user.userId(), vendorId);
    }
}
