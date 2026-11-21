package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ConsumptionService;
import com.sahaj.pharmacy.application.ConsumptionViews;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-79-16 (docs/api/pharmacy.yaml 0.3.6, WP-G): ward consumption. The store operations are {@code OPERATION}: the service calls the
 * store guard. The ordered-by options are not under a store path. A Schedule X entry has its second signer's PIN checked here,
 * between the service's pre-check and its transaction ({@link ScheduleXSignature#verify} must run outside any transaction).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ConsumptionController implements PharmacyApi {

    private final ConsumptionService consumption;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public ConsumptionController(ConsumptionService consumption, ScheduleXSignature signature, ObjectMapper mapper) {
        this.consumption = consumption;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/consumption/admitted-patients")
    public ConsumptionViews.AdmittedList admittedPatients(@PathVariable String storeId,
                                                          @RequestParam(name = "q", required = false) String q) {
        ClinicUser user = ClinicUser.current();
        return consumption.admittedPatients(user.tenantId(), user.userId(), storeId, q);
    }

    @GetMapping("/consumption/ordered-by-options")
    public ConsumptionViews.OrderedByList orderedByOptions() {
        ClinicUser user = ClinicUser.current();
        return consumption.orderedByOptions(user.tenantId(), user.userId());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/consumptions")
    public ConsumptionViews.Page list(@PathVariable String storeId, @RequestParam(name = "date", required = false) String date,
                                      @RequestParam(name = "page", required = false) String page,
                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return consumption.list(user.tenantId(), user.userId(), storeId, date, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/consumptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConsumptionViews.Consumption> create(@PathVariable String storeId,
                                                               @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.ConsumptionCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.ConsumptionCreate.class);
        PharmacyInputs.SecondSignerInput signer = consumption.precheck(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<ConsumptionViews.Consumption> outcome = consumption.create(user.tenantId(), user.userId(), storeId,
                lazy, verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/consumptions/{consumptionId}")
    public ConsumptionViews.Consumption get(@PathVariable String storeId, @PathVariable String consumptionId) {
        ClinicUser user = ClinicUser.current();
        return consumption.get(user.tenantId(), user.userId(), storeId, consumptionId);
    }
}
