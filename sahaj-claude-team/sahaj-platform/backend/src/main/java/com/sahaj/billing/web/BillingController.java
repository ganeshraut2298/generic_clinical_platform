package com.sahaj.billing.web;

import com.sahaj.billing.application.BillingService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-10 Billing counter. Any clinic role reads; OWNER and FRONT_DESK add/remove items, set the
 * discount and collect payment; a refund is OWNER only (checked again inside
 * {@link BillingService}). The tenant, actor and role always come from the signed-in user, never
 * from the request.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/counter")
    @PreAuthorize(BillingRoles.ANY_CLINIC_ROLE)
    public CounterResponse counter() {
        ClinicUser user = ClinicUser.current();
        return CounterResponse.from(billingService.counter(user.tenantId()));
    }

    @GetMapping("/bills/{visitId}")
    @PreAuthorize(BillingRoles.ANY_CLINIC_ROLE)
    public BillResponse get(@PathVariable UUID visitId) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(billingService.get(user.tenantId(), user.role(), visitId));
    }

    @PostMapping("/bills/{visitId}/items")
    @PreAuthorize(BillingRoles.COLLECT)
    public BillResponse addItem(@PathVariable UUID visitId, @RequestBody ItemAddRequest request) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(
                billingService.addItem(user.tenantId(), user.userId(), user.role(), visitId, request.toInput()));
    }

    @DeleteMapping("/bills/{visitId}/items/{itemId}")
    @PreAuthorize(BillingRoles.COLLECT)
    public BillResponse removeItem(@PathVariable UUID visitId, @PathVariable UUID itemId) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(
                billingService.removeItem(user.tenantId(), user.userId(), user.role(), visitId, itemId));
    }

    @PutMapping("/bills/{visitId}/discount")
    @PreAuthorize(BillingRoles.COLLECT)
    public BillResponse setDiscount(@PathVariable UUID visitId, @RequestBody DiscountRequest request) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(
                billingService.setDiscount(user.tenantId(), user.userId(), user.role(), visitId, request.toInput()));
    }

    @PostMapping("/bills/{visitId}/payments")
    @PreAuthorize(BillingRoles.COLLECT)
    public BillResponse recordPayment(@PathVariable UUID visitId, @RequestBody PaymentRequest request) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(billingService.recordPayment(user.tenantId(), user.userId(), user.role(), visitId,
                request.toInput()));
    }

    @PostMapping("/bills/{visitId}/refund")
    @PreAuthorize(BillingRoles.OWNER)
    public BillResponse refund(@PathVariable UUID visitId, @RequestBody RefundRequest request) {
        ClinicUser user = ClinicUser.current();
        return BillResponse.from(
                billingService.refund(user.tenantId(), user.userId(), user.role(), visitId, request.toInput()));
    }

    @PostMapping("/day-closing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BillingRoles.COLLECT)
    public DayClosingResponse closeDay(@RequestBody DayClosingRequest request) {
        ClinicUser user = ClinicUser.current();
        return DayClosingResponse.from(
                billingService.closeDay(user.tenantId(), user.userId(), user.role(), request.toInput()));
    }
}
