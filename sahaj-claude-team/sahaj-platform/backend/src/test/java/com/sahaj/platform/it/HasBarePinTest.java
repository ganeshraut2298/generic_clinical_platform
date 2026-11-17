package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The leak detector of the PIN ITs ({@code hasBarePin}, two copies: EmrSpecialtySupport and PinTestSupport) must flag a PIN that
 * stands alone or sits next to spaces and punctuation, and must not flag digits that are part of a longer number, a time, a
 * uuid or a hash.
 */
class HasBarePinTest {

    private static boolean both(String text, String pin) {
        boolean a = EmrSpecialtySupport.hasBarePin(text, pin);
        boolean b = PinTestSupport.hasBarePin(text, pin);
        assertThat(a).as("the two copies agree on: " + text).isEqualTo(b);
        return a;
    }

    @Test
    void aStandalonePinIsFound() {
        assertThat(both("4827", "4827")).isTrue();
        assertThat(both("the pin is 4827 ok", "4827")).isTrue();
        assertThat(both("{\"pin\":\"4827\"}", "4827")).isTrue();
        assertThat(both("pin=4827,next", "4827")).isTrue();
        assertThat(both("line one\n4827\nline two", "4827")).isTrue();
    }

    @Test
    void aPinNextToNonDigitsPunctuationIsStillFoundButDigitsInsideALongerRunOrAHashAreNot() {
        assertThat(both("[4827]", "4827")).isTrue();
        assertThat(both("(4827)", "4827")).isTrue();
        assertThat(both("48271", "4827")).isFalse();
        assertThat(both("14827", "4827")).isFalse();
        assertThat(both("9f4827c1", "4827")).isFalse();
        assertThat(both("3f2a-4827-11ee", "4827")).isFalse();
        assertThat(both("10:4827", "4827")).isFalse();
        assertThat(both("1.4827", "4827")).isFalse();
        assertThat(both("", "4827")).isFalse();
    }
}
