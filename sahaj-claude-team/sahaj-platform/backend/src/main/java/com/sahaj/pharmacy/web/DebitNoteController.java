package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.DebitNoteService;
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
 * SAH-79-8 (docs/api/pharmacy.yaml 0.3.2, WP-E): vendor returns and debit notes of the Central Store. Every handler is
 * {@code OPERATION}: its service calls the store guard (capability purchase). The hand-over reads the second signer's PIN
 * <b>before</b> the transaction (ADR-029 P7), as the receipt post does.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class DebitNoteController implements PharmacyApi {

    private final DebitNoteService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public DebitNoteController(DebitNoteService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/vendor-returns/eligible")
    public PurchaseViews.VendorReturnEligible eligible(@PathVariable String storeId,
                                                       @RequestParam(name = "vendorId", required = false) String vendorId) {
        ClinicUser user = ClinicUser.current();
        return service.eligible(user.tenantId(), user.userId(), storeId, vendorId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/debit-notes")
    public PurchaseViews.DnPage list(@PathVariable String storeId,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "vendorId", required = false) String vendorId,
                                     @RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, vendorId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PurchaseViews.DebitNote> create(@PathVariable String storeId,
                                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PurchaseViews.DebitNote> outcome = service.create(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.DebitNoteCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/debit-notes/{dnId}")
    public PurchaseViews.DebitNote get(@PathVariable String storeId, @PathVariable String dnId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, dnId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/debit-notes/{dnId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote update(@PathVariable String storeId, @PathVariable String dnId,
                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.update(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.DebitNoteUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote discard(@PathVariable String storeId, @PathVariable String dnId,
                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.discard(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote submit(@PathVariable String storeId, @PathVariable String dnId,
                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.submit(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionClientKeyInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/picked", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote picked(@PathVariable String storeId, @PathVariable String dnId,
                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.picked(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/hand-over", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote handOver(@PathVariable String storeId, @PathVariable String dnId,
                                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.HandOver> lazy = PharmacyBodies.of(mapper, body, PharmacyInputs.HandOver.class);
        PharmacyInputs.SecondSignerInput signer = service.precheck(user.tenantId(), user.userId(), storeId, dnId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.handOver(user.tenantId(), user.userId(), storeId, dnId, lazy, verified);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/credit-note", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote creditNote(@PathVariable String storeId, @PathVariable String dnId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.recordCreditNote(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.CreditNote.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/debit-notes/{dnId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseViews.DebitNote cancel(@PathVariable String storeId, @PathVariable String dnId,
                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.cancel(user.tenantId(), user.userId(), storeId, dnId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionReasonInput.class));
    }
}
