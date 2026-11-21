package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.pharmacy.application.StockAuditService;
import com.sahaj.pharmacy.application.StockAuditViews;
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
 * SAH-79-17 (docs/api/pharmacy.yaml 0.3.6, WP-H): the stock audit and its blind count. Every handler is {@code OPERATION}: its
 * service calls the store guard (capability count, startCount or reviewCount, or a current approver of the audit's request). Submit
 * and "Approve and post" check a Schedule X second signer's PIN here, between the service's pre-check and its transaction
 * ({@link ScheduleXSignature#verify} must run outside any transaction).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StockAuditController implements PharmacyApi {

    private final StockAuditService service;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public StockAuditController(StockAuditService service, ScheduleXSignature signature, ObjectMapper mapper) {
        this.service = service;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-audits")
    public StockAuditViews.Page list(@PathVariable String storeId, @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), storeId, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/stock-audits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockAuditViews.StockAudit> start(@PathVariable String storeId,
                                                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.StockAuditCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.StockAuditCreate.class);
        PharmacyViews.Outcome<StockAuditViews.StockAudit> outcome = service.start(user.tenantId(), user.userId(), storeId, lazy);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-audits/{auditId}")
    public StockAuditViews.StockAudit get(@PathVariable String storeId, @PathVariable String auditId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, auditId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-audits/{auditId}/count-sheet")
    public StockAuditViews.CountSheet countSheet(@PathVariable String storeId, @PathVariable String auditId,
                                                 @RequestParam(name = "page", required = false) String page,
                                                 @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.countSheet(user.tenantId(), user.userId(), storeId, auditId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/stock-audits/{auditId}/counts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockAuditViews.CountSheet saveCounts(@PathVariable String storeId, @PathVariable String auditId,
                                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.CountsInput> lazy = PharmacyBodies.of(mapper, body, PharmacyInputs.CountsInput.class);
        return service.saveCounts(user.tenantId(), user.userId(), storeId, auditId, lazy);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/stock-audits/{auditId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockAuditViews.StockAudit submit(@PathVariable String storeId, @PathVariable String auditId,
                                             @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.PostWithSigner.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckSubmit(user.tenantId(), user.userId(), storeId, auditId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.submit(user.tenantId(), user.userId(), storeId, auditId, lazy, verified);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock-audits/{auditId}/variance")
    public StockAuditViews.VarianceReview variance(@PathVariable String storeId, @PathVariable String auditId) {
        ClinicUser user = ClinicUser.current();
        return service.variance(user.tenantId(), user.userId(), storeId, auditId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/stock-audits/{auditId}/variance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockAuditViews.VarianceReview saveVarianceReasons(@PathVariable String storeId, @PathVariable String auditId,
                                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.VarianceReasonsInput> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.VarianceReasonsInput.class);
        return service.saveVarianceReasons(user.tenantId(), user.userId(), storeId, auditId, lazy);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/stock-audits/{auditId}/recount", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockAuditViews.StockAudit recount(@PathVariable String storeId, @PathVariable String auditId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.RecountInput> lazy = PharmacyBodies.of(mapper, body, PharmacyInputs.RecountInput.class);
        return service.recount(user.tenantId(), user.userId(), storeId, auditId, lazy);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/stock-audits/{auditId}/approve-and-post", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockAuditViews.StockAudit approveAndPost(@PathVariable String storeId, @PathVariable String auditId,
                                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.PostWithSigner> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.PostWithSigner.class);
        PharmacyInputs.SecondSignerInput signer = service.precheckPost(user.tenantId(), user.userId(), storeId, auditId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        return service.approveAndPost(user.tenantId(), user.userId(), storeId, auditId, lazy, verified);
    }
}
