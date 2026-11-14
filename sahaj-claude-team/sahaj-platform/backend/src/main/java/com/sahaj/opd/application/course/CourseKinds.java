package com.sahaj.opd.application.course;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed lists of a treatment course (SAH-58; ADR-022 D12): the five kinds with their unit word and default name,
 * and the "Add" choices of each kind. Values from the EMR mockup #TreatmentCourse ({@code addOpts}), accepted by the
 * human in DEC-039, not clinically reviewed (rule tag B-1).
 */
public final class CourseKinds {

    public static final String ENCOUNTER_OPD = "OPD";
    public static final String ENCOUNTER_DAY_CARE = "DAY_CARE";
    public static final String ENCOUNTER_IPD = "IPD";
    /** The note of a day-care or IPD sitting made by "Add" (tagged B-1). */
    public static final String ADMISSION_REQUEST_NOTE = "Admission request. Rate set at admission.";
    /** The one-line note under the course name (SAH-58-1). */
    public static final String ACCOUNT_NOTE = "One running account: advances and part payments carry across visits.";

    public record Kind(String kind, String unit, String defaultName) {
    }

    /** {@code key} is the lower-case API key; the table stores it upper case. */
    public record AddOption(String key, String label, String encounterKind) {

        public String storedKey() {
            return key.toUpperCase(java.util.Locale.ROOT);
        }
    }

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    private static final Map<String, List<AddOption>> OPTIONS = new LinkedHashMap<>();

    static {
        KINDS.put("DENTAL", new Kind("DENTAL", "SITTING", "Dental treatment course"));
        KINDS.put("ANC", new Kind("ANC", "VISIT", "Pregnancy care · ANC course"));
        KINDS.put("CHEMO", new Kind("CHEMO", "CYCLE", "Chemotherapy course"));
        KINDS.put("SURGERY", new Kind("SURGERY", "STEP", "Surgery episode"));
        KINDS.put("REHAB", new Kind("REHAB", "SESSION", "Rehab package"));
        OPTIONS.put("DENTAL", List.of(new AddOption("opd_sitting", "OPD sitting", ENCOUNTER_OPD),
                new AddOption("day_care_admission", "Day-care admission", ENCOUNTER_DAY_CARE),
                new AddOption("ipd_admission", "IPD admission", ENCOUNTER_IPD)));
        OPTIONS.put("ANC", List.of(new AddOption("anc_visit", "ANC visit", ENCOUNTER_OPD),
                new AddOption("emergency_admission", "Emergency admission", ENCOUNTER_IPD),
                new AddOption("delivery_admission", "Delivery admission", ENCOUNTER_IPD)));
        OPTIONS.put("CHEMO", List.of(new AddOption("chemo_cycle", "Chemo cycle", ENCOUNTER_DAY_CARE),
                new AddOption("review_opd", "Review OPD", ENCOUNTER_OPD),
                new AddOption("side_effects_admission", "Admission for side effects", ENCOUNTER_IPD)));
        OPTIONS.put("SURGERY", List.of(new AddOption("opd_review", "OPD review", ENCOUNTER_OPD),
                new AddOption("re_admission", "Re-admission", ENCOUNTER_IPD),
                new AddOption("day_care_procedure", "Day-care procedure", ENCOUNTER_DAY_CARE)));
        OPTIONS.put("REHAB", List.of(new AddOption("extra_session", "Extra session", ENCOUNTER_OPD),
                new AddOption("bedside_session", "Bedside session (IPD)", ENCOUNTER_IPD)));
    }

    private CourseKinds() {
    }

    public static Optional<Kind> kind(String kind) {
        return Optional.ofNullable(kind == null ? null : KINDS.get(kind));
    }

    public static List<AddOption> options(String kind) {
        return OPTIONS.getOrDefault(kind, List.of());
    }

    /** The option of this kind with this API key (lower case) or stored key (upper case). */
    public static Optional<AddOption> option(String kind, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return options(kind).stream().filter(o -> o.key().equalsIgnoreCase(key)).findFirst();
    }

    public static boolean isAdmission(String encounterKind) {
        return ENCOUNTER_DAY_CARE.equals(encounterKind) || ENCOUNTER_IPD.equals(encounterKind);
    }

    /** Capitalised unit word for the ledger: "Sitting", "Visit", "Cycle", "Step", "Session". */
    public static String unitWord(String unit) {
        return unit == null || unit.isEmpty() ? "" : unit.charAt(0) + unit.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
