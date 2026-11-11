package com.sahaj.platform.devtools.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Creates the start-up line (ADR-018 D2, BR2) whenever this is a dev-tools build and
 * {@code SAHAJ_ADMIN_BOOTSTRAP_ENABLED} is {@code true}, whatever the other conditions say: the line then tells the
 * human whether the registration is on or why it is off. With the switch not {@code true} nothing is created and
 * nothing is logged.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(BootstrapConditions.SwitchOn.class)
class BootstrapStartupNoticeConfiguration {

    @Bean
    BootstrapStartupNotice bootstrapStartupNotice(Environment environment) {
        return new BootstrapStartupNotice(environment);
    }
}
