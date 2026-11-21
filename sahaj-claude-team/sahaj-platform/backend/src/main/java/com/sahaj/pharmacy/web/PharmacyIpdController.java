package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.IpdDispensingService;
import com.sahaj.pharmacy.application.IpdDispensingService.Refusal;
import com.sahaj.pharmacy.application.IpdViews;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-80-13 to 80-17 (docs/api/pharmacy-sales.yaml, WP-M): the IPD queue, issues, ward returns, waiting charges and discharges
 * behind the no-op ports TP-4 and TP-5. The lists answer 200 and empty; the operations that need an IPD order, issue or ward return
 * answer what the contract lists (409 not-connected, 409 not-issued, 404). Bodies are not read: nothing is saved in phase 1.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacyIpdController implements PharmacyApi {

    private final IpdDispensingService service;

    public PharmacyIpdController(IpdDispensingService service) {
        this.service = service;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/ipd-queue")
    public IpdViews.IpdQueuePage queue(@PathVariable String storeId, @RequestParam(name = "lane", required = false) String lane,
                                       @RequestParam(name = "page", required = false) String page,
                                       @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.queue(user.tenantId(), user.userId(), storeId, lane, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/ipd-queue/admissions/{admissionId}")
    public void orderSet(@PathVariable String storeId, @PathVariable String admissionId) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, true, true, Refusal.NOT_CONNECTED,
                admissionId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping("/stores/{storeId}/ipd-queue/admissions/{admissionId}/orders/{orderId}")
    public void setLinePick(@PathVariable String storeId, @PathVariable String admissionId, @PathVariable String orderId,
                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, false, true, Refusal.NOT_CONNECTED,
                admissionId, orderId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/ipd-issues")
    public IpdViews.IpdIssuePage issues(@PathVariable String storeId,
                                        @RequestParam(name = "admissionId", required = false) String admissionId,
                                        @RequestParam(name = "chargeStatus", required = false) String chargeStatus,
                                        @RequestParam(name = "date", required = false) String date,
                                        @RequestParam(name = "page", required = false) String page,
                                        @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.issues(user.tenantId(), user.userId(), storeId, admissionId, chargeStatus, date, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/ipd-issues")
    public void createIssue(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, false, true, Refusal.NOT_CONNECTED);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/ipd-issues/{issueId}")
    public void getIssue(@PathVariable String storeId, @PathVariable String issueId) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, true, false, Refusal.NOT_FOUND, issueId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/ward-returns/returnable")
    public IpdViews.WardReturnable returnable(@PathVariable String storeId, @RequestParam(name = "q", required = false) String q) {
        ClinicUser user = ClinicUser.current();
        return service.returnable(user.tenantId(), user.userId(), storeId, q);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/ward-returns")
    public void recordWardReturn(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.RECORD_WARD_RETURN, false, true,
                Refusal.NOT_ISSUED);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/returns-waiting")
    public IpdViews.ReturnsWaitingPage returnsWaiting(@PathVariable String storeId,
                                                      @RequestParam(name = "page", required = false) String page,
                                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.returnsWaiting(user.tenantId(), user.userId(), storeId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/ward-returns/{wardReturnId}/accept")
    public void acceptWardReturn(@PathVariable String storeId, @PathVariable String wardReturnId,
                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.ACCEPT_WARD_RETURN, false, true, Refusal.NOT_FOUND,
                wardReturnId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/ward-returns/{wardReturnId}/not-accepted")
    public void refuseWardReturn(@PathVariable String storeId, @PathVariable String wardReturnId,
                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.ACCEPT_WARD_RETURN, false, true, Refusal.NOT_FOUND,
                wardReturnId);
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/ipd-charges/waiting")
    public IpdViews.ChargesWaitingPage chargesWaiting(@RequestParam(name = "storeId", required = false) String storeId,
                                                      @RequestParam(name = "page", required = false) String page,
                                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.chargesWaiting(user.tenantId(), user.userId(), storeId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @PostMapping("/ipd-charges/post")
    public void postWaitingCharges(@RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.postWaiting(user.tenantId(), user.userId(), null);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/discharges")
    public IpdViews.DischargeList discharges(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return service.discharges(user.tenantId(), user.userId(), storeId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/discharges/{admissionId}/clear")
    public void markCleared(@PathVariable String storeId, @PathVariable String admissionId,
                            @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        service.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, false, true, Refusal.NOT_CONNECTED,
                admissionId);
    }
}
