package com.sahaj.opd.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrInputs.RevisionInput;
import com.sahaj.opd.application.emr.specialty.CancerJourneyService;
import com.sahaj.opd.application.emr.specialty.CancerJourneyService.JourneyView;
import com.sahaj.opd.application.emr.specialty.SpecialtyHistoryService;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceService;
import com.sahaj.opd.application.emr.specialty.SurgeryBookingService;
import com.sahaj.opd.application.emr.specialty.SurgeryBookingService.Result;
import com.sahaj.opd.application.emr.specialty.VisitTemplateService;
import com.sahaj.opd.application.emr.specialty.VisitTemplateService.Input;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-49 to SAH-71 (ADR-023 D2, D4, D8, D10; docs/api/opd.yaml 0.5.0): the specialty reference, the specialty history of a
 * record, OT booking and the visit templates. Doctors, visiting doctors and owners may call these; each service re-reads
 * the membership ({@code clinic.consultation.write}) and applies the own-patient rule. The clinic is always the signed-in
 * user's. No patient data is logged.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrSpecialtyController implements EmrApi {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";

    private final SpecialtyReferenceService reference;
    private final SpecialtyHistoryService history;
    private final SurgeryBookingService booking;
    private final VisitTemplateService templates;
    private final CancerJourneyService journey;

    public EmrSpecialtyController(SpecialtyReferenceService reference, SpecialtyHistoryService history,
                                  SurgeryBookingService booking, VisitTemplateService templates,
                                  CancerJourneyService journey) {
        this.reference = reference;
        this.history = history;
        this.booking = booking;
        this.templates = templates;
        this.journey = journey;
    }

    @GetMapping("/specialty-reference/{profile}")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode specialtyReference(@PathVariable String profile) {
        return reference.get(ClinicUser.current(), profile);
    }

    @GetMapping("/records/{recordId}/specialty-history")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ObjectNode specialtyHistory(@PathVariable UUID recordId) {
        return history.get(ClinicUser.current(), recordId);
    }

    @PostMapping("/records/{recordId}/surgery-booking")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public Result bookSurgery(@PathVariable UUID recordId,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @RequestBody RevisionInput input) {
        return booking.book(ClinicUser.current(), recordId, key, input == null ? null : input.revision());
    }

    /** SAH-75-7 (ADR-026 D7): appends a version of the patient's Cancer journey; the body is read field by field. */
    @PutMapping("/records/{recordId}/cancer-journey")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public JourneyView saveCancerJourney(@PathVariable UUID recordId, @RequestBody JsonNode body) {
        return journey.save(ClinicUser.current(), recordId, body);
    }

    @GetMapping("/visit-templates")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public List<ObjectNode> visitTemplates() {
        return templates.list(ClinicUser.current());
    }

    @PostMapping("/visit-templates")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ResponseEntity<ObjectNode> createVisitTemplate(@RequestBody Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templates.create(ClinicUser.current(), input));
    }
}
