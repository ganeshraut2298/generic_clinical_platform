package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.EarlyWarningScore;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SAH-37 (DEC-027 point 5, OQ-37-11, ADR-015 D6) the mark of a vital tile on the patient card, taken from the SAH-30
 * per-parameter points of {@link EarlyWarningScore} and never from a new limit: 3 points is CRITICAL, 1 or 2 points is
 * CHECK, 0 points has no mark. Blood pressure is marked by the systolic points only (the diastolic is not scored). The
 * clinical sign-off of the score bands (OQ-30-6) covers it. Pure: no Spring, no I/O.
 */
public final class VitalMarks {

    /** The words "Critical" and "Check" are drawn by the card; colour is never the only signal. */
    public enum Mark {
        CHECK, CRITICAL
    }

    /** The marks of one set of readings; a null mark means 0 points (no mark). */
    public record Marks(Mark bp, Mark pulse, Mark spo2, Mark temp, Mark rr) {
    }

    private VitalMarks() {
    }

    /** The mark of a parameter score (0 to 3): CRITICAL for 3, CHECK for 1 or 2, none (null) for 0. */
    public static Mark ofPoints(int points) {
        if (points >= 3) {
            return Mark.CRITICAL;
        }
        return points >= 1 ? Mark.CHECK : null;
    }

    /** Scores the stored readings with the SAH-30 function and maps the five parameter scores. */
    public static Marks of(int systolic, int pulse, int spo2, BigDecimal tempC, int rr) {
        int tempTenths = tempC.movePointRight(1).setScale(0, RoundingMode.HALF_UP).intValue();
        EarlyWarningScore score = EarlyWarningScore.of(systolic, pulse, spo2, tempTenths, rr);
        return new Marks(ofPoints(score.systolicScore()), ofPoints(score.pulseScore()),
                ofPoints(score.spo2Score()), ofPoints(score.tempScore()), ofPoints(score.rrScore()));
    }
}
