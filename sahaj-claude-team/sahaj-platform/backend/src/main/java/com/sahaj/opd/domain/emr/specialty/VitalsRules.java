package com.sahaj.opd.domain.emr.specialty;

import java.util.ArrayList;
import java.util.List;

/**
 * Vitals flags (SAH-60-1, SAH-65-2; rules C-1 and C-8). Every number is "value from the EMR mockup, accepted by the
 * human in DEC-039, not clinically reviewed" ({@link RuleTag#TAG}). General Medicine uses adult limits in degrees
 * Fahrenheit; Oncology uses its own limits in degrees Celsius. Paediatrics never uses these adult limits.
 */
public final class VitalsRules {

    /** C-1: systolic at or above this is High. */
    public static final int GM_SYSTOLIC_HIGH = 140;
    /** C-1: diastolic at or above this is High. */
    public static final int GM_DIASTOLIC_HIGH = 90;
    /** C-1: systolic below this is Low. */
    public static final int GM_SYSTOLIC_LOW = 90;
    /** C-1: pulse above this is High. */
    public static final int GM_PULSE_HIGH = 100;
    /** C-1: pulse below this is Low. */
    public static final int GM_PULSE_LOW = 60;
    /** C-1: SpO2 below this is Low (the BRD said 94; the mockup is used, SO-45-11). */
    public static final int GM_SPO2_LOW = 95;
    /** C-1: temperature at or above this (degrees F) is Fever (the BRD said 100; the mockup is used). */
    public static final double GM_FEVER_F = 99.5;
    /** C-1: temperature below this (degrees F) is Low. */
    public static final double GM_TEMP_LOW_F = 96.0;
    /** C-1: respiratory rate above this is High. */
    public static final int GM_RESP_HIGH = 20;

    /** C-1, C-8: Oncology systolic at or above this is "Check again". */
    public static final int ONC_SYSTOLIC_CHECK_HIGH = 160;
    /** C-8: Oncology systolic below this is "Check again". */
    public static final int ONC_SYSTOLIC_CHECK_LOW = 90;
    /** C-8: Oncology diastolic at or above this is High. */
    public static final int ONC_DIASTOLIC_HIGH = 100;
    /** C-8: Oncology pulse above this is "Fast". */
    public static final int ONC_PULSE_FAST = 100;
    /** C-8: Oncology SpO2 below this is Low. */
    public static final int ONC_SPO2_LOW = 94;
    /** C-1, C-8: fever is this temperature (degrees C) or above. */
    public static final double ONC_FEVER_C = 38.0;

    private VitalsRules() {
    }

    /** One reading of the visit; any value may be null (not recorded). */
    public record Reading(Integer systolic, Integer diastolic, Integer pulse, Integer spo2, Double temperature,
                          Integer respRate) {
    }

    /** General Medicine flags (temperature in degrees F). */
    public static List<Flag> generalMedicine(Reading r) {
        List<Flag> flags = new ArrayList<>();
        if (r.systolic() != null && r.systolic() < GM_SYSTOLIC_LOW) {
            flags.add(new Flag("bp", "Low", true));
        } else if ((r.systolic() != null && r.systolic() >= GM_SYSTOLIC_HIGH)
                || (r.diastolic() != null && r.diastolic() >= GM_DIASTOLIC_HIGH)) {
            flags.add(new Flag("bp", "High", true));
        }
        if (r.pulse() != null && r.pulse() > GM_PULSE_HIGH) {
            flags.add(new Flag("pulse", "High", true));
        } else if (r.pulse() != null && r.pulse() < GM_PULSE_LOW) {
            flags.add(new Flag("pulse", "Low", true));
        }
        if (r.spo2() != null && r.spo2() < GM_SPO2_LOW) {
            flags.add(new Flag("spo2", "Low", true));
        }
        if (r.temperature() != null && r.temperature() >= GM_FEVER_F) {
            flags.add(new Flag("temperature", "Fever", true));
        } else if (r.temperature() != null && r.temperature() < GM_TEMP_LOW_F) {
            flags.add(new Flag("temperature", "Low", true));
        }
        if (r.respRate() != null && r.respRate() > GM_RESP_HIGH) {
            flags.add(new Flag("respRate", "High", true));
        }
        return flags;
    }

    /** Oncology flags (temperature in degrees C). */
    public static List<Flag> oncology(Reading r) {
        List<Flag> flags = new ArrayList<>();
        if (r.systolic() != null
                && (r.systolic() >= ONC_SYSTOLIC_CHECK_HIGH || r.systolic() < ONC_SYSTOLIC_CHECK_LOW)) {
            flags.add(new Flag("bp", "Check again", true));
        } else if (r.diastolic() != null && r.diastolic() >= ONC_DIASTOLIC_HIGH) {
            flags.add(new Flag("bp", "High", true));
        }
        if (r.pulse() != null && r.pulse() > ONC_PULSE_FAST) {
            flags.add(new Flag("pulse", "Fast · check for fever, anaemia, heart", true));
        }
        if (r.temperature() != null && r.temperature() >= ONC_FEVER_C) {
            flags.add(new Flag("temperature", "Fever · rule out infection before chemo", true));
        }
        if (r.spo2() != null && r.spo2() < ONC_SPO2_LOW) {
            flags.add(new Flag("spo2", "Low · examine lungs", true));
        }
        return flags;
    }

    /** Fever by the Oncology rule: 38 degrees C or above. */
    public static boolean oncologyFever(Double temperatureC) {
        return temperatureC != null && temperatureC >= ONC_FEVER_C;
    }
}
