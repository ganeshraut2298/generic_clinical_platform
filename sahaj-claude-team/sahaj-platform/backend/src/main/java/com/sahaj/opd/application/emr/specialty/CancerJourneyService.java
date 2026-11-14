package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.CancerJourneyRepository.JourneyRow;
import com.sahaj.opd.domain.emr.specialty.JourneyRules;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The patient's Cancer journey (SAH-75-7; ADR-026 D7; docs/api/opd.yaml 0.7.0 PUT /emr/records/{recordId}/cancer-journey).
 * It belongs to the patient, not to the visit: each save appends version {@code expectedVersion + 1} and keeps the earlier
 * ones. A field sent as null clears it in the new version, a field left out keeps the previous version's value. The
 * record's access applies (own patient, {@code clinic.consultation.write}); the record must be an OPD visit of the
 * oncology profile and still a draft. A stale {@code expectedVersion}, and a racing save that loses on the unique key, are
 * 409 journey-changed. The audit holds the version and the names of the changed fields, never a value. Clinical data:
 * this class never logs.
 */
@Service
public class CancerJourneyService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final Set<String> FIELDS = Set.of("expectedVersion", "stageGroup", "tnmText", "markersText",
            "ki67Pct", "geneticsText", "intent", "setting", "lineNo", "boardDate");
    /** Accepted and ignored: the clinic is always the signed-in user's. */
    private static final Set<String> IGNORED = Set.of("tenantId", "clinicId");

    private final EmrRecordService records;
    private final CancerJourneyRepository journeys;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final Clock clock;

    public CancerJourneyService(EmrRecordService records, CancerJourneyRepository journeys, TeamMemberPort team,
                                AuditLog auditLog, Clock clock) {
        this.records = records;
        this.journeys = journeys;
        this.team = team;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** One version as the contract's CancerJourney: the editor's name comes from the team directory, never copied. */
    public record JourneyView(int version, String stageGroup, String tnmText, String markersText, Integer ki67Pct,
                              String geneticsText, String intent, String setting, Integer lineNo, LocalDate boardDate,
                              Instant recordedAt, String recordedByName) {

        /** Clinical data: the version only. */
        @Override
        public String toString() {
            return "JourneyView[version=" + version + "]";
        }
    }

    @Transactional
    public JourneyView save(ClinicUser user, UUID recordId, JsonNode body) {
        UUID tenantId = user.tenantId();
        Authorized authorized = records.authorize(user, recordId);
        RecordRow row = authorized.row();
        if (SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey()).filter("onco"::equals).isEmpty()
                || authorized.patientId() == null) {
            throw EmrProblemException.notApplicable();
        }
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        Parsed parsed = parse(body, today);
        JourneyRow latest = journeys.latest(tenantId, authorized.patientId()).orElse(null);
        int current = latest == null ? 0 : latest.version();
        if (parsed.expectedVersion != current) {
            throw journeyChanged();
        }
        TeamMember member = authorized.member();
        List<String> changed = new ArrayList<>();
        JourneyRow next = merge(latest, parsed, authorized.patientId(), current + 1, recordId, member.membershipId(),
                user.userId(), changed);
        try {
            journeys.insert(tenantId, next);
        } catch (DuplicateKeyException e) {
            throw journeyChanged();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("version", current);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", next.version());
        after.put("changedFields", changed);
        auditLog.record("ONC_JOURNEY_UPDATED", "ONC_JOURNEY", authorized.patientId(), before, after);
        JourneyRow stored = journeys.latest(tenantId, authorized.patientId()).orElse(next);
        return view(tenantId, stored, clock.instant());
    }

    /** The view of a stored version (the strip and the save answer); {@code fallbackAt} when the row has no time yet. */
    JourneyView view(UUID tenantId, JourneyRow row, Instant fallbackAt) {
        String name = row.recordedByMembershipId() == null ? null
                : team.find(tenantId, row.recordedByMembershipId()).map(TeamMember::name).orElse(null);
        return new JourneyView(row.version(), row.stageGroup(), row.tnmText(), row.markersText(), row.ki67Pct(),
                row.geneticsText(), row.intent(), row.setting(), row.lineNo(), row.boardDate(),
                row.recordedAt() == null ? fallbackAt : row.recordedAt(), name == null ? "Someone" : name);
    }

    // ---- the body ----

    /** A field is {@code present} when the body names it (null included); an absent field keeps the earlier value. */
    private static final class Parsed {
        long expectedVersion;
        final Map<String, Object> values = new LinkedHashMap<>();
    }

    private Parsed parse(JsonNode body, LocalDate today) {
        List<FieldMessage> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("expectedVersion", "Send the version you read.")));
        }
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!FIELDS.contains(name) && !IGNORED.contains(name)) {
                errors.add(new FieldMessage(name, "This value is not allowed."));
            }
        }
        Parsed parsed = new Parsed();
        JsonNode version = body.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0) {
            errors.add(new FieldMessage("expectedVersion", "Reload the page and try again."));
        } else {
            parsed.expectedVersion = version.asLong();
        }
        if (body.has("stageGroup")) {
            JsonNode v = body.get("stageGroup");
            if (v.isNull()) {
                parsed.values.put("stageGroup", null);
            } else if (v.isTextual() && JourneyRules.STAGE_GROUPS.contains(v.asText())) {
                parsed.values.put("stageGroup", v.asText());
            } else {
                errors.add(new FieldMessage("stageGroup", "Choose a stage group from the list."));
            }
        }
        text(body, "tnmText", JourneyRules.TNM_MAX, parsed, errors);
        text(body, "markersText", JourneyRules.MARKERS_MAX, parsed, errors);
        text(body, "geneticsText", JourneyRules.GENETICS_MAX, parsed, errors);
        whole(body, "ki67Pct", 0, JourneyRules.KI67_MAX, parsed, errors);
        whole(body, "lineNo", 1, JourneyRules.LINE_MAX, parsed, errors);
        choice(body, "intent", JourneyRules.INTENTS, parsed, errors);
        choice(body, "setting", JourneyRules.SETTINGS, parsed, errors);
        if (body.has("boardDate")) {
            JsonNode v = body.get("boardDate");
            if (v.isNull()) {
                parsed.values.put("boardDate", null);
            } else {
                try {
                    LocalDate date = v.isTextual() ? LocalDate.parse(v.asText()) : null;
                    if (date == null || !JourneyRules.boardDateOk(date, today)) {
                        errors.add(new FieldMessage("boardDate", "Choose a date that is not more than a year ahead."));
                    } else {
                        parsed.values.put("boardDate", date);
                    }
                } catch (DateTimeParseException e) {
                    errors.add(new FieldMessage("boardDate", "Enter a date."));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return parsed;
    }

    private static void text(JsonNode body, String name, int max, Parsed parsed, List<FieldMessage> errors) {
        if (!body.has(name)) {
            return;
        }
        JsonNode v = body.get(name);
        if (v.isNull()) {
            parsed.values.put(name, null);
            return;
        }
        if (!v.isTextual()) {
            errors.add(new FieldMessage(name, "Enter text."));
            return;
        }
        String text = v.asText().strip();
        if (text.length() > max) {
            errors.add(new FieldMessage(name, "Enter up to " + max + " characters."));
        } else if (HTML.matcher(text).find()) {
            errors.add(new FieldMessage(name, "Remove the markup."));
        } else {
            parsed.values.put(name, text.isEmpty() ? null : text);
        }
    }

    private static void whole(JsonNode body, String name, int min, int max, Parsed parsed, List<FieldMessage> errors) {
        if (!body.has(name)) {
            return;
        }
        JsonNode v = body.get(name);
        if (v.isNull()) {
            parsed.values.put(name, null);
        } else if (v.isIntegralNumber() && v.canConvertToInt() && v.asInt() >= min && v.asInt() <= max) {
            parsed.values.put(name, v.asInt());
        } else {
            errors.add(new FieldMessage(name, "Enter " + min + " to " + max + "."));
        }
    }

    private static void choice(JsonNode body, String name, List<String> allowed, Parsed parsed,
                               List<FieldMessage> errors) {
        if (!body.has(name)) {
            return;
        }
        JsonNode v = body.get(name);
        if (v.isNull()) {
            parsed.values.put(name, null);
        } else if (v.isTextual() && allowed.contains(v.asText())) {
            parsed.values.put(name, v.asText());
        } else {
            errors.add(new FieldMessage(name, "Choose a valid option."));
        }
    }

    /** The new version: sent values over the earlier ones; {@code changed} gets the names that differ from the earlier. */
    private static JourneyRow merge(JourneyRow before, Parsed parsed, UUID patientId, int version, UUID recordId,
                                    UUID membershipId, UUID userId, List<String> changed) {
        String stage = pick(parsed, "stageGroup", before == null ? null : before.stageGroup(), changed);
        String tnm = pick(parsed, "tnmText", before == null ? null : before.tnmText(), changed);
        String markers = pick(parsed, "markersText", before == null ? null : before.markersText(), changed);
        Integer ki67 = pick(parsed, "ki67Pct", before == null ? null : before.ki67Pct(), changed);
        String genetics = pick(parsed, "geneticsText", before == null ? null : before.geneticsText(), changed);
        String intent = pick(parsed, "intent", before == null ? null : before.intent(), changed);
        String setting = pick(parsed, "setting", before == null ? null : before.setting(), changed);
        Integer line = pick(parsed, "lineNo", before == null ? null : before.lineNo(), changed);
        LocalDate board = pick(parsed, "boardDate", before == null ? null : before.boardDate(), changed);
        return new JourneyRow(null, patientId, version, stage, tnm, markers, ki67, genetics, intent, setting, line,
                board, recordId, membershipId, userId, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T pick(Parsed parsed, String name, T earlier, List<String> changed) {
        T value = parsed.values.containsKey(name) ? (T) parsed.values.get(name) : earlier;
        if (!Objects.equals(value, earlier)) {
            changed.add(name);
        }
        return value;
    }

    private static EmrProblemException journeyChanged() {
        return EmrProblemException.of(409, "journey-changed", "Journey changed",
                "The journey was changed. Reload.");
    }
}
