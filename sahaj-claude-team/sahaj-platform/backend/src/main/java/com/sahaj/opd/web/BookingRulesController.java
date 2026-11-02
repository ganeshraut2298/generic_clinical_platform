package com.sahaj.opd.web;

import com.sahaj.opd.application.BookingRulesService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-11: the clinic's booking rules. Any clinic user reads; only the owner saves. */
@RestController
@RequestMapping("/api/v1/opd/booking-rules")
public class BookingRulesController {

    private final BookingRulesService service;

    public BookingRulesController(BookingRulesService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public BookingRulesDto get() {
        return BookingRulesDto.from(service.get(ClinicUser.current().tenantId()));
    }

    @PutMapping
    @PreAuthorize(OpdRoles.OWNER)
    public BookingRulesDto save(@RequestBody BookingRulesDto request) {
        ClinicUser user = ClinicUser.current();
        return BookingRulesDto.from(service.save(user.tenantId(), user.userId(), request.toInput()));
    }
}
