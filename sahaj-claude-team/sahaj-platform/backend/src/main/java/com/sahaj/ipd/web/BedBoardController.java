package com.sahaj.ipd.web;

import com.sahaj.ipd.application.BedBoardService;
import com.sahaj.ipd.application.BedBoardViews.BedBoard;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SAH-24 bed board (contract {@code docs/api/ipd.yaml}). The clinic and role come from the signed-in user. */
@RestController
@RequestMapping("/api/v1/ipd")
public class BedBoardController {

    private final BedBoardService service;

    public BedBoardController(BedBoardService service) {
        this.service = service;
    }

    @GetMapping("/board")
    @PreAuthorize(AdmissionController.READERS)
    public BedBoard board() {
        ClinicUser user = ClinicUser.current();
        return service.board(user.tenantId(), user.role());
    }

    @PostMapping("/beds/{id}/ready")
    @PreAuthorize(AdmissionController.ADMITTERS)
    public BedBoard ready(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.markReady(user.tenantId(), user.role(), id);
    }

    @PostMapping("/beds/{id}/block")
    @PreAuthorize(AdmissionController.ADMITTERS)
    public BedBoard block(@PathVariable UUID id) {
        ClinicUser user = ClinicUser.current();
        return service.block(user.tenantId(), user.role(), id);
    }
}
