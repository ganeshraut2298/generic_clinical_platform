package com.sahaj.platform.infrastructure.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;

/** The admin realm is switched on but a secret is missing: every admin route answers 503. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "sahaj.admin", name = "enabled", havingValue = "true")
@Conditional(AdminRealmConditions.SecretsMissing.class)
public @interface ConditionalOnAdminUnavailable {
}
