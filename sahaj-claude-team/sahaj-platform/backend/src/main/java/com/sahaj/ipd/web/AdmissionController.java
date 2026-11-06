package com.sahaj.ipd.web;

import com.sahaj.ipd.application.AdmissionInput;
import com.sahaj.ipd.application.AdmissionService;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.ChargeInput;
import com.sahaj.ipd.application.AdmissionViews.ChargeService;
import com.sahaj.ipd.application.AdmissionViews.DepositInput;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.AdmissionViews.Options;
import com.sahaj.ipd.application.AdmissionViews.Rules;
import com.sahaj.ipd.application.AdmissionViews.RulesInput;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-23 admissions and deposits (contract {@code docs/api/ipd.yaml}). The clinic, the person and the role always
 * come from the signed-in user. Module-off and read-only answers come from the service.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class AdmissionController {

    static final String READERS = "hasAnyRole('OWNER','FRONT_DESK','NURSE','DOCTOR')";
    static final String ADMITTERS = "hasAnyRole('OWNER','FRONT_DESK','NURSE')";
    static final String DEPOSIT_TAKERS = "hasAnyRole('OWNER','FRONT_DESK')";
    static final String OWNER = "hasRole('OWNER')";

    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    @GetMapping("/admission-rules")
    @PreAuthorize(READERS)
    public Rules rules() {
        return service.rules(ClinicUser.current().tenantId());
    }

    @PutMapping("/admission-rules")
    @PreAuthorize(OWNER)
    public Rules updateRules(@RequestBody RulesInput request) {
        ClinicUser user = ClinicUser.current();
        return service.updateRules(user.tenantId(), user.userId(), request);
    }

    @PostMapping("/admission-rules/confirm")
    @PreAuthorize(OWNER)
    public Rules confirmRules() {
        ClinicUser user = ClinicUser.current();
        return service.confirmRules(user.tenantId(), user.userId());
    }

    @GetMapping("/admission-options")
    @PreAuthorize(ADMITTERS)
    public Options options(@RequestParam(name = "emergencyCaseId", required = false) UUID emergencyCaseId) {
        ClinicUser user = ClinicUser.current();
        return service.options(user.tenantId(), user.role(), emergencyCaseId);
    }

    @GetMapping("/admissions")
    @PreAuthorize(READERS)
    public AdmissionList list(@RequestParam(name = "status", required = false) String status) {
        ClinicUser user = ClinicUser.current();
        return service.list(user.tenantId(), user.userId(), user.role(), status);
    }

    @PostMapping("/admissions")
    @PreAuthorize(ADMITTERS)
    @ResponseStatus(HttpStatus.CREATED)
    public Detail admit(@RequestBody AdmissionInput request) {
        ClinicUser user = ClinicUser.current();
        return service.admit(user.tenantId(), user.userId(), user.role(), request);
    }

    @GetMapping("/admissions/{id}")
    @PreAuthorize(READERS)
    public Detail detail(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.detail(user.tenantId(), user.userId(), user.role(), id);
    }

    @PostMapping("/admissions/{id}/deposits")
    @PreAuthorize(DEPOSIT_TAKERS)
    public Detail addDeposit(@PathVariable UUID id, @RequestBody DepositInput request) {
        ClinicUser user = ClinicUser.current();
        return service.addDeposit(user.tenantId(), user.userId(), user.role(), id, request.amountPaise(),
                request.mode());
    }

    /** SAH-25 the fixed list of extra-charge services. */
    @GetMapping("/charge-services")
    @PreAuthorize(READERS)
    public List<ChargeService> chargeServices() {
        return service.chargeServices(ClinicUser.current().tenantId());
    }

    /** SAH-25 adds one charge to an admitted patient's running bill. */
    @PostMapping("/admissions/{id}/charges")
    @PreAuthorize(ADMITTERS)
    public Detail addCharge(@PathVariable UUID id, @RequestBody ChargeInput request) {
        ClinicUser user = ClinicUser.current();
        return service.addCharge(user.tenantId(), user.userId(), user.role(), id, request.serviceKey());
    }
}
