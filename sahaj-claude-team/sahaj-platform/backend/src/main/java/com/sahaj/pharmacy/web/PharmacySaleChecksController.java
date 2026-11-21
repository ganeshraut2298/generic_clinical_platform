package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.DiscountRequestService;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.RegisterService;
import com.sahaj.pharmacy.application.ReportViews;
import com.sahaj.pharmacy.application.SaleCheckViews;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SubstituteService;
import com.sahaj.platform.api.ClinicUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
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
 * SAH-80-6, 80-7, 80-9 and 80-10 (docs/api/pharmacy-sales.yaml, WP-J): discount requests, the substitute card and the H1 and
 * Schedule X registers with their downloads. Every handler is {@code OPERATION}; the services call the store guard. A download answers
 * text/csv only on success; every error is application/problem+json through the problem handler.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacySaleChecksController implements PharmacyApi {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final DiscountRequestService discounts;
    private final SubstituteService substitutes;
    private final RegisterService registers;
    private final ObjectMapper mapper;

    public PharmacySaleChecksController(DiscountRequestService discounts, SubstituteService substitutes,
                                        RegisterService registers, ObjectMapper mapper) {
        this.discounts = discounts;
        this.substitutes = substitutes;
        this.registers = registers;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/sale/discount-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleCheckViews.DiscountRequest> requestDiscount(@PathVariable String storeId,
                                                                          @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SaleCheckViews.DiscountRequest> outcome = discounts.request(user.tenantId(), user.userId(),
                storeId, PharmacyBodies.of(mapper, body, SaleInputs.DiscountRequestInput.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sale/discount-requests/{requestId}")
    public SaleCheckViews.DiscountRequest getDiscount(@PathVariable String storeId, @PathVariable String requestId) {
        ClinicUser user = ClinicUser.current();
        return discounts.get(user.tenantId(), user.userId(), storeId, requestId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/sale/discount-requests/{requestId}/withdraw")
    public SaleCheckViews.DiscountRequest withdrawDiscount(@PathVariable String storeId, @PathVariable String requestId) {
        ClinicUser user = ClinicUser.current();
        return discounts.withdraw(user.tenantId(), user.userId(), storeId, requestId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sale/products/{productId}/substitutes")
    public SaleCheckViews.SubstituteOptions substituteOptions(@PathVariable String storeId, @PathVariable String productId) {
        ClinicUser user = ClinicUser.current();
        return substitutes.options(user.tenantId(), user.userId(), storeId, productId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/registers/h1")
    public SaleCheckViews.H1RegisterPage h1(@PathVariable String storeId,
                                            @RequestParam(name = "from", required = false) String from,
                                            @RequestParam(name = "to", required = false) String to,
                                            @RequestParam(name = "productId", required = false) String productId,
                                            @RequestParam(name = "prescriber", required = false) String prescriber,
                                            @RequestParam(name = "page", required = false) String page,
                                            @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return registers.h1(user.tenantId(), user.userId(), storeId, from, to, productId, prescriber, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/registers/h1/export")
    public ResponseEntity<byte[]> exportH1(@PathVariable String storeId,
                                           @RequestParam(name = "from", required = false) String from,
                                           @RequestParam(name = "to", required = false) String to,
                                           @RequestParam(name = "productId", required = false) String productId,
                                           @RequestParam(name = "prescriber", required = false) String prescriber) {
        ClinicUser user = ClinicUser.current();
        return csv(registers.exportH1(user.tenantId(), user.userId(), storeId, from, to, productId, prescriber));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/registers/schedule-x")
    public SaleCheckViews.ScheduleXRegister scheduleX(@PathVariable String storeId,
                                                      @RequestParam(name = "from", required = false) String from,
                                                      @RequestParam(name = "to", required = false) String to,
                                                      @RequestParam(name = "productId", required = false) String productId) {
        ClinicUser user = ClinicUser.current();
        return registers.scheduleX(user.tenantId(), user.userId(), storeId, from, to, productId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/registers/schedule-x/sales")
    public SaleCheckViews.ScheduleXEntryPage scheduleXSales(@PathVariable String storeId,
                                                            @RequestParam(name = "from", required = false) String from,
                                                            @RequestParam(name = "to", required = false) String to,
                                                            @RequestParam(name = "productId", required = false) String productId,
                                                            @RequestParam(name = "page", required = false) String page,
                                                            @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return registers.scheduleXSales(user.tenantId(), user.userId(), storeId, from, to, productId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/registers/schedule-x/export")
    public ResponseEntity<byte[]> exportScheduleX(@PathVariable String storeId,
                                                  @RequestParam(name = "from", required = false) String from,
                                                  @RequestParam(name = "to", required = false) String to,
                                                  @RequestParam(name = "productId", required = false) String productId) {
        ClinicUser user = ClinicUser.current();
        return csv(registers.exportScheduleX(user.tenantId(), user.userId(), storeId, from, to, productId));
    }

    private static ResponseEntity<byte[]> csv(ReportViews.CsvFile file) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
