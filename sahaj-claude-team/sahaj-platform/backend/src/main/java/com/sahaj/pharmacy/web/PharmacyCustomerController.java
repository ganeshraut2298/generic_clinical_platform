package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.CustomerService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SaleViews;
import com.sahaj.platform.api.ClinicUser;
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
 * SAH-80-2 and SAH-80-4 (docs/api/pharmacy-sales.yaml): the pharmacy's customers, their account and payments. Every handler is
 * {@code OPERATION}: the service calls the store guard (sale-allowed store, capability sell). Thin: the body is read by the service
 * after its gate, and the answers are the contract's records.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacyCustomerController implements PharmacyApi {

    private final CustomerService customers;
    private final ObjectMapper mapper;

    public PharmacyCustomerController(CustomerService customers, ObjectMapper mapper) {
        this.customers = customers;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/customers")
    public SaleViews.CustomerPage search(@PathVariable String storeId, @RequestParam(name = "q", required = false) String q,
                                         @RequestParam(name = "patientId", required = false) String patientId,
                                         @RequestParam(name = "includeInactive", required = false) String includeInactive,
                                         @RequestParam(name = "page", required = false) String page,
                                         @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return customers.search(user.tenantId(), user.userId(), storeId, q, patientId, includeInactive, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/customers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.Customer> create(@PathVariable String storeId,
                                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SaleViews.Customer> outcome = customers.create(user.tenantId(), user.userId(), storeId,
                PharmacyBodies.of(mapper, body, SaleInputs.CustomerCreate.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/customers/{customerId}")
    public SaleViews.Customer get(@PathVariable String storeId, @PathVariable String customerId) {
        ClinicUser user = ClinicUser.current();
        return customers.get(user.tenantId(), user.userId(), storeId, customerId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PutMapping(value = "/stores/{storeId}/customers/{customerId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaleViews.Customer update(@PathVariable String storeId, @PathVariable String customerId,
                                     @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<SaleInputs.CustomerUpdate> lazy = PharmacyBodies.of(mapper, body, SaleInputs.CustomerUpdate.class);
        return customers.update(user.tenantId(), user.userId(), storeId, customerId, lazy);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping("/stores/{storeId}/customers/{customerId}/switch-off")
    public SaleViews.Customer switchOff(@PathVariable String storeId, @PathVariable String customerId) {
        ClinicUser user = ClinicUser.current();
        return customers.switchOff(user.tenantId(), user.userId(), storeId, customerId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/customers/{customerId}/account")
    public SaleViews.CustomerAccount account(@PathVariable String storeId, @PathVariable String customerId,
                                             @RequestParam(name = "page", required = false) String page,
                                             @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return customers.account(user.tenantId(), user.userId(), storeId, customerId, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/customers/{customerId}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.CustomerPaymentReceipt> receivePayment(@PathVariable String storeId,
                                                                           @PathVariable String customerId,
                                                                           @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SaleViews.CustomerPaymentReceipt> outcome = customers.receivePayment(user.tenantId(),
                user.userId(), storeId, customerId, PharmacyBodies.of(mapper, body, SaleInputs.CustomerPaymentInput.class));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }
}
