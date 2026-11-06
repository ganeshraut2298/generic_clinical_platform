package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SAH-30 AC6: the bands of the mockup's {@code pcEws}, tested on both sides of every edge. Temperature is in tenths
 * of a degree. The bands are not clinically validated (OQ-30-6).
 */
class EarlyWarningScoreTest {

    // ---- SpO2: 91 or less 3, 92 to 93 2, 94 to 95 1, above 95 0 ----

    @ParameterizedTest(name = "SpO2 {0} scores {1}")
    @CsvSource({"1,3", "90,3", "91,3", "92,2", "93,2", "94,1", "95,1", "96,0", "98,0", "100,0"})
    void spo2Bands(int spo2, int expected) {
        assertThat(EarlyWarningScore.spo2Score(spo2)).isEqualTo(expected);
    }

    // ---- RR: 8 or less or 25 or more 3, 21 to 24 2, 9 to 11 1, 12 to 20 0 ----

    @ParameterizedTest(name = "RR {0} scores {1}")
    @CsvSource({"1,3", "8,3", "9,1", "10,1", "11,1", "12,0", "16,0", "20,0", "21,2", "22,2", "24,2", "25,3", "300,3"})
    void rrBands(int rr, int expected) {
        assertThat(EarlyWarningScore.rrScore(rr)).isEqualTo(expected);
    }

    // ---- Pulse: 40 or less or 131 or more 3, 111 to 130 2, 41 to 50 or 91 to 110 1, 51 to 90 0 ----

    @ParameterizedTest(name = "pulse {0} scores {1}")
    @CsvSource({"1,3", "40,3", "41,1", "45,1", "50,1", "51,0", "80,0", "90,0", "91,1", "100,1", "110,1", "111,2",
            "120,2", "130,2", "131,3", "300,3"})
    void pulseBands(int pulse, int expected) {
        assertThat(EarlyWarningScore.pulseScore(pulse)).isEqualTo(expected);
    }

    // ---- Temperature in tenths: 35.0 or less 3, 39.1 or more 2, 35.1 to 36.0 or 38.1 to 39.0 1, 36.1 to 38.0 0 ----

    @ParameterizedTest(name = "temperature {0} tenths scores {1}")
    @CsvSource({"250,3", "340,3", "350,3", "351,1", "355,1", "360,1", "361,0", "370,0", "380,0", "381,1", "385,1",
            "390,1", "391,2", "400,2", "450,2"})
    void temperatureBands(int tempTenths, int expected) {
        assertThat(EarlyWarningScore.tempScore(tempTenths)).isEqualTo(expected);
    }

    // ---- Systolic: 90 or less or 220 or more 3, 91 to 100 2, 101 to 110 1, 111 to 219 0 ----

    @ParameterizedTest(name = "systolic {0} scores {1}")
    @CsvSource({"40,3", "90,3", "91,2", "100,2", "101,1", "110,1", "111,0", "120,0", "219,0", "220,3", "300,3"})
    void systolicBands(int systolic, int expected) {
        assertThat(EarlyWarningScore.systolicScore(systolic)).isEqualTo(expected);
    }

    // ---- the total and the risk ----

    @Test
    void normalVitalsScoreZeroAndLow() {
        EarlyWarningScore score = EarlyWarningScore.of(120, 80, 98, 370, 16);

        assertThat(score.total()).isZero();
        assertThat(score.risk()).isEqualTo(Risk.LOW);
    }

    @Test
    void theTotalIsTheSumOfTheFiveParameterScores() {
        // systolic 95 = 2, pulse 105 = 1, SpO2 94 = 1, temp 38.5 = 1, RR 22 = 2
        EarlyWarningScore score = EarlyWarningScore.of(95, 105, 94, 385, 22);

        assertThat(score.systolicScore()).isEqualTo(2);
        assertThat(score.pulseScore()).isEqualTo(1);
        assertThat(score.spo2Score()).isEqualTo(1);
        assertThat(score.tempScore()).isEqualTo(1);
        assertThat(score.rrScore()).isEqualTo(2);
        assertThat(score.total()).isEqualTo(7);
    }

    @Test
    void theHighestPossibleTotalIs15() {
        assertThat(EarlyWarningScore.of(80, 30, 85, 340, 5).total()).isEqualTo(15);
        assertThat(EarlyWarningScore.of(80, 30, 85, 340, 5).risk()).isEqualTo(Risk.HIGH);
    }

    @ParameterizedTest(name = "total {0} with no parameter at 3 is {1}")
    @CsvSource({"0,LOW", "1,LOW", "2,LOW", "3,LOW", "4,LOW", "5,MEDIUM", "6,MEDIUM", "7,HIGH", "8,HIGH"})
    void riskByTotalWhenNoParameterScores3(int total, Risk expected) {
        assertThat(withTotalAndNoThree(total).risk()).isEqualTo(expected);
        assertThat(withTotalAndNoThree(total).total()).isEqualTo(total);
    }

    @Test
    void sevenIsHighAndSixIsNotEvenWhenOneParameterScores3() {
        assertThat(new EarlyWarningScore(3, 3, 1, 0, 0).total()).isEqualTo(7);
        assertThat(new EarlyWarningScore(3, 3, 1, 0, 0).risk()).isEqualTo(Risk.HIGH);
        assertThat(new EarlyWarningScore(3, 3, 0, 0, 0).total()).isEqualTo(6);
        assertThat(new EarlyWarningScore(3, 3, 0, 0, 0).risk()).isEqualTo(Risk.MEDIUM);
    }

    @Test
    void fiveIsMediumAndFourIsLow() {
        assertThat(new EarlyWarningScore(2, 2, 1, 0, 0).risk()).isEqualTo(Risk.MEDIUM);
        assertThat(new EarlyWarningScore(2, 2, 0, 0, 0).risk()).isEqualTo(Risk.LOW);
    }

    @ParameterizedTest(name = "a single parameter of 3 at position {0} makes it Medium")
    @CsvSource({"0", "1", "2", "3", "4"})
    void anyOneParameterOf3IsMediumWhateverTheTotal(int position) {
        int[] parts = new int[5];
        parts[position] = 3;
        EarlyWarningScore score = new EarlyWarningScore(parts[0], parts[1], parts[2], parts[3], parts[4]);

        assertThat(score.total()).isEqualTo(3);
        assertThat(score.risk()).isEqualTo(Risk.MEDIUM);
    }

    @Test
    void aSingleSpo2Of91IsMediumWithATotalOf3() {
        EarlyWarningScore score = EarlyWarningScore.of(120, 80, 91, 370, 16);

        assertThat(score.total()).isEqualTo(3);
        assertThat(score.risk()).isEqualTo(Risk.MEDIUM);
    }

    @Test
    void aSingleSystolicOf220IsMedium() {
        assertThat(EarlyWarningScore.of(220, 80, 98, 370, 16).risk()).isEqualTo(Risk.MEDIUM);
        assertThat(EarlyWarningScore.of(219, 80, 98, 370, 16).risk()).isEqualTo(Risk.LOW);
    }

    @Test
    void twoParametersOf2AndNoneAt3AreStillLow() {
        // pulse 115 = 2, RR 22 = 2: total 4
        EarlyWarningScore score = EarlyWarningScore.of(120, 115, 98, 370, 22);

        assertThat(score.total()).isEqualTo(4);
        assertThat(score.risk()).isEqualTo(Risk.LOW);
    }

    // ---- targets and words ----

    @Test
    void theDoctorReviewTargetIs15MinutesForHigh60ForMediumAndNoneForLow() {
        assertThat(EarlyWarningScore.targetMinutes(Risk.HIGH)).isEqualTo(OptionalInt.of(15));
        assertThat(EarlyWarningScore.targetMinutes(Risk.MEDIUM)).isEqualTo(OptionalInt.of(60));
        assertThat(EarlyWarningScore.targetMinutes(Risk.LOW)).isEmpty();
    }

    @Test
    void theRiskWordIsLowerCase() {
        assertThat(Risk.LOW.word()).isEqualTo("low");
        assertThat(Risk.MEDIUM.word()).isEqualTo("medium");
        assertThat(Risk.HIGH.word()).isEqualTo("high");
    }

    /** A score with the given total (0 to 8) and no parameter at 3. */
    private static EarlyWarningScore withTotalAndNoThree(int total) {
        int[] parts = new int[5];
        int left = total;
        for (int i = 0; i < parts.length && left > 0; i++) {
            parts[i] = Math.min(2, left);
            left -= parts[i];
        }
        return new EarlyWarningScore(parts[0], parts[1], parts[2], parts[3], parts[4]);
    }
}
