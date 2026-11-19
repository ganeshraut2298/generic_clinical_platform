package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.PurchaseOrderService;
import com.sahaj.pharmacy.application.PurchaseViews;
import com.sahaj.platform.api.ClinicUser;
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
 * SAH-79-6 (docs/api/pharmacy.yaml 0.3.1, WP-E): the reorder list and the purchase orders of the Central Store. Every handler is
 * {@code OPERATION}: its service calls the store guard (capability purchase). The body is read lazily, right after the gate.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PurchaseOrderController implements PharmacyApi {

    private final PurchaseOrderService service;
    private final ObjectMapper mapper;

    public PurchaseOrderController(PurchaseOrderService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/reorder-suggestions")
    public PurchaseViews.ReorderPage reorder(@PathVariable String storeId,
                                             @RequestParam(name = "page", required = false) String page,
                                             @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.reorder(user.tenantId(), user.userId(), storeId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/from-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseViews.PoDrafts> fromSuggestions(@PathVariable String storeId,
                                                                  @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PurchaseViews.PoDrafts> outcome = service.createFromSuggestions(user.tenantId(), user.userId(),
                storeId, PharmacyBodies.of(mapper, body, PharmacyInputs.PurchaseOrdersFromSuggestions.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/purchase-orders")
    public PurchaseViews.PoPage list(@PathVariable String storeId,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "overdue", required = false) String overdue,
                                     @RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, overdue, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseViews.PurchaseOrder> create(@PathVariable String storeId,
                                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> outcome = service.create(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.PurchaseOrderCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/purchase-orders/{poId}")
    public PurchaseViews.PurchaseOrder get(@PathVariable String storeId, @PathVariable String poId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, poId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/purchase-orders/{poId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder update(@PathVariable String storeId, @PathVariable String poId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.update(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.PurchaseOrderUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder discard(@PathVariable String storeId, @PathVariable String poId,
                                               @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.discard(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder submit(@PathVariable String storeId, @PathVariable String poId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.submit(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionClientKeyInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/mark-sent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder markSent(@PathVariable String storeId, @PathVariable String poId,
                                                @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.markSent(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.MarkSentInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder cancel(@PathVariable String storeId, @PathVariable String poId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.cancel(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionReasonInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/close-short", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.PurchaseOrder closeShort(@PathVariable String storeId, @PathVariable String poId,
                                                  @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.closeShort(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionReasonInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/purchase-orders/{poId}/copy", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseViews.PurchaseOrder> copy(@PathVariable String storeId, @PathVariable String poId,
                                                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> outcome = service.copy(user.tenantId(), user.userId(), storeId, poId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.ClientKeyInput.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }
}
