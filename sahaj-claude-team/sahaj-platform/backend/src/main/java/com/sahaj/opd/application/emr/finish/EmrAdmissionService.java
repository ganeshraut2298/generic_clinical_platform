package com.sahaj.opd.application.emr.finish;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionRef;
import com.sahaj.opd.api.EmrEncounterSource.EmergencyCaseFacts;
import com.sahaj.opd.application.emr.EmrComponentBinding;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.NewRequest;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RequestRow;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestInput;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestView;
import com.sahaj.opd.application.emr.specialty.SpecialtyProfiles;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-3 (ADR-025 D10): the doctor's "Admit to IPD" from the EMR. The pre-filled facts for the existing admission sheet,
 * and the admission request the front desk, nurse or owner completes with bed, deposit and consent (OQ-45-31 default).
 * One OPEN request per record; a repeat answers the open one. The patient, diagnoses and note are for the screen only: never
 * logged, and the audit entry holds ids and the type only.
 */
@Service
public class EmrAdmissionService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Set<String> TYPES = Set.of("planned", "emergency", "daycare");
    private static final String ENTITY = "EMR_RECORD";

    private final EmrRecordService records;
    private final EmrFinishRepository finish;
    private final EmrEncounterSource encounters;
    private final ModuleEntitlementPort entitlements;
    private final PatientLookupPort patients;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;

    public EmrAdmissionService(EmrRecordService records, EmrFinishRepository finish,
                               EmrEncounterSource encounters, ModuleEntitlementPort entitlements,
                               PatientLookupPort patients, TeamMemberPort teamMembers, AuditLog auditLog) {
        this.records = records;
        this.finish = finish;
        this.encounters = encounters;
        this.entitlements = entitlements;
        this.patients = patients;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
    }

    // ---- the pre-filled sheet ----

    public record PrefillPatient(UUID patientId, String name, Integer age, String sex, String mobile) {

        @Override
        public String toString() {
            return "PrefillPatient[patientId=" + patientId + "]";
        }
    }

    public record OpenAdmissionRef(UUID admissionId, String ipNumber) {
    }

    public record Prefill(PrefillPatient patient, String suggestedType, List<String> diagnoses, String procedure,
                          Integer plannedStayDays, UUID emergencyCaseId, AdmissionRequestView openRequest,
                          OpenAdmissionRef openAdmission, boolean canAdmitDirectly) {

        @Override
        public String toString() {
            return "Prefill[redacted]";
        }
    }

    @Transactional(readOnly = true)
    public Prefill prefill(ClinicUser user, UUID recordId) {
        SignContext ctx = records.signContext(user, recordId, false);
        RecordRow row = ctx.row();
        requireAdmitKind(row);
        requireIpdOn(user.tenantId());
        boolean canAdmitDirectly = "OWNER".equals(ctx.member().role());
        PrefillPatient patient;
        UUID patientId = ctx.encounter().patientId();
        if (patientId != null) {
            PatientSummary summary = patients.find(user.tenantId(), patientId).orElseThrow(EmrProblemException::encounterNotFound);
            // DEC-048 H34: the mobile goes only to the caller who can admit directly (an owner with doctor details)
            patient = new PrefillPatient(patientId, summary.name(), summary.age(), summary.sex(),
                    canAdmitDirectly ? summary.mobile() : null);
        } else {
            EmergencyCaseFacts facts = ctx.encounter().emergency();
            patient = new PrefillPatient(null, facts.displayName() == null ? "Unknown patient" : facts.displayName(),
                    facts.age(), facts.sex(), null);
        }
        boolean emergency = "ER_ASSESSMENT".equals(row.encounterType())
                || (ctx.encounter().visit() != null && "EMERGENCY".equals(ctx.encounter().visit().visitType()));
        List<String> diagnoses = diagnosisTexts(ctx.document());
        String procedure = null;
        Integer stay = null;
        JsonNode plan = surgeryPlan(ctx.document());
        if (plan != null && plan.path("booking").isObject()) {
            procedure = plan.path("procedureText").isTextual() ? plan.path("procedureText").asText() : null;
            stay = plannedStayDays(plan.path("admission").asText(null));
        }
        Optional<RequestRow> latest = finish.latestRequestOfRecord(user.tenantId(), recordId);
        AdmissionRequestView open = latest.filter(r -> "OPEN".equals(r.status())).map(r -> view(r, null)).orElse(null);
        OpenAdmissionRef openAdmission = patientId == null ? null : encounters.openAdmissionOf(user.tenantId(), patientId)
                .map(r -> new OpenAdmissionRef(r.admissionId(), r.ipNumber())).orElse(null);
        return new Prefill(patient, emergency ? "emergency" : "planned", diagnoses, procedure, stay,
                row.erCaseId(), open, openAdmission, canAdmitDirectly);
    }

    // ---- the request ----

    /** {@code created} is true for a new request (201) and false for the open one already made (200). */
    public record RequestResult(AdmissionRequestView view, boolean created) {
    }

    /** Keys starting with this are the ones the sign makes itself (Emergency outcome ADMIT); a client key cannot (opd.yaml 0.6.2). */
    static final String SIGN_KEY_PREFIX = "sign-";

    @Transactional
    public RequestResult request(ClinicUser user, UUID recordId, String key, AdmissionRequestInput input) {
        if (key == null || !KEY.matcher(key).matches() || key.startsWith(SIGN_KEY_PREFIX)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("Idempotency-Key",
                    "Send a key of 8 to 64 letters, digits, dashes or underscores. It cannot start with sign-.")));
        }
        List<FieldMessage> errors = validate(input, "");
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        UUID tenantId = user.tenantId();
        SignContext ctx = records.signContext(user, recordId, true);
        RecordRow row = ctx.row();
        requireAdmitKind(row);
        requireIpdOn(tenantId);
        // opd.yaml 0.6.2 check order: (1) the key, (2) signed, (3) closed, (4) another doctor's hold, (5) open admission, (6) open request
        String hash = hash(recordId, input);
        Optional<RequestRow> sameKey = finish.requestByKey(tenantId, key);
        if (sameKey.isPresent()) {
            if (!recordId.equals(sameKey.get().recordId()) || !hash.equals(sameKey.get().requestHash())) {
                throw EmrProblemException.idempotencyConflict();
            }
            return new RequestResult(view(sameKey.get(), null), false);
        }
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        if (ctx.encounter().closed()) {
            throw EmrProblemException.encounterClosed();
        }
        Optional<EmrFinishRepository.RecordState> state = finish.state(tenantId, recordId);
        if (state.isPresent() && state.get().holdActive() && state.get().holdMembershipId() != null
                && !state.get().holdMembershipId().equals(ctx.member().membershipId())) {
            throw EmrProblemException.emrRecordHeld(holderName(tenantId, state.get().holdMembershipId()));
        }
        UUID patientId = ctx.encounter().patientId();
        if (patientId != null) {
            Optional<AdmissionRef> admitted = encounters.openAdmissionOf(tenantId, patientId);
            if (admitted.isPresent()) {
                throw EmrProblemException.alreadyAdmitted(admitted.get().ipNumber());
            }
        }
        Optional<RequestRow> open = finish.openRequestOfRecord(tenantId, recordId);
        if (open.isPresent()) {
            return new RequestResult(view(open.get(), null), false);
        }
        RequestRow created = insert(user, ctx, input, key, hash);
        return new RequestResult(view(created, null), true);
    }

    /**
     * The request of an Emergency ADMIT outcome, made inside the sign transaction (ADR-025 D9); a repeat returns the open one.
     * Its key is derived from the record and starts with the reserved {@link #SIGN_KEY_PREFIX}, which no client request can use.
     */
    @Transactional
    UUID createInSign(ClinicUser user, SignContext ctx, AdmissionRequestInput input) {
        Optional<RequestRow> open = finish.openRequestOfRecord(user.tenantId(), ctx.row().id());
        if (open.isPresent()) {
            return open.get().id();
        }
        return insert(user, ctx, input, SIGN_KEY_PREFIX + ctx.row().id().toString().replace("-", ""),
                hash(ctx.row().id(), input)).id();
    }

    private String holderName(UUID tenantId, UUID membershipId) {
        return "Dr. " + teamMembers.find(tenantId, membershipId).map(TeamMember::name).orElse("Doctor");
    }

    private RequestRow insert(ClinicUser user, SignContext ctx, AdmissionRequestInput input, String key, String hash) {
        UUID tenantId = user.tenantId();
        RecordRow row = ctx.row();
        String reason = join(diagnosisTexts(ctx.document()), 200);
        JsonNode plan = surgeryPlan(ctx.document());
        String procedure = plan != null && plan.path("procedureText").isTextual() && plan.path("booking").isObject()
                ? clip(plan.path("procedureText").asText(), 200) : null;
        Instant created = finish.recordCreatedAt(tenantId, row.id()).orElse(row.createdAt());
        RequestRow stored = finish.insertRequest(tenantId, new NewRequest(row.id(), created, row.encounterType(),
                row.opdVisitId(), row.erCaseId(), ctx.encounter().patientId(), ctx.member().membershipId(),
                input.admissionType(), input.plannedStayDays(), reason, procedure, blankToNull(input.note()), key, hash),
                user.userId());
        finish.markAdvised(tenantId, row.id(), user.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", stored.id());
        after.put("admissionType", stored.admissionType());
        auditLog.record("EMR_ADMISSION_REQUESTED", ENTITY, row.id(), null, after);
        return stored;
    }

    /** The field errors of an admission request body (the prefix names the field inside a larger body). */
    List<FieldMessage> validate(AdmissionRequestInput input, String prefix) {
        List<FieldMessage> errors = new ArrayList<>();
        String at = prefix.isEmpty() ? "" : prefix + ".";
        if (input == null || input.admissionType() == null || !TYPES.contains(input.admissionType())) {
            errors.add(new FieldMessage(at + "admissionType", "Choose planned, emergency or day care."));
            return errors;
        }
        if (input.plannedStayDays() != null && (input.plannedStayDays() < 1 || input.plannedStayDays() > 60)) {
            errors.add(new FieldMessage(at + "plannedStayDays", "Enter 1 to 60 days."));
        }
        if (input.note() != null && input.note().strip().length() > 300) {
            errors.add(new FieldMessage(at + "note", "Enter at most 300 characters."));
        }
        return errors;
    }

    AdmissionRequestView view(RequestRow row, String ipNumber) {
        return new AdmissionRequestView(row.id(), row.status(), row.admissionType(), row.plannedStayDays(),
                row.createdAt(), row.admissionId(), ipNumber);
    }

    // ---- helpers ----

    private static void requireAdmitKind(RecordRow row) {
        if ("IPD_ROUND".equals(row.encounterType())) {
            throw EmrProblemException.notApplicable();
        }
    }

    private void requireIpdOn(UUID tenantId) {
        String state = entitlements.forTenant(tenantId).ipdState();
        if (!"trial".equals(state) && !"on".equals(state)) {
            throw new ModuleOffException("In-patients (IPD) is not switched on for your clinic.");
        }
    }

    private static List<String> diagnosisTexts(EmrRecordDocument document) {
        List<String> out = new ArrayList<>();
        for (String section : document.sectionKeys()) {
            for (JsonNode value : document.values(section)) {
                if (EmrComponentBinding.EMR_LIST.equals(value.path("component").asText())
                        && "DIAGNOSIS".equals(value.path("kind").asText())) {
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true) && item.path("text").isTextual() && out.size() < 50) {
                            out.add(item.path("text").asText().strip());
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The stay of the OT booking in days from the plan's admission choice (SURGERY_PLAN.admission): "IPD 1 day" 1, "IPD 1-2
     * days" and "IPD 2 days" 2, "IPD 4-5 days" 5 (the longer end of a range, for the bed); day care and anything else none.
     */
    static Integer plannedStayDays(String admission) {
        if (admission == null) {
            return null;
        }
        return switch (admission) {
            case "IPD_1_DAY" -> 1;
            case "IPD_1_2_DAYS", "IPD_2_DAYS" -> 2;
            case "IPD_4_5_DAYS" -> 5;
            default -> null;
        };
    }

    private static JsonNode surgeryPlan(EmrRecordDocument document) {
        String section = SpecialtyProfiles.sectionOf("surgery", "SURGERY_PLAN");
        if (section == null) {
            return null;
        }
        JsonNode value = document.values(section).path("specialty");
        return value.isObject() ? value : null;
    }

    private static String join(List<String> texts, int max) {
        if (texts.isEmpty()) {
            return null;
        }
        return clip(String.join("; ", texts), max);
    }

    private static String clip(String text, int max) {
        String stripped = text.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private String hash(UUID recordId, AdmissionRequestInput input) {
        try {
            String body = recordId + "|" + input.admissionType() + "|" + input.plannedStayDays() + "|" + blankToNull(input.note());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Doctor display name for a membership (used by the screen of the requests list). */
    static String name(TeamMember member) {
        return member == null ? "Doctor" : member.name();
    }
}
