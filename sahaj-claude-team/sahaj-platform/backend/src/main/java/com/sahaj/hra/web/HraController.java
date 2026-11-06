package com.sahaj.hra.web;

import com.sahaj.hra.application.AttendanceView;
import com.sahaj.hra.application.HraService;
import com.sahaj.hra.application.LeaveInput;
import com.sahaj.hra.application.LeaveService;
import com.sahaj.hra.application.LeaveView;
import com.sahaj.hra.application.RosterCellInput;
import com.sahaj.hra.application.RosterView;
import com.sahaj.hra.application.StaffSummaryView;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-20 Staff and leave (contract {@code docs/api/hra.yaml}). Any clinic role reads; the owner edits the
 * roster and publishes; the owner and front desk mark attendance. The clinic, the person and the role
 * always come from the signed-in user. When the HRA add-on is off every call answers 403 module-off
 * (checked in the service).
 */
@RestController
@RequestMapping("/api/v1/hra")
public class HraController {

    static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";
    static final String OWNER = "hasRole('OWNER')";
    static final String OWNER_OR_FRONT_DESK = "hasAnyRole('OWNER','FRONT_DESK')";

    private final HraService service;
    private final LeaveService leaveService;

    public HraController(HraService service, LeaveService leaveService) {
        this.service = service;
        this.leaveService = leaveService;
    }

    @GetMapping("/summary")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public StaffSummaryView summary() {
        ClinicUser user = ClinicUser.current();
        return service.summary(user.tenantId(), user.role());
    }

    @GetMapping("/roster")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public RosterView roster() {
        return service.roster(ClinicUser.current().tenantId());
    }

    @PutMapping("/roster/cells")
    @PreAuthorize(OWNER)
    public RosterView setCell(@RequestBody RosterCellRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.setCell(user.tenantId(), user.userId(),
                new RosterCellInput(request.membershipId(), request.date(), request.shift()));
    }

    @PostMapping("/roster/publish")
    @PreAuthorize(OWNER)
    public RosterView publish() {
        ClinicUser user = ClinicUser.current();
        return service.publish(user.tenantId(), user.userId());
    }

    @GetMapping("/attendance")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public AttendanceView attendance() {
        ClinicUser user = ClinicUser.current();
        return service.attendance(user.tenantId(), user.role());
    }

    @PutMapping("/attendance/{membershipId}")
    @PreAuthorize(OWNER_OR_FRONT_DESK)
    public AttendanceView mark(@PathVariable UUID membershipId, @RequestBody AttendanceMarkRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.mark(user.tenantId(), user.userId(), user.role(), membershipId, request.status());
    }

    @GetMapping("/leave")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public LeaveView leave() {
        ClinicUser user = ClinicUser.current();
        return leaveService.view(user.tenantId(), user.userId(), user.role());
    }

    @PostMapping("/leave")
    @PreAuthorize(ANY_CLINIC_ROLE)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveView applyForLeave(@RequestBody LeaveRequestBody request) {
        ClinicUser user = ClinicUser.current();
        return leaveService.create(user.tenantId(), user.userId(), user.role(),
                new LeaveInput(request.membershipId(), request.type(), request.from(), request.to(), request.reason()));
    }

    @PostMapping("/leave/{id}/approve")
    @PreAuthorize(OWNER)
    public LeaveView approveLeave(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return leaveService.approve(user.tenantId(), user.userId(), user.role(), id);
    }

    @PostMapping("/leave/{id}/decline")
    @PreAuthorize(OWNER)
    public LeaveView declineLeave(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return leaveService.decline(user.tenantId(), user.userId(), user.role(), id);
    }
}
