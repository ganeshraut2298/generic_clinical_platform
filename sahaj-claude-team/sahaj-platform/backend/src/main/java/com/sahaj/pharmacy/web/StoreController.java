package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.StoreService;
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
 * SAH-78-3 (docs/api/pharmacy.yaml): the stores of the unit. All handlers are {@code MASTER}: they read or change the store
 * as a master record and are gated by the access level (SETUP_VIEW or SETUP_EDIT), never by a mapping. A path id that is
 * not a UUID, a query value that is not valid and a body that cannot be read are all taken as text and answered 400 by the
 * service, after the module and access-level checks (module off 403, access level 403, then 400, then 404).
 */
@RestController
@RequestMapping("/api/v1/pharmacy/stores")
public class StoreController implements PharmacyApi {

    private final StoreService stores;
    private final ObjectMapper mapper;

    public StoreController(StoreService stores, ObjectMapper mapper) {
        this.stores = stores;
        this.mapper = mapper;
    }

    @GetMapping
    public PharmacyViews.StoreList list(@RequestParam(name = "includeInactive", required = false) String includeInactive) {
        ClinicUser user = ClinicUser.current();
        return stores.list(user.tenantId(), user.userId(), includeInactive);
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyViews.Store create(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return stores.create(user.tenantId(), user.userId(), PharmacyBodies.of(mapper, body, PharmacyInputs.StoreCreate.class));
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @GetMapping("/{storeId}")
    public PharmacyViews.Store get(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return stores.get(user.tenantId(), user.userId(), storeId);
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PutMapping(value = "/{storeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.Store update(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return stores.update(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.StoreUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.MASTER)
    @PostMapping("/{storeId}/switch-off")
    public PharmacyViews.Store switchOff(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return stores.switchOff(user.tenantId(), user.userId(), storeId);
    }
}
