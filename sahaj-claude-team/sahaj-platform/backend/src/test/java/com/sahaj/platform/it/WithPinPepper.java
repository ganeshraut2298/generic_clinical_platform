package com.sahaj.platform.it;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.ContextConfiguration;

/** Runs the test application context with the random staff PIN pepper of this run ({@link PinPepper}). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ContextConfiguration(initializers = PinPepper.Initializer.class)
@interface WithPinPepper {
}
