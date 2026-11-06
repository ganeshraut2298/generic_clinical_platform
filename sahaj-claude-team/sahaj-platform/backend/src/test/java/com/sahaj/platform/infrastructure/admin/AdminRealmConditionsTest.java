package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The feature switch: with {@code sahaj.admin.enabled} absent or false nothing of the admin realm
 * exists; enabled with both secrets gives the realm; enabled with a secret missing gives only the
 * "unavailable" part.
 */
class AdminRealmConditionsTest {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnAdminRealm
    static class RealmConfig {
        @Bean
        String realmMarker() {
            return "realm";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnAdminUnavailable
    static class UnavailableConfig {
        @Bean
        String unavailableMarker() {
            return "unavailable";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RealmConfig.class, UnavailableConfig.class);

    @Test
    void nothingExistsByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("realmMarker");
            assertThat(context).doesNotHaveBean("unavailableMarker");
        });
    }

    @Test
    void nothingExistsWhenSwitchedOffEvenWithSecrets() {
        runner.withPropertyValues("sahaj.admin.enabled=false", "sahaj.admin.jwt-secret=x", "sahaj.admin.totp-key=y")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("realmMarker");
                    assertThat(context).doesNotHaveBean("unavailableMarker");
                });
    }

    @Test
    void theRealmExistsWhenEnabledWithBothSecrets() {
        runner.withPropertyValues("sahaj.admin.enabled=true", "sahaj.admin.jwt-secret=x", "sahaj.admin.totp-key=y")
                .run(context -> {
                    assertThat(context).hasBean("realmMarker");
                    assertThat(context).doesNotHaveBean("unavailableMarker");
                });
    }

    @Test
    void onlyTheUnavailablePartExistsWhenASecretIsMissingOrBlank() {
        runner.withPropertyValues("sahaj.admin.enabled=true", "sahaj.admin.jwt-secret=x").run(context -> {
            assertThat(context).doesNotHaveBean("realmMarker");
            assertThat(context).hasBean("unavailableMarker");
        });
        runner.withPropertyValues("sahaj.admin.enabled=true", "sahaj.admin.jwt-secret=x", "sahaj.admin.totp-key=  ")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("realmMarker");
                    assertThat(context).hasBean("unavailableMarker");
                });
        runner.withPropertyValues("sahaj.admin.enabled=true").run(context -> {
            assertThat(context).doesNotHaveBean("realmMarker");
            assertThat(context).hasBean("unavailableMarker");
        });
    }
}
