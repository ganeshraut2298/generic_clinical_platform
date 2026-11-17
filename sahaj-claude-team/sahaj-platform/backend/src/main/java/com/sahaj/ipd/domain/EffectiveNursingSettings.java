package com.sahaj.ipd.domain;

/**
 * SAH-76 (ADR-027 D8): the nursing values in force for one patient (ward profile, else clinic settings, else defaults).
 * Read once per request and passed into the pure rules.
 *
 * @param verificationRequired  new and changed orders need the pharmacist check
 * @param windowBeforeMin       a dose is DUE from this many minutes before its slot
 * @param windowAfterMin        a dose is DUE until this many minutes after its slot, then LATE
 * @param statWindowMin         a STAT dose is DUE for this many minutes from its slot, then LATE
 * @param unackMinutes          "not acknowledged" flag after this many minutes
 * @param unackStatMinutes      the same for a STAT order
 * @param undoSeconds           a given dose can be undone for this long (1 to 10)
 * @param vitalsIntervalMinutes vitals are due after this many minutes
 */
public record EffectiveNursingSettings(boolean verificationRequired, int windowBeforeMin, int windowAfterMin,
                                       int statWindowMin, int unackMinutes, int unackStatMinutes, int undoSeconds,
                                       int vitalsIntervalMinutes) {

    /** Built values (ADR-027 D8, OQ-76-15): verification On, 60/60 windows, STAT 15, flags 30/5, undo 10 s, vitals 240. */
    public static EffectiveNursingSettings defaults() {
        return new EffectiveNursingSettings(true, 60, 60, 15, 30, 5, 10, 240);
    }
}
