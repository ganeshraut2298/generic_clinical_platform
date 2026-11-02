package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TrustedProxyStartupCheckTest {

    @Test
    void doesNothingWhenNoHeaderIsConfigured() {
        assertThatCode(() -> TrustedProxyStartupCheck.validate("", "")).doesNotThrowAnyException();
        assertThatCode(() -> TrustedProxyStartupCheck.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    void failsWhenTheHeaderIsSetButNoProxyIsTrusted() {
        assertThatThrownBy(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAHAJ_TRUSTED_PROXIES is blank");
        assertThatThrownBy(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", "   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsWhenTheTrustedProxyPatternMatchesAnyPublicAddress() {
        assertThatThrownBy(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", ".*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches an arbitrary public");
        assertThatThrownBy(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", "\\d+\\.\\d+\\.\\d+\\.\\d+"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesWithANarrowKnownProxyPattern() {
        assertThatCode(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", "127\\.0\\.0\\.1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TrustedProxyStartupCheck.validate("X-Forwarded-For", "10\\.0\\.0\\.\\d{1,3}"))
                .doesNotThrowAnyException();
    }
}
