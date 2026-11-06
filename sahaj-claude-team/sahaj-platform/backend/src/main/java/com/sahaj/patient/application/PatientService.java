package com.sahaj.patient.application;

import com.sahaj.patient.application.PatientRepository.PatientPage;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-8 Patients: register, search, update, group a family by mobile number and soft-merge a
 * duplicate. Reads are for any clinic role; writes are OWNER and FRONT_DESK only, enforced by
 * {@code @PreAuthorize} on the controller (the role in the access token is trusted for this story,
 * unlike SAH-6 team changes, since a patient write carries no last-owner or plan-limit rule that
 * needs the membership re-read). Nothing is ever hard-deleted: a merge sets {@code merged_into_id}
 * on the record that loses, the row stays, and the merge is audited.
 */
@Service
public class PatientService {

    static final int PAGE_SIZE = 20;
    static final int MAX_AGE = 120;
    static final int DUPLICATE_AGE_WINDOW = 2;

    private static final String ENTITY_TYPE = "PATIENT";
    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Set<String> SEX_VALUES = Set.of("F", "M", "O");
    private static final Set<String> LANGUAGES = Set.of("mr", "hi", "en");
    private static final String DEFAULT_LANGUAGE = "mr";
    /** The length limit of the allergy and the chronic text (cleanOptional, 400). */
    private static final int CLINICAL_TEXT_MAX = 400;
    private static final Set<String> PLACEHOLDERS = Set.of("none known", "not recorded", "unknown", "not known", "?");

    /** AC4: follow-up due/missed and check-up overdue need visit data that does not exist until SAH-9+. */
    private static final Set<String> FILTERS_NEEDING_VISIT_DATA = Set.of("due", "missed", "overdue", "lapsed");
    private static final Set<String> KNOWN_FILTERS =
            Set.of("all", "new", "due", "missed", "chronic", "overdue", "lapsed", "kids", "seniors");

    private final PatientRepository patientRepository;
    private final ConsentRepository consentRepository;
    private final RecordAccessRepository recordAccessRepository;
    private final AuditLog auditLog;

    public PatientService(PatientRepository patientRepository, ConsentRepository consentRepository,
                           RecordAccessRepository recordAccessRepository, AuditLog auditLog) {
        this.patientRepository = patientRepository;
        this.consentRepository = consentRepository;
        this.recordAccessRepository = recordAccessRepository;
        this.auditLog = auditLog;
    }

    /** AC3, AC4: search and filter chips. An unknown or not-yet-supported filter never errors. */
    @Transactional(readOnly = true)
    public PatientPage search(UUID tenantId, String q, String filter, int page) {
        String safeFilter = filter == null || !KNOWN_FILTERS.contains(filter) ? "all" : filter;
        int safePage = Math.max(page, 0);
        if (FILTERS_NEEDING_VISIT_DATA.contains(safeFilter)) {
            return new PatientPage(List.of(), 0);
        }
        String cleanedQuery = cleanQuery(q);
        return patientRepository.search(tenantId, cleanedQuery, safeFilter, PAGE_SIZE, safePage * PAGE_SIZE);
    }

    /** AC1, AC2, AC5, AC9: register a patient; same mobile joins the existing family. Never blocks on a duplicate. */
    @Transactional
    public PatientView create(UUID tenantId, UUID actorUserId, PatientInput input) {
        String name = cleanName(input.name());
        int age = validAge(input.age());
        String sex = validSex(input.sex());
        String mobile = validMobile(input.mobile());
        String language = validLanguage(input.language());
        String area = cleanOptional(input.area(), 160, "area");
        String allergy = cleanOptional(input.allergy(), 400, "allergy");
        String chronic = cleanOptional(input.chronic(), 400, "chronic");
        boolean abhaLinked = Boolean.TRUE.equals(input.abhaLinked());
        List<ConsentInput> consents = ConsentInput.validateAll(input.consents());

        UUID id = UUID.randomUUID();
        patientRepository.insert(id, name, age, sex, mobile, area, language, allergy, chronic, abhaLinked,
                actorUserId);
        for (ConsentInput consent : consents) {
            consentRepository.insert(id, consent.kind(), consent.given(), actorUserId);
        }
        PatientRow created = find0(tenantId, id);
        auditLog.record("PATIENT_REGISTERED", ENTITY_TYPE, id, null, snapshot(created));

        UUID duplicateOfId = findPossibleDuplicate(tenantId, id, mobile, age, name);
        return toView(created, duplicateOfId);
    }

    @Transactional(readOnly = true)
    public PatientView find(UUID tenantId, UUID id) {
        return toView(find0(tenantId, id), null);
    }

    /**
     * SAH-17 AC7: a user opening a patient's file. Writes one {@code record_access} row (ids only)
     * in this same transaction, so it is deliberately not read-only. Only the patient detail
     * endpoint calls this; list, search, family and the lookups other modules use do not.
     */
    @Transactional
    public PatientView open(UUID tenantId, UUID actorUserId, UUID id) {
        PatientRow row = find0(tenantId, id);
        recordAccessRepository.insert(id, actorUserId);
        return toView(row, null);
    }

    /** AC6: the family sharing this mobile number, excluding merged-away records and the patient itself. */
    @Transactional(readOnly = true)
    public List<PatientSummaryView> family(UUID tenantId, UUID id) {
        PatientRow patient = find0(tenantId, id);
        return patientRepository.findFamily(tenantId, patient.mobile(), id).stream()
                .map(PatientService::toSummary).toList();
    }

    /** A partial update: any field left null keeps its saved value. Mobile cannot be changed here. */
    @Transactional
    public PatientView update(UUID tenantId, UUID actorUserId, UUID id, PatientUpdateInput input) {
        PatientRow before = find0(tenantId, id);
        String name = input.name() == null ? before.name() : cleanName(input.name());
        int age = input.age() == null ? before.age() : validAge(input.age());
        String sex = input.sex() == null ? before.sex() : validSex(input.sex());
        String area = input.area() == null ? before.area() : cleanOptional(input.area(), 160, "area");
        String language = input.language() == null ? before.language() : validLanguage(input.language());
        String allergy = input.allergy() == null ? before.allergy() : cleanOptional(input.allergy(), 400, "allergy");
        String chronic = input.chronic() == null ? before.chronic() : cleanOptional(input.chronic(), 400, "chronic");
        boolean abhaLinked = input.abhaLinked() == null ? before.abhaLinked() : input.abhaLinked();

        PatientRow after = new PatientRow(id, before.uhid(), name, age, sex, before.mobile(), area, language,
                allergy, chronic, abhaLinked, before.mergedIntoId(), before.createdAt(), before.version());
        if (after.equals(before)) {
            return toView(before, null);
        }
        patientRepository.update(id, name, age, sex, area, language, allergy, chronic, abhaLinked, actorUserId);
        PatientRow saved = find0(tenantId, id);
        auditLog.record("PATIENT_UPDATED", ENTITY_TYPE, id, snapshot(before), snapshot(saved));
        return toView(saved, null);
    }

    /**
     * AC5: soft merge. {@code keepId} survives; {@code mergeId}'s data moves onto the surviving
     * record (the mockup's "Review and merge" offers this once a possible duplicate is found) and
     * {@code mergeId} itself is left in place with {@code merged_into_id} pointing at {@code keepId},
     * never deleted. Reversible by the owner later (a human task, not an API call, in this story).
     */
    @Transactional
    public PatientView merge(UUID tenantId, UUID actorUserId, UUID keepId, UUID mergeId) {
        if (keepId.equals(mergeId)) {
            throw new InvalidInputException("mergeId", "Choose two different patients to merge");
        }
        PatientRow keep = find0(tenantId, keepId);
        PatientRow merge = find0(tenantId, mergeId);
        if (keep.mergedIntoId() != null) {
            throw new InvalidInputException("keepId", "That patient record was already merged into another one");
        }
        if (merge.mergedIntoId() != null) {
            throw new InvalidInputException("mergeId", "That patient record was already merged");
        }

        Map<String, Object> keepBefore = snapshot(keep);
        String allergy = combineClinicalText(keep.allergy(), merge.allergy());
        String chronic = combineClinicalText(keep.chronic(), merge.chronic());
        patientRepository.update(keepId, merge.name(), merge.age(), merge.sex(), merge.area(), merge.language(),
                allergy, chronic, merge.abhaLinked(), actorUserId);
        PatientRow keepAfter = find0(tenantId, keepId);
        auditLog.record("PATIENT_MERGED", ENTITY_TYPE, keepId, keepBefore, snapshot(keepAfter));

        Map<String, Object> mergeBefore = snapshot(merge);
        patientRepository.setMergedInto(mergeId, keepId, actorUserId);
        PatientRow mergeAfter = find0(tenantId, mergeId);
        auditLog.record("PATIENT_MERGED", ENTITY_TYPE, mergeId, mergeBefore, snapshot(mergeAfter));

        return toView(keepAfter, null);
    }

    /**
     * Security review S4: the allergy or chronic text of a merge. A text is never lost: the kept record's text stays
     * when the other one is empty, the other one is taken when the kept one is empty, and two different texts are
     * combined (kept record first, joined with "; ", each part once whatever its case) within the 400-character limit
     * of the field. A placeholder ("None known", "Not recorded", "Unknown", "Not known", "?") is dropped when the
     * other text says something real, so a combined text never reads "None known; Peanuts". When both are only
     * placeholders the kept record's wording stays.
     */
    static String combineClinicalText(String keepText, String mergeText) {
        String kept = keepText == null || keepText.isBlank() ? null : keepText.trim();
        String merged = mergeText == null || mergeText.isBlank() ? null : mergeText.trim();
        if (merged == null) {
            return kept;
        }
        if (kept == null) {
            return merged;
        }
        if (kept.equalsIgnoreCase(merged)) {
            return kept;
        }
        Map<String, String> real = new LinkedHashMap<>();
        for (String part : (kept + ";" + merged).split(";")) {
            String trimmed = part.trim();
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !PLACEHOLDERS.contains(key)) {
                real.putIfAbsent(key, trimmed);
            }
        }
        if (real.isEmpty()) {
            return kept;
        }
        String combined = String.join("; ", real.values());
        return combined.length() > CLINICAL_TEXT_MAX ? combined.substring(0, CLINICAL_TEXT_MAX).trim() : combined;
    }

    private PatientRow find0(UUID tenantId, UUID id) {
        return patientRepository.findById(tenantId, id).orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    /** AC5: same mobile, age within two years, a similar name - flagged, never blocking the write. */
    private UUID findPossibleDuplicate(UUID tenantId, UUID newId, String mobile, int age, String name) {
        return patientRepository.findFamily(tenantId, mobile, newId).stream()
                .filter(candidate -> Math.abs(candidate.age() - age) <= DUPLICATE_AGE_WINDOW)
                .filter(candidate -> namesAreSimilar(candidate.name(), name))
                .map(PatientRow::id)
                .findFirst()
                .orElse(null);
    }

    private static boolean namesAreSimilar(String a, String b) {
        String na = normalizeName(a);
        String nb = normalizeName(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.contains(nb) || nb.contains(na);
    }

    private static String normalizeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String cleanQuery(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new InvalidInputException("name", "Enter the patient's name");
        }
        return trimmed;
    }

    private static int validAge(Integer age) {
        if (age == null || age < 0 || age > MAX_AGE) {
            throw new InvalidInputException("age", "Enter an age between 0 and 120");
        }
        return age;
    }

    private static String validSex(String sex) {
        if (sex == null || !SEX_VALUES.contains(sex)) {
            throw new InvalidInputException("sex", "Choose female, male or other");
        }
        return sex;
    }

    private static String validMobile(String mobile) {
        if (mobile == null || !MOBILE.matcher(mobile).matches()) {
            throw new InvalidInputException("mobile", "Enter a 10-digit mobile number");
        }
        return mobile;
    }

    private static String validLanguage(String language) {
        if (language == null) {
            return DEFAULT_LANGUAGE;
        }
        if (!LANGUAGES.contains(language)) {
            throw new InvalidInputException("language", "Choose Marathi, Hindi or English");
        }
        return language;
    }

    private static String cleanOptional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new InvalidInputException(field, "That is too long");
        }
        return trimmed;
    }

    private static PatientView toView(PatientRow row, UUID possibleDuplicateOfId) {
        return new PatientView(row.id(), row.uhid(), row.name(), row.age(), row.sex(), row.mobile(), row.area(),
                row.language(), row.allergy(), row.chronic(), row.abhaLinked(), row.createdAt(),
                possibleDuplicateOfId);
    }

    private static PatientSummaryView toSummary(PatientRow row) {
        return new PatientSummaryView(row.id(), row.uhid(), row.name(), row.age(), row.sex(), row.mobile(),
                row.chronic(), null);
    }

    private static Map<String, Object> snapshot(PatientRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", row.name());
        values.put("age", row.age());
        values.put("sex", row.sex());
        values.put("mobile", row.mobile());
        values.put("area", row.area());
        values.put("language", row.language());
        // Security review S2: the audit trail says only whether the texts exist, never what they say.
        values.put("allergyRecorded", row.allergy() != null && !row.allergy().isBlank());
        values.put("chronicRecorded", row.chronic() != null && !row.chronic().isBlank());
        values.put("abhaLinked", row.abhaLinked());
        values.put("mergedIntoId", row.mergedIntoId());
        return values;
    }
}
