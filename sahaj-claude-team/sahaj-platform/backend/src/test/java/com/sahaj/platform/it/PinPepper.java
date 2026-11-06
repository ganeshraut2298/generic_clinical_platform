package com.sahaj.platform.it;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The staff PIN pepper of the integration tests (ADR-010 D3): Base64 of 32 random bytes, generated once per JVM run, never
 * committed. {@link Initializer} puts it in the test application context as {@code sahaj.pin.pepper}; it is applied by the
 * annotation {@link WithPinPepper}. A class without that annotation runs with whatever the environment says, and the classes
 * that test a missing pepper blank it with a test property source.
 */
final class PinPepper {

    static final String VALUE;

    static {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        VALUE = Base64.getEncoder().encodeToString(bytes);
    }

    private PinPepper() {
    }

    static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of("sahaj.pin.pepper=" + VALUE).applyTo(context);
        }
    }
}
