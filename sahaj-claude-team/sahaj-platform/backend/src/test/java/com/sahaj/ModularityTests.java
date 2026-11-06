package com.sahaj;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the modular-monolith boundary rule from {@code CLAUDE.md}: a module may be referenced
 * by another module only through its {@code api} sub-package or a domain event (ADR-001 decision
 * D2). This fails the build if any module reaches into another module's internals.
 *
 * <p>Per ADR-001 D2, this check is scoped to the {@code api}-package rule only. It does not
 * enforce one-schema-per-module; that is deferred to SAH-2 when the first schema exists.
 *
 * <p>This is static analysis of the compiled classes; it starts no Spring context and needs no
 * database (ADR-001 D3).
 *
 * <p>Test classes are left out of the analysis by their location. Modulith already leaves out {@code target/test-classes},
 * but the Maven profile {@code dev-tools} (ADR-008) builds into {@code target-devtools}, whose test classes it would
 * otherwise count, and tests may reach into any module.
 */
class ModularityTests {

    private static final DescribedPredicate<JavaClass> TEST_CLASSES = DescribedPredicate.describe(
            "classes that were loaded from a test-classes directory",
            javaClass -> javaClass.getSource().map(source -> source.getUri().toString().contains("/test-classes/"))
                    .orElse(false));

    private final ApplicationModules modules = ApplicationModules.of(SahajApplication.class, TEST_CLASSES);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}
