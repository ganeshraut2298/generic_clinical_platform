package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.IndentService;
import com.sahaj.pharmacy.application.IndentViews;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
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
 * SAH-79-9 and 79-10 (docs/api/pharmacy.yaml 0.3.4, WP-F): indents. Every handler is {@code OPERATION}: its service calls the store
 * guard (capability raiseIndent at the requesting store, purchase at the issuing store). The body is read lazily, right after
 * the gate. The approval of an indent is the decision endpoint of the approval engine (79-2).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class IndentController implements PharmacyApi {

    private final IndentService service;
    private final ObjectMapper mapper;

    public IndentController(IndentService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/indents")
    public IndentViews.Page list(@PathVariable String storeId,
                                 @RequestParam(name = "view", required = false) String view,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "page", required = false) String page,
                                 @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, view, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/indents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IndentViews.Indent> create(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<IndentViews.Indent> outcome = service.create(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.IndentCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/indents/{indentId}")
    public IndentViews.Indent get(@PathVariable String storeId, @PathVariable String indentId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, indentId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/indents/{indentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IndentViews.Indent update(@PathVariable String storeId, @PathVariable String indentId,
                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.update(user.tenantId(), user.userId(), storeId, indentId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.IndentUpdate.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/indents/{indentId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IndentViews.Indent discard(@PathVariable String storeId, @PathVariable String indentId,
                                      @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.discard(user.tenantId(), user.userId(), storeId, indentId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/indents/{indentId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IndentViews.Indent submit(@PathVariable String storeId, @PathVariable String indentId,
                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.submit(user.tenantId(), user.userId(), storeId, indentId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionClientKeyInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/indents/{indentId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IndentViews.Indent cancel(@PathVariable String storeId, @PathVariable String indentId,
                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.cancel(user.tenantId(), user.userId(), storeId, indentId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.VersionReasonInput.class));
    }
}
