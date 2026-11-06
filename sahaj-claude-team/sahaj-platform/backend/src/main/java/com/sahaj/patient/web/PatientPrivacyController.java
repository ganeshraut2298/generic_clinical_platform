package com.sahaj.patient.web;

import com.sahaj.patient.application.ConsentService;
import com.sahaj.patient.application.RecordAccessService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-17 patient consent and the record-access list. Any clinic role reads a patient's consent;
 * OWNER, FRONT_DESK and NURSE record or withdraw it; only the OWNER reads who opened which file.
 * The clinic and the acting user come from the signed-in user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PatientPrivacyController {

    private static final String CONSENT_WRITE = "hasAnyRole('OWNER','FRONT_DESK','NURSE')";
    private static final String OWNER = "hasRole('OWNER')";
    private static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";

    private final ConsentService consentService;
    private final RecordAccessService recordAccessService;

    public PatientPrivacyController(ConsentService consentService, RecordAccessService recordAccessService) {
        this.consentService = consentService;
        this.recordAccessService = recordAccessService;
    }

    @GetMapping("/patients/{id}/consents")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public ConsentStateResponse consents(@PathVariable UUID id) {
        return ConsentStateResponse.from(consentService.state(ClinicUser.current().tenantId(), id));
    }

    @PostMapping("/patients/{id}/consents")
    @PreAuthorize(CONSENT_WRITE)
    public ConsentStateResponse recordConsent(@PathVariable UUID id, @RequestBody ConsentAnswerRequest request) {
        ClinicUser user = ClinicUser.current();
        return ConsentStateResponse.from(consentService.record(user.tenantId(), user.userId(), id,
                request.toInput()));
    }

    @GetMapping("/record-access")
    @PreAuthorize(OWNER)
    public List<RecordAccessResponse> recordAccess(@RequestParam(required = false) Integer limit) {
        ClinicUser user = ClinicUser.current();
        return recordAccessService.recent(user.tenantId(), user.userId(), limit).stream()
                .map(RecordAccessResponse::from).toList();
    }
}
