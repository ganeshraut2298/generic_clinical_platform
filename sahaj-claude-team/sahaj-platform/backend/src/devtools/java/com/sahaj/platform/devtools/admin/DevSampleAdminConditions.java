package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.devtools.DevToolsChecks;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Runtime conditions C1, C2, C4 and C5 of ADR-008 D2 (C3 is {@code @ConditionalOnAdminRealm}; all five are on
 * {@link ConditionalOnDevSampleAdmin}). Each is a
 * {@link Condition}, not a startup failure: when one is false the beans simply do not exist and the
 * reserved-path filter in main code answers 404 (H-DEV-4). Plain environment reads; no value is printed.
 *
 * <p>C1, C4 and C5 are shared with the bootstrap registration and live in {@link DevToolsChecks}; the methods here
 * only delegate, so there is one implementation.
 */
public final class DevSampleAdminConditions {

    private DevSampleAdminConditions() {
    }

    /** C1: profile {@code dev} is active and every active profile is {@code dev} or {@code test}. */
    public static boolean profilesAllowed(Environment env) {
        return DevToolsChecks.profilesAllowed(env);
    }

    static final String FLAG = "sahaj.admin.dev-sample-enabled";

    /**
     * C2 (ADR-008 R2): the flag is on only when the {@code commandLineArgs} property source holds the raw value
     * {@code true} (any case). An environment variable (and so {@code .env}), {@code SPRING_APPLICATION_JSON}, a
     * system property, a yml or properties file, or any other source is ignored, as is every relaxed spelling. The raw
     * value is read from that one source, so a placeholder such as {@code ${X}} is never resolved and is false, and
     * an empty value or a value given twice is false.
     */
    public static boolean flagOnCommandLine(Environment env) {
        if (!(env instanceof ConfigurableEnvironment configurable)) {
            return false;
        }
        PropertySource<?> commandLine = configurable.getPropertySources()
                .get(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME);
        if (commandLine == null) {
            return false;
        }
        Object value = commandLine.getProperty(FLAG);
        return value instanceof String text && "true".equalsIgnoreCase(text);
    }

    /** C4: no proxy configuration and no cloud platform (ADR-008 R6). See {@link DevToolsChecks#noProxyConfiguration}. */
    public static boolean noProxyConfiguration(Environment env) {
        return DevToolsChecks.noProxyConfiguration(env);
    }

    /** C5: {@code server.address} is set and is a loopback IP literal (a host name is never resolved). */
    public static boolean bindsToLoopback(Environment env) {
        return DevToolsChecks.bindsToLoopback(env);
    }

    public static class ProfilesAllowed implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return profilesAllowed(context.getEnvironment());
        }
    }

    public static class FlagOnCommandLine implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return flagOnCommandLine(context.getEnvironment());
        }
    }

    public static class NoProxyConfiguration implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return noProxyConfiguration(context.getEnvironment());
        }
    }

    public static class BindsToLoopback implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return bindsToLoopback(context.getEnvironment());
        }
    }
}
