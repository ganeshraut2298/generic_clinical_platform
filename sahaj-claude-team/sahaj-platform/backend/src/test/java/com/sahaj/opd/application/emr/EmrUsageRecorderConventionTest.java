package com.sahaj.opd.application.emr;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-019 D10: only the sign step of SAH-51 may call {@link EmrUsageRecorder}. Until that class exists nothing in the
 * application may depend on the recorder. When SAH-51 adds its sign service, its class name is added to
 * {@link #ALLOWED_CALLERS}, and nothing else.
 */
class EmrUsageRecorderConventionTest {

    /** The recorder itself, and the sign service of SAH-51 once it exists. */
    private static final String[] ALLOWED_CALLERS = {"com.sahaj.opd.application.emr.EmrUsageRecorder",
            "com.sahaj.opd.application.emr.finish.EmrSignService"};

    private static ArchRule onlyTheSignServiceMayCallTheRecorder() {
        return noClasses().that().doNotHaveFullyQualifiedName(ALLOWED_CALLERS[0])
                .and().doNotHaveFullyQualifiedName(ALLOWED_CALLERS[1])
                .should().dependOnClassesThat().haveFullyQualifiedName(ALLOWED_CALLERS[0])
                .because("only the sign step may write the ranking counts (ADR-019 D10)");
    }

    @Test
    void noClassOfTheApplicationDependsOnTheRecorderExceptTheAllowedCallers() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sahaj");

        onlyTheSignServiceMayCallTheRecorder().check(application);
    }

    @Test
    void theRuleFailsWhenAnotherClassCallsTheRecorder() {
        JavaClasses sample = new ClassFileImporter().importClasses(RogueCaller.class, EmrUsageRecorder.class);

        assertThatThrownBy(() -> onlyTheSignServiceMayCallTheRecorder().check(sample))
                .isInstanceOf(AssertionError.class).hasMessageContaining("RogueCaller");
    }

    /** A stand-in for a class that must not be allowed to count usage. */
    static class RogueCaller {
        void count(EmrUsageRecorder recorder) {
            recorder.recordSignedVisit(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Set.of());
        }
    }
}
