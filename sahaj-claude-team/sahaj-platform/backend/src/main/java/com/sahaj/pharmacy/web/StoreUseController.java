package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.DamageReportService;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyViews;
import com.sahaj.pharmacy.application.ScheduleXSignature;
import com.sahaj.pharmacy.application.StoreReturnService;
import com.sahaj.pharmacy.application.StoreUseViews;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-79-14 (docs/api/pharmacy.yaml 0.3.5): returns to the Central Store and damage reports. Every handler is
 * {@code OPERATION}: its service calls the store guard. A create reads the second signer's PIN <b>before</b> the posting
 * transaction (ADR-029 P7): a short pre-check of the gate, the body and the store, then the colleague's PIN, then the
 * transaction with the verified signer.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class StoreUseController implements PharmacyApi {

    private final StoreReturnService returns;
    private final DamageReportService damage;
    private final ScheduleXSignature signature;
    private final ObjectMapper mapper;

    public StoreUseController(StoreReturnService returns, DamageReportService damage, ScheduleXSignature signature,
                              ObjectMapper mapper) {
        this.returns = returns;
        this.damage = damage;
        this.signature = signature;
        this.mapper = mapper;
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/store-returns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoreUseViews.StoreReturn> createStoreReturn(@PathVariable String storeId,
                                                                       @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.StoreReturnCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.StoreReturnCreate.class);
        PharmacyInputs.SecondSignerInput signer = returns.precheck(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<StoreUseViews.StoreReturn> outcome = returns.create(user.tenantId(), user.userId(), storeId, lazy,
                verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/store-returns/{storeReturnId}")
    public StoreUseViews.StoreReturn getStoreReturn(@PathVariable String storeId, @PathVariable String storeReturnId) {
        ClinicUser user = ClinicUser.current();
        return returns.get(user.tenantId(), user.userId(), storeId, storeReturnId);
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @PostMapping(value = "/stores/{storeId}/damage-reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoreUseViews.DamageReport> createDamageReport(@PathVariable String storeId,
                                                                         @RequestBody(required = false) String body) {
        ClinicUser user = ClinicUser.current();
        PharmacyInputs.Body<PharmacyInputs.DamageReportCreate> lazy = PharmacyBodies.of(mapper, body,
                PharmacyInputs.DamageReportCreate.class);
        PharmacyInputs.SecondSignerInput signer = damage.precheck(user.tenantId(), user.userId(), storeId, lazy);
        UUID verified = signer == null ? null
                : signature.verify(user.tenantId(), user.userId(), signer.membershipId(), signer.pin());
        PharmacyViews.Outcome<StoreUseViews.DamageReport> outcome = damage.create(user.tenantId(), user.userId(), storeId, lazy,
                verified);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.view());
    }

    @StoreAccess(StoreAccess.Kind.OPERATION)
    @GetMapping("/stores/{storeId}/damage-reports/{damageReportId}")
    public StoreUseViews.DamageReport getDamageReport(@PathVariable String storeId, @PathVariable String damageReportId) {
        ClinicUser user = ClinicUser.current();
        return damage.get(user.tenantId(), user.userId(), storeId, damageReportId);
    }
}
