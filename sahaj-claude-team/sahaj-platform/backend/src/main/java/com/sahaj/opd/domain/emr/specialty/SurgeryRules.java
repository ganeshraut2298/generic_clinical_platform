package com.sahaj.opd.domain.emr.specialty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * General Surgery rules (SAH-64-1, SAH-64-2, SAH-64-3, SAH-71-1, SAH-71-2, SAH-71-3; rules C-7, L-3, B-1, B-3). Every
 * number and list is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed"
 * ({@link RuleTag#TAG}). Sample package rates of the mockup (B-2) are never used.
 */
public final class SurgeryRules {

    /** B-1: the deposit for a self-pay patient is half the package rounded to the nearest Rs 1,000 (in paise). */
    public static final long DEPOSIT_STEP_PAISE = 100_000L;
    /** B-3: the deposit for any other payer is Rs 10,000 (in paise). */
    public static final long OTHER_PAYER_DEPOSIT_PAISE = 1_000_000L;

    /** C-7: the procedures with default anaesthesia and stay (changeable). */
    public static final List<Procedure> PROCEDURES = List.of(
            new Procedure("LAP_CHOLECYSTECTOMY", "Lap cholecystectomy", "GA", "IPD_1_2_DAYS"),
            new Procedure("OPEN_CHOLECYSTECTOMY", "Open cholecystectomy", "GA", "IPD_4_5_DAYS"),
            new Procedure("LAP_HERNIA_TEP", "Lap hernia repair (TEP)", "GA", "IPD_1_DAY"),
            new Procedure("OPEN_MESH_HERNIOPLASTY", "Open mesh hernioplasty", "SPINAL", "IPD_2_DAYS"),
            new Procedure("LAP_APPENDICECTOMY", "Lap appendicectomy", "GA", "IPD_1_2_DAYS"),
            new Procedure("HAEMORRHOIDECTOMY", "Haemorrhoidectomy", "SPINAL", "IPD_1_DAY"),
            new Procedure("EXCISION_LIPOMA", "Excision of lipoma", "LOCAL", "DAY_CARE"),
            new Procedure("INCISION_DRAINAGE", "Incision and drainage", "LOCAL", "DAY_CARE"));

    /** C-7: the stay choices with the label shown. */
    public static final Map<String, String> ADMISSION_LABELS = RuleTag.ordered(
            "DAY_CARE", "Day care",
            "IPD_1_DAY", "IPD 1 day",
            "IPD_1_2_DAYS", "IPD 1–2 days",
            "IPD_2_DAYS", "IPD 2 days",
            "IPD_4_5_DAYS", "IPD 4–5 days");

    /** C-7: the pre-op tests and the order they are listed in. */
    public static final List<String> TESTS = List.of("CBC", "LFT", "KFT", "PT_INR", "BLOOD_SUGAR", "ECG", "CHEST_XRAY",
            "HIV", "HBSAG", "HCV", "BLOOD_GROUP");

    /** L-3: the five consent items of surgery, none ticked at the start. */
    public static final List<String> CONSENT_ITEMS = List.of("PROCEDURE_RISKS", "CONVERSION_TO_OPEN", "ANAESTHESIA",
            "BLOOD_TRANSFUSION", "PHOTOS_VIDEO");

    public static final Map<String, String> CONSENT_LABELS = Map.of(
            "PROCEDURE_RISKS", "Procedure, benefits and risks explained",
            "CONVERSION_TO_OPEN", "Possible conversion to open surgery",
            "ANAESTHESIA", "Anaesthesia consent",
            "BLOOD_TRANSFUSION", "Blood transfusion consent",
            "PHOTOS_VIDEO", "Photos / video for records");

    /** C-7: the pre-op order lines (instructions, never medicine orders). */
    public static final List<String> PREOP_ORDERS = List.of("NIL_BY_MOUTH", "INHALER_MORNING", "ANTIBIOTIC_INDUCTION",
            "PART_PREPARATION", "CLOT_PREVENTION", "ADMIT_7AM_OT_10AM");

    /** C-7: the pre-op instruction texts as drawn. */
    public static final Map<String, String> PREOP_LABELS = Map.of(
            "NIL_BY_MOUTH", "Nothing by mouth from midnight",
            "INHALER_MORNING", "Inhaler on the morning of surgery",
            "ANTIBIOTIC_INDUCTION", "Antibiotic at induction: cefazolin 2 g IV",
            "PART_PREPARATION", "Part preparation in ward",
            "CLOT_PREVENTION", "Clot prevention: stockings, early walking",
            "ADMIT_7AM_OT_10AM", "Admit 7 am, OT at 10 am");

    /** C-7: the blood chips as drawn. */
    public static final List<String> BLOOD_CHIPS = List.of("Not needed", "Group and save", "1 unit reserved");

    private static final Set<String> NO_THINNER = Set.of("NONE");

    private SurgeryRules() {
    }

    public record Procedure(String key, String label, String anaesthesia, String admission) {
    }

    public static Procedure procedure(String key) {
        return PROCEDURES.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    // ---- fitness (C-7, SAH-64-2) ----

    /** One pre-op test: state PENDING, NORMAL or ABNORMAL. */
    public record TestState(String key, String state) {
    }

    /** The fitness summary: state FIT, TESTS_PENDING, PAC_NOT_DONE or NOT_FIT with its text. */
    public record Fitness(String state, String text) {
    }

    /**
     * Fit for surgery: PAC "Fit" (or "Fit with caution") with no test pending or abnormal. Otherwise
     * "&lt;n&gt; test(s) pending: &lt;names&gt;", "PAC not done", "Not fit yet: &lt;names&gt; abnormal" or "Not fit yet: PAC unfit".
     * Abnormal tests and an unfit PAC come first, then pending tests, then PAC not done.
     */
    public static Fitness fitness(String pac, List<TestState> tests) {
        List<String> abnormal = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (TestState test : tests) {
            if ("ABNORMAL".equals(test.state())) {
                abnormal.add(testLabel(test.key()));
            } else if ("PENDING".equals(test.state())) {
                pending.add(testLabel(test.key()));
            }
        }
        if ("UNFIT".equals(pac)) {
            return new Fitness("NOT_FIT", "Not fit yet: PAC unfit");
        }
        if (!abnormal.isEmpty()) {
            return new Fitness("NOT_FIT", "Not fit yet: " + String.join(", ", abnormal) + " abnormal");
        }
        if (!pending.isEmpty()) {
            return new Fitness("TESTS_PENDING", pending.size() + " test(s) pending: " + String.join(", ", pending));
        }
        if ("NOT_DONE".equals(pac) || pac == null) {
            return new Fitness("PAC_NOT_DONE", "PAC not done");
        }
        return new Fitness("FIT", "Fit for surgery");
    }

    /** Warnings that inform and never block: a blood thinner other than NONE, an illness other than NONE, PAC unfit. */
    public static boolean hasWarning(String bloodThinner, List<String> otherIllness, String pac) {
        boolean thinner = bloodThinner != null && !NO_THINNER.contains(bloodThinner);
        boolean illness = otherIllness != null && otherIllness.stream().anyMatch(i -> !"NONE".equals(i));
        return thinner || illness || "UNFIT".equals(pac);
    }

    public static String testLabel(String key) {
        return switch (key) {
            case "PT_INR" -> "PT/INR";
            case "BLOOD_SUGAR" -> "Blood sugar";
            case "CHEST_XRAY" -> "Chest X-ray";
            case "HBSAG" -> "HBsAg";
            case "HCV" -> "HCV";
            case "HIV" -> "HIV";
            case "BLOOD_GROUP" -> "Blood group";
            default -> key;
        };
    }

    // ---- deposit and booking (B-1, B-3) ----

    /** B-1, B-3: round(package x 0.5 / 1,000) x 1,000 for Self; Rs 10,000 for any other payer; null with no package for Self. */
    public static Long depositPaise(String payer, Long packagePaise) {
        if (!"SELF".equals(payer)) {
            return OTHER_PAYER_DEPOSIT_PAISE;
        }
        if (packagePaise == null) {
            return null;
        }
        return Math.round(packagePaise * 0.5 / DEPOSIT_STEP_PAISE) * DEPOSIT_STEP_PAISE;
    }

    /** "Self-pay", "&lt;payer&gt; · pre-auth pending" or "&lt;payer&gt; · pre-auth sent". */
    public static String payerChip(String payer, boolean preauthSent) {
        if ("SELF".equals(payer)) {
            return "Self-pay";
        }
        String label = switch (payer) {
            case "INSURANCE_TPA" -> "Insurance (TPA)";
            case "MJPJAY_PMJAY" -> "MJPJAY / PM-JAY";
            case "COMPANY" -> "Company";
            default -> payer;
        };
        return label + (preauthSent ? " · pre-auth sent" : " · pre-auth pending");
    }

    /**
     * The booking rule: an active diagnosis item, every consent item ticked (and at least one item), and pre-authorisation
     * sent when the payer is not Self. Returns the missing parts in order: "diagnosis", "n consent item(s)",
     * "pre-authorisation"; empty when ready.
     */
    public static List<String> bookMissing(boolean hasDiagnosis, int consentTicked, int consentTotal, String payer,
                                           boolean preauthSent) {
        List<String> missing = new ArrayList<>();
        if (!hasDiagnosis) {
            missing.add("diagnosis");
        }
        int open = Math.max(consentTotal, CONSENT_ITEMS.size()) - consentTicked;
        if (consentTotal == 0 || open > 0) {
            missing.add(open + " consent item(s)");
        }
        if (!"SELF".equals(payer) && !preauthSent) {
            missing.add("pre-authorisation");
        }
        return missing;
    }

    /** The 409 booking-not-ready detail: "Before booking: diagnosis, 2 consent item(s)." */
    public static String bookMissingDetail(List<String> missing) {
        return "Before booking: " + String.join(", ", missing) + ".";
    }

    /** The result of the booking message for IPD on, or the no-IPD message (H14). */
    public static String bookedMessage(String procedureText, String otDate, String slot, boolean ipd) {
        if (!ipd) {
            return "Booked. This clinic has no IPD, so no ward bed was requested.";
        }
        String when = slot == null || slot.isBlank() ? otDate : otDate + ", " + slot;
        return procedureText + " booked for " + when + ". Ward bed requested.";
    }

    public static Map<String, String> admissionLabels() {
        return new LinkedHashMap<>(ADMISSION_LABELS);
    }
}
