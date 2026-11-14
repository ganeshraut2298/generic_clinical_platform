package com.sahaj;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
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

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        ImportOption noTests = location -> !location.contains("/test-classes/");
        return new ClassFileImporter().withImportOption(noTests).importPackages("com.sahaj");
    }

    /**
     * ADR-022 D1 (SAH-58): the module graph is platform, patient, opd, billing, ipd. {@code ipd} reaches
     * {@code billing.api} (the course credit port); {@code billing} must never import {@code ipd}. If billing ever needs
     * IPD data it declares a port in {@code billing.api} that ipd implements.
     */
    @Test
    void billingNeverDependsOnIpd() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.sahaj.billing..")
                .should().dependOnClassesThat().resideInAPackage("com.sahaj.ipd..");
        rule.check(productionClasses());
    }

    /** ADR-022 D1: {@code opd} is the lowest business module; it reaches billing and ipd only through its own ports. */
    @Test
    void opdNeverDependsOnBillingOrIpd() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.sahaj.opd..")
                .should().dependOnClassesThat().resideInAnyPackage("com.sahaj.billing..", "com.sahaj.ipd..");
        rule.check(productionClasses());
    }

    /** ADR-022 D1: the one new edge is {@code ipd -> billing.api}; ipd never reaches billing's internals. */
    @Test
    void ipdReachesBillingOnlyThroughItsApi() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.sahaj.ipd..")
                .should().dependOnClassesThat().resideInAnyPackage("com.sahaj.billing.application..",
                        "com.sahaj.billing.web..", "com.sahaj.billing.infrastructure..", "com.sahaj.billing.domain..");
        rule.check(productionClasses());
    }
}
