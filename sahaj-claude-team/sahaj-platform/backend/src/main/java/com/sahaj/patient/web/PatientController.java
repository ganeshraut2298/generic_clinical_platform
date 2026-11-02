package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-8 Patients. Any clinic role reads; only OWNER and FRONT_DESK write (AC8). The clinic comes
 * from the signed-in user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/patient/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @GetMapping
    @PreAuthorize(PatientRoles.READ)
    public PatientPageResponse list(@RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "all") String filter,
                                     @RequestParam(defaultValue = "0") int page) {
        return PatientPageResponse.from(patientService.search(ClinicUser.current().tenantId(), q, filter, page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PatientRoles.WRITE)
    public PatientResponse create(@RequestBody PatientCreateRequest request) {
        ClinicUser user = ClinicUser.current();
        return PatientResponse.from(patientService.create(user.tenantId(), user.userId(), request.toInput()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(PatientRoles.READ)
    public PatientResponse get(@PathVariable UUID id) {
        return PatientResponse.from(patientService.find(ClinicUser.current().tenantId(), id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(PatientRoles.WRITE)
    public PatientResponse update(@PathVariable UUID id, @RequestBody PatientUpdateRequest request) {
        ClinicUser user = ClinicUser.current();
        return PatientResponse.from(
                patientService.update(user.tenantId(), user.userId(), id, request.toInput()));
    }

    @GetMapping("/{id}/family")
    @PreAuthorize(PatientRoles.READ)
    public List<PatientSummaryResponse> family(@PathVariable UUID id) {
        return patientService.family(ClinicUser.current().tenantId(), id).stream()
                .map(PatientSummaryResponse::from).toList();
    }

    @PostMapping("/merge")
    @PreAuthorize(PatientRoles.WRITE)
    public PatientResponse merge(@RequestBody MergeRequest request) {
        ClinicUser user = ClinicUser.current();
        return PatientResponse.from(patientService.merge(user.tenantId(), user.userId(),
                request.requireKeepId(), request.requireMergeId()));
    }
}
