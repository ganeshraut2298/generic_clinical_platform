package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** ADR-018 D3: BCrypt compare, the input rules, the dummy hash for over-long input, no value in toString. */
class BootstrapPinVerifierTest {

    /** A throwaway PIN made up for this test; it is not any real registration PIN. */
    private static final String THROWAWAY = "246813";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static String hash12;

    @BeforeAll
    static void makeHash() {
        hash12 = BCrypt.hashpw(THROWAWAY.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt(12, RANDOM));
    }

    private static boolean check(BootstrapPinVerifier verifier, String typed) {
        return verifier.matches(typed.toCharArray());
    }

    @Test
    void theRightPinMatchesAndAnyOtherDoesNot() {
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash12, RANDOM);

        assertThat(check(verifier, THROWAWAY)).isTrue();
        assertThat(check(verifier, "246814")).isFalse();
        assertThat(check(verifier, "24681")).isFalse();
        assertThat(check(verifier, "2468130")).isFalse();
        assertThat(check(verifier, "")).isFalse();
        assertThat(check(verifier, " 246813")).isFalse();
        assertThat(check(verifier, "246813 ")).isFalse();
        assertThat(check(verifier, THROWAWAY.toUpperCase())).isTrue();
        assertThat(check(verifier, "246813\u0000")).isFalse();
    }

    @Test
    void thePinIsComparedExactlyAsSentWithNoTrimming() {
        String padded = " 24 68 ";
        String hash = BCrypt.hashpw(padded.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt(12, RANDOM));
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash, RANDOM);

        assertThat(check(verifier, padded)).isTrue();
        assertThat(check(verifier, padded.trim())).isFalse();
    }

    @Test
    void aHashMadeBySpringsEncoderOrWithAnotherMinorVersionWorksToo() {
        String viaEncoder = new BCryptPasswordEncoder(12).encode(THROWAWAY);
        assertThat(check(new BootstrapPinVerifier(viaEncoder, RANDOM), THROWAWAY)).isTrue();

        String y = "$2y$" + hash12.substring(4);
        assertThat(check(new BootstrapPinVerifier(y, RANDOM), THROWAWAY)).isTrue();
    }

    @Test
    void aLongerNonNumericPinWorksWithNoCodeChange() {
        String passphrase = "correct horse battery staple, throwaway";
        String hash = BCrypt.hashpw(passphrase.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt(12, RANDOM));
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash, RANDOM);

        assertThat(check(verifier, passphrase)).isTrue();
        assertThat(check(verifier, "correct horse battery staple, throwaw")).isFalse();
    }

    @Test
    void sixtyFourCharactersAreCheckedForRealAndSixtyFiveAreWrongWithoutTheRealHash() {
        String sixtyFour = "a".repeat(64);
        String hash = BCrypt.hashpw(sixtyFour.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt(12, RANDOM));
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash, RANDOM);

        assertThat(check(verifier, sixtyFour)).isTrue();
        assertThat(verifier.dummyChecks()).isZero();

        // 65 characters: the real hash would match the first 64 if the extra one were ignored; it must be wrong.
        assertThat(check(verifier, sixtyFour + "a")).isFalse();
        assertThat(verifier.dummyChecks()).isEqualTo(1);
    }

    @Test
    void seventyTwoBytesAreCheckedForRealAndSeventyFiveAreWrongNotCutAtSeventyTwo() {
        String seventyTwoBytes = "€".repeat(24);
        assertThat(seventyTwoBytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        String hash = BCrypt.hashpw(seventyTwoBytes.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt(12, RANDOM));
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash, RANDOM);

        assertThat(check(verifier, seventyTwoBytes)).isTrue();
        assertThat(verifier.dummyChecks()).isZero();

        // 75 bytes: BCrypt itself would cut at 72 bytes and match. The verifier must not hand it over.
        assertThat(check(verifier, seventyTwoBytes + "€")).isFalse();
        assertThat(verifier.dummyChecks()).isEqualTo(1);
        assertThat(check(verifier, seventyTwoBytes + "a")).isFalse();
        assertThat(verifier.dummyChecks()).isEqualTo(2);
    }

    @Test
    void aVeryLongInputIsWrongWithoutAnError() {
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash12, RANDOM);

        assertThat(check(verifier, "9".repeat(4000))).isFalse();
        assertThat(verifier.dummyChecks()).isEqualTo(1);
    }

    @Test
    void theTypedCharactersAreNotKeptByTheVerifier() {
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash12, RANDOM);
        char[] typed = THROWAWAY.toCharArray();

        assertThat(verifier.matches(typed)).isTrue();

        // the byte copy is zeroed inside; the caller owns and clears the char array
        assertThat(typed).containsExactly(THROWAWAY.toCharArray());
    }

    @Test
    void onlyAValidHashOfCostTwelveToSixteenIsAccepted() {
        for (String bad : new String[] {"", "plain", "$2a$11$" + "a".repeat(53), "$2a$17$" + "a".repeat(53),
                "$2x$12$" + "a".repeat(53), "$2a$12$" + "a".repeat(52), "$2a$12$" + "a".repeat(54),
                "$2a$12$" + "a".repeat(52) + "!", " " + hash12, hash12 + "\n"}) {
            assertThatThrownBy(() -> new BootstrapPinVerifier(bad, RANDOM)).isInstanceOf(
                    IllegalArgumentException.class).hasMessageNotContaining(bad.isEmpty() ? "zzz" : bad);
        }
    }

    @Test
    void toStringPrintsNeitherTheHashNorItsLengthOrPrefix() {
        BootstrapPinVerifier verifier = new BootstrapPinVerifier(hash12, RANDOM);

        assertThat(verifier.toString()).isEqualTo("BootstrapPinVerifier[]").doesNotContain("$2").doesNotContain("12");
    }
}
