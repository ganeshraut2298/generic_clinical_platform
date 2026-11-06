package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminPasswordHasherTest {

    private final AdminPasswordHasher hasher = new AdminPasswordHasher(new SecureRandom());

    @Test
    void hashesWithBcryptStrengthTwelve() {
        String hash = hasher.hash("a long enough password");
        assertThat(hash).startsWith("$2a$12$").hasSize(60);
    }

    @Test
    void matchesTheRightPasswordAndRefusesTheWrongOne() {
        String hash = hasher.hash("a long enough password");
        assertThat(hasher.matches("a long enough password", hash)).isTrue();
        assertThat(hasher.matches("a long enough passwore", hash)).isFalse();
    }

    @Test
    void aPasswordOver72BytesIsNeverAMatchEvenWhenTheFirst72BytesAreRight() {
        String seventyTwo = "p".repeat(72);
        String hash = hasher.hash(seventyTwo);
        assertThat(hasher.matches(seventyTwo, hash)).isTrue();
        assertThat(hasher.matches(seventyTwo + "extra", hash)).isFalse();
    }

    @Test
    void countsBytesNotCharacters() {
        // 37 characters of 2 bytes each = 74 bytes.
        String longInBytes = "é".repeat(37);
        assertThat(hasher.matches(longInBytes, hasher.hash("é".repeat(36)))).isFalse();
        assertThatThrownBy(() -> hasher.hash(longInBytes)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullInputsAreSimplyWrong() {
        assertThat(hasher.matches(null, hasher.hash("a long enough password"))).isFalse();
        assertThat(hasher.matches("a long enough password", null)).isFalse();
    }

    @Test
    void dummyMatchNeverThrowsAndSpendsRealWork() {
        long start = System.nanoTime();
        hasher.dummyMatch("anything");
        hasher.dummyMatch("x".repeat(200));
        hasher.dummyMatch(null);
        long millis = (System.nanoTime() - start) / 1_000_000;
        // Three BCrypt strength 12 checks (plus the one-time dummy hash) cannot take under 50 ms.
        assertThat(millis).isGreaterThan(50);
    }

    @Test
    void isNotAPasswordEncoderBeanType() {
        assertThat(PasswordEncoder.class.isAssignableFrom(AdminPasswordHasher.class)).isFalse();
    }
}
