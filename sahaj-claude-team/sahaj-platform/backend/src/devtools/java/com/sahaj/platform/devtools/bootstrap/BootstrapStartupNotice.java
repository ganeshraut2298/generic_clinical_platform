package com.sahaj.platform.devtools.bootstrap;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The one start-up line (ADR-018 D2, BR2), written by a bean that exists whenever the build has the bootstrap and
 * {@code SAHAJ_ADMIN_BOOTSTRAP_ENABLED} is {@code true}. It says "on" only when every condition B2 to B7 holds, and
 * otherwise "off" with one fixed reason (the first failing one). It never prints the hash, its length, its prefix, a
 * key or any other value from the environment. When the switch is not {@code true} the bean does not exist and nothing
 * is logged.
 */
final class BootstrapStartupNotice {

    static final String ON = "Admin bootstrap registration is on (dev-tools build). /admin/register works from this "
            + "machine until an admin exists.";
    static final String OFF_FORMAT = "Admin bootstrap registration is off: {}.";

    private static final Logger log = LoggerFactory.getLogger(BootstrapStartupNotice.class);

    BootstrapStartupNotice(Environment environment) {
        Optional<String> reason = BootstrapConditions.firstFailingReason(environment);
        if (reason.isPresent()) {
            log.warn(OFF_FORMAT, reason.get());
        } else {
            log.warn(ON);
        }
    }
}
