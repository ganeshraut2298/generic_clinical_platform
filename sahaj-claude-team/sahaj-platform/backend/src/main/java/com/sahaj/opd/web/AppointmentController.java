package com.sahaj.opd.web;

import com.sahaj.opd.application.AppointmentService;
import com.sahaj.platform.api.ClinicUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-11 Appointments. Any clinic role reads the day's board; OWNER and FRONT_DESK book, cancel,
 * mark no-show and create a token. The tenant, actor and role always come from the signed-in
 * user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/opd/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<AppointmentResponse> list(@RequestParam LocalDate date,
                                           @RequestParam(required = false) UUID doctorMembershipId) {
        ClinicUser user = ClinicUser.current();
        return appointmentService.list(user.tenantId(), date, doctorMembershipId).stream()
                .map(AppointmentResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public AppointmentResponse create(@RequestBody AppointmentCreateRequest request) {
        ClinicUser user = ClinicUser.current();
        return AppointmentResponse.from(
                appointmentService.create(user.tenantId(), user.userId(), user.role(), request.toInput()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public AppointmentResponse changeStatus(@PathVariable UUID id, @RequestBody AppointmentStatusRequest request) {
        ClinicUser user = ClinicUser.current();
        return AppointmentResponse.from(
                appointmentService.changeStatus(user.tenantId(), user.userId(), user.role(), id, request.status()));
    }

    @PostMapping("/{id}/token")
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public AppointmentResponse createToken(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return AppointmentResponse.from(appointmentService.createToken(user.tenantId(), user.userId(), user.role(), id));
    }
}
