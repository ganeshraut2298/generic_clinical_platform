package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.ActiveReasonService;
import com.sahaj.pharmacy.application.OpeningStockPostService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.StockReadService;
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
 * SAH-79-1, 79-3, 79-4 and the common reasons read (docs/api/pharmacy.yaml 0.3.0). Every handler under a store is
 * {@code OPERATION}: its service calls the store guard (404 for a store the caller is not mapped to, 403
 * {@code store-permission-denied} without the capability). Path ids, query values and bodies are taken as text and checked by
 * the service after the module and access-level checks.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StockController implements PharmacyApi {

    private final OpeningStockPostService posting;
    private final StockReadService reads;
    private final ActiveReasonService reasons;
    private final ObjectMapper mapper;

    public StockController(OpeningStockPostService posting, StockReadService reads, ActiveReasonService reasons,
                           ObjectMapper mapper) {
        this.posting = posting;
        this.reads = reads;
        this.reasons = reasons;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opening-stock-imports/{importId}/posting-preview")
    public StockViews.PostingPreview preview(@PathVariable String storeId, @PathVariable String importId) {
        ClinicUser user = ClinicUser.current();
        return posting.preview(user.tenantId(), user.userId(), storeId, importId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/opening-stock-imports/{importId}/post",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.OpeningStockImport post(@PathVariable String storeId, @PathVariable String importId,
                                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return posting.post(user.tenantId(), user.userId(), storeId, importId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.ClientKeyInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/overview")
    public StockViews.Overview overview(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return reads.overview(user.tenantId(), user.userId(), storeId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/batches")
    public StockViews.BatchPage batches(@PathVariable String storeId,
                                        @RequestParam(name = "view", required = false) String view,
                                        @RequestParam(name = "withinDays", required = false) String withinDays,
                                        @RequestParam(name = "q", required = false) String q,
                                        @RequestParam(name = "page", required = false) String page,
                                        @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return reads.batches(user.tenantId(), user.userId(), storeId, view, withinDays, q, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/products/{productId}")
    public StockViews.ProductStock product(@PathVariable String storeId, @PathVariable String productId) {
        ClinicUser user = ClinicUser.current();
        return reads.product(user.tenantId(), user.userId(), storeId, productId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/low-stock")
    public StockViews.LowStockPage lowStock(@PathVariable String storeId,
                                            @RequestParam(name = "page", required = false) String page,
                                            @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return reads.lowStock(user.tenantId(), user.userId(), storeId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/fefo-pick")
    public StockViews.FefoPick fefoPick(@PathVariable String storeId,
                                        @RequestParam(name = "productId", required = false) String productId,
                                        @RequestParam(name = "quantity", required = false) String quantity) {
        ClinicUser user = ClinicUser.current();
        return reads.fefoPick(user.tenantId(), user.userId(), storeId, productId, quantity);
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/stock/products/{productId}/stores")
    public StockViews.ProductStockByStore productAllStores(@PathVariable String productId) {
        ClinicUser user = ClinicUser.current();
        return reads.productAllStores(user.tenantId(), user.userId(), productId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/stock/products/{productId}/states")
    public StockViews.ProductStockByStore productMyStore(@PathVariable String storeId, @PathVariable String productId) {
        ClinicUser user = ClinicUser.current();
        return reads.productMyStore(user.tenantId(), user.userId(), storeId, productId);
    }

    @GetMapping("/reasons")
    public StockViews.ActiveReasons activeReasons(@RequestParam(name = "lists", required = false) String lists) {
        ClinicUser user = ClinicUser.current();
        return reasons.list(user.tenantId(), user.userId(), lists);
    }
}
