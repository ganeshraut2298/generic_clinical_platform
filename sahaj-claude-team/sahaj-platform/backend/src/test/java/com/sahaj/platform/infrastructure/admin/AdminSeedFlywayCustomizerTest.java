package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminSeedFlywayCustomizerTest {

    private final AdminPasswordHasher hasher = new AdminPasswordHasher(new SecureRandom());

    private AdminSeedFlywayCustomizer customizer(String email, String password, String name) {
        return new AdminSeedFlywayCustomizer(new AdminProperties.Seed(email, password, name), hasher);
    }

    @Test
    void definesAllThreePlaceholdersEmptyWhenNothingIsSeeded() {
        Map<String, String> placeholders = customizer("", "", "Dev admin").placeholders();
        assertThat(placeholders).containsEntry("sahaj_admin_seed_email", "")
                .containsEntry("sahaj_admin_seed_name", "")
                .containsEntry("sahaj_admin_seed_password_hash", "")
                .hasSize(3);
        assertThat(customizer("a@example.test", "", "x").placeholders().get("sahaj_admin_seed_password_hash")).isEmpty();
        assertThat(customizer("", "some-long-password-1", "x").placeholders().get("sahaj_admin_seed_email")).isEmpty();
    }

    @Test
    void hashesThePasswordInJavaAndPassesOnlyEmailNameAndHash() {
        Map<String, String> placeholders = customizer("  Dev.Admin@Example.test ", "a-strong-password-1", " Dev ").placeholders();

        assertThat(placeholders.get("sahaj_admin_seed_email")).isEqualTo("dev.admin@example.test");
        assertThat(placeholders.get("sahaj_admin_seed_name")).isEqualTo("Dev");
        String hash = placeholders.get("sahaj_admin_seed_password_hash");
        assertThat(hash).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches("a-strong-password-1", hash)).isTrue();
        assertThat(placeholders.values()).noneMatch(value -> value.contains("a-strong-password-1"));
    }

    @Test
    void theNameDefaultsToDevAdmin() {
        assertThat(customizer("dev@example.test", "a-strong-password-1", " ").placeholders().get("sahaj_admin_seed_name"))
                .isEqualTo("Dev admin");
        assertThat(customizer("dev@example.test", "a-strong-password-1", null).placeholders().get("sahaj_admin_seed_name"))
                .isEqualTo("Dev admin");
    }

    @Test
    void aPasswordThatFailsThePolicyStopsStartupWithoutPrintingIt() {
        assertThatThrownBy(() -> customizer("dev.admin@example.test", "short", "x").placeholders())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SAHAJ_ADMIN_SEED_PASSWORD does not meet the admin password policy "
                        + "(12 to 64 characters, not containing the email name).")
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> customizer("dev.admin@example.test", "contains-dev.admin-inside", "x").placeholders())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("contains-dev.admin-inside");
    }

    @Test
    void anInvalidEmailOrQuoteBreakingValueStopsStartup() {
        assertThatThrownBy(() -> customizer("not-an-email", "a-strong-password-1", "x").placeholders())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customizer("dev@example.test", "a-strong-password-1", "bad $sq$ name").placeholders())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customizer("dev@example.test", "a-strong-password-1", "n".repeat(121)).placeholders())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addsTheDevSeedLocationWithoutRemovingTheMigrationLocation() {
        FluentConfiguration configuration = Flyway.configure().locations("classpath:db/migration");

        customizer("", "", "x").customize(configuration);

        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor))
                .containsExactlyInAnyOrder("classpath:db/migration", AdminSeedFlywayCustomizer.LOCATION);
        assertThat(configuration.getPlaceholders()).containsKeys("sahaj_admin_seed_email", "sahaj_admin_seed_name",
                "sahaj_admin_seed_password_hash");
    }

    @Test
    void keepsExistingPlaceholdersAndDoesNotAddTheLocationTwice() {
        FluentConfiguration configuration = Flyway.configure().locations("classpath:db/migration")
                .placeholders(Map.of("other", "value"));
        AdminSeedFlywayCustomizer customizer = customizer("", "", "x");

        customizer.customize(configuration);
        customizer.customize(configuration);

        assertThat(configuration.getPlaceholders()).containsEntry("other", "value");
        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor))
                .containsExactlyInAnyOrder("classpath:db/migration", AdminSeedFlywayCustomizer.LOCATION);
    }
}
