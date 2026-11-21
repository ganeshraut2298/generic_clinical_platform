package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.ReportService;
import com.sahaj.pharmacy.application.ReportViews;
import com.sahaj.platform.api.ClinicUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-79-18 and 79-19: the reports (docs/api/pharmacy.yaml 0.3.6). The download handlers declare no {@code produces}: only a
 * finished file is {@code text/csv}; every refusal goes through the pharmacy and platform handlers and is
 * {@code application/problem+json} whatever the Accept header says, so a client that reads the body as a blob can parse it.
 * Query values are taken as text and checked by the service after the access check.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class ReportController implements PharmacyApi {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports")
    public ReportViews.Catalogue catalogue() {
        ClinicUser user = ClinicUser.current();
        return reports.catalogue(user.tenantId(), user.userId());
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/reports/stock-ledger")
    public ReportViews.StockLedgerPage ledger(@RequestParam(name = "productId", required = false) String productId,
                                              @RequestParam(name = "storeId", required = false) String storeId,
                                              @RequestParam(name = "from", required = false) String from,
                                              @RequestParam(name = "to", required = false) String to,
                                              @RequestParam(name = "page", required = false) String page,
                                              @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return reports.ledger(user.tenantId(), user.userId(),
                new ReportService.Query(storeId, from, to, productId, null, null, page, size));
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/reports/stock-ledger/export")
    public ResponseEntity<byte[]> exportLedger(@RequestParam(name = "productId", required = false) String productId,
                                               @RequestParam(name = "storeId", required = false) String storeId,
                                               @RequestParam(name = "from", required = false) String from,
                                               @RequestParam(name = "to", required = false) String to) {
        ClinicUser user = ClinicUser.current();
        return csv(reports.exportLedger(user.tenantId(), user.userId(),
                new ReportService.Query(storeId, from, to, productId, null, null, null, null)));
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/reports/{reportKey}")
    public ReportViews.ReportPage run(@PathVariable String reportKey,
                                      @RequestParam(name = "storeId", required = false) String storeId,
                                      @RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to,
                                      @RequestParam(name = "productId", required = false) String productId,
                                      @RequestParam(name = "vendorId", required = false) String vendorId,
                                      @RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "page", required = false) String page,
                                      @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return reports.run(user.tenantId(), user.userId(), reportKey,
                new ReportService.Query(storeId, from, to, productId, vendorId, status, page, size));
    }

    @StoreAccess(StoreAccess.Kind.SCOPED)
    @GetMapping("/reports/{reportKey}/export")
    public ResponseEntity<byte[]> export(@PathVariable String reportKey,
                                         @RequestParam(name = "storeId", required = false) String storeId,
                                         @RequestParam(name = "from", required = false) String from,
                                         @RequestParam(name = "to", required = false) String to,
                                         @RequestParam(name = "productId", required = false) String productId,
                                         @RequestParam(name = "vendorId", required = false) String vendorId,
                                         @RequestParam(name = "status", required = false) String status) {
        ClinicUser user = ClinicUser.current();
        return csv(reports.export(user.tenantId(), user.userId(), reportKey,
                new ReportService.Query(storeId, from, to, productId, vendorId, status, null, null)));
    }

    private static ResponseEntity<byte[]> csv(ReportViews.CsvFile file) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
