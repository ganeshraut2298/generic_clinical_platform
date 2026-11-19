package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.DispatchViews;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.ReturnShortageService;
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
 * SAH-79-13 (docs/api/pharmacy.yaml 0.3.5, WP-F): "Waiting for action", returned and rejected items (read, accept, dispute,
 * reply) and shortages (list, settle). Every handler is {@code OPERATION}: its service calls the store guard. The body is read
 * lazily, right after the gate. The second signer's PIN of a Schedule X acceptance or settlement is checked here, between the
 * service's pre-check and its transaction.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ReturnShortageController implements PharmacyApi {

    private final ReturnShortageService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public ReturnShortageController(ReturnShortageService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/waiting-for-action")
    public DispatchViews.ReturnItemPage waiting(@PathVariable String storeId,
                                                @RequestParam(name = "side", required = false) String side,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "page", required = false) String page,
                                                @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.waiting(user.tenantId(), user.userId(), storeId, side, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/return-items/{itemId}")
    public DispatchViews.ReturnItem get(@PathVariable String storeId, @PathVariable String itemId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, itemId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/return-items/{itemId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DispatchViews.ReturnItem accept(@PathVariable String storeId, @PathVariable String itemId,
                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.AcceptReturn> lazy = PharmacyBodies.of(mapper, body, PharmacyInputs.AcceptReturn.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckAccept(user.tenantId(), user.userId(), storeId, itemId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.accept(user.tenantId(), user.userId(), storeId, itemId, lazy, verified);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/return-items/{itemId}/dispute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DispatchViews.ReturnItem dispute(@PathVariable String storeId, @PathVariable String itemId,
                                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.dispute(user.tenantId(), user.userId(), storeId, itemId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionReasonInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/return-items/{itemId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DispatchViews.ReturnItem> reply(@PathVariable String storeId, @PathVariable String itemId,
                                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reply(user.tenantId(), user.userId(), storeId, itemId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.NoteInput.class)));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/shortages")
    public DispatchViews.ShortagePage shortages(@PathVariable String storeId,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "page", required = false) String page,
                                                @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.listShortages(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/shortages/{shortageId}/settle", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DispatchViews.Shortage settle(@PathVariable String storeId, @PathVariable String shortageId,
                                         @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.SettleShortage> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.SettleShortage.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckSettle(user.tenantId(), user.userId(), storeId, shortageId,
                lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.settle(user.tenantId(), user.userId(), storeId, shortageId, lazy, verified);
    }
}
