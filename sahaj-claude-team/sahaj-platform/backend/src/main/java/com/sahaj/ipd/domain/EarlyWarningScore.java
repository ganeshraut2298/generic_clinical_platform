package com.sahaj.ipd.domain;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * The early warning score of one set of vitals (SAH-30, ADR-006 D3): the sum of five parameter scores, each 0 to 3,
 * and the risk worked out from it. No Spring and no I/O; the card story reuses it.
 *
 * <p><b>Clinical warning:</b> the bands below are those of the mockup's {@code pcEws}. They are not a published scale
 * and have not been clinically validated for Sahaj (OQ-30-6, blocking for go-live). A change is a reviewed code change
 * with a test, never a data edit.
 *
 * <p>Temperature is an {@code int} in tenths of a degree (370 is 37.0 C), so 35.0 and 35.1 compare exactly with no
 * binary floating point. The diastolic pressure is stored by the caller but is not scored.
 *
 * @param spo2Score     SpO2: 91 or less 3; 92 to 93 2; 94 to 95 1; above 95 0
 * @param rrScore       RR: 8 or less or 25 or more 3; 21 to 24 2; 9 to 11 1; 12 to 20 0
 * @param pulseScore    pulse: 40 or less or 131 or more 3; 111 to 130 2; 41 to 50 or 91 to 110 1; 51 to 90 0
 * @param tempScore     temperature: 35.0 or less 3; 39.1 or more 2; 35.1 to 36.0 or 38.1 to 39.0 1; 36.1 to 38.0 0
 * @param systolicScore systolic BP: 90 or less or 220 or more 3; 91 to 100 2; 101 to 110 1; 111 to 219 0
 */
public record EarlyWarningScore(int spo2Score, int rrScore, int pulseScore, int tempScore, int systolicScore) {

    /** The risk of a score. The lower-case word is written in toasts and in the clinical event log. */
    public enum Risk {
        LOW, MEDIUM, HIGH;

        /** "low", "medium" or "high". */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Doctor review target for a high score. */
    public static final int HIGH_TARGET_MINUTES = 15;
    /** Doctor review target for a medium score. */
    public static final int MEDIUM_TARGET_MINUTES = 60;

    /** Scores the five values. Pulse, SpO2 and RR are whole numbers, temperature is in tenths of a degree. */
    public static EarlyWarningScore of(int systolic, int pulse, int spo2, int tempTenths, int rr) {
        return new EarlyWarningScore(spo2Score(spo2), rrScore(rr), pulseScore(pulse), tempScore(tempTenths),
                systolicScore(systolic));
    }

    /** The sum of the five parameter scores, 0 to 15. */
    public int total() {
        return spo2Score + rrScore + pulseScore + tempScore + systolicScore;
    }

    /** HIGH from a total of 7; MEDIUM from a total of 5 or when any one parameter scores 3; otherwise LOW. */
    public Risk risk() {
        int total = total();
        if (total >= 7) {
            return Risk.HIGH;
        }
        if (total >= 5 || spo2Score == 3 || rrScore == 3 || pulseScore == 3 || tempScore == 3 || systolicScore == 3) {
            return Risk.MEDIUM;
        }
        return Risk.LOW;
    }

    /** Minutes for the doctor to review: HIGH 15, MEDIUM 60, LOW none. */
    public static OptionalInt targetMinutes(Risk risk) {
        switch (risk) {
            case HIGH:
                return OptionalInt.of(HIGH_TARGET_MINUTES);
            case MEDIUM:
                return OptionalInt.of(MEDIUM_TARGET_MINUTES);
            default:
                return OptionalInt.empty();
        }
    }

    static int spo2Score(int spo2) {
        if (spo2 <= 91) {
            return 3;
        }
        if (spo2 <= 93) {
            return 2;
        }
        return spo2 <= 95 ? 1 : 0;
    }

    static int rrScore(int rr) {
        if (rr <= 8 || rr >= 25) {
            return 3;
        }
        if (rr >= 21) {
            return 2;
        }
        return rr <= 11 ? 1 : 0;
    }

    static int pulseScore(int pulse) {
        if (pulse <= 40 || pulse >= 131) {
            return 3;
        }
        if (pulse >= 111) {
            return 2;
        }
        return pulse <= 50 || pulse >= 91 ? 1 : 0;
    }

    static int tempScore(int tempTenths) {
        if (tempTenths <= 350) {
            return 3;
        }
        if (tempTenths >= 391) {
            return 2;
        }
        return tempTenths <= 360 || tempTenths >= 381 ? 1 : 0;
    }

    static int systolicScore(int systolic) {
        if (systolic <= 90 || systolic >= 220) {
            return 3;
        }
        if (systolic <= 100) {
            return 2;
        }
        return systolic <= 110 ? 1 : 0;
    }
}
