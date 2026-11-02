package com.sahaj.platform.infrastructure.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second line of defence for audit values (SAH-1: never store patient names, mobiles or OTPs in
 * clear). The key check is a substring match on a normalised key (lower-cased, punctuation and
 * separators removed), so {@code mobileNumber}, {@code Whatsapp-No} and {@code PATIENT_NAME} are all
 * caught, not only an exact match. Mobile-like fields are cut to their last four digits; one-time
 * codes, PINs, passwords, secrets, tokens, names and notes are replaced outright, since a partial
 * mask of free text or a person's name is not safe. Entity identifiers and ordinary field names
 * (for example {@code id}, {@code entityId}, {@code specialty}, {@code code}) are not touched, so an
 * auditor can still tell what changed.
 *
 * <p>As a last line of defence, any string value - whatever its key - has every run of exactly ten
 * digits inside it masked to its last four digits, in case a mobile number ends up in a field this
 * list does not know about (a free-text note, for instance, which is itself fully redacted above).
 * Maps and lists are walked, including lists of lists, so nothing nested escapes either rule.
 *
 * <p>A bare {@code name} is not on the full-redact list: under CLAUDE.md only patient names, mobile
 * numbers, ABHA numbers and clinical notes are restricted, and a clinic's own business name
 * ({@code name}, {@code clinicName}, {@code tenantName}, {@code businessName}) is not restricted
 * data - redacting it would defeat the point of an audit entry such as {@code TENANT_RENAMED}. Only
 * markers that name a *person* are redacted: {@code patientName}, {@code ownerName}, {@code fullName},
 * {@code contactName}, {@code staffName} and {@code doctorName}.
 *
 * <p>For {@code entityType PATIENT} (SAH-8) and {@code entityType VISIT} (SAH-9) a bare {@code name}
 * is a patient's own name, not a clinic's business name, so it is redacted too:
 * {@link #sanitize(String, Map)} adds {@code name} to the full-redact markers only for those entity
 * types, so a patient record's or a visit's audit snapshot never carries the person's name in
 * clear, only the entity id. {@code patientName} and {@code doctorName} are redacted for every
 * entity type regardless (see {@code FULL_REDACT_MARKERS} below): {@code VisitService} does not put
 * either into a visit's audit snapshot in the first place, only {@code patientId} and
 * {@code doctorMembershipId}, but this is the second line of defence CLAUDE.md asks for.
 */
final class AuditSanitizer {

    static final String REDACTED = "[redacted]";

    /** Values here are never safe even masked: replaced outright. */
    private static final Set<String> FULL_REDACT_MARKERS = Set.of(
            "otp", "pin", "password", "secret", "token", "notes",
            "patientname", "ownername", "fullname", "contactname", "staffname", "doctorname");

    /** Entity types whose bare {@code name} field names a person, not a clinic's own business name. */
    private static final Set<String> ENTITY_TYPES_WHERE_NAME_IS_A_PERSON = Set.of("PATIENT", "VISIT", "APPOINTMENT");

    /** Values here are numbers that keep their last four digits for an auditor to recognise the row. */
    private static final Set<String> MASK_MARKERS = Set.of("mobile", "phone", "whatsapp", "abha");

    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final Pattern TEN_DIGIT_RUN = Pattern.compile("\\d{10}");

    private AuditSanitizer() {
    }

    static Map<String, Object> sanitize(Map<String, Object> values) {
        return sanitize(null, values);
    }

    /**
     * @param entityType the kind of record the snapshot describes, for example {@code PATIENT}; may
     *                   be null, in which case a bare {@code name} is treated as a business name
     */
    static Map<String, Object> sanitize(String entityType, Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Set<String> fullRedactMarkers = FULL_REDACT_MARKERS;
        if (entityType != null && ENTITY_TYPES_WHERE_NAME_IS_A_PERSON.contains(entityType.toUpperCase(Locale.ROOT))) {
            fullRedactMarkers = new java.util.HashSet<>(FULL_REDACT_MARKERS);
            fullRedactMarkers.add("name");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> markers = fullRedactMarkers;
        values.forEach((key, value) -> result.put(key, sanitizeValue(normalizeKey(key), value, markers)));
        return result;
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return NOT_ALPHANUMERIC.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(String normalizedKey, Object value, Set<String> fullRedactMarkers) {
        if (value == null) {
            return null;
        }
        if (containsAny(normalizedKey, fullRedactMarkers)) {
            return REDACTED;
        }
        if (containsAny(normalizedKey, MASK_MARKERS)) {
            return value instanceof String string ? maskMobile(string) : value;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<String, Object>) nested).forEach((key, nestedValue) ->
                    result.put(key, sanitizeValue(normalizeKey(key), nestedValue, fullRedactMarkers)));
            return result;
        }
        if (value instanceof List<?> list) {
            return sanitizeList(list, fullRedactMarkers);
        }
        if (value instanceof String string) {
            return maskEmbeddedDigitRuns(string);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sanitizeList(List<?> list, Set<String> fullRedactMarkers) {
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                ((Map<String, Object>) map).forEach((key, value) ->
                        result.put(key, sanitizeValue(normalizeKey(key), value, fullRedactMarkers)));
                out.add(result);
            } else if (item instanceof List<?> nestedList) {
                out.add(sanitizeList(nestedList, fullRedactMarkers));
            } else if (item instanceof String string) {
                out.add(maskEmbeddedDigitRuns(string));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    private static boolean containsAny(String normalizedKey, Set<String> markers) {
        for (String marker : markers) {
            if (normalizedKey.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static String maskMobile(String mobile) {
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "••••";
        }
        return "••••••" + digits.substring(digits.length() - 4);
    }

    private static String maskEmbeddedDigitRuns(String value) {
        Matcher matcher = TEN_DIGIT_RUN.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        StringBuilder masked = new StringBuilder();
        int last = 0;
        matcher.reset();
        while (matcher.find()) {
            masked.append(value, last, matcher.start());
            String digits = matcher.group();
            masked.append("••••••").append(digits.substring(digits.length() - 4));
            last = matcher.end();
        }
        masked.append(value.substring(last));
        return masked.toString();
    }
}
