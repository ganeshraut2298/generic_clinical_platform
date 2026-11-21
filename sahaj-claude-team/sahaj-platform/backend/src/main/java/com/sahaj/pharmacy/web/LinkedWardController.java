package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.LinkedWardService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-79-16 (docs/api/pharmacy.yaml 0.3.6): the IPD wards and the linked ward of a ward store. OWNER only. */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class LinkedWardController implements PharmacyApi {

    private final LinkedWardService linked;
    private final ObjectMapper mapper;

    public LinkedWardController(LinkedWardService linked, ObjectMapper mapper) {
        this.linked = linked;
        this.mapper = mapper;
    }

    @GetMapping("/ipd-wards")
    public PharmacyViews.WardList wards() {
        ClinicUser user = ClinicUser.current();
        return linked.wards(user.tenantId(), user.userId());
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PutMapping(value = "/stores/{storeId}/linked-ward", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.Store set(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return linked.setLinkedWard(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.LinkedWard.class));
    }
}
