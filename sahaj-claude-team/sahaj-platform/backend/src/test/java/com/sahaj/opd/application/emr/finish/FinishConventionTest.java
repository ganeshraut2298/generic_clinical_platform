package com.sahaj.opd.application.emr.finish;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-025 D1, D7: the specialty finish contributors are called by the sign step only, inside its transaction, and the sign
 * service is the only caller of the ranking recorder (the recorder's own convention test lists it). A new caller fails the
 * build. The profile beans may implement the interface; nothing else may depend on it.
 */
class FinishConventionTest {

    private static final String CONTRIBUTOR = "com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor";

    private static ArchRule onlyTheSignServiceCallsTheContributors() {
        return noClasses().that().doNotHaveFullyQualifiedName("com.sahaj.opd.application.emr.finish.EmrSignService")
                .and().doNotHaveFullyQualifiedName(CONTRIBUTOR)
                .and().resideOutsideOfPackage("com.sahaj.opd.application.emr.specialty")
                .should().dependOnClassesThat().haveFullyQualifiedName(CONTRIBUTOR)
                .because("what a specialty adds at the end of a visit is written only inside the sign transaction (ADR-025 D7)");
    }

    @Test
    void noClassOutsideTheSignServiceAndTheProfileBeansDependsOnTheContributorInterface() {
        JavaClasses application = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sahaj");

        onlyTheSignServiceCallsTheContributors().check(application);
    }

    @Test
    void theRuleFailsWhenAnotherClassCallsAContributor() {
        JavaClasses sample = new ClassFileImporter().importClasses(RogueCaller.class,
                com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor.class);

        assertThatThrownBy(() -> onlyTheSignServiceCallsTheContributors().check(sample)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("RogueCaller");
    }

    /** A stand-in for a class that must not be allowed to call a contributor. */
    static class RogueCaller {
        void apply(com.sahaj.opd.application.emr.specialty.SpecialtyFinishContributor contributor) {
            contributor.apply(null);
        }
    }
}
