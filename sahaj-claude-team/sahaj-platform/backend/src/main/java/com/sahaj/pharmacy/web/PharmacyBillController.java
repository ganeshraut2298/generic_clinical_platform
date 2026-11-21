package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.BillCancelService;
import com.sahaj.pharmacy.application.BillLinkService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.SaleBillService;
import com.sahaj.pharmacy.application.SaleInputs;
import com.sahaj.pharmacy.application.SaleViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
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
 * SAH-80-3 and 80-5 (docs/api/pharmacy-sales.yaml): the counter bill, its list, cancel and refund. Every handler is
 * {@code OPERATION}. A save or a cancel of a Schedule X bill reads the second signer's PIN <b>before</b> the transaction
 * (ADR-029 P7): a short pre-check of the gate, the body and the store, then the colleague's PIN, then the transaction with the
 * verified signer.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacyBillController implements PharmacyApi {

    private final SaleBillService bills;
    private final BillCancelService cancels;
    private final BillLinkService links;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public PharmacyBillController(SaleBillService bills, BillCancelService cancels, BillLinkService links,
                                  ScheduleXSignature signature, ObjectMapper mapper) {
        this.links = links;
        this.bills = bills;
        this.cancels = cancels;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/bills")
    public SaleViews.BillPage list(@PathVariable String storeId, @RequestParam(name = "number", required = false) String number,
                                   @RequestParam(name = "date", required = false) String date,
                                   @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "mark", required = false) String mark,
                                   @RequestParam(name = "page", required = false) String page,
                                   @RequestParam(name = "size", required = false) String size) {
        ClinicUser user = ClinicUser.current();
        return bills.list(user.tenantId(), user.userId(), storeId, number, date, status, mark, page, size);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/bills", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.Bill> create(@PathVariable String storeId, @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<SaleInputs.BillCreate> lazy = PharmacyBodies.of(mapper, body, SaleInputs.BillCreate.class);
        PharmacyInputs.SecondSignerInput signer = bills.precheck(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<SaleViews.Bill> outcome = bills.create(user.tenantId(), user.userId(), storeId, lazy, verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/bills/{billId}")
    public SaleViews.Bill get(@PathVariable String storeId, @PathVariable String billId) {
        ClinicUser user = ClinicUser.current();
        return bills.get(user.tenantId(), user.userId(), storeId, billId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/bills/{billId}/link-patient", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaleViews.Bill linkPatient(@PathVariable String storeId, @PathVariable String billId,
                                      @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        return links.link(user.tenantId(), user.userId(), storeId, billId,
                PharmacyBodies.of(mapper, body, SaleInputs.LinkPatientInput.class));
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/bills/{billId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.Bill> cancel(@PathVariable String storeId, @PathVariable String billId,
                                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<SaleInputs.CancelBillInput> lazy = PharmacyBodies.of(mapper, body, SaleInputs.CancelBillInput.class);
        PharmacyInputs.SecondSignerInput signer = cancels.precheck(user.tenantId(), user.userId(), storeId, billId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<SaleViews.Bill> outcome = cancels.cancel(user.tenantId(), user.userId(), storeId, billId, lazy,
                verified);
        return ResponseEntity.status(HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/bills/{billId}/refund", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaleViews.Bill> refund(@PathVariable String storeId, @PathVariable String billId,
                                                 @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyViews.Outcome<SaleViews.Bill> outcome = cancels.refund(user.tenantId(), user.userId(), storeId, billId,
                PharmacyBodies.of(mapper, body, SaleInputs.RefundInput.class));
        return ResponseEntity.status(HttpStatus.OK).body(outcome.view());
    }
}
