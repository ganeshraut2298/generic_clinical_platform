package com.sahaj.opd.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.specialty.ChemoCheckService;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ActionInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.CheckerInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ConfirmInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.HoldInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.OrderInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.PinInput;
import com.sahaj.opd.application.emr.specialty.ChemoOrderService;
import com.sahaj.opd.application.emr.specialty.ChemoSecondCheckService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-66, SAH-70 (ADR-023 D6, D12; docs/api/opd.yaml 0.5.0): the chemotherapy order sheet and its independent second
 * check. The sheet endpoints are for doctors, visiting doctors and owners with doctor details (the service re-reads the
 * membership and applies the own-patient rule). The checker endpoints are for NURSE, PHARMACIST, DOCTOR, VISITING_DOCTOR
 * and OWNER with doctor details, and only for orders that name the caller (the service decides, 403 otherwise). The PIN
 * endpoint is not transactional on purpose (the PIN check runs outside any transaction). The clinic is always the
 * signed-in user's; no dose, drug, lab, PIN or patient name is logged.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrChemoController implements EmrApi {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";
    private static final String CHECKER_ROLES = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','PHARMACIST')";

    private final ChemoOrderService orders;
    private final ChemoCheckService checks;
    private final ChemoSecondCheckService secondCheck;

    public EmrChemoController(ChemoOrderService orders, ChemoCheckService checks, ChemoSecondCheckService secondCheck) {
        this.orders = orders;
        this.checks = checks;
        this.secondCheck = secondCheck;
    }

    @GetMapping("/records/{recordId}/chemo-order")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode getOrder(@PathVariable UUID recordId) {
        return orders.get(ClinicUser.current(), recordId);
    }

    @PutMapping("/records/{recordId}/chemo-order")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode saveOrder(@PathVariable UUID recordId, @RequestBody OrderInput input) {
        return orders.save(ClinicUser.current(), recordId, input);
    }

    @GetMapping("/records/{recordId}/chemo-order/checker-options")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public List<Map<String, Object>> checkerOptions(@PathVariable UUID recordId) {
        return orders.checkerOptions(ClinicUser.current(), recordId);
    }

    @PutMapping("/records/{recordId}/chemo-order/checker")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode nameChecker(@PathVariable UUID recordId, @RequestBody CheckerInput input) {
        return orders.nameChecker(ClinicUser.current(), recordId, input);
    }

    @PostMapping("/records/{recordId}/chemo-order/second-check")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode confirmByPin(@PathVariable UUID recordId, @RequestBody PinInput input) {
        return secondCheck.confirmByPin(ClinicUser.current(), recordId, input);
    }

    @PostMapping("/records/{recordId}/chemo-order/send")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode send(@PathVariable UUID recordId, @RequestBody ActionInput input) {
        return orders.send(ClinicUser.current(), recordId, input);
    }

    @PostMapping("/records/{recordId}/chemo-order/hold")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode hold(@PathVariable UUID recordId, @RequestBody HoldInput input) {
        return orders.hold(ClinicUser.current(), recordId, input);
    }

    @GetMapping("/chemo-checks")
    @PreAuthorize(CHECKER_ROLES)
    public ObjectNode myChecks(@RequestParam(name = "page", required = false) Integer page) {
        return checks.list(ClinicUser.current(), page);
    }

    @GetMapping("/chemo-checks/{orderId}")
    @PreAuthorize(CHECKER_ROLES)
    public ObjectNode check(@PathVariable UUID orderId) {
        return checks.summary(ClinicUser.current(), orderId);
    }

    @PostMapping("/chemo-checks/{orderId}/confirm")
    @PreAuthorize(CHECKER_ROLES)
    public ObjectNode confirm(@PathVariable UUID orderId, @RequestBody ConfirmInput input) {
        return checks.confirm(ClinicUser.current(), orderId, input);
    }
}
