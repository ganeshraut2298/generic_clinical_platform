package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void generatesTenDistinctCodesInDisplayForm() {
        var codes = RecoveryCodes.generate(new SecureRandom());
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(code -> code.matches("^[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"));
    }

    @Test
    void normaliseDropsHyphensSpacesAndCase() {
        assertThat(RecoveryCodes.normalise("abcd-efgh-2345-67ab")).isEqualTo("ABCDEFGH234567AB");
        assertThat(RecoveryCodes.normalise("ABCD EFGH 2345 67AB")).isEqualTo("ABCDEFGH234567AB");
    }

    @Test
    void everyGeneratedCodeIsWellFormedAfterNormalising() {
        RecoveryCodes.generate(new SecureRandom()).forEach(code ->
                assertThat(RecoveryCodes.isWellFormed(RecoveryCodes.normalise(code))).isTrue());
    }

    @Test
    void wellFormedMeansSixteenBase32Characters() {
        assertThat(RecoveryCodes.isWellFormed("ABCDEFGH234567AB")).isTrue();
        assertThat(RecoveryCodes.isWellFormed("ABCDEFGH234567A")).isFalse();
        assertThat(RecoveryCodes.isWellFormed("ABCDEFGH234567A1")).isFalse();
        assertThat(RecoveryCodes.isWellFormed("")).isFalse();
    }

    @Test
    void codesAreRandomBetweenCalls() {
        var first = RecoveryCodes.generate(new SecureRandom());
        var second = RecoveryCodes.generate(new SecureRandom());
        assertThat(first).isNotEqualTo(second);
    }
}
