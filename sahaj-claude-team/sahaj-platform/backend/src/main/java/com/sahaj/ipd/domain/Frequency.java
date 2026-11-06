package com.sahaj.ipd.domain;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * SAH-31 how often a medicine is given (mockup SF_FREQ), in select order. The dose times (IST) are for the MAR of
 * SAH-32 ({@link DoseSlots}). The keys are also checked by {@code ck_medicine_order_frequency} in the database.
 */
public enum Frequency {

    OD("od", "Once daily", List.of("8:00 AM"), List.of(LocalTime.of(8, 0))),
    BD("bd", "Twice daily", List.of("8:00 AM", "8:00 PM"), List.of(LocalTime.of(8, 0), LocalTime.of(20, 0))),
    TDS("tds", "Three times daily", List.of("8:00 AM", "2:00 PM", "8:00 PM"),
            List.of(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0))),
    Q8("q8", "8-hourly", List.of("6:00 AM", "2:00 PM", "10:00 PM"),
            List.of(LocalTime.of(6, 0), LocalTime.of(14, 0), LocalTime.of(22, 0)));

    private final String key;
    private final String label;
    private final List<String> doseTimes;
    private final List<LocalTime> times;

    Frequency(String key, String label, List<String> doseTimes, List<LocalTime> times) {
        this.key = key;
        this.label = label;
        this.doseTimes = doseTimes;
        this.times = times;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The label in lower case, for the clinical event "Ordered <drug> <label>". */
    public String lowerLabel() {
        return label.toLowerCase(Locale.ROOT);
    }

    public List<String> doseTimes() {
        return doseTimes;
    }

    /** The dose times of a day as clock times in Asia/Kolkata, in time order (SAH-32, ADR-011 D2). */
    public List<LocalTime> times() {
        return times;
    }

    /** The frequency with this key, or empty for any other text (case matters). */
    public static Optional<Frequency> ofKey(String key) {
        for (Frequency f : values()) {
            if (f.key.equals(key)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
