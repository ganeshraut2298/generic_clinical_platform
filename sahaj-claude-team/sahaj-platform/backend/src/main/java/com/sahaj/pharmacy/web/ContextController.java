package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ContextService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.SetupSummaryService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-78-1 and 78-4 (docs/api/pharmacy.yaml): the context, the working store and the setup summary. The clinic and the
 * person come from the signed-in user only. Access is checked in the services, in the order of the contract: module off
 * (403 {@code module-off}), then the access level (403 {@code forbidden}), then a body that cannot be read (400), then the
 * store. The body is taken as raw text and read by the service after its gate ({@link PharmacyBodies}).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ContextController implements PharmacyApi {

    private final ContextService context;
    private final SetupSummaryService summary;
    private final ObjectMapper mapper;

    public ContextController(ContextService context, SetupSummaryService summary, ObjectMapper mapper) {
        this.context = context;
        this.summary = summary;
        this.mapper = mapper;
    }

    @GetMapping("/context")
    public PharmacyViews.Context context() {
        ClinicUser user = ClinicUser.current();
        return context.get(user.tenantId(), user.userId());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/context/working-store", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.Context setWorkingStore(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return context.setWorkingStore(user.tenantId(), user.userId(),
                PharmacyBodies.of(mapper, body, PharmacyInputs.WorkingStore.class));
    }

    @GetMapping("/setup-summary")
    public PharmacyViews.SetupSummary setupSummary() {
        ClinicUser user = ClinicUser.current();
        return summary.get(user.tenantId(), user.userId());
    }
}
