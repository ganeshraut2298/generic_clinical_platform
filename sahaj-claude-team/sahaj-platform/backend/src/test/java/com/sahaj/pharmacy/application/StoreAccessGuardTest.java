package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;

/**
 * ADR-028 D4 layer 3: unknown, foreign, switched-off and unmapped stores are 404 (the store is not confirmed); a mapped
 * caller without the capability is 403 store-permission-denied carrying ids only (the handler audits it, N-1); the owner reaches every store.
 */
class StoreAccessGuardTest {

    private final PharmacyFixture f = new PharmacyFixture();
    private final PharmacyViews.Store central = f.createCentral();
    private final PharmacyViews.Store opd = f.createStore("Test OPD Store", "OPD", StoreType.SUB_STORE);

    private PharmacyCaller caller(PharmacyFixture.Person p) {
        return f.gate.require(PharmacyFixture.TENANT, p.userId(), AccessLevel.PHARMACY_ACCESS);
    }

    @Test
    void aMappedStoreUserMayRaiseIndentsInTheirStore() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        Store store = f.guard.require(caller(f.nurse), opd.id(), StoreCapability.RAISE_INDENT);
        assertThat(store.id()).isEqualTo(opd.id());
        assertThat(f.auditActions()).doesNotContain("PHARMACY_STORE_ACCESS_DENIED");
    }

    @Test
    void anUnmappedStoreIsNotFoundAndNotForbiddenSoItsExistenceIsNotConfirmed() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.audit.clear();
        assertThatThrownBy(() -> f.guard.require(caller(f.nurse), central.id(), StoreCapability.RAISE_INDENT))
                .isInstanceOf(NotFoundException.class);
        assertThat(f.audit).isEmpty();
    }

    @Test
    void anUnknownIdAndAnotherClinicsStoreLookTheSame() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        UUID foreign = UUID.randomUUID();
        f.storeRows.put(foreign, new PharmacyFixture.Held<>(PharmacyFixture.OTHER_TENANT, new Store(foreign,
                UUID.randomUUID(), "Other", "OTH", StoreType.CENTRAL, null, false, false, false, true, 0L)));

        assertThatThrownBy(() -> f.guard.require(caller(f.nurse), UUID.randomUUID(), StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class).hasMessage("This record was not found.");
        assertThatThrownBy(() -> f.guard.require(caller(f.nurse), foreign, StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class).hasMessage("This record was not found.");
        assertThatThrownBy(() -> f.guard.require(caller(f.owner), foreign, StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aSwitchedOffStoreIsNotFoundEvenForTheOwner() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        PharmacyCaller nurse = caller(f.nurse);
        f.storeService.switchOff(PharmacyFixture.TENANT, f.owner.userId(), opd.id());
        assertThatThrownBy(() -> f.guard.require(nurse, opd.id(), StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.guard.require(caller(f.owner), opd.id(), StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aMappedCallerWithoutTheCapabilityIsRefusedWithTheIdsTheAuditNeedsAndTheGuardWritesNothing() {
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        PharmacyCaller staff = caller(f.storeStaff);
        f.audit.clear();
        f.transactions.events.clear();

        assertThatThrownBy(() -> f.guard.require(staff, opd.id(), StoreCapability.APPROVE))
                .isInstanceOf(StoreAccessDeniedException.class)
                .hasMessage("You cannot do this in this store.")
                .satisfies(ex -> {
                    StoreAccessDeniedException denied = (StoreAccessDeniedException) ex;
                    assertThat(denied.problemType()).isEqualTo("store-permission-denied");
                    assertThat(denied.membershipId()).isEqualTo(staff.membershipId());
                    assertThat(denied.capability()).isEqualTo(StoreCapability.APPROVE);
                    assertThat(denied.storeId()).isEqualTo(opd.id());
                });
        // N-1 / L7: the guard opens no transaction and writes no audit; the handler does it after the caller is done
        assertThat(f.audit).isEmpty();
        assertThat(f.transactions.events).isEmpty();
    }

    @Test
    void theDenialAuditorWritesOneIdsOnlyRowInOneShortTransactionOfTheDefaultKind() {
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        PharmacyCaller staff = caller(f.storeStaff);
        StoreAccessDeniedException denied = f.guard.refuse(staff, opd.id(), StoreCapability.APPROVE);
        f.audit.clear();
        f.transactions.events.clear();
        f.transactions.definitions.clear();

        f.denialAuditor.record(denied);

        assertThat(f.audit).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo("PHARMACY_STORE_ACCESS_DENIED");
            assertThat(a.entityType()).isEqualTo("PHARMACY_STORE");
            assertThat(a.entityId()).isEqualTo(opd.id());
            assertThat(a.before()).isNull();
            assertThat(a.after()).containsOnlyKeys("membershipId", "capability");
            assertThat(a.after().get("membershipId")).isEqualTo(staff.membershipId().toString());
            assertThat(a.after().get("capability")).isEqualTo("APPROVE");
        });
        assertThat(f.transactions.events).containsExactly("begin", "commit");
        assertThat(f.transactions.definitions).singleElement().satisfies(definition -> {
            // not REQUIRES_NEW: it never needs a second connection
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(definition.isReadOnly()).isFalse();
        });
    }

    @Test
    void anAllowedCallAnUnknownStoreAndAnUnmappedStoreStartNoTransactionAndWriteNoAudit() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        f.transactions.events.clear();
        f.guard.require(caller(f.nurse), opd.id(), StoreCapability.RAISE_INDENT);
        assertThatThrownBy(() -> f.guard.require(caller(f.nurse), central.id(), StoreCapability.RAISE_INDENT))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.guard.require(caller(f.nurse), UUID.randomUUID(), StoreCapability.RAISE_INDENT))
                .isInstanceOf(NotFoundException.class);
        assertThat(f.transactions.events).isEmpty();
    }

    @Test
    void storeStaffAndNursesNeverGetScheduleXAndTheOwnerDoesNotEither() {
        f.map(opd, f.storeStaff, StoreRole.STORE_USER);
        assertThatThrownBy(() -> f.guard.require(caller(f.storeStaff), opd.id(), StoreCapability.RECEIVE_SCHEDULE_X))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> f.guard.require(caller(f.owner), opd.id(), StoreCapability.RECEIVE_SCHEDULE_X))
                .isInstanceOf(StoreAccessDeniedException.class);
        f.map(opd, f.pharmacist, StoreRole.STORE_USER);
        f.guard.require(caller(f.pharmacist), opd.id(), StoreCapability.RECEIVE_SCHEDULE_X);
    }

    @Test
    void theOwnerReachesEveryActiveStoreWithoutAMappingForTheOwnersCapabilities() {
        for (StoreCapability capability : new StoreCapability[] {StoreCapability.RAISE_INDENT, StoreCapability.APPROVE,
                StoreCapability.RECEIVE, StoreCapability.CONSUME_RETURN, StoreCapability.UPLOAD_OPENING_STOCK}) {
            assertThat(f.guard.require(caller(f.owner), opd.id(), capability).id()).isEqualTo(opd.id());
            assertThat(f.guard.require(caller(f.owner), central.id(), capability).id()).isEqualTo(central.id());
        }
    }

    @Test
    void aPharmacistWithNoMappingIsNotInAnyStore() {
        assertThatThrownBy(() -> f.guard.require(caller(f.pharmacist), opd.id(), StoreCapability.RECEIVE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void requireMappedWantsAMappingEvenFromTheOwner() {
        f.map(opd, f.nurse, StoreRole.STORE_USER);
        assertThat(f.guard.requireMapped(caller(f.nurse), opd.id()).store().id()).isEqualTo(opd.id());
        assertThatThrownBy(() -> f.guard.requireMapped(caller(f.owner), opd.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.guard.requireMapped(caller(f.nurse), central.id())).isInstanceOf(NotFoundException.class);
    }
}
