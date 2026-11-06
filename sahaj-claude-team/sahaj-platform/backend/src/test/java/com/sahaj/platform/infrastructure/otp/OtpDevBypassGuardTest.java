package com.sahaj.platform.infrastructure.otp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OtpDevBypassGuardTest {

    @Test
    void bypassOffIsAllowedInAnyProfile() {
        assertThatCode(() -> OtpDevBypassGuard.verify(false, List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> OtpDevBypassGuard.verify(false, List.of())).doesNotThrowAnyException();
    }

    @Test
    void bypassOnIsAllowedInDev() {
        assertThatCode(() -> OtpDevBypassGuard.verify(true, List.of("dev"))).doesNotThrowAnyException();
    }

    @Test
    void bypassOnOutsideDevFailsStart() {
        assertThatThrownBy(() -> OtpDevBypassGuard.verify(true, List.of("test")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OtpDevBypassGuard.verify(true, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bypassOnWithDevAndAnotherProfileFailsStart() {
        assertThatThrownBy(() -> OtpDevBypassGuard.verify(true, List.of("dev", "prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OtpDevBypassGuard.verify(true, List.of("prod", "dev")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OtpDevBypassGuard.verify(true, List.of("dev", "staging")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bypassOnWithDevAndTestIsAllowed() {
        assertThatCode(() -> OtpDevBypassGuard.verify(true, List.of("dev", "test"))).doesNotThrowAnyException();
    }

    @Test
    void bypassOffIsAllowedWithDevAndProd() {
        assertThatCode(() -> OtpDevBypassGuard.verify(false, List.of("dev", "prod"))).doesNotThrowAnyException();
    }

    @Test
    void constructorReadsActiveProfiles() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new OtpDevBypassGuard(true, prod)).isInstanceOf(IllegalStateException.class);
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> new OtpDevBypassGuard(true, dev)).doesNotThrowAnyException();
    }
}
