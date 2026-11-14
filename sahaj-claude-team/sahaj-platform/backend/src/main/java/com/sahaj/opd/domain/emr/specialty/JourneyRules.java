package com.sahaj.opd.domain.emr.specialty;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Cancer journey form and the plan chips of the oncology strip (SAH-75-7; ADR-026 D7). Every list, limit and label is
 * "value from the EMR mockup, not clinically reviewed" (ADR-026 H6, H7): the form is ours, not drawn, and waits for the
 * human to approve it. Pure: no database, no clock.
 */
public final class JourneyRules {

    /** The stage groups the form offers; anything else is 400 validation. */
    public static final List<String> STAGE_GROUPS = List.of("0", "I", "IA", "IB", "IC", "II", "IIA", "IIB", "IIC", "III",
            "IIIA", "IIIB", "IIIC", "IV", "IVA", "IVB", "IVC");
    public static final List<String> INTENTS = List.of("CURATIVE", "PALLIATIVE");
    public static final List<String> SETTINGS = List.of("NEOADJUVANT", "ADJUVANT", "DEFINITIVE", "ADVANCED");
    public static final int TNM_MAX = 40;
    public static final int MARKERS_MAX = 80;
    public static final int GENETICS_MAX = 60;
    public static final int KI67_MAX = 100;
    public static final int LINE_MAX = 10;
    /** The earliest tumour board date and how far ahead one may be set (days after today, Asia/Kolkata). */
    public static final LocalDate BOARD_EARLIEST = LocalDate.of(1900, 1, 1);
    public static final int BOARD_DAYS_AHEAD = 365;

    /** The doxorubicin lifetime limit of the watch chip (mg/m2; rule C-8, tagged). */
    public static final int DOXORUBICIN_LIMIT_MGM2 = 450;

    /** The chip label of a protocol: AC, P for both paclitaxel protocols, TC; other keys use their own key. */
    private static final Map<String, String> CHIP_LABELS = Map.of("AC", "AC", "PACLI_3W", "P", "PACLI_WEEKLY", "P",
            "TC", "TC");
    /** The word of a protocol in the plan title ("AC x4 to Paclitaxel x4"). */
    private static final Map<String, String> TITLE_LABELS = Map.of("AC", "AC", "PACLI_3W", "Paclitaxel",
            "PACLI_WEEKLY", "Paclitaxel", "TC", "TC");

    private JourneyRules() {
    }

    /** True when the board date is in the allowed range (not before 1900, not more than 365 days after {@code today}). */
    public static boolean boardDateOk(LocalDate date, LocalDate today) {
        return !date.isBefore(BOARD_EARLIEST) && !date.isAfter(today.plusDays(BOARD_DAYS_AHEAD));
    }

    public static String chipLabel(String regimenKey) {
        return CHIP_LABELS.getOrDefault(regimenKey, regimenKey);
    }

    public static String titleLabel(String regimenKey) {
        return TITLE_LABELS.getOrDefault(regimenKey, regimenKey);
    }

    /**
     * The chip labels of the cycles: the regimen's short label and the number of that regimen so far ("AC1", "AC2", "P1"),
     * or "C" and the cycle's own number when it has no regimen. Short labels are capped at 8 characters. {@code regimens}
     * holds one entry per cycle, in order (null for none).
     */
    public static List<String> cycleLabels(List<String> regimens) {
        List<String> labels = new ArrayList<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < regimens.size(); i++) {
            String regimen = regimens.get(i);
            if (regimen == null) {
                labels.add("C" + (i + 1));
                continue;
            }
            String chip = chipLabel(regimen);
            int ordinal = seen.merge(chip, 1, Integer::sum);
            String label = chip + ordinal;
            labels.add(label.length() > 8 ? label.substring(0, 8) : label);
        }
        return labels;
    }

    /**
     * The plan title: runs of consecutive cycles with the same regimen word ("AC ×4 → Paclitaxel ×4"); cycles with no
     * regimen end a run and are left out; null when no cycle has a regimen (the caller shows the course name).
     */
    public static String planTitle(List<String> regimens) {
        List<String> parts = new ArrayList<>();
        String run = null;
        int count = 0;
        for (String regimen : regimens) {
            String word = regimen == null ? null : titleLabel(regimen);
            if (word != null && word.equals(run)) {
                count++;
                continue;
            }
            if (run != null) {
                parts.add(run + " ×" + count);
            }
            run = word;
            count = word == null ? 0 : 1;
        }
        if (run != null) {
            parts.add(run + " ×" + count);
        }
        return parts.isEmpty() ? null : String.join(" → ", parts);
    }
}
