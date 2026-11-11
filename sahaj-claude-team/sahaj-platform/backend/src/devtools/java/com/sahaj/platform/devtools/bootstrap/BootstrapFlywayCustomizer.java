package com.sahaj.platform.devtools.bootstrap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;

/**
 * Adds {@code classpath:db/bootstrap-dev-tools} so Flyway runs the callback
 * {@code afterMigrate__dev_tools_admin_bootstrap.sql} as {@code sahaj_migrator} (ADR-018 D1, docs/database/SAH-42.md).
 * A separate location from the sample admin's, because Flyway runs every callback it finds in a location: each feature's
 * callback must run only when its own switch is on. Exists only when conditions B2 to B7 hold. Keeps the existing
 * locations and never adds one twice.
 */
final class BootstrapFlywayCustomizer implements FlywayConfigurationCustomizer {

    static final String LOCATION = "classpath:db/bootstrap-dev-tools";

    @Override
    public void customize(FluentConfiguration configuration) {
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        Location location = new Location(LOCATION);
        if (!locations.contains(location)) {
            locations.add(location);
        }
        configuration.locations(locations.toArray(new Location[0]));
    }
}
