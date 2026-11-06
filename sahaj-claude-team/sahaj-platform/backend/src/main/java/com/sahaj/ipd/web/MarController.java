package com.sahaj.ipd.web;

import com.sahaj.ipd.application.DoseService;
import com.sahaj.ipd.application.DoseService.DoseResult;
import com.sahaj.ipd.application.MarService;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MarViews.Mar;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-32 the Medicines (MAR) tab and giving a dose (contract {@code docs/api/ipd.yaml}, ADR-011). The clinic, the
 * person and the role always come from the signed-in user. Every endpoint is open to any signed-in clinic role here on
 * purpose: the contract gives each refused role its own 403 detail, so the role checks, module-off, read-only and every
 * business answer come from the services, which re-check the token role and the role of the active membership. The
 * answers carry names, the allergy text and the medicines; the request carries the wristband text and the second
 * nurse's PIN. None of them is ever logged.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class MarController {

    private final MarService mar;
    private final DoseService doses;

    public MarController(MarService mar, DoseService doses) {
        this.mar = mar;
        this.doses = doses;
    }

    @GetMapping("/admissions/{id}/mar")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public Mar mar(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return mar.mar(user.tenantId(), user.userId(), user.role(), id);
    }

    /** 201 for a new record; 200 for a replay of the same client key. */
    @PostMapping("/medicine-orders/{id}/doses")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public ResponseEntity<DoseRecorded> give(@PathVariable UUID id, @RequestBody DoseInput request) {
        ClinicUser user = ClinicUser.current();
        DoseResult result = doses.record(user.tenantId(), user.userId(), user.role(), id, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.answer());
    }
}
