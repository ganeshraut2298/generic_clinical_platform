package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * All five runtime conditions of ADR-008 D2 in one annotation: C1 profile dev (and only dev or test),
 * C2 {@code sahaj.admin.dev-sample-enabled=true} on the command line only (the {@code commandLineArgs} property
 * source; R2), C3 the admin realm is on with both secrets, C4 no proxy configuration and no cloud platform, C5 the
 * server binds to a loopback address. The property is never set in {@code application*.yml} or
 * {@code .env.example}; an environment variable, {@code .env}, {@code SPRING_APPLICATION_JSON}, a system property or
 * a yml file cannot turn it on. It is passed as a program argument for one run.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnAdminRealm
@Conditional({DevSampleAdminConditions.ProfilesAllowed.class, DevSampleAdminConditions.FlagOnCommandLine.class,
        DevSampleAdminConditions.NoProxyConfiguration.class, DevSampleAdminConditions.BindsToLoopback.class})
public @interface ConditionalOnDevSampleAdmin {
}
