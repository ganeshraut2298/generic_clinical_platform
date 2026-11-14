package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrInputs.AdmissionAdviceInput;
import com.sahaj.opd.application.emr.EmrInputs.CopyItemsInput;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.PrintPreferencesInput;
import com.sahaj.opd.application.emr.EmrViews.CopyItemsResult;
import com.sahaj.opd.application.emr.EmrViews.PrintPreferences;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.SwitchDepartmentInput;
import com.sahaj.opd.application.emr.EmrCasePaperService;
import com.sahaj.opd.application.emr.EmrHistoryService;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrViews.OpenResult;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-46, SAH-47, SAH-52 (ADR-020 D8 to D11): the EMR record of an OPD visit, an IPD round or an Emergency case.
 * Doctors, visiting doctors and owners may call these; the service re-reads the membership and refuses every other role
 * with 403, and applies the own-patient and IPD scope rules. The clinic is always the signed-in user's.
 */
@RestController
@RequestMapping("/api/v1/opd/emr/records")
public class EmrRecordController implements EmrApi {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";

    private final EmrRecordService service;
    private final EmrCasePaperService casePaper;
    private final EmrHistoryService history;

    public EmrRecordController(EmrRecordService service, EmrCasePaperService casePaper, EmrHistoryService history) {
        this.service = service;
        this.casePaper = casePaper;
        this.history = history;
    }

    @GetMapping("/{recordId}/case-paper")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrCasePaperService.CasePaperContext casePaper(@PathVariable UUID recordId) {
        return casePaper.context(ClinicUser.current(), recordId);
    }

    @PostMapping("/open")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public ResponseEntity<OpenResult> open(@RequestBody OpenInput input) {
        OpenResult result = service.open(ClinicUser.current(), input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/{recordId}")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public RecordView get(@PathVariable UUID recordId) {
        return service.get(ClinicUser.current(), recordId);
    }

    @PutMapping("/{recordId}/draft")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public RecordView saveDraft(@PathVariable UUID recordId, @RequestBody RecordDraftInput input) {
        return service.saveDraft(ClinicUser.current(), recordId, input);
    }

    @PutMapping("/{recordId}/department")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public RecordView switchDepartment(@PathVariable UUID recordId, @RequestBody SwitchDepartmentInput input) {
        return service.switchDepartment(ClinicUser.current(), recordId, input);
    }

    @PostMapping("/{recordId}/copy-items")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public CopyItemsResult copyItems(@PathVariable UUID recordId, @RequestBody CopyItemsInput input) {
        return service.copyItems(ClinicUser.current(), recordId, input);
    }

    @PutMapping("/{recordId}/print-preferences")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public PrintPreferences printPreferences(@PathVariable UUID recordId, @RequestBody JsonNode body) {
        return service.setPrintPreferences(ClinicUser.current(), recordId, printPreferencesInput(body));
    }

    /**
     * The body must name both language and paperSize (each a text or null) and nothing else: an empty object is a 400, it
     * does not reset the choice (docs/api/opd.yaml EmrPrintPreferences, QA finding F-2). A record cannot tell a missing
     * property from null, so the body is read as a tree.
     */
    static PrintPreferencesInput printPreferencesInput(JsonNode body) {
        List<FieldMessage> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("body", "Send an object.")));
        }
        for (String name : List.of("language", "paperSize")) {
            if (!body.has(name)) {
                errors.add(new FieldMessage(name, "This is required. Send null for the clinic's default."));
            } else if (!body.get(name).isNull() && !body.get(name).isTextual()) {
                errors.add(new FieldMessage(name, "Choose a valid option"));
            }
        }
        boolean unknown = false;
        for (java.util.Iterator<String> names = body.fieldNames(); names.hasNext();) {
            String name = names.next();
            unknown |= !"language".equals(name) && !"paperSize".equals(name);
        }
        if (unknown) {
            // the client's property name is never echoed back; the same generic field and text as the strict JSON handler
            errors.add(new FieldMessage("body", "Send only the fields listed for this request."));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new PrintPreferencesInput(body.get("language").isNull() ? null : body.get("language").asText(),
                body.get("paperSize").isNull() ? null : body.get("paperSize").asText());
    }

    @GetMapping("/{recordId}/past-visits")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrHistoryService.PastVisitPage pastVisits(@PathVariable UUID recordId,
                                                      @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Integer page) {
        return history.list(ClinicUser.current(), recordId, type, q, page);
    }

    @GetMapping("/{recordId}/past-visits/{kind}/{pastId}")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrHistoryService.PastVisitDetail pastVisit(@PathVariable UUID recordId, @PathVariable String kind,
                                                       @PathVariable UUID pastId) {
        return history.detail(ClinicUser.current(), recordId, kind, pastId);
    }

    @GetMapping("/{recordId}/past-records/{pastRecordId}")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public RecordView pastRecord(@PathVariable UUID recordId, @PathVariable UUID pastRecordId) {
        return service.openPast(ClinicUser.current(), history.pastRecord(ClinicUser.current(), recordId, pastRecordId));
    }

    @GetMapping("/{recordId}/key-facts")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public EmrHistoryService.KeyFacts keyFacts(@PathVariable UUID recordId) {
        return history.keyFacts(ClinicUser.current(), recordId);
    }

    @PostMapping("/{recordId}/admission-advice")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public RecordView adviseAdmission(@PathVariable UUID recordId, @RequestBody AdmissionAdviceInput input) {
        return service.adviseAdmission(ClinicUser.current(), recordId, input);
    }
}
