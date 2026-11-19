package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.SecondSignerService;
import com.sahaj.pharmacy.application.StockViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-79-5 (docs/api/pharmacy.yaml 0.3.2): the select of the Schedule X second signature. OPERATION: the service calls the guard. */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class SecondSignerController implements PharmacyApi {

    private final SecondSignerService service;

    public SecondSignerController(SecondSignerService service) {
        this.service = service;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/second-signers")
    public StockViews.SecondSignerCandidates list(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId);
    }
}
