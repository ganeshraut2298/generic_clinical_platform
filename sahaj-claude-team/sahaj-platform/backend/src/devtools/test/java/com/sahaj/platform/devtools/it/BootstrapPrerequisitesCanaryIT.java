package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import org.junit.jupiter.api.Test;

/**
 * SAH-42 canary (code review findings 2 and 3, security condition "do not switch the feature on until the database ITs ran").
 *
 * <p>The Bootstrap database test classes SKIP, with a message, while a HUMAN prerequisite is missing (role
 * {@code sahaj_admin_definer}, the admin tables, {@code platform.admin_preauth_create}: the ordering guard and the owner's first sign-in, ADR-018 Revision 5). A skip is green, so a missing
 * prerequisite could hide that none of the 100+ database tests ran. This class makes that visible:
 *
 * <ul>
 *   <li>with {@code -Dsahaj.bootstrap.requireDb=true} (or the environment variable {@code SAHAJ_BOOTSTRAP_REQUIRE_DB=true}) a missing
 *       prerequisite FAILS the build, naming the missing item; this is the run the merge approval requires
 *       ({@code mvn -f backend/pom.xml -Pdev-tools verify ... "-Dit.test=Bootstrap*IT,PackagedJar*IT,DevToolsGuardCallbackIT"
 *       -Dsahaj.bootstrap.requireDb=true});
 *   <li>without the property the same condition is a skip that prints the same message, so the normal gate stays green while the
 *       human items are open;
 *   <li>a stray admin row FAILS in both modes (it would turn every database test into a meaningless run), with a message that names
 *       the clean-up.
 * </ul>
 */
class BootstrapPrerequisitesCanaryIT {

    private static String missing() {
        StringBuilder text = new StringBuilder();
        if (!SampleAdminDb.roleExists()) {
            text.append("- ").append(SampleAdminDb.NO_ROLE).append('\n');
        }
        if (!SampleAdminDb.tablesPresent()) {
            text.append("- ").append(SampleAdminDb.NO_TABLES).append('\n');
        } else if (!BootstrapDb.preauthFunctionPresent()) {
            text.append("- ").append(BootstrapDb.NO_PREAUTH).append('\n');
        }
        return text.toString();
    }

    @Test
    void theHumanPrerequisitesOfTheDatabaseTestsArePresent() {
        String missing = missing();
        if (missing.isEmpty()) {
            return;
        }
        String message = "SAH-42 database tests cannot run, human prerequisites missing in sahaj_test:\n" + missing
                + "Every Bootstrap database IT is skipped until they exist. Do not approve the database part of SAH-42 on this run.";
        if (BootstrapDb.requireDb()) {
            throw new AssertionError(message);
        }
        System.out.println("SAH-42 CANARY (skip, -Dsahaj.bootstrap.requireDb is not true): " + message);
        assumeTrue(false, "PRECONDITION NOT MET: " + message);
    }

    @Test
    void noStrayAdminRowExistsSoTheDatabaseTestsMeanSomething() {
        // SampleAdminDb.adminCount() answers 0 when the admin tables do not exist, so without this guard the test would "pass"
        // having looked at nothing (code review N1). With the tables missing it skips with the reason; with requireDb=true the
        // first canary test already fails the build for the same missing item, so the skip hides nothing.
        if (BootstrapDb.requireDb() && !SampleAdminDb.tablesPresent()) {
            throw new AssertionError("SAH-42 stray-admin check cannot count, " + SampleAdminDb.NO_TABLES);
        }
        assumeTrue(SampleAdminDb.tablesPresent(), "PRECONDITION NOT MET, nothing was counted: " + SampleAdminDb.NO_TABLES);
        BootstrapDb.failIfAnAdminExists();
    }

    @Test
    void whenTheRequireDbSwitchIsOnAndEverythingIsPresentTheBootstrapObjectsMustAlsoExistOnceTheCallbackHasRun() {
        // Only meaningful after a database class has started the application once with Flyway on (finding 2). A run that selects
        // only this class cannot start one, so the check stands down unless the objects were already created.
        assumeTrue(missing().isEmpty(), "human prerequisites missing: see the first canary test");
        assumeTrue(BootstrapDb.requireDb(), "-Dsahaj.bootstrap.requireDb is not true");
        assumeTrue(BootstrapDb.tablePresent(), "the dev-tools callback has not run in this database yet");
        assertThat(BootstrapDb.objectsPresent()).as("the table exists, so all five Revision 3 functions and the lease columns must").isTrue();
        assertThat(BootstrapDb.oldShapePresent()).as("no Revision 2 function shape may remain").isFalse();
    }
}
