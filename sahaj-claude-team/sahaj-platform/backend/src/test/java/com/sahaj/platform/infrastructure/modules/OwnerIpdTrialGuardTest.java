package com.sahaj.platform.infrastructure.modules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OwnerIpdTrialGuardTest {

    @Test
    void switchOffIsAllowedInAnyProfile() {
        assertThatCode(() -> OwnerIpdTrialGuard.verify(false, List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> OwnerIpdTrialGuard.verify(false, List.of())).doesNotThrowAnyException();
    }

    @Test
    void switchOnIsAllowedInDevOrTest() {
        assertThatCode(() -> OwnerIpdTrialGuard.verify(true, List.of("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> OwnerIpdTrialGuard.verify(true, List.of("test"))).doesNotThrowAnyException();
        assertThatCode(() -> OwnerIpdTrialGuard.verify(true, List.of("prod", "dev"))).doesNotThrowAnyException();
    }

    @Test
    void switchOnWithoutDevOrTestFailsStart() {
        assertThatThrownBy(() -> OwnerIpdTrialGuard.verify(true, List.of("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("owner-ipd-trial");
        assertThatThrownBy(() -> OwnerIpdTrialGuard.verify(true, List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OwnerIpdTrialGuard.verify(true, List.of("staging", "prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorReadsTheSwitchAndTheActiveProfiles() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new OwnerIpdTrialGuard(true, prod)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new OwnerIpdTrialGuard(false, prod)).doesNotThrowAnyException();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> new OwnerIpdTrialGuard(true, dev)).doesNotThrowAnyException();
    }
}
