package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.CommandCentreService;
import com.sahaj.pharmacy.application.CommandCentreViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-80-20 (docs/api/pharmacy-sales.yaml, WP-N): the Pharmacy Command Center cards. The store is a query value, so the operation is
 * {@code SCOPED}: the service keeps to the stores the caller can reach (404 for any other store, rule 15).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class CommandCentreController implements PharmacyApi {

    private final CommandCentreService service;

    public CommandCentreController(CommandCentreService service) {
        this.service = service;
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/command-centre")
    public CommandCentreViews.CommandCentre commandCentre(@RequestParam(name = "storeId", required = false) String storeId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId);
    }
}
