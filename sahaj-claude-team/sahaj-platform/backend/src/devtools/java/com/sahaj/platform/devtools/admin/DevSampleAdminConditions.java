package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.infrastructure.admin.IpLiterals;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.cloud.CloudPlatform;
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
 */
public final class DevSampleAdminConditions {

    private DevSampleAdminConditions() {
    }

    /** C1: profile {@code dev} is active and every active profile is {@code dev} or {@code test}. */
    public static boolean profilesAllowed(Environment env) {
        boolean dev = false;
        for (String profile : env.getActiveProfiles()) {
            if ("dev".equals(profile)) {
                dev = true;
            } else if (!"test".equals(profile)) {
                return false;
            }
        }
        return dev;
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

    /**
     * C4: no proxy configuration and no cloud platform. With a forward-headers strategy other than {@code none}, or a
     * remote-IP or protocol header configured, {@code getRemoteAddr()} may come from a header, so the loopback check
     * would mean nothing: fail closed. Spring Boot also turns forward headers on when it detects a cloud platform
     * (Kubernetes, Cloud Foundry, Heroku and others), so {@code spring.main.cloud-platform} must be blank (any value,
     * {@code none} included, refuses) and {@link CloudPlatform#getActive} must find none (ADR-008 R6).
     */
    public static boolean noProxyConfiguration(Environment env) {
        if (!isBlank(env.getProperty("spring.main.cloud-platform")) || CloudPlatform.getActive(env) != null) {
            return false;
        }
        String strategy = env.getProperty("server.forward-headers-strategy");
        if (strategy != null && !strategy.isBlank() && !"none".equalsIgnoreCase(strategy.trim())) {
            return false;
        }
        return isBlank(env.getProperty("server.tomcat.remoteip.remote-ip-header"))
                && isBlank(env.getProperty("server.tomcat.remoteip.protocol-header"));
    }

    /** C5: {@code server.address} is set and is a loopback IP literal (a host name is never resolved). */
    public static boolean bindsToLoopback(Environment env) {
        String address = env.getProperty("server.address");
        if (address == null || address.isBlank()) {
            return false;
        }
        Optional<InetAddress> parsed = IpLiterals.parse(address.toLowerCase(Locale.ROOT));
        return parsed.isPresent() && parsed.get().isLoopbackAddress();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
