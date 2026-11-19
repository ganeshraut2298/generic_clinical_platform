package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.pharmacy.application.StockAdjustmentService;
import com.sahaj.pharmacy.application.StockViews;
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
 * SAH-79-5 (docs/api/pharmacy.yaml 0.3.0): stock adjustments of a store. Every handler is {@code OPERATION}: its service calls
 * the store guard. The create reads the second signer's PIN <b>before</b> the posting transaction (ADR-029 P7: the PIN check
 * runs outside any transaction): first a short check of the gate, the body and the store (so only a caller who may adjust stock
 * here can make a PIN try), then the colleague's PIN, then the transaction with the verified signer.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StockAdjustmentController implements PharmacyApi {

    private final StockAdjustmentService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public StockAdjustmentController(StockAdjustmentService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-adjustments")
    public StockViews.StockAdjustmentPage list(@PathVariable String storeId,
                                               @RequestParam(name = "status", required = false) String status,
                                               @RequestParam(name = "page", required = false) String page,
                                               @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/stock-adjustments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockViews.StockAdjustment> create(@PathVariable String storeId,
                                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.StockAdjustmentCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.StockAdjustmentCreate.class);
        PharmacyInputs.SecondSignerInput signer = service.precheck(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<StockViews.StockAdjustment> outcome = service.create(user.tenantId(), user.userId(), storeId,
                lazy, verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-adjustments/{adjustmentId}")
    public StockViews.StockAdjustment get(@PathVariable String storeId, @PathVariable String adjustmentId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, adjustmentId);
    }
}
