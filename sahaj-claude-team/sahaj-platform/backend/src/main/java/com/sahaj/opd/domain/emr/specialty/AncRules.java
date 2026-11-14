package com.sahaj.opd.domain.emr.specialty;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Obstetrics antenatal rules (SAH-61-1, SAH-61-2, SAH-61-4; rules C-4 and L-2). Every number is "value from the EMR
 * mockup, accepted by the human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}). Advice only: nothing here
 * blocks a save or a completion.
 */
public final class AncRules {

    /** C-4: the expected delivery date is the LMP plus this many days (Naegele). */
    public static final int NAEGELE_DAYS = 280;
    /** C-4: systolic at or above this is "Possible pre-eclampsia". */
    public static final int BP_SYSTOLIC_HIGH = 140;
    /** C-4: diastolic at or above this is "Possible pre-eclampsia". */
    public static final int BP_DIASTOLIC_HIGH = 90;
    /** C-4: Hb below this is mild anaemia. */
    public static final double HB_MILD_BELOW = 11.0;
    /** C-4: Hb below this is moderate anaemia. */
    public static final double HB_MODERATE_BELOW = 10.0;
    /** C-4: Hb below this is severe anaemia. */
    public static final double HB_SEVERE_BELOW = 7.0;
    /** C-4: a fundal height that differs from the weeks by more than this does not match. */
    public static final int FUNDAL_TOLERANCE_WEEKS = 2;
    /** C-4: fetal heart rate lower limit (inclusive). */
    public static final int FHR_MIN = 110;
    /** C-4: fetal heart rate upper limit (inclusive). */
    public static final int FHR_MAX = 160;
    /** Longest gestation shown (days); an EDD far in the past gives "Not recorded". */
    private static final int MAX_GA_DAYS = 301;

    /** C-4: the scan windows in days of gestation: dating 6-9 w, NT 11-13+6 w, anomaly 18-22 w, growth 28-32 w, from 36 w. */
    public static final List<ScanWindow> SCAN_WINDOWS = List.of(
            new ScanWindow("DATING", 6 * 7, 9 * 7, "6–9 w"),
            new ScanWindow("NT", 11 * 7, 13 * 7 + 6, "11–13+6 w"),
            new ScanWindow("ANOMALY", 18 * 7, 22 * 7, "18–22 w"),
            new ScanWindow("GROWTH", 28 * 7, 32 * 7, "28–32 w"),
            new ScanWindow("DOPPLER_NST", 36 * 7, Integer.MAX_VALUE, "36 w +"));

    /** C-4: the due orders: OGTT 24-28 w, Td 2nd dose, growth scan with Form F 28-32 w. */
    public static final List<DueOrder> DUE_ORDERS = List.of(
            new DueOrder("OGTT_75G", "OGTT (75 g)", "24–28 w"),
            new DueOrder("TD_2ND_DOSE", "Td 2nd dose", "give today"),
            new DueOrder("GROWTH_SCAN_FORM_F", "Growth scan + Form F", "28–32 w"));

    /** C-4: the advice text by anaemia grade. */
    public static final Map<String, String> ANAEMIA_ADVICE = RuleTag.ordered(
            "ANAEMIA_MILD", "Continue oral iron",
            "ANAEMIA_MODERATE", "Consider IV iron",
            "ANAEMIA_SEVERE", "Needs urgent evaluation");

    /** C-4: the symptoms asked. Headache and blurred vision are danger symptoms; the other two are warning signs. */
    public static final List<String> SYMPTOMS = List.of("HEADACHE", "BLURRED_VISION", "BLEEDING_LEAKING",
            "REDUCED_MOVEMENTS");
    private static final Set<String> WARNING_SYMPTOMS = Set.of("BLEEDING_LEAKING", "REDUCED_MOVEMENTS");

    private AncRules() {
    }

    public record ScanWindow(String key, int fromDays, int toDays, String label) {
    }

    public record DueOrder(String key, String label, String window) {
    }

    /** Gestational age: whole weeks and the days left over. */
    public record Ga(int weeks, int days) {

        public int totalDays() {
            return weeks * 7 + days;
        }
    }

    /** What the screen shows about the pregnancy dates. */
    public record Dating(LocalDate edd, String source, Ga ga, Integer daysToEdd) {
    }

    /** One of today's values; any may be null. */
    public record Today(Double weightKg, Integer systolic, Integer diastolic, Integer fundalHeightCm,
                        Integer fetalHeartBpm, Double hb) {
    }

    /** C-4 Naegele: LMP + 280 days. */
    public static LocalDate naegele(LocalDate lmp) {
        return lmp == null ? null : lmp.plusDays(NAEGELE_DAYS);
    }

    /** A dating-scan EDD wins over the LMP. Source LMP, DATING_SCAN or NONE. */
    public static Dating dating(LocalDate lmp, LocalDate datingScanEdd, LocalDate today) {
        LocalDate edd;
        String source;
        if (datingScanEdd != null) {
            edd = datingScanEdd;
            source = "DATING_SCAN";
        } else if (lmp != null) {
            edd = naegele(lmp);
            source = "LMP";
        } else {
            return new Dating(null, "NONE", null, null);
        }
        long daysToEdd = ChronoUnit.DAYS.between(today, edd);
        long gaDays = NAEGELE_DAYS - daysToEdd;
        Ga ga = gaDays < 0 || gaDays > MAX_GA_DAYS ? null : new Ga((int) (gaDays / 7), (int) (gaDays % 7));
        return new Dating(edd, source, ga, (int) daysToEdd);
    }

    /** The "Possible pre-eclampsia" test: 140 systolic or 90 diastolic and above. */
    public static boolean highBp(Integer systolic, Integer diastolic) {
        return (systolic != null && systolic >= BP_SYSTOLIC_HIGH) || (diastolic != null && diastolic >= BP_DIASTOLIC_HIGH);
    }

    /** MILD, MODERATE, SEVERE or null (normal or not recorded). */
    public static String anaemiaGrade(Double hb) {
        if (hb == null || hb >= HB_MILD_BELOW) {
            return null;
        }
        if (hb < HB_SEVERE_BELOW) {
            return "SEVERE";
        }
        return hb < HB_MODERATE_BELOW ? "MODERATE" : "MILD";
    }

    /** True when the fundal height differs from the gestational weeks by more than 2 (needs a gestational age). */
    public static boolean fundalMismatch(Integer fundalCm, Ga ga) {
        return fundalCm != null && ga != null && Math.abs(fundalCm - ga.weeks()) > FUNDAL_TOLERANCE_WEEKS;
    }

    public static boolean fhrOutside(Integer bpm) {
        return bpm != null && (bpm < FHR_MIN || bpm > FHR_MAX);
    }

    /** The notes next to today's values (BP, fundal height, fetal heart, Hb); a value not recorded gives no note. */
    public static List<Flag> notes(Today today, Ga ga) {
        List<Flag> notes = new ArrayList<>();
        if (today.systolic() != null || today.diastolic() != null) {
            boolean high = highBp(today.systolic(), today.diastolic());
            notes.add(new Flag("bp", high ? "High: 140/90 or above" : "Normal", high));
        }
        if (today.fundalHeightCm() != null) {
            if (ga == null) {
                notes.add(new Flag("fundal", "Needs LMP or dating scan", false));
            } else if (fundalMismatch(today.fundalHeightCm(), ga)) {
                notes.add(new Flag("fundal", "Does not match " + ga.weeks() + " weeks", true));
            } else {
                notes.add(new Flag("fundal", "Matches gestational age", false));
            }
        }
        if (today.fetalHeartBpm() != null) {
            boolean outside = fhrOutside(today.fetalHeartBpm());
            notes.add(new Flag("fhr", outside ? "Outside 110–160" : "Normal (110–160)", outside));
        }
        if (today.hb() != null) {
            String grade = anaemiaGrade(today.hb());
            notes.add(new Flag("hb", grade == null ? "Normal" : capital(grade) + " anaemia", grade != null));
        }
        return notes;
    }

    /** The advice alerts: possible pre-eclampsia, anaemia grade, warning sign. Never a block. */
    public static List<String> alerts(Today today, List<String> symptoms) {
        List<String> alerts = new ArrayList<>();
        if (highBp(today.systolic(), today.diastolic())) {
            alerts.add("POSSIBLE_PRE_ECLAMPSIA");
        }
        String grade = anaemiaGrade(today.hb());
        if (grade != null) {
            alerts.add("ANAEMIA_" + grade);
        }
        if (symptoms != null && symptoms.stream().anyMatch(WARNING_SYMPTOMS::contains)) {
            alerts.add("WARNING_SIGN");
        }
        return alerts;
    }

    /** DONE when marked done, DUE when the gestational age is in or past the window, else LATER. */
    public static String scanState(ScanWindow window, boolean done, Ga ga) {
        if (done) {
            return "DONE";
        }
        return ga != null && ga.totalDays() >= window.fromDays() ? "DUE" : "LATER";
    }

    public static ScanWindow scanWindow(String key) {
        return SCAN_WINDOWS.stream().filter(w -> w.key().equals(key)).findFirst().orElse(null);
    }

    /** "High risk: previous LSCS" chip (C-4). */
    public static List<Flag> riskChips(boolean previousLscs) {
        List<Flag> chips = new ArrayList<>();
        if (previousLscs) {
            chips.add(new Flag("previous_lscs", "High risk: previous LSCS", true));
        }
        return chips;
    }

    private static String capital(String grade) {
        return grade.charAt(0) + grade.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
