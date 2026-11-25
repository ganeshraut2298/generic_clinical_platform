package com.sahaj.opd.web;

import com.sahaj.opd.application.DeskReadService;
import com.sahaj.opd.application.DeskViews.DaySlots;
import com.sahaj.opd.application.DeskViews.Snapshot;
import com.sahaj.opd.application.DeskViews.TokenPanel;
import com.sahaj.platform.api.ClinicUser;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-90 Front desk console reads (docs/api/opd.yaml 0.9.0, ADR-032). Any clinic role reads; the free counts and slots follow
 * the caller's role. The tenant and role come from the signed-in user only. The snapshot is sent with {@code Cache-Control:
 * no-store} (it carries patient names and clock-derived values) and no ETag; gzip comes from {@code server.compression}.
 */
@RestController
@RequestMapping("/api/v1/opd/desk")
public class DeskController implements DeskApi {

    private final DeskReadService service;

    public DeskController(DeskReadService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public ResponseEntity<Snapshot> snapshot(@RequestParam(required = false) UUID doctorMembershipId,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(required = false) UUID boardDoctorMembershipId) {
        ClinicUser user = ClinicUser.current();
        Snapshot snapshot = service.snapshot(user.tenantId(), user.role(), doctorMembershipId, date,
                boardDoctorMembershipId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
    }

    /** SAH-92-2: the token panel of a visit, reopened from a Waiting card or for the slip. Any clinic role; reads only. */
    @GetMapping("/visits/{visitId}/token-panel")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public ResponseEntity<TokenPanel> tokenPanel(@PathVariable UUID visitId) {
        ClinicUser user = ClinicUser.current();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.tokenPanel(user.tenantId(), visitId));
    }

    @GetMapping("/doctors/{doctorMembershipId}/day-slots")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public DaySlots daySlots(@PathVariable UUID doctorMembershipId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ClinicUser user = ClinicUser.current();
        return service.daySlots(user.tenantId(), user.role(), doctorMembershipId, date);
    }
}
