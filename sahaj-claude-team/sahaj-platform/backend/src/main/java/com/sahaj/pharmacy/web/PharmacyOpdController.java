package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.OpdQueueService;
import com.sahaj.pharmacy.application.SaleCheckViews;
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
 * SAH-80-11 and 80-12 (docs/api/pharmacy-sales.yaml, WP-K): the OPD prescription queue behind the no-op port TP-2. The queue read
 * answers 200 with {@code connected false}; the five other operations answer 409 {@code not-connected} after the store guard and the
 * mode check. The bodies are not read: nothing is saved while the port is not connected.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacyOpdController implements PharmacyApi {

    private final OpdQueueService queue;

    public PharmacyOpdController(OpdQueueService queue) {
        this.queue = queue;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opd-queue")
    public SaleCheckViews.OpdQueuePage opdQueue(@PathVariable String storeId,
                                                @RequestParam(name = "q", required = false) String q,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "page", required = false) String page,
                                                @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return queue.queue(user.tenantId(), user.userId(), storeId, q, status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opd-queue/{rxId}")
    public void getPrescription(@PathVariable String storeId, @PathVariable String rxId) {
        ClinicUser user = ClinicUser.current();
        queue.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.SELL, true, rxId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping("/stores/{storeId}/opd-queue/{rxId}/lines/{rxLineId}")
    public void setPick(@PathVariable String storeId, @PathVariable String rxId, @PathVariable String rxLineId,
                        @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        queue.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.SELL, true, rxId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/opd-queue/{rxId}/claim")
    public void claim(@PathVariable String storeId, @PathVariable String rxId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        queue.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.SELL, true, rxId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/opd-queue/{rxId}/close-rest")
    public void closeRest(@PathVariable String storeId, @PathVariable String rxId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        queue.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.SELL_SCHEDULED, true, rxId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping("/stores/{storeId}/product-links")
    public void setProductLink(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        queue.refuse(user.tenantId(), user.userId(), storeId, StoreCapability.DISPENSE, false);
    }
}
