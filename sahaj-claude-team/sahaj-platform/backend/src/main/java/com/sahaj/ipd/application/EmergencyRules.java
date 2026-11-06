package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionViews.BedOption;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-28 pure rules of the emergency queue: the fixed level names, the order of the queue, waiting minutes and "over
 * target", the choice of a bay, the mapping of levels when the triage scale changes and the suggested bed of the
 * Admit hand-off. No state, no database; everything is computed from what the caller passes in.
 */
final class EmergencyRules {

    /** The second level of either scale is the default on the form (AC3). */
    static final int DEFAULT_LEVEL = 2;
    /** Level 1 is the most urgent of either scale; only it is given a bay (AC4). */
    static final int MOST_URGENT = 1;
    static final int MAX_BAYS = 10;
    static final int MAX_TARGET = 240;
    static final int TARGET_STEP = 5;

    private static final String[] NAMES_3 = {"Red", "Yellow", "Green"};
    private static final String[] DESCRIPTIONS_3 = {"Immediate", "Urgent", "Can wait"};
    private static final String[] TONES_3 = {"crit", "warn", "good"};
    private static final String[] NAMES_5 = {"Level 1", "Level 2", "Level 3", "Level 4", "Level 5"};
    private static final String[] DESCRIPTIONS_5 = {"Resuscitation", "Emergent", "Urgent", "Less urgent", "Non-urgent"};
    private static final String[] TONES_5 = {"crit", "crit", "warn", "good", "good"};
    /** 3 colours to 5 levels, indexed by level - 1: Red to Level 2, Yellow to Level 3, Green to Level 5 (AC10). */
    private static final int[] THREE_TO_FIVE = {2, 3, 5};
    /** 5 levels to 3 colours, indexed by level - 1: Levels 1 and 2 to Red, 3 to Yellow, 4 and 5 to Green (AC10). */
    private static final int[] FIVE_TO_THREE = {1, 1, 2, 3, 3};

    private EmergencyRules() {
    }

    /** The levels of the setup's scale in order, with the target of each. */
    static List<Level> levels(EmergencySetup setup) {
        boolean five = setup.scale() == 5;
        String[] names = five ? NAMES_5 : NAMES_3;
        String[] descriptions = five ? DESCRIPTIONS_5 : DESCRIPTIONS_3;
        String[] tones = five ? TONES_5 : TONES_3;
        List<Integer> targets = setup.targets();
        List<Level> levels = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            levels.add(new Level(i + 1, names[i], descriptions[i], tones[i], targets.get(i)));
        }
        return List.copyOf(levels);
    }

    /** Most urgent first, then longest waiting (earliest arrival), then id. */
    static Comparator<CaseRow> queueOrder() {
        return Comparator.comparingInt(CaseRow::level).thenComparing(CaseRow::arrivedAt).thenComparing(CaseRow::id);
    }

    /** Whole minutes from arrival to now, rounded down, never below 0. */
    static int waitingMinutes(Instant arrivedAt, Instant now) {
        long minutes = Duration.between(arrivedAt, now).toMinutes();
        return (int) Math.max(0, Math.min(minutes, Integer.MAX_VALUE));
    }

    /** WAITING only, and waiting longer than the target (target 0: any wait of one minute or more). */
    static boolean overTarget(String status, int waitingMinutes, int targetMinutes) {
        return "WAITING".equals(status) && waitingMinutes > targetMinutes;
    }

    /** The target of a level in the scale in use (the level is clamped to the scale, see {@link #clampLevel}). */
    static int targetOf(EmergencySetup setup, int level) {
        return setup.targets().get(clampLevel(level, setup.scale()) - 1);
    }

    /**
     * A level inside 1 to the scale. A scale change maps every open case in its own transaction, so a stored level never
     * exceeds the committed scale; this is the second line of defence for a read that still pairs a level 4 or 5 with a
     * 3-colour setup (code review 1): the answer shows the lowest-urgency colour and never fails.
     */
    static int clampLevel(int level, int scale) {
        return Math.max(1, Math.min(level, scale));
    }

    /** The bay number of {@code ER-n}, or 0 when the text is not a bay. */
    static int bayNumber(String bay) {
        if (bay == null || !bay.startsWith("ER-")) {
            return 0;
        }
        try {
            return Integer.parseInt(bay.substring(3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The lowest free bay of ER-1 to ER-{@code bays}, given the open cases that hold a bay; null when none is free.
     * A case keeps its bay when the clinic lowers the number of bays, so a held bay above {@code bays} is ignored.
     */
    static String lowestFreeBay(int bays, List<CaseRow> open) {
        Set<Integer> held = heldBays(open);
        for (int n = 1; n <= Math.min(bays, MAX_BAYS); n++) {
            if (!held.contains(n)) {
                return "ER-" + n;
            }
        }
        return null;
    }

    /** Bays of ER-1 to ER-{@code bays} not held by an open case. */
    static int baysFree(int bays, List<CaseRow> open) {
        Set<Integer> held = heldBays(open);
        int free = 0;
        for (int n = 1; n <= Math.min(bays, MAX_BAYS); n++) {
            if (!held.contains(n)) {
                free++;
            }
        }
        return free;
    }

    private static Set<Integer> heldBays(List<CaseRow> open) {
        Set<Integer> held = new HashSet<>();
        for (CaseRow row : open) {
            if (row.open() && row.bay() != null) {
                held.add(bayNumber(row.bay()));
            }
        }
        return held;
    }

    /** The level after a change of scale (AC10). */
    static int mapLevel(int level, int fromScale, int toScale) {
        if (fromScale == toScale) {
            return level;
        }
        return toScale == 5 ? THREE_TO_FIVE[level - 1] : FIVE_TO_THREE[level - 1];
    }

    /** "Unknown male, about 40"; "female" for F, "person" for O; "?" for an empty age. */
    static String unknownName(String sex, Integer age) {
        String word = "M".equals(sex) ? "male" : "F".equals(sex) ? "female" : "person";
        return "Unknown " + word + ", about " + (age == null ? "?" : age.toString());
    }

    /**
     * A whole number from {@code min} to {@code max}, or null when the value is missing, has a fraction, or is out
     * of range. A value such as 40.0 is whole.
     */
    static Integer whole(BigDecimal value, int min, int max) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0) {
            return null;
        }
        if (stripped.compareTo(BigDecimal.valueOf(min)) < 0 || stripped.compareTo(BigDecimal.valueOf(max)) > 0) {
            return null;
        }
        return stripped.intValueExact();
    }

    /**
     * A whole number from {@code min} to {@code max} read from the JSON as it arrived: null when the node is missing,
     * null, not a JSON number (a string such as "40" is never converted), fractional or out of range.
     */
    static Integer wholeNode(JsonNode node, int min, int max) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return whole(node.decimalValue(), min, max);
    }

    /**
     * The bed to preselect on the Admit sheet: for the most urgent level the first Available ICU bed, otherwise the
     * first Available General bed; when that category has none, the first Available bed of any category (mockup
     * openAdmit); null when no bed is Available. {@code free} is in the order of AdmissionOptions.beds.
     */
    static UUID suggestedBed(int level, List<BedOption> free) {
        String wanted = level == MOST_URGENT ? "icu" : "gen";
        for (BedOption bed : free) {
            if (wanted.equals(bed.categoryKey())) {
                return bed.id();
            }
        }
        return free.isEmpty() ? null : free.get(0).id();
    }
}
