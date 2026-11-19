package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.DispatchReceiptService;
import com.sahaj.pharmacy.application.DispatchViews;
import com.sahaj.pharmacy.application.IndentDispatchService;
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
 * SAH-79-11 and 79-12 (docs/api/pharmacy.yaml 0.3.5, WP-F): dispatch an indent, read a dispatch, list the dispatches on their way
 * to a store, and confirm receipt. Every handler is {@code OPERATION}: its service calls the store guard. The body is read
 * lazily, right after the gate. A Schedule X dispatch or receipt has its second signer's PIN checked here, between the service's
 * pre-check and its transaction ({@link ScheduleXSignature#verify} must run outside any transaction).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class DispatchController implements PharmacyApi {

    private final IndentDispatchService dispatches;
    private final DispatchReceiptService receipts;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public DispatchController(IndentDispatchService dispatches, DispatchReceiptService receipts, ScheduleXSignature signature,
                              ObjectMapper mapper) {
        this.dispatches = dispatches;
        this.receipts = receipts;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/indents/{indentId}/lines/{indentLineId}/replacement-options")
    public DispatchViews.ReplacementOptions replacementOptions(@PathVariable String storeId, @PathVariable String indentId,
                                                               @PathVariable String indentLineId) {
        ClinicUser user = ClinicUser.current();
        return dispatches.replacementOptions(user.tenantId(), user.userId(), storeId, indentId, indentLineId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/indents/{indentId}/dispatches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DispatchViews.Dispatch> dispatch(@PathVariable String storeId, @PathVariable String indentId,
                                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.IndentDispatch> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.IndentDispatch.class);
        PharmacyInputs.SecondSignerInput signer = dispatches.precheck(user.tenantId(), user.userId(), storeId, indentId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<DispatchViews.Dispatch> outcome = dispatches.dispatch(user.tenantId(), user.userId(), storeId,
                indentId, lazy, verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/dispatches")
    public DispatchViews.DispatchPage incoming(@PathVariable String storeId,
                                               @RequestParam(name = "status", required = false) String status,
                                               @RequestParam(name = "page", required = false) String page,
                                               @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return dispatches.incoming(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/dispatches/{dispatchId}")
    public DispatchViews.Dispatch get(@PathVariable String storeId, @PathVariable String dispatchId) {
        ClinicUser user = ClinicUser.current();
        return dispatches.get(user.tenantId(), user.userId(), storeId, dispatchId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/dispatches/{dispatchId}/receipt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DispatchViews.Dispatch receive(@PathVariable String storeId, @PathVariable String dispatchId,
                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.DispatchReceipt> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.DispatchReceipt.class);
        PharmacyInputs.SecondSignerInput signer = receipts.precheck(user.tenantId(), user.userId(), storeId, dispatchId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return receipts.receive(user.tenantId(), user.userId(), storeId, dispatchId, lazy, verified);
    }
}
