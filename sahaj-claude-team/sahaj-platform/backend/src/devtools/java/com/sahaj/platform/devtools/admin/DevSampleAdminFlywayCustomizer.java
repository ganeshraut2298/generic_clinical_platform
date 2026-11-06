package com.sahaj.platform.devtools.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;

/**
 * Adds {@code classpath:db/dev-tools} so Flyway runs the callback
 * {@code afterMigrate__dev_tools_sample_admin.sql} as {@code sahaj_migrator} (ADR-008 D5, option W1).
 * Exists only when conditions C1 to C5 hold. Keeps the existing locations and never adds one twice.
 */
final class DevSampleAdminFlywayCustomizer implements FlywayConfigurationCustomizer {

    static final String LOCATION = "classpath:db/dev-tools";

    @Override
    public void customize(FluentConfiguration configuration) {
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        Location devTools = new Location(LOCATION);
        if (!locations.contains(devTools)) {
            locations.add(devTools);
        }
        configuration.locations(locations.toArray(new Location[0]));
    }
}
