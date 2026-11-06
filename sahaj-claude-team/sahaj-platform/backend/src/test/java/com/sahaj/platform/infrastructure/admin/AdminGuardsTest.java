package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Same rules as the clinic OtpDevBypassGuard: dev, optionally with test, and nothing else. */
class AdminGuardsTest {

    private static MockEnvironment profiles(String... names) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(names);
        return environment;
    }

    // ---- dev bypass

    @Test
    void bypassOffIsAllowedEverywhere() {
        assertThatCode(() -> AdminTotpDevBypassGuard.verify(false, List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminTotpDevBypassGuard.verify(false, List.of())).doesNotThrowAnyException();
    }

    @Test
    void bypassOnIsAllowedInDevAndDevWithTest() {
        assertThatCode(() -> AdminTotpDevBypassGuard.verify(true, List.of("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminTotpDevBypassGuard.verify(true, List.of("dev", "test"))).doesNotThrowAnyException();
    }

    @Test
    void bypassOnOutsideDevFailsStart() {
        assertThatThrownBy(() -> AdminTotpDevBypassGuard.verify(true, List.of("test")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminTotpDevBypassGuard.verify(true, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminTotpDevBypassGuard.verify(true, List.of("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminTotpDevBypassGuard.verify(true, List.of("dev", "prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminTotpDevBypassGuard.verify(true, List.of("prod", "dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bypassGuardConstructorReadsTheActiveProfiles() {
        assertThatThrownBy(() -> new AdminTotpDevBypassGuard(true, profiles("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AdminTotpDevBypassGuard(true, profiles("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> new AdminTotpDevBypassGuard(false, profiles("prod"))).doesNotThrowAnyException();
    }

    // ---- seed values

    @Test
    void noSeedValuesIsAllowedEverywhere() {
        assertThatCode(() -> AdminSeedGuard.verify("", "", List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminSeedGuard.verify(null, "  ", List.of())).doesNotThrowAnyException();
    }

    @Test
    void seedValuesAreAllowedInDevOnly() {
        assertThatCode(() -> AdminSeedGuard.verify("a@b.co", "x", List.of("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminSeedGuard.verify("a@b.co", "x", List.of("dev", "test"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AdminSeedGuard.verify("a@b.co", "", List.of("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminSeedGuard.verify("", "secret-value", List.of("test")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminSeedGuard.verify("a@b.co", "x", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminSeedGuard.verify("a@b.co", "x", List.of("dev", "staging")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSeedRefusalNeverPrintsTheValues() {
        assertThatThrownBy(() -> AdminSeedGuard.verify("someone@example.test", "my-seed-password", List.of("prod")))
                .hasMessageNotContaining("someone@example.test")
                .hasMessageNotContaining("my-seed-password");
    }

    @Test
    void seedGuardConstructorReadsTheActiveProfiles() {
        AdminProperties.Seed seed = new AdminProperties.Seed("a@b.co", "pw", "Dev admin");
        assertThatThrownBy(() -> new AdminSeedGuard(seed, profiles("prod"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AdminSeedGuard(seed, profiles("dev"))).doesNotThrowAnyException();
    }

    // ---- the bypass itself

    @Test
    void theBypassAppliesOnlyToLoopbackAddresses() {
        AdminDevBypass on = new AdminDevBypass(true);
        assertThat(on.appliesTo("127.0.0.1")).isTrue();
        assertThat(on.appliesTo("::1")).isTrue();
        assertThat(on.appliesTo("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(on.appliesTo("203.0.113.9")).isFalse();
        assertThat(on.appliesTo("10.0.0.5")).isFalse();
        assertThat(on.appliesTo("localhost")).isFalse();
        assertThat(on.appliesTo(null)).isFalse();
        assertThat(on.appliesTo("")).isFalse();
    }

    @Test
    void withTheSwitchOffTheBypassNeverApplies() {
        assertThat(new AdminDevBypass(false).appliesTo("127.0.0.1")).isFalse();
    }
}
