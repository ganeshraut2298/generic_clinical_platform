package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.application.PatientRepository.PatientPage;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;

    @Autowired
    public PatientService(PatientRepository patientRepository, ConsentRepository consentRepository,
                           RecordAccessRepository recordAccessRepository, AuditLog auditLog, Clock clock) {
        this.patientRepository = patientRepository;
        this.consentRepository = consentRepository;
        this.recordAccessRepository = recordAccessRepository;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** The system clock (the day is worked out in India by {@link PatientAge#today}). */
    public PatientService(PatientRepository patientRepository, ConsentRepository consentRepository,
                           RecordAccessRepository recordAccessRepository, AuditLog auditLog) {
        this(patientRepository, consentRepository, recordAccessRepository, auditLog, Clock.systemUTC());
    }

    /**
     * AC3, AC4: search and filter chips. An unknown or not-yet-supported filter never errors. SAH-73-3: each row
     * carries the age worked out as of today in India (never the date of birth); "Children" is under 13.
     */
    @Transactional(readOnly = true)
    public PatientSummaryPage search(UUID tenantId, String q, String filter, int page) {
        String safeFilter = filter == null || !KNOWN_FILTERS.contains(filter) ? "all" : filter;
        int safePage = Math.max(page, 0);
        if (FILTERS_NEEDING_VISIT_DATA.contains(safeFilter)) {
            return new PatientSummaryPage(List.of(), 0);
        }
        String cleanedQuery = cleanQuery(q);
        LocalDate today = today();
        PatientPage found = patientRepository.search(tenantId, cleanedQuery, safeFilter, PAGE_SIZE,
                safePage * PAGE_SIZE, today);
        return new PatientSummaryPage(found.items().stream().map(row -> toSummary(row, today)).toList(),
                found.total());
    }

    /**
     * AC1, AC2, AC5, AC9: register a patient; same mobile joins the existing family. Never blocks on a duplicate.
     * SAH-73-1: the date of birth is optional and the age is required only without it; the stored age is the
     * completed years from the date as of today (ADR-024 D4, D5).
     */
    @Transactional
    public PatientView create(UUID tenantId, UUID actorUserId, PatientInput input) {
        String name = cleanName(input.name());
        LocalDate today = today();
        DateOfBirthInput sentDate = input.dateOfBirth() == null ? DateOfBirthInput.ABSENT : input.dateOfBirth();
        LocalDate dateOfBirth = sentDate.state() == DateOfBirthInput.State.NULL
                || sentDate.state() == DateOfBirthInput.State.ABSENT ? null : validDateOfBirth(sentDate, today);
        int age = ageFor(dateOfBirth, input.age(), today);
        String sex = validSex(input.sex());
        String mobile = validMobile(input.mobile());
        String language = validLanguage(input.language());
        String area = cleanOptional(input.area(), 160, "area");
        String allergy = cleanOptional(input.allergy(), 400, "allergy");
        String chronic = cleanOptional(input.chronic(), 400, "chronic");
        boolean abhaLinked = Boolean.TRUE.equals(input.abhaLinked());
        List<ConsentInput> consents = ConsentInput.validateAll(input.consents());

        UUID id = UUID.randomUUID();
        patientRepository.insert(id, name, age, dateOfBirth, sex, mobile, area, language, allergy, chronic,
                abhaLinked, actorUserId);
        for (ConsentInput consent : consents) {
            consentRepository.insert(id, consent.kind(), consent.given(), actorUserId);
        }
        PatientRow created = find0(tenantId, id);
        auditLog.record("PATIENT_REGISTERED", ENTITY_TYPE, id, null, snapshot(created, registeredFields(created)));

        UUID duplicateOfId = findPossibleDuplicate(tenantId, id, mobile, PatientAge.of(dateOfBirth, age, today).years(),
                name, today);
        return toView(created, duplicateOfId, today);
    }

    @Transactional(readOnly = true)
    public PatientView find(UUID tenantId, UUID id) {
        return toView(find0(tenantId, id), null, today());
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
        return toView(row, null, today());
    }

    /** AC6: the family sharing this mobile number, excluding merged-away records and the patient itself. */
    @Transactional(readOnly = true)
    public List<PatientSummaryView> family(UUID tenantId, UUID id) {
        PatientRow patient = find0(tenantId, id);
        LocalDate today = today();
        return patientRepository.findFamily(tenantId, patient.mobile(), id).stream()
                .map(row -> toSummary(row, today)).toList();
    }

    /**
     * A partial update: any field left null keeps its saved value. Mobile cannot be changed here. SAH-73-2: the date
     * of birth has three states (absent = unchanged, a date = set or correct, explicit null = clear); the stored age
     * is refreshed from the date on every write (ADR-024 D4) and a set, change or clear writes one
     * PATIENT_DATE_OF_BIRTH_CHANGED event that holds no date.
     */
    @Transactional
    public PatientView update(UUID tenantId, UUID actorUserId, UUID id, PatientUpdateInput input) {
        PatientRow before = find0(tenantId, id);
        LocalDate today = today();
        String name = input.name() == null ? before.name() : cleanName(input.name());
        DateOfBirthInput sentDate = input.dateOfBirth() == null ? DateOfBirthInput.ABSENT : input.dateOfBirth();
        LocalDate dateOfBirth = switch (sentDate.state()) {
            case ABSENT -> before.dateOfBirth();
            case NULL -> null;
            default -> validDateOfBirth(sentDate, today);
        };
        int age;
        if (dateOfBirth != null) {
            age = ageFor(dateOfBirth, input.age(), today);
        } else if (before.dateOfBirth() != null) {
            // Clearing (BR73-9): the age sent, else the completed years worked out from the old date today.
            age = input.age() == null ? PatientAge.completedYears(before.dateOfBirth(), today) : validAge(input.age());
        } else {
            age = input.age() == null ? before.age() : validAge(input.age());
        }
        String sex = input.sex() == null ? before.sex() : validSex(input.sex());
        String area = input.area() == null ? before.area() : cleanOptional(input.area(), 160, "area");
        String language = input.language() == null ? before.language() : validLanguage(input.language());
        String allergy = input.allergy() == null ? before.allergy() : cleanOptional(input.allergy(), 400, "allergy");
        String chronic = input.chronic() == null ? before.chronic() : cleanOptional(input.chronic(), 400, "chronic");
        boolean abhaLinked = input.abhaLinked() == null ? before.abhaLinked() : input.abhaLinked();

        PatientRow after = new PatientRow(id, before.uhid(), name, age, sex, before.mobile(), area, language,
                allergy, chronic, abhaLinked, before.mergedIntoId(), before.createdAt(), before.version(),
                dateOfBirth);
        List<String> changed = changedFields(before, after);
        String dateChange = dateChange(before.dateOfBirth(), dateOfBirth);
        if (changed.isEmpty() && dateChange == null) {
            return toView(before, null, today);
        }
        patientRepository.update(id, name, age, dateOfBirth, sex, area, language, allergy, chronic, abhaLinked,
                actorUserId);
        PatientRow saved = find0(tenantId, id);
        if (!changed.isEmpty()) {
            auditLog.record("PATIENT_UPDATED", ENTITY_TYPE, id, snapshot(before, null), snapshot(saved, changed));
        }
        recordDateOfBirthChange(id, before.dateOfBirth(), dateChange);
        return toView(saved, null, today);
    }

    /**
     * AC5: soft merge. {@code keepId} survives; {@code mergeId}'s data moves onto the surviving
     * record (the mockup's "Review and merge" offers this once a possible duplicate is found) and
     * {@code mergeId} itself is left in place with {@code merged_into_id} pointing at {@code keepId},
     * never deleted. Reversible by the owner later (a human task, not an API call, in this story).
     */
    @Transactional
    public PatientView merge(UUID tenantId, UUID actorUserId, UUID keepId, UUID mergeId) {
        return mergeWithNotice(tenantId, actorUserId, keepId, mergeId).patient();
    }

    /**
     * SAH-73-2 (BR73-11, SO-73-8): the merge of {@link #merge}, and the date of birth rule. The kept record's date
     * stays; when it has none and the merged-away record has one, the date is copied; when both have different dates
     * the kept date stays and the answer says so. When the kept record ends with a date its stored age is refreshed
     * from that date, not taken from the merged record (ADR-024 D4, D5).
     */
    @Transactional
    public MergeOutcome mergeWithNotice(UUID tenantId, UUID actorUserId, UUID keepId, UUID mergeId) {
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

        LocalDate today = today();
        LocalDate dateOfBirth = keep.dateOfBirth() != null ? keep.dateOfBirth() : merge.dateOfBirth();
        boolean differed = keep.dateOfBirth() != null && merge.dateOfBirth() != null
                && !keep.dateOfBirth().equals(merge.dateOfBirth());
        int age = dateOfBirth != null ? PatientAge.completedYears(dateOfBirth, today) : merge.age();

        Map<String, Object> keepBefore = snapshot(keep, null);
        String allergy = combineClinicalText(keep.allergy(), merge.allergy());
        String chronic = combineClinicalText(keep.chronic(), merge.chronic());
        patientRepository.update(keepId, merge.name(), age, dateOfBirth, merge.sex(), merge.area(), merge.language(),
                allergy, chronic, merge.abhaLinked(), actorUserId);
        PatientRow keepAfter = find0(tenantId, keepId);
        auditLog.record("PATIENT_MERGED", ENTITY_TYPE, keepId, keepBefore,
                snapshot(keepAfter, changedFields(keep, keepAfter)));
        recordDateOfBirthChange(keepId, keep.dateOfBirth(), dateChange(keep.dateOfBirth(), keepAfter.dateOfBirth()));

        Map<String, Object> mergeBefore = snapshot(merge, null);
        patientRepository.setMergedInto(mergeId, keepId, actorUserId);
        PatientRow mergeAfter = find0(tenantId, mergeId);
        auditLog.record("PATIENT_MERGED", ENTITY_TYPE, mergeId, mergeBefore,
                snapshot(mergeAfter, List.of("mergedIntoId")));

        return new MergeOutcome(toView(keepAfter, null, today), differed);
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
    private UUID findPossibleDuplicate(UUID tenantId, UUID newId, String mobile, int age, String name,
                                       LocalDate today) {
        // SAH-73-3 (BR73-10): the effective years of both sides, never the dates of birth.
        return patientRepository.findFamily(tenantId, mobile, newId).stream()
                .filter(candidate -> Math.abs(PatientAge.of(candidate.dateOfBirth(), candidate.age(), today).years()
                        - age) <= DUPLICATE_AGE_WINDOW)
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

    private static PatientView toView(PatientRow row, UUID possibleDuplicateOfId, LocalDate today) {
        PatientAge ageDetail = PatientAge.of(row.dateOfBirth(), row.age(), today);
        return new PatientView(row.id(), row.uhid(), row.name(), ageDetail.years(), row.sex(), row.mobile(),
                row.area(), row.language(), row.allergy(), row.chronic(), row.abhaLinked(), row.createdAt(),
                possibleDuplicateOfId, row.dateOfBirth(), ageDetail);
    }

    /** A list or family row: the worked-out age, never the date of birth (BR73-12). */
    private static PatientSummaryView toSummary(PatientRow row, LocalDate today) {
        PatientAge ageDetail = PatientAge.of(row.dateOfBirth(), row.age(), today);
        return new PatientSummaryView(row.id(), row.uhid(), row.name(), ageDetail.years(), row.sex(), row.mobile(),
                row.chronic(), null, ageDetail);
    }

    /**
     * ADR-024 D6: an audit snapshot holds no name, no mobile number, no date of birth and no age of a dated patient.
     * It carries the field names that changed ({@code changedFields}, after a change only) and the non-identifying values.
     */
    private static Map<String, Object> snapshot(PatientRow row, List<String> changedFields) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (changedFields != null) {
            values.put("changedFields", changedFields);
        }
        values.put("sex", row.sex());
        values.put("language", row.language());
        if (row.dateOfBirth() == null) {
            values.put("age", row.age());
        }
        values.put("dateOfBirthRecorded", row.dateOfBirth() != null);
        // Security review S2: the audit trail says only whether the texts exist, never what they say.
        values.put("allergyRecorded", row.allergy() != null && !row.allergy().isBlank());
        values.put("chronicRecorded", row.chronic() != null && !row.chronic().isBlank());
        values.put("abhaLinked", row.abhaLinked());
        values.put("mergedIntoId", row.mergedIntoId());
        return values;
    }

    /** The field names a registration filled in (names only). */
    private static List<String> registeredFields(PatientRow row) {
        List<String> fields = new ArrayList<>(List.of("name", "sex", "mobile", "language"));
        fields.add(row.dateOfBirth() == null ? "age" : "dateOfBirth");
        if (row.area() != null) {
            fields.add("area");
        }
        if (row.allergy() != null) {
            fields.add("allergy");
        }
        if (row.chronic() != null) {
            fields.add("chronic");
        }
        if (row.abhaLinked()) {
            fields.add("abhaLinked");
        }
        return fields;
    }

    /**
     * The names of the fields that differ, for the audit trail. The date of birth is left out (it has its own event) and
     * so is the stored age whenever a date is or was involved (then it is only a snapshot of the date).
     */
    private static List<String> changedFields(PatientRow before, PatientRow after) {
        List<String> changed = new ArrayList<>();
        addIfDifferent(changed, "name", before.name(), after.name());
        if (before.dateOfBirth() == null && after.dateOfBirth() == null) {
            addIfDifferent(changed, "age", before.age(), after.age());
        }
        addIfDifferent(changed, "sex", before.sex(), after.sex());
        addIfDifferent(changed, "area", before.area(), after.area());
        addIfDifferent(changed, "language", before.language(), after.language());
        addIfDifferent(changed, "allergy", before.allergy(), after.allergy());
        addIfDifferent(changed, "chronic", before.chronic(), after.chronic());
        addIfDifferent(changed, "abhaLinked", before.abhaLinked(), after.abhaLinked());
        return changed;
    }

    private static void addIfDifferent(List<String> changed, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    /** SET, CHANGED or CLEARED; null when the date did not change. */
    private static String dateChange(LocalDate before, LocalDate after) {
        if (java.util.Objects.equals(before, after)) {
            return null;
        }
        if (before == null) {
            return "SET";
        }
        return after == null ? "CLEARED" : "CHANGED";
    }

    /**
     * ADR-024 D6: one event per set, change or clear. The values hold the field name and whether a date is recorded,
     * and the kind of change; never the date and never an age derived from it.
     */
    private void recordDateOfBirthChange(UUID patientId, LocalDate before, String change) {
        if (change == null) {
            return;
        }
        Map<String, Object> beforeValues = new LinkedHashMap<>();
        beforeValues.put("field", "dateOfBirth");
        beforeValues.put("dateOfBirthRecorded", before != null);
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("field", "dateOfBirth");
        afterValues.put("dateOfBirthRecorded", !"CLEARED".equals(change));
        afterValues.put("change", change);
        auditLog.record("PATIENT_DATE_OF_BIRTH_CHANGED", ENTITY_TYPE, patientId, beforeValues, afterValues);
    }

    private LocalDate today() {
        return PatientAge.today(clock);
    }

    /**
     * SO-73-7: a date that could not be read, a date later than today (India; today is accepted) and a date with more
     * than 120 completed years are refused on {@code dateOfBirth}. The message never repeats the value sent.
     */
    private static LocalDate validDateOfBirth(DateOfBirthInput input, LocalDate today) {
        LocalDate date = input.date();
        if (input.state() != DateOfBirthInput.State.DATE || date == null) {
            throw new InvalidInputException("dateOfBirth", "Enter a valid date of birth.");
        }
        if (PatientAge.isFuture(date, today)) {
            throw new InvalidInputException("dateOfBirth", "Date of birth cannot be in the future.");
        }
        if (PatientAge.exceedsMaxYears(date, today)) {
            throw new InvalidInputException("dateOfBirth", "Date of birth must be within the last 120 years.");
        }
        return date;
    }

    /**
     * The stored age (ADR-024 D4, D5): with a date, the completed years as of today, and an age sent with it must be
     * the same number; without a date, the age sent (0 to 120).
     */
    private static int ageFor(LocalDate dateOfBirth, Integer sentAge, LocalDate today) {
        if (dateOfBirth == null) {
            return validAge(sentAge);
        }
        int years = PatientAge.completedYears(dateOfBirth, today);
        if (sentAge != null && sentAge != years) {
            throw new InvalidInputException("age", "Age does not match the date of birth.");
        }
        return years;
    }
}
