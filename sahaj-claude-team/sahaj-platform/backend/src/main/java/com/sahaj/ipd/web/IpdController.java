package com.sahaj.ipd.web;

import com.sahaj.ipd.application.IpdLayout;
import com.sahaj.ipd.application.IpdService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-22 IPD setup (contract {@code docs/api/ipd.yaml}). Any clinic role reads; only the owner changes anything.
 * The clinic and the person always come from the signed-in user. Module-off and read-only answers come from
 * the service.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class IpdController {

    static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";
    static final String OWNER = "hasRole('OWNER')";

    private final IpdService service;

    public IpdController(IpdService service) {
        this.service = service;
    }

    @GetMapping("/layout")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public IpdLayout layout() {
        return service.layout(ClinicUser.current().tenantId());
    }

    @PostMapping("/wards")
    @PreAuthorize(OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public IpdLayout addWard(@RequestBody WardCreateRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.addWard(user.tenantId(), user.userId(), request.name());
    }

    @PostMapping("/wards/{id}/rooms")
    @PreAuthorize(OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public IpdLayout addRoom(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.addRoom(user.tenantId(), user.userId(), id);
    }

    @PostMapping("/beds")
    @PreAuthorize(OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public IpdLayout addBed(@RequestBody BedCreateRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.addBed(user.tenantId(), user.userId(), request.wardId(), request.roomId());
    }

    @PutMapping("/beds/{id}")
    @PreAuthorize(OWNER)
    public IpdLayout changeBedCategory(@PathVariable UUID id, @RequestBody BedUpdateRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.changeBedCategory(user.tenantId(), user.userId(), id, request.categoryKey());
    }

    @DeleteMapping("/beds/{id}")
    @PreAuthorize(OWNER)
    public IpdLayout removeBed(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.removeBed(user.tenantId(), user.userId(), id);
    }

    @PutMapping("/room-level")
    @PreAuthorize(OWNER)
    public IpdLayout setRoomLevel(@RequestBody RoomLevelRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.setRoomLevel(user.tenantId(), user.userId(), request.on());
    }

    @PutMapping("/categories/{key}")
    @PreAuthorize(OWNER)
    public IpdLayout updateCategory(@PathVariable String key, @RequestBody CategoryChargeRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.updateCategory(user.tenantId(), user.userId(), key, request.ratePaise(), request.multiplier());
    }

    @PutMapping("/charges")
    @PreAuthorize(OWNER)
    public IpdLayout updateCharges(@RequestBody ChargesRequest request) {
        ClinicUser user = ClinicUser.current();
        return service.updateCharges(user.tenantId(), user.userId(), request.bedDayCut(), request.nursingPaise(),
                request.visitPaise());
    }
}
