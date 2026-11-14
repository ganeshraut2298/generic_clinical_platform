package com.sahaj.opd.domain.emr.specialty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dental rules (SAH-49-1, SAH-49-2, SAH-69-1, SAH-69-2; rule C-3 and the estimate rule B-2). Every number and list is
 * "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}). Sample
 * rates of the mockup are never used: prices come from the clinic tariff.
 */
public final class DentalRules {

    /** C-3: the tooth conditions of the chart. */
    public static final List<String> CONDITIONS = List.of("HEALTHY", "CARIES", "FILLED", "RCT", "CROWN", "MISSING",
            "TO_EXTRACT", "IMPLANT");
    /** C-3: only these conditions may carry surfaces. */
    public static final Set<String> SURFACE_CONDITIONS = Set.of("CARIES", "FILLED", "CROWN");
    /** C-3: the five surfaces. */
    public static final List<String> SURFACES = List.of("M", "O", "D", "B", "L");
    /** C-3: tooth shades; A2 at the start. */
    public static final List<String> SHADES = List.of("A1", "B1", "A2", "A3", "C2");
    /** C-3: gum tones; PINK at the start. */
    public static final List<String> GUMS = List.of("PINK", "LIGHT_BROWN", "DARK_BROWN");
    /** C-3: mobility G0 to G3. */
    public static final int MOBILITY_MAX = 3;
    /** C-3: a root canal is planned as 3 sittings at the start. */
    public static final int ROOT_CANAL_SITTINGS = 3;
    /** Analyst addition: 1 to 6 sittings may be set. */
    public static final int MIN_SITTINGS = 1;
    public static final int MAX_SITTINGS = 6;
    /** C-3: next sitting after 3 days, 7 days, 14 days (or 1 month). */
    public static final List<Integer> NEXT_SITTING_DAYS = List.of(3, 7, 14);
    /** C-3: next sitting after 1 month. */
    public static final int NEXT_SITTING_MONTHS = 1;
    /** B-2 (analyst reading): estimate parts are rounded to this many paise (Rs 50). */
    public static final long ESTIMATE_STEP_PAISE = 5_000L;
    /** ADR-023 H15: the Child tab opens for a patient under this many years. */
    public static final int CHILD_UNDER_YEARS = 13;

    private static final Map<String, String> LABELS = Map.of(
            "COMPOSITE_FILLING", "Composite filling",
            "ROOT_CANAL", "Root canal treatment",
            "PFM_CROWN", "PFM crown",
            "REPLACE_FILLING", "Replace filling",
            "CROWN_RECEMENTATION", "Crown re-cementation",
            "IMPLANT_WITH_CROWN", "Implant with crown",
            "BRIDGE", "Bridge",
            "EXTRACTION", "Extraction",
            "IMPLANT_REVIEW", "Implant review");

    private static final Map<String, List<Suggestion>> SUGGESTIONS = Map.of(
            "CARIES", List.of(new Suggestion("COMPOSITE_FILLING", "Composite filling", 1),
                    new Suggestion("ROOT_CANAL", "Root canal treatment", 1)),
            "RCT", List.of(new Suggestion("ROOT_CANAL", "Root canal treatment", 1),
                    new Suggestion("PFM_CROWN", "PFM crown", 2)),
            "FILLED", List.of(new Suggestion("REPLACE_FILLING", "Replace filling", 1)),
            "CROWN", List.of(new Suggestion("CROWN_RECEMENTATION", "Crown re-cementation", 1)),
            "MISSING", List.of(new Suggestion("IMPLANT_WITH_CROWN", "Implant with crown", 3),
                    new Suggestion("BRIDGE", "Bridge", 2)),
            "TO_EXTRACT", List.of(new Suggestion("EXTRACTION", "Extraction", 1)),
            "IMPLANT", List.of(new Suggestion("IMPLANT_REVIEW", "Implant review", 3)));

    private DentalRules() {
    }

    /** A treatment the chart suggests for a condition, with its phase. */
    public record Suggestion(String procedureKey, String label, int phase) {
    }

    /** A plan row as the estimate and its fingerprint see it. */
    public record PlanRow(int tooth, String procedureText, long amountPaise, int sittingsPlanned) {
    }

    /** C-3: the suggestions of a condition; empty for a healthy tooth ("Healthy tooth: nothing to plan."). */
    public static List<Suggestion> suggestions(String condition) {
        return SUGGESTIONS.getOrDefault(condition == null ? "" : condition, List.of());
    }

    public static String procedureLabel(String procedureKey) {
        return LABELS.get(procedureKey);
    }

    public static boolean isProcedureKey(String key) {
        return key != null && LABELS.containsKey(key);
    }

    /** C-3: a root canal is 3 sittings, every other suggested treatment 1. */
    public static int defaultSittings(String procedureKey) {
        return "ROOT_CANAL".equals(procedureKey) ? ROOT_CANAL_SITTINGS : MIN_SITTINGS;
    }

    /** FDI numbers only: adult 11-18, 21-28, 31-38, 41-48; primary 51-55, 61-65, 71-75, 81-85 (400 otherwise). */
    public static boolean validTooth(int tooth) {
        int quadrant = tooth / 10;
        int index = tooth % 10;
        if (quadrant >= 1 && quadrant <= 4) {
            return index >= 1 && index <= 8;
        }
        if (quadrant >= 5 && quadrant <= 8) {
            return index >= 1 && index <= 5;
        }
        return false;
    }

    public static boolean isPrimary(int tooth) {
        return tooth / 10 >= 5;
    }

    /**
     * ADR-023 H15, ADR-024 D7 (SAH-73-5): the Child tab opens for a patient under 13 completed years. The years are the
     * effective age from patient.api: worked out from the date of birth when one is recorded, else the stored whole
     * number. Null (no patient) opens the Adult tab.
     */
    public static boolean childTabDefault(Integer effectiveYears) {
        return effectiveYears != null && effectiveYears < CHILD_UNDER_YEARS;
    }

    /** The same tooth and procedure twice (ignoring case and spaces) is a duplicate (400 duplicate-item). */
    public static String dedupeKey(int tooth, String procedureText) {
        return tooth + "|" + normalise(procedureText);
    }

    private static String normalise(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * B-2 (analyst reading): the amount of each sitting. Equal parts rounded to Rs 50, the last sitting takes the rest.
     * One sitting takes the whole amount. The parts always add up to the total and none is negative.
     */
    public static List<Long> splitEstimate(long totalPaise, int sittings) {
        int count = Math.max(1, sittings);
        List<Long> parts = new ArrayList<>();
        long remaining = Math.max(0, totalPaise);
        long equal = Math.round((double) remaining / count / ESTIMATE_STEP_PAISE) * ESTIMATE_STEP_PAISE;
        for (int i = 0; i < count - 1; i++) {
            long part = Math.min(equal, remaining);
            parts.add(part);
            remaining -= part;
        }
        parts.add(remaining);
        return parts;
    }

    /** The estimate total of the plan rows. */
    public static long total(List<PlanRow> rows) {
        long sum = 0;
        for (PlanRow row : rows) {
            sum += row.amountPaise();
        }
        return sum;
    }

    /**
     * The fingerprint of the plan (rows, teeth, procedures, amounts, sittings, in order): a change after the patient
     * signed the estimate sets the state CHANGED and clears the tick.
     */
    public static String planFingerprint(List<PlanRow> rows) {
        StringBuilder text = new StringBuilder();
        for (PlanRow row : rows) {
            text.append(row.tooth()).append('|').append(normalise(row.procedureText())).append('|')
                    .append(row.amountPaise()).append('|').append(row.sittingsPlanned()).append('\n');
        }
        return RuleTag.sha256(text.toString());
    }

    // ---- SAH-75-6 (ADR-026 D6): "Medical history and alerts" (values from the EMR mockup, not clinically reviewed) ----

    /** The three chip groups of the card, in the drawn order; NONE last. */
    public static final List<String> MED_CONDITIONS = List.of("DIABETES", "HIGH_BP", "HEART_DISEASE", "THYROID",
            "ASTHMA", "BLEEDING_DISORDER", "EPILEPSY", "PREGNANT", "NONE");
    public static final List<String> MED_MEDICINES = List.of("BLOOD_THINNERS", "ASPIRIN", "BISPHOSPHONATES", "STEROIDS",
            "THYROID_MEDICINE", "NONE");
    public static final List<String> MED_ALLERGIES = List.of("PENICILLIN", "LOCAL_ANAESTHETIC", "LATEX", "NSAIDS",
            "NONE");

    /** The words of each chip as drawn (also the allergy text written to the patient record at signing, OQ-75-16). */
    public static final Map<String, String> MED_LABELS = Map.ofEntries(
            Map.entry("DIABETES", "Diabetes"), Map.entry("HIGH_BP", "High BP"),
            Map.entry("HEART_DISEASE", "Heart disease"), Map.entry("THYROID", "Thyroid"),
            Map.entry("ASTHMA", "Asthma"), Map.entry("BLEEDING_DISORDER", "Bleeding disorder"),
            Map.entry("EPILEPSY", "Epilepsy"), Map.entry("PREGNANT", "Pregnant"),
            Map.entry("BLOOD_THINNERS", "Blood thinners"), Map.entry("ASPIRIN", "Aspirin"),
            Map.entry("BISPHOSPHONATES", "Bisphosphonates"), Map.entry("STEROIDS", "Steroids"),
            Map.entry("THYROID_MEDICINE", "Thyroid medicine"), Map.entry("PENICILLIN", "Penicillin"),
            Map.entry("LOCAL_ANAESTHETIC", "Local anaesthetic"), Map.entry("LATEX", "Latex"),
            Map.entry("NSAIDS", "Painkillers (NSAIDs)"), Map.entry("NONE", "None"));

    /** The drug class of an allergy chip; only Penicillin has one (ADR-026 H15). */
    public static final Map<String, String> MED_ALLERGY_CLASS = Map.of("PENICILLIN", "PENICILLIN");

    /** The habits of "Dental history and habits" that raise the tobacco banner (option keys of dental_hx.habits). */
    public static final Set<String> TOBACCO_HABITS = Set.of("tobacco_chewing", "gutkha", "paan_betel", "smoking");

    /** One banner inside the card: the drawn title and sentence. */
    public record MedicalAlert(String key, String title, String text) {
    }

    /**
     * The banners of "Medical history and alerts", in the drawn order and words, each only when its condition holds. They
     * warn; they never block. {@code habits} are the option keys chosen in dental_hx.habits of the same record.
     */
    public static List<MedicalAlert> medicalAlerts(Set<String> conditions, Set<String> medicines, Set<String> allergies,
                                                   Set<String> habits) {
        List<MedicalAlert> alerts = new ArrayList<>();
        if (allergies.contains("PENICILLIN")) {
            alerts.add(new MedicalAlert("PENICILLIN_ALLERGY", "Penicillin allergy.",
                    "Avoid amoxicillin; use a non-penicillin antibiotic."));
        }
        if (medicines.contains("BLOOD_THINNERS") || medicines.contains("ASPIRIN")
                || conditions.contains("BLEEDING_DISORDER")) {
            alerts.add(new MedicalAlert("BLEEDING_RISK", "Bleeding risk.",
                    "Check INR or stop advice with the physician before extraction or surgery."));
        }
        if (conditions.contains("DIABETES")) {
            alerts.add(new MedicalAlert("DIABETES", "Diabetes.", "Check sugar before surgery; healing is slower."));
        }
        if (conditions.contains("HIGH_BP") || conditions.contains("HEART_DISEASE")) {
            alerts.add(new MedicalAlert("BP_HEART", "BP / heart.",
                    "Check BP; limit adrenaline in local anaesthetic."));
        }
        if (conditions.contains("PREGNANT")) {
            alerts.add(new MedicalAlert("PREGNANT", "Pregnant.",
                    "Avoid X-rays unless essential; second trimester is safest for treatment."));
        }
        if (medicines.contains("BISPHOSPHONATES")) {
            alerts.add(new MedicalAlert("BISPHOSPHONATES", "Bisphosphonates.",
                    "Risk of jaw bone problems after extraction or implant."));
        }
        if (habits.stream().anyMatch(TOBACCO_HABITS::contains)) {
            alerts.add(new MedicalAlert("TOBACCO_USE", "Tobacco use.",
                    "Screen the mouth for pre-cancer patches and counsel to quit."));
        }
        return List.copyOf(alerts);
    }

    /** True when NONE is chosen together with another chip of the same group (400 validation). */
    public static boolean noneWithOthers(List<String> chosen) {
        return chosen.contains("NONE") && chosen.size() > 1;
    }
}
