package com.sahaj.opd.application.emr.specialty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The screen registry by starter key (ADR-023 D1, D3). The record's snapshot {@code starterKey} selects a specialty
 * profile on OPD_VISIT records only; any other starter, no starter (a BLANK department), IPD_ROUND and ER_ASSESSMENT use
 * the generic renderer. A profile is a fixed map from section key to specialty component (the "anchor" sections); every
 * other section of the department renders with the generic renderer and the shared components.
 */
public final class SpecialtyProfiles {

    public static final List<String> PROFILES = List.of("gm", "dental", "og", "paeds", "physio", "onco", "surgery");

    private static final Map<String, Map<String, String>> ANCHORS = new LinkedHashMap<>();

    static {
        ANCHORS.put("gm", ordered("vitals", "VITALS_LOG", "exam", "EXAM_GENERAL"));
        ANCHORS.put("dental", ordered("dental_chart", "TOOTH_CHART", "tx_plan", "DENTAL_PLAN", "dental_done",
                "DENTAL_DONE", "dental_lab", "LAB_WORK", "consent", "CONSENT_CHECKLIST"));
        ANCHORS.put("og", ordered("anc", "ANC_CHECK", "pcpndt", "FORM_F", "gyn_exam", "GYNAE_EXAM"));
        ANCHORS.put("paeds", ordered("growth", "GROWTH", "immunization", "IMMUNIZATION", "milestones", "MILESTONES",
                "wtdose", "WEIGHT_DOSE"));
        ANCHORS.put("physio", ordered("pain", "PAIN_SCORE", "posture", "POSTURE_GAIT", "rom", "ROM_TABLE", "mmt",
                "MMT_TABLE", "special_tests", "SPECIAL_TESTS", "func_scales", "OUTCOME_SCALES", "goals", "GOALS",
                "modalities", "MODALITIES", "hep",
                "HOME_EXERCISES", "sessions", "SESSION_NOTE"));
        ANCHORS.put("surgery", ordered("sx_findings", "SURGICAL_EXAM", "sx_fitness", "SURGERY_FITNESS", "sx_plan",
                "SURGERY_PLAN", "sx_consent", "CONSENT_CHECKLIST", "sx_preauth", "PREAUTH", "sx_preop",
                "PREOP_ORDERS"));
        ANCHORS.put("onco", ordered("vitals", "VITALS_LOG", "onc_tox", "ONC_TOX", "onc_bsa", "ONC_EXAM", "onc_labs",
                "ONC_LABS", "onc_ecog", "ONC_ECOG", "onc_regimen", "CHEMO_PLAN"));
    }

    private SpecialtyProfiles() {
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Map.copyOf(map);
    }

    public static boolean isProfile(String key) {
        return key != null && ANCHORS.containsKey(key);
    }

    /** The profile of a record: only an OPD_VISIT with one of the seven starter keys has one. */
    public static Optional<String> profileOf(String encounterType, String starterKey) {
        if (!"OPD_VISIT".equals(encounterType) || starterKey == null || !ANCHORS.containsKey(starterKey)) {
            return Optional.empty();
        }
        return Optional.of(starterKey);
    }

    /** The anchor sections of a profile (section key to component). */
    public static Map<String, String> anchors(String profile) {
        return ANCHORS.getOrDefault(profile, Map.of());
    }

    /** The component a section has in this profile, or null for a generic section. */
    public static String component(String profile, String sectionKey) {
        return anchors(profile).get(sectionKey);
    }

    /** The section key that holds a component in this profile, or null. */
    public static String sectionOf(String profile, String component) {
        for (Map.Entry<String, String> entry : anchors(profile).entrySet()) {
            if (entry.getValue().equals(component)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static final Map<String, List<String>> HIDDEN_BY_MODE = Map.of(
            "og/ANC", List.of("gyn_exam", "infertility", "contraception"),
            "og/GYNAE", List.of("obstetric", "anc", "pcpndt", "usg"),
            "physio/FIRST_VISIT", List.of("modalities", "sessions"),
            "physio/SESSION", List.of("special_tests", "posture"));

    /**
     * The effective visit mode (ADR-023 D13.3): the stored screen mode when set; else og ANC; physio SESSION when the
     * linked REHAB session number is above 1, else FIRST_VISIT; null for the other profiles.
     */
    public static String effectiveMode(String profile, String storedMode, Integer rehabSessionNo) {
        if (!"og".equals(profile) && !"physio".equals(profile)) {
            return null;
        }
        if (storedMode != null && modes(profile).contains(storedMode)) {
            return storedMode;
        }
        if ("og".equals(profile)) {
            return "ANC";
        }
        return rehabSessionNo != null && rehabSessionNo > 1 ? "SESSION" : "FIRST_VISIT";
    }

    /**
     * The section keys the visit mode hides (stories SAH-61-1, SAH-61-3, SAH-63). {@code formFRequired} keeps {@code pcpndt}
     * shown in the Gynae mode while PCPNDT Form F is a legal duty (H21).
     */
    public static List<String> hiddenSections(String profile, String mode, boolean formFRequired) {
        if (mode == null) {
            return List.of();
        }
        List<String> hidden = HIDDEN_BY_MODE.getOrDefault(profile + "/" + mode, List.of());
        if (formFRequired && "og".equals(profile)) {
            return hidden.stream().filter(key -> !"pcpndt".equals(key)).toList();
        }
        return hidden;
    }

    /** The visit modes a profile has (og ANC or GYNAE; physio FIRST_VISIT or SESSION); empty for the others. */
    public static List<String> modes(String profile) {
        return switch (profile) {
            case "og" -> List.of("ANC", "GYNAE");
            case "physio" -> List.of("FIRST_VISIT", "SESSION");
            default -> List.of();
        };
    }
}
