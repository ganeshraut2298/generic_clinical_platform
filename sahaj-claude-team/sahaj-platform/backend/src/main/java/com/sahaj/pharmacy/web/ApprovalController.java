package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ApprovalService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.StockViews;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-79-2 (docs/api/pharmacy.yaml 0.3.0): the approvals inbox, one approval request and the one decision endpoint. None of
 * these is under a store path (the approver may not be mapped to the document's store), so none declares a store kind; the
 * service decides who may see or decide a request. Path ids, query values and the body are taken as text and read by the service
 * after the module and access-level checks.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ApprovalController implements PharmacyApi {

    private final ApprovalService service;
    private final ObjectMapper mapper;

    public ApprovalController(ApprovalService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/approvals/inbox")
    public StockViews.InboxPage inbox(@RequestParam(name = "type", required = false) String type,
                                      @RequestParam(name = "page", required = false) String page,
                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.inbox(user.tenantId(), user.userId(), type, page, size);
    }

    @GetMapping("/approvals/{requestId}")
    public StockViews.ApprovalRequest get(@PathVariable String requestId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), requestId);
    }

    @PostMapping(value = "/approvals/{requestId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StockViews.ApprovalRequest decide(@PathVariable String requestId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.decide(user.tenantId(), user.userId(), requestId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.ApprovalDecisionInput.class));
    }
}
