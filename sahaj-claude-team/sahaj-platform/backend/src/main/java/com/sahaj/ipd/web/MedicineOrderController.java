package com.sahaj.ipd.web;

import com.sahaj.ipd.application.MedicineOrderService;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecksSummary;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.application.PharmacistCheckService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-31 the EMR sheet, medicine orders and the pharmacist check (contract {@code docs/api/ipd.yaml}, ADR-009). The
 * clinic, the person and the role always come from the signed-in user. Every endpoint is open to any signed-in clinic
 * role here on purpose: the contract gives each refused role its own 403 detail ("Only a doctor can do this.",
 * "Clinical details are hidden for your role.", ...), so the role check, the module-off, read-only and every business
 * answer come from the services, which re-check the token role and the role of the active membership. The answers carry
 * names, the allergy text and findings; they are never logged.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class MedicineOrderController {

    /** Any signed-in clinic role; the services answer each role as the contract says. */
    static final String CLINIC_ROLES =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";

    private final MedicineOrderService orders;
    private final PharmacistCheckService pharmacist;

    public MedicineOrderController(MedicineOrderService orders, PharmacistCheckService pharmacist) {
        this.orders = orders;
        this.pharmacist = pharmacist;
    }

    @GetMapping("/admissions/{id}/emr")
    @PreAuthorize(CLINIC_ROLES)
    public EmrSheet emr(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return orders.emr(user.tenantId(), user.userId(), user.role(), id);
    }

    /** 201 for a placed order; 200 for BLOCKED, NEEDS_REASON and a repeat of a placed order. */
    @PostMapping("/admissions/{id}/medicine-orders")
    @PreAuthorize(CLINIC_ROLES)
    public ResponseEntity<MedicineOrderAnswer> order(@PathVariable UUID id, @RequestBody MedicineOrderInput request) {
        ClinicUser user = ClinicUser.current();
        MedicineOrderAnswer answer = orders.order(user.tenantId(), user.userId(), user.role(), id, request);
        boolean created = "PLACED".equals(answer.outcome()) && !answer.replayed();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(answer);
    }

    @PostMapping("/medicine-orders/{id}/stop")
    @PreAuthorize(CLINIC_ROLES)
    public EmrSheet stop(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return orders.stop(user.tenantId(), user.userId(), user.role(), id);
    }

    @PostMapping("/medicine-orders/{id}/verify")
    @PreAuthorize(CLINIC_ROLES)
    public PharmacistActionResult verify(@PathVariable UUID id, @RequestBody VerifyOrderInput request) {
        ClinicUser user = ClinicUser.current();
        return pharmacist.verify(user.tenantId(), user.userId(), user.role(), id, request);
    }

    @PostMapping("/medicine-orders/{id}/reject")
    @PreAuthorize(CLINIC_ROLES)
    public PharmacistActionResult reject(@PathVariable UUID id, @RequestBody RejectOrderInput request) {
        ClinicUser user = ClinicUser.current();
        return orders.reject(user.tenantId(), user.userId(), user.role(), id, request);
    }

    /** The limit and the cursor are read as text so that the service gives the contract's own 400 for a bad one. */
    @GetMapping("/pharmacist-checks")
    @PreAuthorize(CLINIC_ROLES)
    public PharmacistChecks checks(@RequestParam(name = "limit", required = false) String limit,
                                   @RequestParam(name = "cursor", required = false) String cursor) {
        ClinicUser user = ClinicUser.current();
        return pharmacist.list(user.tenantId(), user.userId(), user.role(), limit, cursor);
    }

    @GetMapping("/pharmacist-checks/summary")
    @PreAuthorize(CLINIC_ROLES)
    public PharmacistChecksSummary summary() {
        ClinicUser user = ClinicUser.current();
        return pharmacist.summary(user.tenantId(), user.userId(), user.role());
    }
}
