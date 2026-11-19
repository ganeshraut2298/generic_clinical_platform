package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.GoodsReceiptService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.PurchaseViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
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
 * SAH-79-7 (docs/api/pharmacy.yaml 0.3.2, WP-E): goods receipts of the Central Store. Every handler is {@code OPERATION}: its
 * service calls the store guard (capability purchase). The body is read lazily, right after the gate. The post reads the second
 * signer's PIN <b>before</b> the posting transaction (ADR-029 P7): first a short check of the gate, the body, the store and
 * whether the receipt has Schedule X lines, then the colleague's PIN, then the transaction with the verified signer.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class GoodsReceiptController implements PharmacyApi {

    private final GoodsReceiptService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public GoodsReceiptController(GoodsReceiptService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/goods-receipts")
    public PurchaseViews.GrnPage list(@PathVariable String storeId,
                                      @RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "page", required = false) String page,
                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/goods-receipts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseViews.GoodsReceipt> create(@PathVariable String storeId,
                                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PurchaseViews.GoodsReceipt> outcome = service.create(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.GoodsReceiptCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/goods-receipts/{grnId}")
    public PurchaseViews.GoodsReceipt get(@PathVariable String storeId, @PathVariable String grnId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, grnId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/goods-receipts/{grnId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.GoodsReceipt update(@PathVariable String storeId, @PathVariable String grnId,
                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.update(user.tenantId(), user.userId(), storeId, grnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.GoodsReceiptUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/goods-receipts/{grnId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.GoodsReceipt discard(@PathVariable String storeId, @PathVariable String grnId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.discard(user.tenantId(), user.userId(), storeId, grnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/goods-receipts/{grnId}/reopen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.GoodsReceipt reopen(@PathVariable String storeId, @PathVariable String grnId,
                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.reopen(user.tenantId(), user.userId(), storeId, grnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/goods-receipts/{grnId}/post", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.GoodsReceipt post(@PathVariable String storeId, @PathVariable String grnId,
                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.PostWithSigner.class);
        PharmacyInputs.SecondSignerInput signer = service.precheck(user.tenantId(), user.userId(), storeId, grnId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.post(user.tenantId(), user.userId(), storeId, grnId, lazy, verified);
    }
}
