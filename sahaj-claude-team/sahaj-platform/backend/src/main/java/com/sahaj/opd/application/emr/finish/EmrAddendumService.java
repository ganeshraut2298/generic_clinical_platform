package com.sahaj.opd.application.emr.finish;

import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.AddendumRow;
import com.sahaj.opd.application.emr.finish.FinishModels.Addendum;
import com.sahaj.opd.application.emr.finish.FinishModels.AddendumInput;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-51-5 (ADR-025 D3): corrections to a signed record are addenda: append-only, stamped with the doctor and the database
 * time, never changing the record, the workflow step, bill lines, follow-up or orders. The reason (1 to 200) and the text (1
 * to 4,000) are plain text, trimmed, without control characters (a line break is allowed in the text). At most 50 per record.
 * The text is never logged or audited: the audit entry holds the record and addendum ids.
 */
@Service
public class EmrAddendumService {

    static final int MAX_ADDENDA = 50;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern CONTROL_IN_REASON = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern CONTROL_IN_TEXT = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    private final EmrRecordService records;
    private final EmrFinishRepository finish;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;

    public EmrAddendumService(EmrRecordService records, EmrFinishRepository finish, TeamMemberPort teamMembers,
                              AuditLog auditLog) {
        this.records = records;
        this.finish = finish;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
    }

    /** {@code created} is true for a new addendum (201) and false for the first result of the same key (200). */
    public record Result(Addendum addendum, boolean created) {
    }

    @Transactional
    public Result add(ClinicUser user, UUID recordId, String key, AddendumInput input) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("Idempotency-Key",
                    "Send a key of 8 to 64 letters, digits, dashes or underscores.")));
        }
        String reason = input == null || input.reason() == null ? "" : input.reason().strip();
        String text = input == null || input.text() == null ? "" : input.text().strip();
        List<FieldMessage> errors = new ArrayList<>();
        if (reason.isEmpty()) {
            errors.add(new FieldMessage("reason", "Enter a reason"));
        } else if (reason.length() > 200 || CONTROL_IN_REASON.matcher(reason).find()) {
            errors.add(new FieldMessage("reason", "Enter 1 to 200 characters, without line breaks."));
        }
        if (text.isEmpty()) {
            errors.add(new FieldMessage("text", "Enter the correction or addition"));
        } else if (text.length() > 4000 || CONTROL_IN_TEXT.matcher(text).find()) {
            errors.add(new FieldMessage("text", "Enter 1 to 4,000 characters."));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        UUID tenantId = user.tenantId();
        SignContext ctx = records.signContext(user, recordId, true);
        if (!"SIGNED".equals(ctx.row().status())) {
            throw EmrProblemException.emrRecordNotSigned();
        }
        // DEC-048 H29: an IPD round or an Emergency record takes an addendum from its signer, the admitting doctor or an owner
        UUID signer = finish.state(tenantId, recordId).map(EmrFinishRepository.RecordState::signedByMembershipId)
                .orElse(null);
        if (!EmrRecordService.mayAddAddendum(ctx.member(), ctx.encounter(), signer)) {
            throw EmrProblemException.notOwnPatient();
        }
        String hash = hash(reason, text);
        Optional<AddendumRow> sameKey = finish.addendumByKey(tenantId, key);
        if (sameKey.isPresent()) {
            if (!hash.equals(sameKey.get().requestHash()) || !sameKey.get().recordId().equals(recordId)) {
                throw EmrProblemException.idempotencyConflict();
            }
            return new Result(view(tenantId, sameKey.get()), false);
        }
        if (finish.countAddenda(tenantId, recordId) >= MAX_ADDENDA) {
            throw EmrProblemException.addendumLimit();
        }
        AddendumRow stored = finish.insertAddendum(tenantId, recordId, reason, text, ctx.member().membershipId(),
                user.userId(), key, hash);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("addendumId", stored.id());
        auditLog.record("EMR_ADDENDUM_ADDED", "EMR_RECORD", recordId, null, after);
        return new Result(view(tenantId, stored), true);
    }

    private Addendum view(UUID tenantId, AddendumRow row) {
        String name = teamMembers.find(tenantId, row.createdByMembershipId()).map(TeamMember::name).orElse("Doctor");
        return new Addendum(row.id(), row.reason(), row.text(), row.createdAt(), name);
    }

    private static String hash(String reason, String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((reason + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
