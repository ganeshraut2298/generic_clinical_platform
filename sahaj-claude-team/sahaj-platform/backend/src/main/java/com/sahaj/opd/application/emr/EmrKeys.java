package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Key formats and the 15 starter department keys of the Doctor EMR (ADR-020 D4, D5). The keys are the ids of the EMR
 * mockup's {@code DEPTS} list.
 */
public final class EmrKeys {

    /** The 15 starter departments, in the mockup order (General Medicine first). */
    public static final List<String> STARTER_KEYS = List.of("gm", "dental", "og", "paeds", "physio", "ortho", "eye",
            "ent", "derm", "diab", "cardio", "ayush", "onco", "surgery", "psych");

    public static final String DEFAULT_STARTER_KEY = "gm";

    /**
     * ADR-020 D13: the hint on {@code opd.diagnosis.specialty} (SAH-7, eight platform codes) for a starter department.
     * Other starters give no hint ordering.
     */
    private static final Map<String, String> DIAGNOSIS_SPECIALTY = Map.of("gm", "gp", "og", "gynae", "derm", "derma",
            "ayush", "ayur", "dental", "dental", "paeds", "paeds", "physio", "physio");

    /** ADR-020 D7: the clinic profile's specialty code (four values, not widened) to the default starter. */
    private static final Map<String, String> CLINIC_DEFAULT = Map.of("gp", "gm", "dental", "dental", "paeds", "paeds");

    private static final Pattern DEPARTMENT_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final Pattern SECTION_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    private EmrKeys() {
    }

    public static boolean isWellFormedDepartmentKey(String key) {
        return key != null && DEPARTMENT_KEY.matcher(key).matches();
    }

    public static boolean isWellFormedSectionKey(String key) {
        return key != null && SECTION_KEY.matcher(key).matches();
    }

    public static boolean isStarterKey(String key) {
        return key != null && STARTER_KEYS.contains(key);
    }

    /** The platform specialty code used to order diagnoses for a starter key, or null for no hint. */
    public static String diagnosisSpecialty(String starterKey) {
        return starterKey == null ? null : DIAGNOSIS_SPECIALTY.get(starterKey);
    }

    /** gp to gm, dental to dental, paeds to paeds; homeo, empty or anything else to gm (ADR-020 D7, SO-45-40). */
    public static String clinicDefaultStarter(String clinicSpecialtyCode) {
        return CLINIC_DEFAULT.getOrDefault(clinicSpecialtyCode == null ? "" : clinicSpecialtyCode,
                DEFAULT_STARTER_KEY);
    }
}
