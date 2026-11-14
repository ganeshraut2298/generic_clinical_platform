package com.sahaj.opd.domain.emr.specialty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Medical Oncology assessment rules (SAH-65-1 to SAH-65-4; rule C-8). Every number is "value from the EMR mockup, accepted
 * by the human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}). The suggestion is advice: the doctor makes the
 * final call and a different choice needs a reason. Nothing here sends or orders anything.
 */
public final class OncologyRules {

    /** C-8: the body surface area is capped at this when "Cap at 2.0 m2" is on. */
    public static final double BSA_CAP = 2.0;
    /** C-8: a weight change of this percent or more (either way) from cycle 1 recalculates the doses. */
    public static final double WEIGHT_CHANGE_RECALCULATE_PCT = 10.0;
    /** C-8: labs drawn within this many hours before now count as fresh. */
    public static final long LABS_FRESH_HOURS = 72;
    /** C-8: fever is this temperature (degrees C) or above. */
    public static final double FEVER_C = 38.0;
    /** C-8: fit for chemo at this ECOG grade or lower. */
    public static final int ECOG_FIT_MAX = 2;
    /** C-8: ECOG at this grade or higher gives the suggestion Stop chemo. */
    public static final int ECOG_STOP_MIN = 3;
    /** C-8: a worst confirmed side-effect grade at this level or higher suggests a reduced dose and raises an alert. */
    public static final int GRADE_REDUCE_MIN = 3;
    /** C-8: lesion change at or below this percent from baseline is green (and a partial response). */
    public static final double LESION_GREEN_PCT = -30.0;
    /** C-8: lesion change at or above this percent from baseline is red. */
    public static final double LESION_RED_PCT = 20.0;
    /** C-8: progressive disease needs this rise from the nadir in percent ... */
    public static final double PD_FROM_NADIR_PCT = 20.0;
    /** C-8: ... and this absolute rise in centimetres. */
    public static final double PD_FROM_NADIR_CM = 0.5;
    /** C-8: lab limits. Hb below, ANC below, platelets below; creatinine, bilirubin, ALT above; LVEF below. */
    public static final double HB_MIN = 8.0;
    public static final int ANC_MIN = 1500;
    public static final int PLT_MIN = 100;
    public static final double CREATININE_MAX = 1.5;
    public static final double BILIRUBIN_MAX = 1.5;
    public static final int ALT_MAX = 100;
    public static final int LVEF_MIN = 50;
    /** C-8: the next-cycle echo is "Before next cycle" when the last LVEF is below this. */
    public static final int ECHO_BEFORE_NEXT_CYCLE_BELOW = 55;
    /** C-8: a delayed cycle is re-booked this many days later. */
    public static final int DELAY_DAYS = 7;
    /** C-8: the lifetime anthracycline alert is raised above this (mg/m2). */
    public static final double CUMULATIVE_ALERT_MG_M2 = 400.0;

    public static final List<String> SYMPTOMS = List.of("NAUSEA", "VOMITING", "MOUTH_SORES", "LOOSE_MOTIONS",
            "TINGLING_NUMBNESS", "FATIGUE", "FEVER_LOW_COUNTS", "PAIN", "LOSS_OF_APPETITE");
    public static final List<String> EVENTS = List.of("ER_VISIT", "ADMITTED", "FEVER_38_AT_HOME", "BLOOD_TRANSFUSION",
            "ANTIBIOTICS", "MISSED_ORAL_MEDICINES");
    public static final List<String> DECISIONS = List.of("GIVE_AS_PLANNED", "GIVE_REDUCED", "DELAY_1_WEEK",
            "CHANGE_REGIMEN", "STOP");
    public static final List<String> DEVIATION_REASONS = List.of("PATIENT_PREFERENCE", "TUMOUR_BOARD_ADVICE",
            "COUNTS_RECOVERING", "CLINICAL_JUDGEMENT", "SCHEME_COST");
    public static final List<String> ECOG_TEXTS = List.of("Fully active", "Light work possible",
            "Up and about over 50% of day; no work", "In bed or chair over 50% of day", "Fully bedbound");

    /** C-8 (0.5.1): one of the five examination systems as drawn: key, label, hint and the chips. */
    public record ExamSystem(String key, String label, String hint, List<String> chips) {
    }

    public static final List<ExamSystem> SYSTEMS = List.of(
            new ExamSystem("cvs", "Heart (CVS)", "Anthracycline: watch for heart failure",
                    List.of("New murmur", "Raised JVP", "Tachycardia", "S3")),
            new ExamSystem("rs", "Lungs (RS)", "Effusion, infection, drug lung",
                    List.of("Reduced air entry", "Crepitations", "Dull note (effusion)", "Wheeze")),
            new ExamSystem("pa", "Abdomen", "Liver spread, ascites",
                    List.of("Liver enlarged", "Tenderness", "Ascites", "Mass")),
            new ExamSystem("cns", "Nervous system", "Brain spread, neuropathy",
                    List.of("Focal weakness", "Reduced sensation", "Reflexes reduced", "Confusion")),
            new ExamSystem("bone", "Spine and bones", "Bone spread",
                    List.of("Spine tenderness", "Rib tenderness", "Restricted movement")));

    /** C-8 (0.5.1): the keys of the five systems; "systemic exam" is Done when every one is marked. */
    public static final List<String> SYSTEM_KEYS = SYSTEMS.stream().map(ExamSystem::key).toList();

    /** C-8 (0.5.1): a take-home line as drawn. Tick at the start of a cycle: {@code defaultOn} (Pegfilgrastim is suggested). */
    public record TakeHome(String key, String label, String medicineName, String note, String frequency,
                           String frequencyText, Integer days, boolean defaultOn) {
    }

    public static final List<TakeHome> TAKE_HOME = List.of(
            new TakeHome("ONDANSETRON_8", "Ondansetron 8 mg", "Ondansetron 8 mg", "Nausea", "1-0-1", null, 3, true),
            new TakeHome("DEXAMETHASONE_4", "Dexamethasone 4 mg", "Dexamethasone 4 mg", "Delayed nausea", "1-0-1", null,
                    2, true),
            new TakeHome("PANTOPRAZOLE_40", "Pantoprazole 40 mg", "Pantoprazole 40 mg", "Stomach protection", "1-0-0",
                    null, 5, true),
            new TakeHome("PEGFILGRASTIM_6", "Pegfilgrastim 6 mg SC", "Pegfilgrastim 6 mg", "Prevents low counts / fever",
                    null, "Once, day 2", null, false),
            new TakeHome("BENZYDAMINE_MOUTHWASH", "Benzydamine mouthwash", "Benzydamine mouthwash", "Mouth sores",
                    "1-1-1", null, 7, true),
            new TakeHome("LOPERAMIDE_2", "Loperamide 2 mg", "Loperamide 2 mg", "Loose motions", null,
                    "After each loose motion (max 8)", null, false));

    /** C-8 (0.5.1): the eight referrals as drawn, none ticked at the start. */
    public static final java.util.Map<String, String> REFERRALS = RuleTag.ordered(
            "RADIOTHERAPY", "Radiotherapy",
            "SURGICAL_ONCOLOGY", "Surgical oncology",
            "PALLIATIVE_CARE", "Palliative care",
            "DIETITIAN", "Dietitian",
            "PSYCHO_ONCOLOGY_COUNSELLING", "Psycho-oncology counselling",
            "FERTILITY_COUNSELLING", "Fertility counselling",
            "SOCIAL_WORKER_SCHEME_PAPERS", "Social worker (scheme papers)",
            "CARDIOLOGY_ECHO", "Cardiology (echo)");

    private OncologyRules() {
    }

    // ---- BSA and weight ----

    /** C-8 (Mosteller): the square root of height (cm) x weight (kg) / 3600, to 2 decimals; null when either is missing. */
    public static Double bsa(Double heightCm, Double weightKg) {
        if (heightCm == null || weightKg == null || heightCm <= 0 || weightKg <= 0) {
            return null;
        }
        return round(Math.sqrt(heightCm * weightKg / 3600.0), 2);
    }

    /** The BSA used for the doses: capped at 2.0 m2 when the cap is on. */
    public static Double bsaUsed(Double bsa, boolean capAt2) {
        if (bsa == null) {
            return null;
        }
        return capAt2 ? Math.min(BSA_CAP, bsa) : bsa;
    }

    /** True when the cap changed the BSA. */
    public static boolean capped(Double bsa, boolean capAt2) {
        return bsa != null && capAt2 && bsa > BSA_CAP;
    }

    /** (today - cycle 1) / cycle 1 x 100, one decimal; null when either weight is missing. */
    public static Double weightChangePct(Double todayKg, Double cycle1Kg) {
        if (todayKg == null || cycle1Kg == null || cycle1Kg <= 0) {
            return null;
        }
        return round((todayKg - cycle1Kg) / cycle1Kg * 100.0, 1);
    }

    /** True when the weight changed by 10 percent or more either way. */
    public static boolean recalculateDoses(Double weightChangePct) {
        return weightChangePct != null && Math.abs(weightChangePct) >= WEIGHT_CHANGE_RECALCULATE_PCT;
    }

    // ---- lesions and response ----

    /** One lesion; {@code previous} and {@code today} are null when not measured. */
    public record Lesion(String id, double baseline, Double previous, Double today) {
    }

    public record LesionChange(String id, Double pctFromBaseline, String colour) {
    }

    public record Sums(double baseline, double previous, double today, double nadir, Double pctFromBaseline,
                       Double pctFromNadir) {
    }

    /** The response and the sums it came from; both null when it cannot be worked out. */
    public record Response(String category, Sums sums) {
    }

    public static List<LesionChange> lesionChanges(List<Lesion> lesions) {
        List<LesionChange> changes = new ArrayList<>();
        for (Lesion lesion : lesions) {
            Double pct = lesion.today() == null || lesion.baseline() <= 0 ? null
                    : round((lesion.today() - lesion.baseline()) / lesion.baseline() * 100.0, 1);
            String colour = pct == null ? "NONE" : pct <= LESION_GREEN_PCT ? "GREEN" : pct >= LESION_RED_PCT ? "RED"
                    : "NONE";
            changes.add(new LesionChange(lesion.id(), pct, colour));
        }
        return changes;
    }

    /**
     * C-8: with B the baseline sum, P the previous sum (the baseline when nothing earlier) and T today's sum: percent from
     * baseline = (T - B) / B x 100; nadir = min(B, P); percent from nadir = (T - nadir) / nadir x 100. Response:
     * PROGRESSIVE_DISEASE when a new lesion is seen, or the rise from the nadir is 20 percent or more and 0.5 cm or more;
     * else COMPLETE_RESPONSE when lesions exist and T is 0; else PARTIAL_RESPONSE when the change from baseline is -30
     * percent or lower; else STABLE_DISEASE. Without a new lesion every lesion needs today's size, otherwise null.
     */
    public static Response response(List<Lesion> lesions, boolean newLesionSeen) {
        boolean allMeasured = !lesions.isEmpty() && lesions.stream().allMatch(l -> l.today() != null);
        if (!allMeasured) {
            return new Response(newLesionSeen ? "PROGRESSIVE_DISEASE" : null, null);
        }
        double baseline = 0;
        double previous = 0;
        double today = 0;
        for (Lesion lesion : lesions) {
            baseline += lesion.baseline();
            previous += lesion.previous() == null ? lesion.baseline() : lesion.previous();
            today += lesion.today();
        }
        baseline = round(baseline, 1);
        previous = round(previous, 1);
        today = round(today, 1);
        double nadir = Math.min(baseline, previous);
        Double pctBaseline = baseline > 0 ? round((today - baseline) / baseline * 100.0, 1) : null;
        Double pctNadir = nadir > 0 ? round((today - nadir) / nadir * 100.0, 1) : null;
        Sums sums = new Sums(baseline, previous, today, nadir, pctBaseline, pctNadir);
        boolean riseFromNadir = pctNadir != null && pctNadir >= PD_FROM_NADIR_PCT
                && round(today - nadir, 1) >= PD_FROM_NADIR_CM;
        String category;
        if (newLesionSeen || riseFromNadir) {
            category = "PROGRESSIVE_DISEASE";
        } else if (today == 0) {
            category = "COMPLETE_RESPONSE";
        } else if (pctBaseline != null && pctBaseline <= LESION_GREEN_PCT) {
            category = "PARTIAL_RESPONSE";
        } else {
            category = "STABLE_DISEASE";
        }
        return new Response(category, sums);
    }

    // ---- labs ----

    /** The seven values; any may be null. */
    public record Labs(Double hb, Integer anc, Integer plt, Double cr, Double bili, Integer alt, Integer lvef) {
    }

    /** One failed lab: key hb, anc, plt, cr, bili, alt or lvef and the direction. */
    public record LabFail(String key, String direction) {
    }

    /** C-8: the failed labs in the order Hb, ANC, platelets, creatinine, bilirubin, ALT, LVEF. */
    public static List<LabFail> labFails(Labs labs) {
        List<LabFail> fails = new ArrayList<>();
        if (labs.hb() != null && labs.hb() < HB_MIN) {
            fails.add(new LabFail("hb", "BELOW_NEEDED"));
        }
        if (labs.anc() != null && labs.anc() < ANC_MIN) {
            fails.add(new LabFail("anc", "BELOW_NEEDED"));
        }
        if (labs.plt() != null && labs.plt() < PLT_MIN) {
            fails.add(new LabFail("plt", "BELOW_NEEDED"));
        }
        if (labs.cr() != null && labs.cr() > CREATININE_MAX) {
            fails.add(new LabFail("cr", "ABOVE_NEEDED"));
        }
        if (labs.bili() != null && labs.bili() > BILIRUBIN_MAX) {
            fails.add(new LabFail("bili", "ABOVE_NEEDED"));
        }
        if (labs.alt() != null && labs.alt() > ALT_MAX) {
            fails.add(new LabFail("alt", "ABOVE_NEEDED"));
        }
        if (labs.lvef() != null && labs.lvef() < LVEF_MIN) {
            fails.add(new LabFail("lvef", "BELOW_NEEDED"));
        }
        return fails;
    }

    /** "Counts and organ" fails: every failed lab except LVEF (the heart is a separate reason). */
    public static boolean countOrOrganFail(List<LabFail> fails) {
        return fails.stream().anyMatch(f -> !"lvef".equals(f.key()));
    }

    public static boolean lvefLow(List<LabFail> fails) {
        return fails.stream().anyMatch(f -> "lvef".equals(f.key()));
    }

    /** "2 out of range" or "Within limits". */
    public static String labsBadge(List<LabFail> fails) {
        return fails.isEmpty() ? "Within limits" : fails.size() + " out of range";
    }

    /** True when the labs were drawn at most 72 hours before now; null when the time is not recorded. */
    public static Boolean labsFresh(Instant drawnAt, Instant now) {
        if (drawnAt == null) {
            return null;
        }
        return Duration.between(drawnAt, now).compareTo(Duration.ofHours(LABS_FRESH_HOURS)) <= 0;
    }

    // ---- side effects, ECOG, line ----

    /** The worst confirmed grade, or null when none is confirmed. */
    public static Integer worstGrade(List<Integer> confirmedGrades) {
        Integer worst = null;
        for (Integer grade : confirmedGrades) {
            if (grade != null && (worst == null || grade > worst)) {
                worst = grade;
            }
        }
        return worst;
    }

    /**
     * Fever or admission event (mockup; ADR-023 H3): a confirmed grade of "Fever with low counts" of 1 or more, or the
     * events "Fever at 38 degrees C or above at home" or "Admitted". An ER visit is not counted.
     */
    public static boolean feverOrAdmissionEvent(Integer feverLowCountsGrade, Set<String> events) {
        return (feverLowCountsGrade != null && feverLowCountsGrade >= 1) || events.contains("FEVER_38_AT_HOME")
                || events.contains("ADMITTED");
    }

    /** "ECOG n · fit for chemo" or "ECOG n · usually not fit for chemo"; null when not chosen. */
    public static String ecogText(Integer grade) {
        if (grade == null) {
            return null;
        }
        return "ECOG " + grade + (grade <= ECOG_FIT_MAX ? " · fit for chemo" : " · usually not fit for chemo");
    }

    /**
     * Line ready for a vesicant: a port with "Flushes freely", "Blood return seen" and "No redness or swelling" ticked.
     * Null when no device is chosen. Another device is not ready as a vesicant line (the three checks are for a port).
     */
    public static Boolean lineReady(String device, Set<String> checks) {
        if (device == null) {
            return null;
        }
        return "PORT".equals(device) && checks.contains("FLUSHES_FREELY") && checks.contains("BLOOD_RETURN")
                && checks.contains("NO_REDNESS_SWELLING");
    }

    // ---- suggestion ----

    /** The facts the suggestion engine reads. */
    public record Facts(Integer ecog, boolean countOrOrganFail, boolean fever, String response, boolean lvefLow,
                        Integer worstGrade, boolean feverOrAdmissionEvent, Boolean lineReady,
                        boolean abnormalHeartExam, Integer lvef, List<LabFail> fails) {
    }

    /**
     * C-8, in order: STOP when ECOG is 3 or more; else DELAY_1_WEEK when a count or organ lab fails or there is fever;
     * else CHANGE_REGIMEN when the response is progressive or LVEF is below 50; else GIVE_REDUCED when the worst
     * confirmed grade is 3 or more or there is a fever or admission event; else GIVE_AS_PLANNED. Null until ECOG is chosen.
     */
    public static String suggestion(Facts f) {
        if (f.ecog() == null) {
            return null;
        }
        if (f.ecog() >= ECOG_STOP_MIN) {
            return "STOP";
        }
        if (f.countOrOrganFail() || f.fever()) {
            return "DELAY_1_WEEK";
        }
        if ("PROGRESSIVE_DISEASE".equals(f.response()) || f.lvefLow()) {
            return "CHANGE_REGIMEN";
        }
        if ((f.worstGrade() != null && f.worstGrade() >= GRADE_REDUCE_MIN) || f.feverOrAdmissionEvent()) {
            return "GIVE_REDUCED";
        }
        return "GIVE_AS_PLANNED";
    }

    /** One line of "why": tick or cross with its text. */
    public record Reason(String key, boolean ok, String text) {
    }

    /** The reasons list: ECOG, counts and fever, worst grade, response, heart, line ready for a vesicant. */
    public static List<Reason> reasons(Facts f) {
        List<Reason> reasons = new ArrayList<>();
        boolean ecogOk = f.ecog() != null && f.ecog() <= ECOG_FIT_MAX;
        reasons.add(new Reason("ECOG", ecogOk, f.ecog() == null ? "ECOG not chosen yet" : ecogText(f.ecog())));
        boolean countsOk = !f.countOrOrganFail() && !f.fever();
        String counts;
        if (countsOk) {
            counts = "Counts, organ tests OK · no fever";
        } else if (f.fever() && !f.countOrOrganFail()) {
            counts = "Fever · rule out infection before chemo";
        } else {
            counts = f.fails().stream().filter(x -> !"lvef".equals(x.key())).map(x -> labName(x.key())).reduce(
                    (a, b) -> a + ", " + b).orElse("") + " out of range" + (f.fever() ? " · fever" : "");
        }
        reasons.add(new Reason("COUNTS_FEVER", countsOk, counts));
        boolean gradeOk = (f.worstGrade() == null || f.worstGrade() < GRADE_REDUCE_MIN) && !f.feverOrAdmissionEvent();
        reasons.add(new Reason("WORST_GRADE", gradeOk, f.worstGrade() == null ? "No side effect grade confirmed yet"
                : "Worst side effect grade " + f.worstGrade() + (f.feverOrAdmissionEvent() ? " · fever or admission"
                : "")));
        boolean responseOk = !"PROGRESSIVE_DISEASE".equals(f.response());
        reasons.add(new Reason("RESPONSE", responseOk, responseText(f.response())));
        boolean heartOk = !f.lvefLow() && !f.abnormalHeartExam();
        reasons.add(new Reason("HEART", heartOk, f.lvefLow() ? "LVEF " + f.lvef() + "%: stop anthracycline"
                : f.abnormalHeartExam() ? "Abnormal heart exam: get echo"
                : f.lvef() == null ? "Heart: LVEF not recorded" : "Heart: LVEF " + f.lvef() + "%, exam normal"));
        boolean lineOk = !Boolean.FALSE.equals(f.lineReady());
        reasons.add(new Reason("LINE", lineOk, f.lineReady() == null ? "Line not recorded"
                : f.lineReady() ? "Line ready for vesicant" : "Port: no blood return yet"));
        return reasons;
    }

    public static String responseText(String response) {
        if (response == null) {
            return "Response not measured yet";
        }
        return switch (response) {
            case "COMPLETE_RESPONSE" -> "Complete response";
            case "PARTIAL_RESPONSE" -> "Partial response";
            case "PROGRESSIVE_DISEASE" -> "Progressive disease";
            default -> "Stable disease";
        };
    }

    public static String labName(String key) {
        return switch (key) {
            case "hb" -> "Haemoglobin";
            case "anc" -> "Neutrophils (ANC)";
            case "plt" -> "Platelets";
            case "cr" -> "Creatinine";
            case "bili" -> "Bilirubin";
            case "alt" -> "ALT (SGPT)";
            default -> "Heart pumping (LVEF)";
        };
    }

    /** The patient alerts "Patient alerts (n)". */
    public static List<String> alerts(Integer worstGrade, boolean anyLabFail, boolean fever, boolean recalculate,
                                      Double cumulativeMgM2, String device, Set<String> lineChecks) {
        List<String> alerts = new ArrayList<>();
        if (worstGrade != null && worstGrade >= GRADE_REDUCE_MIN) {
            alerts.add("GRADE_3_PLUS");
        }
        if (anyLabFail) {
            alerts.add("LAB_OUT_OF_RANGE");
        }
        if (fever) {
            alerts.add("FEVER");
        }
        if (recalculate) {
            alerts.add("WEIGHT_CHANGE_10");
        }
        if (cumulativeMgM2 != null && cumulativeMgM2 > CUMULATIVE_ALERT_MG_M2) {
            alerts.add("CUMULATIVE_OVER_400");
        }
        if ("PORT".equals(device) && !lineChecks.contains("BLOOD_RETURN")) {
            alerts.add("PORT_NO_BLOOD_RETURN");
        }
        return alerts;
    }

    // ---- next cycle ----

    /** The default next cycle: today + the protocol interval, or + 7 days when delayed; the blood test the day before. */
    public record NextCycle(LocalDate defaultDate, String label, LocalDate bloodTestDate, String echo) {
    }

    public static NextCycle nextCycle(LocalDate today, int intervalDays, boolean delayed, Integer lastLvef) {
        LocalDate date = today.plusDays(delayed ? DELAY_DAYS : intervalDays);
        String echo = lastLvef != null && lastLvef < ECHO_BEFORE_NEXT_CYCLE_BELOW ? "Before next cycle"
                : "Before paclitaxel";
        return new NextCycle(date, delayed ? "REBOOKED_CHEMO" : "NEXT_CHEMO", date.minusDays(1), echo);
    }

    // ---- helpers ----

    static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
