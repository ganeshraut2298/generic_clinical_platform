package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.EmrFormModel.CreateInput;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.DraftInput;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.RevisionInput;
import com.sahaj.opd.application.emr.EmrFormModel.SendBackInput;
import com.sahaj.opd.application.emr.EmrFormModel.Settings;
import com.sahaj.opd.application.emr.EmrFormRepository.FormRow;
import com.sahaj.opd.application.emr.EmrFormRepository.FormState;
import com.sahaj.opd.application.emr.EmrFormRepository.NewForm;
import com.sahaj.opd.application.emr.EmrFormRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrFormRepository.VersionRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The form builder (SAH-59; ADR-022 D11): a clinic's forms as data, their drafts, approval, publishing, retiring and the
 * six starter forms. Only an active owner changes anything ({@code clinic.emr_forms.edit} and
 * {@code clinic.emr_forms.approve} are both the owner; a clinic with one owner approves its own forms); every clinic role
 * reads. Everything is all or nothing, guarded by the form revision (409 form-changed), and audited by ids and setup
 * values only (no patient data exists here; field labels are not put in the audit).
 *
 * <p>A form has one working draft with a state (DRAFT, WAITING_APPROVAL, SENT_BACK) and immutable published versions.
 * A live form that is edited gets draft v(live+1); the live version stays in use until the new one is approved.
 */
@Service
public class EmrFormService {

    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String ENTITY = "EMR_FORM";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    // ---- views ----

    public record UseIn(List<String> departmentKeys, List<String> places) {
    }

    public record Summary(String formKey, String name, String state, int chipVersionNo, int liveVersionNo,
                          String starterKey, EmrFormModel.UseIn useIn, String emrPart, int fieldCount, int logicCount,
                          int scoredCount, Instant updatedAt) {
    }

    public record FormList(boolean canEdit, boolean canApprove, List<Summary> forms) {
    }

    public record VersionLine(int versionNo, String state, Instant at, String byName) {
    }

    public record FormView(String formKey, String name, String state, int chipVersionNo, int liveVersionNo,
                           String starterKey, EmrFormModel.UseIn useIn, String emrPart, int fieldCount, int logicCount,
                           int scoredCount, Instant updatedAt, boolean canEdit, boolean canApprove, long revision,
                           Definition definition, String sentBackReason, List<VersionLine> versions) {
    }

    public record VersionView(String formKey, int versionNo, Definition definition, Instant publishedAt,
                              String publishedByName, String approvedByName) {
    }

    public record StarterSummary(String starterKey, String name, String description, String suitedFor) {
    }

    public record StarterDetail(String starterKey, String name, String description, String suitedFor,
                                Definition definition) {
    }

    /** The result of a create: the form and whether this call made it (false = the same key came before). */
    public record Created(boolean created, FormView form) {
    }

    private final EmrAccess access;
    private final EmrFormRepository forms;
    private final EmrLibraryRepository library;
    private final EmrDepartmentRepository departments;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final SecureRandom random;
    private final Clock clock;

    public EmrFormService(EmrAccess access, EmrFormRepository forms, EmrLibraryRepository library,
                          EmrDepartmentRepository departments, TeamMemberPort teamMembers, AuditLog auditLog,
                          ObjectMapper mapper, SecureRandom random, Clock clock) {
        this.access = access;
        this.forms = forms;
        this.library = library;
        this.departments = departments;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.random = random;
        this.clock = clock;
    }

    // ---- reads (any clinic role) ----

    @Transactional(readOnly = true)
    public FormList list(ClinicUser user) {
        boolean owner = access.canEditSetup(user);
        List<Summary> result = new ArrayList<>();
        for (FormRow row : forms.findAll(user.tenantId())) {
            result.add(summary(row, working(row, latestVersion(user.tenantId(), row))));
        }
        return new FormList(owner, owner, result);
    }

    @Transactional(readOnly = true)
    public FormView get(ClinicUser user, String formKey) {
        FormRow row = require(user.tenantId(), formKey);
        return view(user, row);
    }

    @Transactional(readOnly = true)
    public VersionView version(ClinicUser user, String formKey, int versionNo) {
        FormRow row = require(user.tenantId(), formKey);
        if (versionNo < 1 || versionNo > 100_000) {
            throw EmrProblemException.badRequest("Choose a version number from 1 to 100000.");
        }
        VersionRow version = forms.version(user.tenantId(), row.id(), versionNo)
                .orElseThrow(EmrProblemException::versionNotFound);
        return new VersionView(row.formKey(), versionNo, read(version.definition()), version.publishedAt(),
                nameOf(user.tenantId(), version.publishedBy()),
                version.approvedBy() == null ? null : nameOf(user.tenantId(), version.approvedBy()));
    }

    @Transactional(readOnly = true)
    public List<StarterSummary> starters(ClinicUser user) {
        return forms.starters().stream()
                .map(s -> new StarterSummary(s.starterKey(), s.name(), s.description(), s.suitedFor())).toList();
    }

    @Transactional(readOnly = true)
    public StarterDetail starter(ClinicUser user, String starterKey) {
        StarterRow row = forms.starter(starterKey).orElseThrow(EmrFormService::starterNotFound);
        return new StarterDetail(row.starterKey(), row.name(), row.description(), row.suitedFor(),
                read(row.definition()));
    }

    // ---- create ("Use this starter") ----

    @Transactional
    public Created create(ClinicUser user, String idempotencyKey, CreateInput input) {
        access.requireSetupEdit(user);
        UUID tenantId = user.tenantId();
        requireKey(idempotencyKey);
        if (input == null || input.starterKey() == null) {
            throw invalid("starterKey", "Choose a starter form.");
        }
        String hash = hash(input.starterKey() + "|" + (input.name() == null ? "" : input.name().strip()));
        Optional<FormRow> earlier = forms.findByIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isPresent()) {
            if (!hash.equals(earlier.get().requestHash())) {
                throw EmrProblemException.of(409, "idempotency-conflict", "Request already used",
                        "This request key was used for a different request.");
            }
            return new Created(false, view(user, earlier.get()));
        }
        StarterRow starter = forms.starter(input.starterKey()).orElseThrow(EmrFormService::starterNotFound);
        if (forms.count(tenantId) >= EmrFormModel.MAX_FORMS) {
            throw EmrProblemException.of(400, "form-limit", "Form limit reached",
                    "A clinic can have at most 100 forms.");
        }
        Definition base = read(starter.definition());
        String name = input.name() == null ? uniqueName(tenantId, starter.name()) : input.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            throw invalid("name", "Enter a form name of 1 to 80 characters.");
        }
        if (forms.nameTaken(tenantId, name, null)) {
            throw invalid("name", "A form with this name already exists.");
        }
        Settings settings = base.settings() == null ? null : new Settings(base.settings().useIn(), EmrModel.PART_ASSESSMENT,
                base.settings().visitTypes(), base.settings().whoCanFill(), base.settings().printLanguage(),
                base.settings().approvalRequired());
        Definition draft = new Definition(EmrFormModel.SCHEMA_VERSION, name, settings, base.fields());
        UUID id = null;
        String key = null;
        for (int attempt = 0; attempt < 5 && id == null; attempt++) {
            key = newKey();
            try {
                id = forms.insert(tenantId, new NewForm(key, name, starter.starterKey(), 1, write(draft),
                        idempotencyKey, hash), user.userId());
            } catch (DuplicateKeyException e) {
                if (forms.nameTaken(tenantId, name, null)) {
                    throw invalid("name", "A form with this name already exists.");
                }
            }
        }
        if (id == null) {
            throw new IllegalStateException("A form key could not be generated");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("formKey", key);
        after.put("starterKey", starter.starterKey());
        after.put("versionNo", 1);
        after.put("fieldCount", draft.fields() == null ? 0 : draft.fields().size());
        auditLog.record("EMR_FORM_CREATED", ENTITY, id, null, after);
        return new Created(true, view(user, require(tenantId, key)));
    }

    private String uniqueName(UUID tenantId, String base) {
        String name = base;
        for (int n = 2; forms.nameTaken(tenantId, name, null) && n < 200; n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    // ---- save draft ----

    @Transactional
    public FormView saveDraft(ClinicUser user, String formKey, DraftInput input) {
        access.requireSetupEdit(user);
        UUID tenantId = user.tenantId();
        FormRow row = require(tenantId, formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        requireNotRetired(row);
        String name = input.name() == null ? "" : input.name().strip();
        List<FieldMessage> errors = EmrFormRules.checkDraft(name, input.settings(), input.fields(),
                doctorDepartmentKeys(tenantId));
        if (name.length() >= 1 && name.length() <= 80 && forms.nameTaken(tenantId, name, row.id())) {
            errors.add(new FieldMessage("name", "A form with this name already exists."));
        }
        errors.addAll(removedSourceErrors(row, input.fields()));
        if (!errors.isEmpty()) {
            throw EmrProblemException.of(400, "form-invalid", "The form has problems",
                    "Fix the highlighted fields and save again.").withFieldErrors(errors);
        }
        Definition draft = new Definition(EmrFormModel.SCHEMA_VERSION, name, input.settings(), input.fields());
        String json = write(draft);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw invalid("fields", "This form is too large (256 KB at most).");
        }
        if (revision != row.revision()) {
            throw EmrProblemException.of(409, "form-changed", "Form changed",
                    "Someone else changed this form. Reload and try again.");
        }
        FormState state = new FormState(name, row.status(), row.liveVersionNo(), row.liveVersionNo() + 1, "DRAFT", json,
                null, null, null);
        apply(row, revision, state, user);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("revision", revision);
        before.put("state", row.draftState());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("revision", revision + 1);
        after.put("versionNo", row.liveVersionNo() + 1);
        after.put("fieldCount", input.fields() == null ? 0 : input.fields().size());
        after.put("departmentCount", input.settings().useIn().departmentKeys().size());
        after.put("approvalRequired", input.settings().approvalRequired());
        auditLog.record("EMR_FORM_DRAFT_SAVED", ENTITY, row.id(), before, after);
        return view(user, require(tenantId, formKey));
    }

    /** A field that another field reads (show-if, score, band) cannot be removed while it is used. */
    private List<FieldMessage> removedSourceErrors(FormRow row, List<Field> newFields) {
        List<FieldMessage> errors = new ArrayList<>();
        if (row.draftDefinition() == null) {
            return errors;
        }
        Set<String> keys = new HashSet<>();
        for (Field field : newFields == null ? List.<Field>of() : newFields) {
            keys.add(field.key());
        }
        for (Map.Entry<String, List<String>> used : EmrFormRules.usedBy(read(row.draftDefinition()).fields())
                .entrySet()) {
            if (!keys.contains(used.getKey()) && used.getValue().stream().anyMatch(keys::contains)) {
                errors.add(new FieldMessage("fields", "Field " + used.getKey() + " is used by "
                        + String.join(", ", used.getValue()) + " and cannot be removed."));
            }
        }
        return errors;
    }

    // ---- states ----

    @Transactional
    public FormView submit(ClinicUser user, String formKey, RevisionInput input) {
        access.requireSetupEdit(user);
        FormRow row = require(user.tenantId(), formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        requireNotRetired(row);
        Definition draft = requireDraft(row, Set.of("DRAFT", "SENT_BACK"));
        if (!Boolean.TRUE.equals(draft.settings() == null ? null : draft.settings().approvalRequired())) {
            throw formState("This form does not need approval. Publish it instead.");
        }
        requirePublishable(draft);
        requireRevision(row, revision);
        FormState state = new FormState(row.name(), row.status(), row.liveVersionNo(), row.draftVersionNo(),
                "WAITING_APPROVAL", row.draftDefinition(), null, clock.instant(), user.userId());
        apply(row, revision, state, user);
        auditState("EMR_FORM_SUBMITTED", row, "WAITING_APPROVAL");
        return view(user, require(user.tenantId(), formKey));
    }

    @Transactional
    public FormView approve(ClinicUser user, String formKey, RevisionInput input) {
        access.requireSetupEdit(user);
        FormRow row = require(user.tenantId(), formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        requireNotRetired(row);
        Definition draft = requireDraft(row, Set.of("WAITING_APPROVAL"));
        requirePublishable(draft);
        requireRevision(row, revision);
        goLive(user, row, revision, draft, user.userId(), "EMR_FORM_APPROVED");
        return view(user, require(user.tenantId(), formKey));
    }

    @Transactional
    public FormView sendBack(ClinicUser user, String formKey, SendBackInput input) {
        access.requireSetupEdit(user);
        FormRow row = require(user.tenantId(), formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isEmpty() || reason.length() > 300) {
            throw invalid("reason", "Enter a reason of 1 to 300 characters.");
        }
        requireNotRetired(row);
        requireDraft(row, Set.of("WAITING_APPROVAL"));
        requireRevision(row, revision);
        FormState state = new FormState(row.name(), row.status(), row.liveVersionNo(), row.draftVersionNo(),
                "SENT_BACK", row.draftDefinition(), reason, null, null);
        apply(row, revision, state, user);
        auditState("EMR_FORM_SENT_BACK", row, "SENT_BACK");
        return view(user, require(user.tenantId(), formKey));
    }

    @Transactional
    public FormView publish(ClinicUser user, String formKey, RevisionInput input) {
        access.requireSetupEdit(user);
        FormRow row = require(user.tenantId(), formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        requireNotRetired(row);
        Definition draft = requireDraft(row, Set.of("DRAFT", "SENT_BACK"));
        if (Boolean.TRUE.equals(draft.settings() == null ? null : draft.settings().approvalRequired())) {
            throw formState("This form needs approval. Send it for approval first.");
        }
        requirePublishable(draft);
        requireRevision(row, revision);
        goLive(user, row, revision, draft, null, "EMR_FORM_PUBLISHED");
        return view(user, require(user.tenantId(), formKey));
    }

    @Transactional
    public FormView retire(ClinicUser user, String formKey, RevisionInput input) {
        access.requireSetupEdit(user);
        FormRow row = require(user.tenantId(), formKey);
        long revision = revisionOf(input == null ? null : input.revision());
        if ("RETIRED".equals(row.status())) {
            return view(user, row);
        }
        requireRevision(row, revision);
        FormState state = new FormState(row.name(), "RETIRED", row.liveVersionNo(), row.draftVersionNo(),
                row.draftState(), row.draftDefinition(), row.sentBackReason(), row.submittedAt(), row.submittedBy());
        apply(row, revision, state, user);
        auditState("EMR_FORM_RETIRED", row, "RETIRED");
        return view(user, require(user.tenantId(), formKey));
    }

    private void goLive(ClinicUser user, FormRow row, long revision, Definition draft, UUID approvedBy, String action) {
        int versionNo = row.liveVersionNo() + 1;
        forms.insertVersion(user.tenantId(), row.id(), versionNo, write(draft), approvedBy, user.userId());
        FormState state = new FormState(draft.name(), row.status(), versionNo, versionNo + 1, "NONE", null, null, null,
                null);
        apply(row, revision, state, user);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("formKey", row.formKey());
        after.put("versionNo", versionNo);
        after.put("fieldCount", draft.fields().size());
        auditLog.record(action, ENTITY, row.id(), null, after);
    }

    private void apply(FormRow row, long revision, FormState state, ClinicUser user) {
        try {
            if (!forms.update(user.tenantId(), row.id(), revision, state, user.userId())) {
                throw formChanged();
            }
        } catch (DuplicateKeyException e) {
            throw invalid("name", "A form with this name already exists.");
        }
    }

    private void auditState(String action, FormRow row, String state) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("formKey", row.formKey());
        after.put("versionNo", row.draftVersionNo());
        after.put("state", state);
        auditLog.record(action, ENTITY, row.id(), null, after);
    }

    // ---- helpers ----

    private Definition requireDraft(FormRow row, Set<String> states) {
        if (row.draftDefinition() == null || !states.contains(row.draftState())) {
            throw formState("The form is not in a state that allows this.");
        }
        return read(row.draftDefinition());
    }

    private void requirePublishable(Definition draft) {
        List<FieldMessage> errors = EmrFormRules.checkPublishable(draft);
        if (!errors.isEmpty()) {
            throw EmrProblemException.of(400, "form-not-publishable", "The form cannot go live yet",
                    "Fix the highlighted problems first.").withFieldErrors(errors);
        }
    }

    private static void requireNotRetired(FormRow row) {
        if ("RETIRED".equals(row.status())) {
            throw formState("This form is retired.");
        }
    }

    private static void requireRevision(FormRow row, long revision) {
        if (revision != row.revision()) {
            throw formChanged();
        }
    }

    private static EmrProblemException formChanged() {
        return EmrProblemException.of(409, "form-changed", "Form changed",
                "Someone else changed this form. Reload and try again.");
    }

    private static EmrProblemException formState(String detail) {
        return EmrProblemException.of(409, "form-state", "Form state does not allow this", detail);
    }

    private static EmrProblemException starterNotFound() {
        return EmrProblemException.of(404, "form-starter-not-found", "Starter form not found",
                "There is no such starter form.");
    }

    private FormRow require(UUID tenantId, String formKey) {
        if (formKey == null || !formKey.matches("^f_[a-z2-7]{10}$")) {
            throw EmrProblemException.badRequest("Choose a valid form key.");
        }
        return forms.find(tenantId, formKey).orElseThrow(() -> EmrProblemException.of(404, "form-not-found",
                "Form not found", "There is no such form in your clinic."));
    }

    private static long revisionOf(Long revision) {
        if (revision == null || revision < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        return revision;
    }

    private static void requireKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw invalid("Idempotency-Key", "Send a request key of 8 to 64 letters, digits, dashes or underscores.");
        }
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }

    /** Departments doctors can use: the 15 starters and the added departments that are published. */
    private Set<String> doctorDepartmentKeys(UUID tenantId) {
        Set<String> keys = new HashSet<>();
        library.starters().forEach(s -> keys.add(s.starterKey()));
        departments.findAll(tenantId).forEach(d -> {
            if (d.publishedVersion() > 0) {
                keys.add(d.key());
            }
        });
        return keys;
    }

    private Optional<VersionRow> latestVersion(UUID tenantId, FormRow row) {
        return row.liveVersionNo() < 1 ? Optional.empty()
                : forms.version(tenantId, row.id(), row.liveVersionNo());
    }

    /** The working definition: the draft, else the live version (ADR-022 D11). */
    private Definition working(FormRow row, Optional<VersionRow> live) {
        if (row.draftDefinition() != null) {
            return read(row.draftDefinition());
        }
        return read(live.orElseThrow(() -> new IllegalStateException("A form has neither a draft nor a live version"))
                .definition());
    }

    private static String state(FormRow row) {
        if ("RETIRED".equals(row.status())) {
            return "RETIRED";
        }
        return switch (row.draftState()) {
            case "WAITING_APPROVAL" -> "WAITING_APPROVAL";
            case "SENT_BACK" -> "SENT_BACK";
            case "DRAFT" -> "DRAFT";
            default -> "LIVE";
        };
    }

    private Summary summary(FormRow row, Definition definition) {
        int[] counts = EmrFormRules.counts(definition.fields());
        return new Summary(row.formKey(), row.name(), state(row),
                row.draftDefinition() != null ? row.draftVersionNo() : row.liveVersionNo(), row.liveVersionNo(),
                row.starterKey(), definition.settings().useIn(), definition.settings().emrPart(), counts[0], counts[1],
                counts[2], row.updatedAt());
    }

    private FormView view(ClinicUser user, FormRow row) {
        UUID tenantId = user.tenantId();
        Definition definition = working(row, latestVersion(tenantId, row));
        Summary s = summary(row, definition);
        boolean owner = access.canEditSetup(user);
        List<VersionLine> lines = new ArrayList<>();
        if (row.draftDefinition() != null) {
            lines.add(new VersionLine(row.draftVersionNo(), row.draftState(),
                    row.submittedAt() != null ? row.submittedAt() : row.updatedAt(),
                    nameOf(tenantId, row.submittedBy() != null ? row.submittedBy() : row.updatedBy())));
        }
        for (VersionRow version : forms.versions(tenantId, row.id())) {
            lines.add(new VersionLine(version.versionNo(), version.versionNo() == row.liveVersionNo() ? "LIVE"
                    : "SUPERSEDED", version.publishedAt(), nameOf(tenantId, version.publishedBy())));
        }
        return new FormView(s.formKey(), s.name(), s.state(), s.chipVersionNo(), s.liveVersionNo(), s.starterKey(),
                s.useIn(), s.emrPart(), s.fieldCount(), s.logicCount(), s.scoredCount(), s.updatedAt(), owner, owner,
                row.revision(), definition, row.sentBackReason(), lines);
    }

    private String nameOf(UUID tenantId, UUID membershipOrUser) {
        if (membershipOrUser == null) {
            return "Unknown";
        }
        return teamMembers.find(tenantId, membershipOrUser).map(TeamMember::name)
                .or(() -> teamMembers.findActiveByUserId(tenantId, membershipOrUser).map(TeamMember::name))
                .orElse("Unknown");
    }

    private Definition read(String json) {
        try {
            return mapper.readValue(json, Definition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored form could not be read", e);
        }
    }

    private String write(Definition definition) {
        try {
            return mapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A form could not be written", e);
        }
    }

    private String newKey() {
        StringBuilder key = new StringBuilder("f_");
        for (int i = 0; i < 10; i++) {
            key.append(BASE32[random.nextInt(BASE32.length)]);
        }
        return key.toString();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
