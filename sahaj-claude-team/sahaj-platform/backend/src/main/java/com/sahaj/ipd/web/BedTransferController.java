package com.sahaj.ipd.web;

import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.AdmissionViews.TransferInput;
import com.sahaj.ipd.application.AdmissionViews.TransferRulesInput;
import com.sahaj.ipd.application.BedTransferService;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-26 bed transfer and transfer rules (contract {@code docs/api/ipd.yaml}). The clinic, the person and the role
 * always come from the signed-in user. Module-off, read-only and the owner-approval answers come from the service.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class BedTransferController {

    private final BedTransferService service;

    public BedTransferController(BedTransferService service) {
        this.service = service;
    }

    @GetMapping("/transfer-rules")
    @PreAuthorize(AdmissionController.READERS)
    public TransferRules rules() {
        return service.rules(ClinicUser.current().tenantId());
    }

    @PutMapping("/transfer-rules")
    @PreAuthorize(AdmissionController.OWNER)
    public TransferRules updateRules(@RequestBody TransferRulesInput request) {
        ClinicUser user = ClinicUser.current();
        return service.updateRules(user.tenantId(), user.userId(), request.upgradeNeedsOwner(),
                request.newRateFrom());
    }

    @PostMapping("/admissions/{id}/transfer")
    @PreAuthorize(AdmissionController.ADMITTERS)
    public Detail transfer(@PathVariable UUID id, @RequestBody TransferInput request) {
        ClinicUser user = ClinicUser.current();
        return service.transfer(user.tenantId(), user.userId(), user.role(), id, request.toBedId(),
                request.reason());
    }
}
