package com.sahaj.platform.it.devsample;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.SahajApplication;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Starts the whole Sahaj application inside the test JVM exactly as {@code java -jar ... --option=value} would,
 * on a random loopback port, against the {@code sahaj_test} database (never {@code sahaj_dev}). Used by the DEC-016
 * tests, which need the real command-line property source (condition C2), real servlet and security filters and a real
 * socket (conditions C4 and C5, checks G2 to G7). A Spring test context cannot do this: it never has the
 * {@code commandLineArgs} source that C2 reads.
 *
 * <p>Defaults: profiles {@code dev,test} (later profile wins, so {@code sahaj_test}), port 0 on 127.0.0.1, Flyway OFF
 * (the schema is already migrated; callers that need the dev-tools callback ask for {@link Builder#flywayOn()}),
 * the admin realm on with dummy secrets that exist only in these tests, the admin authenticator bypass OFF, and the
 * dev sample flag {@code --sahaj.admin.dev-sample-enabled=true}. Every default can be replaced; an option is stored once,
 * so it never reaches Spring twice (a repeated option would turn into a comma-joined value).
 *
 * <p>All log events of the run are kept in memory ({@link #logText()}) so tests can prove what is never logged.
 * Classes that sit in {@code test-classes} are excluded from component scanning (a Spring test context does this for
 * you; a plain run does not).
 */
public final class AppRun implements AutoCloseable {

    /** Dummy values used only by these tests. The authenticator key is the Base64 of "sahaj-test-totp-key-32-bytes!!!!". */
    public static final String ADMIN_JWT_SECRET = "dec016-test-admin-signing-secret-0123456789";
    public static final String ADMIN_TOTP_KEY_BASE64 = "c2FoYWotdGVzdC10b3RwLWtleS0zMi1ieXRlcyEhISE=";
    public static final String FLAG = "sahaj.admin.dev-sample-enabled";
    public static final String PATH = "/api/v1/admin/dev/sample-admin";

    private static final Pattern NONCE_LINE = Pattern.compile("X-Sahaj-Dev-Nonce: ([0-9a-f]{32})\\.");

    private final ConfigurableApplicationContext context;
    private final ListAppender<ILoggingEvent> logs;
    private final Map<String, String> restoredSystemProperties;
    private final int port;

    private AppRun(ConfigurableApplicationContext context, ListAppender<ILoggingEvent> logs,
                   Map<String, String> restoredSystemProperties) {
        this.context = context;
        this.logs = logs;
        this.restoredSystemProperties = restoredSystemProperties;
        this.port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return port;
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public RawHttp.Request post(String target) {
        return RawHttp.request(port, "POST", target);
    }

    public RawHttp.Request request(String method, String target) {
        return RawHttp.request(port, method, target);
    }

    /** A fully valid call to the dev endpoint: right path, Host, and (when given) the nonce. */
    public RawHttp.Request devCall(String nonce) {
        RawHttp.Request request = post(PATH);
        if (nonce != null) {
            request.header("X-Sahaj-Dev-Nonce", nonce);
        }
        return request;
    }

    /** Every log event of this run as text: logger, level, formatted message, MDC values and any exception. */
    public synchronized List<String> logLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            StringBuilder line = new StringBuilder();
            line.append(event.getLevel()).append(' ').append(event.getLoggerName()).append(" - ")
                    .append(event.getFormattedMessage());
            if (!event.getMDCPropertyMap().isEmpty()) {
                line.append(" MDC=").append(event.getMDCPropertyMap());
            }
            if (event.getThrowableProxy() != null) {
                line.append('\n').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    public String logText() {
        return String.join("\n", logLines());
    }

    /** The per-start nonce, read from the one start-up WARN line (ADR-008 R1); null when there is no such line. */
    public String nonceFromLog() {
        for (String line : logLines()) {
            Matcher matcher = NONCE_LINE.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            context.close();
        } finally {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext loggerContext) {
                loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(logs);
            }
            logs.stop();
            restoredSystemProperties.forEach((name, previous) -> {
                if (previous == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, previous);
                }
            });
        }
    }

    /** Excludes everything that lives in test-classes from component scanning (as a Spring test context does). */
    private static final class TestClassesExcluder extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestClassesExcluder;
        }

        @Override
        public int hashCode() {
            return TestClassesExcluder.class.hashCode();
        }
    }

    public static final class Builder {
        private final Map<String, String> options = new LinkedHashMap<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private final Map<String, Object> defaultProperties = new LinkedHashMap<>();
        private final Map<String, Object> fakeEnvironment = new LinkedHashMap<>();
        private final List<String> debugLoggers = new ArrayList<>();

        private Builder() {
            String url = SampleAdminDb.url();
            option("spring.profiles.active", "dev,test");
            option("server.port", "0");
            option("server.address", "127.0.0.1");
            option("spring.main.banner-mode", "off");
            option("spring.datasource.url", url);
            option("spring.flyway.url", url);
            option("spring.flyway.enabled", "false");
            option("sahaj.admin.enabled", "true");
            option("sahaj.admin.jwt-secret", ADMIN_JWT_SECRET);
            option("sahaj.admin.totp-key", ADMIN_TOTP_KEY_BASE64);
            option("sahaj.admin.totp.dev-bypass", "false");
            option(FLAG, "true");
        }

        /** Sets (or replaces) one command-line option. */
        public Builder option(String name, String value) {
            options.put(name, value);
            return this;
        }

        public Builder removeOption(String name) {
            options.remove(name);
            return this;
        }

        public Builder profiles(String profiles) {
            return option("spring.profiles.active", profiles);
        }

        public Builder flag(String rawValue) {
            return option(FLAG, rawValue);
        }

        public Builder noFlag() {
            return removeOption(FLAG);
        }

        public Builder flywayOn() {
            return removeOption("spring.flyway.enabled");
        }

        public Builder realmOff() {
            return option("sahaj.admin.enabled", "false");
        }

        public Builder realmUnavailable() {
            return option("sahaj.admin.enabled", "true").removeOption("sahaj.admin.totp-key");
        }

        public Builder devBypass(boolean on) {
            return option("sahaj.admin.totp.dev-bypass", Boolean.toString(on));
        }

        /** Raises the log level of the given logger to DEBUG for this run. */
        public Builder debug(String... loggers) {
            Collections.addAll(debugLoggers, loggers);
            return this;
        }

        /** A JVM system property for the duration of the run (restored by close). */
        public Builder systemProperty(String name, String value) {
            systemProperties.put(name, value);
            return this;
        }

        /** A value in the lowest-precedence "default properties" map source. */
        public Builder defaultProperty(String name, Object value) {
            defaultProperties.put(name, value);
            return this;
        }

        /** An operating-system environment variable (a real one cannot be set in-process, so this adds a source of the same type). */
        public Builder environmentVariable(String name, String value) {
            fakeEnvironment.put(name, value);
            return this;
        }

        public AppRun start() {
            List<String> args = new ArrayList<>();
            options.forEach((name, value) -> args.add("--" + name + "=" + value));
            for (String logger : debugLoggers) {
                args.add("--logging.level." + logger + "=DEBUG");
            }
            Map<String, String> previous = new LinkedHashMap<>();
            systemProperties.forEach((name, value) -> {
                previous.put(name, System.getProperty(name));
                System.setProperty(name, value);
            });
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            SpringApplicationBuilder builder = new SpringApplicationBuilder(SahajApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .initializers((ApplicationContextInitializer<ConfigurableApplicationContext>) ctx -> {
                        ctx.getBeanFactory().registerSingleton("dec016TestClassesExcluder", new TestClassesExcluder());
                        if (!fakeEnvironment.isEmpty()) {
                            // Replace the real "systemEnvironment" source by a copy with the extra variables: that is the
                            // source Spring Boot's cloud-platform detection and the relaxed environment-variable names read.
                            Map<String, Object> merged = new LinkedHashMap<>(System.getenv());
                            merged.putAll(fakeEnvironment);
                            ctx.getEnvironment().getPropertySources()
                                    .replace(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                            new SystemEnvironmentPropertySource(
                                                    org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, merged));
                        }
                        if (!defaultProperties.isEmpty()) {
                            ctx.getEnvironment().getPropertySources()
                                    .addLast(new MapPropertySource("dec016DefaultProperties", defaultProperties));
                        }
                    })
                    .listeners((ApplicationListener<ApplicationEvent>) event -> {
                        if (event instanceof ApplicationPreparedEvent && !appender.isStarted()) {
                            // Logging is configured by now; keep every event of this run from here on.
                            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
                            if (factory instanceof LoggerContext loggerContext) {
                                appender.setContext(loggerContext);
                                appender.start();
                                loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
                            }
                        }
                    });
            try {
                ConfigurableApplicationContext context = builder.run(args.toArray(new String[0]));
                return new AppRun(context, appender, previous);
            } catch (RuntimeException | Error failure) {
                previous.forEach((name, old) -> {
                    if (old == null) {
                        System.clearProperty(name);
                    } else {
                        System.setProperty(name, old);
                    }
                });
                throw failure;
            }
        }
    }
}
