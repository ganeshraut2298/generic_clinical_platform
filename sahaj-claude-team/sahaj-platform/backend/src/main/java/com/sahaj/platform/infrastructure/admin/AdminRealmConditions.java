package com.sahaj.platform.infrastructure.admin;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * When the admin realm exists (ADR-003 D3 "Negative"): {@code sahaj.admin.enabled=true} and both
 * secrets present gives the full realm; enabled with a secret missing gives only a chain that
 * answers 503 admin-unavailable; disabled (the default) gives nothing at all.
 * Plain environment reads on purpose, so a secret value is never placed in an expression.
 */
public final class AdminRealmConditions {

    private AdminRealmConditions() {
    }

    static boolean secretsConfigured(Environment env) {
        return notBlank(env.getProperty("sahaj.admin.jwt-secret")) && notBlank(env.getProperty("sahaj.admin.totp-key"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Both secrets are present. */
    public static class SecretsConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return secretsConfigured(context.getEnvironment());
        }
    }

    /** At least one secret is missing. */
    public static class SecretsMissing implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !secretsConfigured(context.getEnvironment());
        }
    }
}
