package com.sahaj.platform.web.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one of the four admin sign-in endpoints that need no access token (they validate their own
 * pre-auth token or refresh cookie). Every other handler under {@code /api/v1/admin} must carry
 * {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")}; a convention test fails the build otherwise
 * (ADR-003 D8).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}
