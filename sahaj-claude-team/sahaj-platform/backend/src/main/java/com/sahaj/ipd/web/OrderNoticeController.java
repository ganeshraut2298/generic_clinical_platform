package com.sahaj.ipd.web;

import com.sahaj.ipd.application.OrderNoticeService;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeList;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePage;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeSummary;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-76-3 the nurse's order inbox (contract {@code docs/api/ipd.yaml} 0.4.0, ADR-027 D5): the Orders tab of one patient,
 * the Safety tab panel, the 30-second counts and the acknowledgement. The clinic, the person and the role always come from
 * the signed-in user. Every endpoint is open to any signed-in clinic role here on purpose: the contract gives each refused
 * role its own 403 detail, so the role check, module-off and the business answers come from the service, which re-checks
 * the token role and the role of the active membership. The answers carry names and medicines; they are never logged.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class OrderNoticeController {

    private final OrderNoticeService notices;

    public OrderNoticeController(OrderNoticeService notices) {
        this.notices = notices;
    }

    @ScopeGuarded(ScopeGuarded.Kind.ADMISSION)
    @GetMapping("/admissions/{id}/order-notices")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public OrderNoticeList forAdmission(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return notices.forAdmission(user.tenantId(), user.userId(), user.role(), id);
    }

    /** The limit and the cursor are read as text so that the service gives the contract's own 400 for a bad one. */
    @ScopeGuarded(ScopeGuarded.Kind.LIST)
    @GetMapping("/order-notices")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public OrderNoticePage page(@RequestParam(name = "limit", required = false) String limit,
                                @RequestParam(name = "cursor", required = false) String cursor) {
        ClinicUser user = ClinicUser.current();
        return notices.page(user.tenantId(), user.userId(), user.role(), limit, cursor);
    }

    @ScopeGuarded(ScopeGuarded.Kind.LIST)
    @GetMapping("/order-notices/summary")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public OrderNoticeSummary summary() {
        ClinicUser user = ClinicUser.current();
        return notices.summary(user.tenantId(), user.userId(), user.role());
    }

    @ScopeGuarded(ScopeGuarded.Kind.NOTICE)
    @PostMapping("/order-notices/{id}/acknowledge")
    @PreAuthorize(MedicineOrderController.CLINIC_ROLES)
    public OrderNoticeList acknowledge(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return notices.acknowledge(user.tenantId(), user.userId(), user.role(), id);
    }
}
