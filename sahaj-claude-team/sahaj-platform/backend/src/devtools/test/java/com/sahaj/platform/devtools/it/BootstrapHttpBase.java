package com.sahaj.platform.devtools.it;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Base of the SAH-42 integration tests that talk to the running application AND the real bootstrap objects in
 * {@code sahaj_test}. One application (registration ON, Flyway off, the objects already created) is shared by the class.
 *
 * <p>PRECONDITIONS: the human prerequisites of DEC-016 (role {@code sahaj_admin_definer}, the admin tables, and the parked
 * migration {@code V2026_11_15_1010}) and an empty {@code platform.platform_admin}. When one is missing the whole class is
 * skipped with a message naming it ({@link BootstrapDb}). When the objects are not created yet, the first class that runs
 * starts the application once with Flyway on, so the dev-tools callback creates them; a callback that fails is a failure.
 *
 * <p>Each test starts from a clean slate (count 0, no lock, no ticket) and removes the admin rows and audit rows it created
 * through {@link SampleAdminDb#cleanup} (the admin tables are append-only by design, so only the test owner role can). The
 * state row is reset again afterwards, so a locked state never leaks into another class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BootstrapHttpBase {

    protected AppRun app;
    protected SampleAdminDb.Snapshot snapshot;

    /** Override to change the application (extra options, debug loggers). */
    protected AppRun.Builder appBuilder() {
        return BootstrapSupport.builder();
    }

    @BeforeAll
    void startApplication() {
        BootstrapSupport.ensureObjectsOrSkip();
        BootstrapDb.failIfAnAdminExists();
        app = appBuilder().start();
    }

    @AfterAll
    void stopApplication() {
        if (app != null) {
            app.close();
        }
        if (BootstrapDb.objectsPresent()) {
            BootstrapDb.resetState();
        }
    }

    @BeforeEach
    void cleanSlate() {
        BootstrapDb.failIfAnAdminExists();
        snapshot = SampleAdminDb.snapshot();
        BootstrapDb.resetState();
    }

    @AfterEach
    void removeWhatTheTestCreated() {
        if (snapshot != null) {
            SampleAdminDb.cleanup(snapshot);
        }
        if (BootstrapDb.objectsPresent()) {
            BootstrapDb.resetState();
        }
    }

    /** A different address per test, so no per-address counter of the sign-in functions can carry over. */
    protected static String uniqueEmail() {
        return "qa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
