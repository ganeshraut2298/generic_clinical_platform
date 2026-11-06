package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminPasswordPolicyTest {

    private static final String EMAIL = "dev.admin@example.test";

    @Test
    void acceptsTwelveToSixtyFourCharacters() {
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "correct horse b")).isTrue();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "x".repeat(12))).isTrue();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "x".repeat(64))).isTrue();
    }

    @Test
    void refusesTooShortAndTooLong() {
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "x".repeat(11))).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "x".repeat(65))).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "")).isFalse();
    }

    @Test
    void refusesMoreThanSeventyTwoBytesEvenWhenUnderSixtyFourCharacters() {
        // 40 characters of 2 bytes = 80 bytes.
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "é".repeat(40))).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "é".repeat(36))).isTrue();
    }

    @Test
    void refusesAPasswordContainingTheEmailNameInAnyCase() {
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "my-dev.admin-secret-1")).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "MY-DEV.ADMIN-SECRET-1")).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "my-other-secret-1")).isTrue();
    }

    @Test
    void theDomainPartIsNotPartOfTheRule() {
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "visit example.test now")).isTrue();
    }

    @Test
    void noCompositionRules() {
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "alllowercaseletters")).isTrue();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, "111111111111")).isTrue();
    }

    @Test
    void nullsAreRefused() {
        assertThat(AdminPasswordPolicy.isAcceptable(null, "x".repeat(12))).isFalse();
        assertThat(AdminPasswordPolicy.isAcceptable(EMAIL, null)).isFalse();
    }

    @Test
    void theSeedMessageNamesThePolicyButNoValue() {
        assertThat(AdminPasswordPolicy.SEED_MESSAGE)
                .isEqualTo("SAHAJ_ADMIN_SEED_PASSWORD does not meet the admin password policy "
                        + "(12 to 64 characters, not containing the email name).");
    }
}
