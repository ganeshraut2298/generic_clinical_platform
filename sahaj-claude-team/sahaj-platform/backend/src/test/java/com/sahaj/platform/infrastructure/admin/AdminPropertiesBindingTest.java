package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The secrets are not listed in application.yml (the profile config test allows no default on a
 * secret). They reach the application only through the environment variables, bound by name.
 */
class AdminPropertiesBindingTest {

    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }

    @Test
    void environmentVariablesBindToTheProperties() {
        StandardEnvironment env = environment(Map.of(
                "SAHAJ_ADMIN_ENABLED", "true",
                "SAHAJ_ADMIN_JWT_SECRET", "a-jwt-secret",
                "SAHAJ_ADMIN_TOTP_KEY", "a-totp-key",
                "SAHAJ_ADMIN_ALLOWED_NETWORKS", "10.0.0.0/8",
                "SAHAJ_ADMIN_SEED_EMAIL", "dev@example.test",
                "SAHAJ_ADMIN_SEED_PASSWORD", "a-seed-password",
                "SAHAJ_ADMIN_SEED_NAME", "Someone"));

        AdminProperties properties = Binder.get(env).bind("sahaj.admin", AdminProperties.class).get();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.jwtSecret()).isEqualTo("a-jwt-secret");
        assertThat(properties.totpKey()).isEqualTo("a-totp-key");
        assertThat(properties.allowedNetworks()).isEqualTo("10.0.0.0/8");
        assertThat(properties.seed().email()).isEqualTo("dev@example.test");
        assertThat(properties.seed().password()).isEqualTo("a-seed-password");
        assertThat(properties.seed().name()).isEqualTo("Someone");
        assertThat(properties.secretsConfigured()).isTrue();
        assertThat(AdminRealmConditions.secretsConfigured(env)).isTrue();
    }

    @Test
    void withNothingSetTheRealmIsOffAndNothingIsConfigured() {
        StandardEnvironment env = environment(Map.of("SAHAJ_ADMIN_ENABLED", "false"));

        AdminProperties properties = Binder.get(env).bind("sahaj.admin", AdminProperties.class).get();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.jwtSecret()).isNull();
        assertThat(properties.secretsConfigured()).isFalse();
        assertThat(properties.totp().devBypass()).isFalse();
        assertThat(properties.seed().name()).isEqualTo("Dev admin");
        assertThat(AdminRealmConditions.secretsConfigured(env)).isFalse();
    }

    @Test
    void anEmptyVariableCountsAsMissing() {
        StandardEnvironment env = environment(Map.of("SAHAJ_ADMIN_JWT_SECRET", "", "SAHAJ_ADMIN_TOTP_KEY", "k"));

        assertThat(AdminRealmConditions.secretsConfigured(env)).isFalse();
    }

    @Test
    void thePropertiesNeverPrintASecret() {
        StandardEnvironment env = environment(Map.of("SAHAJ_ADMIN_ENABLED", "true",
                "SAHAJ_ADMIN_JWT_SECRET", "super-secret-value", "SAHAJ_ADMIN_SEED_PASSWORD", "seed-pass-value"));

        AdminProperties properties = Binder.get(env).bind("sahaj.admin", AdminProperties.class).get();

        assertThat(properties.toString()).doesNotContain("super-secret-value").doesNotContain("seed-pass-value");
        assertThat(properties.seed().toString()).doesNotContain("seed-pass-value");
    }
}
