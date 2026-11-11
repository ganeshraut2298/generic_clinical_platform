package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.devtools.DevToolsChecks;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The start-time conditions B2 to B7 of ADR-018 D2 (B1 is "the class exists", which only a build made with the Maven
 * profile {@code dev-tools} gives). Each is a {@link Condition}, not a startup failure: when one is false no bootstrap
 * bean exists and the reserved-path filter in main code answers the empty 404.
 *
 * <ul>
 *   <li>B2 profile {@code dev}, and every active profile {@code dev} or {@code test} ({@link DevToolsChecks});
 *   <li>B3 {@value #ENABLED} equals {@code true}, ignoring case, read from the {@link Environment} (so
 *       {@code SAHAJ_ADMIN_BOOTSTRAP_ENABLED} in {@code .env} works), no trimming;
 *   <li>B4 {@value #PIN_HASH} is a BCrypt hash of cost 12 to 16 (missing, blank, another format or a lower cost is off);
 *   <li>B5 the admin realm is on ({@code @ConditionalOnAdminRealm} on {@link ConditionalOnAdminBootstrap});
 *   <li>B6 no proxy configuration, no cloud platform; B7 {@code server.address} is a loopback literal.
 * </ul>
 *
 * <p>Plain environment reads. No value of the hash is ever printed, measured or put in a message.
 */
public final class BootstrapConditions {

    public static final String ENABLED = "sahaj.admin.bootstrap.enabled";
    public static final String PIN_HASH = "sahaj.admin.bootstrap.pin-hash";

    /** B4: {@code $2a$}, {@code $2b$} or {@code $2y$}, cost 12 to 16, 22 salt and 31 hash characters. */
    static final Pattern PIN_HASH_FORMAT = Pattern.compile("^\\$2[aby]\\$(1[2-6])\\$[./A-Za-z0-9]{53}$");

    static final String REASON_HASH = "the PIN hash is missing or not a valid BCrypt hash (cost 12 to 16)";
    static final String REASON_PROFILES = "the active profiles are not dev";
    static final String REASON_REALM = "the admin realm is off";
    static final String REASON_PROXY = "proxy settings or a cloud platform are present";
    static final String REASON_ADDRESS = "server.address is not a loopback address";

    private BootstrapConditions() {
    }

    /** B3. */
    public static boolean switchOn(Environment env) {
        return "true".equalsIgnoreCase(env.getProperty(ENABLED));
    }

    /** B4. */
    public static boolean pinHashValid(Environment env) {
        String hash = env.getProperty(PIN_HASH);
        return hash != null && PIN_HASH_FORMAT.matcher(hash).matches();
    }

    /** B5, for the start-up line only: the bean conditions use {@code @ConditionalOnAdminRealm}, which reads the same three things. */
    static boolean adminRealmOn(Environment env) {
        return "true".equalsIgnoreCase(env.getProperty("sahaj.admin.enabled"))
                && notBlank(env.getProperty("sahaj.admin.jwt-secret"))
                && notBlank(env.getProperty("sahaj.admin.totp-key"));
    }

    /** The first failing reason among B4, B2, B5, B6 and B7, in the order of the start-up line (ADR-018 D2), or empty. */
    static Optional<String> firstFailingReason(Environment env) {
        if (!pinHashValid(env)) {
            return Optional.of(REASON_HASH);
        }
        if (!DevToolsChecks.profilesAllowed(env)) {
            return Optional.of(REASON_PROFILES);
        }
        if (!adminRealmOn(env)) {
            return Optional.of(REASON_REALM);
        }
        if (!DevToolsChecks.noProxyConfiguration(env)) {
            return Optional.of(REASON_PROXY);
        }
        if (!DevToolsChecks.bindsToLoopback(env)) {
            return Optional.of(REASON_ADDRESS);
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static class ProfilesAllowed implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DevToolsChecks.profilesAllowed(context.getEnvironment());
        }
    }

    public static class SwitchOn implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return switchOn(context.getEnvironment());
        }
    }

    public static class PinHashValid implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return pinHashValid(context.getEnvironment());
        }
    }

    public static class NoProxyConfiguration implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DevToolsChecks.noProxyConfiguration(context.getEnvironment());
        }
    }

    public static class BindsToLoopback implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DevToolsChecks.bindsToLoopback(context.getEnvironment());
        }
    }
}
