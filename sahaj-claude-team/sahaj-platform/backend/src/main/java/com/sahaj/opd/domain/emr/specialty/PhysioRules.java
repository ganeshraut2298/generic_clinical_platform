package com.sahaj.opd.domain.emr.specialty;

import java.util.List;
import java.util.Map;

/**
 * Physiotherapy rules (SAH-63-1, SAH-63-2, SAH-63-3; rule C-6). Every number and list is "value from the EMR mockup,
 * accepted by the human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}).
 */
public final class PhysioRules {

    /** C-6: NPRS 0 "no pain" to 10 "worst pain". */
    public static final int NPRS_MIN = 0;
    public static final int NPRS_MAX = 10;
    /** C-6: manual muscle testing 0 to 5. */
    public static final int MMT_MIN = 0;
    public static final int MMT_MAX = 5;
    /** C-6: a package is 10 sessions. */
    public static final int PACKAGE_SESSIONS = 10;
    /** C-6: the extension bar shows this percent when the normal maximum is 0 degrees and the value is below 0. */
    public static final int EXTENSION_BAR_BELOW_ZERO_PCT = 90;

    /** C-6: the gait choices. */
    public static final List<String> GAITS = List.of("NORMAL", "ANTALGIC", "WITH_STICK", "WITH_WALKER");

    /** C-6: the modalities with their default parameters. A typed modality has no key and no defaults. */
    public static final Map<String, String> MODALITY_DEFAULTS = RuleTag.ordered(
            "IFT", "15 min · knee, 4-pole",
            "HOT_PACK", "10 min · before exercise",
            "ULTRASOUND", "1 MHz · 5 min",
            "TENS", "20 min · conventional",
            "EXERCISE_THERAPY", "Supervised, 20 min",
            "TAPING", "Patella, unloading");

    public static final Map<String, String> MODALITY_LABELS = RuleTag.ordered(
            "IFT", "IFT",
            "HOT_PACK", "Hot pack",
            "ULTRASOUND", "Ultrasound",
            "TENS", "TENS",
            "EXERCISE_THERAPY", "Exercise therapy",
            "TAPING", "Taping");

    /**
     * C-6 (0.5.1, H20): the outcome scales a therapist types a total score for, with the highest score of each scale
     * used as an input sanity limit (not a clinical threshold). The score is typed; no questionnaire is drawn.
     */
    public static final Map<String, Integer> OUTCOME_SCALE_MAX = Map.of("ODI", 100, "NDI", 50, "WOMAC", 96, "LEFS", 80);

    /** C-6: the scales in the order the mockup lists them (Scale: ODI, NDI, WOMAC, LEFS). */
    public static final List<String> OUTCOME_SCALES = List.of("ODI", "NDI", "WOMAC", "LEFS");

    private PhysioRules() {
    }

    /**
     * The bar of a range of motion: value divided by the normal maximum times 100, between 0 and 100. For a normal
     * maximum of 0 degrees (extension): 100 percent at 0 degrees, otherwise 90 percent. No value gives null; no normal
     * range gives null (a typed joint has no normal range).
     */
    public static Integer romPercent(Integer degrees, Integer normalMax) {
        if (degrees == null || normalMax == null) {
            return null;
        }
        if (normalMax == 0) {
            return degrees == 0 ? 100 : EXTENSION_BAR_BELOW_ZERO_PCT;
        }
        long pct = Math.round(degrees * 100.0 / normalMax);
        return (int) Math.max(0, Math.min(100, pct));
    }

    /** The next test result when tapped: NOT_DONE, POSITIVE (red), NEGATIVE (green), NOT_DONE. */
    public static String nextTestResult(String current) {
        return switch (current == null ? "NOT_DONE" : current) {
            case "NOT_DONE" -> "POSITIVE";
            case "POSITIVE" -> "NEGATIVE";
            default -> "NOT_DONE";
        };
    }
}
