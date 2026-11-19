package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.OpeningStockService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.domain.OpeningStockCsv;
import com.sahaj.platform.api.ClinicUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
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
 * SAH-78-7 (docs/api/pharmacy.yaml): the opening-stock import. Every handler under a store is {@code OPERATION}: its service
 * calls the store guard (404 for a store the caller is not mapped to, 403 {@code store-permission-denied} without the
 * capability). The template and the owner's list work outside a store. Path ids, query values and bodies are taken as text and
 * checked by the service after the module and access-level checks. The CSV is served as plain text with a fixed content type,
 * so no problem answer depends on the {@code Accept} header.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class OpeningStockController implements PharmacyApi {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final OpeningStockService service;
    private final ObjectMapper mapper;

    public OpeningStockController(OpeningStockService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/opening-stock/template")
    public ResponseEntity<String> template() {
        ClinicUser user = ClinicUser.current();
        String csv = service.template(user.tenantId(), user.userId());
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + OpeningStockCsv.FILE_NAME + "\"")
                .body(csv);
    }

    @GetMapping("/opening-stock-imports")
    public PharmacyViews.ImportPage listAll(@RequestParam(name = "status", required = false) String status,
                                            @RequestParam(name = "page", required = false) String page,
                                            @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.listAll(user.tenantId(), user.userId(), status, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opening-stock-imports")
    public PharmacyViews.ImportPage list(@PathVariable String storeId,
                                         @RequestParam(name = "page", required = false) String page,
                                         @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.listForStore(user.tenantId(), user.userId(), storeId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/opening-stock-imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PharmacyViews.OpeningStockImport> start(@PathVariable String storeId,
                                                                  @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<PharmacyViews.OpeningStockImport> outcome = service.start(user.tenantId(), user.userId(),
                storeId, PharmacyBodies.of(mapper, body, PharmacyInputs.StartImport.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opening-stock-imports/{importId}")
    public PharmacyViews.OpeningStockImport get(@PathVariable String storeId, @PathVariable String importId) {
        ClinicUser user = ClinicUser.current();
        return service.get(user.tenantId(), user.userId(), storeId, importId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/opening-stock-imports/{importId}/lines")
    public PharmacyViews.OpeningStockLinePage lines(@PathVariable String storeId, @PathVariable String importId,
                                                    @RequestParam(name = "withIssues", required = false) String withIssues,
                                                    @RequestParam(name = "page", required = false) String page,
                                                    @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return service.lines(user.tenantId(), user.userId(), storeId, importId, withIssues, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/opening-stock-imports/{importId}/lines",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.LinesResult addLines(@PathVariable String storeId, @PathVariable String importId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.addLines(user.tenantId(), user.userId(), storeId, importId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.ImportLines.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/opening-stock-imports/{importId}/submit")
    public PharmacyViews.OpeningStockImport submit(@PathVariable String storeId, @PathVariable String importId) {
        ClinicUser user = ClinicUser.current();
        return service.submit(user.tenantId(), user.userId(), storeId, importId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/opening-stock-imports/{importId}/approve")
    public PharmacyViews.OpeningStockImport approve(@PathVariable String storeId, @PathVariable String importId) {
        ClinicUser user = ClinicUser.current();
        return service.approve(user.tenantId(), user.userId(), storeId, importId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/opening-stock-imports/{importId}/reject",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PharmacyViews.OpeningStockImport reject(@PathVariable String storeId, @PathVariable String importId,
                                                   @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return service.reject(user.tenantId(), user.userId(), storeId, importId,
                PharmacyBodies.of(mapper, body, PharmacyInputs.RejectImport.class));
    }
}
