package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.HeldBillService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SalePatientService;
import com.sahaj.pharmacy.application.SaleQuoteService;
import com.sahaj.pharmacy.application.SaleViews;
import com.sahaj.platform.api.ClinicUser;
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
 * SAH-80-2, 80-3 and 80-5 (docs/api/pharmacy-sales.yaml): the patient of a bill, the counter's product search, the quote and the
 * held bills. Every handler is {@code OPERATION}: the service calls the store guard (sale-allowed store, capability sell).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacySaleController implements PharmacyApi {

    private final SalePatientService patients;
    private final SaleQuoteService quotes;
    private final HeldBillService heldBills;
    private final ObjectMapper mapper;

    public PharmacySaleController(SalePatientService patients, SaleQuoteService quotes, HeldBillService heldBills,
                                  ObjectMapper mapper) {
        this.patients = patients;
        this.quotes = quotes;
        this.heldBills = heldBills;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sale/patients")
    public SaleViews.SalePatientList searchPatients(@PathVariable String storeId,
                                                    @RequestParam(name = "q", required = false) String q) {
        ClinicUser user = ClinicUser.current();
        return patients.search(user.tenantId(), user.userId(), storeId, q);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sale/patients/{patientId}")
    public SaleViews.SalePatient getPatient(@PathVariable String storeId, @PathVariable String patientId) {
        ClinicUser user = ClinicUser.current();
        return patients.get(user.tenantId(), user.userId(), storeId, patientId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/sale/products")
    public SaleViews.SaleProductList searchProducts(@PathVariable String storeId,
                                                    @RequestParam(name = "q", required = false) String q,
                                                    @RequestParam(name = "barcode", required = false) String barcode) {
        ClinicUser user = ClinicUser.current();
        return quotes.search(user.tenantId(), user.userId(), storeId, q, barcode);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/sale/quote", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaleViews.SaleQuote quote(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return quotes.quote(user.tenantId(), user.userId(), storeId, PharmacyBodies.of(mapper, body, SaleInputs.CartInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/held-bills")
    public SaleViews.HeldBillList listHeldBills(@PathVariable String storeId) {
        ClinicUser user = ClinicUser.current();
        return heldBills.list(user.tenantId(), user.userId(), storeId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/held-bills", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.HeldBill> holdBill(@PathVariable String storeId,
                                                       @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SaleViews.HeldBill> outcome = heldBills.hold(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, SaleInputs.HoldInput.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/held-bills/{heldBillId}")
    public SaleViews.HeldBill getHeldBill(@PathVariable String storeId, @PathVariable String heldBillId) {
        ClinicUser user = ClinicUser.current();
        return heldBills.get(user.tenantId(), user.userId(), storeId, heldBillId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/held-bills/{heldBillId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaleViews.HeldBill discardHeldBill(@PathVariable String storeId, @PathVariable String heldBillId,
                                              @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.VersionInput> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.VersionInput.class);
        return heldBills.discard(user.tenantId(), user.userId(), storeId, heldBillId, lazy);
    }
}
