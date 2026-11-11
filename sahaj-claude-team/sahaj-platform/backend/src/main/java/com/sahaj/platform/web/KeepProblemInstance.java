package com.sahaj.platform.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller whose problem documents carry an {@code instance} that the controller itself set to a fixed value
 * (never taken from the request), so {@link ProblemInstanceScrubber} must leave it alone. Every other controller keeps
 * the rule that {@code instance} is cleared. Used by the dev-only bootstrap registration (docs/api/admin.yaml
 * {@code Problem.instance}, ADR-018 D2 "instance", Revision 3).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface KeepProblemInstance {
}
