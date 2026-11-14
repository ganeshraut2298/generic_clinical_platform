package com.sahaj.opd.web;

import com.sahaj.opd.api.EmrEmergencyPort.TriageScale;
import com.sahaj.opd.api.EmrIpdOrderPort.OrderOptions;
import com.sahaj.opd.application.emr.EmrVisitTypeService;
import com.sahaj.opd.application.emr.finish.EmrAddendumService;
import com.sahaj.opd.application.emr.finish.EmrAdmissionService;
import com.sahaj.opd.application.emr.finish.EmrEditHoldService;
import com.sahaj.opd.application.emr.finish.EmrEncounterVitalsService;
import com.sahaj.opd.application.emr.finish.EmrFreeSlotService;
import com.sahaj.opd.application.emr.finish.EmrReprintService;
import com.sahaj.opd.application.emr.finish.EmrSignService;
import com.sahaj.opd.application.emr.finish.FinishModels.AddendumInput;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestInput;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestView;
import com.sahaj.opd.application.emr.finish.FinishModels.Addendum;
import com.sahaj.opd.application.emr.finish.FinishModels.EditHold;
import com.sahaj.opd.application.emr.finish.FinishModels.EditHoldInput;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishCheck;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishChoices;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import com.sahaj.opd.application.emr.finish.FinishModels.SignInput;
import com.sahaj.opd.application.emr.finish.FinishModels.SignResult;
import com.sahaj.platform.api.ClinicUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-51, SAH-67, SAH-68 (docs/api/opd.yaml 0.6.0, ADR-025): finish and sign, addenda, the edit hold, the encounter vitals,
 * the admission request, the free slots, the triage levels and the reprint of a signed prescription. Thin: the role gate is
 * here, every rule (own patient, IPD scope, the signed state) is in the services, which read the membership again. The clinic
 * is always the signed-in user's.
 */
@RestController
@RequestMapping("/api/v1/opd")
public class EmrFinishController implements EmrApi {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";
    private static final String REPRINT_ROLES = "hasAnyRole('OWNER','FRONT_DESK','PHARMACIST','DOCTOR','VISITING_DOCTOR')";
    private static final String KEY = "Idempotency-Key";

    private final EmrSignService sign;
    private final EmrAddendumService addenda;
    private final EmrEditHoldService holds;
    private final EmrEncounterVitalsService vitals;
    private final EmrAdmissionService admissions;
    private final EmrFreeSlotService freeSlots;
    private final EmrReprintService reprints;
    private final EmrVisitTypeService visitTypes;

    public EmrFinishController(EmrSignService sign, EmrAddendumService addenda, EmrEditHoldService holds,
                               EmrEncounterVitalsService vitals, EmrAdmissionService admissions,
                               EmrFreeSlotService freeSlots, EmrReprintService reprints, EmrVisitTypeService visitTypes) {
        this.sign = sign;
        this.addenda = addenda;
        this.holds = holds;
        this.vitals = vitals;
        this.admissions = admissions;
        this.freeSlots = freeSlots;
        this.reprints = reprints;
        this.visitTypes = visitTypes;
    }

    @PostMapping("/emr/records/{recordId}/finish-check")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public FinishCheck finishCheck(@PathVariable UUID recordId, @RequestBody(required = false) FinishChoices choices) {
        return sign.check(ClinicUser.current(), recordId, choices);
    }

    @PostMapping("/emr/records/{recordId}/sign")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public SignResult sign(@PathVariable UUID recordId, @RequestHeader(value = KEY, required = false) String key,
                           @RequestBody SignInput input) {
        return sign.sign(ClinicUser.current(), recordId, key, input);
    }

    @PostMapping("/emr/records/{recordId}/addenda")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ResponseEntity<Addendum> addAddendum(@PathVariable UUID recordId,
                                                @RequestHeader(value = KEY, required = false) String key,
                                                @RequestBody AddendumInput input) {
        EmrAddendumService.Result result = addenda.add(ClinicUser.current(), recordId, key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.addendum());
    }

    @PutMapping("/emr/records/{recordId}/edit-hold")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EditHold takeHold(@PathVariable UUID recordId, @RequestBody(required = false) EditHoldInput input) {
        return holds.take(ClinicUser.current(), recordId, input != null && Boolean.TRUE.equals(input.takeOver()));
    }

    @DeleteMapping("/emr/records/{recordId}/edit-hold")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ResponseEntity<Void> releaseHold(@PathVariable UUID recordId) {
        holds.release(ClinicUser.current(), recordId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/emr/records/{recordId}/encounter-vitals")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrEncounterVitalsService.EncounterVitals encounterVitals(@PathVariable UUID recordId) {
        return vitals.vitals(ClinicUser.current(), recordId);
    }

    @GetMapping("/emr/records/{recordId}/ipd-order-options")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public OrderOptions ipdOrderOptions(@PathVariable UUID recordId) {
        return vitals.ipdOrderOptions(ClinicUser.current(), recordId);
    }

    @GetMapping("/emr/records/{recordId}/admission-prefill")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrAdmissionService.Prefill admissionPrefill(@PathVariable UUID recordId) {
        return admissions.prefill(ClinicUser.current(), recordId);
    }

    @PostMapping("/emr/records/{recordId}/admission-request")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ResponseEntity<AdmissionRequestView> requestAdmission(@PathVariable UUID recordId,
                                                                 @RequestHeader(value = KEY, required = false) String key,
                                                                 @RequestBody AdmissionRequestInput input) {
        EmrAdmissionService.RequestResult result = admissions.request(ClinicUser.current(), recordId, key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.view());
    }

    @GetMapping("/emr/records/{recordId}/free-slots")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public List<FreeSlot> freeSlots(@PathVariable UUID recordId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) Integer limit) {
        return freeSlots.freeSlots(ClinicUser.current(), recordId, from, limit);
    }

    @GetMapping("/emr/triage-levels")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public TriageScale triageLevels() {
        return visitTypes.triageLevels(ClinicUser.current());
    }

    @GetMapping("/visits/{visitId}/prescription-print")
    @PreAuthorize(REPRINT_ROLES)
    public EmrReprintService.Reprint prescriptionPrint(@PathVariable UUID visitId) {
        return reprints.reprint(ClinicUser.current(), visitId);
    }
}
