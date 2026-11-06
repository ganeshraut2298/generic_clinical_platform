package com.sahaj.opd.web;

import com.sahaj.opd.application.TodaySummaryService;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-15 Today dashboard: any clinic role reads; read-only, no audit rows. */
@RestController
@RequestMapping("/api/v1/opd/today-summary")
public class TodaySummaryController {

    private final TodaySummaryService todaySummaryService;

    public TodaySummaryController(TodaySummaryService todaySummaryService) {
        this.todaySummaryService = todaySummaryService;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public TodaySummaryResponse get() {
        ClinicUser user = ClinicUser.current();
        return TodaySummaryResponse.from(todaySummaryService.summary(user.tenantId(), user.role()));
    }
}
