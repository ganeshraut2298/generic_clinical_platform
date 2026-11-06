package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.AdminPasswordPolicy;
import com.sahaj.platform.application.admin.AdminProvisioning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;

/**
 * Dev profile only (ADR-003 D5), and only when the admin realm is switched on: adds the
 * {@code classpath:db/dev-seed} Flyway location and the three placeholders the callback
 * {@code afterMigrate__dev_seed_platform_admin.sql} needs. The password is checked against the admin
 * policy and hashed here in Java (BCrypt, strength 12); only the email, the name and the hash are
 * handed to Flyway, so the plain password never reaches the database, a placeholder or a log line.
 * Empty placeholders mean "seed nothing".
 */
public final class AdminSeedFlywayCustomizer implements FlywayConfigurationCustomizer {

    public static final String LOCATION = "classpath:db/dev-seed";

    private final AdminProperties.Seed seed;
    private final AdminProvisioning provisioning;

    public AdminSeedFlywayCustomizer(AdminProperties.Seed seed, AdminPasswordHasher hasher) {
        this.seed = seed;
        this.provisioning = new AdminProvisioning(hasher);
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        Location seedLocation = new Location(LOCATION);
        if (!locations.contains(seedLocation)) {
            locations.add(seedLocation);
        }
        configuration.locations(locations.toArray(new Location[0]));

        Map<String, String> placeholders = new HashMap<>(configuration.getPlaceholders());
        placeholders.putAll(placeholders());
        configuration.placeholders(placeholders);
    }

    Map<String, String> placeholders() {
        String email = seed.email() == null ? "" : seed.email().trim().toLowerCase(Locale.ROOT);
        String password = seed.password() == null ? "" : seed.password();
        String name = seed.name() == null || seed.name().isBlank() ? "Dev admin" : seed.name().trim();

        Map<String, String> values = new HashMap<>();
        if (email.isEmpty() || password.isEmpty()) {
            values.put("sahaj_admin_seed_email", "");
            values.put("sahaj_admin_seed_name", "");
            values.put("sahaj_admin_seed_password_hash", "");
            return values;
        }
        AdminProvisioning.PreparedAdmin prepared;
        try {
            prepared = provisioning.prepare(email, name, password);
        } catch (AdminProvisioning.RefusedException ex) {
            throw refusal(ex.reason());
        }
        values.put("sahaj_admin_seed_email", prepared.email());
        values.put("sahaj_admin_seed_name", prepared.name());
        values.put("sahaj_admin_seed_password_hash", prepared.passwordHash());
        return values;
    }

    private static IllegalStateException refusal(AdminProvisioning.Reason reason) {
        switch (reason) {
            case EMAIL_INVALID:
                return new IllegalStateException("SAHAJ_ADMIN_SEED_EMAIL is not a valid email address.");
            case NAME_INVALID:
                return new IllegalStateException("SAHAJ_ADMIN_SEED_NAME must be at most 120 characters.");
            case PASSWORD_POLICY:
            default:
                return new IllegalStateException(AdminPasswordPolicy.SEED_MESSAGE);
        }
    }
}
