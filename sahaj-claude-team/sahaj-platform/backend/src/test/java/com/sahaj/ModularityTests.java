package com.sahaj;

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
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(SahajApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}
