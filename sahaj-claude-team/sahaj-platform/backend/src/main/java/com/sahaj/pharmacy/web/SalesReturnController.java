package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SalesReturnService;
import com.sahaj.pharmacy.application.SalesReturnViews;
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
 * SAH-80-18 (docs/api/pharmacy-sales.yaml, WP-L): the sales return. Every handler is {@code OPERATION}. A create, an accept or a
 * finish with a Schedule X line reads the second signer's PIN <b>before</b> the transaction (ADR-029 P7): a short pre-check of the
 * gate, the body and the store, then the colleague's PIN, then the transaction with the verified signer.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class SalesReturnController implements PharmacyApi {

    private final SalesReturnService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public SalesReturnController(SalesReturnService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sales-returns/bills")
    public SalesReturnViews.ReturnableBillList findBills(@PathVariable String storeId,
                                                         @RequestParam(name = "number", required = false) String number,
                                                         @RequestParam(name = "customer", required = false) String customer) {
        ClinicUser user = ClinicUser.current();
        return service.findBills(user.tenantId(), user.userId(), storeId, number, customer);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sales-returns/bills/{billId}")
    public SalesReturnViews.ReturnableBill getBill(@PathVariable String storeId, @PathVariable String billId) {
        ClinicUser user = ClinicUser.current();
        return service.getBill(user.tenantId(), user.userId(), storeId, billId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sales-returns")
    public SalesReturnViews.SalesReturnPage list(@PathVariable String storeId,
                                                 @RequestParam(name = "status", required = false) String status,
                                                 @RequestParam(name = "page", required = false) String page,
                                                 @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/sales-returns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SalesReturnViews.SalesReturn> create(@PathVariable String storeId,
                                                               @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<SaleInputs.SalesReturnCreate> lazy = PharmacyBodies.of(mapper, body, SaleInputs.SalesReturnCreate.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckCreate(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<SalesReturnViews.SalesReturn> outcome = service.create(user.tenantId(), user.userId(), storeId, lazy,
                verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sales-returns/{returnId}")
    public SalesReturnViews.SalesReturn get(@PathVariable String storeId, @PathVariable String returnId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, returnId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/sales-returns/{returnId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SalesReturnViews.SalesReturn accept(@PathVariable String storeId, @PathVariable String returnId,
                                               @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<SaleInputs.AcceptSalesReturnInput> lazy = PharmacyBodies.of(mapper, body,
                SaleInputs.AcceptSalesReturnInput.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckAccept(user.tenantId(), user.userId(), storeId, returnId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.accept(user.tenantId(), user.userId(), storeId, returnId, lazy, verified);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/sales-returns/{returnId}/finish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SalesReturnViews.SalesReturn finish(@PathVariable String storeId, @PathVariable String returnId,
                                               @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.PostWithSigner.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckFinish(user.tenantId(), user.userId(), storeId, returnId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.finish(user.tenantId(), user.userId(), storeId, returnId, lazy, verified);
    }
}
