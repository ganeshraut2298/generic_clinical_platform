package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.pharmacy.application.TransferDispatchService;
import com.sahaj.pharmacy.application.TransferService;
import com.sahaj.pharmacy.application.TransferViews;
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
 * SAH-79-15 (docs/api/pharmacy.yaml 0.3.6, WP-G): store-to-store transfers. Every handler is {@code OPERATION}: its service calls the
 * store guard. A dispatch with a Schedule X line has its second signer's PIN checked here, between the service's pre-check and its
 * transaction ({@link ScheduleXSignature#verify} must run outside any transaction).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class TransferController implements PharmacyApi {

    private final TransferService transfers;
    private final TransferDispatchService dispatches;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public TransferController(TransferService transfers, TransferDispatchService dispatches, ScheduleXSignature signature,
                              ObjectMapper mapper) {
        this.transfers = transfers;
        this.dispatches = dispatches;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/transfers")
    public TransferViews.Page list(@PathVariable String storeId, @RequestParam(name = "open", required = false) String open,
                                   @RequestParam(name = "page", required = false) String page,
                                   @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return transfers.list(user.tenantId(), user.userId(), storeId, open, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransferViews.Transfer> create(@PathVariable String storeId,
                                                         @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.TransferCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.TransferCreate.class);
        PharmacyViews.Outcome<TransferViews.Transfer> outcome = transfers.create(user.tenantId(), user.userId(), storeId, lazy);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/transfer-stores")
    public TransferViews.StoreList transferStores(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return transfers.transferStores(user.tenantId(), user.userId(), storeId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/transfers/{transferId}")
    public TransferViews.Transfer get(@PathVariable String storeId, @PathVariable String transferId) {
        ClinicUser user = ClinicUser.current();
        return transfers.get(user.tenantId(), user.userId(), storeId, transferId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/transfers/{transferId}/dispatch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransferViews.Transfer dispatch(@PathVariable String storeId, @PathVariable String transferId,
                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.PostWithSigner.class);
        PharmacyInputs.SecondSignerInput signer = dispatches.precheck(user.tenantId(), user.userId(), storeId, transferId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return dispatches.dispatch(user.tenantId(), user.userId(), storeId, transferId, lazy, verified);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/transfers/{transferId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransferViews.Transfer cancel(@PathVariable String storeId, @PathVariable String transferId,
                                         @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.VersionReasonInput.class);
        return transfers.cancel(user.tenantId(), user.userId(), storeId, transferId, lazy);
    }
}
