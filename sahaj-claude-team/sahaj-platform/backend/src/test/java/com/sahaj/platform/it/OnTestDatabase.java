package com.sahaj.platform.it;

import com.sahaj.platform.support.FailureProbeController;
import com.sahaj.platform.support.TenantProbeController;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Standard set-up of a database integration test: the whole application on the {@code sahaj_test}
 * database (profile {@code test}, app as sahaj_app, Flyway as sahaj_migrator) with MockMvc through
 * the real security filter chain, plus the two test-only controllers. All tests that use it share
 * one Spring context.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TenantProbeController.class, FailureProbeController.class})
@interface OnTestDatabase {
}
