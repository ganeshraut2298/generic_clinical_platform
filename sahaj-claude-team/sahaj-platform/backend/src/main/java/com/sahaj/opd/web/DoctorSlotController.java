package com.sahaj.opd.web;

import com.sahaj.opd.application.DoctorSlotSettingsService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-11: each doctor's own slot pattern. Any clinic role reads; OWNER and FRONT_DESK change a doctor's settings. */
@RestController
@RequestMapping("/api/v1/opd/doctor-slots")
public class DoctorSlotController {

    private final DoctorSlotSettingsService service;

    public DoctorSlotController(DoctorSlotSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<DoctorSlotSettingsDto> list() {
        return service.listAll(ClinicUser.current().tenantId()).stream().map(DoctorSlotSettingsDto::from).toList();
    }

    @PutMapping("/{doctorMembershipId}")
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public DoctorSlotSettingsDto save(@PathVariable UUID doctorMembershipId,
                                       @RequestBody DoctorSlotSettingsDto request) {
        ClinicUser user = ClinicUser.current();
        return DoctorSlotSettingsDto.from(service.save(user.tenantId(), user.userId(), user.role(),
                doctorMembershipId, request.toInput()));
    }
}
