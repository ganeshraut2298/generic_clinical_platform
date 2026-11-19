package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Body;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleOffException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract general rule 2 (code review F-3, security L1): module off 403, then the access level 403, then 400 for the body,
 * a path id or a query value, then 404. Every operation with a body asks for it only after its gate, so a body that cannot be
 * read gets the 403 and never the 400. The bodies here count how often they are read and always fail when read.
 */
class BodyAfterGateTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final PharmacyFixture f = new PharmacyFixture();
    private final AtomicInteger reads = new AtomicInteger();

    private <T> Body<T> unreadable() {
        return () -> {
            reads.incrementAndGet();
            throw new IllegalStateException("the body was read");
        };
    }

    /** Runs every operation that takes a body and needs the owner (the working store is tested on its own). */
    private void everyBodyOperationAs(UUID userId, Consumer<Runnable> expectRefusal) {
        UUID id = UUID.randomUUID();
        expectRefusal.accept(() -> f.unitService.save(TENANT, userId, unreadable()));
        expectRefusal.accept(() -> f.storeService.create(TENANT, userId, unreadable()));
        expectRefusal.accept(() -> f.storeService.update(TENANT, userId, id.toString(), unreadable()));
        expectRefusal.accept(() -> f.storeService.update(TENANT, userId, "not-a-uuid", unreadable()));
        expectRefusal.accept(() -> f.staffService.map(TENANT, userId, id.toString(), unreadable()));
        expectRefusal.accept(() -> f.staffService.map(TENANT, userId, "not-a-uuid", unreadable()));
        expectRefusal.accept(() -> f.staffService.change(TENANT, userId, id.toString(), id.toString(), unreadable()));
        expectRefusal.accept(() -> f.staffService.change(TENANT, userId, "not-a-uuid", "not-a-uuid", unreadable()));
    }

    @Test
    void withTheModuleOffEveryOperationAnswersModuleOffAndNeverReadsTheBody() {
        f.pharmacyOn = false;
        everyBodyOperationAs(f.owner.userId(), action -> assertThatThrownBy(action::run)
                .isInstanceOf(ModuleOffException.class));
        assertThat(reads).hasValue(0);
    }

    @Test
    void aCallerBelowTheAccessLevelAnswers403AndNeverReadsTheBody() {
        f.createCentral();
        everyBodyOperationAs(f.nurse.userId(), action -> assertThatThrownBy(action::run)
                .isInstanceOf(ForbiddenException.class));
        assertThat(reads).hasValue(0);
    }

    @Test
    void aPharmacistWhoMayViewButNotEditStillGets403BeforeTheBody() {
        f.createCentral();
        everyBodyOperationAs(f.pharmacist.userId(), action -> assertThatThrownBy(action::run)
                .isInstanceOf(ForbiddenException.class));
        assertThat(reads).hasValue(0);
    }

    @Test
    void anAllowedCallerReadsTheBodyAfterTheGateAndAPathIdIsCheckedBeforeIt() {
        f.createCentral();
        assertThatThrownBy(() -> f.storeService.create(TENANT, f.owner.userId(), unreadable()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(1);

        // a path id that is not an id is 400 bad-request before the body is read
        reads.set(0);
        assertThatThrownBy(() -> f.storeService.update(TENANT, f.owner.userId(), "not-a-uuid", unreadable()))
                .isInstanceOf(PharmacyProblemException.class);
        assertThat(reads).hasValue(0);
    }

    @Test
    void theWorkingStoreFollowsTheSameOrder() {
        f.pharmacyOn = false;
        assertThatThrownBy(() -> f.contextService.setWorkingStore(TENANT, f.owner.userId(), unreadable()))
                .isInstanceOf(ModuleOffException.class);
        f.pharmacyOn = true;
        // a nurse with no store mapping has no access to Pharmacy and Inventory at all
        assertThatThrownBy(() -> f.contextService.setWorkingStore(TENANT, f.nurse.userId(), unreadable()))
                .isInstanceOf(ForbiddenException.class);
        assertThat(reads).hasValue(0);
    }

    @Test
    void aMappedStoreUserMayReadTheirBodyForTheWorkingStoreOnceAllowed() {
        f.createCentral();
        PharmacyViews.Store ward = f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
        f.map(ward, f.nurse, StoreRole.STORE_USER);
        assertThatThrownBy(() -> f.contextService.setWorkingStore(TENANT, f.nurse.userId(), unreadable()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(1);
    }
}
