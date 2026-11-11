package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Conditions B2 to B7 of ADR-018 D2 in one annotation (B1 is that this class exists at all, which only a build made
 * with {@code -Pdev-tools} gives). Everything of the bootstrap registration except the start-up line carries it:
 * when one condition is false no bootstrap bean exists and main code's reserved-path filter answers the empty 404.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnAdminRealm
@Conditional({BootstrapConditions.ProfilesAllowed.class, BootstrapConditions.SwitchOn.class,
        BootstrapConditions.PinHashValid.class, BootstrapConditions.NoProxyConfiguration.class,
        BootstrapConditions.BindsToLoopback.class})
public @interface ConditionalOnAdminBootstrap {
}
