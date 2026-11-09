package com.sahaj.ipd.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.CardSetupService;
import com.sahaj.ipd.application.CardSetupViews.CardSetupView;
import com.sahaj.platform.api.ClinicUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-37 Clinic setup, Patient card (contract {@code docs/api/ipd.yaml}, tag SAH-37; ADR-015). The clinic, the person
 * and the role always come from the signed-in user. The write bodies are taken as JSON trees and read strictly by the
 * service (an unknown or missing property is a 400 naming its path), so the contract's {@code additionalProperties:
 * false} holds. Module-off, read-only, validation and conflict answers come from the service.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class CardSetupController {

    private final CardSetupService service;

    public CardSetupController(CardSetupService service) {
        this.service = service;
    }

    @GetMapping("/card-setup")
    @PreAuthorize(AdmissionController.READERS)
    public CardSetupView view() {
        ClinicUser user = ClinicUser.current();
        return service.view(user.tenantId(), user.userId(), user.role());
    }

    @PutMapping("/card-setup/draft")
    @PreAuthorize(AdmissionController.OWNER)
    public CardSetupView saveDraft(@RequestBody JsonNode request) {
        ClinicUser user = ClinicUser.current();
        return service.saveDraft(user.tenantId(), user.userId(), user.role(), request);
    }

    @DeleteMapping("/card-setup/draft")
    @PreAuthorize(AdmissionController.OWNER)
    public CardSetupView discardDraft(@RequestParam(name = "draftRevision") Long draftRevision) {
        ClinicUser user = ClinicUser.current();
        return service.discardDraft(user.tenantId(), user.userId(), user.role(), draftRevision);
    }

    @PostMapping("/card-setup/publish")
    @PreAuthorize(AdmissionController.OWNER)
    public CardSetupView publish(@RequestBody JsonNode request) {
        ClinicUser user = ClinicUser.current();
        return service.publish(user.tenantId(), user.userId(), user.role(), request);
    }
}
