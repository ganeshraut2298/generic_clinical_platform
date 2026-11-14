package com.sahaj.opd.infrastructure.partition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring scheduling for the daily partition check (DEC-049 item 3); only when the check itself is on. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "sahaj.opd.partition-check", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class EmrPartitionUpkeepSchedulingConfig {
}
