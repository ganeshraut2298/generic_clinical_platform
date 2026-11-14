package com.sahaj.opd.domain.emr.specialty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Paediatrics rules (SAH-62-1, SAH-62-2, SAH-62-3; rule C-5). Every number is "value from the EMR mockup, accepted by the
 * human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}). Adult vitals limits are never applied here.
 */
public final class PaedsRules {

    /** The text shown when no growth band holds the child's age (OQ-45-32: only 18 months is drawn). */
    public static final String CENTILE_NOT_AVAILABLE = "Centile not available for this age";

    /**
     * ADR-023 H8, DEC-044: the length and head-circumference centile notes of the mockup are fixed text, independent of
     * the value. They are hidden until real WHO tables exist (a later story); the measured values and the weight note
     * stay. Only these measures give a centile note.
     */
    public static final Set<String> CENTILE_MEASURES = Set.of("WEIGHT");

    private PaedsRules() {
    }

    // ---- dose by weight (C-5) ----

    /** C-5: paracetamol 15 mg/kg, at most 500 mg (120 mg/5 ml = 24 mg/ml, 250 mg/5 ml = 50 mg/ml); ibuprofen 10 mg/kg, at most 400 mg (20 mg/ml). */
    public static final List<DoseRule> DOSE_RULES = List.of(
            new DoseRule("PARACETAMOL_120_5", "Paracetamol syrup 120 mg / 5 ml", "Fever or pain", 15, 500, 24,
                    "every 6 hours if needed, at most 4 doses a day"),
            new DoseRule("PARACETAMOL_250_5", "Paracetamol syrup 250 mg / 5 ml", "Fever or pain, stronger syrup", 15,
                    500, 50, "every 6 hours if needed, at most 4 doses a day"),
            new DoseRule("IBUPROFEN_100_5", "Ibuprofen syrup 100 mg / 5 ml", "Fever or pain, with food", 10, 400, 20,
                    "every 8 hours with food, at most 3 doses a day"));

    public record DoseRule(String key, String name, String sub, int mgPerKg, int maxMg, int mgPerMl,
                           String frequencyText) {
    }

    /** One dose: whole milligrams and millilitres to one decimal; {@code capped} when the maximum was applied. */
    public record Dose(int mg, double ml, boolean capped) {
    }

    public static DoseRule doseRule(String key) {
        return DOSE_RULES.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }

    /** mg = min(max mg, round(weight x mg per kg)); ml = round(mg / mg per ml, 1 decimal). No weight gives no dose. */
    public static Dose dose(DoseRule rule, Double weightKg) {
        if (rule == null || weightKg == null || weightKg <= 0) {
            return null;
        }
        BigDecimal raw = BigDecimal.valueOf(weightKg).multiply(BigDecimal.valueOf(rule.mgPerKg()))
                .setScale(0, RoundingMode.HALF_UP);
        boolean capped = raw.intValue() > rule.maxMg();
        int mg = Math.min(rule.maxMg(), raw.intValue());
        double ml = BigDecimal.valueOf(mg).divide(BigDecimal.valueOf(rule.mgPerMl()), 1, RoundingMode.HALF_UP)
                .doubleValue();
        return new Dose(mg, ml, capped);
    }

    // ---- growth (C-5) ----

    /** One reference band (global table): the first band whose upper bound is above the value gives the note. */
    public record GrowthBand(String measure, String sex, int ageMonthsFrom, int ageMonthsTo, Double upperExclusive,
                             String label, boolean alert, int position) {
    }

    /**
     * The centile note of a measure: the first band (by position) of the measure whose age range holds the age and
     * whose upper bound is above the value. No band gives "Centile not available for this age". Only the weight note is
     * given (H8, DEC-044); length and head circumference give nothing.
     */
    public static Optional<Flag> growthNote(String measure, String sex, Integer ageMonths, Double value,
                                            List<GrowthBand> bands) {
        if (!CENTILE_MEASURES.contains(measure) || value == null) {
            return Optional.empty();
        }
        if (ageMonths == null) {
            return Optional.of(new Flag(field(measure), CENTILE_NOT_AVAILABLE, false));
        }
        List<GrowthBand> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(GrowthBand::position));
        for (GrowthBand band : sorted) {
            boolean ageHolds = ageMonths >= band.ageMonthsFrom() && ageMonths <= band.ageMonthsTo();
            boolean sexHolds = band.sex() == null || band.sex().equals(sex);
            if (band.measure().equals(measure) && ageHolds && sexHolds
                    && (band.upperExclusive() == null || value < band.upperExclusive())) {
                return Optional.of(new Flag(field(measure), band.label(), band.alert()));
            }
        }
        return Optional.of(new Flag(field(measure), CENTILE_NOT_AVAILABLE, false));
    }

    private static String field(String measure) {
        return switch (measure) {
            case "WEIGHT" -> "weightKg";
            case "LENGTH" -> "lengthCm";
            default -> "headCircCm";
        };
    }

    // ---- immunisation (C-5, H9) ----

    /** One IAP group with its window in the child's age (end exclusive). Units DAYS, WEEKS or MONTHS. */
    public record Group(String key, String label, int position, int fromValue, String fromUnit, int toValue,
                        String toUnit) {

        public LocalDate from(LocalDate dateOfBirth) {
            return shift(dateOfBirth, fromValue, fromUnit);
        }

        public LocalDate to(LocalDate dateOfBirth) {
            return shift(dateOfBirth, toValue, toUnit);
        }
    }

    public record Vaccine(String key, String groupKey, String label, int position) {
    }

    private static LocalDate shift(LocalDate dob, int value, String unit) {
        return switch (unit) {
            case "WEEKS" -> dob.plusWeeks(value);
            case "MONTHS" -> dob.plusMonths(value);
            default -> dob.plusDays(value);
        };
    }

    /** A vaccine's state today: GIVEN, DUE_TODAY, OVERDUE or LATER. */
    public record VaccineState(String vaccineKey, String state) {
    }

    /**
     * Given = given in this record or any earlier record. Due today = the group's window holds today's age and not
     * given; overdue = the group's window ended before today and not given; otherwise later. Without a date of birth
     * nothing is due or overdue (everything not given is later).
     */
    public static List<VaccineState> vaccineStates(List<Group> groups, List<Vaccine> vaccines, Set<String> given,
                                                   LocalDate dateOfBirth, LocalDate today) {
        List<VaccineState> states = new ArrayList<>();
        for (Vaccine vaccine : vaccines) {
            Group group = groups.stream().filter(g -> g.key().equals(vaccine.groupKey())).findFirst().orElse(null);
            String state;
            if (given.contains(vaccine.key())) {
                state = "GIVEN";
            } else if (group == null || dateOfBirth == null || dateOfBirth.isAfter(today)) {
                state = "LATER";
            } else if (!today.isBefore(group.to(dateOfBirth))) {
                state = "OVERDUE";
            } else if (!today.isBefore(group.from(dateOfBirth))) {
                state = "DUE_TODAY";
            } else {
                state = "LATER";
            }
            states.add(new VaccineState(vaccine.key(), state));
        }
        return states;
    }

    /** Only DUE_TODAY or OVERDUE may be given in this visit (400 otherwise). */
    public static boolean mayGive(String state) {
        return "DUE_TODAY".equals(state) || "OVERDUE".equals(state);
    }
}
