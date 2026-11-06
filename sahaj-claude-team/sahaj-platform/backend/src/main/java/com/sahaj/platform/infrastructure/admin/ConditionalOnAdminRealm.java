package com.sahaj.platform.infrastructure.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only when {@code sahaj.admin.enabled=true} and both admin secrets are present.
 * With the property absent or false (the default) the whole admin feature is invisible and the
 * application behaves as it did before ADM-1.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "sahaj.admin", name = "enabled", havingValue = "true")
@Conditional(AdminRealmConditions.SecretsConfigured.class)
public @interface ConditionalOnAdminRealm {
}
