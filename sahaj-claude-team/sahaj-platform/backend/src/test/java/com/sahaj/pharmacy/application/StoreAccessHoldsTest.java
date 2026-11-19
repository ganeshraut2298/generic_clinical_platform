package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** L18: {@code StoreAccessGuard.holds} is the non-throwing form of the guard that approval requests use to answer 404. */
class StoreAccessHoldsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID OTHER_STORE = UUID.randomUUID();

    private static PharmacyCaller caller(String teamRole, StoreRole role) {
        Store store = new Store(STORE, UUID.randomUUID(), "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true,
                true, 0);
        List<PharmacyCaller.Mapping> mappings = role == null ? List.of()
                : List.of(new PharmacyCaller.Mapping(store, null, role, StoreRules.access(teamRole, role, false)));
        return new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Person", teamRole, mappings);
    }

    @Test
    void purchaseIsTheOwnerOrACentralStoreUserMappedToThatStore() {
        assertThat(StoreAccessGuard.holds(caller("OWNER", null), STORE, StoreCapability.PURCHASE)).isTrue();
        assertThat(StoreAccessGuard.holds(caller("STORE_STAFF", StoreRole.CENTRAL_STORE_USER), STORE, StoreCapability.PURCHASE)).isTrue();
        assertThat(StoreAccessGuard.holds(caller("STORE_STAFF", StoreRole.CENTRAL_STORE_USER), OTHER_STORE, StoreCapability.PURCHASE)).isFalse();
        assertThat(StoreAccessGuard.holds(caller("NURSE", StoreRole.APPROVER), STORE, StoreCapability.PURCHASE)).isFalse();
        assertThat(StoreAccessGuard.holds(caller("STORE_STAFF", StoreRole.STORE_USER), STORE, StoreCapability.PURCHASE)).isFalse();
        assertThat(StoreAccessGuard.holds(caller("PHARMACIST", null), STORE, StoreCapability.PURCHASE)).isFalse();
    }

    @Test
    void adjustStockFollowsTheMappingsAccessAndTheOwnersTable() {
        assertThat(StoreAccessGuard.holds(caller("OWNER", null), STORE, StoreCapability.ADJUST_STOCK)).isTrue();
        assertThat(StoreAccessGuard.holds(caller("NURSE", StoreRole.APPROVER), STORE, StoreCapability.ADJUST_STOCK)).isTrue();
        assertThat(StoreAccessGuard.holds(caller("STORE_STAFF", StoreRole.CENTRAL_STORE_USER), STORE, StoreCapability.ADJUST_STOCK)).isTrue();
        // a plain Store user who is not a pharmacist cannot adjust, and is not mapped at another store
        assertThat(StoreAccessGuard.holds(caller("STORE_STAFF", StoreRole.STORE_USER), STORE, StoreCapability.ADJUST_STOCK)).isFalse();
        assertThat(StoreAccessGuard.holds(caller("NURSE", StoreRole.APPROVER), OTHER_STORE, StoreCapability.ADJUST_STOCK)).isFalse();
        assertThat(StoreAccessGuard.holds(caller("NURSE", StoreRole.APPROVER), null, StoreCapability.ADJUST_STOCK)).isFalse();
    }
}
