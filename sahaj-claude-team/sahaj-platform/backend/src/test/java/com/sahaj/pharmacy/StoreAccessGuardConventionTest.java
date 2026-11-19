package com.sahaj.pharmacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyCaller;
import com.sahaj.pharmacy.application.StoreAccessDeniedException;
import com.sahaj.pharmacy.application.StoreAccessGuard;
import com.sahaj.pharmacy.application.StoreDenialAuditor;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.web.PharmacyApi;
import com.sahaj.pharmacy.web.PharmacyProblemHandler;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

/**
 * Convention test for the store guard (code review N-1, security L7; ADR-028 R3). The guard throws a
 * {@code StoreAccessDeniedException} that carries ids and writes nothing: the {@code PHARMACY_STORE_ACCESS_DENIED} row is
 * written by the pharmacy problem handler through {@code StoreDenialAuditor}, after the caller's transaction has ended,
 * in one short transaction. A request therefore never holds a second pooled connection for the denial. These rules keep it
 * that way, next to the behaviour tests ({@code StoreAccessGuardTest}, {@code StoreAccessGuardTenantBindingTest}).
 */
class StoreAccessGuardConventionTest {

    private static JavaClasses productionClasses() {
        ImportOption noTests = location -> !location.contains("/test-classes/");
        return new ClassFileImporter().withImportOption(noTests).importPackages("com.sahaj.pharmacy");
    }

    /** The guard owns no transaction and no audit: it cannot take a second connection. */
    @Test
    void theGuardStartsNoTransactionAndWritesNoAudit() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().haveFullyQualifiedName(StoreAccessGuard.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(TransactionTemplate.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(PlatformTransactionManager.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(AuditLog.class.getName());
        rule.check(productionClasses());
    }

    /**
     * Nothing in the pharmacy module asks for a new transaction inside the caller's: that is what took the second
     * connection. An annotation value is not a field access in the bytecode, so the {@code @Transactional} annotations of
     * classes and methods are read directly; the field access and the template setters are checked as well.
     */
    @Test
    void noPharmacyClassUsesRequiresNew() {
        ArchRuleDefinition.noClasses().should().accessField(Propagation.class, "REQUIRES_NEW").check(productionClasses());
        ArchRuleDefinition.noClasses().should().callMethod(TransactionTemplate.class, "setPropagationBehavior", int.class)
                .orShould().callMethod(TransactionTemplate.class, "setPropagationBehaviorName", String.class)
                .check(productionClasses());
        ArchRuleDefinition.classes().should(notRequireANewTransaction()).check(productionClasses());
    }

    /** The annotation rule fires on a violation (the fixtures below are test classes: the production import excludes them). */
    @Test
    void theRequiresNewRuleFailsOnAViolation() {
        for (Class<?> violation : new Class<?>[] {RequiresNewOnAMethod.class, RequiresNewOnAClass.class}) {
            JavaClasses imported = new ClassFileImporter().importClasses(violation);
            assertThatThrownBy(() -> ArchRuleDefinition.classes().should(notRequireANewTransaction()).check(imported))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("REQUIRES_NEW");
        }
    }

    /** Rule fixture. */
    static class RequiresNewOnAMethod {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void run() {
        }
    }

    /** Rule fixture. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    static class RequiresNewOnAClass {
    }

    private static ArchCondition<JavaClass> notRequireANewTransaction() {
        return new ArchCondition<>("not declare @Transactional(propagation = REQUIRES_NEW)") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.tryGetAnnotationOfType(Transactional.class).ifPresent(t -> report(item.getName(), t, events));
                for (JavaMethod method : item.getMethods()) {
                    method.tryGetAnnotationOfType(Transactional.class)
                            .ifPresent(t -> report(method.getFullName(), t, events));
                }
            }

            private void report(String where, Transactional transactional, ConditionEvents events) {
                if (transactional.propagation() == Propagation.REQUIRES_NEW) {
                    events.add(SimpleConditionEvent.violated(where, where + " uses propagation REQUIRES_NEW"));
                }
            }
        };
    }

    /** Every pharmacy controller implements {@link PharmacyApi}, so that the pharmacy problem handler applies to it. */
    @Test
    void everyPharmacyRestControllerImplementsPharmacyApi() {
        ArchRule rule = ArchRuleDefinition.classes().that().resideInAPackage("com.sahaj.pharmacy.web..")
                .and().areAnnotatedWith(RestController.class)
                .should().implement(PharmacyApi.class);
        rule.check(productionClasses());
    }

    /**
     * Nothing in the pharmacy module catches the store denial: a caught denial would swallow the refusal and its audit.
     * Only the problem handler (an {@code @ExceptionHandler}, not a catch block) deals with it.
     */
    @Test
    void noPharmacyClassCatchesTheStoreDenial() {
        ArchRuleDefinition.classes().should(notCatchTheStoreDenial()).check(productionClasses());
    }

    private static ArchCondition<JavaClass> notCatchTheStoreDenial() {
        return new ArchCondition<>("not catch StoreAccessDeniedException") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnit unit : item.getCodeUnits()) {
                    for (TryCatchBlock block : unit.getTryCatchBlocks()) {
                        if (block.getCaughtThrowables().stream().anyMatch(t -> t.isEquivalentTo(StoreAccessDeniedException.class))) {
                            events.add(SimpleConditionEvent.violated(unit.getFullName(),
                                    unit.getFullName() + " catches StoreAccessDeniedException"));
                        }
                    }
                }
            }
        };
    }

    /**
     * Security I14: the denial is not caught in {@code com.sahaj.pharmacy.application} through a supertype either. A
     * {@code catch (RuntimeException)}, {@code catch (Exception)}, {@code catch (Throwable)} or {@code catch (ForbiddenException)}
     * around a guarded call would swallow the denial and its audit as well as a direct catch. The rule is by type, because
     * which calls reach the guard is not visible in the bytecode. A catch that is needed and does not wrap a guarded call is
     * listed in {@link #ALLOWED_BROAD_CATCHES} with the reason; the list is empty now. The handler around the auditor is in
     * {@code com.sahaj.pharmacy.web}, outside this rule.
     */
    @Test
    void noApplicationClassCatchesTheStoreDenialThroughASupertype() {
        ArchRuleDefinition.classes().that().resideInAPackage("com.sahaj.pharmacy.application..")
                .should(notCatchTheDenialOrASupertype(ALLOWED_BROAD_CATCHES)).check(productionClasses());
    }

    /**
     * Allow-list of {@code Class.method} names whose broad catch is justified (it does not wrap a call that reaches
     * {@code StoreAccessGuard.require}). Add an entry only with a comment that says why. The retry on a duplicate key
     * (SQL state 23505) catches {@code DuplicateKeyException}, which is not a supertype of the denial and needs no entry.
     */
    private static final Set<String> ALLOWED_BROAD_CATCHES = Set.of();

    private static final List<Class<?>> DENIAL_AND_SUPERTYPES = List.of(StoreAccessDeniedException.class,
            ForbiddenException.class, RuntimeException.class, Exception.class, Throwable.class);

    private static ArchCondition<JavaClass> notCatchTheDenialOrASupertype(Set<String> allowed) {
        return new ArchCondition<>("not catch StoreAccessDeniedException or ForbiddenException, RuntimeException, Exception, Throwable") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnit unit : item.getCodeUnits()) {
                    String name = item.getSimpleName() + "." + unit.getName();
                    if (allowed.contains(name)) {
                        continue;
                    }
                    for (TryCatchBlock block : unit.getTryCatchBlocks()) {
                        for (JavaClass caught : block.getCaughtThrowables()) {
                            if (DENIAL_AND_SUPERTYPES.stream().anyMatch(caught::isEquivalentTo)) {
                                events.add(SimpleConditionEvent.violated(unit.getFullName(), unit.getFullName() + " catches "
                                        + caught.getName() + ", which can swallow StoreAccessDeniedException"));
                            }
                        }
                    }
                }
            }
        };
    }

    /** The supertype rule fires on each of the four supertypes and the type itself, and an allow-list entry silences it. */
    @Test
    void theSupertypeCatchRuleFailsOnAViolationAndHonoursTheAllowList() {
        for (Class<?> violation : new Class<?>[] {CatchesRuntimeException.class, CatchesException.class,
                CatchesThrowable.class, CatchesForbidden.class, CatchesDenial.class}) {
            JavaClasses imported = new ClassFileImporter().importClasses(violation);
            assertThatThrownBy(() -> ArchRuleDefinition.classes().should(notCatchTheDenialOrASupertype(Set.of()))
                    .check(imported)).isInstanceOf(AssertionError.class).hasMessageContaining("swallow");
            ArchRuleDefinition.classes().should(notCatchTheDenialOrASupertype(Set.of(violation.getSimpleName() + ".run")))
                    .check(imported);
        }
        // a narrower catch (a sibling of the denial) is not flagged
        ArchRuleDefinition.classes().should(notCatchTheDenialOrASupertype(Set.of()))
                .check(new ClassFileImporter().importClasses(CatchesASibling.class));
    }

    /** Rule fixture. */
    static class CatchesRuntimeException {
        void run() {
            try {
                work();
            } catch (RuntimeException e) {
                work();
            }
        }

        static void work() {
        }
    }

    /** Rule fixture. */
    static class CatchesException {
        void run() {
            try {
                CatchesRuntimeException.work();
            } catch (Exception e) {
                CatchesRuntimeException.work();
            }
        }
    }

    /** Rule fixture. */
    static class CatchesThrowable {
        void run() {
            try {
                CatchesRuntimeException.work();
            } catch (Throwable e) {
                CatchesRuntimeException.work();
            }
        }
    }

    /** Rule fixture. */
    static class CatchesForbidden {
        void run() {
            try {
                CatchesRuntimeException.work();
            } catch (ForbiddenException e) {
                CatchesRuntimeException.work();
            }
        }
    }

    /** Rule fixture. */
    static class CatchesDenial {
        void run() {
            try {
                CatchesRuntimeException.work();
            } catch (StoreAccessDeniedException e) {
                CatchesRuntimeException.work();
            }
        }
    }

    /** Rule fixture. */
    static class CatchesASibling {
        void run() {
            try {
                CatchesRuntimeException.work();
            } catch (IllegalArgumentException e) {
                CatchesRuntimeException.work();
            }
        }
    }

    /** Only the pharmacy problem handler writes the denial audit, so it always runs after the caller's transaction. */
    @Test
    void onlyTheProblemHandlerWritesTheDenialAudit() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().doNotHaveFullyQualifiedName(PharmacyProblemHandler.class.getName())
                .should().callMethod(StoreDenialAuditor.class, "record", StoreAccessDeniedException.class);
        rule.check(productionClasses());
    }

    /**
     * {@code StoreAccessGuard.require} is called from the application services only: never from a controller or the
     * infrastructure, so every call runs behind the gate, inside the request's transaction, and no web class can reach a
     * store without the module and access-level checks.
     */
    @Test
    void onlyTheApplicationServicesCallRequire() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.sahaj.pharmacy.application..")
                .should().callMethod(StoreAccessGuard.class, "require", PharmacyCaller.class, UUID.class,
                        StoreCapability.class);
        rule.check(productionClasses());
    }

    /** The denial is a refusal of the caller's request: only the guard throws it. */
    @Test
    void onlyTheGuardThrowsTheStoreDenial() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().doNotHaveFullyQualifiedName(StoreAccessGuard.class.getName())
                .and().doNotHaveFullyQualifiedName("com.sahaj.pharmacy.application.StoreAccessDeniedException")
                .should().callConstructor(com.sahaj.pharmacy.application.StoreAccessDeniedException.class);
        rule.check(productionClasses());
    }
}
